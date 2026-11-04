(ns black-box-cli.preview
  "A cut rendered to one continuous mp4 for watching: mpv stalls at every cut of an EDL (it opens and seeks each
  file, and rebuilds its output where a recorder's mono WAV follows a proxy), so `--play` renders the cut first.
  Made with ffmpeg from the cut's clips (proxies only, never the NAS) into this Mac's cache, once per cut and
  content; every clip is brought to 960×540 at 23.976 with stereo sound, and cut.srt goes in as subtitles.

  A clip with sound but no picture (a recorder's line) takes the picture of a camera heard with it, from the sync
  map: first a camera with the speaker's face on screen, then the surest pair, then the longest overlap
  (Black Box's rule). With none, it shows its own waveform and its subtitles say
  \"audio only\"."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.people :as people]
   [black-box-cli.sync :as sync]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private size "960x540")
(def ^:private rate "24000/1001")

(def ^:private tolerance
  "Seconds a clip may run past a pair's overlap and still take its camera's picture."
  0.05)

;; ------------------------------------------------------------------------------------------------ the clips

(defn parse-mpv-edl
  "The segments of an mpv EDL's text: [{:path :start :length}], the paths length-prefixed (`%n%`) as cuts writes them."
  [text]
  (vec (for [line (str/split-lines text)
             :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))]
         (let [[_ n rest] (re-matches #"%(\d+)%(.*)" line)
               bs (.getBytes ^String rest "UTF-8")
               n (parse-long n)
               path (String. bs 0 (int n) "UTF-8")
               [start length] (str/split (String. bs (int (inc n)) (int (- (alength bs) n 1)) "UTF-8") #",")]
           {:path path :start (parse-double start) :length (parse-double length)}))))

(defn- clips-of
  "The rough cut's clips in play order: from cut.json ({:path :start :length :rel :sha256 :from :name}), or from
  cut.mpv.edl for a folder without one."
  [folder]
  (let [f (fs/path folder "cut.json")]
    (if (fs/exists? f)
      (->> (:clips (json/parse-string (slurp (str f)) true))
           (filter :play_path)
           (mapv (fn [c] {:path (:play_path c) :start (double (:start c)) :length (- (double (:end c)) (:start c))
                          :rel (:rel c) :sha256 (:source_sha256 c) :from (:from c) :name (:source_name c)})))
      (parse-mpv-edl (slurp (str (fs/path folder "cut.mpv.edl")))))))

(defn- streams
  "The kinds of stream in `path` (a set of \"video\" and \"audio\"), from ffprobe beside `ffmpeg`. Attached
  pictures and data don't count."
  [ffmpeg path]
  (let [ffprobe (str (fs/path (fs/parent ffmpeg) "ffprobe"))
        {:keys [out]} (process/sh ffprobe "-v" "error" "-show_entries" "stream=codec_type:stream_disposition=attached_pic"
                                  "-of" "csv=p=0" path)]
    (set (keep (fn [l] (let [[kind pic] (str/split l #",")] (when (not= "1" pic) kind))) (str/split-lines out)))))

;; ------------------------------------------------------------------------------------------------ pictures

(defn- voice-of
  "The person id named for the voice of line `id`, or nil."
  [index-path id]
  (when (and id (people/available? index-path))
    (when-let [{:keys [clip cue]} (try (ids/parse id) (catch clojure.lang.ExceptionInfo _ nil))]
      (when-let [r (db/one index-path "SELECT c.speaker, t.rel, t.json_sha256 FROM cues c
                                       JOIN transcripts t ON t.id = c.transcript_id
                                       WHERE t.version = :v AND t.clip = :clip AND c.n = :n AND c.speaker IS NOT NULL;"
                           {:v index/default-version :clip clip :n cue})]
        (:person (db/one (people/path index-path)
                         "SELECT person FROM names WHERE kind = 'voice' AND rel = :rel AND transcript_sha256 = :t
                          AND label = :l;"
                         {:rel (:rel r) :t (:json_sha256 r) :l (:speaker r)}))))))

(defn- face-on-screen?
  "Whether `person`'s face is on screen in the camera file `rel` between `a` and `b` (face ranges are whole seconds)."
  [index-path person rel a b]
  (boolean
   (when (and person (people/available? index-path))
     (some (fn [{:keys [ranges]}] (some (fn [[x y]] (and (<= x b) (< a (inc y)))) (json/parse-string ranges)))
           (db/query (people/path index-path) "SELECT ranges FROM names WHERE kind = 'face' AND rel = :rel AND person = :p;"
                     {:rel rel :p person})))))

(defn best-picture
  "Of `candidates` ({:face :confidence :o0 :o1 :camera_rel ...}), the one to show: a face on screen first, then the
  surest pair, then the longest overlap, then the camera's rel."
  [candidates]
  (first (sort-by (juxt #(if (:face %) 0 1) #(- (:confidence %)) #(- (:o0 %) (:o1 %)) :camera_rel) candidates)))

(defn- picture-for
  "A camera's picture for the clip `c` of a file without one: {:path :start :camera ...}, or nil."
  [{:keys [index-path meta has-video]} {:keys [rel sha256 start length from]}]
  (when (and rel sha256)
    (let [end (+ start length)
          person (delay (voice-of index-path from))]
      (best-picture
       (for [p (sync/cameras-of index-path rel sha256)
             :let [a (sync/camera-time p start)
                   b (sync/camera-time p end)
                   proxy (archive/proxy-of meta (:camera_rel p))]
             :when (and proxy (>= a (- (:o0 p) tolerance)) (<= b (+ (:o1 p) tolerance)) (>= a 0.0) (has-video proxy))]
         (assoc p :path proxy :start a :camera (last (str/split (:camera_rel p) #"/"))
                :face (face-on-screen? index-path @person (:camera_rel p) a b)))))))

(defn audio-only?
  "Whether the proxy at `path` has sound and no picture (a recorder's), by ffprobe beside ffmpeg; nil without ffmpeg."
  [env path]
  (when-let [ffmpeg (tools/find-tool env "ffmpeg")]
    (let [k (streams ffmpeg (str path))]
      (and (contains? k "audio") (not (contains? k "video"))))))

(defn camera-picture
  "The picture for moment `t` of a file without one of its own (`rel`, `sha256`), by the rough cut's rule: a camera
  heard with it, the speaker of line `from` on screen first. {:path :start :camera :camera_rel ...}, or nil when no
  camera with a proxy was heard with it then."
  [{:keys [env index-path meta]} {:keys [rel sha256 t from]}]
  (when-let [ffmpeg (tools/find-tool env "ffmpeg")]
    (let [kinds (memoize #(streams ffmpeg %))
          has-video #(and (fs/exists? %) (not (tools/online-only? %)) (contains? (kinds %) "video"))]
      (picture-for {:index-path index-path :meta meta :has-video has-video}
                   {:rel rel :sha256 sha256 :start t :length 0.0 :from from}))))

;; ------------------------------------------------------------------------------------------------ ffmpeg

(defn ffmpeg-args
  "ffmpeg's arguments (after the program) to render `segments` and the subtitles at `srt` (or nil) to `out-path`
  with `encoder`. A segment is {:path :start :length :video :audio}, the last two whether the source has them, and
  :picture {:path :start} for a camera's picture over its sound; with sound and no picture it shows its waveform,
  with neither it's black and silent."
  [segments srt out-path encoder]
  (let [scale (fn [len] (str "scale=960:540:force_original_aspect_ratio=decrease,pad=960:540:-1:-1,fps=" rate
                             ",trim=0:" len ",setpts=PTS-STARTPTS,"))
        sound (fn [src len j] (str src "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,apad,atrim=0:"
                                   len ",asetpts=PTS-STARTPTS[a" j "]"))
        {:keys [args n filters]}
        (reduce
         (fn [{:keys [args n filters]} [j {:keys [path start length video audio picture]}]]
           (let [len (tc/fixed length 3)
                 src ["-ss" (tc/fixed start 3) "-t" len "-i" path]
                 lavfi (fn [spec] ["-f" "lavfi" "-t" len "-i" spec])]
             (cond
               picture
               {:args (-> args (into src) (into ["-ss" (tc/fixed (:start picture) 3) "-t" len "-i" (:path picture)]))
                :n (+ n 2)
                :filters (conj filters (str "[" (inc n) ":v:0]" (scale len) "setsar=1,format=yuv420p[v" j "];"
                                            (sound (str "[" n ":a:0]") len j)))}

               (and audio (not video))
               {:args (into args src)
                :n (inc n)
                :filters (conj filters (str "[" n ":a:0]asplit=2[w" j "][s" j "];[w" j "]aformat=channel_layouts=mono,"
                                            "showwaves=s=" size ":mode=cline:rate=" rate ":colors=0x8a9099,"
                                            "trim=0:" len ",setpts=PTS-STARTPTS,setsar=1,format=yuv420p[v" j "];"
                                            (sound (str "[s" j "]") len j)))}

               :else
               (let [black (when-not video (lavfi (str "color=black:s=" size ":r=" rate)))
                     quiet (when-not audio (lavfi "anullsrc=r=48000:cl=stereo"))
                     v (if video (str n ":v:0") (str (inc n) ":v"))
                     a (if audio (str n ":a:0") (str (+ n 1 (if black 1 0)) ":a"))]
                 {:args (-> args (into src) (into black) (into quiet))
                  :n (+ n 1 (if black 1 0) (if quiet 1 0))
                  :filters (conj filters (str "[" v "]" (when video (scale len)) "setsar=1,format=yuv420p[v" j "];"
                                              (sound (str "[" a "]") len j)))}))))
         {:args [] :n 0 :filters []}
         (map-indexed vector segments))
        joined (str (str/join (map #(str "[v" % "][a" % "]") (range (count segments))))
                    "concat=n=" (count segments) ":v=1:a=1[v][a]")]
    (concat ["-nostdin" "-v" "error" "-y"]
            args
            (when srt ["-i" srt])
            ["-filter_complex" (str/join ";" (conj filters joined)) "-map" "[v]" "-map" "[a]"]
            (when srt ["-map" (str n ":s") "-c:s" "mov_text" "-metadata:s:s:0" "language=und"])
            (if (= "h264_videotoolbox" encoder)
              ["-c:v" "h264_videotoolbox" "-b:v" "3M"]
              ["-c:v" "libx264" "-preset" "veryfast" "-crf" "23"])
            ["-c:a" "aac" "-b:a" "160k" "-movflags" "+faststart" "-f" "mp4" out-path])))

;; ------------------------------------------------------------------------------------------------ subtitles

(defn- srt-seconds [s]
  (let [[_ h m sec ms] (re-matches #"(\d+):(\d+):(\d+),(\d+)" s)]
    (+ (* 3600 (parse-long h)) (* 60 (parse-long m)) (parse-long sec) (/ (parse-long ms) 1000.0))))

(defn- parse-srt [text]
  (vec (for [block (str/split (str/trim (str/replace (str text) "\r" "")) #"\n\n+")
             :let [[_ times & lines] (str/split-lines block)
                   [_ a b] (some->> times (re-matches #"(\S+) --> (\S+)"))]
             :when a]
         [(srt-seconds a) (srt-seconds b) (str/join "\n" lines)])))

(defn labelled-srt
  "cut.srt's `text` with each clip in `spans` ([a b label] on the cut's timeline) labelled: its subtitles start with
  the label's line, and a clip without any gets the label alone."
  [text spans]
  (let [entries (parse-srt text)
        inside (fn [[a b] [x y]] (< x (/ (+ a b) 2) y))
        labelled (mapv (fn [e] (if-let [[_ _ label] (first (filter #(inside e %) spans))]
                                 (update e 2 #(str label "\n" %))
                                 e))
                       entries)
        bare (for [[x y label] spans :when (not-any? #(inside % [x y]) entries)] [x y label])]
    (apply str (map-indexed (fn [i [a b t]] (str (inc i) "\n" (cuts/srt-time a) " --> " (cuts/srt-time b) "\n" t "\n\n"))
                            (sort-by first (concat labelled bare))))))

;; ------------------------------------------------------------------------------------------------ render

(defn render!
  "The preview of the cut in `folder`, rendered into `cache` the first time (per cut and content): {:path :pictures
  :audio-only}, the counts of clips without a picture of their own that took a camera's, or show their waveform.
  Fails with no-ffmpeg, no-clips, online-only or render-failed. Tries the Mac's hardware encoder, then libx264."
  [{:keys [env cache folder index-path meta]}]
  (let [clips (clips-of folder)
        _ (when (empty? clips) (out/fail! "no-clips" "the rough cut has no clips with a proxy" {}))
        ffmpeg (or (tools/find-tool env "ffmpeg")
                   (out/fail! "no-ffmpeg" "ffmpeg isn't installed" {:help ["Run `brew install ffmpeg`"]}))
        _ (doseq [p (distinct (map :path clips))]
            (when (or (not (fs/exists? p)) (tools/online-only? p))
              (out/fail! "online-only" (str p " isn't on this Mac")
                         {:help ["Make media_meta/proxies available offline in Finder"]})))
        kinds (memoize #(streams ffmpeg %))
        _ (doseq [p (distinct (map :path clips)) :when (empty? (kinds p))]
            (out/fail! "render-failed" (str (fs/file-name p) " has neither picture nor sound") {}))
        has-video #(and (fs/exists? %) (not (tools/online-only? %)) (contains? (kinds %) "video"))
        segments (mapv (fn [c]
                         (let [k (kinds (:path c))
                               s (assoc c :video (contains? k "video") :audio (contains? k "audio"))
                               p (when (and index-path (:audio s) (not (:video s)))
                                   (picture-for {:index-path index-path :meta meta :has-video has-video} c))]
                           (cond-> s p (assoc :picture (select-keys p [:path :start :camera])))))
                       clips)
        offsets (reductions + 0.0 (map :length segments))
        spans (for [[s a] (map vector segments offsets)
                    :when (and (:audio s) (not (:video s)) (not (:picture s)))]
                [a (+ a (:length s)) (str "[audio only · " (or (:name s) (fs/file-name (:path s))) "]")])
        srt-file (fs/path folder "cut.srt")
        srt (labelled-srt (if (fs/exists? srt-file) (slurp (str srt-file)) "") spans)
        plan (pr-str [(map #(dissoc % :from :name) segments) srt])
        target (fs/path cache (str (fs/file-name folder) "-" (subs (frames/sha256-hex (.getBytes ^String plan "UTF-8")) 0 12)
                                   ".mp4"))
        result {:path (str target) :pictures (count (filter :picture segments)) :audio-only (count spans)}]
    (if (fs/exists? target)
      result
      (let [_ (fs/create-dirs cache)
            tmp (str (fs/path cache (str "." (fs/file-name target) ".partial-" (.pid (java.lang.ProcessHandle/current)))))
            sub (when-not (str/blank? srt) (str tmp ".srt"))
            run (fn [encoder] (process/sh (cons ffmpeg (ffmpeg-args segments sub tmp encoder))))]
        (try
          (when sub (spit sub srt))
          (let [{:keys [exit err]} (let [r (run "h264_videotoolbox")] (if (zero? (:exit r)) r (run "libx264")))]
            (if (and (zero? exit) (fs/exists? tmp) (pos? (fs/size tmp)))
              (do (try (fs/move tmp target {:atomic-move true})
                       (catch java.nio.file.FileAlreadyExistsException _ nil))
                  result)
              (out/fail! "render-failed" (str "ffmpeg couldn't render the cut"
                                              (when-let [line (first (remove str/blank? (str/split-lines (str err))))]
                                                (str ": " line)))
                         {})))
          (finally (fs/delete-if-exists tmp)
                   (some-> sub fs/delete-if-exists)))))))
