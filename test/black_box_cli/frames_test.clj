(ns black-box-cli.frames-test
  "Picture search on the fixture's invented frames (four numbers a vector): the index is a cache like the
  transcripts', ids come from media_meta, every frame is scored exactly, and Black Box (faked here by a script)
  only turns words into a vector."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private label-dir "embeddings/google/siglip2-base-patch16-256-5s")

(defn- copy-meta []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- fake-black-box
  "A stand-in for Black Box's `embed query`: always the same vector, with this label and model."
  ([] (fake-black-box "google/siglip2-base-patch16-256-5s" "google/siglip2-base-patch16-256"))
  ([label model]
   (let [f (str (fs/path (fs/create-temp-dir {:prefix "bbcli-app-"}) "BlackBox"))]
     (spit f (str "#!/bin/sh\necho '{\"model\": \"" model "\", \"revision\": \"x\", \"label\": \"" label
                  "\", \"dim\": 4, \"vector\": [0.6, 0.8, 0.0, 0.0]}'\n"))
     (fs/set-posix-file-permissions f "rwxr-xr-x")
     f)))

(defn- runner
  ([] (runner s/fixture-meta))
  ([meta]
   (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
         home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
         app (fake-black-box)
         run (fn [& args]
               (let [[env args] (if (map? (first args)) [(first args) (rest args)] [{} args])
                     res (s/cli* {:home home :env (merge {"BLACKBOX_APP" app} env)}
                                 (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                 (assoc res :edn (read-string (:out res)))))]
     {:local local :run run})))

(defn- ids-of [res] (map :id (:frames (:edn res))))

(deftest the-picture-index-is-a-cache
  (let [meta (copy-meta)
        {:keys [local run]} (runner meta)
        search (fs/path local "search")
        bins #(sort (map (comp str fs/file-name) (fs/glob search "frames-*.f32")))]
    (is (= {:added 3 :changed 0 :unchanged 0 :removed 0 :skipped 0 :total 7} (:frames (:edn (run "index")))))
    (is (= {:added 0 :changed 0 :unchanged 3 :removed 0 :skipped 0 :total 7} (:frames (:edn (run "index")))))
    (is (= 1 (count (bins))))
    (is (= 3 (count (fs/list-dir (fs/path search "vectors")))))
    (testing "a changed frames file comes through, and only what's current is kept"
      (let [f (str (fs/path meta label-dir "media/2026/10/03/src/a7s/C5900.MP4.frames.json"))
            before (bins)]
        (spit f (str/replace (slurp f) "\"t\": 5.0" "\"t\": 5.5"))
        (is (= 1 (:changed (:frames (:edn (run "index"))))))
        (is (= 1 (count (bins))))
        (is (= 3 (count (fs/list-dir (fs/path search "vectors")))))
        (testing "a new vectors file replaces the old one"
          (is (not= before (bins))))))
    (testing "a frames file gone from media_meta leaves the index"
      (fs/delete (fs/path meta label-dir "media/2026/09/16/src/fx3/C5280.MP4.frames.json"))
      (is (= {:added 0 :changed 0 :unchanged 2 :removed 1 :skipped 0 :total 5} (:frames (:edn (run "index")))))
      (is (= 2 (count (fs/list-dir (fs/path search "vectors"))))))
    (testing "a rebuild gives the same vectors file"
      (let [before (bins)]
        (run "index" "--rebuild")
        (is (= before (bins)))))))

(deftest look-scores-every-frame
  (let [{:keys [run]} (runner)]
    (run "index")
    (let [res (run "look" "a" "test")]
      (is (= 0 (:exit res)))
      (is (= ["sqlknzuz.f1" "r54qb74c.f2" "luyk4sl6.f2"] (ids-of res)) "best first, one per clip")
      (is (= {:id "r54qb74c.f2" :day "2026-09-16" :file "C5274.MP4" :at "0:05.0" :tc "03:06:56:08" :score 0.96}
             (second (:frames (:edn res)))))
      (is (= 7 (:frames_searched (:edn res)))))
    (is (= ["sqlknzuz.f1" "r54qb74c.f2" "r54qb74c.f3" "r54qb74c.f1"] (ids-of (run "look" "x" "--per-clip" "0" "--limit" "4"))))
    (is (= ["luyk4sl6.f2"] (ids-of (run "look" "x" "--day" "2026-10-03"))))
    (is (= "no frames in scope" (:frames (:edn (run "look" "x" "--month" "2025-01")))))
    (testing "Black Box missing, or a query from another model"
      (is (= "no-black-box" (-> (run {"BLACKBOX_APP" "/nowhere/BlackBox"} "look" "x") :edn :error :code)))
      (is (= "model-mismatch" (-> (run {"BLACKBOX_APP" (fake-black-box "other/model-5s" "other/model")} "look" "x")
                                  :edn :error :code))))))

(deftest like-finds-frames-in-other-clips
  (let [{:keys [run]} (runner)]
    (run "index")
    (let [res (run "like" "r54qb74c.f2")]
      (is (= ["sqlknzuz.f1" "luyk4sl6.f2"] (ids-of res)) "never the frame's own clip")
      (is (= "r54qb74c.f2 (C5274.MP4 at 0:05.0)" (:query (:edn res)))))
    (is (= "bad-id" (-> (run "like" "r54qb74c") :edn :error :code)))
    (is (= "not-found" (-> (run "like" "r54qb74c.f9") :edn :error :code)))))

(deftest a-frame-by-its-id
  (let [ffmpeg (tools/find-tool (into {} (System/getenv)) "ffmpeg")]
    (when ffmpeg
      (let [meta (copy-meta)
            proxy (fs/path meta "proxies/media/2026/09/16/src/fx3/C5274.MP4.proxy.mp4")
            _ (fs/delete proxy)
            _ (process/sh ffmpeg "-v" "error" "-f" "lavfi" "-i" "testsrc=size=320x180:rate=24000/1001:duration=33"
                          "-c:v" "mpeg4" "-q:v" "5" (str proxy))
            {:keys [local run]} (runner meta)]
        (run "index")
        (let [{:keys [edn]} (run "frame" "r54qb74c.f2")]
          (is (= {:id "r54qb74c.f2" :file "C5274.MP4" :rel "media/2026/09/16/src/fx3/C5274.MP4" :at "0:05.0"
                  :tc "03:06:56:08"
                  :faces [{:id "r54qb74c.face1" :person "Tobia Sàsso"}]
                  :warning "the proxy has changed since it was embedded; this may not be the same frame"}
                 (dissoc edn :jpeg :help)))
          (is (fs/exists? (str/replace (:jpeg edn) #"^<local>" local))))
        (testing "look --html: a page of the frames"
          (let [{:keys [edn]} (run "look" "x" "--html")
                page (slurp (str/replace (:page edn) #"^<local>" local))]
            (is (str/starts-with? (:page edn) (str local "/look/")))
            (is (= 1 (count (re-seq #"<img src=\"file:///" page))) "the one clip with a real proxy")
            (is (= 2 (count (re-seq #"no proxy" page))))))))))
