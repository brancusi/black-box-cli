(ns black-box-cli.edl-test
  "Cuts beyond the goldens: a cut list's forms and problems, a cut folder never overwritten, a changed line refused,
  --play (mpv is never started: spawn! is replaced), and Black Box's old cuts read but never written."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- indexed
  "A new --local folder with the fixture indexed, and a runner for it: (run stdin & args) → {:exit :out}."
  []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [stdin & args]
              (s/cli* {:stdin stdin :home home :env {"PATH" ""}} (concat ["--meta" s/fixture-meta "--local" local] args)))]
    (run nil "index")
    {:local local :home home :run run}))

(defn- edn-out [{:keys [out]}] (read-string out))

(deftest cut-lists-read-both-forms
  (is (= {:title "T"
          :items [{:n 2 :kind :lines :a "r54qb74c.1" :b "r54qb74c.2" :note "why"}
                  {:n 3 :kind :file :file "C5274.MP4" :in "0:20" :out "26.6" :note ""}]
          :problems []}
         (cutlist/parse "title: T\nr54qb74c.1..r54qb74c.2 # why\nC5274.MP4 0:20-26.6\n# a comment\n")))
  (is (= [{:n 0 :kind :lines :a "r54qb74c.1" :b "r54qb74c.1" :note "" :text "Think"}]
         (:items (cutlist/parse "{\"title\": \"J\", \"clips\": [{\"from\": \"r54qb74c.1\", \"text\": \"Think\"}]}"))))
  (testing "problems name their line; old row ids aren't line ids"
    (is (= ["line 1: 'c12345' isn't a clip (a line id, id..id, or <file> <in>-<out>)"]
           (:problems (cutlist/parse "c12345\n"))))
    (is (= ["no clips"] (:problems (cutlist/parse "title: only\n"))))
    (is (str/starts-with? (first (:problems (cutlist/parse "{\"clips\": [{\"file\": \"x\"}]}")))
                          "not a cut list in JSON: "))))

(deftest times-read-like-the-old-cli
  (is (= 83.5 (cutlist/parse-time "83.5")))
  (is (= 83.5 (cutlist/parse-time "1:23.5")))
  (is (= 3683.5 (cutlist/parse-time "1:01:23.5")))
  (doseq [bad ["" "1:" "a" "1:2:3:4" "1e3"]]
    (testing bad
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't a time" (cutlist/parse-time bad))))))

(deftest a-cut-folder-is-never-overwritten
  (let [{:keys [local run]} (indexed)
        cutlist "title: Same\nr54qb74c.1\n"]
    (is (= 0 (:exit (run cutlist "edl" "new" "-"))))
    (is (= 0 (:exit (run cutlist "edl" "new" "-"))))
    (testing "two cuts of one title make two folders, even in the same second"
      (let [folders (sort (map (comp str fs/file-name) (fs/list-dir (fs/path local "cuts"))))]
        (is (= 2 (count folders)))
        (is (every? #(re-matches #"\d{8}T\d{6}Z-same(-2)?" %) folders))))
    (testing "a file that exists is never written over"
      (let [folder (first (fs/list-dir (fs/path local "cuts")))
            before (slurp (str (fs/path folder "cut.json")))]
        (is (thrown? java.nio.file.FileAlreadyExistsException
                     (#'cuts/write-new! (fs/path folder "cut.json") "{}")))
        (is (= before (slurp (str (fs/path folder "cut.json")))))))))

(deftest a-changed-line-is-refused
  (let [{:keys [local run]} (indexed)
        clip (fn [text] (json/generate-string {:title "T" :clips [{:from "r54qb74c.1" :to "r54qb74c.2" :text text}]}))]
    (testing "the text the agent saw, spaced any way, is still what the lines say"
      (is (= 0 (:exit (run (clip " Think you should go for your bag.\n It's safer. You know? ") "edl" "new" "-" "--dry-run")))))
    (let [res (run (clip "Think you should go for your bag.") "edl" "new" "-" "--format" "edn")
          {:keys [error]} (edn-out res)]
      (is (= 1 (:exit res)))
      (is (= "invalid-cutlist" (:code error)))
      (is (= ["clip 1: the text of r54qb74c.1..r54qb74c.2 has changed since the cut list was made; it now says \"Think you should go for your bag. It's safer. You know?\""]
             (:problems error)))
      (is (not (fs/exists? (fs/path local "cuts")))))))

(deftest lines-and-files-are-checked
  (let [{:keys [run]} (indexed)
        problems (fn [text] (-> (run text "edl" "new" "-" "--title" "T" "--format" "edn") edn-out :error :problems))]
    (is (= ["clip 1 (line 1): r54qb74c.2 comes after r54qb74c.1"] (problems "r54qb74c.2..r54qb74c.1")))
    (is (= ["clip 1 (line 1): 'r54qb74c.01' isn't a line id (like k7f3q2ab.41)"] (problems "r54qb74c.01")))
    (is (= ["clip 1 (line 1): in 40 is past the end of C5274.MP4 (0:32.5)"] (problems "C5274.MP4 40-41")))
    (is (= ["no title: add `title: …` to the cut list, or --title"]
           (-> (run "r54qb74c.1" "edl" "new" "-" "--format" "edn") edn-out :error :problems)))
    (testing "a cut list from a file, or a missing one"
      (let [f (str (fs/create-temp-file {:suffix ".txt"}))]
        (spit f "title: From a file\nr54qb74c.3\n")
        (is (= 0 (:exit (run nil "edl" "new" f "--dry-run")))))
      (is (= "not-found" (-> (run nil "edl" "new" "/nowhere/cut.txt" "--format" "edn") edn-out :error :code))))))

(deftest unrendered-mpv-plays-the-edl
  (let [{:keys [local home]} (indexed)
        bin (fs/create-temp-dir {:prefix "bbcli-bin-"})
        mpv (str (fs/path bin "mpv"))
        started (atom [])
        run (fn [path & args]
              (with-redefs [tools/spawn! #(swap! started conj (vec %))]
                (s/cli* {:stdin "title: Play\nr54qb74c.1\n" :home home :env {"PATH" path}}
                        (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))))]
    (spit mpv "#!/bin/sh\n")
    (fs/set-posix-file-permissions mpv "rwxr-xr-x")
    (let [{:keys [playing id help]} (edn-out (run (str bin) "edl" "new" "-" "--play"))
          folder (str (fs/path local "cuts" id))]
      (is (str/starts-with? playing "yes, in mpv from cut.mpv.edl (not rendered: ") "the fixture's proxy isn't a video")
      (is (= [[mpv "--autofit=70%" "--force-window=immediate" "--keep-open=yes"
               (str "--sub-file=" folder "/cut.srt") (str folder "/cut.mpv.edl")]]
             @started))
      (is (str/starts-with? (first help) "It's playing in mpv"))
      (testing "edl show --play too"
        (is (str/starts-with? (:playing (edn-out (run (str bin) "edl" "show" id "--play"))) "yes, in mpv from"))
        (is (= 2 (count @started)))))
    (testing "--dry-run never plays"
      (is (nil? (:playing (edn-out (run (str bin) "edl" "new" "-" "--play" "--dry-run")))))
      (is (= 2 (count @started))))))

(deftest old-cuts-are-listed-and-never-written
  (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        old (fs/path home "Library" "Application Support" "Media Utilities" "media-mgmt" "cuts")
        ours (fs/path home "Library" "Application Support" "black-box-cli" "cuts")
        run (fn [stdin & args] (s/cli* {:stdin stdin :home home} (concat ["--meta" s/fixture-meta "--format" "edn"] args)))]
    (fs/create-dirs (fs/path old "20261001T000000Z-before"))
    (spit (str (fs/path old "20261001T000000Z-before" "cut.json"))
          (json/generate-string {:title "Before" :made_at "2026-10-01T00:00:00.000Z" :length_s 3.0
                                 :clips [{:source_name "A.MP4" :start 0.0 :end 3.0 :source_in "00:00:00:00"
                                          :source_out "00:00:03:00" :text "x"}]}))
    (run nil "index")
    (run "title: After\nr54qb74c.1\n" "edl" "new" "-")
    (let [{:keys [total edls]} (edn-out (run nil "edl" "list" "--fields" "id,title,folder"))]
      (is (= 2 total))
      (is (= ["After" "Before"] (map :title edls)))
      (is (str/starts-with? (:folder (first edls)) "~/Library/Application Support/black-box-cli/cuts/")))
    (is (= ["20261001T000000Z-before"] (map (comp str fs/file-name) (fs/list-dir old))))
    (is (= 1 (count (fs/list-dir ours))))
    (testing "edl show finds either, by part of its id"
      (is (= "Before" (:title (edn-out (run nil "edl" "show" "before")))))
      (is (= "ambiguous" (-> (run nil "edl" "show" "Z-") edn-out :error :code))))))
