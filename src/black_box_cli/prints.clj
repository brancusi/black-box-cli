(ns black-box-cli.prints
  "Voice and face prints: a cache built from media_meta's prints files (`prints/<model>/<rel>.<stamp>.prints.json`,
  schema media-mgmt.prints/1; docs/CONTRACT.md), like the picture index. Only the newest stamp of each (model,
  rel) is read: an older one may hold a person who has since opted out. Items are in `prints.sqlite`; their
  vectors, float32, in one file per model named by its content (`prints-<id>.f32`), made from one file per prints
  file (`print-vectors/<sha256>.f32`).

  Scoring follows Black Box's People page (Black Box repo d70a5a6), so a score here is the page's: a print's score for
  a person is the mean of its top_k cosines to that person's confirmed whole prints, the longest max_refs of them
  (ties by `<source sha256>|<transcript sha256>|<label>`), and people tie by id."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.ids :as ids]
   [black-box-cli.out :as out]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.util Base64)))

(def marker
  "The file that marks media_meta's prints folder as published by Black Box."
  ".media-mgmt-prints-root")

(def ^:private schema-version 1)

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE print_files (
  id                INTEGER PRIMARY KEY,
  path              TEXT NOT NULL UNIQUE, -- under prints/: <model>/<rel>.<stamp>.prints.json
  kind              TEXT NOT NULL,        -- voice or face
  model             TEXT NOT NULL,        -- the producer's label, e.g. wespeaker/wespeaker-resnet34-lm-v1
  rel               TEXT NOT NULL,
  clip              TEXT NOT NULL,        -- as in line ids
  stamp             TEXT NOT NULL,
  json_sha256       TEXT NOT NULL,
  json_size         INTEGER NOT NULL,
  json_mtime        REAL NOT NULL,        -- for spotting a change only
  source_sha256     TEXT,
  transcript        TEXT,                 -- the transcript version
  transcript_sha256 TEXT,
  dim               INTEGER NOT NULL,
  count             INTEGER NOT NULL,
  first_row         INTEGER NOT NULL DEFAULT 0  -- its first vector's row in its model's vectors file
);
CREATE INDEX ix_print_files_clip ON print_files(clip);
CREATE TABLE print_items (
  file_id INTEGER NOT NULL REFERENCES print_files(id),
  n       INTEGER NOT NULL,              -- 1, 2, … in the file
  label   TEXT NOT NULL,                 -- S1 (a voice) or F1 (a face)
  piece   INTEGER,                       -- null for the whole print, else a piece of a voice
  seconds REAL,
  ranges  TEXT NOT NULL,                 -- json: [[start, end], ...]
  PRIMARY KEY (file_id, n)
);")

(defn paths
  "The prints' files, beside the transcripts' index at `index-path`."
  [index-path]
  (let [dir (fs/parent index-path)]
    {:db (str (fs/path dir "prints.sqlite"))
     :vectors (str (fs/path dir "print-vectors"))
     :dir (str dir)}))

;; ------------------------------------------------------------------------------------------------ reading media_meta

(def ^:private file-re #"(.+)\.(\d{8}T\d{6}Z)\.prints\.json")

(defn- walk
  "The newest prints file of each (model, rel) under `tree`, sorted by path; hidden folders and files left out."
  [tree]
  (->> (fs/glob tree "**.prints.json")
       (keep (fn [p] (let [path (str (fs/relativize tree p))]
                       (when-let [[_ key stamp] (re-matches file-re path)]
                         (when-not (some #(str/starts-with? % ".") (str/split path #"/"))
                           {:file (str p) :path path :key key :stamp stamp})))))
       (group-by :key)
       vals
       (map #(last (sort-by :stamp %)))
       (sort-by :path)))

(defn- file-row
  "The index's row for prints file `doc` found at `key` (<model>/<rel>), with its items and vectors, or nil when it
  isn't a prints file for that model and original."
  [doc key stamp]
  (let [{:keys [schema kind source transcript producer dim dtype byte_order items vectors]} doc
        bs (try (.decode (Base64/getDecoder) (str vectors)) (catch Exception _ nil))]
    (when (and (= "media-mgmt.prints/1" schema)
               (#{"voice" "face"} kind)
               (= "float32" dtype)
               (= "little" byte_order)
               (pos-int? dim)
               (sequential? items)
               (= key (str (:label producer) "/" (:rel source)))
               bs
               (= (alength ^bytes bs) (* 4 dim (count items))))
      {:kind kind :model (:label producer) :rel (:rel source) :clip (ids/clip (:rel source)) :stamp stamp
       :source_sha256 (:sha256 source) :transcript (:label transcript) :transcript_sha256 (:sha256 transcript)
       :dim dim :count (count items)
       :items (vec (map-indexed (fn [i {:keys [label piece seconds ranges]}]
                                  {:n (inc i) :label label :piece piece :seconds seconds :ranges ranges})
                                items))
       :vectors bs})))

;; ------------------------------------------------------------------------------------------------ building

(def ^:private insert-sql
  "BEGIN;
INSERT INTO print_files (path, kind, model, rel, clip, stamp, json_sha256, json_size, json_mtime, source_sha256,
                         transcript, transcript_sha256, dim, count)
  SELECT d->>'path', d->>'kind', d->>'model', d->>'rel', d->>'clip', d->>'stamp', d->>'json_sha256', d->>'json_size',
         d->>'json_mtime', d->>'source_sha256', d->>'transcript', d->>'transcript_sha256', d->>'dim', d->>'count'
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO print_items (file_id, n, label, piece, seconds, ranges)
  SELECT f.id, i.value->>'n', i.value->>'label', i.value->>'piece', i.value->>'seconds', i.value->'ranges'
  FROM json_each(:docs) j JOIN print_files f ON f.path = j.value->>'path', json_each(j.value->'items') i;
COMMIT;")

(def ^:private drop-sql
  "BEGIN;
DELETE FROM print_items WHERE file_id IN (SELECT value FROM json_each(:ids));
DELETE FROM print_files WHERE id IN (SELECT value FROM json_each(:ids));
COMMIT;")

(def ^:private first-row-sql
  "UPDATE print_files SET first_row = (SELECT coalesce(sum(g.count), 0) FROM print_files g
                                       WHERE g.model = print_files.model AND g.path < print_files.path);")

(defn- reusable? [path]
  (and (fs/exists? path)
       (try
         (let [m (into {} (map (juxt :key :value)) (db/query path "SELECT key, value FROM meta;"))]
           (and (= (str schema-version) (get m "schema_version"))
                (= (:v (db/one path "SELECT sqlite_version() AS v;")) (get m "sqlite_version"))))
         (catch clojure.lang.ExceptionInfo _ false))))

(defn- fresh! [path]
  (db/run! path (str schema-sql "
INSERT INTO meta VALUES ('schema_version', :schema), ('sqlite_version', sqlite_version());")
           {:schema (str schema-version)}))

(defn- vectors-files!
  "Each model's vectors, concatenated in row order into `prints-<id>.f32` (made only when missing): {model {:vectors
  name :dim :rows}}. A file's vectors come from print-vectors/<sha256>.f32, made again from its file when gone."
  [{:keys [dir vectors]} tmp tree]
  (let [files (db/query tmp "SELECT path, model, dim, count, json_sha256 FROM print_files ORDER BY model, path;")]
    (into (sorted-map)
          (for [[model fs-of] (group-by :model files)]
            (let [id (subs (frames/sha256-hex (.getBytes (str model "\n" (str/join "\n" (map :json_sha256 fs-of))) "UTF-8"))
                           0 16)
                  name (str "prints-" id ".f32")
                  target (fs/path dir name)]
              (when-not (fs/exists? target)
                (let [baos (java.io.ByteArrayOutputStream.)]
                  (doseq [{:keys [path json_sha256]} fs-of]
                    (let [cached (fs/path vectors (str json_sha256 ".f32"))]
                      (when-not (fs/exists? cached)
                        (let [[_ key stamp] (re-matches file-re path)
                              row (file-row (json/parse-string (slurp (str (fs/path tree path))) true) key stamp)]
                          (frames/write-new! cached (:vectors row))))
                      (.write baos ^bytes (fs/read-all-bytes cached))))
                  (frames/write-new! target (.toByteArray baos))))
              [model {:vectors name :dim (:dim (first fs-of)) :rows (reduce + (map :count fs-of))}])))))

(defn- check-dims!
  "One model, one dim: a prints file of another length can't be scored with the rest."
  [tmp]
  (when-let [{:keys [model]} (db/one tmp "SELECT model FROM print_files GROUP BY model HAVING count(DISTINCT dim) > 1
                                          LIMIT 1;")]
    (out/fail! "bad-prints" (str "the prints of " model " don't all have the same length")
               {:help ["Tell the owner: Black Box publishes the prints"]})))

(defn- tidy!
  "Removes vectors files no longer named by the index."
  [{:keys [dir vectors db]}]
  (let [current (set (map :vectors (vals (json/parse-string (:value (db/one db "SELECT value FROM meta WHERE key = 'models';"))
                                                            true))))
        shas (set (map :json_sha256 (db/query db "SELECT json_sha256 FROM print_files;")))]
    (doseq [f (fs/glob dir "prints-*.f32") :when (not (current (str (fs/file-name f))))]
      (fs/delete-if-exists f))
    (when (fs/directory? vectors)
      (doseq [f (fs/glob vectors "*.f32") :when (not (shas (str/replace (str (fs/file-name f)) #"\.f32$" "")))]
        (fs/delete-if-exists f)))))

(defn build!
  "Brings the prints beside `index-path` up to date with media_meta's prints files, or builds them from scratch when
  `rebuild`. Returns the counts, or nil when media_meta has no published prints folder."
  [{:keys [meta index-path rebuild]}]
  (let [tree (fs/path meta "prints")
        {:keys [db] :as p} (paths index-path)]
    (when (fs/exists? (fs/path tree marker))
      (fs/create-dirs (:vectors p))
      (let [tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
        (try
          (fs/delete-if-exists tmp)
          (if (and (not rebuild) (reusable? db)) (fs/copy db tmp) (fresh! tmp))
          (let [known (into {} (map (juxt :key identity))
                            (map #(assoc % :key (second (re-matches file-re (:path %))))
                                 (db/query tmp "SELECT id, path, json_size, json_mtime FROM print_files;")))
                res (reduce
                     (fn [res {:keys [file path key stamp]}]
                       (let [prev (known key)
                             mtime (/ (.toMillis (fs/last-modified-time file)) 1000.0)
                             res (update res :seen conj key)]
                         (cond
                           (and prev (= path (:path prev)) (= (:json_size prev) (fs/size file))
                                (== (:json_mtime prev) mtime))
                           (update res :unchanged inc)

                           (tools/online-only? file) (update res :skipped inc)

                           :else
                           (let [bs (fs/read-all-bytes file)
                                 sha (frames/sha256-hex bs)]
                             (if-let [row (some-> (try (json/parse-string (String. ^bytes bs "UTF-8") true)
                                                       (catch Exception _ nil))
                                                  (file-row key stamp))]
                               (do (frames/write-new! (fs/path (:vectors p) (str sha ".f32")) (:vectors row))
                                   (-> res
                                       (update (if prev :changed :added) inc)
                                       (update :drop conj (:id prev))
                                       (update :rows conj (-> row
                                                              (dissoc :vectors)
                                                              (assoc :path path :json_sha256 sha
                                                                     :json_size (alength bs) :json_mtime mtime)))))
                               (update res :skipped inc))))))
                     {:added 0 :changed 0 :unchanged 0 :skipped 0 :seen #{} :rows [] :drop []}
                     (walk tree))
                gone (remove (:seen res) (keys known))
                drop-ids (vec (concat (remove nil? (:drop res)) (map (comp :id known) gone)))]
            (when (seq drop-ids)
              (db/run! tmp drop-sql {:ids (json/generate-string drop-ids)}))
            (doseq [batch (partition-all 200 (:rows res))]
              (db/run! tmp insert-sql {:docs (json/generate-string batch)}))
            (check-dims! tmp)
            (db/run! tmp first-row-sql {})
            (db/run! tmp "INSERT OR REPLACE INTO meta VALUES ('models', :models);"
                     {:models (json/generate-string (vectors-files! p tmp tree))})
            (fs/move tmp db {:replace-existing true :atomic-move true})
            (tidy! p)
            (let [whole (into {} (map (juxt :kind :n))
                              (db/query db "SELECT f.kind, count(*) AS n FROM print_items i
                                            JOIN print_files f ON f.id = i.file_id WHERE i.piece IS NULL GROUP BY f.kind;"))]
              (array-map :added (:added res) :changed (:changed res) :unchanged (:unchanged res)
                         :removed (count gone) :skipped (:skipped res)
                         :voices (get whole "voice" 0) :faces (get whole "face" 0))))
          (finally (fs/delete-if-exists tmp)))))))

;; ------------------------------------------------------------------------------------------------ searching

(defn available?
  "Whether prints have been indexed."
  [index-path]
  (fs/exists? (:db (paths index-path))))

(defn require!
  "The prints' files and models, or error.code no-prints."
  [index-path]
  (let [{:keys [db] :as p} (paths index-path)]
    (when-not (fs/exists? db)
      (out/fail! "no-prints" "no voice or face prints in the index: media_meta has no prints folder, or `index` hasn't run since"
                 {:help ["Run `blackbox index`"]}))
    (assoc p :models (json/parse-string (or (:value (db/one db "SELECT value FROM meta WHERE key = 'models';")) "{}")))))

(defn model-of
  "The model the prints of `kind` are scored with: the snapshot's (`matching`), else the only one indexed."
  [{:keys [db]} matching kind]
  (or (get-in matching [(keyword kind) :model])
      (let [ms (map :model (db/query db "SELECT DISTINCT model FROM print_files WHERE kind = :kind;" {:kind kind}))]
        (when (= 1 (count ms)) (first ms)))))

(defn vectors
  "The float32 vectors of `model` and their dim: [floats dim], or nil when none are indexed."
  [{:keys [dir models]} model]
  (when-let [{:strs [vectors dim]} (get models model)]
    [(frames/read-floats (str (fs/path dir vectors))) dim]))

(defn whole-prints
  "Every whole print (no piece) of `model`, in row order: [{:row :rel :clip :label :seconds :ranges :source_sha256
  :transcript :transcript_sha256}]."
  [{:keys [db]} model]
  (mapv #(update % :ranges json/parse-string)
        (db/query db "SELECT f.first_row + i.n - 1 AS row, f.rel, f.clip, i.label, i.seconds, i.ranges, f.source_sha256,
                             f.transcript, f.transcript_sha256
                      FROM print_items i JOIN print_files f ON f.id = i.file_id
                      WHERE f.model = :model AND i.piece IS NULL ORDER BY f.path, i.n;"
                  {:model model})))

(defn print-key
  "A print's key as the People page sorts it: `<source sha256>|<transcript sha256>|<label>`."
  [{:keys [source_sha256 transcript_sha256 label]}]
  (str source_sha256 "|" transcript_sha256 "|" label))

(defn references
  "Each person's references: their confirmed whole prints among `prints`, longest first (ties by key), at most
  `max-refs`: {person [print …]}."
  [prints confirmed max-refs]
  (let [by (into {} (map (juxt (juxt :rel :transcript_sha256 :label) identity)) prints)]
    (->> confirmed
         (keep (fn [n] (when-let [p (by [(:rel n) (:transcript_sha256 n) (:label n)])] [(:person n) p])))
         (group-by first)
         (into {} (map (fn [[person ps]]
                         [person (->> (map second ps) distinct
                                      (sort-by (juxt (comp - :seconds) print-key))
                                      (take max-refs) vec)]))))))

(defn person-scores
  "The score of the print at `row` for each person with references other than itself: the mean of its top `top-k`
  cosines to them. Best first, people tied by id: [[person score] …]."
  [^floats m dim row refs top-k]
  (let [q (java.util.Arrays/copyOfRange m (int (* row dim)) (int (* (inc row) dim)))]
    (->> refs
         (keep (fn [[person ps]]
                 (let [cs (->> ps (remove #(= row (:row %)))
                               (map #(frames/dot m (* (:row %) dim) q dim))
                               (sort >) (take top-k))]
                   (when (seq cs) [person (/ (reduce + cs) (count cs))]))))
         (sort-by (fn [[person s]] [(- s) person])))))

(defn like
  "The whole prints most like the one at `row`, never from `clip`, best first, ties by row: [[print score] …]."
  [^floats m dim row prints clip]
  (let [q (java.util.Arrays/copyOfRange m (int (* row dim)) (int (* (inc row) dim)))]
    (->> prints
         (remove #(= clip (:clip %)))
         (map (fn [p] [p (frames/dot m (* (:row p) dim) q dim)]))
         (sort-by (fn [[p s]] [(- s) (:row p)])))))

;; ------------------------------------------------------------------------------------------------ face ids

(def ^:private face-id-re #"([a-z2-7]{8})\.face([1-9][0-9]*)")

(defn face-id
  "A face's id: its clip and its label's number (F2 → face2)."
  [clip label]
  (when-let [[_ n] (re-matches #"F([1-9][0-9]*)" (str label))]
    (str clip ".face" n)))

(defn face-id?
  "Whether `v` looks like a face id."
  [v]
  (some? (re-matches face-id-re (str/lower-case (str/trim (str v))))))

(defn parse-face-id
  "A face id as {:clip :label}, or error.code bad-id."
  [v]
  (if-let [[_ c n] (re-matches face-id-re (str/lower-case (str/trim (str v))))]
    {:clip c :label (str "F" n)}
    (out/fail! "bad-id" (str "'" v "' isn't a face id (like k7f3q2ab.face2)")
               {:help ["Run `blackbox frame <id>` for the faces on screen"]})))
