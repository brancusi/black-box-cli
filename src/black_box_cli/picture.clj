(ns black-box-cli.picture
  "Frames as JPEGs, pulled from the proxies in media_meta with ffmpeg (never the originals, never the NAS) into this
  Mac's cache, made once per proxy, time and width. `frame` shows one; cut.html shows a clip's in, middle and out."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.out :as out]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [clojure.string :as str]))

(def default-width
  "Pixels wide, unless --width says otherwise (0: the proxy's own size)."
  960)

(defn- target
  "Where the JPEG of `proxy` at `t` lives in `cache`: per clip and proxy size, so a new proxy gets new frames."
  [cache clip proxy t width]
  (fs/path cache (str clip "-" (fs/size proxy)) (str (tc/fixed t 6) "-w" width ".jpg")))

(defn jpeg!
  "The JPEG of the frame at `t` seconds in `proxy`, made with ffmpeg the first time; its path. Fails with
  no-ffmpeg, online-only or no-frame."
  [{:keys [env cache clip proxy t width]}]
  (let [out-path (target cache clip proxy t width)]
    (if (fs/exists? out-path)
      (str out-path)
      (let [ffmpeg (or (tools/find-tool env "ffmpeg")
                       (out/fail! "no-ffmpeg" "ffmpeg isn't installed" {:help ["Run `brew install ffmpeg`"]}))
            _ (when (tools/online-only? proxy)
                (out/fail! "online-only" (str proxy " is online-only")
                           {:help ["Make media_meta/proxies available offline in Finder"]}))
            dir (fs/create-dirs (fs/parent out-path))
            tmp (fs/path dir (str "." (fs/file-name out-path) ".partial-" (.pid (java.lang.ProcessHandle/current))))
            ;; A hair before t: the first frame at or after the seek point is t's own, whatever the rounding.
            seek (tc/fixed (max 0.0 (- t 0.001)) 6)
            {:keys [exit err]} (process/sh (concat [ffmpeg "-nostdin" "-v" "error" "-ss" seek "-i" (str proxy)
                                                    "-frames:v" "1" "-an"]
                                                   (when (pos? width) ["-vf" (str "scale=w='min(" width ",iw)':h=-2")])
                                                   ["-pix_fmt" "yuvj420p" "-c:v" "mjpeg" "-q:v" "3" "-f" "image2"
                                                    "-y" (str tmp)]))]
        (try
          (if (and (zero? exit) (fs/exists? tmp) (pos? (fs/size tmp)))
            (do (try (fs/move tmp out-path {:atomic-move true})
                     (catch java.nio.file.FileAlreadyExistsException _ nil))
                (str out-path))
            (out/fail! "no-frame" (str "no frame at " (tc/hms t) " in " (fs/file-name proxy)
                                       (when-let [line (first (remove str/blank? (str/split-lines (str err))))]
                                         (str ": " line)))
                       {:help ["Check the time against the file's length (`blackbox transcript <file>`)"]}))
          (finally (fs/delete-if-exists tmp)))))))

(defn try-jpeg
  "jpeg!, or nil when the frame can't be made (no ffmpeg, no proxy, past the end): for pages, where a missing
  picture is a grey box, not an error."
  [opts]
  (when (:proxy opts)
    (try (jpeg! opts) (catch clojure.lang.ExceptionInfo _ nil))))

(defn url
  "A local file as a file:// URL for a page."
  [path]
  (str (.toUri (fs/path path))))
