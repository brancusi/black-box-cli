(ns black-box-cli.support
  "Runs the command line in-process, as the shell would, against the fixture media_meta (invented clips and lines)
  and a fresh temporary folder for the cache and cuts. Never a real media_meta."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.main :as main]
   [clojure.data.json :as json]
   [clojure.string :as str])
  (:import
   (java.io StringReader StringWriter)))

(def fixture-meta
  "The invented media_meta the goldens were made from."
  (str (fs/absolutize "test/fixtures/media_meta")))

(def golden-dir "test/golden")

(defn cli*
  "Runs `args` with `opts` ({:env :home :stdin}): {:exit :out}."
  [opts args]
  (let [w (StringWriter.)
        exit (binding [*out* w
                       *in* (clojure.lang.LineNumberingPushbackReader. (StringReader. (or (:stdin opts) "")))]
               (main/run {:args args
                          :env (merge {"BLACKBOX_BIN" "~/.local/bin/blackbox" "BLACKBOX_REFRESH" "never"} (:env opts))
                          :home (or (:home opts) (str (fs/create-temp-dir {:prefix "bbcli-home-"})))}))]
    {:exit exit :out (str w)}))

(defn cli
  "Runs `args` against the fixture media_meta and a new temporary --local folder: {:exit :out :local}."
  [& args]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))]
    (assoc (cli* {} (concat ["--meta" fixture-meta "--local" local] args)) :local local)))

(defn normalise
  "Output written the way the goldens are: <local>, <meta>, ~, <bin>, <stamp>-, <now>, %<len>%."
  [text {:keys [local home]}]
  (cond-> text
    local (str/replace local "<local>")
    true (str/replace fixture-meta "<meta>")
    home (str/replace home "~")
    true (-> (str/replace #"(?m)^bin: .*$" "bin: <bin>")
             (str/replace #"%\d+%<meta>" "%<len>%<meta>")
             (str/replace #"\d{8}T\d{6}Z-" "<stamp>-")
             (str/replace #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z" "<now>"))))

(defn golden-steps
  "test/golden/steps.json: every step's argv, stdin, exit and expected output."
  []
  (let [{:strs [steps]} (json/read-str (slurp (str golden-dir "/steps.json")))]
    (for [{:strs [n argv stdin exit out]} steps]
      {:n n :argv argv :stdin stdin :exit exit :expected (slurp (str golden-dir "/" out))})))

(defn cut-files
  "The files of the one cut folder under `local`, normalised like the goldens, by name."
  [local home]
  (let [[folder & more] (fs/list-dir (fs/path local "cuts"))]
    (assert (and folder (empty? more)) "one cut folder")
    (into (sorted-map) (for [f (fs/list-dir folder)]
                         [(str (fs/file-name f)) (normalise (slurp (str f)) {:local local :home home})]))))

(defn golden-cut-files
  "test/golden/cut/: the files golden step 40 wrote, by name."
  []
  (into (sorted-map) (for [f (fs/list-dir (fs/path golden-dir "cut"))]
                       [(str (fs/file-name f)) (slurp (str f))])))
