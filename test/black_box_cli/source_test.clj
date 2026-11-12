(ns black-box-cli.source-test
  "`source` hands pictures to Black Box (faked here by a script that logs its arguments and prints an answer) and
  shows what it found, with the picture index's frame id for each hit. Nothing reads an original."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.ids :as ids]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private rel "media/2026/09/16/src/fx3/C5274.MP4")

(defn- fake-black-box
  "A stand-in for `BlackBox --cli source`: logs its arguments, prints `answer`, exits `exit`."
  [answer exit]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-app-"})
        f (str (fs/path dir "BlackBox"))]
    (spit (str (fs/path dir "answer")) (str answer "\n"))
    (spit f (str "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/calls\"\n"
                 "cat \"$(dirname \"$0\")/answer\"\nexit " exit "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:app f :dir (str dir)}))

(defn- calls [dir]
  (let [f (fs/path dir "calls")] (if (fs/exists? f) (str/split-lines (slurp (str f))) [])))

(defn- runner []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        pic (str (fs/path home "copy.png"))
        run (fn [app & args]
              (let [res (s/cli* {:home home :env {"BLACKBOX_APP" app "PATH" ""}}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    (spit pic "not really a picture: the fake Black Box never reads it")
    (run "/nowhere/BlackBox" "index")
    {:pic pic :run run}))

(defn- hit [m]
  (merge {:original "/VideoSource/2026/09/16/src/fx3/C5274.MP4" :rel rel :kind "video" :at "0:12.0" :t 12.0
          :diff 0.02 :points 412 :similarity 0.912 :proxy "~/media_meta/proxies/C5274.MP4.proxy.mp4"}
         m))

(deftest a-copy-is-found-and-named-by-its-frame
  (let [{:keys [pic run]} (runner)
        {:keys [app dir]} (fake-black-box (json/generate-string
                                           {:query "x" :size [320 180] :checked 60 :seconds 6.5
                                            :matches [(hit {:match "best"})]
                                            :similar [(hit {:match nil :diff 0.43 :points 31 :similarity 0.81})]})
                                          0)
        out (run app "source" pic)]
    (is (= 0 (:exit out)))
    (testing "Black Box is asked with the picture's full path and the options"
      (is (= [(str "--cli source " pic " --candidates 60 --max-diff 0.1 --format json")] (calls dir))))
    (is (= [{:picture pic :size "320x180" :checked 60 :found 1 :best "C5274.MP4" :seconds 6.5}] (:pictures out)))
    (let [[m] (:matches out)]
      (is (= "best" (:match m)))
      (is (str/starts-with? (:id m) (str (ids/clip rel) ".f")) "the picture index's frame nearest the hit")
      (is (= ["C5274.MP4" "0:12.0" "/VideoSource/2026/09/16/src/fx3/C5274.MP4"] ((juxt :file :at :original) m)))
      (is (not (contains? m :proxy)) "small default fields")
      (is (not (contains? m :picture)) "one picture: no column to repeat it"))
    (is (= 0.81 (:similarity (first (:similar out)))))
    (is (some #(str/includes? % "still new") (:help out)))))

(deftest nothing-confirmed-says-so
  (let [{:keys [pic run]} (runner)
        {:keys [app]} (fake-black-box (json/generate-string {:query "x" :size [10 10] :checked 60 :seconds 6.0
                                                             :matches [] :similar []})
                                      0)
        out (run app "source" pic)]
    (is (= "none confirmed" (:matches out)))
    (is (= "none" (:similar out)))
    (is (some #(str/includes? % "Nothing was confirmed") (:help out)))))

(deftest several-pictures-come-back-in-order
  (let [{:keys [pic run]} (runner)
        pic2 (str (fs/path (fs/parent pic) "two.png"))
        _ (spit pic2 "x")
        one (fn [q] {:query q :size [1 1] :checked 3 :seconds 1.0 :matches [] :similar []})
        {:keys [app]} (fake-black-box (json/generate-string {:results [(one pic) (one pic2)]}) 0)
        out (run app "source" pic pic2)]
    (is (= [pic pic2] (map :picture (:pictures out))))))

(deftest several-pictures-name-each-hit
  (let [{:keys [pic run]} (runner)
        pic2 (str (fs/path (fs/parent pic) "two.png"))
        _ (spit pic2 "x")
        one (fn [q] {:query q :size [1 1] :checked 3 :seconds 1.0 :matches [(hit {:match "best"})] :similar []})
        {:keys [app]} (fake-black-box (json/generate-string {:results [(one "a") (one "b")]}) 0)]
    (is (= [pic pic2] (map :picture (:matches (run app "source" pic pic2)))))))

(deftest what-is-refused
  (let [{:keys [pic run]} (runner)
        {:keys [app dir]} (fake-black-box "{}" 0)]
    (testing "a picture on the NAS is never handed over"
      (let [out (run app "source" "/Volumes/Media/a.jpg" "/nowhere/b.png")]
        (is (= "bad-picture" (-> out :error :code)))
        (is (= 2 (count (-> out :error :problems))))
        (is (empty? (calls dir)))))
    (testing "an older Black Box"
      (let [{:keys [app]} (fake-black-box "error: invalid choice: 'source'" 2)]
        (is (= "black-box-too-old" (-> (run app "source" pic) :error :code)))))
    (testing "Black Box's own errors pass through"
      (let [{:keys [app]} (fake-black-box (json/generate-string {:error {:code "no-index" :message "the picture index is empty"}}) 1)
            out (run app "source" pic)]
        (is (= "no-index" (-> out :error :code)))
        (is (str/includes? (-> out :error :message) "the picture index is empty"))))
    (testing "options"
      (is (= 2 (:exit (run app "source" pic "--max-diff" "2")))))))
