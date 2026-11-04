(ns black-box-cli.flags
  "Each command's arguments and flags, and reading a command line against them the AXI way (§6, §10): an unknown
  flag, a missing value or a missing argument is a usage error (exit 2) that lists the command's valid flags;
  `--help` always passes. Returns data; only `out` prints."
  (:require
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.out :as out]
   [clojure.string :as str]))

(def formats
  "The output formats, as --format spells them."
  {"toon" :toon "json" :json "edn" :edn})

(defn- output-flags
  "--format, --fields (unless `fields?` is false) and --full."
  ([] (output-flags true))
  ([fields?]
   (cond-> [{:flag "--format" :value "toon|json|edn" :doc "output format (default toon)"}]
     fields? (conj {:flag "--fields" :value "a,b" :doc "comma-separated fields to show, or `all`"})
     true (conj {:flag "--full" :doc "every field, and long text uncut"}))))

(def ^:private version-flag
  {:flag "--transcripts" :value "version" :doc "transcript version (default deepgram-nova-3-multi)"})

(defn- scope-flags
  "The flags that narrow which files a command looks at."
  ([] (scope-flags true))
  ([video?]
   (cond-> [{:flag "--month" :value "YYYY-MM" :repeat true :doc "only this month (repeatable)"}
            {:flag "--day" :value "YYYY-MM-DD" :repeat true :doc "only this day (repeatable)"}
            {:flag "--path" :value "text" :doc "only files whose path contains this, e.g. src/fx3"}]
     video? (conj {:flag "--video" :doc "only video files (not recorder audio)"})
     true (conj version-flag))))

(defn- limit-flag [n what]
  {:flag "--limit" :value "n" :parse :int :default n :doc (str what " shown (default " n ")")})

(def ^:private person-flag
  {:flag "--person" :value "name" :doc "only where this person is heard (search) or heard or seen (footage)"})

(def ^:private picture-flags
  "look's and like's: how many, how many from one clip, the scope (no transcript version), the review page."
  [(limit-flag 20 "frames")
   {:flag "--per-clip" :value "n" :parse :int :default 1 :doc "at most n frames from one clip (default 1; 0: no limit)"}
   {:flag "--month" :value "YYYY-MM" :repeat true :doc "only this month (repeatable)"}
   {:flag "--day" :value "YYYY-MM-DD" :repeat true :doc "only this day (repeatable)"}
   {:flag "--path" :value "text" :doc "only files whose path contains this, e.g. src/fx3"}
   {:flag "--video" :doc "only video files"}
   {:flag "--html" :doc "also write a page of the frames (prints its path)"}
   {:flag "--open" :doc "write the page and open it in the browser"}])

(def commands
  "Every command: its words, the phase that built it, a summary, arguments, flags and examples."
  [{:path ["index"] :phase 2
    :summary "build this Mac's index from media_meta: transcripts, frames, people, sync and shot logs"
    :flags (into [{:flag "--rebuild" :doc "build it from scratch instead of updating it"} version-flag]
                 (output-flags false))
    :examples ["blackbox index" "blackbox index --rebuild" "blackbox index --format json"]}
   {:path ["search"] :phase 2
    :summary "find what was said (full text, accents and case ignored); any word matches unless --all"
    :args [{:arg "terms" :many true :doc "words, or a quoted phrase"}]
    :flags (-> [{:flag "--all" :doc "every word must match"}
                (limit-flag 20 "lines")
                person-flag
                {:flag "--every-device" :doc "a line heard on several devices once per device (default: once, with `also`)"}]
               (into (scope-flags))
               (into (output-flags)))
    :examples ["blackbox search marble"
               "blackbox search \"hammer drill\" --month 2026-09"
               "blackbox search marble sculpture --all --fields id,file,at,text"
               "blackbox search marble --person <name>"]}
   {:path ["line"] :phase 2
    :summary "one line in context: its file, timecode and proxy"
    :args [{:arg "id" :doc "a line id from search"}]
    :flags (into [{:flag "--context" :value "n" :parse :int :default 3 :doc "lines either side (default 3)"}]
                 (output-flags))
    :examples ["blackbox line <id>" "blackbox line <id> --context 1"]}
   {:path ["transcript"] :phase 2
    :summary "a file's transcript, line by line"
    :args [{:arg "file" :doc "file name or part of its path"}]
    :flags (-> [(limit-flag 40 "lines")] (into (scope-flags false)) (into (output-flags false)))
    :examples ["blackbox transcript C5274.MP4" "blackbox transcript C5274.MP4 --full"]}
   {:path ["footage"] :phase 2
    :summary "the files with transcripts in scope: day, length, lines"
    :flags (-> [(limit-flag 50 "files") person-flag] (into (scope-flags)) (into (output-flags)))
    :examples ["blackbox footage --month 2026-09" "blackbox footage --video --fields all"
               "blackbox footage --person <name> --fields file,day,heard,seen,heard_at,seen_at"]}
   {:path ["edl" "new"] :phase 3
    :summary "a cut list → a rough cut for mpv (proxies only) and cut.edl for Resolve"
    :args [{:arg "cutlist" :doc "a cut list file, or - for stdin"}]
    :flags (-> [{:flag "--title" :value "text" :doc "the cut's title (or `title:` in the cut list)"}
                {:flag "--dry-run" :doc "check and show the cut; write nothing"}
                {:flag "--play" :doc "open the cut in mpv when it's written"}
                version-flag]
               (into (output-flags false)))
    :examples ["blackbox edl new cut.txt --dry-run" "blackbox edl new - --play < cut.txt"]
    :about cutlist/help}
   {:path ["edl" "list"] :phase 3
    :summary "EDLs made on this Mac, newest first"
    :flags (into [(limit-flag 20 "EDLs")] (output-flags))
    :examples ["blackbox edl list" "blackbox edl list --fields all"]}
   {:path ["edl" "show"] :phase 3
    :summary "one EDL's clips and files"
    :args [{:arg "id" :doc "its id, or part of it, from `blackbox edl list`"}]
    :flags (into [{:flag "--play" :doc "open the cut in mpv"}] (output-flags false))
    :examples ["blackbox edl show <id>" "blackbox edl show <id> --play"]}
   {:path ["edl" "build"] :phase 11
    :summary "have the mini build the cut as a new Resolve project: originals, recorders' audio synced, proxies linked"
    :args [{:arg "id" :doc "its id, or part of it, from `blackbox edl list`"}]
    :flags (output-flags false)
    :examples ["blackbox edl build <id>" "blackbox edl show <id>   (how the build is going)"]
    :about (str "Black Box on this Mac carries the request to the mini, which builds the project in Resolve's\n"
                "\"Black Box cuts\" folder, named \"<title> (<id>)\". Each cut is built once; `edl show` gives its state.\n")}
   {:path ["frame"] :phase 4
    :summary "a frame as a JPEG, pulled from the proxy into this Mac's cache; prints its path"
    :args [{:arg "what" :doc "a frame id, a shot id (its best moment), a line id (its start), or a file (name or part of its path)"}]
    :flags (-> [{:flag "--at" :value "time" :doc "for a file: the moment (s, m:ss or h:mm:ss; default 0)"}
                {:flag "--width" :value "px" :parse :int :default 960 :doc "pixels wide (default 960; 0: the proxy's)"}
                {:flag "--day" :value "YYYY-MM-DD" :repeat true :doc "for a file: only this day (repeatable)"}
                {:flag "--path" :value "text" :doc "for a file: only paths containing this"}]
               (into (output-flags false)))
    :examples ["blackbox frame <frame id>" "blackbox frame <shot id>" "blackbox frame <line id>" "blackbox frame C5274.MP4 --at 0:21.5"
               "blackbox frame <line id> --width 0"]}
   {:path ["still" "new"] :phase 12
    :summary "stills from the originals, native size, in a look: the mini renders them (minutes); still show has the paths"
    :args [{:arg "what" :many true :doc "a frame id, a line id, or file@time (as `frame` takes them); add =name to name the file, ~white=x,y or ~gains=r,g,b to balance it"}]
    :flags (-> [{:flag "--look" :value "neutral|filmic|rec709|log" :default "neutral"
                 :doc "neutral: the house look (default); filmic: neutral, a film curve and grain; rec709: the camera's conversion only; log: ungraded"}
                {:flag "--tiff" :doc "a 16-bit TIFF beside each JPEG"}
                {:flag "--out" :value "folder" :doc "also copy each still here when it's done (never over a file)"}]
               (into (output-flags false)))
    :examples ["blackbox still new <frame id>" "blackbox still new C5274.MP4@40=unloading <line id>=quote --look rec709"
               "blackbox still new <frame id>=hero --out ~/site/media-inbox"
               "blackbox still new C5274.MP4@40=unloading~white=0.6,0.56 --look filmic"
               "blackbox still show <id>"]
    :about (str "Find the moment first with search, look and frame (proxies, on this Mac). Black Box carries the request\n"
                "to the Mac mini, which renders each frame from the original camera file at its own resolution, in the\n"
                "look's fixed grade, and brings the files back to this Mac. Nothing on this Mac reads an original.\n"
                "\n"
                "A look never guesses a white balance: a warm or cool shot is how the camera was set. To correct one,\n"
                "add ~white=x,y (a point that is white or grey in the scene, as fractions of the frame's width and\n"
                "height, 0,0 top left: the mini makes it neutral) or ~gains=r,g,b (0.25 to 4). Brightness is kept.\n"
                "`still show` reports the gains used (balance), to reuse as ~gains for other frames of the shot.\n")}
   {:path ["still" "show"] :phase 12
    :summary "a stills request: its state and the paths of the files that have come back (--wait until they have)"
    :args [{:arg "id" :doc "its id, or part of it, from `blackbox still list`"}]
    :flags (into [{:flag "--wait" :doc "ask again every few seconds until every still is done or failed"}
                  {:flag "--timeout" :value "minutes" :parse :int :default 60 :doc "how long --wait waits (default 60)"}]
                 (output-flags false))
    :examples ["blackbox still show <id>" "blackbox still show <id> --wait   (run it in the background)"]}
   {:path ["still" "list"] :phase 12
    :summary "stills requests made on this Mac, newest first"
    :flags (into [(limit-flag 20 "requests")] (output-flags false))
    :examples ["blackbox still list"]}
   {:path ["look"] :phase 5
    :summary "frames by what's in the picture, best first (every frame scored; Black Box reads the words)"
    :args [{:arg "words" :many true :doc "what to look for, e.g. two people at a table"}]
    :flags (-> picture-flags (into (output-flags)))
    :examples ["blackbox look boat on the lagoon" "blackbox look hands carving stone --month 2026-09 --per-clip 2"
               "blackbox look sunset --html"]}
   {:path ["like"] :phase 5
    :summary "frames, voices or faces like one, from other clips, best first"
    :args [{:arg "id" :doc "a frame id (frames), a line id (its voice) or a face id (faces)"}]
    :flags (-> picture-flags (into (output-flags)))
    :examples ["blackbox like <frame id>" "blackbox like <frame id> --day 2026-09-16 --open" "blackbox like <line id>"
               "blackbox like <face id> --per-clip 0"]}
   {:path ["shots"] :phase 13
    :summary "shots by what happens in them (the shot logs): framing, the camera's move, the action, the best moment, issues"
    :args [{:arg "words" :many true :optional true :doc "what happens, e.g. hands carving stone (optional with a filter)"}]
    :flags (-> [{:flag "--all" :doc "every word must match"}
                {:flag "--camera" :value "move" :doc "only this camera move: static, handheld, pan, tilt, follow, tracking, zoom…"}
                {:flag "--framing" :value "wide|medium|close" :repeat true :doc "only this framing (repeatable)"}
                {:flag "--clean" :doc "only shots with no issues logged"}
                {:flag "--file" :value "file" :doc "only this file's shots (name or part of its path)"}
                (limit-flag 20 "shots")
                {:flag "--per-clip" :value "n" :parse :int :default 2 :doc "at most n shots from one clip (default 2; 0: no limit; --file: all)"}
                {:flag "--month" :value "YYYY-MM" :repeat true :doc "only this month (repeatable)"}
                {:flag "--day" :value "YYYY-MM-DD" :repeat true :doc "only this day (repeatable)"}
                {:flag "--path" :value "text" :doc "only files whose path contains this, e.g. src/fx3"}]
               (into (output-flags)))
    :examples ["blackbox shots hands carving stone" "blackbox shots boat lagoon --camera pan --framing wide"
               "blackbox shots --file C5274.MP4 --day 2026-09-16" "blackbox shots workshop --clean --fields all"]
    :about (str "Black Box logs every clip's shots on the mini (Gemini, from the proxy with its sound): where each shot\n"
                "starts and ends, its framing and camera move, what happens, its best few seconds and why, and issues\n"
                "(shake, focus, flat log look…). Words match the action, the reason, the issues and the clip's summary\n"
                "and tags. A shot id (k7f3q2ab.s3) in a cut list plays its best moment; `k7f3q2ab.s3 whole`, all of it.\n")}
   {:path ["people"] :phase 8
    :summary "the names Black Box publishes: who is heard and seen in how many clips"
    :flags (output-flags)
    :examples ["blackbox people" "blackbox people --fields all"]}
   {:path ["whose"] :phase 8
    :summary "who a voice or a face may be: the names file's answer, and everyone scored by print"
    :args [{:arg "id" :doc "a line id (its speaker's voice) or a face id (from frame or like)"}]
    :flags (into [(limit-flag 5 "people")] (output-flags false))
    :examples ["blackbox whose <line id>" "blackbox whose <face id>"]}
   {:path ["setup" "agent"] :phase 6
    :summary "install the Agent Skill and the session hook"
    :flags (output-flags false)
    :examples ["blackbox setup agent"]}])

(defn find-command
  "The command `words` start with, and the words left after it: [spec rest], or nil."
  [words]
  (some (fn [{:keys [path] :as spec}]
          (when (= path (take (count path) words))
            [spec (vec (drop (count path) words))]))
        commands))

(defn group-of
  "The commands under `word` when it names a group (edl, setup) rather than a command, else nil."
  [word]
  (seq (filter #(and (= word (first (:path %))) (< 1 (count (:path %)))) commands)))

(defn command-name
  "`blackbox edl new`, as help and errors spell it."
  [spec]
  (str "blackbox " (str/join " " (:path spec))))

(defn- flag-usage [{:keys [flag value]}]
  (if value (str flag " <" value ">") flag))

(defn- arg-usage [{:keys [arg many optional]}]
  (if optional (str "[" arg (when many "…") "]") (str "<" arg (when many "…") ">")))

(defn- usage-help
  "Next steps for a usage error on `spec`: its valid flags inline, so the agent corrects in one turn."
  [spec]
  [(str "Valid flags for `" (command-name spec) "`: " (str/join ", " (map flag-usage (:flags spec)))
        " (--help always allowed)")
   (str "Run `" (command-name spec) " --help` for the options")])

(defn usage!
  "Stops with a usage error (exit 2) about `spec`."
  [spec message]
  (out/fail! "usage" message {:help (usage-help spec)}))

(defn help-text
  "The concise reference for one command: arguments, flags with defaults, examples."
  [{:keys [summary args flags examples about] :as spec}]
  (let [rows (concat (map (juxt arg-usage :doc) args) (map (juxt flag-usage :doc) flags))
        width (+ 2 (apply max 0 (map (comp count first) rows)))]
    (str (command-name spec) (apply str (map #(str " " (arg-usage %)) args)) ": " summary "\n\n"
         (apply str (map (fn [[k doc]] (str "  " k (apply str (repeat (- width (count k)) " ")) doc "\n")) rows))
         "  --help" (apply str (repeat (- width 6) " ")) "this help\n"
         "\nExamples:\n"
         (apply str (map #(str "  " % "\n") examples))
         (when about (str "\n" about)))))

(defn- flag-key [flag] (keyword (subs flag 2)))

(defn- defaults [spec]
  (into {} (keep (fn [{:keys [flag default]}] (when (some? default) [(flag-key flag) default]))) (:flags spec)))

(defn- value-of
  "A flag's value read as its spec says, or a usage error."
  [spec {:keys [flag parse]} v]
  (case parse
    :int (or (parse-long v) (usage! spec (str flag " needs a whole number, not '" v "'")))
    v))

(defn- flag-token? [t]
  (and (str/starts-with? t "-") (not= t "-")))

(defn- read-tokens
  "Splits `tokens` into flags (by key) and arguments; collects unknown flags rather than stopping at the first."
  [spec tokens]
  (let [known (into {} (map (juxt :flag identity)) (:flags spec))]
    (loop [opts (defaults spec) args [] unknown [] [t & more :as ts] tokens]
      (cond
        (empty? ts) {:opts opts :args args :unknown unknown}
        (= t "--") (recur opts (into args more) unknown nil)
        (flag-token? t)
        (let [[flag inline] (str/split t #"=" 2)
              {:keys [value repeat] :as f} (known flag)
              k (when f (flag-key flag))]
          (cond
            (nil? f) (recur opts args (conj unknown flag) more)
            (not value) (if inline
                          (usage! spec (str flag " takes no value"))
                          (recur (assoc opts k true) args unknown more))
            :else
            (let [v (or inline (when-not (some-> (first more) flag-token?) (first more)))
                  more (if inline more (rest more))]
              (when (nil? v) (usage! spec (str flag " needs a value: <" value ">")))
              (let [v (value-of spec f v)]
                (recur (if repeat (update opts k (fnil conj []) v) (assoc opts k v)) args unknown more)))))
        :else (recur opts (conj args t) unknown more)))))

(defn- bind-args
  "The arguments by name, or a usage error when one is missing or one too many."
  [spec args]
  (let [specs (:args spec)
        {:keys [arg many]} (last specs)
        fixed (if many (butlast specs) specs)
        required (remove :optional specs)]
    (when (< (count args) (count required))
      (usage! spec (str "`" (command-name spec) "` needs " (str/join " " (map arg-usage (drop (count args) required))))))
    (when (and (not many) (> (count args) (count specs)))
      (usage! spec (str "unexpected argument '" (nth args (count specs)) "' for `" (command-name spec) "`")))
    (cond-> (zipmap (map (comp keyword :arg) fixed) args)
      many (assoc (keyword arg) (vec (drop (count fixed) args))))))

(defn parse
  "Reads `tokens` (what follows the command's words) against `spec`: {:opts :args}, or {:help true} when --help or
  -h is anywhere. Throws a usage error otherwise wrong."
  [spec tokens]
  (if (some #{"--help" "-h"} tokens)
    {:help true}
    (let [{:keys [opts args unknown]} (read-tokens spec tokens)]
      (when (seq unknown)
        (usage! spec (str "unknown flag" (when (next unknown) "s") " " (str/join ", " unknown)
                          " for `" (command-name spec) "`")))
      (when-let [f (:format opts)]
        (when-not (formats f)
          (usage! spec (str "--format " f ": use toon, json or edn"))))
      {:opts opts :args (bind-args spec args)})))
