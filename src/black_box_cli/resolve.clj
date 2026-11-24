(ns black-box-cli.resolve
  "DaVinci Resolve on this Mac, for `online` (the owner's decision, 2026-11-23): a cut's clips with their pre-
  and post-roll, as a plan, and running resolve_build.py with Resolve's own Python to make a new timeline from the
  originals. Resolve opens the originals by path; the CLI never does (AGENTS.md rules 1 and 5)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.db :as db]
   [black-box-cli.sync :as sync]
   [black-box-cli.tc :as tc]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-handle
  "Seconds of pre- and post-roll a clip gets when neither it nor --pre/--post says (the owner's choice)."
  2.0)

(def resolve-python
  "Resolve's own Python: it comes with Resolve, so an editor's Mac needs nothing else."
  "/Applications/DaVinci Resolve/DaVinci Resolve.app/Contents/Applications/ResolvePython")

(def intos
  "Where the timeline goes: auto (the open project, or a new one when Resolve wasn't running or nothing real is
  open), current (the open project) or new-project."
  #{"auto" "current" "new-project"})

(defn- seconds [v default]
  (if (some? v) (cutlist/parse-time v) default))

(defn with-handles
  "The segments with their pre- and post-roll added (a clip's own :pre/:post first, then `pre`/`post`), clamped to
  the file: [segments warnings]."
  [segments {:keys [pre post]}]
  (let [done (map-indexed
              (fn [i s]
                (let [want-pre (seconds (:pre s) pre)
                      want-post (seconds (:post s) post)
                      start (max 0.0 (- (:start s) want-pre))
                      end (cond-> (+ (:end s) want-post) (:duration s) (min (:duration s)))
                      short (cond-> []
                              (< (- (:start s) start) (- want-pre 1e-6))
                              (conj (str "pre-roll " (tc/round-to (- (:start s) start) 2) " s (the file starts)"))
                              (< (- end (:end s)) (- want-post 1e-6))
                              (conj (str "post-roll " (tc/round-to (- end (:end s)) 2) " s (the file ends)")))]
                  [(assoc s :start (tc/round-to start 3) :end (tc/round-to end 3)
                          :pre_s (tc/round-to (- (:start s) start) 3) :post_s (tc/round-to (- end (:end s)) 3))
                   (when (seq short) (str "clip " (inc i) " (" (:source_name s) "): " (str/join ", " short)))]))
              segments)]
    [(mapv first done) (vec (keep second done))]))

(def min-confidence
  "The sync map's confidence a recorder needs to be laid under a clip (Black Box's advice: below it, list it)."
  0.5)

(defn- device
  "Which recorder a file is, for its track: its folder and the start of its name (LAV3_0007.wav in lavs/ is
  lavs/LAV3), so one lav keeps one track through an edit."
  [rel]
  (str (fs/file-name (fs/parent rel)) "/" (first (str/split (str (fs/file-name rel)) #"[_\-.]"))))

(declare original)

(defn synced-audio
  "The recorders heard with each segment (pre- and post-roll applied), by the sync map: [audio warnings]. Each is
  {:clip (1-based) :recorder :rel :source_path :start :end :record_s (where the clip starts on the timeline)
  :track (the recorder's place, 1 up: Resolve's script gives each a block of tracks from A2 as wide as its channels)}, recorder time = camera time + offset_s + drift (Black Box's formula, per
  segment). Only pairs whose overlap covers the whole segment; ones under min-confidence are named in a warning."
  [index-path segments originals]
  (let [starts (reductions + 0.0 (map #(- (:end %) (:start %)) segments))
        found (for [[i s record-s] (map vector (range) segments starts)
                    :when (and (:rel s) (:source_sha256 s))
                    p (sync/recorders-of index-path (:rel s) (:source_sha256 s))
                    :when (and (<= (:o0 p) (+ (:start s) 1e-3)) (<= (- (:end s) 1e-3) (:o1 p)))]
                [i s record-s p])
        weak (filter (fn [[_ _ _ p]] (< (:confidence p) min-confidence)) found)
        strong (remove (fn [[_ _ _ p]] (< (:confidence p) min-confidence)) found)
        tracks (zipmap (sort (distinct (map (fn [[_ _ _ p]] (device (:recorder_rel p))) strong))) (iterate inc 1))
        audio (vec (for [[i s record-s p] strong
                         :let [t (db/one index-path (str "SELECT rel, source_path FROM transcripts "
                                                         "WHERE rel = :rel AND source_sha256 = :sha LIMIT 1;")
                                         {:rel (:recorder_rel p) :sha (:recorder_sha256 p)})
                               in (sync/recorder-time p (:start s))]]
                     (array-map :clip (inc i)
                                :recorder (str (fs/file-name (:recorder_rel p)))
                                :rel (:recorder_rel p)
                                :source_path (original {:rel (:recorder_rel p) :source_path (:source_path t)} originals)
                                :start (tc/round-to in 3)
                                :end (tc/round-to (+ in (- (:end s) (:start s))) 3)
                                :record_s (tc/round-to record-s 3)
                                :track (tracks (device (:recorder_rel p)))
                                :confidence (:confidence p))))
        warnings (vec (for [[i s _ p] weak]
                        (str "clip " (inc i) " (" (:source_name s) "): " (fs/file-name (:recorder_rel p))
                             " left out, a weak sync match (confidence " (:confidence p) ")")))]
    [(vec (remove #(str/blank? (:source_path %)) audio)) warnings]))

(defn original
  "Where Resolve finds a segment's original: its source path, or under `root` when the NAS is mounted elsewhere on
  this Mac (the same rel below it)."
  [s root]
  (if (and root (:rel s))
    (str (fs/path root (:rel s)))
    (:source_path s)))

(defn fps-setting
  "A rate as Resolve's timelineFrameRate setting spells it."
  [rate]
  (let [v (tc/rate-value rate)
        near (fn [x] (< (abs (- v x)) 0.01))]
    (cond (near 23.976) "23.976" (near 29.97) "29.97" (near 47.952) "47.952" (near 59.94) "59.94"
          (near 119.88) "119.88" :else (str (long (Math/rint v))))))

(defn video?
  "Whether a segment's file has a picture."
  [s]
  (some #(str/ends-with? (str/lower-case (str (:rel s))) (str "." %)) archive/video-exts))

(defn- clip-frames
  "Each segment's [in out] frames (end exclusive, as AppendToTimeline counts), at its own rate."
  [segments rate]
  (mapv (fn [s] (let [fps (tc/rate-value (or (tc/parse-rate (:rate s)) rate))]
                  [(long (Math/rint (* (:start s) fps))) (long (Math/rint (* (:end s) fps)))]))
        segments))

(defn plan
  "What resolve_build.py does, as data: the timeline's place and name, and each clip's original, proxy and frames
  (end exclusive, as AppendToTimeline counts). Placed by frame, a clip starts at `record_frame`, where the frames
  before it end, and its recorders at their clip's (`clip_frame`); the subtitles are timed the same way."
  [segments {:keys [edl title id into db proxies originals rate audio subtitles]}]
  (let [frames (clip-frames segments rate)
        records (vec (reductions + 0 (map (fn [[a b]] (- b a)) frames)))]
    (array-map :into into
               :db db
               :project (str title " (" id ")")
               :timeline title
               :bin id
               :edl (str edl)
               :fps (fps-setting rate)
               :proxies (boolean proxies)
               :subtitles (some-> subtitles str)
               :clips (mapv (fn [s [in out] record]
                              (array-map :source_path (original s originals)
                                         :proxy (when (video? s) (:play_path s))
                                         :video (boolean (video? s))
                                         :in_frame in
                                         :out_frame out
                                         :record_frame record))
                            segments frames records)
               :audio (let [fps (tc/rate-value rate)
                            f #(long (Math/rint (* % fps)))]
                        (mapv (fn [a]
                                (array-map :source_path (:source_path a)
                                           :in_s (:start a) :out_s (:end a)
                                           :in_frame (f (:start a))
                                         ;; as long as its clip in frames, so it ends where its clip ends
                                           :out_frame (+ (f (:start a))
                                                         (if-let [[ca cb] (get frames (dec (:clip a)))]
                                                           (- cb ca)
                                                           (- (f (:end a)) (f (:start a)))))
                                           :record_frame (f (:record_s a))
                                           :clip_frame (get records (dec (:clip a)) (f (:record_s a)))
                                           :track (:track a)))
                              audio)))))

(defn- words-in
  "Every word the file of segment `s` has between its in and out, handles included, from the index: [[w start end]]."
  [idx version s]
  (when-let [t (db/one idx "SELECT id FROM transcripts WHERE version = :v AND rel = :rel;" {:v version :rel (:rel s)})]
    (->> (db/query idx (str "SELECT start, end, words FROM cues WHERE transcript_id = :t AND end > :a AND start < :b "
                            "ORDER BY n;")
                   {:t (:id t) :a (:start s) :b (:end s)})
         (mapcat (fn [q] (map (fn [[w ws we]] [w (or ws (:start q)) (or we ws (:end q))])
                              (json/parse-string (:words q)))))
         (filter (fn [[_ ws]] (and (>= ws (:start s)) (< ws (:end s)))))
         vec)))

(defn subtitles
  "What's said in `segments`, as [start end text] in seconds on the timeline Resolve builds: every word of each
  clip's range (handles included), each clip starting where the frames before it end (the plan's frames, at each
  clip's own rate, counted at the timeline's `rate`), so the subtitles sit on the words however the frames round."
  [idx version segments rate]
  (let [tfps (tc/rate-value rate)
        offsets (map #(/ % tfps) (reductions + 0 (map (fn [[a b]] (- b a)) (clip-frames segments rate))))]
    ;; a clip's lines end where its frames end, never in the next clip
    (vec (mapcat (fn [s offset next-offset]
                   (keep (fn [[a b text]] (let [b (min b next-offset)] (when (> b a) [a b text])))
                         (cuts/srt-entries (assoc s :words (words-in idx version s)) offset)))
                 segments offsets (rest offsets)))))

(defn- python
  "Resolve's Python, or `$BLACKBOX_RESOLVE_PYTHON` alone when it's set (tests set it, so they never reach a real
  Resolve); nil when it isn't there."
  [env]
  (let [py (get env "BLACKBOX_RESOLVE_PYTHON" resolve-python)]
    (when (and (fs/regular-file? py) (fs/executable? py)) py)))

(defn build!
  "Runs resolve_build.py on the plan at `plan-path`: [its result, nil] or [nil {:code :message}]."
  [env plan-path]
  (if-let [py (python env)]
    (let [script (fs/create-temp-file {:prefix "blackbox-resolve-" :suffix ".py"})]
      (try
        (spit (str script) (slurp (io/resource "black_box_cli/resolve_build.py")))
        (let [{:keys [out err exit]} (process/sh py (str script) (str plan-path))
              doc (try (json/parse-string (last (str/split-lines (str out))) true) (catch Exception _ nil))]
          (cond
            (:error doc) [nil (select-keys (:error doc) [:code :message])]
            (and (zero? exit) (map? doc)) [doc nil]
            :else [nil {:code "resolve-failed"
                        :message (or (first (remove str/blank? (str/split-lines (str err out)))) "no answer")}]))
        (finally (fs/delete-if-exists script))))
    [nil {:code "no-resolve" :message "DaVinci Resolve isn't installed on this Mac (its Python wasn't found)"}]))
