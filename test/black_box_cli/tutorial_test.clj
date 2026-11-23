(ns black-box-cli.tutorial-test
  "`tutorial`: the lessons an agent teaches from, every example a real command line, and docs/EDITORS.md the same
  lessons for people."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.flags :as flags]
   [black-box-cli.support :as s]
   [black-box-cli.tutorial :as tutorial]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- run [& args]
  (let [res (s/cli* {:home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))}
                    (concat ["--meta" s/fixture-meta "--format" "edn"] args))]
    (assoc (read-string (:out res)) :exit (:exit res))))

(deftest the-overview-and-a-lesson
  (let [out (run "tutorial")]
    (is (= 0 (:exit out)))
    (is (= (map :id tutorial/lessons) (map :id (:lessons out))))
    (is (every? :try (:lessons out))))
  (let [out (run "tutorial" "online")]
    (is (= ["online" "project"] ((juxt :lesson :next) out)))
    (is (seq (:ask out)))
    (is (every? :run (:runs out))))
  (is (nil? (:next (run "tutorial" "stills"))) "the last lesson")
  (is (= (count tutorial/lessons) (count (:lessons (run "tutorial" "all")))))
  (is (= "not-found" (-> (run "tutorial" "nope") :error :code))))

(deftest every-example-is-a-real-command-line
  (doseq [{:keys [id runs]} tutorial/lessons
          [line] runs
          :let [words (rest (str/split line #"\s+"))]
          :when (seq words)]
    (testing (str id ": " line)
      (let [[spec tokens] (flags/find-command words)]
        (is spec)
        (is (map? (flags/parse spec tokens)))))))

(deftest editors-md-is-the-lessons
  (is (= (tutorial/markdown) (slurp "docs/EDITORS.md")) "run `bb editors` after changing a lesson"))
