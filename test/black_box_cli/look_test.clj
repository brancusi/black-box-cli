(ns black-box-cli.look-test
  "Frames from proxies: a tiny proxy made by ffmpeg in a copy of the fixture media_meta (never a real one). Skipped
  where ffmpeg isn't installed."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private ffmpeg (tools/find-tool (into {} (System/getenv)) "ffmpeg"))

(defn- meta-with-proxy
  "A copy of the fixture media_meta whose C5274.MP4 has a real proxy: 33 s of a test pattern, 320×180."
  []
  (let [meta (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")
        proxy (fs/path meta "proxies/media/2026/09/16/src/fx3/C5274.MP4.proxy.mp4")]
    (fs/copy-tree s/fixture-meta meta)
    (fs/delete proxy)
    (process/sh ffmpeg "-v" "error" "-f" "lavfi" "-i" "testsrc=size=320x180:rate=24000/1001:duration=33"
                "-c:v" "mpeg4" "-q:v" "5" (str proxy))
    (str meta)))

(defn- runner [meta]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [& args]
              (let [res (s/cli* {:home home} (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                (assoc res :edn (read-string (:out res)))))]
    (run "index")
    {:local local :run run}))

(defn- width-of [jpeg]
  (parse-long (str/trim (:out (process/sh (str (fs/path (fs/parent ffmpeg) "ffprobe")) "-v" "error" "-select_streams"
                                          "v:0" "-show_entries" "stream=width" "-of" "csv=p=0" jpeg)))))

(deftest a-line-or-a-moment-as-a-jpeg
  (when ffmpeg
    (let [{:keys [local run]} (runner (meta-with-proxy))
          {:keys [exit edn]} (run "frame" "r54qb74c.3")
          jpeg (str/replace (:jpeg edn) #"^<local>" local)]
      (is (= 0 exit))
      (is (= {:id "r54qb74c.3" :file "C5274.MP4" :rel "media/2026/09/16/src/fx3/C5274.MP4" :at "0:22.2"
              :tc "03:07:13:12" :faces "no face on screen"
              :shot {:id "r54qb74c.s2" :at "0:20.5" :end "0:32.5" :framing "close" :camera "static"
                     :action "Hands guide a hammer drill into a block of marble." :best "0:26.5-0:29.5"
                     :issues "none"}}
             (dissoc edn :jpeg :help)))
      (is (str/starts-with? jpeg (str local "/frames/r54qb74c-")))
      (is (= 320 (width-of jpeg)) "never wider than the proxy")
      (testing "made once: the same frame again is the same file"
        (let [before (fs/last-modified-time jpeg)]
          (is (= (:jpeg edn) (:jpeg (:edn (run "frame" "r54qb74c.3")))))
          (is (= before (fs/last-modified-time jpeg)))))
      (testing "a file at a moment, at a width"
        (let [{:keys [edn]} (run "frame" "C5274.MP4" "--at" "0:05" "--width" "160")]
          (is (= "0:05.0" (:at edn)))
          (is (= 160 (width-of (:jpeg edn))))))
      (testing "a shot id: the middle of its best moment"
        (is (= ["r54qb74c.s2" "0:28.0"] ((juxt :id :at) (:edn (run "frame" "r54qb74c.s2"))))))
      (testing "what can't be shown says why"
        (is (= "bad-time" (-> (run "frame" "C5274.MP4" "--at" "40") :edn :error :code)))
        (is (= 2 (:exit (run "frame" "r54qb74c.3" "--at" "1"))))
        (is (= "no-proxy" (-> (run "frame" "2npshqu5.1") :edn :error :code)))
        (with-redefs [tools/find-tool (constantly nil)]
          (is (= "no-ffmpeg" (-> (run "frame" "r54qb74c.3" "--width" "200") :edn :error :code))))))))

(deftest a-cut-page-shows-its-clips
  (when ffmpeg
    (let [meta (meta-with-proxy)
          {:keys [local]} (runner meta)
          res (s/cli* {:stdin "title: Pictures\nr54qb74c.1..r54qb74c.2\n09/16/audio/DJI_01.WAV 5-9.5\n"}
                      ["--meta" meta "--local" local "edl" "new" "-"])
          page (slurp (str (first (fs/glob (fs/path local "cuts") "*/cut.html"))))]
      (is (= 0 (:exit res)))
      (is (= 3 (count (re-seq #"<img src=\"file:///" page))) "in, middle and out of the clip with a proxy")
      (is (= 3 (count (re-seq #"no picture" page))) "grey boxes for the clip without one"))))
