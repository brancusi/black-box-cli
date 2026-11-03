(ns black-box-cli.project-test
  "`edl build` hands a cut to Black Box (faked here by a script that logs how it was run) and `edl show` asks it how
  the build is going. Nothing reaches the mini or Resolve."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- fake-black-box
  "A stand-in for Black Box's `project` commands: `build` prints `build-out`, `status` prints `status-out`, each
  exiting `exit`. Every run is logged, one line of arguments, in `log`."
  [{:keys [build-out status-out exit] :or {exit 0}}]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-app-"})
        f (str (fs/path dir "BlackBox"))
        log (str (fs/path dir "calls"))]
    (spit (str (fs/path dir "build")) (str build-out "\n"))
    (spit (str (fs/path dir "status")) (str status-out "\n"))
    (spit f (str "#!/bin/sh\necho \"$@\" >> '" log "'\n"
                 "cat \"$(dirname \"$0\")/$3\"\n"
                 "exit " exit "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:app f :log log}))

(defn- calls [log]
  (if (fs/exists? log) (str/split-lines (slurp log)) []))

(defn- runner []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [app & args]
              (let [res (s/cli* {:home home :stdin "title: Marble\nr54qb74c.1\n"
                                 :env {"BLACKBOX_APP" app "PATH" ""}}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    (run "/nowhere/BlackBox" "index")
    {:local local :run run}))

(def ^:private queued
  (json/generate-string {:request "r-1" :state "queued" :project "Marble (x)"}))

(deftest a-cut-is-sent-once
  (let [{:keys [local run]} (runner)
        id (:id (run "/nowhere/BlackBox" "edl" "new" "-"))
        folder (str (fs/path local "cuts" id))
        {:keys [app log]} (fake-black-box
                           {:build-out queued
                            :status-out (json/generate-string {:state "done" :project "Marble (x)"
                                                               :report {:clips 1 :without_original 0
                                                                        :synced_audio 0 :without_sync 0}})})]
    (testing "before: show has no project"
      (is (not (contains? (run app "edl" "show" id) :project)))
      (is (empty? (calls log)) "and doesn't ask Black Box"))
    (let [res (run app "edl" "build" id)]
      (is (= [:id :title :project :request :state :help] (keys (dissoc res :exit))))
      (is (= [id "Marble" "Marble (x)" "r-1" "queued"] ((juxt :id :title :project :request :state) res)))
      (is (= [(str "--cli project build --cut " folder " --format json")] (calls log)) "the cut's own folder"))
    (testing "build.json keeps the request, written once"
      (is (= {:schema "black-box-cli.build/1" :request "r-1" :project "Marble (x)"}
             (dissoc (json/parse-string (slurp (str (fs/path folder "build.json"))) true) :asked_at))))
    (testing "show asks Black Box how it's going"
      (is (= {:name "Marble (x)" :request "r-1" :state "done"
              :report {:clips 1 :without_original 0 :synced_audio 0 :without_sync 0}}
             (:project (run app "edl" "show" id))))
      (is (= "--cli project status r-1 --format json" (last (calls log)))))
    (testing "a second build is refused, and Black Box isn't asked"
      (let [n (count (calls log))
            res (run app "edl" "build" id)]
        (is (= ["already-sent" 1] [(-> res :error :code) (:exit res)]))
        (is (= n (count (calls log))))))
    (testing "when Black Box can't answer, show still works and says why"
      (is (= "unknown: Black Box isn't installed at /nowhere/BlackBox"
             (-> (run "/nowhere/BlackBox" "edl" "show" id) :project :state))))))

(deftest a-failed-build-says-why
  (let [{:keys [run]} (runner)
        id (:id (run "/nowhere/BlackBox" "edl" "new" "-"))
        failed (fn [doc] (:project (run (:app (fake-black-box {:build-out queued :status-out (json/generate-string doc)}))
                                        "edl" "show" id)))
        {:keys [app]} (fake-black-box {:build-out queued})]
    (run app "edl" "build" id)
    (is (= {:code "no_original" :message "C5274.MP4 isn't in the catalog"}
           (:error (failed {:state "failed" :error {:code "no_original" :message "C5274.MP4 isn't in the catalog"}}))))
    (is (= {:name "Marble (x)" :request "r-1" :state "failed"
            :error {:code "project_exists" :message "Marble (x) is already on the database"}}
           (failed {:request "r-1" :state "failed" :project "Marble (x)"
                    :error {:code "project_exists" :message "Marble (x) is already on the database"}})))
    (is (= {:clips 2 :placed 2 :audio_only 1 :notes ["one clip has no recorder"]}
           (:report (failed {:request "r-1" :state "done" :project "Marble (x)"
                             :report {:clips 2 :placed 2 :audio_only 1 :notes ["one clip has no recorder"]}})))
        "the report as Black Box gives it, whatever its keys")))

(deftest when-the-cut-cant-be-sent-nothing-is-written
  (let [{:keys [local run]} (runner)
        id (:id (run "/nowhere/BlackBox" "edl" "new" "-"))
        build-json (fs/path local "cuts" id "build.json")
        try-build (fn [opts] (run (:app (fake-black-box opts)) "edl" "build" id))]
    (testing "Black Box isn't installed"
      (is (= "no-black-box" (-> (run "/nowhere/BlackBox" "edl" "build" id) :error :code))))
    (testing "the app isn't open: its error, and what to do"
      (let [res (try-build {:exit 1 :build-out (json/generate-string
                                                {:error {:code "app_not_running"
                                                         :message "Black Box isn't running on this Mac"}})})]
        (is (= ["app_not_running" 1] [(-> res :error :code) (:exit res)]))
        (is (str/starts-with? (first (:help res)) "Open Black Box"))))
    (testing "an older Black Box without `project`"
      (let [res (try-build {:exit 2 :build-out "error:\n  code: usage\n  message: \"argument command: invalid choice: project\""})]
        (is (= "black-box-too-old" (-> res :error :code)))
        (is (str/includes? (-> res :error :message) "invalid choice: project"))))
    (testing "an answer without a request"
      (is (= "black-box-failed" (-> (try-build {:build-out "{}"}) :error :code))))
    (is (not (fs/exists? build-json)))))

(deftest a-cut-in-black-boxs-old-folder-isnt-sent
  (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        {:keys [app log]} (fake-black-box {:build-out queued})
        run (fn [& args]
              (read-string (:out (s/cli* {:home home :stdin "title: Old\nr54qb74c.1\n" :env {"BLACKBOX_APP" app "PATH" ""}}
                                         (concat ["--meta" s/fixture-meta "--format" "edn"] args)))))
        _ (run "index")
        id (:id (run "edl" "new" "-"))
        old (fs/path home "Library/Application Support/Media Utilities/media-mgmt/cuts" "20250101T000000Z-old")]
    (fs/create-dirs (fs/parent old))
    (fs/move (fs/path home "Library/Application Support/black-box-cli/cuts" id) old)
    (is (= "old-cut" (-> (run "edl" "build" "20250101T000000Z-old") :error :code)))
    (is (empty? (calls log)))
    (is (not (fs/exists? (fs/path old "build.json"))))))
