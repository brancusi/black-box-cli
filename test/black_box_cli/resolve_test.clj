(ns black-box-cli.resolve-test
  "`edl resolve`: a cut list (proxy paths too) with pre- and post-roll, back to the originals, handed to Resolve's
  Python (faked here by a script that keeps the plan and prints an answer). Nothing opens Resolve or an original."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.edl :as edl]
   [black-box-cli.resolve :as resolve]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- fake-python
  "A stand-in for Resolve's Python: keeps the plan it's given (its second argument) and prints `answer`."
  [answer]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-py-"})
        f (str (fs/path dir "python"))]
    (spit (str (fs/path dir "answer")) (str answer "\n"))
    (spit f "#!/bin/sh\ncp \"$2\" \"$(dirname \"$0\")/plan.json\"\ncat \"$(dirname \"$0\")/answer\"\n")
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:python f :dir (str dir)}))

(defn- runner []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [python stdin & args]
              (let [res (s/cli* {:home home :stdin stdin :env {"BLACKBOX_RESOLVE_PYTHON" python "PATH" ""}}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    (run "/nowhere/python" "" "index")
    {:local local :run run}))

(def ^:private answer
  (json/generate-string {:project "Kiln (x)" :created_project true :database "Local Database" :timeline "Kiln"
                         :method "edl" :edl_note nil :clips 2 :imported 1 :reused 0 :missing []
                         :proxies_linked 1 :launched true :resolve "21.1.0.17"}))

(def ^:private cut
  (str "title: Kiln\n"
       "<meta>/proxies/media/2026/09/16/src/fx3/C5274.MP4.proxy.mp4 0:10-0:12 pre 1 post 30\n"
       "C5274.MP4 0:01-0:03\n"))

(deftest a-clip-s-handles
  (testing "the text form and JSON carry a clip's own pre and post"
    (is (= {:pre "1" :post "3"} (select-keys (first (:items (cutlist/parse "C5274.MP4 0:21-0:26 pre 1 post 3"))) [:pre :post])))
    (is (= {:file "C5274.MP4" :in "0:21" :out "0:26"}
           (select-keys (first (:items (cutlist/parse "C5274.MP4 0:21-0:26 # pre 1 post 3"))) [:file :in :out]))
        "a note is a note")
    (is (= {:kind :file :file "/m/proxies/a.MP4.proxy.mp4" :pre "0.5"}
           (select-keys (first (:items (cutlist/parse "{\"clips\":[{\"proxy\":\"/m/proxies/a.MP4.proxy.mp4\",\"in\":1,\"out\":2,\"pre\":0.5}]}")))
                        [:kind :file :pre]))))
  (testing "a proxy's path stands for its file"
    (is (= "media/2026/09/16/src/fx3/C5274.MP4" (edl/proxy->file "/x/media_meta/proxies/media/2026/09/16/src/fx3/C5274.MP4.proxy.mp4")))
    (is (= "C5274.MP4" (edl/proxy->file "C5274.MP4.proxy.mp4")))
    (is (= "C5274.MP4" (edl/proxy->file "C5274.MP4"))))
  (testing "handles are clamped to the file, and say so"
    (let [[[a b] warnings] (resolve/with-handles [{:source_name "A" :start 1.0 :end 5.0 :duration 6.0}
                                                  {:source_name "B" :start 3.0 :end 4.0 :duration 9.0 :pre "0.5"}]
                             {:pre 2.0 :post 2.0})]
      (is (= [0.0 6.0 1.0 1.0] ((juxt :start :end :pre_s :post_s) a)))
      (is (= [2.5 6.0] ((juxt :start :end) b)) "a clip's own pre first")
      (is (= ["clip 1 (A): pre-roll 1.0 s (the file starts), post-roll 1.0 s (the file ends)"] warnings))))
  (is (= ["23.976" "25" "29.97"] (map resolve/fps-setting [[24000 1001] [25 1] [30000 1001]]))))

(deftest a-cut-becomes-a-timeline
  (let [{:keys [run]} (runner)
        {:keys [python dir]} (fake-python answer)
        stdin (str/replace cut "<meta>" s/fixture-meta)]
    (testing "--dry-run: the clips with their handles; nothing written, Resolve not asked"
      (let [out (run python stdin "online" "-" "--dry-run")]
        (is (= 0 (:exit out)))
        (is (= [{:n 1 :file "C5274.MP4" :in "0:09.0" :out "0:32.5" :pre 1.0 :post 20.5 :length 23.5 :recorders 0}
                {:n 2 :file "C5274.MP4" :in "0:00.0" :out "0:05.0" :pre 1.0 :post 2.0 :length 5.0 :recorders 0}]
               (:cut out)))
        (is (not (fs/exists? (fs/path dir "plan.json"))))))
    (testing "the plan Resolve's Python gets, and what it said"
      (let [out (run python stdin "online" "-" "--into" "new-project")
            plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
        (is (= 0 (:exit out)))
        (is (= {:project "Kiln (x)" :timeline "Kiln" :clips 2 :by "the EDL, imported"}
               (select-keys (:resolve out) [:project :timeline :clips :by])))
        (is (= ["new-project" "Kiln" "23.976" true] ((juxt :into :timeline :fps :proxies) plan)))
        (is (= (str "Kiln (" (:id out) ")") (:project plan)))
        (is (= {:source_path "/nowhere/media/2026/09/16/src/fx3/C5274.MP4" :video true :in_frame 216 :out_frame 779
                :record_frame 0 :tc "03:06:51:08" :tc_fps (/ 24000.0 1001)}
               (dissoc (first (:clips plan)) :proxy))
            "where the file starts, for Resolve to find it by its path")
        (is (= 563 (:record_frame (second (:clips plan)))) "placed by frame, a clip starts where the frames before it end")
        (testing "what's said goes on the subtitle track, timed from the same frames"
          (let [srt (slurp (:subtitles plan))
                cues (re-seq #"(\d\d):(\d\d):(\d\d),(\d{3}) --> " srt)
                secs (map (fn [[_ h m sec ms]] (+ (* 3600 (parse-long h)) (* 60 (parse-long m)) (parse-long sec) (/ (parse-long ms) 1000.0))) cues)]
            (is (re-find #"lines on the subtitle track" (:subtitles out)))
            (is (seq cues))
            (is (apply <= secs) "in timeline order")
            (is (< (last secs) (/ (+ 563 120) (/ 24000.0 1001))) "within the timeline")
            (is (str/ends-with? (:subtitles plan) ".srt"))))
        (is (str/ends-with? (:proxy (first (:clips plan))) "C5274.MP4.proxy.mp4"))
        (is (fs/exists? (:edl plan)) "the EDL is written before Resolve is asked")
        (is (= (:edl out) (str (fs/file-name (:edl plan)))))
        (is (= "Kiln" (:timeline (json/parse-string (slurp (str/replace (:edl plan) #"\.edl$" ".json")) true))))))
    (testing "--originals: the NAS mounted somewhere else"
      (run python stdin "online" "-" "--originals" "/Volumes/Other/MMM" "--no-proxies")
      (let [plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
        (is (= "/Volumes/Other/MMM/media/2026/09/16/src/fx3/C5274.MP4" (:source_path (first (:clips plan)))))
        (is (false? (:proxies plan)))))))

(deftest a-saved-edit-goes-online
  (let [{:keys [run]} (runner)
        {:keys [python dir]} (fake-python answer)
        stdin (str/replace cut "<meta>" s/fixture-meta)
        {:keys [id folder]} (run python stdin "edl" "new" "-" "--no-render")
        out (run python "" "online" (subs id 0 (- (count id) 2)) "--into" "current")
        plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
    (is (= 0 (:exit out)))
    (is (= id (:id out)) "the same edit, found by part of its id")
    (is (= [216 779] ((juxt :in_frame :out_frame) (first (:clips plan))))
        "a clip's own pre and post, kept in cut.json")
    (is (= "current" (:into plan)))
    (let [files (set (map (comp str fs/file-name) (fs/list-dir folder)))]
      (is (some #(re-matches #"online-.*\.edl" %) files) "the EDL with its handles, beside the edit")))
  (testing "--dry-run on a saved edit: Resolve isn't asked"
    (let [{:keys [run]} (runner)
          {:keys [python dir]} (fake-python answer)
          {:keys [id]} (run python "title: K\nC5274.MP4 0:01-0:03\n" "edl" "new" "-" "--no-render")]
      (is (= 0 (:exit (run python "" "online" id "--dry-run"))))
      (is (not (fs/exists? (fs/path dir "plan.json")))))))

(deftest recorders-come-synced
  (let [{:keys [run]} (runner)
        {:keys [python dir]} (fake-python answer)
        cut "title: Kiln\nC5274.MP4 0:20-0:25\nC5274.MP4 0:01-0:03\n"]
    (testing "the fixture's sync map puts DJI_01.WAV with C5274 from 13.2 s, 13.2 s earlier"
      (let [out (run python cut "online" "-" "--pre" "1" "--post" "1")
            plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
        (is (= 0 (:exit out)))
        (is (= [1 0] (map :recorders (:cut out))) "the second clip is outside the overlap")
        (is (str/starts-with? (:synced_audio out) "1 recorder clip from 1 recorder"))
        (is (= [{:source_path "/nowhere/media/2026/09/16/audio/DJI_01.WAV" :in_s 5.8 :out_s 12.8
                 :in_frame 139 :out_frame 306 :record_frame 0 :clip_frame 0 :track 1 :tc nil :tc_fps (/ 24000.0 1001)}]
               (:audio plan))
            "recorder time = camera time + offset_s, the clip's own length, at the clip's place")))
    (testing "--no-audio"
      (run python cut "online" "-" "--no-audio")
      (is (= [] (:audio (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)))))))

(deftest what-goes-wrong
  (let [{:keys [run]} (runner)
        stdin (str/replace cut "<meta>" s/fixture-meta)]
    (testing "Resolve's own errors pass through, with what to do"
      (let [{:keys [python]} (fake-python (json/generate-string {:error {:code "resolve-scripting-off" :message "no scripts"}}))
            out (run python stdin "online" "-")]
        (is (= "resolve-scripting-off" (-> out :error :code)))
        (is (str/includes? (first (:help out)) "External scripting"))))
    (testing "no Resolve"
      (is (= "no-resolve" (-> (run "/nowhere/python" stdin "online" "-") :error :code))))
    (testing "usage"
      (is (= 2 (:exit (run "/nowhere/python" stdin "online" "-" "--into" "elsewhere")))))))

(deftest originals-already-in-the-project-are-used-where-they-are
  (testing "Resolve finds the camera clips in any bin, each checked by its path: an exact path or the same media/ path,
            never a file name alone; the recorders come from the Black Box bin, the project is never walked"
    (let [dir (str (fs/create-temp-dir))
          {:keys [exit out err]} (process/sh "python3" "test/black_box_cli/resolve_build_check.py"
                                             "src/black_box_cli/resolve_build.py" dir)]
      (is (zero? exit) err)
      (is (= {"in_our_bin" {"2026/09/16/audio/REC01.WAV" "an earlier cut"}
              "asked_in_our_bin" 1
              "by_resolve" {"2026/09/16/fx3/C0001.MP4" ["Editor media" "/Volumes/Video/media/2026/09/16/fx3/C0001.MP4"]
                            "2026/09/17/fx3/C0002.MP4" ["Editor media" "/Volumes/Video-1/media/2026/09/17/fx3/C0002.MP4"]
                            "2026/09/18/audio/REC02 b's.WAV" ["Sound" "/Volumes/Video/media/2026/09/18/audio/REC02 b's.WAV"]}
              "probe_deleted" 1
              "shown_after" "the editor's own"
              "xml_written" true
              "xml_has_format" true
              "xml_quoted" true}
             (json/parse-string out))
          (str "C0001 is taken by its path over another card's of the same name; C0003 Resolve matched by its name to "
               "another card's file: not taken, it's imported; the recorder in a bin's own bin is found; the editor's "
               "timeline is back on screen")))))
