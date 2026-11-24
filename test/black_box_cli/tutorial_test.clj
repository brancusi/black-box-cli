(ns black-box-cli.tutorial-test
  "`tutorial`: the lessons an agent teaches from, every example a real command line, and docs/EDITORS.md the same
  lessons for people."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.flags :as flags]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [black-box-cli.tutorial :as tutorial]
   [black-box-cli.version :as version]
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

(deftest every-lesson-is-in-one-workflow
  (is (= (sort (map :id tutorial/lessons)) (sort (mapcat :lessons tutorial/workflows))))
  (is (every? :punch tutorial/lessons)))

(deftest every-command-is-taught
  (testing "a new command fails here until a lesson runs it, so the tutorial keeps up with the CLI"
    (let [taught (set (for [{:keys [runs]} tutorial/lessons [line] runs
                            :let [[spec] (flags/find-command (rest (str/split line #"\s+")))] :when spec]
                        (:path spec)))]
      (doseq [{:keys [path]} flags/commands :when (not= ["setup" "skill-check"] path)]
        (is (taught path) (str "no lesson runs `blackbox " (str/join " " path) "`"))))))

(defn- version-vec [v] (mapv parse-long (str/split v #"\.")))

(deftest whats-new-keeps-up
  (testing "each release has its news (add it to tutorial/news with the CHANGELOG line)"
    (is (>= (compare (version-vec (:version (first tutorial/news))) (version-vec version/version)) 0)))
  (is (every? (set (map :id tutorial/lessons)) (map :lesson (mapcat :items tutorial/news))))
  (let [out (run "tutorial" "new")]
    (is (= (map :version tutorial/news) (map :version (:news out))))))

(deftest the-animated-page
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        go (fn [& args] (read-string (:out (s/cli* {:home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))}
                                                   (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args)))))
        a (go "tutorial" "--html")
        html (slurp (:tutorial a))]
    (is (str/starts-with? (:tutorial a) (str (fs/path local "tutorial") "/tutorial-" version/version "-")))
    (is (= (:tutorial a) (:tutorial (go "tutorial" "--html"))) "the same page is written once")
    (is (not (str/includes? html "/*TUTORIAL_DATA*/")))
    (is (str/includes? html "\"punch\":"))
    (is (str/includes? html "\"usage\":\"blackbox review new <selects>\"") "every command, from the flags table")
    (is (= 3 (count (re-seq #"</script>" html))))
    (testing "the narration is copied beside the page"
      (is (= (count (tutorial/narration-lines)) (count (fs/list-dir (fs/path local "tutorial" "voice"))))))
    (let [opened (atom nil)]
      (with-redefs [tools/spawn! #(reset! opened %)]
        (is (:opened (go "tutorial" "--open")))
        (is (= ["/usr/bin/open" (:tutorial a)] @opened))))))

(deftest every-line-is-narrated
  (testing "a changed or new line needs its audio: run `bb narrate` (ElevenLabs), then commit tutorial_voice/"
    (let [m (tutorial/voice-manifest)]
      (doseq [[id text] (tutorial/narration-lines)
              :let [sha (tutorial/line-sha text)]]
        (is (= text (:text (get m sha))) (str id ": no audio for " (pr-str text)))
        (is (fs/exists? (fs/path "src/black_box_cli/tutorial_voice" (str sha ".mp3"))) id)))))
