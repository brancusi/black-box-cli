(ns black-box-cli.translations
  "English for the lines said in another language (Italian, mostly), from media_meta's
  `translations/<label>/v<N>/<rel>.<sha12>.en.json` (schema media-mgmt.translation/1): one file per transcript Black
  Box translated on the mini, `sha12` the start of that transcript file's sha256, with a cue for each line not in
  English (`i`, its 0-based place in the transcript, and the English `text`). Read into `translations.sqlite` beside
  the index, whole, whenever the folder's files change. A line gets English only from a translation of the very
  transcript file the index holds (by its sha256), so a transcript changed since is never given another's words; of
  several for one transcript, the one in the highest v<N>, then the newest."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def marker
  "The file that marks media_meta's translations folder as published by Black Box."
  ".media-mgmt-translations-root")

(def to
  "The language translated into."
  "en")

(def ^:private schema-version 1)

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE translations (
  transcript_sha256 TEXT PRIMARY KEY, -- the transcript file it translates (the index's json_sha256)
  rel               TEXT NOT NULL,
  label             TEXT NOT NULL,    -- <label>/v<N>, e.g. gemini-3.1-pro/v1
  made_at           TEXT
);
CREATE TABLE lines (
  transcript_sha256 TEXT NOT NULL,
  n                 INTEGER NOT NULL, -- the line's 1-based place in its transcript, as in its line id
  lang              TEXT,             -- what was said: it
  text              TEXT NOT NULL,    -- in English
  PRIMARY KEY (transcript_sha256, n)
);")

(def ^:private insert-sql
  "BEGIN;
INSERT INTO translations (transcript_sha256, rel, label, made_at)
  SELECT d->>'sha', d->>'rel', d->>'label', d->>'made_at' FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO lines (transcript_sha256, n, lang, text)
  SELECT d->>'sha', c.value->>'n', c.value->>'lang', c.value->>'text'
  FROM (SELECT value AS d FROM json_each(:docs)), json_each(d->'lines') c;
INSERT INTO meta VALUES ('schema_version', :schema), ('files', :files), ('skipped', :skipped);
COMMIT;")

(defn path
  "The translations' database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "translations.sqlite")))

(def ^:private file-re #"(.+)\.([0-9a-f]{12})\.([a-z]{2,3})\.json")

(defn- hidden? [rel] (some #(str/starts-with? % ".") (str/split rel #"/")))

(defn- files
  "Every translation into English under `root`: {:path :rel :sha12 :label :n}, label being <label>/v<N>."
  [root]
  (->> (for [l (when (fs/directory? root) (fs/list-dir root))
             :when (and (fs/directory? l) (not (str/starts-with? (str (fs/file-name l)) ".")))
             v (fs/list-dir l)
             :let [[_ n] (re-matches #"v(\d+)" (str (fs/file-name v)))]
             :when (and n (fs/directory? v))
             p (fs/glob v "**.json")
             :let [rel-path (str (fs/relativize v p))
                   [_ rel sha12 lang] (re-matches file-re rel-path)]
             :when (and rel (= to lang) (not (hidden? rel)))]
         {:path (str p) :rel rel :sha12 sha12 :label (str (fs/file-name l) "/" (fs/file-name v)) :n (parse-long n)})
       (sort-by (juxt :label :rel :sha12))))

(defn row
  "What translation file `doc`, found at `rel` with `sha12` in its name, gives: {:sha :rel :made_at :lines [{:n :lang
  :text}]}, or nil when it isn't an English translation of a transcript of that original."
  [doc rel sha12]
  (let [{:keys [schema source transcript cues producer]} doc
        sha (str (:sha256 transcript))]
    (when (and (map? doc)
               (= "media-mgmt.translation/1" schema)
               (= to (:to doc))
               (= rel (:rel source))
               (str/starts-with? sha sha12)
               (sequential? cues))
      {:sha sha :rel rel :made_at (some-> (:made_at producer) str)
       :lines (vec (for [c cues
                         :let [i (:i c) text (some-> (:text c) str str/trim)]
                         :when (and (int? i) (>= i 0) (seq text))]
                     {:n (inc i) :lang (some-> (:lang c) str) :text text}))})))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn- summary [db]
  (let [{:keys [files lines]} (db/one db "SELECT (SELECT count(*) FROM translations) AS files,
                                                 (SELECT count(*) FROM lines) AS lines;")]
    (array-map :to to
               :files files
               :lines lines
               :skipped (parse-long (get (meta-of db) "skipped" "0")))))

(defn build!
  "Brings the translations beside `index-path` up to date with media_meta's translations folder: their summary, or
  nil when media_meta has no published translations folder (and then an old database here is removed). A file that
  isn't one, or that Dropbox keeps online-only, is skipped and counted, and read again next time."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "translations")
        db (path index-path)]
    (if-not (fs/exists? (fs/path root marker))
      (do (fs/delete-if-exists db) nil)
      (let [fs* (files root)
            listing (str/join "\n" (map #(str (:label %) "/" (:rel %) "." (:sha12 %) " " (fs/size (:path %))) fs*))
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (fs/create-dirs (fs/parent db))
        (if (and m (= (str schema-version) (get m "schema_version")) (= listing (get m "files")))
          (summary db)
          (let [read (fn [{p :path :keys [rel sha12 label n]}]
                       (when-not (tools/online-only? p)
                         (some-> (try (json/parse-string (slurp p) true) (catch Exception _ nil))
                                 (row rel sha12)
                                 (assoc :label label :v n))))
                got (map (fn [f] [f (read f)]) fs*)
                skipped (count (remove second got))
                ;; one translation per transcript: the highest v<N>, then the newest
                docs (->> (keep second got)
                          (group-by :sha)
                          vals
                          (map #(last (sort-by (juxt :v :made_at) %)))
                          (sort-by :sha)
                          (map #(dissoc % :v)))
                tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
            (try
              (fs/delete-if-exists tmp)
              (db/run! tmp schema-sql {})
              (db/run! tmp insert-sql {:docs (json/generate-string docs)
                                       :schema (str schema-version)
                                       :files (if (pos? skipped) "" listing)
                                       :skipped (str skipped)})
              (fs/move tmp db {:replace-existing true :atomic-move true})
              (summary db)
              (finally (fs/delete-if-exists tmp)))))))))

(defn for-transcript
  "The English of a transcript's lines, by the transcript file's sha256: {n {:en text :lang what-was-said}}, or nil
  when Black Box hasn't translated it. A translated transcript's lines without one were said in English."
  [index-path sha]
  (let [db (path index-path)]
    (when (and sha (fs/exists? db)
               (db/one db "SELECT 1 AS y FROM translations WHERE transcript_sha256 = :s;" {:s sha}))
      (into {} (map (fn [{:keys [n text lang]}] [n {:en text :lang lang}]))
            (db/query db "SELECT n, text, lang FROM lines WHERE transcript_sha256 = :s;" {:s sha})))))
