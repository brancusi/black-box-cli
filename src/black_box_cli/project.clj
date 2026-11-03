(ns black-box-cli.project
  "Cut projects (docs/CONTRACT.md): a cut folder handed to the Black Box app on this Mac, which forwards it to
  the mini; the mini builds it as a new Resolve project (originals, the recorders' audio synced, the proxies linked,
  the timeline laid out). The CLI talks only to the local app (AGENTS.md rule 4) and never to Resolve (rule 5). The
  request is kept in the cut folder's build.json, written once (rule 3)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.app :as app]
   [black-box-cli.cuts :as cuts]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def record-file
  "The file in a cut folder that says it was sent to the mini, and its request."
  "build.json")

(def ^:private schema "black-box-cli.build/1")

(defn sent
  "What build.json in `folder` says ({:request :project :asked_at}), or nil when the cut was never sent."
  [folder]
  (let [f (fs/path folder record-file)]
    (when (fs/exists? f)
      (json/parse-string (slurp (str f)) true))))

(defn build!
  "Hands the cut in `folder` to Black Box and keeps its request in build.json: [{:request :state :project} nil], or
  [nil {:code :message}] with nothing written."
  [env folder]
  (let [[ans err] (app/ask env ["project" "build" "--cut" (str folder)])]
    (cond
      err [nil err]
      (str/blank? (str (:request ans))) [nil {:code "black-box-failed" :message "Black Box gave no request id"}]
      :else (do (cuts/write-new! (fs/path folder record-file)
                                 (str (json/generate-string (array-map :schema schema
                                                                       :request (str (:request ans))
                                                                       :project (:project ans)
                                                                       :asked_at (str (Instant/now)))
                                                            {:pretty true})
                                      "\n"))
                [(select-keys ans [:request :state :project]) nil]))))

(defn status
  "The `project` block of `edl show` for a cut sent as `record`: its name, request and state, with the report when
  it's done (its counts and notes as Black Box gives them; it may add keys) and the error ({code, message}) when it
  failed. When Black Box can't answer, the state says why."
  [env {:keys [request project]}]
  (let [[ans err] (app/ask env ["project" "status" request])
        failure (:error ans)]
    (if err
      (array-map :name project :request request :state (str "unknown: " (:message err)))
      (apply array-map (concat [:name (or (:project ans) project) :request request :state (:state ans)]
                               (when (:report ans) [:report (:report ans)])
                               (when failure [:error failure]))))))
