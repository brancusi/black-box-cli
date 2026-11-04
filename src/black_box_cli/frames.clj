(ns black-box-cli.frames
  "This Mac's picture index: a cache built from media_meta's frames files (`embeddings/<label>/<rel>.frames.json`,
  schema media-mgmt.embeddings/1; docs/CONTRACT.md), beside the transcripts' index. The frames' times
  and files are in SQLite (`frames.sqlite`); their vectors, float32, are in one file named by its content
  (`frames-<id>.f32`), made from one file per frames file (`vectors/<sha256>.f32`), so an update converts only
  what changed. Searching scores every frame in scope exactly, on all cores (docs/DESIGN.md, decision 6).

  Frame ids are `<clip>.f<n>`: the clip of the original's path, as in line ids, and the frame's place in its file.
  The same media_meta gives the same ids and the same index on every Mac."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.out :as out]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.nio ByteBuffer ByteOrder)
   (java.security MessageDigest)
   (java.util Base64)))

(def label
  "The frames' version searched: SigLIP 2 base at a frame every 5 s, as Black Box publishes it."
  "google/siglip2-base-patch16-256-5s")

(def schema-version
  "Bumped whenever the tables change: an index of another version is rebuilt from scratch."
  1)

(def marker
  "The file that marks media_meta's embeddings folder as published by Black Box."
  ".media-mgmt-embeddings-root")

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE frame_files (
  id            INTEGER PRIMARY KEY,
  rel           TEXT NOT NULL UNIQUE, -- the original's path
  clip          TEXT NOT NULL,        -- the clip part of its frame ids
  json_sha256   TEXT NOT NULL,
  json_size     INTEGER NOT NULL,
  json_mtime    REAL NOT NULL,        -- for spotting a change only
  source_name   TEXT,
  source_sha256 TEXT,
  start_tc      TEXT,
  rate          TEXT,
  duration      REAL,
  proxy_rel     TEXT,
  proxy_size    INTEGER,
  model         TEXT,                 -- the producer's model repo, checked against look's query
  dim           INTEGER NOT NULL,
  count         INTEGER NOT NULL,
  first_row     INTEGER NOT NULL DEFAULT 0  -- its first vector's row in the vectors file
);
CREATE INDEX ix_frame_files_clip ON frame_files(clip);
CREATE TABLE frame_items (
  file_id       INTEGER NOT NULL REFERENCES frame_files(id),
  n             INTEGER NOT NULL,     -- 1, 2, … in the file
  t             REAL NOT NULL,        -- seconds from the proxy's first frame
  pts           REAL NOT NULL,        -- the frame's own time in the proxy: what `frame` seeks to
  frame         INTEGER,
  PRIMARY KEY (file_id, n)
);")

(defn paths
  "The picture index's files, beside the transcripts' index at `index-path`."
  [index-path]
  (let [dir (fs/parent index-path)]
    {:db (str (fs/path dir "frames.sqlite"))
     :vectors (str (fs/path dir "vectors"))
     :dir (str dir)}))

;; ------------------------------------------------------------------------------------------------ vectors

(def ^:private half->float
  (delay (let [t (float-array 65536)]
           (dotimes [i 65536] (aset t i (Float/float16ToFloat (unchecked-short i))))
           t)))

(defn- f16->f32-bytes
  "float16 little-endian bytes as float32 little-endian bytes."
  ^bytes [^bytes bs]
  (let [n (quot (alength bs) 2)
        shorts (short-array n)
        _ (.get (.asShortBuffer (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN)) shorts)
        ^floats table @half->float
        floats (float-array n)
        _ (dotimes [i n] (aset floats i (aget table (bit-and (aget shorts i) 0xffff))))
        out (ByteBuffer/allocate (* 4 n))]
    (.put (.asFloatBuffer (.order out ByteOrder/LITTLE_ENDIAN)) floats)
    (.array out)))

(defn read-floats
  "A float32 little-endian file as a float array."
  ^floats [path]
  (let [bs (fs/read-all-bytes path)
        fl (float-array (quot (alength bs) 4))]
    (.get (.asFloatBuffer (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN)) fl)
    fl))

(defn write-new!
  "Writes `bs` to `path` through a temporary file beside it; an existing `path` (the same content, by its name) is
  kept."
  [path ^bytes bs]
  (when-not (fs/exists? path)
    (fs/create-dirs (fs/parent path))
    (let [tmp (str path ".new-" (.pid (java.lang.ProcessHandle/current)))]
      (try
        (fs/write-bytes tmp bs)
        (try (fs/move tmp path {:atomic-move true}) (catch java.nio.file.FileAlreadyExistsException _ nil))
        (finally (fs/delete-if-exists tmp))))))

;; ------------------------------------------------------------------------------------------------ reading media_meta

(defn sha256-hex
  "The SHA-256 of `bs`, as lowercase hex."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(def ^:private suffix ".frames.json")

(defn- dataless [tree]
  (let [{:keys [exit out]} (process/sh "find" (str tree) "-type" "f" "-name" (str "*" suffix) "-flags" "+dataless")]
    (if (zero? exit) (set (remove str/blank? (str/split-lines out))) #{})))

(defn- walk
  "Every frames file of `label` under `tree`, sorted, with its rel; hidden folders and files left out."
  [tree]
  (->> (fs/glob tree (str "**" suffix))
       (map (fn [p] (let [rel-path (str (fs/relativize tree p))]
                      {:path (str p) :rel (subs rel-path 0 (- (count rel-path) (count suffix)))})))
       (remove (fn [{:keys [rel]}] (some #(str/starts-with? % ".") (str/split rel #"/"))))
       (sort-by :rel)))

(defn- file-row
  "The index's row for frames file `doc` found at `rel`, with its items and float32 vectors, or nil when it isn't a
  frames file of `label` for that original."
  [doc rel]
  (let [{:keys [schema source proxy producer dim dtype items vectors]} doc
        vec-bytes (try (.decode (Base64/getDecoder) (str vectors)) (catch Exception _ nil))]
    (when (and (= "media-mgmt.embeddings/1" schema)
               (= label (:label producer))
               (= rel (:rel source))
               (= "float16" dtype)
               (pos-int? dim)
               (sequential? items)
               vec-bytes
               (= (alength ^bytes vec-bytes) (* 2 dim (count items))))
      {:rel rel :clip (ids/clip rel)
       :source_name (:name source) :source_sha256 (:sha256 source) :start_tc (:start_timecode source)
       :rate (some-> (:rate source) str) :duration (:duration source)
       :proxy_rel (:rel proxy) :proxy_size (:size proxy) :model (get-in producer [:model :repo])
       :dim dim :count (count items)
       :items (mapv (fn [{:keys [n t pts frame]}] {:n n :t t :pts (or pts t) :frame frame}) items)
       :vectors (f16->f32-bytes vec-bytes)})))

;; ------------------------------------------------------------------------------------------------ building

(def ^:private insert-sql
  "BEGIN;
INSERT INTO frame_files (rel, clip, json_sha256, json_size, json_mtime, source_name, source_sha256, start_tc, rate,
                         duration, proxy_rel, proxy_size, model, dim, count)
  SELECT d->>'rel', d->>'clip', d->>'json_sha256', d->>'json_size', d->>'json_mtime', d->>'source_name',
         d->>'source_sha256', d->>'start_tc', d->>'rate', d->>'duration', d->>'proxy_rel', d->>'proxy_size',
         d->>'model', d->>'dim', d->>'count'
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO frame_items (file_id, n, t, pts, frame)
  SELECT f.id, i.value->>'n', i.value->>'t', i.value->>'pts', i.value->>'frame'
  FROM json_each(:docs) j JOIN frame_files f ON f.rel = j.value->>'rel', json_each(j.value->'items') i;
COMMIT;")

(def ^:private drop-sql
  "BEGIN;
DELETE FROM frame_items WHERE file_id IN (SELECT value FROM json_each(:ids));
DELETE FROM frame_files WHERE id IN (SELECT value FROM json_each(:ids));
COMMIT;")

(def ^:private finish-sql
  "The files' rows in the vectors file, in order of rel; the vectors file's name and size."
  "UPDATE frame_files SET first_row = (SELECT coalesce(sum(g.count), 0) FROM frame_files g WHERE g.rel < frame_files.rel);
INSERT OR REPLACE INTO meta VALUES ('rows', (SELECT coalesce(sum(count), 0) FROM frame_files)),
                                   ('dim', (SELECT coalesce(max(dim), 0) FROM frame_files)),
                                   ('vectors', :vectors);")

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

(defn- check-clips! [path]
  (when-let [{:keys [clip rels]} (db/one path "SELECT clip, group_concat(rel, ', ') AS rels FROM frame_files
                                                GROUP BY clip HAVING count(*) > 1 ORDER BY clip LIMIT 1;")]
    (out/fail! "id-clash" (str "two frames files share the frame id prefix " clip ": " rels)
               {:help ["Tell the owner: ids need a longer prefix (docs/DESIGN.md, decision 2)"]})))

(defn- vectors-file!
  "The vectors of every file in `tmp`, concatenated in row order into `frames-<id>.f32` (made only when missing);
  its name. A file's vectors come from vectors/<sha256>.f32, made again from its frames file when that's gone."
  [{:keys [dir vectors]} tmp tree]
  (let [files (db/query tmp "SELECT rel, json_sha256 FROM frame_files ORDER BY rel;")
        id (subs (sha256-hex (.getBytes (str/join "\n" (map :json_sha256 files)) "UTF-8")) 0 16)
        name (str "frames-" id ".f32")
        target (fs/path dir name)]
    (when-not (fs/exists? target)
      (let [baos (java.io.ByteArrayOutputStream.)]
        (doseq [{:keys [rel json_sha256]} files]
          (let [cached (fs/path vectors (str json_sha256 ".f32"))]
            (when-not (fs/exists? cached)
              (let [row (file-row (json/parse-string (slurp (str (fs/path tree (str rel suffix)))) true) rel)]
                (write-new! cached (:vectors row))))
            (.write baos ^bytes (fs/read-all-bytes cached))))
        (write-new! target (.toByteArray baos))))
    name))

(defn- tidy!
  "Removes vectors files no longer named by the index: old frames-*.f32 and per-file caches of files gone."
  [{:keys [dir vectors db]}]
  (let [current (:value (db/one db "SELECT value FROM meta WHERE key = 'vectors';"))
        shas (set (map :json_sha256 (db/query db "SELECT json_sha256 FROM frame_files;")))]
    (doseq [f (fs/glob dir "frames-*.f32") :when (not= current (str (fs/file-name f)))]
      (fs/delete-if-exists f))
    (when (fs/directory? vectors)
      (doseq [f (fs/glob vectors "*.f32") :when (not (shas (str/replace (str (fs/file-name f)) #"\.f32$" "")))]
        (fs/delete-if-exists f)))))

(defn build!
  "Brings the picture index beside `index-path` up to date with media_meta's frames files, or builds it from scratch
  when `rebuild`. Returns the counts, or nil when media_meta has no published embeddings folder."
  [{:keys [meta index-path rebuild progress]}]
  (let [root (fs/path meta "embeddings")
        tree (fs/path root label)
        {:keys [db] :as p} (paths index-path)]
    (when (and (fs/exists? (fs/path root marker)) (fs/directory? tree))
      (fs/create-dirs (:vectors p))
      (let [tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
        (try
          (fs/delete-if-exists tmp)
          (if (and (not rebuild) (reusable? db)) (fs/copy db tmp) (fresh! tmp))
          (let [known (into {} (map (juxt :rel identity))
                            (db/query tmp "SELECT id, rel, json_size, json_mtime, json_sha256 FROM frame_files;"))
                offline (dataless tree)
                files (walk tree)
                total (count files)
                res (reduce
                     (fn [res {:keys [path rel]}]
                       (let [prev (known rel)
                             mtime (/ (.toMillis (fs/last-modified-time path)) 1000.0)
                             res (update res :seen conj rel)]
                         (when (and progress (zero? (mod (count (:seen res)) 250)))
                           (progress {:stage "frames" :done (count (:seen res)) :total total}))
                         (cond
                           (and prev (= (:json_size prev) (fs/size path)) (== (:json_mtime prev) mtime))
                           (update res :unchanged inc)

                           (offline path) (update res :skipped inc)

                           :else
                           (let [bs (fs/read-all-bytes path)
                                 sha (sha256-hex bs)]
                             (if (and prev (= sha (:json_sha256 prev)))
                               (update res :unchanged inc)
                               (if-let [row (some-> (try (json/parse-string (String. ^bytes bs "UTF-8") true)
                                                         (catch Exception _ nil))
                                                    (file-row rel))]
                                 (do (write-new! (fs/path (:vectors p) (str sha ".f32")) (:vectors row))
                                     (-> res
                                         (update (if prev :changed :added) inc)
                                         (update :drop conj (:id prev))
                                         (update :rows conj (-> row
                                                                (dissoc :vectors)
                                                                (assoc :json_sha256 sha :json_size (alength bs)
                                                                       :json_mtime mtime)))))
                                 (update res :skipped inc)))))))
                     {:added 0 :changed 0 :unchanged 0 :removed 0 :skipped 0 :seen #{} :rows [] :drop []}
                     files)
                gone (remove (:seen res) (keys known))
                drop-ids (vec (concat (remove nil? (:drop res)) (map (comp :id known) gone)))]
            (when (seq drop-ids)
              (db/run! tmp drop-sql {:ids (json/generate-string drop-ids)}))
            (doseq [batch (partition-all 200 (:rows res))]
              (db/run! tmp insert-sql {:docs (json/generate-string batch)}))
            (check-clips! tmp)
            (db/run! tmp finish-sql {:vectors (vectors-file! p tmp tree)})
            (fs/move tmp db {:replace-existing true :atomic-move true})
            (tidy! p)
            (-> res
                (assoc :removed (count gone)
                       :frames (or (some-> (db/one db "SELECT value FROM meta WHERE key = 'rows';") :value parse-long) 0))
                (select-keys [:added :changed :unchanged :removed :skipped :frames])))
          (finally (fs/delete-if-exists tmp)))))))

;; ------------------------------------------------------------------------------------------------ searching

(defn require-index!
  "The picture index's files, or error.code no-index when it hasn't been built (or media_meta has no frames)."
  [index-path home]
  (let [{:keys [db] :as p} (paths index-path)]
    (when-not (fs/exists? db)
      (out/fail! "no-index" (str "no picture index at " (out/home-path db home))
                 {:help ["Run `blackbox index` to build it from media_meta's frames files"]}))
    (let [m (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;"))]
      (assoc p :rows (parse-long (get m "rows" "0")) :dim (parse-long (get m "dim" "0"))
             :bin (str (fs/path (:dir p) (get m "vectors" "")))))))

(defn dot
  "The dot product of `q` with the `dim` floats of `m` from `off`."
  ^double [^floats m ^long off ^floats q ^long dim]
  (loop [k 0 s 0.0]
    (if (< k dim) (recur (inc k) (+ s (* (aget m (+ off k)) (aget q k)))) s)))

(defn scores
  "The score (cosine: the vectors are L2-normalised) of every row in `ranges` ([first count] pairs) against `q`, on
  all cores: a double array as long as the vectors file."
  [^floats m ^floats q dim rows ranges]
  (let [out (double-array rows)
        cores (.availableProcessors (Runtime/getRuntime))
        total (reduce + (map second ranges))
        per (max 1 (long (Math/ceil (/ total (double cores)))))
        groups (loop [rs ranges acc [] cur [] n 0]
                 (cond
                   (empty? rs) (cond-> acc (seq cur) (conj cur))
                   (>= n per) (recur rs (conj acc cur) [] 0)
                   :else (recur (rest rs) acc (conj cur (first rs)) (+ n (second (first rs))))))]
    (run! deref (doall (for [g groups]
                         (future (doseq [[a c] g]
                                   (loop [r a]
                                     (when (< r (+ a c))
                                       (aset out r (dot m (* r dim) q dim))
                                       (recur (inc r)))))))))
    out))

(defn top
  "The best rows of `files` (each with :id :first_row :count), at most `per-clip` from one file (0: no limit),
  never from `exclude` (a file id), best first, ties by row: [[row score file] …]."
  [^doubles scored files {:keys [limit per-clip exclude]}]
  (let [rows (for [f files :when (not= exclude (:id f))
                   r (range (:first_row f) (+ (:first_row f) (:count f)))]
               [r (aget scored r) f])
        ranked (sort-by (fn [[r s]] [(- s) r]) rows)]
    (loop [[x & more :as xs] ranked per {} out []]
      (if (or (empty? xs) (>= (count out) limit))
        out
        (let [id (:id (nth x 2))]
          (if (and (pos? per-clip) (>= (get per id 0) per-clip))
            (recur more per out)
            (recur more (update per id (fnil inc 0)) (conj out x))))))))

(defn frame-id
  "A frame's id: its file's clip and its place."
  [clip n]
  (str clip ".f" n))

(def ^:private frame-id-re #"([a-z2-7]{8})\.f([1-9][0-9]*)")

(defn frame-id?
  "Whether `v` looks like a frame id."
  [v]
  (some? (re-matches frame-id-re (str/lower-case (str/trim (str v))))))

(defn parse-id
  "A frame id as {:clip :n}, or error.code bad-id."
  [v]
  (if-let [[_ c n] (re-matches frame-id-re (str/lower-case (str/trim (str v))))]
    {:clip c :n (parse-long n)}
    (out/fail! "bad-id" (str "'" v "' isn't a frame id (like k7f3q2ab.f12)")
               {:help ["Run `blackbox look <words>` for frame ids"]})))

(defn lookup
  "The frame `id` names, with its file's fields: {:file {…} :n :t :pts :frame :row}, or error.code not-found."
  [db id]
  (let [{:keys [clip n]} (parse-id id)
        r (db/one db "SELECT f.*, i.n AS item_n, i.t, i.pts, i.frame FROM frame_items i
                      JOIN frame_files f ON f.id = i.file_id WHERE f.clip = :clip AND i.n = :n;"
                  {:clip clip :n n})]
    (when-not r
      (out/fail! "not-found" (str "no frame " (frame-id clip n) " in the picture index")
                 {:help ["Run `blackbox look <words>` for frame ids"]}))
    {:file r :n (:item_n r) :t (:t r) :pts (:pts r) :frame (:frame r) :row (+ (:first_row r) (dec (:item_n r)))}))
