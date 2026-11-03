(ns black-box-cli.stills-test
  "`still new` names moments as `frame` does and hands them to Black Box (faked here by a script that logs its
  arguments and what it was sent, and can bring files back); `still show` reads the folder and Black Box's status.
  Nothing reads an original."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.ids :as ids]
   [black-box-cli.stills :as stills]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private frame-id (str (ids/clip "media/2026/09/16/src/fx3/C5274.MP4") ".f3"))

(defn- fake-black-box
  "A stand-in for Black Box's `still` commands: `request` logs what it was sent and prints `request-out`; `status`
  prints `status-out`; each exits `exit`."
  [{:keys [request-out status-out exit] :or {exit 0}}]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-app-"})
        f (str (fs/path dir "BlackBox"))]
    (spit (str (fs/path dir "request")) (str request-out "\n"))
    (spit (str (fs/path dir "status")) (str status-out "\n"))
    (spit f (str "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/calls\"\n"
                 "[ \"$3\" = request ] && cat > \"$(dirname \"$0\")/sent\"\n"
                 "cat \"$(dirname \"$0\")/$3\"\nexit " exit "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:app f :dir (str dir)}))

(defn- calls [dir]
  (let [f (fs/path dir "calls")] (if (fs/exists? f) (str/split-lines (slurp (str f))) [])))

(defn- runner []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [app & args]
              (let [res (s/cli* {:home home :env {"BLACKBOX_APP" app "PATH" ""}}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    (run "/nowhere/BlackBox" "index")
    {:local local :run run}))

(deftest the-forms-of-a-moment
  (is (= {:what "C5274.MP4" :at "40" :name "unloading"} (stills/parse-what "C5274.MP4@40=unloading")))
  (is (= {:what "r54qb74c.1" :at nil :name nil} (stills/parse-what "r54qb74c.1")))
  (is (= {:what "IMG 1.MOV" :at "1:02.5" :name nil} (stills/parse-what " IMG 1.MOV@1:02.5 ")))
  (testing "a balance, chosen, never guessed"
    (is (= {:what "C5274.MP4" :at "40" :name "u" :balance {:white [0.6 0.5625]}}
           (stills/parse-what "C5274.MP4@40=u~white=0.6,0.5625")))
    (is (= {:what "r54qb74c.f3" :at nil :name nil :balance {:gains [0.7 0.92 2.71]}}
           (stills/parse-what "r54qb74c.f3~gains=0.7,0.92,2.71")))
    (is (:bad-balance (stills/parse-what "C5274.MP4@40~white=1.2,0.5")) "off the frame")
    (is (:bad-balance (stills/parse-what "C5274.MP4@40~gains=0.7,9,1")) "past 4")
    (is (:bad-balance (stills/parse-what "C5274.MP4@40~gains=0.7,1")) "two gains")))

(deftest stills-are-asked-for-and-come-back
  (let [{:keys [local run]} (runner)
        {:keys [app dir]} (fake-black-box {:request-out (json/generate-string {:request "s-1" :state "queued"})
                                           :status-out (json/generate-string
                                                        {:state "rendering" :renderer "ffmpeg 9.0.2"
                                                         :items [{:n 1 :state "fetching"}
                                                                 {:n 2 :state "done" :width 3840 :height 2160
                                                                  :camera "ILME-FX3" :input "slog3-sgamut3cine"
                                                                  :look_version "neutral/1" :t_frame 21.4797
                                                                  :flags ["not_camera_original"]}]})})
        res (run app "still" "new" frame-id "C5274.MP4@21.5=quote" "--tiff")
        folder (str (fs/path local "stills" (:id res)))]
    (is (= 0 (:exit res)))
    (is (str/ends-with? (:id res) "-neutral") "the default look")
    (is (= ["s-1" "queued"] ((juxt :request :state) res)))
    (is (= [[1 "C5274_10.010s" "C5274.MP4" "0:10.0"] [2 "quote" "C5274.MP4" "0:21.5"]]
           (map (juxt :n :name :file :at) (:stills res))))
    (testing "Black Box is told where to put them, and sent the originals by sha256"
      (is (= [(str "--cli still request --out " folder " --format json")] (calls dir)))
      (let [sent (json/parse-string (slurp (str (fs/path dir "sent"))) true)]
        (is (= ["black-box-cli.still/1" "neutral" ["jpeg" "tiff16"]] ((juxt :schema :look :formats) sent)))
        (is (= [{:n 1 :rel "media/2026/09/16/src/fx3/C5274.MP4" :sha256 (apply str (repeat 64 "a")) :t 10.01
                 :name "C5274_10.010s"}
                {:n 2 :rel "media/2026/09/16/src/fx3/C5274.MP4" :sha256 (apply str (repeat 64 "a")) :t 21.5
                 :name "quote"}]
               (:items sent)))))
    (testing "request.json keeps the request"
      (is (= "s-1" (:request (json/parse-string (slurp (str (fs/path folder "request.json"))) true)))))
    (testing "show: Black Box's state, and the files that have landed"
      (spit (str (fs/path folder "quote.jpg")) "jpeg")
      (spit (str (fs/path folder "quote.tif")) "tiff")
      (spit (str (fs/path folder "C5274_10.010s.jpg")) "jpeg, its tiff still coming")
      (let [shown (run app "still" "show" (:id res))]
        (is (= ["rendering" "1 of 2" "neutral/1" "ffmpeg 9.0.2"] ((juxt :state :done :look_version :renderer) shown)))
        (is (= [["C5274_10.010s" "fetching" "" "" "" (str folder "/C5274_10.010s.jpg")]
                ["quote" "done" "3840x2160" "ILME-FX3" "not_camera_original"
                 (str folder "/quote.jpg, " folder "/quote.tif")]]
               (map (juxt :name :state :size :camera :flags :paths) (:stills shown))))
        (is (some #(str/includes? % "not_camera_original") (:help shown)))
        (is (= "--cli still status s-1 --format json" (last (calls dir))))))
    (testing "list"
      (is (= [[(:id res) "neutral" 2]] (map (juxt :id :look :stills) (:requests (run app "still" "list"))))))))

(deftest a-filmic-still-with-a-white-point
  (let [{:keys [run]} (runner)
        {:keys [app dir]} (fake-black-box {:request-out (json/generate-string {:request "s-2" :state "queued"})
                                           :status-out (json/generate-string
                                                        {:state "done" :items [{:n 1 :state "done"
                                                                                :look_version "filmic/1"
                                                                                :balance {:gains [0.7 0.92 2.71]}}
                                                                               {:n 2 :state "done"}]})})
        res (run app "still" "new" "C5274.MP4@20=crate~white=0.604,0.5625" "C5274.MP4@21=plain" "--look" "filmic")]
    (is (str/ends-with? (:id res) "-filmic"))
    (testing "the balance goes with its still only"
      (is (= [{:white [0.604 0.5625]} nil]
             (map :balance (:items (json/parse-string (slurp (str (fs/path dir "sent"))) true))))))
    (testing "show reports the gains used, to reuse as ~gains"
      (is (= ["0.700 0.920 2.710" ""] (map :balance (:stills (run app "still" "show" (:id res)))))))))

(deftest nothing-is-sent-when-a-moment-is-wrong
  (let [{:keys [local run]} (runner)
        {:keys [app dir]} (fake-black-box {:request-out "{\"request\": \"s-1\"}"})]
    (let [res (run app "still" "new" "C5274.MP4@999" "nosuchfile.MOV" "r54qb74c.1@3" "C5274.MP4@1=a" "C5274.MP4@2=a")]
      (is (= "invalid-stills" (-> res :error :code)))
      (is (= 4 (count (-> res :error :problems))) "past the end, no such file, a time on a line id, two named a")
      (is (empty? (calls dir))))
    (is (= 2 (:exit (run app "still" "new" "r54qb74c.1" "--look" "dramatic"))) "an unknown look is a usage error")
    (testing "a balance that's wrong, or with the log look, sends nothing"
      (is (= "invalid-stills" (-> (run app "still" "new" "C5274.MP4@1~gains=5,1,1") :error :code)))
      (is (= ["a balance can't go with --look log, which is the original ungraded"]
             (-> (run app "still" "new" "C5274.MP4@1~gains=1,1,1.2" "--look" "log") :error :problems)))
      (is (empty? (calls dir))))
    (testing "Black Box can't take it: its error, and no folder left behind"
      (let [{bad :app} (fake-black-box {:exit 1 :request-out (json/generate-string
                                                              {:error {:code "app_not_running"
                                                                       :message "Black Box isn't running"}})})
            res (run bad "still" "new" "r54qb74c.1")]
        (is (= "app_not_running" (-> res :error :code)))
        (is (empty? (fs/list-dir (fs/path local "stills"))))))))

(defn- rendering-black-box
  "A stand-in that renders `names` on its second `still status`: until then they're rendering, then each
  `<name>.jpg` is written (via .part) into the folder `still request` was given."
  [names]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-app-"})
        f (str (fs/path dir "BlackBox"))
        d (str dir)]
    (spit f (str "#!/bin/sh\nD='" d "'\n"
                 "case \"$3\" in\n"
                 "  request) echo \"$5\" > \"$D/out\"; cat > /dev/null; echo '{\"request\": \"s-9\", \"state\": \"queued\"}' ;;\n"
                 "  status) n=$(( $(cat \"$D/count\" 2>/dev/null || echo 0) + 1 )); echo $n > \"$D/count\"\n"
                 "    if [ $n -ge 2 ]; then\n"
                 (apply str (for [nm names]
                              (str "      printf 'jpeg " nm "' > \"$(cat \"$D/out\")/" nm ".jpg.part\"; "
                                   "mv \"$(cat \"$D/out\")/" nm ".jpg.part\" \"$(cat \"$D/out\")/" nm ".jpg\"\n")))
                 "      echo '{\"state\": \"done\"}'\n"
                 "    else echo '{\"state\": \"rendering\"}'; fi ;;\n"
                 "esac\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:app f :dir d}))

(deftest wait-until-done-and-copy-out
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        inbox (str (fs/path home "site" "inbox"))
        {:keys [app dir]} (rendering-black-box ["hero" "quote"])
        run (fn [& args]
              (let [res (s/cli* {:home home :env {"BLACKBOX_APP" app "PATH" "" "BLACKBOX_STILL_POLL_S" "0.05"}}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))
        _ (run "index")
        res (run "still" "new" (str frame-id "=hero") "C5274.MP4@21.5=quote" "--out" "~/site/inbox")]
    (is (= "~/site/inbox" (:out res)))
    (is (str/includes? (first (:help res)) "--wait"))
    (testing "without --wait: still rendering, nothing copied yet"
      (let [shown (run "still" "show" (:id res))]
        (is (= ["rendering" "0 of 2"] ((juxt :state :done) shown)))
        (is (not (contains? shown :waited)))
        (is (= ["" ""] (map :copied (:stills shown))))))
    (testing "--wait asks again until every still is done, and copies them into --out"
      (let [shown (run "still" "show" (:id res) "--wait")]
        (is (= ["done" "2 of 2"] ((juxt :state :done) shown)))
        (is (str/ends-with? (:waited shown) "s"))
        (is (not (str/includes? (:waited shown) "gave up")))
        (is (= ["~/site/inbox/hero.jpg" "~/site/inbox/quote.jpg"] (map :copied (:stills shown))))
        (is (= "jpeg quote" (slurp (str (fs/path inbox "quote.jpg")))))
        (is (empty? (fs/glob inbox "*.part")))))
    (testing "showing again copies nothing twice, and never writes over a different file"
      (spit (str (fs/path inbox "quote.jpg")) "someone else's")
      (let [rows (:stills (run "still" "show" (:id res)))]
        (is (= "~/site/inbox/hero.jpg" (:copied (first rows))))
        (is (= "quote.jpg is already in --out and differs: not copied" (:copied (second rows))))
        (is (= "someone else's" (slurp (str (fs/path inbox "quote.jpg")))))))
    (is (= 2 (:exit (run "still" "new" frame-id "--out" s/fixture-meta))) "never into media_meta")
    (is (fs/exists? (fs/path dir "count")))))

(deftest wait-gives-up-at-the-timeout
  (let [{:keys [run]} (runner)
        {:keys [app]} (fake-black-box {:request-out (json/generate-string {:request "s-1" :state "queued"})
                                       :status-out (json/generate-string {:state "rendering"})})
        id (:id (run app "still" "new" frame-id))
        shown (run app "still" "show" id "--wait" "--timeout" "0")]
    (is (= "rendering" (:state shown)))
    (is (str/includes? (:waited shown) "gave up after 0m"))
    (is (str/includes? (first (:help shown)) "--wait"))))
