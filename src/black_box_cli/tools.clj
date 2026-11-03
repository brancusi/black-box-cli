(ns black-box-cli.tools
  "The local tools the CLI runs (AGENTS.md rule 4): finding them, and telling a file Dropbox keeps online-only, which
  must never be read (reading it makes Dropbox download it)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.lang ProcessBuilder$Redirect)))

(def ^:private homebrew-dirs
  "Homebrew's, for when the PATH (an agent's) leaves them out."
  ["/opt/homebrew/bin" "/usr/local/bin"])

(defn find-tool
  "The executable `name`: in `$BLACKBOX_TOOLS` first (the tools Black Box bundles with this CLI), then on the PATH
  in `env`, then in Homebrew's folders; nil when it's nowhere."
  [env name]
  (->> (concat (some-> (get env "BLACKBOX_TOOLS") (str/split #":")) (str/split (get env "PATH" "") #":" -1) homebrew-dirs)
       (map #(str (fs/path (if (str/blank? %) "." %) name)))
       (filter #(and (fs/regular-file? %) (fs/executable? %)))
       first))

(def black-box-app
  "Black Box's binary, run by its full path: on a Mac `BlackBox` and `blackbox` are one name on the PATH, and the
  name is this CLI's."
  "/Applications/Black Box.app/Contents/MacOS/BlackBox")

(defn black-box
  "The Black Box binary to run: `$BLACKBOX_APP`, else the app's, or nil when it isn't there."
  [env]
  (let [app (or (get env "BLACKBOX_APP") black-box-app)]
    (when (and (fs/regular-file? app) (fs/executable? app)) app)))

(defn spawn!
  "Starts `argv` without waiting for it, its input and output on /dev/null (mpv, open). Tests replace this: they
  never start mpv."
  [argv]
  (-> (ProcessBuilder. ^java.util.List (vec argv))
      (.redirectInput (ProcessBuilder$Redirect/from (File. "/dev/null")))
      (.redirectOutput ProcessBuilder$Redirect/DISCARD)
      (.redirectError ProcessBuilder$Redirect/DISCARD)
      .start)
  nil)

(defn online-only?
  "Whether `path` is a Dropbox placeholder: empty, or flagged dataless."
  [path]
  (or (zero? (fs/size path))
      (let [{:keys [exit out]} (process/sh "find" (str path) "-flags" "+dataless")]
        (and (zero? exit) (not (str/blank? out))))))
