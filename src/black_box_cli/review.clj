(ns black-box-cli.review
  "`blackbox review`: a selects review for an editor. A cut list of selects (with `group:` sections) becomes a folder
  in ~/Black Box/reviews/<id>/ holding review.html (the clips on their proxies, each with its words under it; a clip
  opens with its file's whole transcript, a word jumps there, and in and out can be trimmed), review.json and a
  thumbnail per clip. `review open` serves it on 127.0.0.1 only (the owner's decision, 2026-11-24), so the page
  plays the proxies and its Import button sends the chosen clips to `online`: a new Resolve timeline on this Mac,
  from the originals, with the recorders synced. Nothing in media_meta is written; the page only reads proxies."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as hc]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.around :as around]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.edl :as edl]
   [black-box-cli.heard :as heard]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.picture :as picture]
   [black-box-cli.resolve :as resolve]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [black-box-cli.version :as version]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.httpkit.server :as http]))

(def ^:private schema "black-box-cli.review/1")

;; ------------------------------------------------------------------------------------------------ the data

(defn- file-lines
  "Every line said in the file `rel` (transcript `version`), as fully as the archive knows (black-box-cli.heard): its
  own and, where it has none, its synced recorders', each with who said it when named. Nil without a transcript."
  [path version rel]
  (:lines (heard/lines path version rel)))

(defn- fresh
  "The review `doc` as the index knows its files now, for a page served now: each file's lines again (the English
  Black Box has published since, a camera's gaps filled from its recorders, the names), and its recorders."
  [ctx doc]
  (let [path (:index (index/paths ctx))]
    (update doc :files
            (fn [files]
              (into (array-map)
                    (for [[rel f] files
                          :let [rel (if (keyword? rel) (subs (str rel) 1) (str rel))
                                now (heard/lines path (:transcripts doc) rel)]]
                      [rel (cond-> f
                             (seq (:lines now)) (assoc :lines (:lines now))
                             now (assoc :recorders (:recorders now)))]))))))

(defn- thumb!
  "A picture of the middle of segment `s` in the review's thumbs/ folder: thumbs/<n>.jpg, or nil."
  [{:keys [env] :as ctx} folder n s]
  (when-let [jpeg (picture/try-jpeg {:env env :cache (:frames (index/paths ctx)) :clip (ids/clip (:rel s))
                                     :proxy (:play_path s) :t (/ (+ (:start s) (:end s)) 2) :width 480})]
    (let [to (fs/path folder "thumbs" (str n ".jpg"))]
      (fs/create-dirs (fs/parent to))
      (fs/copy jpeg to)
      (str "thumbs/" n ".jpg"))))

(defn page
  "review.html for the review `doc`: the page, with the review in it as JSON."
  [doc]
  (str/replace (slurp (io/resource "black_box_cli/review_page.html"))
               "/*REVIEW_DATA*/"
               (-> (json/generate-string doc)
                   (str/replace "</" "<\\/")
                   (str/replace "\u2028" "\\u2028")
                   (str/replace "\u2029" "\\u2029"))))

(defn- row [n s lines]
  (array-map :n n :group (:group s) :file (:source_name s) :in (tc/hms (:start s)) :out (tc/hms (:end s))
             :length (tc/round-to (cuts/length s) 1) :lines lines))

;; ------------------------------------------------------------------------------------------------ the server

(def ^:private chunk-bytes
  "The most of a proxy one range request gets; the browser asks again for more."
  (* 8 1024 1024))

(defn- content-type [path]
  (case (some-> (re-find #"\.([A-Za-z0-9]+)$" (str path)) second str/lower-case)
    "mp4" "video/mp4" "m4v" "video/mp4" "mov" "video/quicktime" "m4a" "audio/mp4" "wav" "audio/wav"
    "mp3" "audio/mpeg" "jpg" "image/jpeg" "jpeg" "image/jpeg" "application/octet-stream"))

(defn file-response
  "A file for http-kit, honouring a Range header (video seeks with them): 200, 206 with at most chunk-bytes, or 416."
  [path range-header]
  (let [f (io/file (str path))
        size (.length f)
        ct (content-type path)
        [_ a b] (some->> range-header (re-matches #"bytes=(\d*)-(\d*)"))]
    (if-not (or (seq a) (seq b))
      {:status 200 :headers {"content-type" ct "accept-ranges" "bytes" "content-length" (str size)} :body f}
      (let [start (if (seq a) (parse-long a) (max 0 (- size (parse-long b))))
            end (min (dec size) (if (and (seq a) (seq b)) (parse-long b) (+ start chunk-bytes -1)) (+ start chunk-bytes -1))]
        (if (or (>= start size) (< end start))
          {:status 416 :headers {"content-range" (str "bytes */" size)}}
          (let [buf (byte-array (inc (- end start)))]
            (with-open [raf (java.io.RandomAccessFile. f "r")]
              (.seek raf start)
              (.readFully raf buf))
            {:status 206
             :headers {"content-type" ct "accept-ranges" "bytes" "content-length" (str (alength buf))
                       "content-range" (str "bytes " start "-" end "/" size)}
             :body buf}))))))

(defn- query-params [qs]
  (into {} (for [kv (str/split (or qs "") #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn audio-window
  "The sound of `proxy` from `from` for `dur` seconds (at most a minute) as a small WAV (mono, 24 kHz), for playing
  it backwards on the page: its bytes, or nil without ffmpeg or a sound there."
  [env proxy from dur]
  (when-let [ffmpeg (tools/find-tool env "ffmpeg")]
    (let [tmp (fs/create-temp-file {:prefix "bbcli-audio-" :suffix ".wav"})]
      (try
        (let [{:keys [exit]} (process/sh [ffmpeg "-nostdin" "-v" "error" "-ss" (tc/fixed (max 0.0 from) 3) "-t"
                                          (tc/fixed (min 60.0 (max 0.05 dur)) 3) "-i" (str proxy) "-vn" "-ac" "1"
                                          "-ar" "24000" "-c:a" "pcm_s16le" "-f" "wav" "-y" (str tmp)])]
          (when (and (zero? exit) (> (fs/size tmp) 44)) (fs/read-all-bytes tmp)))
        (finally (fs/delete-if-exists tmp))))))

(def ^:private peaks-per-second 20)

(defn peaks
  "How loud `proxy`'s sound is, 20 values a second (the mean level of each 50 ms, 0 to 1000), for the waveform under
  the open clip: JSON {per_second, peaks}. Made once with ffmpeg and kept in `cache` (this Mac's frames cache) beside
  the clip's frames; nil without ffmpeg or a sound."
  [env cache clip proxy]
  (let [out (fs/path cache (str clip "-" (fs/size proxy)) (str "peaks-" peaks-per-second ".json"))]
    (if (fs/exists? out)
      (slurp (str out))
      (when-let [ffmpeg (tools/find-tool env "ffmpeg")]
        (let [tmp (fs/create-temp-file {:prefix "bbcli-peaks-" :suffix ".raw"})]
          (try
            (let [{:keys [exit]} (process/sh [ffmpeg "-nostdin" "-v" "error" "-i" (str proxy) "-vn"
                                              "-af" (str "aeval=abs(val(0)):c=mono,aresample=" peaks-per-second)
                                              "-f" "f32le" "-y" (str tmp)])]
              (when (and (zero? exit) (pos? (fs/size tmp)))
                (let [fb (.asFloatBuffer (.order (java.nio.ByteBuffer/wrap (fs/read-all-bytes tmp))
                                                 java.nio.ByteOrder/LITTLE_ENDIAN))
                      vals (vec (for [i (range (.remaining fb))]
                                  (-> (.get fb (int i)) double (max 0.0) (min 1.0) (* 1000.0) Math/round)))
                      text (json/generate-string {:per_second peaks-per-second :peaks vals})
                      part (fs/path (fs/parent out) (str "." (fs/file-name out) ".partial-" (.pid (java.lang.ProcessHandle/current))))]
                  (fs/create-dirs (fs/parent out))
                  (spit (str part) text)
                  (try (fs/move part out {:atomic-move true :replace-existing true})
                       (finally (fs/delete-if-exists part)))
                  text)))
            (finally (fs/delete-if-exists tmp))))))))

(defn last-state
  "What the editor last did on review `folder`'s page (the last line of its state.jsonl), or nil."
  [folder]
  (let [f (fs/path folder "state.jsonl")]
    (when (fs/exists? f)
      (some->> (str/split-lines (slurp (str f))) (remove str/blank?) last
               (#(try (json/parse-string % true) (catch Exception _ nil)))))))

(def ^:private state-lock (Object.))

(defn save-state!
  "Appends the page's state `m` to review `folder`'s state.jsonl, one line, stamped: never rewritten."
  [folder m]
  (locking state-lock
    (spit (str (fs/path folder "state.jsonl"))
          (str (json/generate-string (assoc m :saved (str (java.time.Instant/now)))) "\n")
          :append true)))

(defn import!
  "The page's Import: the chosen clips ([{:n :start :end}], trimmed or not) of review `doc` as a cut list, handed to
  `online`. Its output as data, or {:error {:code :message} :help}."
  [ctx doc {:keys [clips pre post audio subtitles] place :into}]
  (try
    (let [by (into {} (map (juxt :n identity) (:clips doc)))
          index-path (:index (index/paths ctx))
          picked (vec (for [{:keys [n start end rel]} clips
                            ;; a clip of the review, or one the editor added from around a select (an original the
                            ;; index knows, with the in and out they set)
                            :let [c (or (by n)
                                        (when (and (string? rel) (number? start) (number? end) (around/known? index-path rel))
                                          {:rel rel :group "Added"}))]
                            :when c
                            :let [s (max 0.0 (double (or start (:start c))))
                                  e (min (double (or (:duration c) 1e9)) (double (or end (:end c))))]
                            :when (> e s)]
                        (cond-> (array-map :file (:rel c) :in (tc/round-to s 3) :out (tc/round-to e 3))
                          (:group c) (assoc :group (:group c))
                          (seq (:note c)) (assoc :note (:note c)))))
          _ (when (empty? picked) (out/fail! "usage" "no clips were chosen" {:help ["Tick the clips to import"]}))
          text (json/generate-string {:title (str (:title doc) " (selects)") :clips picked})
          opts {:pre (str (or pre resolve/default-handle)) :post (str (or post resolve/default-handle))
                :into (or place "auto") :no-audio (false? audio) :no-render true :transcripts (:transcripts doc)
                ;; the page's choice, else the review's (a review made before there was one: English)
                :subtitles-in (or (#{"en" "spoken"} subtitles) (:subtitles_in doc) "en")}
          [m help] (binding [*in* (java.io.BufferedReader. (java.io.StringReader. text))]
                     (edl/online-cmd (assoc ctx :opts opts :args {:edit "-"})))]
      (assoc m :help (vec help)))
    (catch clojure.lang.ExceptionInfo e
      (if-let [{:keys [code message help]} (out/error-of e)]
        {:error {:code code :message message} :help (vec help)}
        (throw e)))))

(def default-port
  "Where the review server listens unless that port is taken: the same every time, so a review's address keeps
  working across restarts."
  7387)

(def idle-hours
  "The review server stops itself after this long with no request from any page; `review open` starts it again."
  24)

(defn- read-state [path]
  (try (json/parse-string (slurp path) true) (catch Exception _ nil)))

(defn- write-state!
  "The server's state file (this CLI's own, in Application Support): written beside it, then moved into place."
  [path m]
  (fs/create-dirs (fs/parent path))
  (let [tmp (str path ".new-" (.pid (java.lang.ProcessHandle/current)))]
    (spit tmp (json/generate-string m))
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn- token-of
  "This Mac's review key, kept in the state file so open pages keep working after the server restarts; made once."
  [path]
  (or (:token (read-state path))
      (let [t (apply str (repeatedly 32 #(rand-nth "0123456789abcdef")))]
        (write-state! path {:token t})
        t)))

(defn- review-folder
  "The folder of review `id` (exactly its folder's name), or nil."
  [dir id]
  (when (and id (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" id))
    (let [f (fs/path dir id)]
      (when (fs/exists? (fs/path f "review.json")) (str f)))))

(defn start!
  "Serves every review in this Mac's reviews folder on 127.0.0.1 at /<id>/: its page, its proxies (only those it
  names), thumbnails, /<id>/api/import and /<id>/api/ping; /health says it's this server. Every request needs this
  Mac's review key and this host. On `port` (default-port; 0 for any), or any free port when that's taken. It stops
  itself after idle-hours with no request, or on POST /api/stop. {:port :token :stop :done}."
  ([ctx] (start! ctx default-port))
  ([ctx port]
   (let [{:keys [reviews review-server]} (index/paths ctx)
         token (token-of review-server)
         bound (atom nil)
         seen (atom (System/currentTimeMillis))
         done (promise)
         stopper (atom nil)
         json-ok (fn [m] {:status 200 :headers {"content-type" "application/json" "cache-control" "no-store"}
                          :body (json/generate-string m)})
         handler
         (fn [{:keys [uri request-method headers query-string body]}]
           (let [q (query-params query-string)
                 [_ id rest*] (re-matches #"/([^/]+)(/.*)?" uri)
                 folder (review-folder reviews id)]
             (cond
               (not (#{(str "127.0.0.1:" @bound) (str "localhost:" @bound)} (get headers "host")))
               {:status 403 :body "not this host"}

               (not= token (get q "t"))
               {:status 403 :body "This review's link has a key: open it with `blackbox review open <id>`."}

               :else
               (do
                 (reset! seen (System/currentTimeMillis))
                 (cond
                   (and (= :get request-method) (= "/health" uri))
                   (json-ok {:server "black-box-cli.review" :cli version/version :pid (.pid (java.lang.ProcessHandle/current))})

                   (and (= :post request-method) (= "/api/stop" uri))
                   (do (future (Thread/sleep 100) (@stopper)) {:status 204})

                   (nil? folder) {:status 404 :body "no such review on this Mac"}

                   (nil? rest*) {:status 302 :headers {"location" (str "/" id "/?t=" token)}}

                   :else
                   (let [doc (json/parse-string (slurp (str (fs/path folder "review.json"))) true)
                         media (vec (:media doc))]
                     (cond
                       (and (= :get request-method) (= "/" rest*))
                       {:status 200 :headers {"content-type" "text/html; charset=utf-8" "cache-control" "no-store"}
                        :body (page (fresh ctx doc))} ; this CLI's page, so a review made earlier gets its fixes

                       (and (= :get request-method) (re-matches #"/media/\d+" rest*))
                       (if-let [p (get media (parse-long (subs rest* 7)))]
                         (if (fs/exists? p) (file-response p (get headers "range")) {:status 404 :body "not on this Mac"})
                         {:status 404})

                       (and (= :get request-method) (re-matches #"/thumbs/\d+\.jpg" rest*))
                       (let [p (fs/path folder (subs rest* 1))]
                         (if (fs/exists? p) (file-response p (get headers "range")) {:status 404}))

                       (and (= :post request-method) (= "/api/ping" rest*))
                       {:status 204}

                       ;; around a select: the clips its camera shot before and after, each file's words, its proxy,
                       ;; a still; only of originals the index knows, and only their proxies (never the NAS)
                       ;; the strip grows as it's scrolled: each side asked for on its own (n for both)
                       (and (= :get request-method) (= "/api/around" rest*))
                       (let [n (or (some-> (get q "n") parse-long) 3)
                             side (fn [k] (min 400 (max 0 (or (some-> (get q k) parse-long) n))))]
                         (json-ok (or (around/around {:meta (:meta ctx) :index-path (:index (index/paths ctx))
                                                      :version (:transcripts doc)}
                                                     (get q "rel") (side "before") (side "after"))
                                      {:clips [] :more_before false :more_after false})))

                       (and (= :get request-method) (= "/api/file" rest*))
                       (let [rel (get q "rel") path (:index (index/paths ctx))]
                         (if (around/known? path rel)
                           (let [now (heard/lines path (:transcripts doc) rel)]
                             (json-ok {:rel rel :lines (or (:lines now) []) :recorders (or (:recorders now) [])}))
                           {:status 404}))

                       ;; a few seconds of a clip's sound, for playing it backwards (J): from its proxy only
                       (and (= :get request-method) (= "/audio" rest*))
                       (let [rel (get q "rel")
                             p (when (around/known? (:index (index/paths ctx)) rel) (archive/proxy-of (:meta ctx) rel))
                             wav (when p (audio-window (:env ctx) p (or (parse-double (get q "from" "0")) 0.0)
                                                       (or (parse-double (get q "dur" "6")) 6.0)))]
                         (if wav
                           {:status 200 :headers {"content-type" "audio/wav" "cache-control" "no-store"} :body wav}
                           {:status 404 :body "no sound of that here"}))

                       ;; how loud a clip is along its length, for the waveform under the open clip
                       (and (= :get request-method) (= "/api/peaks" rest*))
                       (let [rel (get q "rel")
                             p (when (around/known? (:index (index/paths ctx)) rel) (archive/proxy-of (:meta ctx) rel))
                             text (when p (peaks (:env ctx) (:frames (index/paths ctx)) (ids/clip rel) p))]
                         (if text
                           {:status 200 :headers {"content-type" "application/json" "cache-control" "no-store"} :body text}
                           {:status 404 :body "no sound of that here"}))

                       (and (= :get request-method) (#{"/proxy" "/thumb"} rest*))
                       (let [rel (get q "rel")
                             p (when (around/known? (:index (index/paths ctx)) rel) (archive/proxy-of (:meta ctx) rel))]
                         (cond
                           (nil? p) {:status 404 :body "no proxy of that on this Mac"}
                           (= "/proxy" rest*) (file-response p (get headers "range"))
                           :else (if-let [jpeg (picture/try-jpeg {:env (:env ctx) :cache (:frames (index/paths ctx))
                                                                  :clip (ids/clip rel) :proxy p :width 320
                                                                  :t (or (parse-double (get q "at" "0")) 0)})]
                                   (file-response jpeg nil)
                                   {:status 404})))

                       ;; what the editor has done on the page (selected, trimmed, added), a snapshot a line, appended:
                       ;; review.json stays as it was made, and the last line is the state
                       (and (= :get request-method) (= "/api/state" rest*))
                       (json-ok (or (last-state folder) {}))

                       (and (= :post request-method) (= "/api/state" rest*))
                       (let [m (try (json/parse-string (slurp body) true) (catch Exception _ nil))]
                         (if (and (map? m) (< (count (json/generate-string m)) 1000000))
                           (do (save-state! folder m) {:status 204})
                           {:status 400 :body "a state is a JSON object"}))

                       (and (= :post request-method) (= "/api/import" rest*))
                       (json-ok (try (import! ctx doc (json/parse-string (slurp body) true))
                                     (catch Exception e
                                       {:error {:code "import-failed" :message (str (.getSimpleName (class e)) ": " (ex-message e))}
                                        :help ["Tell your agent what it says; it can run `blackbox online` on the clips instead"]})))

                       :else {:status 404})))))))
         run (fn [p] (http/run-server handler {:ip "127.0.0.1" :port p :legacy-return-value? false}))
         server (try (run port) (catch java.net.BindException _ (run 0)))
         _ (reset! bound (http/server-port server))
         stop (fn [] (when-not (realized? done) (http/server-stop! server) (deliver done true)))]
     (reset! stopper stop)
     (future
       (loop []
         (Thread/sleep 5000)
         (if (> (- (System/currentTimeMillis) @seen) (* idle-hours 3600 1000))
           (stop)
           (when-not (realized? done) (recur)))))
     {:port @bound :token token :stop stop :done done})))

(defn url
  "Review `id`'s address on the server at `port` with `token`."
  [port token id]
  (str "http://127.0.0.1:" port "/" id "/?t=" token))

(defn- health
  "What the server at `port` says it is, with `token`, or nil when nothing (or something else) answers."
  [port token]
  (try
    (let [r (hc/get (str "http://127.0.0.1:" port "/health?t=" token)
                    {:throw false :timeout 2000 :client (hc/client {:connect-timeout 1000})})]
      (when (= 200 (:status r))
        (let [m (json/parse-string (:body r) true)]
          (when (= "black-box-cli.review" (:server m)) m))))
    (catch Exception _ nil)))

(defn- post-stop! [port token]
  (try (hc/post (str "http://127.0.0.1:" port "/api/stop?t=" token)
                {:throw false :timeout 2000 :client (hc/client {:connect-timeout 1000})})
       (catch Exception _ nil)))

(defn self-argv
  "The command line that runs this blackbox: Black Box's launcher ($BLACKBOX_BIN), else this process's own, less
  the arguments it was given; with the global flags that say where things are."
  [{:keys [env argv meta local]}]
  (let [base (or (when-let [b (get env "BLACKBOX_BIN")] (when (fs/executable? b) [b]))
                 (let [info (.info (java.lang.ProcessHandle/current))
                       cmd (.orElse (.command info) nil)
                       all (vec (.orElse (.arguments info) (into-array String [])))
                       n (count argv)]
                   (when (and cmd (>= (count all) n) (= (vec argv) (subvec all (- (count all) n))))
                     (into [cmd] (subvec all 0 (- (count all) n))))))]
    (when base
      (cond-> base
        meta (into ["--meta" (str meta)])
        local (into ["--local" (str local)])))))

(defn ensure-server!
  "The review server on this Mac, running this CLI's version: {:port :token :started bool}. A running one is kept
  (it serves every review, so pages already open keep working); an older version's is asked to stop and replaced on
  the same port. A new one is started on its own, apart from this command and whatever ran it, so it outlives both."
  [ctx]
  (let [{:keys [review-server]} (index/paths ctx)
        token (token-of review-server)
        {:keys [port]} (read-state review-server)
        port (or port default-port)
        running (health port token)]
    (if (= version/version (:cli running))
      {:port port :token token :started false}
      (do
        (when running
          (post-stop! port token)
          (loop [i 0] (when (and (< i 30) (health port token)) (Thread/sleep 100) (recur (inc i)))))
        (let [argv (or (self-argv ctx)
                       (out/fail! "review-server" "couldn't tell how to start the review server (no $BLACKBOX_BIN)"
                                  {:help ["Run `blackbox review serve` in the background yourself, then open the review again"]}))]
          (tools/spawn-detached! (into argv ["review" "serve" "--port" (str port)])
                                 (str (fs/path (fs/parent review-server) "review-server.log")))
          (loop [i 0]
            (let [{p :port} (read-state review-server)]
              (cond
                (and p (= version/version (:cli (health p token)))) {:port p :token token :started true}
                (< i 100) (do (Thread/sleep 100) (recur (inc i)))
                :else (out/fail! "review-server" "the review server didn't start within 10 s"
                                 {:help [(str "See " (out/home-path (str (fs/path (fs/parent review-server) "review-server.log")) (:home ctx)))
                                         "Run `blackbox review serve` yourself to see why"]})))))))))

(defn- open!
  "Review `id` in the browser, from the review server (started when it isn't running): what to print about it."
  [ctx id]
  (let [{:keys [port token started]} (ensure-server! ctx)
        u (url port token id)]
    (tools/spawn! ["/usr/bin/open" u])
    {:url u :server (str (if started "started" "already running") " on 127.0.0.1:" port
                         ", for every review; pages already open keep working")}))

(def ^:private open-help
  ["Tell the user it's open: tick clips, click one for its transcript, then Import into Resolve"
   "Earlier reviews stay open and working in their tabs; never stop the review server"])

;; ------------------------------------------------------------------------------------------------ commands

(defn- review-help [id]
  [(str "Run `blackbox review open " id "`: it opens the page in the browser, with Import into Resolve")
   "Without it, review.html opens straight from Finder to watch and read (importing needs `review open`)"])

(defn new-cmd
  "`blackbox review new <selects|->`."
  [{:keys [home opts args] :as ctx}]
  (let [{:keys [text title segments warnings version]} (edl/read-cut (assoc-in ctx [:args :cutlist] (:selects args))
                                                                     "review new")
        ;; the editor's language (what the page reads in; --language is its old name), and the subtitles' in Resolve
        language (or (:language opts) (:read-in opts) "en")
        _ (when-not (re-matches #"[a-z]{2,3}" language)
            (out/fail! "usage" (str "--read-in takes a language's code, like en or it, not " language)
                       {:help ["Run `blackbox review new --help`"]}))
        subs-in (or (:subtitles-in opts) "en")
        _ (when-not (resolve/subtitle-languages subs-in)
            (out/fail! "usage" (str "--subtitles-in " subs-in " isn't available: subtitles are in English (en) or as spoken"
                                    " (spoken); other languages wait for translations into them")
                       {:help ["Run `blackbox review new --help`"]}))
        path (index/require-index! ctx)
        no-proxy (keep-indexed (fn [i s] (when-not (:play_path s)
                                           (str "clip " (inc i) " (" (:source_name s) "): no proxy, left out of the review")))
                               segments)
        segments (filterv :play_path segments)
        _ (when (empty? segments)
            (out/fail! "no-clips" "none of the clips has a proxy to play" {:help ["Make the proxies available offline in Finder"]}))
        warnings (into (vec warnings) no-proxy)
        files (into {} (for [rel (distinct (map :rel segments))]
                         [rel (array-map :name (str (fs/file-name rel))
                                         :duration (:duration (first (filter #(= rel (:rel %)) segments)))
                                         :lines (or (file-lines path version rel) []))]))
        in-range (fn [s] (filter #(and (> (:end %) (:start s)) (< (:start %) (:end s))) (:lines (files (:rel s)))))
        lines-in (comp count in-range)
        ;; a clip is in another language when most of its lines whose language is known are
        elsewhere (fn [s] (let [known (keep :lang (in-range s))]
                            (and (seq known) (> (* 2 (count (remove #{language} known))) (count known)))))
        rows (vec (map-indexed (fn [i s] (row (inc i) s (lines-in s))) segments))
        groups (vec (distinct (keep :group segments)))
        head [:title title :clips (count segments) :groups (if (seq groups) groups "none")
              :read_in language :subtitles_in subs-in :in_another_language (count (filter elsewhere segments))
              :warnings (if (seq warnings) warnings "none")]]
    (if (:dry-run opts)
      [(apply array-map (concat head [:written "nothing (--dry-run)" :review rows]))
       ["Run the same without --dry-run to make the review"]]
      (let [folder (cuts/new-folder! (:reviews (index/paths ctx)) (str (cuts/stamp) "-" (cuts/slug title)))
            id (str (fs/file-name folder))
            media (vec (distinct (map :play_path segments)))
            thumbs (vec (pmap (fn [[i s]] (thumb! ctx folder (inc i) s)) (map-indexed vector segments)))
            doc (array-map
                 :schema schema :id id :title title :made_at (str (java.time.Instant/now)) :transcripts version
                 :read_in language :subtitles_in subs-in
                 :groups groups :warnings warnings :media media
                 :clips (vec (map-indexed
                              (fn [i s]
                                (array-map :n (inc i) :group (:group s) :note (not-empty (:why s))
                                           :file (:source_name s) :rel (:rel s) :media (.indexOf ^java.util.List media (:play_path s))
                                           :audio_only (not (resolve/video? s))
                                           :start (:start s) :end (:end s) :duration (:duration s)
                                           :tc_in (tc/source-tc s (:start s) (cuts/timeline-rate [s]))
                                           :thumb (nth thumbs i)))
                              segments))
                 :files files
                 :cutlist (subs text 0 (min 20000 (count text))))
            _ (cuts/write-new! (fs/path folder "review.json") (json/generate-string doc {:pretty true}))
            _ (cuts/write-new! (fs/path folder "review.html") (page doc))
            m (apply array-map (concat head [:id id :folder (out/home-path folder home) :page "review.html" :review rows]))]
        (if (:open opts)
          [(merge m (open! ctx id)) open-help]
          [m (review-help id)])))))

(defn- reviews [ctx]
  (let [dir (:reviews (index/paths ctx))]
    (if (fs/directory? dir)
      (->> (fs/glob dir "*/review.json") (map (comp str fs/parent)) (sort) (reverse) vec)
      [])))

(defn- find-review [ctx id]
  (let [all (reviews ctx)
        hits (or (some #(when (= id (str (fs/file-name %))) [%]) all)
                 (filterv #(str/includes? (str (fs/file-name %)) id) all))]
    (when (not= 1 (count hits))
      (out/fail! (if (empty? hits) "not-found" "ambiguous")
                 (if (empty? hits) (str "no review matching '" id "'") (str (count hits) " reviews match '" id "'"))
                 {:help ["Run `blackbox review list` for ids"]}))
    (first hits)))

(defn open-cmd
  "`blackbox review open <id>`: opens a review in the browser from the review server, and returns at once."
  [{:keys [home args] :as ctx}]
  (let [folder (find-review ctx (:id args))
        doc (json/parse-string (slurp (str (fs/path folder "review.json"))) true)]
    [(merge (array-map :id (:id doc) :title (:title doc) :clips (count (:clips doc)) :folder (out/home-path folder home))
            (open! ctx (str (fs/file-name folder))))
     open-help]))

(defn serve-cmd
  "`blackbox review serve`: the review server itself, in this process, until it stops (`review open` starts it on its
  own; this is for seeing why it won't)."
  [{:keys [opts] :as ctx}]
  (let [{:keys [review-server]} (index/paths ctx)
        {:keys [port token done]} (start! ctx (or (:port opts) default-port))]
    (write-state! review-server {:token token :port port :cli version/version
                                 :pid (.pid (java.lang.ProcessHandle/current))})
    (out/emit! (array-map :serving (str "127.0.0.1:" port) :reviews (out/home-path (:reviews (index/paths ctx)) (:home ctx))
                          :stops (str "after " idle-hours " h with no page calling"))
               [] (or (:fmt ctx) :toon))
    @done
    [nil nil]))

(defn list-cmd
  "`blackbox review list`."
  [{:keys [home opts] :as ctx}]
  (let [rows (vec (for [f (reviews ctx)
                        :let [doc (try (json/parse-string (slurp (str (fs/path f "review.json"))) true) (catch Exception _ nil))]
                        :when doc]
                    (array-map :id (:id doc) :title (:title doc) :clips (count (:clips doc))
                               :made (:made_at doc) :folder (out/home-path f home))))]
    (if (empty? rows)
      [(array-map :count 0 :result "no reviews yet") ["Run `blackbox review new <selects|->` to make one"]]
      [(array-map :count (min (:limit opts) (count rows)) :total (count rows)
                  :reviews (mapv #(out/pick % [:id :title :clips] opts) (take (:limit opts) rows)))
       ["Run `blackbox review open <id>` in the background to open one"]])))
