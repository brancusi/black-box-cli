(ns black-box-cli.index
  "This Mac's index of what was said: a cache built from media_meta's transcripts, in SQLite with FTS5 (docs/DESIGN.md,
  decisions 1–3). A build reads every new or changed transcript, drops the ones gone from media_meta, and gives the
  same rows as a build from scratch. It is written into a new file beside the old one and renamed into place, the
  only file the CLI ever replaces (AGENTS.md rule 3)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.out :as out]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(def schema-version
  "Bumped whenever the tables or the ids change: an index of another version is rebuilt from scratch. 2: line ids
  of 8 characters."
  2)

(def default-version
  "The transcript version searched unless --version names another."
  "deepgram-nova-3-multi")

(def schema-sql
  "The tables. No wall-clock time in any row: the same media_meta gives the same rows on every Mac."
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE transcripts (
  id            INTEGER PRIMARY KEY,
  version       TEXT NOT NULL,     -- the transcript version (its tree), e.g. deepgram-nova-3-multi
  rel           TEXT NOT NULL,     -- the original's path under the version's tree
  clip          TEXT NOT NULL,     -- the clip part of its line ids
  json_sha256   TEXT NOT NULL,
  json_size     INTEGER NOT NULL,
  json_mtime    REAL NOT NULL,     -- for spotting a change only; never shown
  source_path   TEXT,              -- shown, never opened (AGENTS.md rule 1)
  source_name   TEXT,
  source_sha256 TEXT,
  duration      REAL,
  clock_kind    TEXT,
  start_tc      TEXT,
  rate          TEXT,
  tod_s         REAL,
  language      TEXT,
  engine        TEXT,
  UNIQUE (version, rel)
);
CREATE INDEX ix_transcripts_clip ON transcripts(version, clip);
CREATE TABLE cues (
  rowid         INTEGER PRIMARY KEY,
  id            TEXT NOT NULL,     -- the line id, <clip>.<n>
  transcript_id INTEGER NOT NULL REFERENCES transcripts(id),
  n             INTEGER NOT NULL,  -- 1-based place in its transcript
  start         REAL NOT NULL,
  end           REAL NOT NULL,
  text          TEXT NOT NULL,
  speaker       TEXT,
  language      TEXT,
  words         TEXT NOT NULL      -- json: [[word, start, end], ...]
);
CREATE INDEX ix_cues_transcript ON cues(transcript_id, n);
CREATE INDEX ix_cues_id ON cues(id);
CREATE VIRTUAL TABLE cues_fts USING fts5(
  text, content='cues', content_rowid='rowid', tokenize='porter unicode61 remove_diacritics 2');
CREATE TRIGGER cues_ai AFTER INSERT ON cues BEGIN
  INSERT INTO cues_fts (rowid, text) VALUES (new.rowid, new.text);
END;
CREATE TRIGGER cues_ad AFTER DELETE ON cues BEGIN
  INSERT INTO cues_fts (cues_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
END;")

;; ------------------------------------------------------------------------------------------------ where

(defn paths
  "Where the index and cuts live (decision 3). Under --local <folder> (tests, goldens): <folder>/search/ and
  <folder>/cuts/ (and frames as JPEGs in <folder>/frames/). Otherwise the cache in ~/Library/Caches/black-box-cli/
  (the index, the JPEGs, rendered cuts), new cuts in
  ~/Library/Application Support/black-box-cli/cuts/, and Black Box's old cuts folder read for `edl list`."
  [{:keys [local home]}]
  (if local
    (let [local (str (fs/absolutize local))]
      {:index (str (fs/path local "search" "transcripts.sqlite"))
       :frames (str (fs/path local "frames"))
       :pages (str (fs/path local "look"))
       :previews (str (fs/path local "previews"))
       :stills (str (fs/path local "stills"))
       :cuts (str (fs/path local "cuts"))})
    {:index (str (fs/path home "Library" "Caches" "black-box-cli" "search" "transcripts.sqlite"))
     :frames (str (fs/path home "Library" "Caches" "black-box-cli" "frames"))
     :pages (str (fs/path home "Library" "Application Support" "black-box-cli" "look"))
     :previews (str (fs/path home "Library" "Caches" "black-box-cli" "previews"))
     :cuts (str (fs/path home "Library" "Application Support" "black-box-cli" "cuts"))
     :stills (str (fs/path home "Library" "Application Support" "black-box-cli" "stills"))
     :old-cuts (str (fs/path home "Library" "Application Support" "Media Utilities" "media-mgmt" "cuts"))}))

(defn require-index!
  "The index's path, or error.code no-index when it hasn't been built."
  [{:keys [home] :as ctx}]
  (let [path (:index (paths ctx))]
    (when-not (fs/exists? path)
      (out/fail! "no-index" (str "no search index at " (out/home-path path home))
                 {:help ["Run `blackbox index` to build it from the transcripts"]}))
    path))

;; ------------------------------------------------------------------------------------------------ reading media_meta

(def ^:private bounce-re
  "\"C5900.MP4 2.json\": a duplicate Dropbox's File Provider made."
  #"\.[^./]+ \d+\.json$")

(defn- transcript-name?
  "Whether a json's name can be a transcript's, `<original name>.json`, judged by the name alone."
  [file-name]
  (and (not (re-find bounce-re file-name))
       (some? (re-find #"\.[^./]+\.json$" file-name))))

(defn- dataless
  "The json files under `tree` that Dropbox keeps online-only: never read, so never downloaded."
  [tree]
  (let [{:keys [exit out]} (process/sh "find" (str tree) "-type" "f" "-name" "*.json" "-flags" "+dataless")]
    (if (zero? exit) (set (remove str/blank? (str/split-lines out))) #{})))

(defn- sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- text-of [v] (some-> v str))

(defn- transcript-row
  "The index's row for a transcript `doc` read from `rel`, with its cues, or nil when `doc` isn't a transcript of
  `version`."
  [doc version rel]
  (let [{:keys [schema source producer clock cues]} doc]
    (when (and (map? doc)
               (str/starts-with? (str schema) "media-mgmt.transcript/")
               (let [label (:label producer)] (or (nil? label) (= label version))))
      (let [clip (ids/clip rel)]
        {:version version :rel rel :clip clip
         :source_path (:path source) :source_name (:name source) :source_sha256 (:sha256 source)
         :duration (:duration source)
         :clock_kind (:kind clock) :start_tc (:start_timecode clock) :rate (text-of (:rate clock))
         :tod_s (:time_of_day_start_s clock)
         :language (:language doc) :engine (:engine producer)
         :cues (vec (map-indexed
                     (fn [i c]
                       {:id (str clip "." (inc i)) :n (inc i) :start (:start c) :end (:end c)
                        :text (str/trim (str (:text c ""))) :speaker (:speaker c) :language (:language c)
                        :words (json/generate-string (mapv (fn [w] [(:w w "") (:start w) (:end w)]) (:words c)))})
                     cues))}))))

(defn- walk
  "Every json under the version's tree, sorted, as {:path :rel :file-name}; hidden folders and files left out."
  [tree]
  (->> (fs/glob tree "**.json")
       (map (fn [p] (let [rel-path (str (fs/relativize tree p))]
                      {:path (str p) :rel-path rel-path :file-name (str (fs/file-name p))})))
       (remove (fn [{:keys [rel-path]}] (some #(str/starts-with? % ".") (str/split rel-path #"/"))))
       (sort-by :rel-path)))

;; ------------------------------------------------------------------------------------------------ building

(def ^:private insert-sql
  "BEGIN;
INSERT INTO transcripts (version, rel, clip, json_sha256, json_size, json_mtime, source_path, source_name,
                         source_sha256, duration, clock_kind, start_tc, rate, tod_s, language, engine)
  SELECT d->>'version', d->>'rel', d->>'clip', d->>'json_sha256', d->>'json_size', d->>'json_mtime',
         d->>'source_path', d->>'source_name', d->>'source_sha256', d->>'duration', d->>'clock_kind',
         d->>'start_tc', d->>'rate', d->>'tod_s', d->>'language', d->>'engine'
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO cues (id, transcript_id, n, start, end, text, speaker, language, words)
  SELECT c.value->>'id', t.id, c.value->>'n', c.value->>'start', c.value->>'end', c.value->>'text',
         c.value->>'speaker', c.value->>'language', c.value->>'words'
  FROM json_each(:docs) j
  JOIN transcripts t ON t.version = j.value->>'version' AND t.rel = j.value->>'rel',
  json_each(j.value->'cues') c
  ORDER BY t.id, c.key;
COMMIT;")

(def ^:private drop-sql
  "BEGIN;
DELETE FROM cues WHERE transcript_id IN (SELECT value FROM json_each(:ids));
DELETE FROM transcripts WHERE id IN (SELECT value FROM json_each(:ids));
COMMIT;")

(defn- sqlite-version [path]
  (:v (db/one path "SELECT sqlite_version() AS v;")))

(defn- reusable?
  "Whether the index at `path` has this schema and this SQLite, so it can be updated rather than rebuilt."
  [path]
  (and (fs/exists? path)
       (try
         (let [m (into {} (map (juxt :key :value)) (db/query path "SELECT key, value FROM meta;"))]
           (and (= (str schema-version) (get m "schema_version"))
                (= (sqlite-version path) (get m "sqlite_version"))))
         (catch clojure.lang.ExceptionInfo _ false))))

(defn- fresh! [path]
  (db/run! path (str schema-sql "
INSERT INTO meta VALUES ('schema_version', :schema), ('sqlite_version', sqlite_version());")
           {:schema (str schema-version)}))

(def ^:private batch-bytes
  "Transcripts are written in batches of about this much json, one sqlite3 run each."
  4000000)

(defn- write-batches! [path rows]
  (loop [rows rows batch [] size 0]
    (let [flush! #(when (seq batch) (db/run! path insert-sql {:docs (json/generate-string batch)}))]
      (cond
        (empty? rows) (flush!)
        (> size batch-bytes) (do (flush!) (recur rows [] 0))
        :else (let [r (first rows)]
                (recur (rest rows) (conj batch r) (+ size 200 (reduce + (map #(+ 100 (count (:words %)) (count (:text %)))
                                                                             (:cues r))))))))))

(defn- check-clips!
  "error.code id-clash when two transcripts of one version share a clip (decision 2: fail loudly)."
  [path]
  (when-let [{:keys [clip rels]} (db/one path "SELECT clip, group_concat(rel, ', ') AS rels FROM transcripts
                                                GROUP BY version, clip HAVING count(*) > 1 ORDER BY clip LIMIT 1;")]
    (out/fail! "id-clash" (str "two transcripts share the line id prefix " clip ": " rels)
               {:help ["Tell the owner: line ids need a longer prefix (docs/DESIGN.md, decision 2)"]})))

(defn build!
  "Brings the index at `index-path` up to date with the transcripts of `version` under `meta`, or builds it from
  scratch when `rebuild` (or when its schema or SQLite differ). Returns the counts."
  [{:keys [meta index-path version rebuild progress]}]
  (let [tree (fs/path meta "transcripts" version)]
    (when-not (fs/directory? tree)
      (out/fail! "no-transcripts" (str "no transcripts of version " version ": " tree " is not a folder")
                 {:help ["Is Dropbox running, and is media_meta available offline?"]}))
    (fs/create-dirs (fs/parent index-path))
    (let [tmp (str index-path ".new-" (.pid (java.lang.ProcessHandle/current)))]
      (try
        (fs/delete-if-exists tmp)
        (if (and (not rebuild) (reusable? index-path))
          (fs/copy index-path tmp)
          (fresh! tmp))
        (let [known (into {} (map (juxt :rel identity))
                          (db/query tmp "SELECT id, rel, json_size, json_mtime, json_sha256 FROM transcripts
                                         WHERE version = :v;" {:v version}))
              offline (dataless tree)
              files (walk tree)
              total (count files)
              res (reduce
                   (fn [res {:keys [path rel-path file-name]}]
                     (let [rel (subs rel-path 0 (- (count rel-path) 5))
                           prev (known rel)
                           res (update res :seen conj rel)]
                       (when (and progress (zero? (mod (count (:seen res)) 250)))
                         (progress {:stage "transcripts" :done (count (:seen res)) :total total}))
                       (cond
                         (not (transcript-name? file-name)) (update res :skipped inc)

                         (and prev (= (:json_size prev) (fs/size path))
                              (== (:json_mtime prev) (/ (.toMillis (fs/last-modified-time path)) 1000.0)))
                         (update res :unchanged inc)

                         (offline path) (update res :skipped inc)

                         :else
                         (let [bs (fs/read-all-bytes path)
                               sha (sha256-hex bs)]
                           (if (and prev (= sha (:json_sha256 prev)))
                             (update res :unchanged inc)
                             (if-let [row (some-> (try (json/parse-string (String. ^bytes bs "UTF-8") true)
                                                       (catch Exception _ nil))
                                                  (transcript-row version rel))]
                               (-> res
                                   (update (if prev :changed :added) inc)
                                   (update :lines_added + (count (:cues row)))
                                   (update :drop (fnil conj []) (:id prev))
                                   (update :rows conj
                                           (assoc row :json_sha256 sha :json_size (alength bs)
                                                  :json_mtime (/ (.toMillis (fs/last-modified-time path)) 1000.0))))
                               (update res :skipped inc)))))))
                   {:added 0 :changed 0 :unchanged 0 :removed 0 :skipped 0 :lines_added 0 :seen #{} :rows []}
                   files)
              gone (remove (:seen res) (keys known))
              drop-ids (vec (concat (remove nil? (:drop res)) (map (comp :id known) gone)))]
          (when (seq drop-ids)
            (db/run! tmp drop-sql {:ids (json/generate-string drop-ids)}))
          (write-batches! tmp (:rows res))
          (check-clips! tmp)
          (fs/move tmp index-path {:replace-existing true :atomic-move true})
          (-> res
              (assoc :removed (count gone))
              (select-keys [:added :changed :unchanged :removed :skipped :lines_added])))
        (finally
          (fs/delete-if-exists tmp))))))
