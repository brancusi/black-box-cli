(ns black-box-cli.resolve
  "DaVinci Resolve on this Mac, for `online` (the owner's decision, 2026-11-23): a cut's clips with their pre-
  and post-roll, as a plan, and running resolve_build.py with Resolve's own Python to make a new timeline from the
  originals. Resolve opens the originals by path; the CLI never does (AGENTS.md rules 1 and 5)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.cutlist :as cutlist]
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

(defn plan
  "What resolve_build.py does, as data: the timeline's place and name, and each clip's original, proxy and frames
  (end exclusive, as AppendToTimeline counts)."
  [segments {:keys [edl title id into db proxies originals rate]}]
  (array-map :into into
             :db db
             :project (str title " (" id ")")
             :timeline title
             :bin id
             :edl (str edl)
             :fps (fps-setting rate)
             :proxies (boolean proxies)
             :clips (mapv (fn [s]
                            (let [fps (tc/rate-value (or (tc/parse-rate (:rate s)) rate))]
                              (array-map :source_path (original s originals)
                                         :proxy (when (video? s) (:play_path s))
                                         :video (boolean (video? s))
                                         :in_frame (long (Math/rint (* (:start s) fps)))
                                         :out_frame (long (Math/rint (* (:end s) fps))))))
                          segments)))

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
