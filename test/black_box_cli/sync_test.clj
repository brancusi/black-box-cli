(ns black-box-cli.sync-test
  "The sync map from the fixture's invented `sync/` (a camera and a recorder of one day, offset −13.2 s): the index
  reads the newest stamp of each day, and search shows a moment heard on two devices once, with the other line in
  `also` and a `check` when the devices name different speakers."
  (:require
   [babashka.fs :as fs]
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
