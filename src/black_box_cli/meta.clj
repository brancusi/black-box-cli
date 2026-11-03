(ns black-box-cli.meta
  "Finding Dropbox's `media_meta` on this Mac (docs/CONTRACT.md). Read-only: nothing here writes."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def folder-name "media_meta")

(def marker
  "The file Black Box keeps at the top of `media_meta`."
  ".media-mgmt-meta-root")

(defn candidates
  "Every `media_meta` with its marker at the top of a Dropbox in ~/Library/CloudStorage, sorted."
  [home]
  (let [cloud (fs/path home "Library" "CloudStorage")]
    (if-not (fs/directory? cloud)
      []
      (->> (fs/list-dir cloud)
           (filter #(str/starts-with? (str (fs/file-name %)) "Dropbox"))
           (map #(fs/path % folder-name))
           (filter #(fs/exists? (fs/path % marker)))
           (map str)
           sort
           vec))))

(defn locate
  "The `media_meta` to use: `--meta`, else BLACKBOX_META, else the one found in CloudStorage. nil when there is
  none. Paths are made absolute but never resolved through links."
  [{:keys [meta env home]}]
  (if-let [named (or meta (get env "BLACKBOX_META"))]
    (str (fs/absolutize named))
    (first (candidates home))))
