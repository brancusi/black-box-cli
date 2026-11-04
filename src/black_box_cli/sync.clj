(ns black-box-cli.sync
  "The sync map: which recorder files were heard with each camera file and how their times line up (media_meta's
  `sync/<YYYY>/<MM>/<DD>.<stamp>.sync.jsonl`, schema media-mgmt.sync/1; docs/CONTRACT.md), read into
  `sync.sqlite` beside the transcripts' index. The newest stamp of each day wins. A pair joins a file on (rel,
  sha256): a different sha256 is another file. Read-only: Black Box makes it on the mini."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def marker
  "The file that marks media_meta's sync folder as published by Black Box."
  ".media-mgmt-sync-root")

(def ^:private schema-version 1)

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE pairs (
  camera_rel        TEXT NOT NULL,
  camera_sha256     TEXT NOT NULL,
  recorder_rel      TEXT NOT NULL,
  recorder_sha256   TEXT NOT NULL,
  offset_s          REAL NOT NULL,   -- recorder time = camera time + offset_s + drift_ppm × 1e-6 × (camera time − o0)
  drift_ppm         REAL,
  o0                REAL NOT NULL,   -- the overlap, on the camera's timeline
  o1                REAL NOT NULL,
  confidence        REAL NOT NULL,
  method            TEXT NOT NULL
);
CREATE INDEX ix_pairs_camera ON pairs(camera_rel);
CREATE INDEX ix_pairs_recorder ON pairs(recorder_rel);")

(def ^:private insert-sql
  "BEGIN;
INSERT INTO pairs
  SELECT value->'camera'->>'rel', value->'camera'->>'sha256', value->'recorder'->>'rel', value->'recorder'->>'sha256',
         value->>'offset_s', value->>'drift_ppm', value->'overlap'->>0, value->'overlap'->>1, value->>'confidence',
         value->>'method'
  FROM json_each(:pairs);
INSERT INTO meta VALUES ('schema_version', :schema), ('files', :files), ('days', :days), ('skipped', :skipped);
COMMIT;")

(defn path
  "The sync map's database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "sync.sqlite")))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn newest-days
  "The current file of each day under `root`: the newest stamp per `<YYYY>/<MM>/<DD>`, sorted by day."
  [root]
  (->> (fs/glob root "*/*/*.sync.jsonl")
       (keep (fn [p]
               (let [r (str/replace (str (fs/relativize root p)) "\\" "/")]
                 (when-let [[_ day stamp] (re-matches #"(\d{4}/\d{2}/\d{2})\.(\d{8}T\d{6}Z)\.sync\.jsonl" r)]
                   {:day day :stamp stamp :path p :rel r}))))
       (group-by :day)
       vals
       (map #(last (sort-by :stamp %)))
       (sort-by :day)))

(defn- summary [db]
  (let [m (meta-of db)]
    (array-map :days (parse-long (get m "days" "0"))
               :pairs (:n (db/one db "SELECT count(*) AS n FROM pairs;"))
               :skipped (parse-long (get m "skipped" "0")))))

(defn build!
  "Brings the sync map beside `index-path` up to date with media_meta's: its summary, or nil when media_meta has no
  published sync folder (and then an old one here is removed: no sync map means nothing is synced)."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "sync")
        db (path index-path)]
    (if-not (fs/exists? (fs/path root marker))
      (do (fs/delete-if-exists db) nil)
      (let [days (newest-days root)
            files (str/join "\n" (map :rel days))
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (if (and m (= (str schema-version) (get m "schema_version")) (= files (get m "files")))
          (summary db)
          (let [{readable false skipped true} (group-by #(tools/online-only? (:path %)) days)
                pairs (->> readable
                           (mapcat #(str/split-lines (slurp (str (:path %)))))
                           (remove str/blank?)
                           (keep #(try (json/parse-string % true) (catch Exception _ nil)))
                           (filter #(and (= "media-mgmt.sync/1" (:schema %)) (= "pair" (:kind %)))))
                tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
            (try
              (fs/delete-if-exists tmp)
              (db/run! tmp schema-sql {})
              (db/run! tmp insert-sql {:pairs (json/generate-string pairs)
                                       :schema (str schema-version)
                                       :files (if (seq skipped) "" files) ; read again until every day is here
                                       :days (str (count readable))
                                       :skipped (str (count skipped))})
              (fs/move tmp db {:replace-existing true :atomic-move true})
              (summary db)
              (finally (fs/delete-if-exists tmp)))))))))

;; ------------------------------------------------------------------------------------------------ reading

(defn available?
  "Whether a sync map has been indexed."
  [index-path]
  (fs/exists? (path index-path)))

(defn cameras-of
  "The pairs that put a camera with the recorder file `rel` (`sha256`): [{:camera_rel :camera_sha256 :offset_s
  :drift_ppm :o0 :o1 :confidence :method ...}], or [] without a sync map."
  [index-path rel sha256]
  (if-not (available? index-path)
    []
    (db/query (path index-path) "SELECT * FROM pairs WHERE recorder_rel = :rel AND recorder_sha256 = :sha;"
              {:rel rel :sha sha256})))

(defn pairs-among
  "The pairs whose recorder is among `rels` (with any camera: two recorders heard with one camera are one moment
  even when the camera's line isn't among them), or [] without a sync map."
  [index-path rels]
  (if (or (not (available? index-path)) (< (count (distinct rels)) 2))
    []
    (db/query (path index-path) "SELECT * FROM pairs WHERE recorder_rel IN (SELECT value FROM json_each(:rels));"
              {:rels (json/generate-string (distinct rels))})))

(defn recorder-time
  "The recorder's time for camera time `t`, by `pair`."
  [{:keys [offset_s drift_ppm o0]} t]
  (+ t offset_s (* (or drift_ppm 0.0) 1e-6 (- t o0))))

(defn camera-time
  "The camera's time for recorder time `t`, by `pair`: recorder-time turned around."
  [{:keys [offset_s drift_ppm o0]} t]
  (let [k (* (or drift_ppm 0.0) 1e-6)]
    (/ (+ (- t offset_s) (* k o0)) (+ 1.0 k))))
