(ns black-box-cli.shots-test
  "Shot logs on the fixture's invented ones: the index reads them as a cache, `shots` finds them by words and by
  what the camera did, `line` shows the shot a line is in, and a shot id in a cut list plays its best moment."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.support :as s]
   [clojure.test :refer [deftest is testing]]))

(defn- copy-meta []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- runner
  "A new --local folder and runners over `meta` that read EDN: (run & args) and (run-in stdin & args), each
  {:exit :edn}."
  ([] (runner s/fixture-meta))
  ([meta]
   (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
         home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
         run-in (fn [stdin & args]
                  (let [{:keys [exit out]} (s/cli* {:home home :env {"PATH" ""} :stdin stdin}
                                                   (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                    {:exit exit :edn (read-string out)}))]
     {:local local :run (partial run-in nil) :run-in run-in})))

(defn- cut [run-in text]
  (run-in text "edl" "new" "-" "--dry-run"))

(defn- ids [res] (mapv :id (:shots (:edn res))))

(deftest the-index-reads-the-shot-logs
  (let [meta (copy-meta)
        {:keys [run local]} (runner meta)]
    (is (= {:version "gemini-3.1-pro/v1" :clips 3 :shots 5 :skipped 1} (:shots (:edn (run "index"))))
        "a log that isn't one is skipped and counted")
    (testing "the same again, and from scratch"
      (is (= {:version "gemini-3.1-pro/v1" :clips 3 :shots 5 :skipped 1} (:shots (:edn (run "index")))))
      (is (= {:version "gemini-3.1-pro/v1" :clips 3 :shots 5 :skipped 1} (:shots (:edn (run "index" "--rebuild"))))))
    (testing "a new log comes in"
      (fs/copy (fs/path meta "shots/gemini-3.1-pro/v1/media/2026/09/16/src/fx3/C5280.MP4.shots.json")
               (fs/path meta "shots/gemini-3.1-pro/v1/media/2026/09/16/src/fx3/C5281.MP4.shots.json"))
      (is (= 3 (:clips (:shots (:edn (run "index"))))) "a log of another original's rel isn't read")
      (spit (str (fs/path meta "shots/gemini-3.1-pro/v1/media/2026/09/16/src/fx3/C5281.MP4.shots.json"))
            (-> (slurp (str (fs/path meta "shots/gemini-3.1-pro/v1/media/2026/09/16/src/fx3/C5280.MP4.shots.json")))
                (.replace "C5280.MP4" "C5281.MP4")))
      (is (= 4 (:clips (:shots (:edn (run "index")))))))
    (testing "another prompt's folder isn't read"
      (fs/create-dirs (fs/path meta "shots/gemini-3.1-pro/v2/media"))
      (fs/copy (fs/path meta "shots/gemini-3.1-pro/v1/media/2026/09/16/src/fx3/C5280.MP4.shots.json")
               (fs/path meta "shots/gemini-3.1-pro/v2/media/C5280.MP4.shots.json"))
      (is (= 4 (:clips (:shots (:edn (run "index")))))))
    (testing "no marker, no shot logs: the old database goes"
      (fs/delete (fs/path meta "shots/.blackbox-shots-root"))
      (is (= "none: media_meta has no shots folder" (:shots (:edn (run "index")))))
      (is (not (fs/exists? (fs/path local "search" "shots.sqlite"))))
      (is (= "no-shots" (-> (run "shots" "boat") :edn :error :code))))))

(deftest shots-by-words-and-by-camera
  (let [{:keys [run]} (runner)]
    (run "index")
    (let [{:keys [exit edn]} (run "shots" "drill")]
      (is (= 0 exit))
      (is (= {:query "drill" :match "any word" :scope "everything" :logs "gemini-3.1-pro/v1" :count 2 :total 2 :files 1}
             (dissoc edn :shots :help)))
      (is (= {:id "r54qb74c.s2" :day "2026-09-16" :file "C5274.MP4" :at "0:20.5" :length "0:12.0" :framing "close"
              :camera "static" :action "Hands guide a hammer drill into a block of marble." :best "0:26.5-0:29.5"
              :issues nil}
             (first (:shots edn)))
          "the shot that says it first; the clip's summary matches its other shot too"))
    (is (= ["luyk4sl6.s1" "luyk4sl6.s2"] (ids (run "shots" "kiln"))) "the clip's summary and tags are searched")
    (is (= ["r54qb74c.s1"] (ids (run "shots" "crate" "light" "--all"))) "the reason it is the best moment too")
    (is (= ["sqlknzuz.s1"] (ids (run "shots" "--camera" "pan"))))
    (is (= ["r54qb74c.s2" "luyk4sl6.s2"] (ids (run "shots" "--framing" "close"))) "without words, by date")
    (is (= ["r54qb74c.s2" "luyk4sl6.s1"] (ids (run "shots" "--clean"))))
    (is (= ["luyk4sl6.s1" "luyk4sl6.s2"] (ids (run "shots" "--month" "2026-10"))))
    (testing "--file: one file's shots, with its summary"
      (let [{:keys [edn]} (run "shots" "--file" "C5274.MP4")]
        (is (= ["r54qb74c.s1" "r54qb74c.s2"] (mapv :id (:shots edn))))
        (is (= {:file "C5274.MP4" :rel "media/2026/09/16/src/fx3/C5274.MP4"
                :summary "In a stone yard two people talk, then a hammer drill cuts into a marble block."
                :tags "stone yard, hammer drill, dust, marble"}
               (:clip edn)))))
    (testing "--file on a clip with no shot log says so, not that nothing matched"
      (is (= "no shot log for DJI_01.WAV yet" (:result (:edn (run "shots" "--file" "DJI_01.WAV")))))
      (is (= "0 shots match" (:result (:edn (run "shots" "--file" "C5274.MP4" "--camera" "zoom"))))))
    (testing "--fields all: why, timecode and the clip's tags; a file without a transcript has no timecode"
      (let [row (first (:shots (:edn (run "shots" "boat" "--fields" "all"))))]
        (is (= ["sqlknzuz.s1" "C5280.MP4" "The boat is centred against the sky." "shake, vignette" nil]
               ((juxt :id :file :why :issues :tc) row))))
      (is (= ["03:07:11:20" "03:07:17:19-03:07:20:19"]
             ((juxt :tc :best_tc) (first (:shots (:edn (run "shots" "drill" "--fields" "all"))))))))
    (testing "nothing found, and what can't be asked"
      (is (= "0 shots match" (:result (:edn (run "shots" "zzz")))))
      (is (= 2 (:exit (run "shots"))) "every shot is too many: words or a filter")
      (is (= 2 (:exit (run "shots" "x" "--bogus")))))))

(deftest a-line-shows-its-shot
  (let [{:keys [run]} (runner)]
    (run "index")
    (is (= "r54qb74c.s1" (-> (run "line" "r54qb74c.1") :edn :shot :id)))
    (is (= "r54qb74c.s2" (-> (run "line" "r54qb74c.3") :edn :shot :id)))
    (is (nil? (-> (run "line" "2npshqu5.1") :edn :shot)) "no log for a recorder")))

(deftest a-shot-in-a-cut-list
  (is (= [{:n 1 :kind :shot :shot "r54qb74c.s2" :whole false :note ""}
          {:n 2 :kind :shot :shot "luyk4sl6.s1" :whole true :note "kiln"}]
         (:items (cutlist/parse "r54qb74c.s2\nluyk4sl6.s1 whole # kiln\n"))))
  (is (= [{:n 0 :kind :shot :shot "r54qb74c.s2" :whole true :note ""}]
         (:items (cutlist/parse "{\"clips\": [{\"shot\": \"r54qb74c.s2\", \"whole\": true}]}"))))
  (let [{:keys [run run-in]} (runner)]
    (run "index")
    (let [{:keys [exit edn]} (cut run-in "title: Shots\nr54qb74c.s2\nluyk4sl6.s1 whole\n")]
      (is (= 0 exit))
      (is (= [["C5274.MP4" "0:26.5" "0:29.5"] ["C5900.MP4" "0:00.0" "0:30.0"]]
             (mapv (juxt :file :in :out) (:cut edn)))
          "its best moment, or all of it"))
    (testing "what can't be cut says why"
      (is (= ["clip 1 (line 2): no shot r54qb74c.s9 in the shot logs"
              "clip 2 (line 3): no transcript for media/2026/09/16/src/fx3/C5280.MP4, which cut.edl needs for its timecode"]
             (-> (cut run-in "title: X\nr54qb74c.s9\nsqlknzuz.s1\n") :edn :error :problems))))))
