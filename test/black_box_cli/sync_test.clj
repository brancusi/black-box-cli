(ns black-box-cli.sync-test
  "The sync map from the fixture's invented `sync/` (a camera and a recorder of one day, offset −13.2 s): the index
  reads the newest stamp of each day, and search shows a moment heard on two devices once, with the other line in
  `also` and a `check` when the devices name different speakers."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.archive :as archive]
   [black-box-cli.frames :as frames]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private recorder-json "transcripts/deepgram-nova-3-multi/media/2026/09/16/audio/DJI_01.WAV.json")

(defn- copy-meta []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- runner [meta]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        run (fn [& args] (read-string (:out (s/cli* {} (concat ["--meta" meta "--local" local "--format" "edn"] args)))))]
    (run "index")
    run))

(defn- one-moment-on-two-devices
  "A copy of the fixture where the recorder's second line says what the camera's third does, at the time the sync
  map puts it (camera 22.2 s is recorder 9.0 s), and its voice is named as Tobia Sàsso (the camera's is Mara Vento)."
  []
  (let [meta (copy-meta)
        f (str (fs/path meta recorder-json))
        doc (-> (json/parse-string (slurp f) true)
                (assoc-in [:cues 1 :text] "We use this to make a marble sculpture.")
                (assoc-in [:cues 1 :speaker] "S1"))
        _ (spit f (json/generate-string doc))
        tsha (frames/sha256-hex (fs/read-all-bytes f))
        assignments (fs/path meta "people/assignments/20261020T100000Z.jsonl")
        line (json/generate-string (sorted-map :how "confirmed" :kind "voice" :label "S1" :person "p7a8b9c0d1e2f"
                                               :ranges [[9.0 10.75]] :rel "media/2026/09/16/audio/DJI_01.WAV"
                                               :schema "media-mgmt.people/1" :source_sha256 (apply str (repeat 64 "b"))
                                               :transcript "deepgram-nova-3-multi" :transcript_sha256 tsha))
        _ (spit (str assignments) (str (slurp (str assignments)) line "\n"))
        registry (str (fs/path meta "people/registry/20261020T100000Z.json"))]
    (spit registry (json/generate-string (assoc (json/parse-string (slurp registry) true)
                                                :assignments_sha256 (frames/sha256-hex (fs/read-all-bytes assignments)))))
    meta))

(deftest the-newest-day-file-is-read
  (let [run (runner s/fixture-meta)]
    (is (= {:days 1 :pairs 1 :skipped 0} (:sync (run "index"))) "the older stamp of the day and an unknown kind ignored"))
  (testing "no sync folder"
    (let [meta (copy-meta)]
      (fs/delete-tree (fs/path meta "sync"))
      (is (= "none: media_meta has no sync folder" (:sync ((runner meta) "index")))))))

(deftest a-moment-heard-on-two-devices-is-one-hit
  (let [run (runner (one-moment-on-two-devices))
        {:keys [count hits]} (run "search" "marble" "sculpture" "--all")]
    (is (= 1 count))
    (is (= #{"r54qb74c.3" "2npshqu5.2"} (set [(:id (first hits)) (:also (first hits))])))
    (is (= #{"Mara Vento" "Tobia Sàsso"}
           (set (str/split (str/replace (:check (first hits)) "devices name different speakers: " "") #", "))))
    (testing "--every-device shows each device's line"
      (let [{:keys [count hits]} (run "search" "marble" "sculpture" "--all" "--every-device")]
        (is (= 2 count))
        (is (every? #(not (contains? % :also)) hits))))
    (testing "lines that only happen to be near each other stay apart"
      (is (= 2 (:count (run "search" "safer" "sculpture")))))))

(defn- two-recorders-one-camera
  "`one-moment-on-two-devices` with a second recorder, DJI_02.WAV, heard with the same camera (offset −12.2 s), whose
  second line says the same as the first recorder's at the same moment (camera 22.2 s is its 10.0 s)."
  []
  (let [meta (one-moment-on-two-devices)
        doc (-> (json/parse-string (slurp (str (fs/path meta recorder-json))) true)
                (assoc-in [:source :path] "/nowhere/media/2026/09/16/audio/DJI_02.WAV")
                (assoc-in [:source :name] "DJI_02.WAV")
                (assoc-in [:source :sha256] (apply str (repeat 64 "c")))
                (assoc-in [:cues 1 :start] 10.0)
                (assoc-in [:cues 1 :end] 11.75))
        _ (spit (str (fs/path meta (str/replace recorder-json "DJI_01" "DJI_02"))) (json/generate-string doc))
        day (str (fs/path meta "sync/2026/09/16.20261030T120000Z.sync.jsonl"))
        [head pair & more] (str/split-lines (slurp day))
        pair2 (-> (json/parse-string pair true)
                  (assoc-in [:recorder :rel] "media/2026/09/16/audio/DJI_02.WAV")
                  (assoc-in [:recorder :sha256] (apply str (repeat 64 "c")))
                  (assoc :offset_s -12.2))]
    (spit (str (fs/path meta "sync/2026/09/16.20261031T120000Z.sync.jsonl"))
          (str/join "\n" (concat [(str/replace head "\"pairs\": 1" "\"pairs\": 2") pair (json/generate-string pair2)]
                                 more [""])))
    meta))

(deftest two-recorders-heard-with-one-camera-are-one-hit
  (let [run (runner (two-recorders-one-camera))]
    (testing "without the camera's line among the hits"
      (let [{:keys [count hits]} (run "search" "marble" "sculpture" "--all" "--path" "audio")]
        (is (= 1 count))
        (is (contains? (set [(:id (first hits)) (:also (first hits))]) "2npshqu5.2"))
        (is (re-matches #"[a-z2-7]{8}\.2" (str (:also (first hits)))) "one other line, the other recorder's")
        (is (not= (:id (first hits)) (:also (first hits))))))
    (testing "with it: all three are one moment"
      (let [{:keys [count hits]} (run "search" "marble" "sculpture" "--all")]
        (is (= 1 count))
        (is (= 2 (clojure.core/count (str/split (:also (first hits)) #", "))))))))

(deftest also-and-check-are-always-fields
  (let [run (runner s/fixture-meta)
        {:keys [hits]} (run "search" "marble" "--fields" "id,also,check")]
    (is (seq hits))
    (is (every? #(= [:id :also :check] (keys %)) hits))
    (is (every? #(nil? (:also %)) hits) "nothing grouped")))

(deftest a-line-joining-two-groups-merges-them
  (let [cam {:rel "c.MP4" :source_sha256 "c" :start 100.0 :text "you see, in marble, you see, horse." :id "cam"}
        a {:rel "a.WAV" :source_sha256 "a" :start 10.0 :text "so you see, the marble, you see, a marble horse."
           :id "a"}
        b {:rel "b.WAV" :source_sha256 "b" :start 20.0 :text "so you said, you see, in marble, you see, marble house."
           :id "b"}
        pair (fn [rec off] {:camera_rel "c.MP4" :camera_sha256 "c" :recorder_rel (:rel rec) :recorder_sha256 (:rel rec)
                            :offset_s off :drift_ppm nil :o0 0.0 :o1 500.0})
        pairs [(assoc (pair a -90.0) :recorder_sha256 "a") (assoc (pair b -80.0) :recorder_sha256 "b")]
        moments #'archive/moments]
    (is (= [["a" "b" "cam"]] (mapv #(mapv :id %) (moments pairs [a b cam])))
        "the two recorders' words differ too much on their own; the camera's line joins them")
    (is (= [["cam" "a" "b"]] (mapv #(mapv :id %) (moments pairs [cam a b]))))))
