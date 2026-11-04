(ns black-box-cli.archive
  "The commands over what was said: `index`, `search`, `line`, `transcript` and `footage`, and what the home view
  shows of the index. Each builds plain data for `out`; the queries run in this Mac's index (black-box-cli.index)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.people :as people]
   [black-box-cli.prints :as prints]
   [black-box-cli.shots :as shots]
   [black-box-cli.sync :as sync]
   [black-box-cli.tc :as tc]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(def video-exts
  "What counts as video for --video: the original's extension."
  ["mp4" "mov" "mxf" "m4v" "avi" "mkv" "mts" "braw" "r3d" "crm"])

(defn- nfc [s] (Normalizer/normalize (str s) Normalizer$Form/NFC))

(defn day-of
  "YYYY-MM-DD from the date folders of `rel`, or nil."
  [rel]
  (when-let [[_ y m d] (re-find #"/(\d{4})/(\d{2})/(\d{2})/" (str "/" rel))]
    (str y "-" m "-" d)))

(defn version-of
  "The transcript version a command reads: --transcripts, else the default."
  [opts]
  (or (:transcripts opts) index/default-version))

(defn- scope
  "The SQL condition on transcripts `t` for the scope flags in `opts`, and its parameters."
  [opts]
  (let [{:keys [day month path video]} opts
        dates (concat (map (fn [d] (let [[y m dd] (str/split d #"-")] (str "%/" y "/" m "/" dd "/%"))) day)
                      (map (fn [mo] (let [[y m] (str/split mo #"-")] (str "%/" y "/" m "/%"))) month))
        params (into {:version (version-of opts)}
                     (map-indexed (fn [i v] [(keyword (str "d" i)) v]) dates))
        ors (map-indexed (fn [i _] (str "t.rel LIKE :d" i)) dates)
        where (cond-> ["t.version = :version"]
                (seq ors) (conj (str "(" (str/join " OR " ors) ")"))
                path (conj "t.rel LIKE :path")
                video (conj (str "(" (str/join " OR " (map-indexed (fn [i _] (str "lower(t.source_name) LIKE :v" i))
                                                                   video-exts))
                                 ")")))]
    {:where (str/join " AND " where)
     :params (cond-> params
               path (assoc :path (str "%" (nfc path) "%"))
               video (into (map-indexed (fn [i e] [(keyword (str "v" i)) (str "%." e)]) video-exts)))}))

(defn scope-words
  "The scope flags in words, for a command's head."
  [{:keys [day month path video]}]
  (let [parts (cond-> (vec (concat day month))
                path (conj (str "path~" path))
                video (conj "video only"))]
    (if (seq parts) (str/join ", " parts) "everything")))

(defn proxy-of
  "The clip's proxy in media_meta, or nil: <rel>.proxy.mp4, or for audio an exact copy at <rel>. Never the NAS."
  [meta rel]
  (when meta
    (some (fn [p] (when (fs/exists? p) (str p)))
          [(fs/path meta "proxies" (str rel ".proxy.mp4")) (fs/path meta "proxies" rel)])))

(def ^:private line-cols
  "c.id, c.transcript_id, c.n, c.start, c.end, c.text, c.speaker, t.rel, t.version, t.json_sha256, t.source_name,
   t.source_sha256, t.duration, t.clock_kind, t.start_tc, t.rate, t.tod_s")

(defn- line-row
  "Every field a line can show, in order; commands pick from these. `voices` is people/voice-names."
  [{:keys [meta voices]} r full]
  (array-map :id (:id r)
             :file (:source_name r)
             :day (day-of (:rel r))
             :at (tc/hms (:start r))
             :end (tc/hms (:end r))
             :speaker (:speaker r)
             :text (out/cut (:text r) full)
             :rel (:rel r)
             :tc (tc/source-tc r (:start r))
             :seconds (tc/round-to (:start r) 3)
             :proxy (proxy-of meta (:rel r))
             :person (get voices [(:rel r) (:version r) (:json_sha256 r) (:speaker r)])))

(def ^:private fields-hint
  "Add `--fields id,file,day,at,speaker,text,tc,proxy` for timecode and proxy")

;; ------------------------------------------------------------------------------------------------ index

(defn index-cmd
  "`blackbox index [--rebuild]`."
  [{:keys [meta home opts] :as ctx}]
  (let [path (:index (index/paths ctx))
        res (index/build! {:meta meta :index-path path :version (version-of opts) :rebuild (:rebuild opts)})
        pics (frames/build! {:meta meta :index-path path :rebuild (:rebuild opts)})
        names (people/build! {:meta meta :index-path path :rebuild (:rebuild opts)})
        voices (prints/build! {:meta meta :index-path path :rebuild (:rebuild opts)})
        synced (sync/build! {:meta meta :index-path path :rebuild (:rebuild opts)})
        logged (shots/build! {:meta meta :index-path path :rebuild (:rebuild opts)})]
    [(array-map :index (out/home-path path home)
                :transcripts (version-of opts)
                :added (:added res)
                :changed (:changed res)
                :unchanged (:unchanged res)
                :removed (:removed res)
                :skipped (:skipped res)
                :lines_added (:lines_added res)
                :frames (if pics
                          (array-map :added (:added pics)
                                     :changed (:changed pics)
                                     :unchanged (:unchanged pics)
                                     :removed (:removed pics)
                                     :skipped (:skipped pics)
                                     :total (:frames pics))
                          "none: media_meta has no embeddings folder")
                :people (or names "none: media_meta has no people folder")
                :prints (or voices "none: media_meta has no prints folder")
                :sync (or synced "none: media_meta has no sync folder")
                :shots (or logged "none: media_meta has no shots folder"))
     (cond-> []
       (or (pos? (:skipped res)) (pos? (:skipped pics 0)) (pos? (:skipped voices 0)) (pos? (:skipped synced 0))
           (pos? (:skipped logged 0)))
       (conj "Skipped files are online-only or not transcripts; make media_meta available offline in Finder")
       true (conj "Run `blackbox` for what the index holds"))]))

;; ------------------------------------------------------------------------------------------------ home

(defn home-index
  "What the home view says about the index: its counts and its months, or that it isn't built."
  [{:keys [home] :as ctx}]
  (let [path (:index (index/paths ctx))]
    (if-not (fs/exists? path)
      {:index (array-map :path (out/home-path path home) :state "not built")}
      (let [rows (db/query path "SELECT t.rel, t.duration, (SELECT count(*) FROM cues WHERE transcript_id = t.id) AS n
                                 FROM transcripts t WHERE t.version = :version;"
                           {:version index/default-version})
            months (reduce (fn [acc {:keys [rel duration n]}]
                             (let [d (day-of rel)]
                               (update acc (if d (subs d 0 7) "undated")
                                       (fn [[f h l]] [(inc (or f 0)) (+ (or h 0.0) (or duration 0.0)) (+ (or l 0) n)]))))
                           {} rows)]
        {:index (array-map :path (out/home-path path home)
                           :transcripts index/default-version
                           :files (count rows)
                           :lines (reduce + (map :n rows))
                           :hours (tc/round-to (/ (reduce + (map #(or (:duration %) 0.0) rows)) 3600.0) 1))
         :months (vec (for [[m [f h l]] (sort-by key #(compare %2 %1) months)]
                        (array-map :month m :files f :hours (tc/round-to (/ h 3600.0) 1) :lines l)))}))))

;; ------------------------------------------------------------------------------------------------ search

(defn- person-names
  "The name lines of person `id` (of `kind`, or both when nil) in the index's names."
  [path id kind]
  (filter #(= id (:person %)) (people/names (people/require! path) kind)))

(defn- voices-of
  "Where person `id` is heard, as `rel|version|transcript-sha256|label` keys for heard-sql (| is char 31)."
  [path id]
  (mapv #(str/join (char 31) ((juxt :rel :transcript :transcript_sha256 :label) %)) (person-names path id "voice")))

(def ^:private heard-sql
  "A line heard from one person: its voice's key among theirs (an IN list, so SQLite indexes it once)."
  " AND (t.rel || char(31) || t.version || char(31) || t.json_sha256 || char(31) || c.speaker)
        IN (SELECT value FROM json_each(:who))")

(defn fts-query
  "`terms` as an FTS5 query, each quoted: any of them, or all when `all`."
  [terms all]
  (str/join (if all " AND " " OR ") (map #(str "\"" (str/replace % "\"" "\"\"") "\"") terms)))

;; One moment heard on several devices (the sync map, docs/CONTRACT.md): a camera's line and a recorder's are one
;; moment when the sync map lines their starts up within `moment-s` and they share half or more of their words.

(def ^:private moment-s
  "Seconds apart two devices' lines may start, once the sync map lines them up, and still be one moment."
  1.0)

(defn- word-set [text] (set (re-seq #"[\p{L}\p{N}]+" (str/lower-case (str text)))))

(defn- alike?
  "Whether two transcripts of a moment say mostly the same: half or more of all their words shared."
  [a b]
  (let [x (word-set a) y (word-set b) all (count (into x y))]
    (and (pos? all) (>= (* 2 (count (filter x y))) all))))

(defn- one-moment?
  "Whether lines `a` and `b` (rows with :rel :source_sha256 :start :text) are one moment by `pairs`."
  [pairs a b]
  (and (alike? (:text a) (:text b))
       (boolean
        (some (fn [[cam rec]]
                (some (fn [p] (and (= (:rel cam) (:camera_rel p)) (= (:source_sha256 cam) (:camera_sha256 p))
                                   (= (:rel rec) (:recorder_rel p)) (= (:source_sha256 rec) (:recorder_sha256 p))
                                   (<= (- (:o0 p) moment-s) (:start cam) (+ (:o1 p) moment-s))
                                   (<= (abs (- (sync/recorder-time p (:start cam)) (:start rec))) moment-s)))
                      pairs))
              [[a b] [b a]]))))

(defn- moments
  "`rows` in rank order, grouped by moment: [[first-ranked & the same moment on other devices] …]."
  [pairs rows]
  (reduce (fn [groups r]
            (if-let [i (first (keep-indexed (fn [i g] (when (some #(one-moment? pairs % r) g) i)) groups))]
              (update groups i conj r)
              (conj groups [r])))
          []
          rows))

(defn search-cmd
  "`blackbox search <words…>`."
  [{:keys [opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        terms (vec (remove str/blank? (:terms args)))
        _ (when (empty? terms) (out/fail! "usage" "no search words" {:help ["Run `blackbox search <words>`"]}))
        {:keys [where params]} (scope opts)
        person (when (:person opts) (people/resolve! (people/require! path) (:person opts)))
        params (cond-> (assoc params :q (fts-query terms (:all opts)) :limit (:limit opts))
                 person (assoc :who (json/generate-string (voices-of path (:id person)))))
        base (str "FROM cues_fts f JOIN cues c ON c.rowid = f.rowid JOIN transcripts t ON t.id = c.transcript_id
                   WHERE cues_fts MATCH :q AND " where
                  (when person heard-sql))
        ctx (assoc ctx :voices (people/voice-names path))
        [[{:keys [total files]}] rows]
        (db/run! path (str "SELECT count(*) AS total, count(DISTINCT t.id) AS files " base ";
                            SELECT " line-cols " " base " ORDER BY bm25(cues_fts), c.id LIMIT :limit;")
                 params)
        rows (or rows [])
        head (cond-> (array-map :query (str/join " " terms)
                                :match (if (:all opts) "all words" "any word")
                                :scope (scope-words opts)
                                :transcripts (version-of opts))
               person (assoc :person (:name person)))]
    (if (empty? rows)
      [(merge head (array-map :count 0 :total 0 :result "0 lines match"))
       (if person
         ["Try fewer or other words, Italian ones too, or leave out --person"
          (str "Run `blackbox footage --person " (pr-str (:name person)) "` for where they're heard")]
         ["Try fewer or other words, Italian ones too (any word matches unless --all)"
          "Run `blackbox footage` to see which months are indexed"])]
      (let [pairs (when-not (:every-device opts) (sync/pairs-among path (map :rel rows)))
            groups (if (seq pairs) (moments pairs rows) (mapv vector rows))
            grouped (boolean (some next groups))
            person-of #(get (:voices ctx) [(:rel %) (:version %) (:json_sha256 %) (:speaker %)])
            hits (mapv (fn [[r & others]]
                         (let [row (line-row ctx r (:full opts))
                               names (distinct (keep person-of (cons r others)))]
                           (if grouped
                             (out/pick (apply array-map
                                              (concat (mapcat identity row)
                                                      [:also (not-empty (str/join ", " (map :id others)))
                                                       :check (when (next names)
                                                                (str "devices name different speakers: "
                                                                     (str/join ", " names)))]))
                                       [:id :file :at :text :also :check] opts)
                             (out/pick row [:id :file :at :text] opts))))
                       groups)
            limit (:limit opts)]
        [(apply array-map (concat (mapcat identity head)
                                  [:count (count hits) :total total :files files :hits hits]))
         (cond-> [(str "Run `blackbox line " (get (first hits) :id "<id>") "` to see a line in context")]
           (> total (count rows))
           (conj (str "Run `blackbox search " (str/join " " terms) " --limit " (min total (* limit 3)) "` for more"))
           true (conj fields-hint))]))))

;; ------------------------------------------------------------------------------------------------ line

(defn line-cmd
  "`blackbox line <id>`."
  [{:keys [meta opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        id (:id args)
        {:keys [clip cue]} (ids/parse id)
        r (db/one path (str "SELECT " line-cols " FROM cues c JOIN transcripts t ON t.id = c.transcript_id
                             WHERE t.version = :version AND t.clip = :clip AND c.n = :n;")
                  {:version index/default-version :clip clip :n cue})
        _ (when-not r
            (out/fail! "not-found" (str "no line " clip "." cue " in the index")
                       {:help ["Run `blackbox search <words>` for line ids"]}))
        context (:context opts)
        around (db/query path (str "SELECT " line-cols " FROM cues c JOIN transcripts t ON t.id = c.transcript_id
                                    WHERE c.transcript_id = :t AND c.n BETWEEN :a AND :b ORDER BY c.n;")
                         {:t (:transcript_id r) :a (- (:n r) context) :b (+ (:n r) context)})
        row (line-row (assoc ctx :voices (people/voice-names path)) r true)
        row (apply array-map (concat (mapcat identity row) [:tc_out (tc/source-tc r (:end r))]))
        shot (shots/at path (:rel r) (:start r))]
    [(apply array-map
            (concat
             [:line (out/pick row [:id :file :day :at :end :speaker :text :tc :tc_out] opts)
              :file (array-map :name (:source_name r)
                               :rel (:rel r)
                               :length (tc/hms (:duration r))
                               :proxy (proxy-of meta (:rel r)))]
             (when shot [:shot (shots/brief shot)])
             [:context (mapv (fn [x] (array-map :id (:id x)
                                                :at (tc/hms (:start x))
                                                :speaker (:speaker x)
                                                :text (out/cut (:text x) (:full opts))
                                                :this (= (:id x) (:id r))))
                             around)]))
     (cond-> [(str "Run `echo '" (:id (first around)) ".." (:id (last around))
                   "' | blackbox edl new - --title <title>` to make these lines an EDL")
              (str "Run `blackbox transcript " (:source_name r) " --day " (day-of (:rel r)) "` for the whole file")]
       shot (conj (str "Run `blackbox shots --file " (:source_name r) " --day " (day-of (:rel r))
                       "` for the file's shots")))]))

;; ------------------------------------------------------------------------------------------------ transcript

(defn find-file
  "One indexed transcript by file name or part of its path, narrowed by the scope flags."
  [path opts name]
  (let [q (str/trim (nfc name))
        {:keys [where params]} (scope opts)
        rows (db/query path (str "SELECT t.* FROM transcripts t WHERE " where
                                 " AND (t.source_name = :q OR t.rel LIKE :like) ORDER BY t.rel;")
                       (assoc params :q q :like (str "%" q "%")))
        exact (filter #(or (= q (:source_name %)) (str/ends-with? (:rel %) (str "/" q))) rows)
        rows (if (seq exact) exact rows)]
    (cond
      (empty? rows)
      (out/fail! "not-found" (str "no transcript for '" name "' (" (version-of opts) ")")
                 {:help ["Run `blackbox footage` to list the files" "Run `blackbox search <words>` to find a file"]})

      (next rows)
      (out/fail! "ambiguous" (str (count rows) " files match '" name "': " (str/join ", " (map :rel (take 8 rows)))
                                  (when (> (count rows) 8) " …"))
                 {:help ["Use more of the path, e.g. `09/16/src/fx3/C5274.MP4`, or add --day YYYY-MM-DD"]})

      :else (first rows))))

(defn transcript-cmd
  "`blackbox transcript <file>`."
  [{:keys [meta opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        t (find-file path opts (:file args))
        lines (db/query path "SELECT id, start, speaker, text FROM cues WHERE transcript_id = :t ORDER BY n;"
                        {:t (:id t)})
        shown (if (:full opts) lines (take (:limit opts) lines))]
    [(array-map :file (array-map :name (:source_name t)
                                 :rel (:rel t)
                                 :day (day-of (:rel t))
                                 :length (tc/hms (:duration t))
                                 :start_tc (:start_tc t)
                                 :language (:language t)
                                 :proxy (proxy-of meta (:rel t))
                                 :transcripts (:version t))
                :count (count shown)
                :total (count lines)
                :lines (if (seq lines)
                         (mapv (fn [x] (array-map :id (:id x) :at (tc/hms (:start x)) :speaker (:speaker x)
                                                  :text (out/cut (:text x) (:full opts))))
                               shown)
                         "0 lines (no speech in this file)"))
     (cond-> []
       (< (count shown) (count lines))
       (conj (str "Run `blackbox transcript " (:file args) " --full` for all " (count lines) " lines"))
       true (conj "Run `blackbox line <id>` for a line in context, with its timecode and proxy"))]))

;; ------------------------------------------------------------------------------------------------ footage

(defn- ranges-text [ranges]
  (when (seq ranges) (str/join ", " (map (fn [[a b]] (str (tc/hms a) "-" (tc/hms b))) ranges))))

(defn- ranges-length [ranges]
  (when (seq ranges) (tc/hms (reduce + (map (fn [[a b]] (- b a)) ranges)))))

(defn- with-person
  "The files of `rows` where person `id` is heard or seen, each with when: the names' ranges, joined on the file's
  rel and transcript."
  [path id rows]
  (let [names (group-by (juxt :rel :transcript :transcript_sha256) (person-names path id nil))]
    (keep (fn [r]
            (let [ns (get names [(:rel r) (:version r) (:json_sha256 r)])
                  heard (sort (mapcat :ranges (filter #(= "voice" (:kind %)) ns)))
                  seen (sort (mapcat :ranges (filter #(= "face" (:kind %)) ns)))]
              (when (or (seq heard) (seq seen))
                (assoc r :heard heard :seen seen))))
          rows)))

(defn footage-cmd
  "`blackbox footage [--person <name>]`."
  [{:keys [opts] :as ctx}]
  (let [path (index/require-index! ctx)
        {:keys [where params]} (scope opts)
        person (when (:person opts) (people/resolve! (people/require! path) (:person opts)))
        rows (db/query path (str "SELECT t.*, (SELECT count(*) FROM cues WHERE transcript_id = t.id) AS n,
                                  (SELECT count(DISTINCT speaker) FROM cues WHERE transcript_id = t.id) AS speakers
                                  FROM transcripts t WHERE " where " ORDER BY t.rel;")
                       params)
        rows (if person (vec (with-person path (:id person) rows)) rows)
        head (cond-> (array-map :scope (scope-words opts)) person (assoc :person (:name person)))]
    (if (empty? rows)
      [(merge head (array-map :count 0 :total 0 :result (if person
                                                          (str "0 files where " (:name person) " is heard or seen in scope")
                                                          "0 files with transcripts in scope")))
       ["Run `blackbox` to see which months are indexed"]]
      (let [shown (if (:full opts) rows (take (:limit opts) rows))
            row (fn [r] (apply array-map
                               (concat [:file (:source_name r)
                                        :day (day-of (:rel r))
                                        :length (tc/hms (:duration r))
                                        :lines (:n r)
                                        :speakers (:speakers r)
                                        :rel (:rel r)
                                        :start_tc (:start_tc r)
                                        :language (:language r)]
                                       (when person
                                         [:heard (ranges-length (:heard r))
                                          :seen (ranges-length (:seen r))
                                          :heard_at (ranges-text (:heard r))
                                          :seen_at (ranges-text (:seen r))]))))]
        [(merge head
                (array-map :count (count shown)
                           :total (count rows)
                           :hours (tc/round-to (/ (reduce + (map #(or (:duration %) 0.0) rows)) 3600.0) 2)
                           :files (mapv #(out/pick (row %) (if person [:file :day :length :heard :seen]
                                                               [:file :day :length :lines])
                                                   opts)
                                        shown)))
         (cond-> []
           (< (count shown) (count rows))
           (conj (str "Run the same with `--limit " (count rows) "` for all " (count rows) " files"))
           person (conj "Add `--fields file,day,heard,seen,heard_at,seen_at` for when"
                        (str "Run `blackbox search <words> --person " (pr-str (:name person)) "` for what they said"))
           (not person) (conj "Run `blackbox transcript <file>` to read one"
                              "Run `blackbox search <words> --day YYYY-MM-DD` to search one day"))]))))
