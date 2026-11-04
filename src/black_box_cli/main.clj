(ns black-box-cli.main
  "The `blackbox` command line: global flags, dispatch, the home view. Each command's handler lives in the
  namespace that owns it."
  (:require
   [black-box-cli.archive :as archive]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.edl :as edl]
   [black-box-cli.flags :as flags]
   [black-box-cli.index :as index]
   [black-box-cli.look :as look]
   [black-box-cli.meta :as meta]
   [black-box-cli.out :as out]
   [black-box-cli.setup :as setup]
   [black-box-cli.stills :as stills]
   [black-box-cli.version :as version]
   [black-box-cli.who :as who]
   [clojure.string :as str]))

(def description
  "The one sentence the home view and the skill start with."
  "Search the footage archive by what was said and what's in the picture, and build rough cuts and EDLs for Resolve.")

(def global-flags
  "Flags taken before the command, each with a value. --format is also every command's own."
  #{"--meta" "--local" "--format"})

(def help-text
  (str "blackbox " version/version ": " description "

  blackbox                       what the index holds, where media_meta is, recent cuts
  blackbox index [--rebuild]     build this Mac's index from media_meta
  blackbox search <words…>       what was said (--all --month --day --path --video --limit)
  blackbox line <id>             one line in context
  blackbox transcript <file>     a file's lines
  blackbox footage               the files in scope
  blackbox edl new <cutlist|->   a rough cut (mpv, proxies only) and cut.edl for Resolve (--dry-run --play)
  blackbox edl list | edl show <id>
  blackbox edl build <id>        have the mini build a cut as a Resolve project (originals, synced audio, proxies)
  blackbox frame <id>            a frame as a JPEG, from the proxy
  blackbox still new <id…>       stills from the originals, full size and graded, rendered by the mini (list, show)
  blackbox look <words…>         what's in the picture; like <frame id> for frames like one
  blackbox shots [words…]        shots by what happens in them: camera move, framing, best moment, issues
  blackbox people                who is heard and seen (--person on search and footage)
  blackbox whose <id>            who a voice (line id) or a face (face id) may be; like <id> for ones like it
  blackbox setup agent           the Agent Skill and the session hook

Global flags, before the command: --meta <media_meta folder>, --local <cache and cuts folder>.
Output is TOON (--format json or edn, anywhere on the line); errors are on stdout; exit 0 done, 1 error, 2 usage.
Nothing prompts. `blackbox <command> --help` for a command's flags and examples.
"))

(defn- parse-globals
  "Splits leading global flags from the command and its arguments."
  [args]
  (loop [opts {} [a & more :as args] args]
    (cond
      (and (contains? global-flags a) (seq more)) (recur (assoc opts (keyword (subs a 2)) (first more)) (rest more))
      (contains? global-flags a) (out/fail! "usage" (str a " needs a value") {:help ["Run `blackbox --help` for the options"]})
      :else [opts (vec args)])))

(defn- fmt-of
  "The output format: the last valid --format on the line, else BLACKBOX_FORMAT, else TOON. Read before the
  command is parsed, so its usage errors come out in the format asked for."
  [args env]
  (or (->> (partition 2 1 args)
           (keep (fn [[a b]] (when (= a "--format") (flags/formats b))))
           last)
      (some->> args (keep #(some-> (re-matches #"--format=(.*)" %) second flags/formats)) last)
      (flags/formats (get env "BLACKBOX_FORMAT"))
      :toon))

(defn- check-format!
  "A usage error for a --format before the command that names no format."
  [opts]
  (when-let [f (:format opts)]
    (when-not (flags/formats f)
      (out/fail! "usage" (str "--format " f ": use toon, json or edn") {:help ["Run `blackbox --help` for the options"]}))))

(defn- bin [{:keys [env home]}]
  (out/home-path (or (get env "BLACKBOX_BIN") (System/getProperty "babashka.file") "blackbox") home))

(defn- home-view [ctx fmt]
  (let [{:keys [index months]} (archive/home-index ctx)
        m (meta/locate ctx)
        meta-line (if m (out/home-path m (:home ctx)) "not found")
        no-meta "Pass --meta <the media_meta folder>, or make Dropbox's media_meta available on this Mac"]
    (if-not months
      (out/emit! (array-map :bin (bin ctx) :description description :index index :media_meta meta-line)
                 [(if m "Run `blackbox index` to build the search index from the transcripts" no-meta)]
                 fmt)
      (let [recent (take 3 (cuts/edls (index/paths ctx)))]
        (out/emit! (array-map :bin (bin ctx)
                              :description description
                              :index index
                              :media_meta meta-line
                              :months months
                              :recent_edls (if (seq recent)
                                             (mapv #(select-keys % [:id :title :clips :length]) recent)
                                             "none yet"))
                   (cond-> []
                     (not m) (conj no-meta)
                     true (conj "Run `blackbox search <words> [--month YYYY-MM]` to find what was said"
                                "Run `blackbox footage --month YYYY-MM` to list the files of a month"
                                "Run `blackbox edl new <cutlist|->` to build an EDL (see `blackbox edl new --help`)"))
                   fmt)))
    0))

(def ^:private handlers
  "The built commands, by their words."
  {["index"] archive/index-cmd
   ["search"] archive/search-cmd
   ["line"] archive/line-cmd
   ["transcript"] archive/transcript-cmd
   ["footage"] archive/footage-cmd
   ["edl" "new"] edl/new-cmd
   ["edl" "list"] edl/list-cmd
   ["edl" "show"] edl/show-cmd
   ["edl" "build"] edl/build-cmd
   ["frame"] look/frame-cmd
   ["still" "new"] stills/new-cmd
   ["still" "show"] stills/show-cmd
   ["still" "list"] stills/list-cmd
   ["look"] look/look-cmd
   ["like"] look/like-cmd
   ["shots"] look/shots-cmd
   ["people"] who/people-cmd
   ["whose"] who/whose-cmd
   ["setup" "agent"] setup/agent-cmd})

(defn- meta!
  "media_meta for a command that reads it, or error.code no-meta."
  [ctx]
  (or (meta/locate ctx)
      (out/fail! "no-meta" "no media_meta found on this Mac"
                 {:help ["Pass --meta <the media_meta folder>, or make Dropbox's media_meta available on this Mac"]})))

(defn- command-words
  "Every command's first word, for the unknown-command error."
  []
  (distinct (map (comp first :path) flags/commands)))

(defn- group-help
  "The help for a group such as `edl`: its commands."
  [word cmds]
  (let [width (+ 2 (apply max (map (comp count flags/command-name) cmds)))]
    (str "blackbox " word ": " (str/join ", " (map (comp last :path) cmds)) "\n\n"
         (apply str (map #(let [n (flags/command-name %)]
                            (str "  " n (apply str (repeat (- width (count n)) " ")) (:summary %) "\n"))
                         cmds))
         "\n`blackbox " word " <command> --help` for one command's flags and examples.\n")))

(defn- dispatch
  "Runs the command in `words`; returns the exit code."
  [ctx words fmt]
  (let [[word & more] words
        found (flags/find-command words)]
    (cond
      found
      (let [[spec tokens] found
            {:keys [help opts args]} (flags/parse spec tokens)
            handler (handlers (:path spec))]
        (cond
          help (do (out/text! (flags/help-text spec)) 0)
          :else (let [ctx (assoc ctx :opts opts :args args)
                      ctx (assoc ctx :meta (if (= ["index"] (:path spec)) (meta! ctx) (meta/locate ctx)))
                      [m help] (handler ctx)]
                  (out/emit! m help fmt)
                  0)))

      (flags/group-of word)
      (let [cmds (flags/group-of word)]
        (if (some #{"--help" "-h"} more)
          (do (out/text! (group-help word cmds)) 0)
          (out/fail! "usage" (if (empty? more)
                               (str "`blackbox " word "` needs a command")
                               (str "unknown command `blackbox " word " " (first more) "`"))
                     {:help [(str "Commands: " (str/join ", " (map flags/command-name cmds)))
                             (str "Run `blackbox " word " --help` for the options")]})))

      :else
      (out/fail! "usage" (str "unknown command " word)
                 {:help [(str "Commands: " (str/join ", " (command-words)))
                         "Run `blackbox --help` for the options"]}))))

(defn run
  "Runs the command line in `ctx` ({:args :env :home}) and returns the exit code. Prints only through `out`."
  [{:keys [args env] :as ctx}]
  (let [args (vec args)]
    (if (and (= 1 (count args)) (contains? #{"-v" "-V" "--version"} (first args)))
      (do (out/text! (str version/version "\n")) 0)
      (let [fmt (fmt-of args env)]
        (try
          (let [[opts words] (parse-globals args)]
            (check-format! opts)
            (let [ctx (merge ctx (select-keys opts [:meta :local]))]
              (cond
                (empty? words) (home-view ctx fmt)
                (contains? #{"--help" "-h" "help"} (first words)) (do (out/text! help-text) 0)
                :else (dispatch ctx words fmt))))
          (catch clojure.lang.ExceptionInfo e
            (if-let [err (out/error-of e)]
              (out/error! err fmt)
              (throw e))))))))

(defn -main
  "The entry point for bb and bbin."
  [& args]
  (let [env (into {} (System/getenv))]
    ;; $HOME first, as every shell tool reads it: a launcher or a test that sets it gets that home.
    (System/exit (run {:args args
                       :env env
                       :home (or (not-empty (get env "HOME")) (System/getProperty "user.home"))}))))
