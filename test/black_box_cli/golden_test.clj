(ns black-box-cli.golden-test
  "The golden steps that are built, run in order on one --local folder and compared byte for byte, so a step that
  matches can't slip back. `bb golden` reports on all of them, built or not."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.support :as s]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def built
  "The steps whose commands are built: phase 2 (index, the home view, search, line, transcript, footage) and
  phase 3 (edl new, list, show)."
  (set (range 1 45)))

(deftest built-steps-match-their-goldens
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-golden-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))]
    (doseq [{:keys [n argv stdin exit expected]} (s/golden-steps)
            :when (built n)]
      (let [{got-exit :exit out :out} (s/cli* {:stdin stdin :home home}
                                              (concat ["--meta" s/fixture-meta "--local" local] argv))]
        (testing (str "step " n ": blackbox " (str/join " " argv))
          (is (= exit got-exit))
          (is (= expected (s/normalise out {:local local :home home}))))))
    (testing "the files of the cut step 40 wrote"
      (let [want (s/golden-cut-files)
            got (s/cut-files local home)]
        (is (= (keys want) (keys got)))
        (doseq [[name text] want]
          (testing name (is (= text (get got name)))))))))
