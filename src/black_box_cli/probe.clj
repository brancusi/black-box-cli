(ns black-box-cli.probe
  "The camera and technical facts of every original: the device that made it (camera or recorder), its resolution,
  the rate it was shot at and plays at, slow motion, codec and bitrate, from media_meta's
  `probe/v<N>/<rel>.<sha12>.json` (each original from the newest N that has it) (schema media-mgmt.probe/1; docs/CONTRACT.md). Black Box reads them from each
  file's own metadata on the mini, never from folder names, and writes each once; a changed original gets a new
  file beside the old one. Read into `probe.sqlite` beside the transcripts' index, one row per original (its newest
  file), whenever the folder's files change.

  The filters (--device, --fps, --slow-motion, --res, --codec, --bitrate) narrow a command to the originals whose
  facts match: `narrow` turns them into the set of rels the command keeps."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.out :as out]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(defn- versions
  "The `v<N>` folders under media_meta's probe folder `root`, oldest first (by N): [[n name] ...]."
  [root]
  (->> (when (fs/directory? root) (fs/list-dir root))
       (keep (fn [p] (when-let [[_ n] (re-matches #"v(\d+)" (str (fs/file-name p)))]
                       (when (fs/directory? p) [(parse-long n) (str (fs/file-name p))]))))
       sort vec))

(defn version-in
  "The newest probe version under `root` (by N), or v1 when there is none yet."
  [root]
  (or (second (peek (versions root))) "v1"))

(def marker
  "The file that marks media_meta's probe folder as published by Black Box."
  ".media-mgmt-probe-root")

(def ^:private schema-version 2)

(def ^:private schema-sql
  "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE media (
  rel          TEXT PRIMARY KEY, -- the original's path
  sha256       TEXT,
  name         TEXT,
  kind         TEXT,             -- video or audio
  device       TEXT,             -- the short name people say: FX3, Osmo Pocket 3, DJI Mic
  make         TEXT,
  model        TEXT,
  lens         TEXT,
  width        INTEGER,
  height       INTEGER,
  resolution   TEXT,             -- 8K, 6K, 5K, 4K, 2.7K, 1080p, 720p, SD
  timecode     TEXT,             -- where the file starts, as written (; for drop frame), at `rate`
  start_s      REAL,             -- a Broadcast WAV's start: seconds since midnight on the recorder's clock
  fps          REAL,             -- the rate it plays at
  rate         TEXT,             -- the same, exactly: 24000/1001
  capture_fps  REAL,             -- the rate it was shot at
  slow_motion  REAL,             -- capture over the rate it's meant to play at; null when it isn't
  vfr          INTEGER,
  codec        TEXT,
  profile      TEXT,
  bit_depth    INTEGER,
  chroma       TEXT,
  bitrate      INTEGER,          -- bits a second
  gamma        TEXT,
  gamut        TEXT,
  channels     INTEGER,
  sample_rate  INTEGER,
  duration     REAL,
  recorded_at  TEXT
);")

(def ^:private cols
  [:rel :sha256 :name :kind :device :make :model :lens :width :height :resolution :timecode :start_s :fps :rate :capture_fps :slow_motion
   :vfr :codec :profile :bit_depth :chroma :bitrate :gamma :gamut :channels :sample_rate :duration :recorded_at])

(def ^:private insert-sql
  (str "BEGIN;
INSERT INTO media (" (str/join ", " (map name cols)) ")
  SELECT " (str/join ", " (map #(str "d->>'" (name %) "'") cols)) "
  FROM (SELECT value AS d FROM json_each(:docs));
INSERT INTO meta VALUES ('schema_version', :schema), ('files', :files), ('skipped', :skipped);
COMMIT;"))

(defn path
  "The facts' database, beside the transcripts' index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "probe.sqlite")))

(def ^:private file-re #"(.+)\.([0-9a-f]{12})\.json")

(defn- walk
  "Every probe file under `tree`, sorted, with the rel and sha its name gives; hidden folders and files left out."
  [tree]
  (->> (fs/glob tree "**.json")
       (keep (fn [p] (let [rel-path (str (fs/relativize tree p))]
                       (when-let [[_ rel sha12] (re-matches file-re rel-path)]
                         {:path (str p) :rel rel :sha12 sha12}))))
       (remove (fn [{:keys [rel]}] (some #(str/starts-with? % ".") (str/split rel #"/"))))
       (sort-by (juxt :rel :sha12))))

(defn- num-or-nil [v] (when (number? v) v))
(defn- text-or-nil [v] (when (and (some? v) (not (coll? v))) (not-empty (str v))))

(defn row
  "The index's row for probe file `doc` found at `rel` with `sha12` in its name, or nil when it isn't the facts of
  that original."
  [doc rel sha12]
  (let [{:keys [schema source camera video audio duration_s recorded_at]} doc]
    (when (and (map? doc)
               (= "media-mgmt.probe/1" schema)
               (= rel (:rel source))
               (str/starts-with? (str (:sha256 source)) sha12))
      (let [v (when (map? video) video)
            a (when (map? audio) audio)
            c (when (map? camera) camera)]
        {:rel rel :sha256 (:sha256 source) :name (text-or-nil (:name source)) :kind (text-or-nil (:kind source))
         :device (text-or-nil (:name c)) :make (text-or-nil (:make c)) :model (text-or-nil (:model c))
         :lens (text-or-nil (:lens c))
         :width (num-or-nil (:width v)) :height (num-or-nil (:height v)) :resolution (text-or-nil (:resolution v))
         :timecode (text-or-nil (:timecode v)) :start_s (num-or-nil (:start_s a))
         :fps (num-or-nil (:fps v)) :rate (text-or-nil (:fps_exact v)) :capture_fps (num-or-nil (:capture_fps v))
         :slow_motion (num-or-nil (:slow_motion v)) :vfr (when (boolean? (:vfr v)) (if (:vfr v) 1 0))
         :codec (text-or-nil (:codec v)) :profile (text-or-nil (:profile v)) :bit_depth (num-or-nil (:bit_depth v))
         :chroma (text-or-nil (:chroma v)) :bitrate (num-or-nil (:bitrate_bps v))
         :gamma (text-or-nil (get-in v [:color :gamma])) :gamut (text-or-nil (get-in v [:color :gamut]))
         :channels (num-or-nil (:channels a)) :sample_rate (num-or-nil (:sample_rate a))
         :duration (num-or-nil duration_s) :recorded_at (text-or-nil recorded_at)
         :made_at (str (get-in doc [:producer :made_at]))}))))

(defn- meta-of [db]
  (into {} (map (juxt :key :value)) (db/query db "SELECT key, value FROM meta;")))

(defn- summary [db version]
  (let [{:keys [n video audio]} (db/one db "SELECT count(*) AS n, count(*) FILTER (WHERE kind = 'video') AS video,
                                            count(*) FILTER (WHERE kind = 'audio') AS audio FROM media;")]
    (array-map :version version
               :files n
               :video video
               :audio audio
               :skipped (parse-long (get (meta-of db) "skipped" "0")))))

(defn build!
  "Brings the facts beside `index-path` up to date with media_meta's probe folder: their summary, or nil when
  media_meta has no published probe folder (and then an old database here is removed). Read again whole whenever
  the folder's files change; a file that isn't one, or that Dropbox keeps online-only, is skipped and counted. Of
  several files for one original, the one in the newest version folder that has it, and of those the newest (it
  changed)."
  [{:keys [meta index-path rebuild]}]
  (let [root (fs/path meta "probe")
        vs (versions root)
        version (version-in root)
        db (path index-path)]
    (if-not (fs/exists? (fs/path root marker))
      (do (fs/delete-if-exists db) nil)
      (let [files (vec (for [[n v] vs f (walk (fs/path root v))] (assoc f :n n :v v)))
            listing (str/join "\n" (map #(str (:v %) "/" (:rel %) "." (:sha12 %) " " (fs/size (:path %))) files))
            m (when (and (not rebuild) (fs/exists? db)) (try (meta-of db) (catch clojure.lang.ExceptionInfo _ nil)))]
        (fs/create-dirs (fs/parent db))
        (if (and m (= (str schema-version) (get m "schema_version")) (= listing (get m "files")))
          (summary db version)
          (let [read-file (fn [{p :path :keys [rel sha12]}]
                            (when-not (tools/online-only? p)
                              (some-> (try (json/parse-string (slurp p) true) (catch Exception _ nil))
                                      (row rel sha12))))
                ;; each original from the newest version that has it (a version being published overlays the one
                                ;; before it), and of its files there the newest; an older version only when none of those reads
                picked (for [[_ fs*] (group-by :rel files)]
                         (loop [[[_ top] & older] (sort-by key > (group-by :n fs*)) skipped 0]
                           (let [rs (keep read-file top)
                                 skipped (+ skipped (- (count top) (count rs)))]
                             (cond (seq rs) {:row (dissoc (last (sort-by :made_at rs)) :made_at) :skipped skipped}
                                   (seq older) (recur older skipped)
                                   :else {:row nil :skipped skipped}))))
                rows (sort-by :rel (keep :row picked))
                skipped (reduce + (map :skipped picked))
                tmp (str db ".new-" (.pid (java.lang.ProcessHandle/current)))]
            (try
              (fs/delete-if-exists tmp)
              (db/run! tmp schema-sql {})
              (db/run! tmp insert-sql {:docs (json/generate-string rows)
                                       :schema (str schema-version)
                                       ;; a skipped file is read again next time, in case Dropbox has it by then
                                       :files (if (pos? skipped) "" listing)
                                       :skipped (str skipped)})
              (fs/move tmp db {:replace-existing true :atomic-move true})
              (summary db version)
              (finally (fs/delete-if-exists tmp)))))))))

;; ------------------------------------------------------------------------------------------------ reading

(defn available?
  "Whether the facts have been indexed."
  [index-path]
  (fs/exists? (path index-path)))

(defn find-original
  "The facts of the one original in `facts` (rows) named `q` (its file name, part of its path, or its clip id): [row],
  or the rows that match when it isn't one (none, or several)."
  [facts q]
  (let [by-clip (when (ids/clip-id? q) (filter #(= q (ids/clip (:rel %))) facts))
        rows (if (seq by-clip) by-clip (filter #(or (= q (:name %)) (str/includes? (:rel %) q)) facts))
        exact (filter #(or (= q (:name %)) (str/ends-with? (:rel %) (str "/" q))) rows)]
    (vec (sort-by :rel (if (seq exact) exact rows)))))

(defn facts
  "Every original's facts, by rel ({} without them)."
  [index-path]
  (if (available? index-path)
    (into {} (map (juxt :rel identity)) (db/query (path index-path) "SELECT * FROM media;"))
    {}))

;; ------------------------------------------------------------------------------------------------ the filters

(def ^:private filter-keys [:device :fps :slow-motion :res :codec :bitrate])

(defn asked?
  "Whether `opts` has any of the filters."
  [opts]
  (some #(let [v (get opts %)] (if (coll? v) (seq v) v)) filter-keys))

(def ^:private resolutions ["SD" "720p" "1080p" "2.7K" "4K" "5K" "6K" "8K"])

(def ^:private resolution-names
  (merge (into {} (map (juxt str/lower-case identity)) resolutions)
         {"uhd" "4K" "dci" "4K" "hd" "1080p" "fhd" "1080p" "1080" "1080p" "720" "720p" "2.7" "2.7K"
          "4" "4K" "5" "5K" "6" "6K" "8" "8K" "2160p" "4K" "4320p" "8K"}))

(defn- usage! [msg help]
  (out/fail! "usage" msg {:help help}))

(defn- number-range
  "`n`, `n+` or `n-` as {:n :cmp}, or a usage error naming `flag`."
  [flag v]
  (if-let [[_ n sign] (re-matches #"\s*(\d+(?:\.\d+)?)\s*([+-]?)\s*" (str v))]
    {:n (parse-double n) :cmp (case sign "+" :min "-" :max :about)}
    (usage! (str flag " " v ": use a number, n+ (at least) or n- (at most)")
            [(str "e.g. `" flag " 120`, `" flag " 50+`")])))

(defn- in-range? [{:keys [n cmp]} tolerance x]
  (and (number? x)
       (case cmp
         :min (>= x (* n (- 1 tolerance)))
         :max (<= x (* n (+ 1 tolerance)))
         :about (<= (Math/abs (double (- x n))) (* n tolerance)))))

(defn- resolution-range
  "--res's value as {:at label :min bool}, or a usage error."
  [v]
  (let [[_ s plus] (re-matches #"\s*(.+?)\s*(\+?)\s*" (str/lower-case (str v)))]
    (if-let [label (get resolution-names s)]
      {:at label :min (= "+" plus)}
      (usage! (str "--res " v ": use one of " (str/join ", " (map str/lower-case resolutions)) " (4k+ for 4K and up)")
              ["e.g. `--res 4k`, `--res 6k+`"]))))

(defn- fold
  "Text for loose matching: lower case, accents, spaces and punctuation dropped (ILME-FX3A, fx3a)."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (str/replace #"[^a-z0-9]" "")))

(def ^:private codec-names {"h265" "hevc" "x265" "hevc" "avc" "h264" "x264" "h264"})

(defn- rank [label] (.indexOf ^java.util.List resolutions label))

(defn- slow? [{:keys [slow_motion capture_fps]}]
  (boolean (or (and slow_motion (> slow_motion 1.0)) (and capture_fps (>= capture_fps 90.0)))))

(defn matcher
  "A predicate over a fact row for the filters in `opts` (usage errors for values it can't read)."
  [{:keys [device fps slow-motion res codec bitrate]}]
  (let [devices (seq (map fold device))
        fps-r (some->> fps (number-range "--fps"))
        res-rs (seq (map resolution-range res))
        codec-q (some-> codec fold (#(get codec-names % %)))
        bitrate-r (some->> bitrate (number-range "--bitrate"))]
    (fn [r]
      (and (or (nil? devices)
               (let [names (keep #(some-> % fold not-empty) [(:device r) (:model r) (:make r)])]
                 (some (fn [d] (some #(str/includes? % d) names)) devices)))
           (or (nil? fps-r) (in-range? fps-r 0.01 (or (:capture_fps r) (:fps r))))
           (or (not slow-motion) (slow? r))
           (or (nil? res-rs)
               (when-let [k (some-> (:resolution r) rank (#(when (>= % 0) %)))]
                 (some (fn [{:keys [at min]}] (if min (>= k (rank at)) (= k (rank at)))) res-rs)))
           (or (nil? codec-q) (some #(str/includes? (fold %) codec-q) (remove nil? [(:codec r) (:profile r)])))
           (or (nil? bitrate-r) (in-range? bitrate-r 0.1 (some-> (:bitrate r) (/ 1e6))))))))

(defn require!
  "The facts' database, or error.code no-probe when none have been indexed."
  [index-path home]
  (let [db (path index-path)]
    (when-not (fs/exists? db)
      (out/fail! "no-probe" (str "no camera facts at " (out/home-path db home)
                                 " (--device, --fps, --slow-motion, --res, --codec and --bitrate need them)")
                 {:help ["Run `blackbox index`: it reads media_meta's probe folder once Black Box has published it"
                         "Meanwhile, `--path fx3` narrows by the folder name"]}))
    db))

(defn narrow
  "`ctx` with `:rels` in its opts, the set of originals the filters keep, when it has any (`ctx` as it is
  otherwise)."
  [{:keys [opts home] :as ctx} index-path]
  (if-not (asked? opts)
    ctx
    (let [keep? (matcher opts)
          db (require! index-path home)]
      (assoc-in ctx [:opts :rels]
                (into #{} (comp (filter keep?) (map :rel)) (db/query db "SELECT * FROM media;"))))))

(defn words
  "The filters in words, for a command's scope."
  [{:keys [device fps slow-motion res codec bitrate]}]
  (cond-> []
    (seq device) (conj (str "device " (str/join "/" device)))
    fps (conj (str fps " fps"))
    slow-motion (conj "slow motion")
    (seq res) (conj (str/join "/" res))
    codec (conj (str "codec " codec))
    bitrate (conj (str bitrate " Mb/s"))))

;; ------------------------------------------------------------------------------------------------ showing

(defn- num-val [x]
  (when (number? x)
    (let [r (/ (Math/rint (* 1000.0 x)) 1000.0)]
      (if (== r (Math/floor r)) (long r) r))))

(defn fields
  "An original's facts as rows show them, in order: device, res, fps (shot at), slow_motion (\"5x\"), codec,
  bitrate (Mb/s), make, model, lens, gamma. All nil without facts."
  [r]
  (array-map :device (:device r)
             :res (:resolution r)
             :fps (num-val (or (:capture_fps r) (:fps r)))
             :slow_motion (when (slow? r) (if-let [x (:slow_motion r)] (str (num-val x) "x") "conform"))
             :codec (:codec r)
             :bitrate (some-> (:bitrate r) (/ 1e6) Math/rint long)
             :make (:make r)
             :model (:model r)
             :lens (:lens r)
             :gamma (:gamma r)))
