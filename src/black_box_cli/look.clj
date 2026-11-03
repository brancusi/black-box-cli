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

(defn kind-of
  "What `what` names: :frame (a frame id), :line (a line id) or :file."
  [what]
  (cond (frames/frame-id? what) :frame
        (re-matches #"(?i)[a-z2-7]{8}\.\d+" what) :line
        :else :file))

(defn target
  "The moment `what` names, a frame id, a line id or a file (at `(:at opts)`, default 0): {:t :rel :clip :name
  :sha256 :tc ...}. error.code not-found, bad-time or no-frames otherwise."
  [ctx path opts what]
  (case (kind-of what)
    :frame (frame-target ctx what)
    :line (line-target path what)
    (file-target path opts what)))

(defn frame-cmd
  "`blackbox frame <line id|frame id|file> [--at T]`."
  [{:keys [meta home env opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        what (str/trim (:what args))
        kind (kind-of what)
        _ (when (and (not= :file kind) (:at opts))
            (out/fail! "usage" "--at is for a file; a line or frame id has its own time"
                       {:help ["Run `blackbox frame <file> --at <time>` for any moment of a file"]}))
        {:keys [id t seek rel clip name file] :as target} (target ctx path opts what)
        proxy (or (if file (frame-proxy meta file) (archive/proxy-of meta rel))
                  (out/fail! "no-proxy" (str "no proxy for " rel " in media_meta")
                             {:help ["Proxies come from the Mac mini; a clip without one has no pictures here"]}))
        jpeg (picture/jpeg! {:env env :cache (:frames (index/paths ctx)) :clip clip :proxy proxy :t (or seek t)
                             :width (:width opts)})
        changed? (and file (:proxy_size file) (not= (:proxy_size file) (fs/size proxy)))
        faces (who/faces-at path rel t)]
    [(apply array-map (concat (when id [:id id])
                              [:file name :rel rel :at (tc/hms t) :tc (:tc target) :jpeg (out/home-path jpeg home)]
                              (when faces [:faces (if (seq faces) faces "no face on screen")])
                              (when changed?
                                [:warning "the proxy has changed since it was embedded; this may not be the same frame"])))
     (cond-> [(str "Run `open " (out/shell-quoted jpeg) "` to see it")]
       (= :frame kind) (conj (str "Run `blackbox like " id "` for frames that look like it"))
       (= :line kind) (conj (str "Run `blackbox line " id "` for what was said around it"))
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
        rows (mapv (fn [[r s f]]
                     (let [n (inc (- r (:first_row f)))
                           {:keys [t frame]} (items [(:id f) n])]
                       (array-map :id (frames/frame-id (:clip f) n)
                                  :day (archive/day-of (:rel f))
                                  :file (:source_name f)
                                  :at (tc/hms t)
                                  :tc (frame-tc f t)
                                  :score (tc/round-to s 4)
                                  :rel (:rel f)
                                  :seconds t
                                  :frame frame
                                  :proxy (some-> (frame-proxy meta f) (out/home-path home)))))
                   hits)
        shown (mapv #(out/pick % [:id :day :file :at :tc :score] opts) rows)
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
