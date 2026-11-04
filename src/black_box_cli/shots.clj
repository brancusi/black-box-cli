(ns black-box-cli.shots
  "The shot logs: what happens in each shot of a clip (where takes cut, framing, the camera's move, the action, the
  best few seconds and why, what's wrong with it), from media_meta's `shots/<version>/<rel>.shots.json` (schema
  blackbox.shots/1; docs/CONTRACT.md), read into `shots.sqlite` beside the transcripts' index with a full-text
  table over the words. Black Box makes them on the mini; never redone in place, so the folder's file list says
  when to read them again.

  Shot ids are `<clip>.s<n>`: the clip of the original's path, as in line and frame ids, and the shot's place in its
  log. The same media_meta gives the same ids on every Mac."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.out :as out]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def version
  "The shot logs read: Gemini 3.1 Pro with the first prompt, as Black Box publishes them."
  "gemini-3.1-pro/v1")

(def marker
  "The file that marks media_meta's shots folder as published by Black Box."
  ".blackbox-shots-root")

(def ^:private schema-version 1)

(def ^:private suffix ".shots.json")

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE clips (
  id            INTEGER PRIMARY KEY,
  rel           TEXT NOT NULL UNIQUE, -- the original's path
  clip          TEXT NOT NULL,        -- the clip part of its shot ids
  sha256        TEXT,                 -- the original's
  duration      REAL,
  proxy_rel     TEXT,
  summary       TEXT,
  tags          TEXT NOT NULL         -- json: [tag, ...]
);
CREATE INDEX ix_clips_clip ON clips(clip);
CREATE TABLE shots (
  rowid         INTEGER PRIMARY KEY,
  id            TEXT NOT NULL,        -- <clip>.s<n>
  clip_id       INTEGER NOT NULL REFERENCES clips(id),
  n             INTEGER NOT NULL,     -- 1-based place in its log
  start         REAL NOT NULL,
  end           REAL NOT NULL,
  framing       TEXT,
  camera        TEXT,
  action        TEXT,
  best_start    REAL,
  best_end      REAL,
  best_why      TEXT,
  issues        TEXT NOT NULL         -- json: [issue, ...]
);
CREATE INDEX ix_shots_clip ON shots(clip_id, n);
CREATE VIRTUAL TABLE shots_fts USING fts5(
  action, why, issues, summary, tags, tokenize='porter unicode61 remove_diacritics 2');")

(def ^:private insert-sql
  "BEGIN;
INSERT INTO clips (rel, clip, sha256, duration, proxy_rel, summary, tags)
  SELECT d->>'rel', d->>'clip', d->>'sha256', d->>'duration', d->>'proxy_rel', d->>'summary', d->>'tags'
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO shots (id, clip_id, n, start, end, framing, camera, action, best_start, best_end, best_why, issues)
  SELECT s.value->>'id', c.id, s.value->>'n', s.value->>'start', s.value->>'end', s.value->>'framing',
         s.value->>'camera', s.value->>'action', s.value->>'best_start', s.value->>'best_end', s.value->>'best_why',
         s.value->>'issues'
  FROM json_each(:docs) j JOIN clips c ON c.rel = j.value->>'rel', json_each(j.value->'shots') s
  ORDER BY c.id, s.key;
INSERT INTO shots_fts (rowid, action, why, issues, summary, tags)
  SELECT s.rowid, coalesce(s.action, ''), coalesce(s.best_why, ''),
         (SELECT coalesce(group_concat(value, ', '), '') FROM json_each(s.issues)),
         coalesce(c.summary, ''), (SELECT coalesce(group_concat(value, ', '), '') FROM json_each(c.tags))
  FROM shots s JOIN clips c ON c.id = s.clip_id;
INSERT INTO meta VALUES ('schema_version', :schema), ('files', :files), ('skipped', :skipped);
COMMIT;")

(defn path
  "The shot logs' database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "shots.sqlite")))

(defn shot-id
  "A shot's id: its clip and its place."
  [clip n]
  (str clip ".s" n))

(defn- walk
  "Every shot log of `version` under `tree`, sorted, with its rel; hidden folders and files left out."
  [tree]
  (->> (fs/glob tree (str "**" suffix))
       (map (fn [p] (let [rel-path (str (fs/relativize tree p))]
                      {:path (str p) :rel (subs rel-path 0 (- (count rel-path) (count suffix)))})))
       (remove (fn [{:keys [rel]}] (some #(str/starts-with? % ".") (str/split rel #"/"))))
       (sort-by :rel)))

(defn- texts [v] (if (sequential? v) (mapv str (remove nil? v)) []))

(defn- num-or-nil [v] (when (number? v) (double v)))

(defn clip-row
  "The index's rows for shot log `doc` found at `rel`, or nil when it isn't a shot log of that original."
  [doc rel]
  (let [{:keys [schema clip proxy shots summary tags]} doc]
    (when (and (map? doc)
               (= "blackbox.shots/1" schema)
               (= rel (:rel clip))
               (sequential? shots)
               (every? #(and (map? %) (number? (:start_s %)) (number? (:end_s %))) shots))
      (let [c (ids/clip rel)]
        {:rel rel :clip c :sha256 (:sha256 clip) :duration (num-or-nil (:duration_s clip)) :proxy_rel (:rel proxy)
         :summary (some-> summary str) :tags (json/generate-string (texts tags))
         :shots (vec (map-indexed
                      (fn [i {:keys [start_s end_s framing camera action best issues]}]
                        {:id (shot-id c (inc i)) :n (inc i) :start (double start_s) :end (double end_s)
                         :framing (some-> framing str) :camera (some-> camera str) :action (some-> action str)
                         :best_start (num-or-nil (:start_s best)) :best_end (num-or-nil (:end_s best))
                         :best_why (some-> (:why best) str) :issues (json/generate-string (texts issues))})
                      shots))}))))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn- summary [db]
  (let [m (meta-of db)]
    (array-map :version version
               :clips (:n (db/one db "SELECT count(*) AS n FROM clips;"))
               :shots (:n (db/one db "SELECT count(*) AS n FROM shots;"))
               :skipped (parse-long (get m "skipped" "0")))))

(defn build!
  "Brings the shot logs beside `index-path` up to date with media_meta's: their summary, or nil when media_meta has
  no published shots folder (and then an old database here is removed). Read again whole whenever the folder's
  files change; a log that isn't one, or that Dropbox keeps online-only, is skipped and counted."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "shots")
        tree (fs/path root version)
        db (path index-path)]
    (if-not (fs/exists? (fs/path root marker))
      (do (fs/delete-if-exists db) nil)
      (let [files (if (fs/directory? tree) (walk tree) [])
            listing (str/join "\n" (map #(str (:rel %) " " (fs/size (:path %))) files))
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (fs/create-dirs (fs/parent db))
        (if (and m (= (str schema-version) (get m "schema_version")) (= listing (get m "files")))
          (summary db)
          (let [parsed (map (fn [{p :path rel :rel}]
                              (when-not (tools/online-only? p)
                                (some-> (try (json/parse-string (slurp p) true) (catch Exception _ nil))
                                        (clip-row rel))))
                            files)
                rows (vec (remove nil? parsed))
                skipped (- (count files) (count rows))
                tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
            (try
              (fs/delete-if-exists tmp)
              (db/run! tmp schema-sql {})
              (db/run! tmp insert-sql {:docs (json/generate-string rows)
                                       :schema (str schema-version)
                                       ;; a skipped log is read again next time, in case Dropbox has it by then
                                       :files (if (pos? skipped) "" listing)
                                       :skipped (str skipped)})
              (fs/move tmp db {:replace-existing true :atomic-move true})
              (summary db)
              (finally (fs/delete-if-exists tmp)))))))))

;; ------------------------------------------------------------------------------------------------ reading

(defn available?
  "Whether shot logs have been indexed."
  [index-path]
  (fs/exists? (path index-path)))

(defn require!
  "The shot logs' database, or error.code no-shots when none have been indexed."
  [index-path home]
  (let [db (path index-path)]
    (when-not (fs/exists? db)
      (out/fail! "no-shots" (str "no shot logs at " (out/home-path db home))
                 {:help ["Run `blackbox index`: it reads media_meta's shot logs when Black Box has made some"]}))
    db))

(def ^:private shot-id-re #"([a-z2-7]{8})\.s([1-9][0-9]*)")

(def example "k7f3q2ab.s3")

(defn shot-id?
  "Whether `v` looks like a shot id."
  [v]
  (some? (re-matches shot-id-re (str/lower-case (str/trim (str v))))))

(defn- parse-id [v]
  (if-let [[_ c n] (re-matches shot-id-re (str/lower-case (str/trim (str v))))]
    {:clip c :n (parse-long n)}
    (out/fail! "bad-id" (str "'" v "' isn't a shot id (like " example ")")
               {:help ["Run `blackbox shots <words>` for shot ids"]})))

(def cols
  "Every column a shot row has: the shot's and its clip's."
  "s.id, s.n, s.start, s.end, s.framing, s.camera, s.action, s.best_start, s.best_end, s.best_why, s.issues,
   c.rel, c.clip, c.sha256, c.duration, c.proxy_rel, c.summary, c.tags")

(defn lookup
  "The shot `id` names, with its clip's fields, or error.code not-found (no-shots without shot logs)."
  [index-path home id]
  (let [db (require! index-path home)
        {:keys [clip n]} (parse-id id)]
    (or (db/one db (str "SELECT " cols " FROM shots s JOIN clips c ON c.id = s.clip_id
                         WHERE c.clip = :clip AND s.n = :n;")
                {:clip clip :n n})
        (out/fail! "not-found" (str "no shot " (shot-id clip n) " in the shot logs")
                   {:help ["Run `blackbox shots <words>` for shot ids"]}))))

(defn at-each
  "The shot each [rel t] of `moments` falls in, in order (nil where none, and all nil without shot logs). At a cut
  between two shots, the later one."
  [index-path moments]
  (if-not (and (available? index-path) (seq moments))
    (vec (repeat (count moments) nil))
    (let [rows (db/query (path index-path)
                         (str "SELECT k.key AS k, " cols " FROM json_each(:moments) k
                               JOIN clips c ON c.rel = k.value->>0
                               JOIN shots s ON s.clip_id = c.id AND s.start <= k.value->>1 AND k.value->>1 <= s.end
                               ORDER BY k.key, s.n DESC;")
                         {:moments (json/generate-string (map (fn [[rel t]] [rel (double (or t -1.0))]) moments))})
          by-k (reduce (fn [m r] (if (contains? m (:k r)) m (assoc m (:k r) (dissoc r :k)))) {} rows)]
      (mapv #(get by-k %) (range (count moments))))))

(defn at
  "The shot of the original at `rel` that time `t` falls in, or nil (also without shot logs)."
  [index-path rel t]
  (when (and rel t) (first (at-each index-path [[rel t]]))))

(defn issues
  "A shot row's issues, as a list."
  [r]
  (texts (try (json/parse-string (str (:issues r))) (catch Exception _ nil))))

(defn tags
  "A shot row's clip's tags, as a list."
  [r]
  (texts (try (json/parse-string (str (:tags r))) (catch Exception _ nil))))

(defn best
  "A shot's best moment, [start end], clamped to the shot; the whole shot when the log names none."
  [{:keys [start end best_start best_end]}]
  (let [a (if best_start (max start (double best_start)) start)
        b (if best_end (min end (double best_end)) end)]
    (if (< a b) [a b] [start end])))

(defn- range-text [[a b]] (str (tc/hms a) "-" (tc/hms b)))

(defn issues-text
  "A shot row's issues in words, or nil when it has none."
  [r]
  (not-empty (str/join ", " (issues r))))

(defn brief
  "A shot as `frame` and `line` show it."
  [r]
  (array-map :id (:id r)
             :at (tc/hms (:start r))
             :end (tc/hms (:end r))
             :framing (:framing r)
             :camera (:camera r)
             :action (:action r)
             :best (range-text (best r))
             :issues (or (issues-text r) "none")))

(defn best-text
  "A shot's best moment as `0:02.0-0:05.0`."
  [r]
  (range-text (best r)))

(defn search
  "Shots matching `q` (an FTS5 query, or nil for every shot in scope) where `where` (SQL over shots `s` and clips
  `c`, with `params`) holds, best first (by date without `q`), at most `per-clip` from one clip (0: no limit) and
  `limit` in all: {:total :files :rows}."
  [db {:keys [q where params limit per-clip]}]
  (let [base (str "FROM shots s JOIN clips c ON c.id = s.clip_id"
                  (when q " JOIN shots_fts f ON f.rowid = s.rowid")
                  " WHERE " (str/join " AND " (cond-> (vec where) q (conj "shots_fts MATCH :q"))))
        rank (if q "bm25(shots_fts)" "0")
        [[{:keys [total files]}] rows]
        (db/run! db (str "SELECT count(*) AS total, count(DISTINCT c.id) AS files " base ";
                          WITH hits AS MATERIALIZED (SELECT " cols ", " rank " AS rk " base ")
                          SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY rel ORDER BY rk, n) AS rn FROM hits)
                          WHERE :per = 0 OR rn <= :per
                          ORDER BY rk, " (if q "clip, n" "rel, n") " LIMIT :limit;")
                 (cond-> (assoc params :limit limit :per (or per-clip 0)) q (assoc :q q)))]
    {:total total :files files :rows (mapv #(dissoc % :rk :rn) (or rows []))}))
