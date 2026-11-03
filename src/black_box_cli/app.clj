(ns black-box-cli.app
  "The Black Box app on this Mac, for what only it can do: carry a request to the mini over its team connection
  (`project` builds, `still` renders; docs/CONTRACT.md). The CLI talks to nothing else (AGENTS.md rule
  4). Black Box is run by its full path (black-box-cli.tools/black-box)."
  (:require
   [babashka.process :as process]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn ask
  "Black Box's answer to `--cli <args> --format json`, with `in` on its stdin when given: [its JSON as data, nil],
  or [nil {:code :message}] when it couldn't answer. An older Black Box doesn't know the command and says so with a
  usage error (exit 2): black-box-too-old."
  ([env args] (ask env args nil))
  ([env args in]
   (if-let [app (tools/black-box env)]
     (let [{:keys [exit out err]} (apply process/sh (cond-> {} in (assoc :in in))
                                         app "--cli" (concat args ["--format" "json"]))
           doc (try (json/parse-string out true) (catch Exception _ nil))
           lines (remove str/blank? (map str/trim (str/split-lines (str out err))))
           said (or (get-in doc [:error :message])
                    (some #(some-> (re-matches #"message: \"?(.*?)\"?" %) second) lines) ; its TOON error
                    (first lines)
                    "no answer")]
       (cond
         (= 2 exit) [nil {:code "black-box-too-old"
                          :message (str "this Black Box can't `" (first args) "` yet (" said ")")}]
         (and (zero? exit) (map? doc)) [doc nil]
         (get-in doc [:error :code]) [nil (select-keys (:error doc) [:code :message])]
         :else [nil {:code "black-box-failed" :message said}]))
     [nil {:code "no-black-box"
           :message (str "Black Box isn't installed at " (get env "BLACKBOX_APP" tools/black-box-app))}])))

(defn help-for
  "What to do about Black Box's error `code` when asked for `what` (project builds, stills)."
  [code what]
  (case code
    "no-black-box" ["Install Black Box, or set BLACKBOX_APP to its binary"]
    "app_not_running" ["Open Black Box on this Mac, then run it again: it carries the request to the mini"]
    "no_server" ["This Mac's Black Box has no Mac mini on its team: join the team in Black Box's settings"]
    "server_unreachable" ["The Mac mini isn't answering: check it's on and reachable, then run it again"]
    "server_too_old" ["The Mac mini's Black Box is older than this one: update it there (the owner releases Black Box)"]
    "no_role" ["This Mac's Black Box has no role on the team that may ask the mini: set it in Black Box's settings"]
    "bad_cut" ["Make the cut again with `blackbox edl new`: the mini couldn't read this one"]
    "black-box-too-old" [(str "Update Black Box to a version with " what ", then run it again")]
    ["Run it again; if it keeps failing, tell the owner: the mini's Black Box does this"]))
