(ns black-box-cli.setup
  "`setup agent` (AXI §7): the Agent Skill and a SessionStart hook that shows the home view, for Claude Code
  (`~/.claude`) and Codex (`~/.agents/skills`, `~/.codex/hooks.json`). It never replaces what a person made: a skill
  without a marker line, or settings it can't read, are left as they are, and settings are copied aside before
  they change. Black Box's own skill and hook (its marker, its hook command) count as ours: this CLI takes them
  over at the cutover."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.out :as out]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def marker
  "The line that makes an installed skill ours to replace."
  "<!-- Installed by `blackbox setup agent` and replaced when it runs again. Delete this line to keep your own edits. -->")

(def ^:private black-box-marker
  "The line in the skill Black Box installed before this CLI took it over."
  "<!-- Installed by Black Box and replaced when it updates. Delete this line to keep your own edits. -->")

(def ^:private default-bin "~/.local/bin/blackbox")

(defn skill-text
  "The skill as `setup agent` installs it: skills/blackbox/SKILL.md, naming `bin` (a ~ path) as the command."
  [bin]
  (-> (slurp (io/resource "blackbox/SKILL.md"))
      (str/replace default-bin bin)))

(defn- bin-path
  "Where the command is: $BLACKBOX_BIN (~ is the home folder), else bbin's ~/.local/bin/blackbox."
  [{:keys [env home]}]
  (str/replace-first (get env "BLACKBOX_BIN" default-bin) #"^~(?=/|$)" (str/re-quote-replacement home)))

(def bundled-marker
  "The line in the launcher Black Box writes at ~/.local/bin/blackbox for the copy of this CLI it bundles."
  "# black-box: bundled-cli")

(defn- ours?
  "Whether `path` is this CLI: bbin's launcher (it names the checkout or the git repo) or Black Box's launcher for
  the bundled copy (its marker line), never a link (Black Box's old command was one, into the app)."
  [path]
  (and (not (fs/sym-link? path))
       (fs/regular-file? path)
       (let [head (byte-array 4096)
             n (with-open [in (io/input-stream (str path))] (.read in head))
             text (String. head 0 (int (max n 0)) "UTF-8")]
         (or (str/includes? text "black-box-cli") (str/includes? text bundled-marker)))))

(defn- require-bin!
  "The command's path, or an error: setup installs what calls it, so it must be this CLI."
  [{:keys [home] :as ctx}]
  (let [path (bin-path ctx)
        shown (out/home-path path home)]
    (cond
      (not (fs/exists? path))
      (out/fail! "not-installed" (str "no blackbox at " shown)
                 {:help ["Open Black Box once: an up-to-date Black Box installs `blackbox` at each start"
                         "Or install it by hand: `bbin install <this checkout or its git URL> --as blackbox`"]})

      (not (ours? path))
      (out/fail! "not-ours" (str shown " is Black Box's own command, not this CLI")
                 {:help ["Black Box installs its own skill and hook for it; this CLI takes over at the cutover"
                         (str "At the cutover, `rm " shown "` (the link only) before `bbin install … --as blackbox`:"
                              " bbin writes through a link, into Black Box's own binary")]})

      :else path)))

;; ------------------------------------------------------------------------------------------------ writing

(defn- replace!
  "Writes `text` to `path` through a temporary file beside it, so it's never half written."
  [path text]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/create-temp-file {:dir (fs/parent path) :prefix ".blackbox-"})]
    (spit (str tmp) text)
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn- skill!
  "Installs the skill at `path`; what it did."
  [path text]
  (let [old (when (fs/exists? path) (slurp (str path)))]
    (cond
      (= old text) "already installed"
      (and old (not (str/includes? old marker)) (not (str/includes? old black-box-marker)))
      "left as it is: edited by hand (no marker line)"
      :else (do (replace! path text) (if old "updated" "installed")))))

(defn- read-json
  "A settings file's JSON, its keys in the file's order when YAML reads it the same (most JSON), else as JSON reads
  it. nil when it isn't JSON."
  [text]
  (when-let [plain (try (json/parse-string text) (catch Exception _ nil))]
    (let [ordered (try (yaml/parse-string text :keywords false) (catch Exception _ nil))]
      (if (= plain ordered) ordered plain))))

(def ^:private printer
  (json/create-pretty-printer
   (assoc json/default-pretty-print-options :indentation 2 :indent-arrays? true :object-field-value-separator ": ")))

(defn- has-hook?
  "Whether a SessionStart hook already runs `command`'s program."
  [start program]
  (some (fn [group]
          (some #(str/includes? (str (get % "command")) program) (when (map? group) (get group "hooks"))))
        start))

(defn- hook!
  "Adds a SessionStart hook running `command` to the settings at `path` (Claude Code's and Codex's share the shape);
  what it did."
  [path command program]
  (let [existed (fs/exists? path)
        settings (if existed (read-json (slurp (str path))) {})
        hooks (get settings "hooks" {})
        start (get hooks "SessionStart" [])]
    (cond
      (not (map? settings)) "left as it is: not JSON this can read"
      (not (map? hooks)) "left as it is: its hooks aren't an object"
      (not (sequential? start)) "left as it is: its SessionStart hooks aren't a list"
      (has-hook? start program) "already installed"
      :else
      (let [group (array-map "hooks" [(array-map "type" "command" "command" command "timeout" 10)])
            settings (assoc settings "hooks" (assoc hooks "SessionStart" (conj (vec start) group)))]
        (when existed
          (fs/copy path (str path ".before-blackbox-" (cuts/stamp))))
        (replace! path (str (json/generate-string settings {:pretty printer}) "\n"))
        (if existed "installed (the old file is kept beside it)" "installed")))))

(defn- codex-hooks-on?
  "Whether Codex's config turns hooks on (`hooks = true` under [features])."
  [home]
  (let [config (fs/path home ".codex" "config.toml")]
    (boolean (and (fs/exists? config) (re-find #"(?m)^\s*hooks\s*=\s*true" (slurp (str config)))))))

;; ------------------------------------------------------------------------------------------------ the command

(defn agent-cmd
  "`blackbox setup agent`: the skill and the session hook for each agent this Mac has."
  [{:keys [home] :as ctx}]
  (let [path (require-bin! ctx)
        bin (out/home-path path home)
        program (str/replace-first bin #"^~" "\\$HOME")
        command (str "\"" program "\" 2>/dev/null || true")
        text (skill-text bin)
        row (fn [agent part p result] (array-map :agent agent :part part :path (out/home-path p home) :result result))
        skip "skipped: not set up on this Mac"
        claude (fs/path home ".claude")
        codex (fs/path home ".codex")
        rows (concat
              (let [skill (fs/path claude "skills" "blackbox" "SKILL.md")
                    settings (fs/path claude "settings.json")]
                (if (fs/exists? claude)
                  [(row "claude" "skill" skill (skill! skill text))
                   (row "claude" "hook" settings (hook! settings command program))]
                  [(row "claude" "skill" skill skip) (row "claude" "hook" settings skip)]))
              (let [skill (fs/path home ".agents" "skills" "blackbox" "SKILL.md")
                    hooks (fs/path codex "hooks.json")]
                (if (fs/exists? codex)
                  [(row "codex" "skill" skill (skill! skill text))
                   (row "codex" "hook" hooks
                        (cond-> (hook! hooks command program)
                          (not (codex-hooks-on? home)) (str "; Codex runs it once [features] hooks = true")))]
                  [(row "codex" "skill" skill skip) (row "codex" "hook" hooks skip)])))
        kept (filter #(str/starts-with? (:result %) "left as it is: edited") rows)]
    [(array-map :bin bin :parts (vec rows))
     (concat
      (map #(str "To take the new skill, delete " (:path %) " (or its edits) and run `blackbox setup agent` again") kept)
      ["A new Claude Code or Codex session starts with `blackbox`'s home view"])]))
