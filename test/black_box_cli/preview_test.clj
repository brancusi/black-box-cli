(ns black-box-cli.preview-test
  "`--play` renders the cut to one file and opens it: on a tiny proxy and a recorder's WAV made by ffmpeg in a copy
  of the fixture media_meta (never a real one). Nothing is ever started: spawn! is replaced. Skipped where ffmpeg
  isn't installed."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.preview :as preview]
   [black-box-cli.support :as s]
   [black-box-cli.sync :as sync]
   [black-box-cli.tools :as tools]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private system-env (into {} (System/getenv)))
(def ^:private ffmpeg (tools/find-tool system-env "ffmpeg"))

(defn- meta-with-media
  "A copy of the fixture media_meta with a real proxy for C5274.MP4 (33 s of a test pattern and a tone, 320×180) and
  a real copy of DJI_01.WAV (40 s of a mono tone in 32-bit float, as the recorders write)."
  []
  (let [meta (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")
        proxy (fs/path meta "proxies/media/2026/09/16/src/fx3/C5274.MP4.proxy.mp4")
        wav (fs/path meta "proxies/media/2026/09/16/audio/DJI_01.WAV")]
    (fs/copy-tree s/fixture-meta meta)
    (fs/delete proxy)
    (fs/create-dirs (fs/parent wav))
    (process/sh ffmpeg "-v" "error" "-f" "lavfi" "-i" "testsrc=size=320x180:rate=24000/1001:duration=33"
                "-f" "lavfi" "-i" "sine=frequency=440:duration=33" "-c:v" "mpeg4" "-q:v" "5" "-c:a" "aac"
                "-shortest" (str proxy))
    (process/sh ffmpeg "-v" "error" "-f" "lavfi" "-i" "sine=frequency=220:duration=40:sample_rate=48000"
                "-c:a" "pcm_f32le" (str wav))
    (str meta)))

(defn- probe [path entries & more]
  (str/trim (:out (process/sh (concat [(str (fs/path (fs/parent ffmpeg) "ffprobe")) "-v" "error" "-show_entries" entries
                                       "-of" "csv=p=0"]
                                      more [path])))))

(deftest the-paths-of-an-mpv-edl
  (is (= [{:path "/a/b, c/Séance.mp4" :start 1.5 :length 2.25} {:path "/x.WAV" :start 0.0 :length 3.0}]
         (preview/parse-mpv-edl "# mpv EDL v0\n%19%/a/b, c/Séance.mp4,1.500,2.250\n%6%/x.WAV,0.000,3.000\n"))))

(deftest play-renders-the-cut-and-opens-it
  (when ffmpeg
    (let [meta (meta-with-media)
          local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
          home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
          bin (fs/create-temp-dir {:prefix "bbcli-bin-"})
          mpv (str (fs/path bin "mpv"))
          started (atom [])
          run (fn [env & args]
                (with-redefs [tools/spawn! #(swap! started conj (vec %))]
                  (let [res (s/cli* {:stdin "title: Render\nr54qb74c.1\n09/16/audio/DJI_01.WAV 5-7\nC5274.MP4 20-21.5\n"
                                     :home home :env (merge system-env env)}
                                    (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                    (read-string (:out res)))))]
      (spit mpv "#!/bin/sh\n")
      (fs/set-posix-file-permissions mpv "rwxr-xr-x")
      (run {} "index")
      (let [{:keys [playing id length help]} (run {} "edl" "new" "-" "--play")
            [[open file]] @started]
        (is (= "yes, in the default video app (1 recorder clip with a camera's picture)" playing)
            "the recorder's line takes the camera's picture through the fixture's sync map")
        (is (= "/usr/bin/open" open))
        (is (str/starts-with? file (str (fs/path local "previews" id) "-")))
        (is (str/starts-with? (first help) "It's playing, rendered to one file"))
        (testing "one file: every clip at 960×540 with stereo sound, the WAV over black, and the subtitles"
          (is (= "video\naudio\nsubtitle" (probe file "stream=codec_type")))
          (is (= "960,540" (probe file "stream=width,height" "-select_streams" "v")))
          (is (= "2" (probe file "stream=channels" "-select_streams" "a")))
          (is (< (abs (- (parse-double (probe file "format=duration"))
                         (parse-double (last (re-find #"(\d+\.\d+)$" (str length))))))
                 0.1)
              "as long as the cut"))
        (testing "made once: edl show --play opens the same file"
          (let [made (fs/last-modified-time file)]
            (is (str/starts-with? (:playing (run {} "edl" "show" id "--play")) "yes, in the default video app"))
            (is (= file (second (second @started))))
            (is (= made (fs/last-modified-time file)))))
        (testing "BLACKBOX_PLAYER=mpv plays it in mpv"
          (is (str/starts-with? (:playing (run {"PATH" (str bin) "BLACKBOX_PLAYER" "mpv"} "edl" "show" id "--play"))
                                "yes, in mpv ("))
          (is (= [mpv "--autofit=70%" "--force-window=immediate" "--keep-open=yes" file] (last @started))))))))

(deftest a-recorder-line-without-a-camera-is-audio-only
  (when ffmpeg
    (let [meta (meta-with-media)
          local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
          home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
          started (atom [])
          run (fn [stdin & args]
                (with-redefs [tools/spawn! #(swap! started conj (vec %))]
                  (read-string (:out (s/cli* {:stdin stdin :home home :env system-env}
                                             (concat ["--meta" meta "--local" local "--format" "edn"] args))))))]
      (run nil "index")
      (testing "past the camera's end (recorder 25 s is camera 38.2 s; it ends at 32.5): the waveform, and the
                subtitles say so"
        (is (= "yes, in the default video app (1 audio only)"
               (:playing (run "title: Late\n09/16/audio/DJI_01.WAV 25-28\n" "edl" "new" "-" "--play"))))
        (let [file (second (last @started))
              subs (:out (process/sh (str ffmpeg) "-v" "error" "-i" file "-map" "0:s" "-f" "srt" "-"))]
          (is (str/includes? subs "[audio only · DJI_01.WAV]"))))
      (testing "without a sync map every recorder clip is audio only"
        (fs/delete-tree (fs/path meta "sync"))
        (run nil "index" "--rebuild")
        (is (= "yes, in the default video app (1 audio only)"
               (:playing (run "title: Later\n09/16/audio/DJI_01.WAV 5-7\n" "edl" "new" "-" "--play"))))))))

(deftest which-camera-carries-the-picture
  (let [p (fn [rel face confidence o0 o1] {:camera_rel rel :face face :confidence confidence :o0 o0 :o1 o1})]
    (is (= "b" (:camera_rel (preview/best-picture [(p "a" false 1.0 0 100) (p "b" true 0.5 0 10)])))
        "the speaker's face on screen first")
    (is (= "a" (:camera_rel (preview/best-picture [(p "a" false 0.9 0 10) (p "b" false 0.5 0 100)])))
        "then the surest pair")
    (is (= "b" (:camera_rel (preview/best-picture [(p "a" false 0.9 0 10) (p "b" false 0.9 0 100)])))
        "then the longest overlap")
    (is (nil? (preview/best-picture [])))))

(deftest a-picture-from-one-file-under-the-sound-of-another
  (let [args (vec (preview/ffmpeg-args [{:path "/r.WAV" :start 5.0 :length 2.0 :audio true
                                         :picture {:path "/c.mp4" :start 18.2}}]
                                       nil "/out.mp4" "libx264"))
        fc (nth args (inc (.indexOf args "-filter_complex")))]
    (is (= ["-ss" "5.000" "-t" "2.000" "-i" "/r.WAV" "-ss" "18.200" "-t" "2.000" "-i" "/c.mp4"]
           (subvec args 4 16)))
    (is (str/starts-with? fc "[1:v:0]scale=") "the picture from the camera")
    (is (str/includes? fc "[0:a:0]aresample") "the sound from the recorder")))

(deftest audio-only-clips-are-labelled-in-the-subtitles
  (is (= (str "1\n00:00:00,000 --> 00:00:01,500\nA camera line\n\n"
              "2\n00:00:02,000 --> 00:00:03,000\n[audio only · R.WAV]\nA recorder line\n\n"
              "3\n00:00:04,000 --> 00:00:06,000\n[audio only · Q.WAV]\n\n")
         (preview/labelled-srt (str "1\n00:00:00,000 --> 00:00:01,500\nA camera line\n\n"
                                    "2\n00:00:02,000 --> 00:00:03,000\nA recorder line\n\n")
                               [[1.8 3.5 "[audio only · R.WAV]"] [4.0 6.0 "[audio only · Q.WAV]"]]))))

(deftest recorder-and-camera-times
  (let [pair {:offset_s -1909.9068 :drift_ppm -1.3 :o0 1909.91}]
    (is (< (abs (- 2000.0 (sync/camera-time pair (sync/recorder-time pair 2000.0)))) 1e-9))
    (is (= 90.0931 (/ (Math/round (* 1e4 (sync/recorder-time pair 2000.0))) 1e4))
        "the drift counts from the start of the overlap")
    (is (= 5.0 (sync/recorder-time {:offset_s -13.2 :drift_ppm nil :o0 13.2} 18.2)))))

(deftest frame-on-a-recorder-shows-a-camera
  (when ffmpeg
    (let [meta (meta-with-media)
          local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
          home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
          run (fn [& args]
                (read-string (:out (s/cli* {:home home :env system-env}
                                           (concat ["--meta" meta "--local" local "--format" "edn"] args)))))]
      (run "index")
      (testing "a recorder's line: the picture of the camera heard with it (recorder 5.0 s is camera 18.2 s)"
        (let [edn (run "frame" "2npshqu5.1")]
          (is (= {:camera "C5274.MP4" :rel "media/2026/09/16/src/fx3/C5274.MP4" :at "0:18.2"
                  :why "a recorder has no picture: a camera heard with it"}
                 (:picture edn)))
          (is (= ["2npshqu5.1" "DJI_01.WAV" "0:05.0"] ((juxt :id :file :at) edn)))
          (is (fs/exists? (str/replace (:jpeg edn) #"^<local>" local)))
          (is (= "r54qb74c.s1" (-> edn :shot :id)) "the camera's shot at that moment")))
      (testing "a moment no camera heard says so"
        (let [edn (run "frame" "09/16/audio/DJI_01.WAV" "--at" "25")]
          (is (= "audio-only" (-> edn :error :code)))
          (is (str/includes? (-> edn :error :message) "DJI_01.WAV is sound only")))))))
