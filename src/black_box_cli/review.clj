(ns black-box-cli.review
  "`blackbox review`: a selects review for an editor. A cut list of selects (with `group:` sections) becomes a folder
  in ~/Black Box/reviews/<id>/ holding review.html (the clips on their proxies, each with its words under it; a clip
  opens with its file's whole transcript, a word jumps there, and in and out can be trimmed), review.json and a
  thumbnail per clip. `review open` serves it on 127.0.0.1 only (the owner's decision, 2026-11-24), so the page
  plays the proxies and its Import button sends the chosen clips to `online`: a new Resolve timeline on this Mac,
  from the originals, with the recorders synced. Nothing in media_meta is written; the page only reads proxies."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.db :as db]
   [black-box-cli.edl :as edl]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.picture :as picture]
   [black-box-cli.resolve :as resolve]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.httpkit.server :as http]))

(def ^:private schema "black-box-cli.review/1")

;; ------------------------------------------------------------------------------------------------ the data

(defn- file-lines
  "Every line of the file `rel` in transcript `version`: {:id :start :end :text :speaker :lang :words [[w s e]]}."
  [path version rel]
  (when-let [t (db/one path "SELECT id FROM transcripts WHERE version = :v AND rel = :rel;" {:v version :rel rel})]
    (mapv (fn [q]
            (let [words (mapv (fn [[w s e]]
                                [w (tc/round-to (or s (:start q)) 2) (tc/round-to (or e s (:end q)) 2)])
                              (json/parse-string (:words q)))]
              (array-map :id (:id q) :start (:start q) :end (:end q) :text (:text q)
                         :speaker (:speaker q) :lang (:language q) :words words)))
          (db/query path "SELECT id, start, end, text, speaker, language, words FROM cues WHERE transcript_id = :t ORDER BY n;"
                    {:t (:id t)}))))

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

(defn import!
  "The page's Import: the chosen clips ([{:n :start :end}], trimmed or not) of review `doc` as a cut list, handed to
  `online`. Its output as data, or {:error {:code :message} :help}."
  [ctx doc {:keys [clips pre post audio] place :into}]
  (try
    (let [by (into {} (map (juxt :n identity) (:clips doc)))
          picked (vec (for [{:keys [n start end]} clips
                            :let [c (by n)]
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
                :into (or place "auto") :no-audio (false? audio) :no-render true :transcripts (:transcripts doc)}
          [m help] (binding [*in* (java.io.BufferedReader. (java.io.StringReader. text))]
                     (edl/online-cmd (assoc ctx :opts opts :args {:edit "-"})))]
      (assoc m :help (vec help)))
    (catch clojure.lang.ExceptionInfo e
      (if-let [{:keys [code message help]} (out/error-of e)]
        {:error {:code code :message message} :help (vec help)}
        (throw e)))))

(defn start!
  "Serves the review in `folder` on 127.0.0.1, on a free port: the page, its proxies (only those the review names)
  and thumbnails, and /api/import. Every request needs the review's token (a link opened elsewhere can't use it) and
  this host. It stops itself 5 minutes after the page stops calling (30 if it's never opened). {:url :port :stop
  :done}, `done` a promise delivered when it stops."
  [ctx folder]
  (let [doc (json/parse-string (slurp (str (fs/path folder "review.json"))) true)
        media (vec (:media doc))
        token (apply str (repeatedly 32 #(rand-nth "0123456789abcdef")))
        port (atom nil)
        seen (atom {:at (System/currentTimeMillis) :opened false})
        done (promise)
        json-ok (fn [m] {:status 200 :headers {"content-type" "application/json" "cache-control" "no-store"}
                         :body (json/generate-string m)})
        handler (fn [{:keys [uri request-method headers query-string body]}]
                  (let [q (query-params query-string)]
                    (cond
                      (not (#{(str "127.0.0.1:" @port) (str "localhost:" @port)} (get headers "host")))
                      {:status 403 :body "not this host"}

                      (not= token (get q "t"))
                      {:status 403 :body "This review's link has a key: open it with `blackbox review open`."}

                      (and (= :get request-method) (= "/" uri))
                      {:status 200 :headers {"content-type" "text/html; charset=utf-8" "cache-control" "no-store"}
                       :body (page doc)} ; this CLI's page, so a review made earlier gets its fixes

                      (and (= :get request-method) (re-matches #"/media/\d+" uri))
                      (let [k (parse-long (subs uri 7))]
                        (if-let [p (get media k)]
                          (if (fs/exists? p) (file-response p (get headers "range")) {:status 404 :body "not on this Mac"})
                          {:status 404}))

                      (and (= :get request-method) (re-matches #"/thumbs/\d+\.jpg" uri))
                      (let [p (fs/path folder (subs uri 1))]
                        (if (fs/exists? p) (file-response p (get headers "range")) {:status 404}))

                      (and (= :post request-method) (= "/api/ping" uri))
                      (do (reset! seen {:at (System/currentTimeMillis) :opened true}) {:status 204})

                      (and (= :post request-method) (= "/api/import" uri))
                      (do (swap! seen assoc :at (System/currentTimeMillis))
                          (json-ok (try (import! ctx doc (json/parse-string (slurp body) true))
                                        (catch Exception e
                                          {:error {:code "import-failed" :message (str (.getSimpleName (class e)) ": " (ex-message e))}
                                           :help ["Tell your agent what it says; it can run `blackbox online` on the clips instead"]}))))

                      :else {:status 404})))
        server (http/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        _ (reset! port (http/server-port server))
        stop (fn [] (when-not (realized? done) (http/server-stop! server) (deliver done true)))]
    (future
      (loop []
        (Thread/sleep 5000)
        (let [{:keys [at opened]} @seen
              idle (- (System/currentTimeMillis) at)]
          (if (> idle (if opened (* 5 60 1000) (* 30 60 1000)))
            (stop)
            (when-not (realized? done) (recur))))))
    {:url (str "http://127.0.0.1:" @port "/?t=" token) :port @port :stop stop :done done}))

(defn- serve!
  "Serves the review in `folder`, opens it in the browser and waits until it stops; prints `m` first."
  [{:keys [fmt] :as ctx} folder m help]
  (let [{:keys [url done]} (start! ctx folder)]
    (out/emit! (assoc m :url url :serving "until the page has been closed for 5 minutes") help (or fmt :toon))
    (tools/spawn! ["/usr/bin/open" url])
    @done
    [nil nil]))

;; ------------------------------------------------------------------------------------------------ commands

(defn- review-help [id]
  [(str "Run `blackbox review open " id "` in the background: it opens the page in the browser, with Import into Resolve")
   "Without it, review.html opens straight from Finder to watch and read (importing needs `review open`)"])

(defn new-cmd
  "`blackbox review new <selects|->`."
  [{:keys [home opts args] :as ctx}]
  (let [{:keys [text title segments warnings version]} (edl/read-cut (assoc-in ctx [:args :cutlist] (:selects args))
                                                                     "review new")
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
        lines-in (fn [s] (count (filter #(and (> (:end %) (:start s)) (< (:start %) (:end s))) (:lines (files (:rel s))))))
        rows (vec (map-indexed (fn [i s] (row (inc i) s (lines-in s))) segments))
        groups (vec (distinct (keep :group segments)))
        head [:title title :clips (count segments) :groups (if (seq groups) groups "none")
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
          (serve! ctx folder m ["Tell the user it's open: tick clips, click one for its transcript, then Import into Resolve"])
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
  "`blackbox review open <id>`: serves a review and opens it, until its page has been closed for 5 minutes."
  [{:keys [home args] :as ctx}]
  (let [folder (find-review ctx (:id args))
        doc (json/parse-string (slurp (str (fs/path folder "review.json"))) true)]
    (serve! ctx folder
            (array-map :id (:id doc) :title (:title doc) :clips (count (:clips doc)) :folder (out/home-path folder home))
            ["Tell the user it's open: tick clips, click one for its transcript, then Import into Resolve"])))

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
