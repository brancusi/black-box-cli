(ns black-box-cli.look
  "The commands over the picture: `frame` (a JPEG from the proxy), `look` (frames like some words) and `like`
  (frames like a frame), over this Mac's picture index (black-box-cli.frames). `look`'s words become a vector in
  Black Box, which runs the model (docs/DESIGN.md, decision 6); everything else is here. Each builds plain data for
  `out`."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.picture :as picture]
   [black-box-cli.preview :as preview]
   [black-box-cli.shots :as shots]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [black-box-cli.who :as who]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(defn- frame-tc
  "A frame's source timecode, when its file has a timecode and a rate."
  [{:keys [start_tc rate]} t]
  (when (and start_tc (tc/parse-rate rate))
    (tc/source-tc {:clock_kind "timecode" :start_tc start_tc :rate rate} t)))

(defn- frame-proxy
  "The proxy a frames file was made from, in media_meta, or the clip's proxy found by its path."
  [meta {:keys [proxy_rel rel]}]
  (when meta
    (or (when proxy_rel (let [p (fs/path meta "proxies" proxy_rel)] (when (fs/exists? p) (str p))))
        (archive/proxy-of meta rel))))

;; ------------------------------------------------------------------------------------------------ frame

(defn- line-target
  "What `frame <line id>` shows: the line's file at the line's start."
  [path id]
  (let [{:keys [clip cue]} (ids/parse id)
        r (db/one path "SELECT t.*, c.id AS line_id, c.start AS line_start FROM cues c
                        JOIN transcripts t ON t.id = c.transcript_id
                        WHERE t.version = :version AND t.clip = :clip AND c.n = :n;"
                  {:version index/default-version :clip clip :n cue})]
    (when-not r
      (out/fail! "not-found" (str "no line " clip "." cue " in the index")
                 {:help ["Run `blackbox search <words>` for line ids"]}))
    {:id (:line_id r) :t (:line_start r) :rel (:rel r) :clip (:clip r) :name (:source_name r)
     :sha256 (:source_sha256 r) :duration (:duration r) :tc (tc/source-tc r (:line_start r))}))

(defn- file-target
  "What `frame <file> --at T` shows."
  [path opts file]
  (let [t (archive/find-file path opts file)
        at (if (:at opts) (cutlist/parse-time (:at opts)) 0.0)]
    (when (and (:duration t) (>= at (:duration t)))
      (out/fail! "bad-time" (str "--at " (:at opts) " is past the end of " (:source_name t) " ("
                                 (tc/hms (:duration t)) ")")))
    {:t at :rel (:rel t) :clip (:clip t) :name (:source_name t) :sha256 (:source_sha256 t) :duration (:duration t)
     :tc (tc/source-tc t at)}))

(defn- frame-target
  "What `frame <frame id>` shows: the frame itself, at the time it was embedded."
  [{:keys [home] :as ctx} id]
  (let [{:keys [db]} (frames/require-index! (:index (index/paths ctx)) home)
        {:keys [file n t pts]} (frames/lookup db id)]
    {:id (frames/frame-id (:clip file) n) :t t :seek pts :rel (:rel file) :clip (:clip file)
     :name (:source_name file) :sha256 (:source_sha256 file) :duration (:duration file) :tc (frame-tc file t)
     :file file}))

(defn transcript-of
  "The transcript of the original at `rel` (its clock, name and sha256), or nil."
  [path rel]
  (db/one path "SELECT * FROM transcripts WHERE version = :version AND rel = :rel;"
          {:version index/default-version :rel rel}))

(defn- shot-target
  "What `frame <shot id>` shows: the middle of the shot's best moment."
  [{:keys [home] :as ctx} path id]
  (let [r (shots/lookup (:index (index/paths ctx)) home id)
        [a b] (shots/best r)
        t (tc/round-to (/ (+ a b) 2) 3)
        tr (transcript-of path (:rel r))]
    {:id (:id r) :t t :rel (:rel r) :clip (:clip r) :name (or (:source_name tr) (str (fs/file-name (:rel r))))
     :sha256 (or (:source_sha256 tr) (:sha256 r)) :duration (or (:duration tr) (:duration r))
     :tc (when tr (tc/source-tc tr t))}))

(defn kind-of
  "What `what` names: :frame (a frame id), :shot (a shot id), :line (a line id) or :file."
  [what]
  (cond (frames/frame-id? what) :frame
        (shots/shot-id? what) :shot
        (re-matches #"(?i)[a-z2-7]{8}\.\d+" what) :line
        :else :file))

(defn target
  "The moment `what` names, a frame id, a shot id (its best moment), a line id or a file (at `(:at opts)`, default
  0): {:t :rel :clip :name :sha256 :tc ...}. error.code not-found, bad-time or no-frames otherwise."
  [ctx path opts what]
  (case (kind-of what)
    :frame (frame-target ctx what)
    :shot (shot-target ctx path what)
    :line (line-target path what)
    (file-target path opts what)))

(defn frame-cmd
  "`blackbox frame <line id|frame id|shot id|file> [--at T]`."
  [{:keys [meta home env opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        what (str/trim (:what args))
        kind (kind-of what)
        _ (when (and (not= :file kind) (:at opts))
            (out/fail! "usage" "--at is for a file; a line, frame or shot id has its own time"
                       {:help ["Run `blackbox frame <file> --at <time>` for any moment of a file"]}))
        {:keys [id t seek rel clip name file sha256] :as target} (target ctx path opts what)
        proxy (or (if file (frame-proxy meta file) (archive/proxy-of meta rel))
                  (out/fail! "no-proxy" (str "no proxy for " rel " in media_meta")
                             {:help ["Proxies come from the Mac mini; a clip without one has no pictures here"]}))
        ;; A recorder has no picture: show a camera heard with it, as the rough cut does.
        cam (when (and (not file) (preview/audio-only? env proxy))
              (or (preview/camera-picture {:env env :index-path path :meta meta}
                                          {:rel rel :sha256 sha256 :t t :from (when (= :line kind) id)})
                  (out/fail! "audio-only" (str name " is sound only (a recorder) and no camera was heard with it at "
                                               (tc/hms t))
                             {:help [(if (= :line kind)
                                       (str "Run `echo '" id "' | blackbox edl new - --title <title> --play` to hear it")
                                       (str "Run `blackbox transcript " name "` for what was said"))]})))
        [pic-rel pic-t pic-clip pic-proxy]
        (if cam
          (let [ct (tc/round-to (:start cam) 3)]
            [(:camera_rel cam) ct (or (:clip (transcript-of path (:camera_rel cam))) (subs (:camera_sha256 cam) 0 8))
             (:path cam)])
          [rel t clip proxy])
        jpeg (picture/jpeg! {:env env :cache (:frames (index/paths ctx)) :clip pic-clip :proxy pic-proxy
                             :t (if cam pic-t (or seek t)) :width (:width opts)})
        changed? (and file (:proxy_size file) (not= (:proxy_size file) (fs/size proxy)))
        faces (who/faces-at path pic-rel pic-t)
        shot (shots/at path pic-rel pic-t)]
    [(apply array-map (concat (when id [:id id])
                              [:file name :rel rel :at (tc/hms t) :tc (:tc target)]
                              (when cam [:picture (array-map :camera (:camera cam) :rel pic-rel :at (tc/hms pic-t)
                                                             :why "a recorder has no picture: a camera heard with it")])
                              [:jpeg (out/home-path jpeg home)]
                              (when faces [:faces (if (seq faces) faces "no face on screen")])
                              (when shot [:shot (shots/brief shot)])
                              (when changed?
                                [:warning "the proxy has changed since it was embedded; this may not be the same frame"])))
     (cond-> [(str "Run `open " (out/shell-quoted jpeg) "` to see it")]
       (= :frame kind) (conj (str "Run `blackbox like " id "` for frames that look like it"))
       (= :line kind) (conj (str "Run `blackbox line " id "` for what was said around it"))
       (= :shot kind) (conj (str "Run `echo '" id "' | blackbox edl new - --title <title> --play` to play its best moment"))
       (= :file kind) (conj (str "Run `blackbox frame " what " --at <time>` for another moment"))
       (seq faces) (conj (str "Run `blackbox whose " (:id (first faces)) "` for who a face may be")))]))

;; ------------------------------------------------------------------------------------------------ look, like

(defn- nfc [s] (Normalizer/normalize (str s) Normalizer$Form/NFC))

(defn- in-scope?
  "Whether a frames file's original is in the scope of --month, --day, --path and --video."
  [{:keys [month day path video]} {:keys [rel]}]
  (let [d (or (archive/day-of rel) "")]
    (and (or (empty? month) (some #(str/starts-with? d %) month))
         (or (empty? day) (some #(= d %) day))
         (or (nil? path) (str/includes? rel (nfc path)))
         (or (not video) (some #(str/ends-with? (str/lower-case rel) (str "." %)) archive/video-exts)))))

(defn- scope-words [{:keys [day month path video]}]
  (let [parts (cond-> (vec (concat day month)) path (conj (str "path~" path)) video (conj "video only"))]
    (if (seq parts) (str/join ", " parts) "everything")))

(defn- query-vector
  "Black Box's vector for `words`, checked against the frames' model and label (decision 6)."
  [env words files dim]
  (let [app (or (tools/black-box env)
                (out/fail! "no-black-box" (str "Black Box isn't installed at "
                                               (get env "BLACKBOX_APP" tools/black-box-app)
                                               "; it turns look's words into a vector")
                           {:help ["Install Black Box (0.30.0 or later), or set BLACKBOX_APP to its binary"
                                   "Run `blackbox like <frame id>` meanwhile: it needs no model"]}))
        {:keys [exit out err]} (process/sh app "--cli" "embed" "query" words "--format" "json")
        doc (try (json/parse-string out true) (catch Exception _ nil))]
    (when-not (and (zero? exit) (sequential? (:vector doc)))
      (out/fail! "black-box-failed" (str "Black Box couldn't make the query vector: "
                                         (or (get-in doc [:error :message])
                                             (first (remove str/blank? (str/split-lines (str out err)))) "no answer"))
                 {:help ["Open Black Box once (it installs its model), then run it again"]}))
    (let [models (set (keep :model files))]
      (when-not (and (= frames/label (:label doc)) (= dim (count (:vector doc)))
                     (or (empty? models) (= #{(:model doc)} models)))
        (out/fail! "model-mismatch" (str "Black Box's query is " (:model doc) " (" (:label doc) ", " (count (:vector doc))
                                         " values) but the frames are " (str/join ", " models) " (" frames/label
                                         ", " dim " values)")
                   {:help ["Update Black Box, or ask the owner which model the mini made the frames with"]})))
    (float-array (:vector doc))))

(defn- review-page!
  "A page of the hits with their frames, written new under `dir`; its path."
  [dir title rows jpegs]
  (let [esc #(-> (str %) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
                 (str/replace "\"" "&quot;"))
        cards (apply str (map (fn [r j]
                                (str "<figure>" (if j (str "<img src=\"" (esc (picture/url j)) "\" loading=\"lazy\">")
                                                    "<div class=none>no proxy</div>")
                                     "<figcaption><b>" (esc (:file r)) "</b> " (esc (:at r)) " <span>" (esc (:tc r))
                                     "</span><br><small>" (esc (:id r)) " · " (:score r) " · " (esc (:day r))
                                     "</small></figcaption></figure>\n"))
                              rows jpegs))
        html (str "<!doctype html><meta charset=\"utf-8\"><title>" (esc title) "</title>
<style>body{font:13px -apple-system,sans-serif;background:#16171a;color:#e8e6e1;margin:24px}h1{font-size:18px;font-weight:600}
main{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px}figure{margin:0}img{width:100%;border-radius:4px;display:block}
figcaption{padding:6px 2px}span,small{color:#9a978f}.none{aspect-ratio:16/9;background:#2a2b2f;display:grid;place-items:center}</style>
<h1>" (esc title) "</h1><main>\n" cards "</main>\n")
        page (fs/path dir (str (cuts/stamp) "-" (cuts/slug title) ".html"))]
    (fs/create-dirs dir)
    (cuts/write-new! page html)
    (str page)))

(defn- search
  "`look` and `like`: every frame in scope scored against `q`, the best shown."
  [{:keys [meta home env opts] :as ctx} {:keys [db bin rows dim]} files q query exclude]
  (let [scope-files (filterv #(in-scope? opts %) files)
        m (frames/read-floats bin)
        _ (when (not= (alength m) (* rows dim))
            (out/fail! "index-error" "the picture index's vectors don't match it" {:help ["Run `blackbox index --rebuild`"]}))
        scored (frames/scores m q dim rows (map (juxt :first_row :count) scope-files))
        hits (frames/top scored scope-files {:limit (:limit opts) :per-clip (:per-clip opts) :exclude exclude})
        items (when (seq hits)
                (into {} (map (juxt (juxt :file_id :n) identity))
                      (db/query db "SELECT i.file_id, i.n, i.t, i.pts, i.frame FROM json_each(:keys) k
                                    JOIN frame_items i ON i.file_id = k.value->>0 AND i.n = k.value->>1;"
                                {:keys (json/generate-string (map (fn [[r _ f]] [(:id f) (inc (- r (:first_row f)))])
                                                                  hits))})))
        logged (shots/available? (:index (index/paths ctx)))
        shot-of (zipmap (map (fn [[r _ f]] [(:id f) r]) hits)
                        (shots/at-each (:index (index/paths ctx))
                                       (map (fn [[r _ f]] [(:rel f) (:t (items [(:id f) (inc (- r (:first_row f)))]))])
                                            hits)))
        rows (mapv (fn [[r s f]]
                     (let [n (inc (- r (:first_row f)))
                           {:keys [t frame]} (items [(:id f) n])
                           shot (shot-of [(:id f) r])]
                       (apply array-map
                              (concat
                               [:id (frames/frame-id (:clip f) n)
                                :day (archive/day-of (:rel f))
                                :file (:source_name f)
                                :at (tc/hms t)
                                :tc (frame-tc f t)
                                :score (tc/round-to s 4)]
                               (when logged
                                 [:shot (:id shot) :camera (:camera shot) :action (:action shot)])
                               [:rel (:rel f)
                                :seconds t
                                :frame frame
                                :proxy (some-> (frame-proxy meta f) (out/home-path home))]))))
                   hits)
        shown (mapv #(out/pick % (cond-> [:id :day :file :at :tc :score] logged (conj :camera :action)) opts) rows)
        page (when (and (seq rows) (or (:html opts) (:open opts)))
               (let [cache (:frames (index/paths ctx))
                     jpegs (vec (pmap (fn [[r _ f]]
                                        (let [n (inc (- r (:first_row f)))]
                                          (picture/try-jpeg {:env env :cache cache :clip (:clip f)
                                                             :proxy (frame-proxy meta f) :t (:pts (items [(:id f) n]))
                                                             :width 480})))
                                      hits))]
                 (review-page! (:pages (index/paths ctx)) query rows jpegs)))]
    (when (and page (:open opts)) (tools/spawn! ["/usr/bin/open" page]))
    [(apply array-map (concat [:query query
                               :scope (scope-words opts)
                               :frames_searched (reduce + (map :count scope-files))
                               :count (count shown)
                               :frames (if (seq shown) shown "no frames in scope")]
                              (when page [:page (out/home-path page home)])))
     (if (seq shown)
       (cond-> []
         (and page (not (:open opts))) (conj (str "Run `open " (out/shell-quoted page) "` to see the page"))
         true (conj (str "Run `blackbox frame " (:id (first shown) "<id>") "` for a frame as a JPEG")
                    "Run `blackbox like <id>` for frames that look like it"
                    "Run `blackbox transcript <file>` for what was said there"))
       ["Run `blackbox footage` for what's in the archive, or widen the scope"])]))

(defn- files-of [db]
  (db/query db "SELECT id, rel, clip, source_name, start_tc, rate, proxy_rel, proxy_size, model, first_row, count
                FROM frame_files ORDER BY rel;"))

(defn look-cmd
  "`blackbox look <words…>`."
  [{:keys [home env args] :as ctx}]
  (let [idx (frames/require-index! (:index (index/paths ctx)) home)
        words (str/join " " (remove str/blank? (:words args)))
        _ (when (str/blank? words) (out/fail! "usage" "no words to look for" {:help ["Run `blackbox look <words>`"]}))
        files (files-of (:db idx))
        q (query-vector env words files (:dim idx))]
    (search ctx idx files q words nil)))

(defn like-cmd
  "`blackbox like <frame id>`; a line id or a face id goes to voices or faces (black-box-cli.who)."
  [{:keys [home args] :as ctx}]
  (if-not (frames/frame-id? (:id args))
    (who/like-cmd ctx)
    (let [{:keys [db bin dim] :as idx} (frames/require-index! (:index (index/paths ctx)) home)
          {:keys [file n t row]} (frames/lookup db (:id args))
          m (frames/read-floats bin)
          q (float-array dim)]
      (System/arraycopy m (* row dim) q 0 dim)
      (search ctx idx (files-of db) q
              (str (frames/frame-id (:clip file) n) " (" (:source_name file) " at " (tc/hms t) ")")
              (:id file)))))

;; ------------------------------------------------------------------------------------------------ shots

(defn- shots-scope
  "The SQL conditions on shots `s` and clips `c` for the flags in `opts`, and their parameters."
  [{:keys [month day path camera framing clean file]}]
  (let [dates (concat (map (fn [d] (let [[y m dd] (str/split d #"-")] (str "%/" y "/" m "/" dd "/%"))) day)
                      (map (fn [mo] (let [[y m] (str/split mo #"-")] (str "%/" y "/" m "/%"))) month))
        dkeys (map-indexed (fn [i _] (keyword (str "d" i))) dates)
        fkeys (map-indexed (fn [i _] (keyword (str "f" i))) framing)]
    {:where (cond-> []
              (seq dates) (conj (str "(" (str/join " OR " (map #(str "('/' || c.rel) LIKE :" (name %)) dkeys)) ")"))
              path (conj "c.rel LIKE :path")
              file (conj "(c.rel LIKE :file_end OR c.rel LIKE :file_part)")
              camera (conj "lower(s.camera) LIKE :camera")
              (seq framing) (conj (str "lower(s.framing) IN (" (str/join ", " (map #(str ":" (name %)) fkeys)) ")"))
              clean (conj "s.issues = '[]'"))
     :params (cond-> (merge (zipmap dkeys dates) (zipmap fkeys (map (comp str/lower-case str/trim) framing)))
               path (assoc :path (str "%" (nfc path) "%"))
               file (assoc :file_end (str "%/" (nfc (str/trim file))) :file_part (str "%" (nfc (str/trim file)) "%"))
               camera (assoc :camera (str "%" (str/lower-case (str/trim camera)) "%")))}))

(defn- shots-scope-words [{:keys [camera framing clean file] :as opts}]
  (let [parts (cond-> []
                (not= "everything" (scope-words opts)) (conj (scope-words opts))
                file (conj (str "file~" file))
                camera (conj (str "camera~" camera))
                (seq framing) (conj (str "framing " (str/join "/" framing)))
                clean (conj "no issues"))]
    (if (seq parts) (str/join ", " parts) "everything")))

(defn- shot-row
  "Every field a shot can show, in order; `tr` is its file's transcript (clock and name), when there is one."
  [{:keys [meta home]} r tr full]
  (let [[a b] (shots/best r)]
    (array-map :id (:id r)
               :day (archive/day-of (:rel r))
               :file (or (:source_name tr) (str (fs/file-name (:rel r))))
               :at (tc/hms (:start r))
               :end (tc/hms (:end r))
               :length (tc/hms (- (:end r) (:start r)))
               :framing (:framing r)
               :camera (:camera r)
               :action (out/cut (:action r) full)
               :best (shots/best-text r)
               :why (:best_why r)
               :issues (shots/issues-text r)
               :summary (out/cut (:summary r) full)
               :tags (not-empty (str/join ", " (shots/tags r)))
               :rel (:rel r)
               :tc (when tr (tc/source-tc tr (:start r)))
               :best_tc (when tr (str (tc/source-tc tr a) "-" (tc/source-tc tr b)))
               :proxy (some-> (archive/proxy-of meta (:rel r)) (out/home-path home)))))

(defn shots-cmd
  "`blackbox shots [words…]`: the shot logs, by words (any matches unless --all) and by what the camera did."
  [{:keys [home opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        db (shots/require! path home)
        terms (vec (remove str/blank? (:words args)))
        _ (when (and (empty? terms) (not (some opts [:camera :framing :clean :file :month :day :path])))
            (out/fail! "usage" "no words and no filter: that would be every shot"
                       {:help ["Run `blackbox shots <words>`, or narrow with --camera, --framing, --file or --day"]}))
        {:keys [where params]} (shots-scope opts)
        {:keys [total files rows]} (shots/search db {:q (when (seq terms) (archive/fts-query terms (:all opts)))
                                                     :where where :params params :limit (:limit opts)
                                                     :per-clip (if (:file opts) 0 (:per-clip opts))})
        trs (into {} (map (juxt :rel identity))
                  (when (seq rows)
                    (db/query path "SELECT * FROM transcripts WHERE version = :version
                                    AND rel IN (SELECT value FROM json_each(:rels));"
                              {:version index/default-version :rels (json/generate-string (distinct (map :rel rows)))})))
        head (array-map :query (if (seq terms) (str/join " " terms) "every shot in scope")
                        :match (cond (empty? terms) "filters only" (:all opts) "all words" :else "any word")
                        :scope (shots-scope-words opts)
                        :logs shots/version)]
    (if (empty? rows)
      (if (and (:file opts) (zero? (:total (shots/search db (assoc (select-keys (shots-scope (select-keys opts [:file])) [:where :params])
                                                                   :limit 1 :per-clip 0)))))
        [(merge head (array-map :count 0 :total 0 :result (str "no shot log for " (:file opts) " yet")))
         ["Black Box logs shots on the mini a month at a time; this file's month may not be logged yet"
          (str "Run `blackbox frame " (:file opts) " --at <time>` or `blackbox transcript " (:file opts) "` meanwhile")]]
        [(merge head (array-map :count 0 :total 0 :result "0 shots match"))
         ["Try fewer or other words (any word matches unless --all), or loosen the filters"
          "Run `blackbox look <words>` to search the frames themselves"]])
      (let [one (when (and (:file opts) (= 1 files)) (first rows))
            shown (mapv #(out/pick (shot-row ctx % (trs (:rel %)) (:full opts))
                                   [:id :day :file :at :length :framing :camera :action :best :issues] opts)
                        rows)
            id (:id (first rows))]
        [(apply array-map
                (concat (mapcat identity head)
                        (when one
                          [:clip (array-map :file (or (:source_name (trs (:rel one))) (str (fs/file-name (:rel one))))
                                            :rel (:rel one)
                                            :summary (:summary one)
                                            :tags (not-empty (str/join ", " (shots/tags one))))])
                        [:count (count shown) :total total :files files :shots shown]))
         (cond-> [(str "Run `echo '" id "' | blackbox edl new - --title <title> --play` to play a shot's best moment"
                       " (`" id " whole` for all of it)")
                  (str "Run `blackbox frame " id "` for its best moment as a JPEG")]
           (> total (count rows)) (conj (str "Run the same with `--limit " (min total (* 3 (:limit opts))) "` for more"))
           true (conj "Add `--fields all` for why it's the best moment, the clip's summary and tags, timecode and proxy"))]))))
