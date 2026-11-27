(ns black-box-cli.source
  "`blackbox source <picture…>`: the original of a screenshot, a thumbnail or a crop, found by Black Box (0.43.0 or
  later): its picture search picks look-alikes, then each is checked point by point and pixel by pixel. The CLI hands
  Black Box the pictures and shows what it found, with the picture index's frame id for each hit, so `frame`, `like`
  and `still new` can take it. The originals' paths are shown, never opened (AGENTS.md rule 1)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.app :as app]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.photos :as photos]
   [black-box-cli.probe :as probe]
   [clojure.string :as str]))

(def ^:private nas-roots
  "Where the originals live: a picture there is never read, by this CLI or by Black Box on its behalf."
  ["/Volumes/" "/VideoSource/" "/AssetSource/"])

(defn- check-pictures!
  "The pictures as absolute paths, or error.code bad-picture listing each one that's on the NAS or isn't a file."
  [pictures]
  (let [abs (mapv #(str (fs/absolutize (fs/expand-home %))) pictures)
        problems (vec (keep (fn [[given p]]
                              (cond
                                (some #(str/starts-with? p %) nas-roots)
                                (str given ": on the NAS; give a copy on this Mac (the originals are never read)")
                                (not (fs/regular-file? p)) (str given ": no such file")))
                            (map vector pictures abs)))]
    (when (seq problems)
      (out/fail! "bad-picture" (str (count problems) " problem" (when (next problems) "s") "; nothing was searched")
                 {:problems problems :help ["Give pictures on this Mac (JPEG, PNG, HEIC, TIFF …)"
                                            "Run `blackbox source --help` for the options"]}))
    abs))

(defn- frame-of
  "The picture index's frame nearest `t` (0 for a photo) in the file at `rel`: its id, or nil when it isn't indexed."
  [frames-db rel t]
  (when frames-db
    (some-> (db/one frames-db "SELECT f.clip, i.n FROM frame_files f JOIN frame_items i ON i.file_id = f.id
                               WHERE f.rel = :rel ORDER BY abs(i.t - :t), i.n LIMIT 1;"
                    {:rel rel :t (double (or t 0))})
            ((fn [{:keys [clip n]}] (frames/frame-id clip n))))))

(defn- tier
  "How much of an original a file is, best first: a raw (0); a TIFF, PSD or EXR, or a camera's video, its own frames'
  source (1); a PNG (2); a JPEG or HEIC (3); anything else (4). As findsrc ranks them."
  [rel]
  (let [ext (some-> (re-find #"\.([A-Za-z0-9]+)$" (str rel)) second str/lower-case)]
    (cond
      (#{"arw" "dng" "cr2" "cr3" "nef" "raf" "orf" "rw2" "iiq" "3fr" "srw"} ext) 0
      (#{"tif" "tiff" "psd" "psb" "exr" "mp4" "mov" "mxf" "m4v" "braw" "r3d" "insv" "lrf"} ext) 1
      (= "png" ext) 2
      (#{"jpg" "jpeg" "heic" "heif"} ext) 3
      :else 4)))

(defn- sizes
  "Each original's size in pixels, {rel [w h]}: a photo's from Black Box's description, a video's from its camera facts."
  [index-path hits]
  (let [rels (distinct (keep :rel hits))
        described (photos/of index-path rels [])
        facts (try (probe/facts index-path) (catch Exception _ {}))]
    (into {} (for [rel rels
                   :let [d (or (described rel) (get facts rel))]
                   :when (and (:width d) (:height d))]
               [rel [(:width d) (:height d)]]))))

(defn- ranked
  "One picture's confirmed matches, best original first: by `tier`, then larger, then as Black Box found them. The
  first is `source`, the rest `version`s of it."
  [rows]
  (->> rows
       (map-indexed vector)
       (sort-by (fn [[i r]] [(tier (:rel r)) (- (or (:pixels r) 0)) i]))
       (map-indexed (fn [j [_ r]] (-> r (assoc :match (if (zero? j) "source" "version")) (dissoc :pixels))))))

(defn- hit-row
  "One hit as a row: its frame id and file first, every field in a fixed order (rows of a table share their keys)."
  [frames-db size-of picture h]
  (array-map :picture picture
             :match (:match h)
             :id (frame-of frames-db (:rel h) (:t h))
             :file (some-> (:rel h) fs/file-name str)
             :kind (:kind h)
             :at (:at h)
             :size (if-let [[w hh] (size-of (:rel h))] (str w "x" hh) "")
             :pixels (when-let [[w hh] (size-of (:rel h))] (* w hh))
             :diff (:diff h)
             :points (:points h)
             :similarity (:similarity h)
             :original (:original h)
             :rel (:rel h)
             :t (:t h)
             :proxy (:proxy h)))

(def ^:private match-fields [:picture :match :id :file :at :size :diff :points :original])
(def ^:private similar-fields [:picture :id :file :at :diff :similarity :original])

(defn- results-of
  "Black Box's answer as one result per picture: it answers a single object for one picture, {results} for several."
  [ans]
  (if (sequential? (:results ans)) (:results ans) [ans]))

(defn source-cmd
  "`blackbox source <picture…> [--candidates N] [--max-diff X]`."
  [{:keys [env opts args] :as ctx}]
  (let [given (vec (remove str/blank? (:picture args)))
        _ (when (empty? given) (out/fail! "usage" "no picture to find" {:help ["Run `blackbox source <picture>`"]}))
        max-diff (:max-diff opts)
        _ (when-not (some-> max-diff parse-double (#(<= 0.0 % 1.0)))
            (out/fail! "usage" (str "--max-diff " max-diff " isn't a share between 0 and 1")
                       {:help ["Run `blackbox source --help` for the options"]}))
        _ (when-not (pos? (:candidates opts))
            (out/fail! "usage" "--candidates must be 1 or more" {:help ["Run `blackbox source --help` for the options"]}))
        pictures (check-pictures! given)
        [ans err] (app/ask env (concat ["source"] pictures
                                       ["--candidates" (str (:candidates opts)) "--max-diff" max-diff]))
        _ (when err
            (out/fail! (:code err) (str "the pictures weren't searched: " (:message err))
                       {:help (case (:code err)
                                "no-index" ["Open Black Box once and let it build this Mac's picture index, then run it again"]
                                "no-media-meta" ["Make Dropbox's media_meta available on this Mac, then run it again"]
                                (app/help-for (:code err) "`source` (Black Box 0.43.0 or later)"))}))
        frames-db (let [p (:db (frames/paths (:index (index/paths ctx))))] (when (fs/exists? p) p))
        results (results-of ans)
        ;; Black Box answers in argument order: each result is named by the picture as it was given
        named (map vector (concat given (repeat nil)) results)
        size-of (sizes (:index (index/paths ctx)) (mapcat #(concat (:matches %) (:similar %)) results))
        by-picture (mapv (fn [[p r]] (vec (ranked (map #(hit-row frames-db size-of p %) (:matches r))))) named)
        matches (vec (apply concat by-picture))
        similar (vec (for [[p r] named h (:similar r)] (dissoc (hit-row frames-db size-of p h) :pixels)))
        summary (mapv (fn [[p r] ms] (array-map :picture p
                                                :size (str/join "x" (:size r))
                                                :checked (:checked r)
                                                :found (count (:matches r))
                                                :best (some-> (first ms) :file)
                                                :seconds (:seconds r)))
                      named by-picture)
        ;; one picture: every row is its, so the column only says it again
        pick #(out/pick % (if (next given) %2 (vec (remove #{:picture} %2))) opts)
        first-id (some :id (concat matches similar))
        video-id (some #(when (= "video" (:kind %)) (:id %)) (concat matches similar))]
    [(array-map :pictures summary
                :matches (if (seq matches) (mapv #(pick % match-fields) matches) "none confirmed")
                :similar (if (seq similar) (mapv #(pick % similar-fields) similar) "none"))
     (cond-> []
       (empty? matches) (conj (str "Nothing was confirmed as the same picture: an edit, a repaint or a picture"
                                   " that isn't in the archive won't match point by point")
                              "Run `blackbox like <id>` from a look-alike, or `blackbox look <words>`, for similar frames")
       first-id (conj (str "Run `blackbox frame " first-id "` to see it"))
       video-id (conj (str "Run `blackbox still new " video-id "` for a full-size still from the original"))
       true (conj "Add `--fields all` for each hit's kind, rel, seconds, similarity and proxy"))]))
