(ns black-box-cli.people
  "The names: media_meta's people snapshot (`people/registry/<stamp>.json` and the assignments file it names, schema
  media-mgmt.people/1; docs/CONTRACT.md), read into `people.sqlite` beside the transcripts' index. The newest
  registry wins, and its assignments file must match its sha256. A name joins a transcript on (rel, transcript
  sha256) and, for a voice, the speaker label: never on line ids. Read-only: the People page in Black Box is where
  names are made."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.out :as out]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(def marker
  "The file that marks media_meta's people folder as published by Black Box."
  ".media-mgmt-people-root")

(def kinds
  "The kinds of name this CLI reads; a line of another kind is skipped."
  #{"voice" "face"})

(def ^:private schema-version 1)

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE people (
  id      TEXT PRIMARY KEY,
  n       INTEGER NOT NULL,          -- its place in the registry
  name    TEXT NOT NULL,
  aliases TEXT NOT NULL              -- json: [alias, ...]
);
CREATE TABLE names (
  kind              TEXT NOT NULL,   -- voice or face
  rel               TEXT NOT NULL,
  source_sha256     TEXT,
  transcript        TEXT,            -- the transcript version
  transcript_sha256 TEXT,
  label             TEXT NOT NULL,   -- a speaker (S1) or a face (F1)
  person            TEXT NOT NULL,
  how               TEXT NOT NULL,   -- confirmed or match
  score             REAL,
  ranges            TEXT NOT NULL    -- json: [[start, end], ...]
);
CREATE INDEX ix_names_rel ON names(rel, label);
CREATE INDEX ix_names_person ON names(person);")

(def ^:private insert-sql
  "BEGIN;
INSERT INTO people (id, n, name, aliases)
  SELECT value->>'id', key, value->>'name', coalesce(value->'aliases', '[]') FROM json_each(:people);
INSERT INTO names (kind, rel, source_sha256, transcript, transcript_sha256, label, person, how, score, ranges)
  SELECT value->>'kind', value->>'rel', value->>'source_sha256', value->>'transcript', value->>'transcript_sha256',
         value->>'label', value->>'person', value->>'how', value->>'score', value->'ranges'
  FROM json_each(:names);
INSERT INTO meta VALUES ('schema_version', :schema), ('snapshot', :snapshot), ('content_sha256', :content),
                        ('at', :at), ('matching', :matching), ('lines', :lines);
COMMIT;")

(defn path
  "The names' database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "people.sqlite")))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn- newest-registry [root]
  (->> (fs/glob (fs/path root "registry") "*.json")
       (filter #(re-matches #"\d{8}T\d{6}Z\.json" (str (fs/file-name %))))
       (sort-by #(str (fs/file-name %)))
       last))

(defn- summary [db]
  (let [m (meta-of db)]
    (array-map :snapshot (get m "snapshot")
               :at (get m "at")
               :people (:n (db/one db "SELECT count(*) AS n FROM people;"))
               :names (parse-long (get m "lines" "0")))))

(defn build!
  "Brings the names beside `index-path` up to date with media_meta's newest people snapshot: its summary, a string
  saying why it was left as it was, or nil when media_meta has no published people folder."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "people")
        db (path index-path)
        registry (when (fs/exists? (fs/path root marker)) (newest-registry root))]
    (when registry
      (let [snapshot (str/replace (str (fs/file-name registry)) #"\.json$" "")
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (cond
          (and m (= (str schema-version) (get m "schema_version")) (= snapshot (get m "snapshot")))
          (summary db)

          (tools/online-only? registry) "skipped: the newest snapshot is online-only"

          :else
          (let [reg (try (json/parse-string (slurp (str registry)) true) (catch Exception _ nil))
                assignments (some->> (:assignments reg) (fs/path root))
                bs (when (and assignments (fs/exists? assignments) (not (tools/online-only? assignments)))
                     (fs/read-all-bytes assignments))]
            (cond
              (not= "media-mgmt.people/1" (:schema reg)) (str "skipped: " snapshot " isn't a people snapshot")
              (nil? bs) (str "skipped: " (:assignments reg) " is missing or online-only")
              (not= (:assignments_sha256 reg) (frames/sha256-hex bs))
              (str "skipped: " (:assignments reg) " doesn't match its sha256")
              :else
              (let [names (->> (str/split-lines (String. ^bytes bs "UTF-8"))
                               (remove str/blank?)
                               (keep #(try (json/parse-string % true) (catch Exception _ nil)))
                               (filter #(kinds (:kind %))))
                    tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
                (try
                  (fs/delete-if-exists tmp)
                  (db/run! tmp schema-sql {})
                  (db/run! tmp insert-sql {:people (json/generate-string (:people reg))
                                           :names (json/generate-string names)
                                           :schema (str schema-version)
                                           :snapshot snapshot
                                           :content (str (:content_sha256 reg))
                                           :at (str (:at reg))
                                           :matching (if (:matching reg) (json/generate-string (:matching reg)) "")
                                           :lines (str (count names))})
                  (fs/move tmp db {:replace-existing true :atomic-move true})
                  (summary db)
                  (finally (fs/delete-if-exists tmp)))))))))))

;; ------------------------------------------------------------------------------------------------ reading

(defn available?
  "Whether the names have been indexed."
  [index-path]
  (fs/exists? (path index-path)))

(defn require!
  "The names' database, or error.code no-people when media_meta had none to index."
  [index-path]
  (let [db (path index-path)]
    (when-not (fs/exists? db)
      (out/fail! "no-people" "no names in the index: media_meta has no people folder, or `index` hasn't run since"
                 {:help ["Run `blackbox index`" "Names are made on Black Box's People page and published by the mini"]}))
    db))

(defn matching
  "The scoring the snapshot was made with ({:voice {...} :face {...} :top_k :max_refs}), or nil before 0.36.0."
  [db]
  (let [m (get (meta-of db) "matching")]
    (when-not (str/blank? m) (json/parse-string m true))))

(defn snapshot-at
  "When the snapshot was made."
  [db]
  (get (meta-of db) "at"))

(defn people
  "Everyone in the snapshot, in its order: [{:id :name :aliases}]."
  [db]
  (mapv #(update % :aliases json/parse-string)
        (db/query db "SELECT id, name, aliases FROM people ORDER BY n;")))

(defn names
  "Every name line of `kind` (all kinds when nil), with ranges read: [{:kind :rel :transcript_sha256 :label :person
  :how :score :ranges ...}]."
  [db kind]
  (mapv #(update % :ranges json/parse-string)
        (db/query db (str "SELECT * FROM names" (when kind " WHERE kind = :kind") " ORDER BY rel, kind DESC, label;")
                  {:kind kind})))

(defn voice-names
  "Who each voice is: {[rel transcript-version transcript-sha256 label] person-name}, or {} without names."
  [index-path]
  (if-not (available? index-path)
    {}
    (let [db (path index-path)
          who (into {} (map (juxt :id :name)) (people db))]
      (into {} (map (fn [n] [[(:rel n) (:transcript n) (:transcript_sha256 n) (:label n)] (who (:person n))]))
            (names db "voice")))))

(defn- folded
  "A name to compare: no accents, lower case, single spaces."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn resolve!
  "The person `q` names: by name or alias, ignoring case and accents, else by a unique start of one. error.code
  not-found or ambiguous otherwise."
  [db q]
  (let [everyone (people db)
        f (folded q)
        called (fn [p] (map folded (cons (:name p) (:aliases p))))
        exact (filter #(some #{f} (called %)) everyone)
        starts (filter #(some (fn [n] (str/starts-with? n f)) (called %)) everyone)
        found (if (seq exact) exact starts)]
    (cond
      (str/blank? f) (out/fail! "usage" "--person needs a name" {:help ["Run `blackbox people` for the names"]})
      (empty? found) (out/fail! "not-found" (str "no one called '" q "' in the names")
                                {:help ["Run `blackbox people` for the names"]})
      (next found) (out/fail! "ambiguous" (str "'" q "' could be " (str/join ", " (map :name found)))
                              {:help ["Use more of the name"]})
      :else (first found))))
