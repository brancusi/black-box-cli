(ns black-box-cli.update
  "`blackbox update`: asks Black Box on this Mac to install the newest blackbox now. Black Box does the download
  (the CLI talks to nothing but local tools, AGENTS.md rule 4): it takes a release only once it's signed on the
  mini and when this Mac's Black Box is at least the release's `min_black_box`, and never runs one older than the
  copy it carries (docs/CONTRACT.md)."
  (:require
   [black-box-cli.app :as app]
   [black-box-cli.out :as out]
   [black-box-cli.version :as version]))

(defn installed
  "How this blackbox came to be on this Mac, from what Black Box's launcher sets: \"updated, Black Box 0.45.0\",
  \"bundled, Black Box 0.45.0\", or nil when it wasn't started by Black Box (a checkout, bbin)."
  [env]
  (when-let [source (get env "BLACKBOX_CLI_SOURCE")]
    (str source (some->> (get env "BLACKBOX_APP_VERSION") (str ", Black Box ")))))

(defn- help-for [{:keys [state latest min_black_box]}]
  (case state
    "updated" [(str "blackbox " latest " is installed: the next command runs it, and the agent skill was updated")
               "Run `blackbox tutorial new` for what's new"]
    "current" ["This is the newest blackbox; Black Box also checks every few minutes by itself"]
    "unsigned" [(str "blackbox " latest " was just published: the Mac mini checks and signs each release by itself,"
                     " then it installs within minutes (if it's still waiting in an hour, tell the owner)")]
    "needs-black-box" [(str "blackbox " latest " needs Black Box " min_black_box " or later: update Black Box"
                            " (it installs blackbox " latest " as soon as it's updated)")]
    ["Run `blackbox update` again; if it keeps failing, tell the owner"]))

(defn update-cmd
  "`blackbox update`: Black Box checks for a newer blackbox now and installs it when this Mac's Black Box can run it."
  [{:keys [env]}]
  (let [[ans err] (app/ask env ["cli" "update"])]
    (when err
      (out/fail! (:code err) (str "Black Box couldn't update blackbox: " (:message err))
                 {:help (case (:code err)
                          "black-box-too-old" ["This Black Box doesn't update blackbox by itself yet: update Black Box, which carries the newest it has"]
                          "signature_invalid" ["The newest release's signature doesn't verify, so it wasn't installed and this blackbox keeps running: tell the owner"]
                          (app/help-for (:code err) "blackbox updates"))}))
    [(apply array-map
            (concat [:state (:state ans)
                     :running version/version
                     :latest (or (:latest ans) "unknown")
                     :installed (or (:installed ans) version/version)
                     :black_box (or (:black_box ans) "unknown")]
                    (when (= "needs-black-box" (:state ans)) [:needs_black_box (:min_black_box ans)])
                    (when (:message ans) [:message (:message ans)])))
     (help-for ans)]))
