(ns black-box-cli.probe-test
  "The camera facts (media_meta/probe, invented in the fixture): read into the index, the newest file of an original
  kept, and the filters (--device, --fps, --slow-motion, --res, --codec, --bitrate) narrowing footage, search,
  shots and cuts. The fixture: C5274 (FX3, 4K 23.976, an older 1080p file beside it), C5280 (FX3 S&Q 119.88 into
  23.976, nothing said), C5900 in fx3 (FX3, 4K HEVC 59.94), C5900 in a7s (A7S III, 1080p 119.88 without S&Q) and
  DJI_01 (a DJI Mic, known by its file name)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.probe :as probe]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- fake-python
  "A stand-in for Resolve's Python: keeps the plan it's given (its second argument) and says it placed one clip."
  []
  (let [dir (fs/create-temp-dir {:prefix "bbcli-py-"})
        f (str (fs/path dir "python"))]
    (spit (str (fs/path dir "answer"))
          (str (json/generate-string {:project "Slow (x)" :timeline "Slow" :method "frames" :clips 2 :imported 2
                                      :reused 0 :missing [] :proxies_linked 0 :resolve "21.1.0.17"}) "\n"))
    (spit f "#!/bin/sh\ncp \"$2\" \"$(dirname \"$0\")/plan.json\"\ncat \"$(dirname \"$0\")/answer\"\n")
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:python f :dir (str dir)}))

(defn- runner
  "A command line over `meta` (the fixture by default) and one new --local folder, indexed, that answers in EDN;
  Resolve's Python is `python`."
  ([] (runner s/fixture-meta))
  ([meta] (runner meta "/nowhere/python"))
  ([meta python]
   (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
         home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
         run (fn [stdin & args]
               (let [res (s/cli* {:home home :stdin stdin :env {"BLACKBOX_RESOLVE_PYTHON" python "PATH" ""}}
                                 (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                 (assoc (edn/read-string (:out res)) :exit (:exit res))))]
     {:run run :local local :index (run "" "index")})))

(defn- files [out] (mapv (juxt :file :day) (:files out)))

(deftest the-facts-are-indexed
  (let [{:keys [index]} (runner)]
    (is (= {:version "v1" :files 5 :video 4 :audio 1 :skipped 0} (:probe index))
        "C5274 has two files; the newest is kept")))

(deftest the-filters-narrow-footage
  (let [{:keys [run]} (runner)
        footage (fn [& args] (apply run "" "footage" args))]
    (testing "every file, the ones nothing is said in too, with what made it"
      (let [out (footage)]
        (is (= 5 (:total out)))
        (is (= {:file "C5280.MP4" :day "2026-09-16" :length "0:12.0" :lines 0 :device "FX3" :res "4K" :fps 119.88}
               (first (filter #(= "C5280.MP4" (:file %)) (:files out)))))
        (is (= {:device "FX3" :res "4K" :fps 23.976}
               (select-keys (first (filter #(= "C5274.MP4" (:file %)) (:files out))) [:device :res :fps]))
            "the newest file's facts, not the older one's 1080p")))
    (testing "slow motion: S&Q (5x) and a high-frame-rate file to conform"
      (let [out (footage "--slow-motion")]
        (is (= "slow motion" (:scope out)))
        (is (= [["C5280.MP4" "5x"] ["C5900.MP4" "conform"]] (mapv (juxt :file :slow_motion) (:files out))))))
    (testing "the shooting rate: 120 finds 119.88; n+ at least"
      (is (= [["C5280.MP4" "2026-09-16"] ["C5900.MP4" "2026-10-03"]] (files (footage "--fps" "120"))))
      (is (= [["C5900.MP4" "2026-10-02"]] (files (footage "--fps" "60"))))
      (is (= 3 (:total (footage "--fps" "50+")))))
    (testing "the device by name, model or make, whatever its folder"
      (is (= 3 (:total (footage "--device" "fx3"))))
      (is (= [["C5900.MP4" "2026-10-03"]] (files (footage "--device" "a7s iii"))))
      (is (= 4 (:total (footage "--device" "sony"))))
      (is (= [["DJI_01.WAV" "2026-09-16"]] (files (footage "--device" "DJI Mic"))) "a recorder too, by its file name")
      (is (= 4 (:total (footage "--device" "fx3" "--device" "a7s"))) "repeated: either"))
    (testing "resolution, codec and bitrate"
      (is (= 3 (:total (footage "--res" "4k"))))
      (is (= 4 (:total (footage "--res" "1080p+"))))
      (is (= 3 (:total (footage "--res" "uhd" "--device" "fx3"))))
      (is (= [["C5900.MP4" "2026-10-02"]] (files (footage "--codec" "h265"))) "h265 is hevc")
      (is (= 3 (:total (footage "--bitrate" "150+"))))
      (is (= [["C5900.MP4" "2026-10-03"]] (files (footage "--bitrate" "100")))))
    (testing "the filters with the other scope flags"
      (is (= [["C5280.MP4" "2026-09-16"]] (files (footage "--slow-motion" "--day" "2026-09-16"))))
      (is (= 0 (:total (footage "--device" "DJI Mic" "--video")))))
    (testing "nothing matches: an explicit empty result"
      (let [out (footage "--res" "8k")]
        (is (= [0 0 "0 files in scope"] ((juxt :count :total :result) out)))
        (is (some #(str/includes? % "Loosen") (:help out)))))))

(deftest bad-values-are-usage-errors
  (let [{:keys [run]} (runner)]
    (doseq [args [["--res" "9k"] ["--fps" "fast"] ["--bitrate" "lots"]]]
      (let [out (apply run "" "footage" args)]
        (is (= 2 (:exit out)) (str args))
        (is (= "usage" (-> out :error :code)))))))

(deftest search-and-shots-narrow-too
  (let [{:keys [run]} (runner)]
    (testing "search keeps the lines of the files the filters keep"
      (let [all (run "" "search" "bag")]
        (is (pos? (:total all)))
        (is (= (:total all) (:total (run "" "search" "bag" "--device" "fx3" "--res" "4k"))))
        (is (= 0 (:total (run "" "search" "bag" "--device" "a7s"))))))
    (testing "shots: only the slow-motion clips' (S&Q and high frame rate), with or without words"
      (let [out (run "" "shots" "--slow-motion" "--per-clip" "0")]
        (is (= #{["C5280.MP4" "2026-09-16"] ["C5900.MP4" "2026-10-03"]} (set (map (juxt :file :day) (:shots out)))))
        (is (= "slow motion" (:scope out)))))))

(deftest a-clip-nothing-is-said-in-can-be-cut
  (let [{:keys [python dir]} (fake-python)
        {:keys [run local]} (runner s/fixture-meta python)
        out (run "title: Slow\nC5280.MP4 0:01-0:03\nC5274.MP4 0:01-0:03\n" "edl" "new" "-" "--no-render")
        clip (first (:clips (json/parse-string (slurp (str (fs/path local "cuts" (:id out) "cut.json"))) true)))]
    (testing "a cut by its file name, warned that it has no timecode"
      (is (= 0 (:exit out)))
      (is (= 2 (:clips out)))
      (is (some #(str/includes? % "no timecode") (:warnings out)))
      (is (= "media/2026/09/16/src/fx3/C5280.MP4" (:rel clip)))
      (is (str/ends-with? (str (:source_path clip)) "/media/2026/09/16/src/fx3/C5280.MP4")
          "the original's path, under the root the transcripts give"))
    (testing "online, from the saved edit: placed by frame, never by the EDL's timecode"
      (let [res (run "" "online" (:id out) "--into" "new-project")
            plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
        (is (= 0 (:exit res)))
        (is (true? (:by_frames plan)))
        (is (= "frames (a clip has no timecode)" (-> res :resolve :by)))
        (is (= [0 120] ((juxt :in_frame :out_frame) (first (:clips plan))))
            "1 s to 3 s with the handles (0 s to 5 s), at the file's own 23.976")))
    (testing "shots of it can be cut too"
      (let [shot (-> (run "" "shots" "--slow-motion" "--per-clip" "0") :shots first :id)
            res (run (str "title: Slow shot\n" shot "\n") "edl" "new" "-" "--no-render")]
        (is (= 0 (:exit res)))
        (is (some #(str/includes? % "no timecode") (:warnings res)))))))

(deftest without-facts-the-filters-say-so
  (let [meta (str (fs/create-temp-dir {:prefix "bbcli-meta-"}))]
    (fs/copy-tree s/fixture-meta meta)
    (fs/delete-tree (fs/path meta "probe"))
    (let [{:keys [run index]} (runner meta)
          out (run "" "footage" "--slow-motion")]
      (is (= "none: media_meta has no probe folder" (:probe index)))
      (is (= "no-probe" (-> out :error :code)))
      (is (= 4 (:total (run "" "footage"))) "without facts, footage is the transcribed files, as before"))))

(deftest the-newest-version-is-read
  (let [meta (str (fs/create-temp-dir {:prefix "bbcli-meta-"}))
        rel "media/2026/09/16/src/fx3/C5280.MP4"]
    (fs/copy-tree s/fixture-meta meta)
    (let [[f] (fs/glob (fs/path meta "probe" "v1") (str rel ".*.json"))
          doc (-> (json/parse-string (slurp (str f)) true)
                  (assoc-in [:video :capture_fps] 23.976)
                  (assoc-in [:video :slow_motion] nil))]
      (fs/create-dirs (fs/path meta "probe" "v9"))
      (fs/create-dirs (fs/parent (fs/path meta "probe" "v10" rel)))
      (spit (str (fs/path meta "probe" "v10" (str (fs/relativize (fs/path meta "probe" "v1") f))))
            (json/generate-string doc)))
    (let [{:keys [run index]} (runner meta)]
      (is (= {:version "v10" :files 1} (select-keys (:probe index) [:version :files])) "v10 after v9, by number")
      (is (= 0 (:total (run "" "footage" "--slow-motion"))) "v10's facts, not v1's"))))

(deftest a-file-is-only-the-facts-of-its-own-original
  (let [doc {:schema "media-mgmt.probe/1" :source {:rel "media/a/C1.MP4" :sha256 "0123456789abcdef"}}]
    (is (some? (probe/row doc "media/a/C1.MP4" "0123456789ab")))
    (is (nil? (probe/row doc "media/b/C1.MP4" "0123456789ab")) "another original's path")
    (is (nil? (probe/row doc "media/a/C1.MP4" "ffffffffffff")) "another original's sha")
    (is (nil? (probe/row (assoc doc :schema "x/1") "media/a/C1.MP4" "0123456789ab")))))
