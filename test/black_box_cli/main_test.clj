(ns black-box-cli.main-test
  (:require
   [babashka.fs :as fs]
   [black-box-cli.flags :as flags]
   [black-box-cli.main :as main]
   [black-box-cli.support :as s]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest version-is-bare
  (doseq [flag ["-v" "-V" "--version"]]
    (let [{:keys [exit out]} (s/cli* {} [flag])]
      (is (= 0 exit))
      (is (re-matches #"\d+\.\d+\.\d+\n" out)))))

(deftest home-view-names-itself-and-media-meta
  (let [{:keys [exit out]} (s/cli)]
    (is (= 0 exit))
    (is (re-find #"^bin: ~/.local/bin/blackbox\nversion: \d+\.\d+\.\d+\ndescription: " out))
    (is (str/includes? out (str "media_meta: " s/fixture-meta))))
  (testing "no media_meta anywhere: said plainly, with the fix"
    (let [{:keys [exit out]} (s/cli* {} [])]
      (is (= 0 exit))
      (is (str/includes? out "media_meta: not found"))
      (is (str/includes? out "Pass --meta <the media_meta folder>")))))

(deftest usage-errors-exit-2-on-stdout
  (let [{:keys [exit out]} (s/cli "nonsense")]
    (is (= 2 exit))
    (is (str/starts-with? out "error:\n  code: usage\n  message: unknown command nonsense\nhelp[2]:")))
  (let [{:keys [exit out]} (s/cli* {} ["--format" "yaml"])]
    (is (= 2 exit))
    (is (str/includes? out "--format yaml: use toon, json or edn"))))

(deftest every-command-is-built
  (is (= (set (map :path flags/commands)) (set (keys @#'main/handlers)))))

(deftest index-needs-media-meta
  (let [{:keys [exit out]} (s/cli* {} ["index"])]
    (is (= 1 exit))
    (is (str/starts-with? out "error:\n  code: no-meta\n"))))

(deftest the-default-places-are-this-macs-own
  (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        {:keys [exit out]} (s/cli* {:home home} ["--meta" s/fixture-meta "index"])]
    (is (= 0 exit))
    (is (str/starts-with? out "index: ~/Library/Caches/black-box-cli/search/transcripts.sqlite\n"))))

(deftest a-file-is-found-by-its-clip-id
  (testing "the clip id the review page copies stands for the file, as its name does"
    (let [local (:local (s/cli "index"))
          run #(:out (s/cli* {} (concat ["--meta" s/fixture-meta "--local" local "transcript"] %&)))
          by-name (run "C5274.MP4" "--limit" "2")
          by-clip (run "r54qb74c" "--limit" "2")]
      (is (str/starts-with? by-clip "file:"))
      (is (= (first (str/split by-name #"help\[")) (first (str/split by-clip #"help\["))) "the same file and lines"))))
