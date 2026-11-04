(ns black-box-cli.status-test
  "The index's state: `status` says how healthy it is and what media_meta has that it hasn't read, commands bring it
  up to date themselves, one build runs at a time, and `index --progress` reports on stderr."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.status :as status]
   [black-box-cli.support :as s]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io StringWriter)))

(defn- copy-meta []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- runner
  "Runs over `meta` and a new --local folder with commands refreshing the index (the tests' default turns it off):
  (run & args) → the EDN printed."
  [meta]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))]
    {:local local
     :run (fn [& args]
            (read-string (:out (s/cli* {:home home :env {"BLACKBOX_REFRESH" "auto"}}
                                       (concat ["--meta" meta "--local" local "--format" "edn"] args)))))}))

(def ^:private new-transcript "transcripts/deepgram-nova-3-multi/media/2026/10/03/src/a7s/C5999.MP4.json")

(defn- add-transcript!
  "A new transcript in `meta`, a copy of one there with another clip's name and words."
  [meta]
  (let [from (fs/path meta "transcripts/deepgram-nova-3-multi/media/2026/10/03/src/a7s/C5900.MP4.json")]
    (spit (str (fs/path meta new-transcript))
          (-> (slurp (str from)) (str/replace "C5900" "C5999") (str/replace "a different day" "a quarry at dawn")))))

(deftest status-says-how-the-index-is
  (let [meta (copy-meta)
        {:keys [run local]} (runner meta)]
    (testing "before a build"
      (is (= "not built" (:health (run "status"))))
      (is (= "never" (:indexed_at (run "status")))))
    (run "index")
    (let [st (run "status")]
      (is (= "ok" (:health st)))
      (is (= "just now" (:age st)))
      (is (= [["transcripts" "ok" "4 files"] ["frames" "ok" "7 frames in 3 files"] ["people" "ok" "3 people, 6 names"]
              ["prints" "ok" "3 voices, 4 faces"] ["sync" "ok" "1 days, 1 pairs"] ["shots" "ok" "5 shots in 3 clips"]]
             (mapv (juxt :source :state :holds) (:sources st)))))
    (testing "the home view says it too"
      (is (= "ok" (-> (run) :index :health))))
    (testing "a file new in media_meta makes it stale"
      (Thread/sleep 1100) ; a later ctime than the record's
      (add-transcript! meta)
      (let [st (run "status")]
        (is (= "stale" (:health st)))
        (is (= 1 (:pending st)))
        (is (= "stale" (:state (first (:sources st)))))))
    (testing "an index that can't be read is broken"
      (spit (str (fs/path local "search" "transcripts.sqlite")) "not a database")
      (is (= "broken" (:health (run "status")))))))

(deftest commands-keep-the-index-up-to-date
  (let [meta (copy-meta)
        {:keys [run local]} (runner meta)]
    (testing "a command builds a missing index"
      (is (= 2 (:total (run "search" "marble"))))
      (is (= "ok" (:health (run "status")))))
    (Thread/sleep 1100)
    (add-transcript! meta)
    (testing "within a minute of the last build it isn't checked"
      (is (= 0 (:count (run "search" "quarry")))))
    (testing "after that, a command reads what's new first"
      (with-redefs [status/refresh-after-s 0]
        (is (= 1 (:count (run "search" "quarry"))))
        (is (= "ok" (:health (run "status"))))))
    (testing "another build running: the command doesn't wait for it"
      (Thread/sleep 1100)
      (spit (str (fs/path meta new-transcript)) (str/replace (slurp (str (fs/path meta new-transcript))) "quarry" "harbour"))
      (spit (str (fs/path local "search" ".index.lock")) (str (.pid (java.lang.ProcessHandle/current))))
      (with-redefs [status/refresh-after-s 0]
        (is (= 0 (:count (run "search" "harbour")))))
      (testing "and a lock left by a process that's gone is taken over"
        (spit (str (fs/path local "search" ".index.lock")) "999999999")
        (with-redefs [status/refresh-after-s 0]
          (is (= 1 (:count (run "search" "harbour")))))
        (is (not (fs/exists? (fs/path local "search" ".index.lock"))))))
    (testing "BLACKBOX_REFRESH=never leaves it alone"
      (let [local2 (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
            out (read-string (:out (s/cli* {} ["--meta" meta "--local" local2 "--format" "edn" "search" "marble"])))]
        (is (= "no-index" (-> out :error :code)))))))

(deftest index-reports-progress-on-stderr
  (let [err (StringWriter.)
        local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        {:keys [exit out]} (binding [*err* err]
                             (s/cli* {} ["--meta" s/fixture-meta "--local" local "index" "--progress" "--format" "json"]))
        lines (str/split-lines (str err))]
    (is (= 0 exit))
    (is (str/starts-with? out "{") "stdout is still the one answer")
    (is (= "{\"stage\":\"transcripts\",\"state\":\"start\"}" (first lines)))
    (is (= "{\"stage\":\"shots\",\"state\":\"done\"}" (last lines)))
    (is (= 12 (count lines)))))
