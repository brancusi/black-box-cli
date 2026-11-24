(ns black-box-cli.update-test
  "`blackbox update` hands the update to Black Box (faked here by a script that keeps its arguments and prints an
  answer); the home view says how this blackbox was installed when Black Box's launcher says."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.support :as s]
   [black-box-cli.version :as version]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- fake-black-box
  "A stand-in for Black Box: writes its arguments beside itself, prints `answer`, exits with `exit`."
  [answer exit]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-app-"})
        f (str (fs/path dir "BlackBox"))]
    (spit (str (fs/path dir "answer")) answer)
    (spit f (str "#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/args\"\ncat \"$(dirname \"$0\")/answer\"\nexit " exit "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(defn- update! [answer exit]
  (let [app (fake-black-box answer exit)
        res (s/cli* {:env {"BLACKBOX_APP" app}} ["update" "--format" "edn"])]
    (assoc res :edn (edn/read-string (:out res)) :args (str/trim (slurp (str (fs/path (fs/parent app) "args")))))))

(deftest black-box-does-the-update
  (testing "updated: what's running, what's installed, and that the skill came with it"
    (let [{:keys [exit edn args]} (update! "{\"state\": \"updated\", \"latest\": \"9.9.9\", \"installed\": \"9.9.9\", \"black_box\": \"1.0.0\"}" 0)]
      (is (= "--cli cli update --format json" args))
      (is (= 0 exit))
      (is (= {:state "updated" :running version/version :latest "9.9.9" :installed "9.9.9" :black_box "1.0.0"}
             (dissoc edn :help)))
      (is (some #(str/includes? % "skill") (:help edn)))))
  (testing "a release that needs a newer Black Box waits, and says which"
    (let [{:keys [edn]} (update! "{\"state\": \"needs-black-box\", \"latest\": \"9.9.9\", \"installed\": \"0.1.0\", \"black_box\": \"1.0.0\", \"min_black_box\": \"1.2.0\"}" 0)]
      (is (= "1.2.0" (:needs_black_box edn)))
      (is (some #(str/includes? % "needs Black Box 1.2.0") (:help edn)))))
  (testing "a release without a signature isn't installed"
    (let [{:keys [edn]} (update! "{\"state\": \"unsigned\", \"latest\": \"9.9.9\", \"installed\": \"0.1.0\", \"black_box\": \"1.0.0\"}" 0)]
      (is (= "unsigned" (:state edn)))
      (is (some #(str/includes? % "without its signature") (:help edn)))))
  (testing "a bad signature is an error, and the running blackbox stays"
    (let [{:keys [exit edn]} (update! "{\"error\": {\"code\": \"signature_invalid\", \"message\": \"the signature doesn't verify\"}}" 1)]
      (is (= 1 exit))
      (is (= "signature_invalid" (-> edn :error :code)))
      (is (some #(str/includes? % "keeps running") (:help edn)))))
  (testing "a Black Box without `cli update` is too old"
    (let [{:keys [exit edn]} (update! "error: unknown command" 2)]
      (is (= 1 exit))
      (is (= "black-box-too-old" (-> edn :error :code))))))

(deftest the-home-view-says-how-it-was-installed
  (let [home (fn [env] (edn/read-string (:out (s/cli* {:env env} ["--meta" s/fixture-meta "--format" "edn"]))))]
    (is (= "updated, Black Box 0.45.0"
           (:installed (home {"BLACKBOX_CLI_SOURCE" "updated" "BLACKBOX_APP_VERSION" "0.45.0"}))))
    (is (not (contains? (home {}) :installed)) "not started by Black Box: nothing to say")))
