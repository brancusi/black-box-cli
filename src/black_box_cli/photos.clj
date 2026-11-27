(ns black-box-cli.photos
  "What's in each photo, as Black Box describes it on the mini (Gemini, from the photo's proxy): a description, the
  subjects, setting and framing, tags, the picture's quality and whether it would print, with the camera's own facts
  (make, model, lens, exposure, size), from media_meta's `photos/<model>/<version>/<rel>.photo.json` (schema
  blackbox.photos/1; docs/CONTRACT.md). Read into `photos.sqlite` beside the transcripts' index with a full-text table
  over the words. Written once and never redone in place (a new prompt or model goes into a new version folder), so
  the folders' file list says when to read them again, and of two versions of one photo the newest is kept.

  Every photo is also in the picture index (black-box-cli.frames, kind photo), described or not: a photo's id is its
  frame there, `<clip>.f1`, which `frame`, `like` and `still new` take."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def marker
  "The file that marks media_meta's photos folder as published by Black Box."
  ".blackbox-photos-root")

(def ^:private schema-version 1)

(def ^:private suffix ".photo.json")

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE photos (
  id            INTEGER PRIMARY KEY,
  rel           TEXT NOT NULL UNIQUE, -- the original's path
  clip          TEXT NOT NULL,        -- the clip part of its frame id
  sha256        TEXT,                 -- the original's
  version       TEXT NOT NULL,        -- <model>/<prompt version>, as published
  taken_at      TEXT,
  make          TEXT,
  camera        TEXT,
  lens          TEXT,
  focal_mm      REAL,
  f_number      REAL,
  exposure_s    REAL,
  iso           INTEGER,
  width         INTEGER,
  height        INTEGER,
  megapixels    REAL,
  orientation   TEXT,
  sequence      TEXT,                 -- a time-lapse's sequence, when the photo is one of its frames
  proxy_rel     TEXT,
  description   TEXT,
  subjects      TEXT NOT NULL,        -- json: [subject, ...]
  setting       TEXT,
  framing       TEXT,
  tags          TEXT NOT NULL,        -- json: [tag, ...]
  focus         TEXT,
  exposure      TEXT,
  motion_blur   INTEGER,
  issues        TEXT NOT NULL,        -- json: [issue, ...]
  print_score   INTEGER,              -- how well it would print, large (1 to 5 so far)
  print_why     TEXT
);
CREATE INDEX ix_photos_clip ON photos(clip);
CREATE INDEX ix_photos_sha ON photos(sha256);
CREATE VIRTUAL TABLE photos_fts USING fts5(
  description, subjects, setting, framing, tags, tokenize='porter unicode61 remove_diacritics 2');")

(def ^:private insert-sql
  "BEGIN;
INSERT INTO photos (rel, clip, sha256, version, taken_at, make, camera, lens, focal_mm, f_number, exposure_s, iso,
                    width, height, megapixels, orientation, sequence, proxy_rel, description, subjects, setting,
                    framing, tags, focus, exposure, motion_blur, issues, print_score, print_why)
  SELECT d->>'rel', d->>'clip', d->>'sha256', d->>'version', d->>'taken_at', d->>'make', d->>'camera', d->>'lens',
         d->>'focal_mm', d->>'f_number', d->>'exposure_s', d->>'iso', d->>'width', d->>'height', d->>'megapixels',
         d->>'orientation', d->>'sequence', d->>'proxy_rel', d->>'description', d->>'subjects', d->>'setting',
         d->>'framing', d->>'tags', d->>'focus', d->>'exposure', d->>'motion_blur', d->>'issues', d->>'print_score',
         d->>'print_why'
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO photos_fts (rowid, description, subjects, setting, framing, tags)
  SELECT id, coalesce(description, ''), (SELECT coalesce(group_concat(value, ', '), '') FROM json_each(subjects)),
         coalesce(setting, ''), coalesce(framing, ''), (SELECT coalesce(group_concat(value, ', '), '') FROM json_each(tags))
  FROM photos;
INSERT INTO meta VALUES ('schema_version', :schema), ('files', :files), ('skipped', :skipped);
COMMIT;")

(defn path
  "The photo descriptions' database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "photos.sqlite")))

(defn- walk
  "Every description under `root` (photos/<model>/<version>/…), sorted, with its rel and version; hidden folders and
  files left out."
  [root]
  (->> (fs/glob root (str "*/*/**" suffix))
       (keep (fn [p]
               (let [parts (str/split (str (fs/relativize root p)) #"/")
                     rel (str/join "/" (drop 2 parts))]
                 (when-not (some #(str/starts-with? % ".") parts)
                   {:path (str p) :version (str (first parts) "/" (second parts))
                    :rel (subs rel 0 (- (count rel) (count suffix)))}))))
       (sort-by (juxt :rel :version))))

(defn- texts [v] (if (sequential? v) (mapv str (remove nil? v)) []))

(defn- num-or-nil [v] (when (number? v) v))

(defn- str-or-nil [v] (when (and v (not (str/blank? (str v)))) (str v)))

(defn photo-row
  "The index's row for description `doc` found at `rel` (version `version`), or nil when it isn't one of that
  original."
  [doc rel version]
  (let [{:keys [schema photo proxy description subjects setting framing tags quality orientation]} doc]
    (when (and (map? doc) (= "blackbox.photos/1" schema) (map? photo) (= rel (:rel photo)))
      {:rel rel :clip (ids/clip rel) :sha256 (str-or-nil (:sha256 photo)) :version version
       :taken_at (str-or-nil (:taken_at photo)) :make (str-or-nil (:make photo)) :camera (str-or-nil (:camera photo))
       :lens (str-or-nil (:lens photo)) :focal_mm (num-or-nil (:focal_mm photo)) :f_number (num-or-nil (:f_number photo))
       :exposure_s (num-or-nil (:exposure_s photo)) :iso (num-or-nil (:iso photo))
       :width (num-or-nil (:width photo)) :height (num-or-nil (:height photo))
       :megapixels (num-or-nil (:megapixels photo)) :orientation (str-or-nil orientation)
       :sequence (str-or-nil (get-in photo [:timelapse :sequence])) :proxy_rel (str-or-nil (:rel proxy))
       :description (str-or-nil description) :subjects (json/generate-string (texts subjects))
       :setting (str-or-nil setting) :framing (str-or-nil framing) :tags (json/generate-string (texts tags))
       :focus (str-or-nil (:focus quality)) :exposure (str-or-nil (:exposure quality))
       :motion_blur (when (boolean? (:motion_blur quality)) (if (:motion_blur quality) 1 0))
       :issues (json/generate-string (texts (:issues quality)))
       :print_score (num-or-nil (get-in doc [:print :score])) :print_why (str-or-nil (get-in doc [:print :why]))})))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn- summary [db]
  (let [m (meta-of db)]
    (array-map :described (:n (db/one db "SELECT count(*) AS n FROM photos;"))
               :skipped (parse-long (get m "skipped" "0")))))

(defn build!
  "Brings the photo descriptions beside `index-path` up to date with media_meta's: their summary, or nil when
  media_meta has no published photos folder (and then an old database here is removed). Read again whole whenever the
  folder's files change; a description that isn't one, or that Dropbox keeps online-only, is skipped and counted."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "photos")
        db (path index-path)]
    (if-not (fs/exists? (fs/path root marker))
      (do (fs/delete-if-exists db) nil)
      (let [files (walk root)
            listing (str/join "\n" (map #(str (:version %) " " (:rel %) " " (fs/size (:path %))) files))
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (fs/create-dirs (fs/parent db))
        (if (and m (= (str schema-version) (get m "schema_version")) (= listing (get m "files")))
          (summary db)
          (let [parsed (map (fn [{p :path rel :rel version :version}]
                              (when-not (tools/online-only? p)
                                (some-> (try (json/parse-string (slurp p) true) (catch Exception _ nil))
                                        (photo-row rel version))))
                            files)
                ;; sorted by rel then version: the last of a rel is its newest version
                rows (->> (remove nil? parsed) (reduce (fn [m r] (assoc m (:rel r) r)) {}) vals (sort-by :rel) vec)
                skipped (count (remove some? parsed))
                tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
            (try
              (fs/delete-if-exists tmp)
              (db/run! tmp schema-sql {})
              (db/run! tmp insert-sql {:docs (json/generate-string rows)
                                       :schema (str schema-version)
                                       ;; a skipped one is read again next time, in case Dropbox has it by then
                                       :files (if (pos? skipped) "" listing)
                                       :skipped (str skipped)})
              (fs/move tmp db {:replace-existing true :atomic-move true})
              (summary db)
              (finally (fs/delete-if-exists tmp)))))))))

;; ------------------------------------------------------------------------------------------------ reading

(defn available?
  "Whether photo descriptions have been indexed."
  [index-path]
  (fs/exists? (path index-path)))

(def cols
  "Every column of a description, as a row has them."
  "p.rel, p.clip, p.sha256, p.version, p.taken_at, p.make, p.camera, p.lens, p.focal_mm, p.f_number, p.exposure_s,
   p.iso, p.width, p.height, p.megapixels, p.orientation, p.sequence, p.proxy_rel, p.description, p.subjects,
   p.setting, p.framing, p.tags, p.focus, p.exposure, p.motion_blur, p.issues, p.print_score, p.print_why")

(defn of
  "The descriptions of the photos at `rels` (and, for a copy of one at another path, of the same `sha256s`):
  {rel description}, by rel and by sha256 (a photo copied to two folders is described once, at one of them)."
  [index-path rels sha256s]
  (if-not (and (available? index-path) (or (seq rels) (seq sha256s)))
    {}
    (let [rows (db/query (path index-path)
                         (str "SELECT " cols " FROM photos p
                               WHERE p.rel IN (SELECT value FROM json_each(:rels))
                                  OR p.sha256 IN (SELECT value FROM json_each(:shas));")
                         {:rels (json/generate-string (vec rels)) :shas (json/generate-string (vec (remove nil? sha256s)))})]
      (merge (into {} (map (juxt :sha256 identity)) (filter :sha256 rows))
             (into {} (map (juxt :rel identity)) rows)))))

(defn search
  "The described photos matching `q` (an FTS5 query) where `where` (SQL over photos `p`, with `params`) holds, best
  first: rows with every column."
  [index-path {:keys [q where params]}]
  (if-not (available? index-path)
    []
    (db/query (path index-path)
              (str "SELECT " cols ", bm25(photos_fts) AS rk FROM photos p JOIN photos_fts f ON f.rowid = p.id
                    WHERE photos_fts MATCH :q" (str/join (map #(str " AND " %) where)) " ORDER BY rk, p.rel;")
              (assoc params :q q))))

(defn tags
  "A description's tags, or subjects, or issues (`k`), as a list."
  [r k]
  (texts (try (json/parse-string (str (get r k))) (catch Exception _ nil))))

(defn camera-words
  "The camera a photo was taken with, in words: its make and model, and the lens."
  [r]
  (not-empty (str/join ", " (remove str/blank? [(str/trim (str (:make r) " " (:camera r))) (:lens r)]))))

(defn size-words
  "A photo's size: 6240x4160 (26 MP)."
  [{:keys [width height megapixels]}]
  (when (and width height)
    (str width "x" height (when megapixels (str " (" (Math/round (double megapixels)) " MP)")))))
