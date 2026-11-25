(ns black-box-cli.around
  "The clips one camera shot before and after a clip, in the order it shot them: what an editor looks for around a
  select. From the camera facts (probe.sqlite): the same camera is the same device in a folder of the same name
  (`fx3`, on any day), and the order is the camera's own clock (`recorded_at`; a clock set to the wrong zone is still
  in order with itself), then the file's start timecode on its day, then its name. Never the NAS: each clip plays
  from its proxy."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.archive :as archive]
   [black-box-cli.db :as db]
   [black-box-cli.probe :as probe]
   [black-box-cli.tc :as tc]
   [cheshire.core :as json])
  (:import
   (java.time LocalDate LocalDateTime OffsetDateTime ZoneOffset)))

(defn- day-of
  "The shoot day a rel's folders say (media/2026/09/16/…), as \"2026-09-16\", or nil."
  [rel]
  (when-let [[_ y m d] (re-find #"(?:^|/)(\d{4})/(\d{2})/(\d{2})/" (str rel))] (str y "-" m "-" d)))

(defn- tc-seconds
  "Seconds into its day of a timecode \"17:39:38:12\" (frames ignored), or nil."
  [s]
  (when-let [[_ h m sec] (re-find #"^(\d{2}):(\d{2}):(\d{2})" (str s))]
    (+ (* 3600 (parse-long h)) (* 60 (parse-long m)) (parse-long sec))))

(defn start-of
  "When a clip started, in seconds on its camera's clock: recorded_at (with its zone, or as written), else its day and
  start timecode, else its day; nil without any."
  [{:keys [rel recorded_at timecode]}]
  (or (when (seq recorded_at)
        (try (.toEpochSecond (OffsetDateTime/parse recorded_at))
             (catch Exception _
               (try (.toEpochSecond (LocalDateTime/parse recorded_at) ZoneOffset/UTC) (catch Exception _ nil)))))
      (when-let [d (day-of rel)]
        (+ (.toEpochSecond (.atStartOfDay (LocalDate/parse d)) ZoneOffset/UTC) (or (tc-seconds timecode) 0)))))

(defn- clock
  "The time of day it started, as an editor sees it in Resolve: its start timecode (\"17:39:38\"), else the camera's
  clock; nil without either."
  [{:keys [recorded_at timecode]}]
  (or (some->> timecode (re-find #"^(\d{2}:\d{2}:\d{2})") second)
      (some->> recorded_at (re-find #"T(\d{2}:\d{2}:\d{2})") second)))

(defn camera
  "The camera that shot a clip, as far as the order goes: its device and its folder's name."
  [{:keys [rel device]}]
  [device (some-> rel fs/parent fs/file-name str)])

(defn in-order
  "The video clips of `rows` (probe rows) that `rel`'s camera shot, in the order it shot them."
  [rows rel]
  (let [me (first (filter #(= rel (:rel %)) rows))]
    (when me
      (->> rows
           (filter #(and (= "video" (:kind %)) (= (camera me) (camera %))))
           (sort-by (juxt #(or (start-of %) Long/MAX_VALUE) :rel))
           vec))))

(defn- lines-by-rel
  "How many lines each of `rels` has in transcript `version`."
  [index-path version rels]
  (into {} (map (juxt :rel :n))
        (db/query index-path "SELECT t.rel, count(c.id) AS n FROM transcripts t JOIN cues c ON c.transcript_id = t.id
                              WHERE t.version = :v AND t.rel IN (SELECT value FROM json_each(:rels)) GROUP BY t.rel;"
                  {:v version :rels (json/generate-string rels)})))

(defn around
  "The `before` clips `rel`'s camera shot before it and the `after` after it (`n` of each), with it: {:clips [...]
  :more_before :more_after}, each
  clip {:rel :name :day :at :duration :gap :lines :proxy :current :device :res :fps :slow_motion}. `gap` is the
  seconds from the end of the clip before it in the order (nil for the first shown; 0 when the clocks overlap).
  Nil when the camera facts don't know `rel`."
  ([ctx rel n] (around ctx rel n n))
  ([{:keys [meta index-path version]} rel before after]
   (when (probe/available? index-path)
     (let [rows (db/query (probe/path index-path)
                          "SELECT rel, name, kind, device, recorded_at, timecode, duration, resolution, fps, capture_fps,
                                 slow_motion FROM media WHERE kind = 'video';")]
       (when-let [order (not-empty (in-order rows rel))]
         (let [i (first (keep-indexed #(when (= rel (:rel %2)) %1) order))
               from (max 0 (- i before))
               shown (subvec order from (min (count order) (+ i after 1)))
               lines (lines-by-rel index-path version (mapv :rel shown))]
           {:more_before (pos? from)
            :more_after (< (+ i after 1) (count order))
            :clips (vec (map-indexed
                         (fn [k r]
                           (let [prev (when (pos? (+ from k)) (nth order (dec (+ from k))))
                                 gap (when (and prev (start-of prev) (start-of r))
                                       (max 0 (tc/round-to (- (start-of r) (+ (start-of prev) (or (:duration prev) 0))) 1)))]
                             (merge (array-map :rel (:rel r)
                                               :name (or (:name r) (str (fs/file-name (:rel r))))
                                               :day (day-of (:rel r))
                                               :at (clock r)
                                               :duration (:duration r)
                                               :gap gap
                                               :lines (get lines (:rel r) 0)
                                               :proxy (some? (archive/proxy-of meta (:rel r)))
                                               :current (= rel (:rel r)))
                                    (select-keys (probe/fields r) [:device :res :fps :slow_motion]))))
                         shown))}))))))

(defn known?
  "Whether `rel` is an original the index knows (camera facts or a transcript): what the review server will play."
  [index-path rel]
  (boolean
   (or (and (probe/available? index-path)
            (db/one (probe/path index-path) "SELECT 1 AS y FROM media WHERE rel = :r;" {:r rel}))
       (db/one index-path "SELECT 1 AS y FROM transcripts WHERE rel = :r;" {:r rel}))))
