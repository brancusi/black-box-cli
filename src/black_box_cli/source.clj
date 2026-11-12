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

(defn- hit-row
  "One hit as a row: its frame id and file first, every field in a fixed order (rows of a table share their keys)."
  [frames-db picture h]
  (array-map :picture picture
             :match (:match h)
             :id (frame-of frames-db (:rel h) (:t h))
             :file (some-> (:rel h) fs/file-name str)
             :kind (:kind h)
             :at (:at h)
             :diff (:diff h)
             :points (:points h)
             :similarity (:similarity h)
             :original (:original h)
             :rel (:rel h)
             :t (:t h)
             :proxy (:proxy h)))

(def ^:private match-fields [:picture :match :id :file :at :diff :points :original])
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
        matches (vec (for [[p r] named h (:matches r)] (hit-row frames-db p h)))
        similar (vec (for [[p r] named h (:similar r)] (hit-row frames-db p h)))
        summary (mapv (fn [[p r]] (array-map :picture p
                                             :size (str/join "x" (:size r))
                                             :checked (:checked r)
                                             :found (count (:matches r))
                                             :best (some-> (first (:matches r)) :rel fs/file-name str)
                                             :seconds (:seconds r)))
                      named)
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
