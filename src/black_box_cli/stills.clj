(ns black-box-cli.stills
  "Stills from the originals (docs/CONTRACT.md): `still new` names moments the way `frame` does (a frame id, a
  line id, or file@time) and asks the Black Box app on this Mac to have the mini render each from the original at
  its native resolution, in a named look. Nothing here reads an original (AGENTS.md rule 1): the mini does. Each
  request gets a new folder under <local>/stills, with request.json (written once) and the files Black Box brings
  back."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.app :as app]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.index :as index]
   [black-box-cli.look :as look]
   [black-box-cli.out :as out]
   [black-box-cli.tc :as tc]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def looks
  "The looks the mini renders (named presets kept on the mini, versioned there): neutral, the house look (a gentle,
  slightly warm grade from the camera's own conversion); filmic, neutral with a film curve, split toning and grain
  (Black Box 0.39.0); rec709, the camera maker's conversion only; log, the original's own picture, ungraded, for a
  colourist."
  ["neutral" "filmic" "rec709" "log"])

(def ^:private schema "black-box-cli.still/1")

(def ^:private request-file "request.json")

(defn parse-what
  "One moment as `still new` takes it: `<frame id | line id | file>[@time][=name][~white=x,y | ~gains=r,g,b]` →
  {:what :at :name :balance}, the balance as Black Box takes it ({:white [x y]} or {:gains [r g b]}), or with
  :bad-balance when its numbers are wrong."
  [s]
  (when-let [[_ what at name kind nums]
             (re-matches #"(.+?)(?:@([0-9][0-9:.]*))?(?:=([A-Za-z0-9][A-Za-z0-9._-]*))?(?:~(white|gains)=([^~]*))?"
                         (str/trim s))]
    (let [xs (when kind (mapv #(parse-double (str/trim %)) (str/split nums #",")))
          ok? (case kind
                "white" (and (= 2 (count xs)) (every? #(and % (<= 0.0 % 1.0)) xs))
                "gains" (and (= 3 (count xs)) (every? #(and % (<= 0.25 % 4.0)) xs))
                true)]
      (cond-> {:what what :at at :name name}
        (and kind ok?) (assoc :balance {(keyword kind) xs})
        (not ok?) (assoc :bad-balance (case kind
                                        "white" "~white=x,y takes two fractions of the frame, 0 to 1"
                                        "gains" "~gains=r,g,b takes three gains, 0.25 to 4"))))))

(defn- default-name
  "A still's file name without extension when none is given: the original's name and the time, e.g. C5274_40.000s."
  [{:keys [name t]}]
  (str (str/replace (str name) #"\.[^.]*$" "") "_" (format "%.3f" (double t)) "s"))

(defn- item
  "The still `s` names, or [nil problem]."
  [ctx path s]
  (let [{:keys [what at name balance bad-balance] :as w} (parse-what s)]
    (cond
      (nil? w) [nil (str s ": not a frame id, line id or file@time")]
      bad-balance [nil (str s ": " bad-balance)]
      (and at (not= :file (look/kind-of what))) [nil (str s ": a frame or line id has its own time; @time is for a file")]
      :else
      (try
        (let [t (look/target ctx path (cond-> {} at (assoc :at at)) what)]
          (if (str/blank? (str (:sha256 t)))
            [nil (str s ": media_meta doesn't say which original " (:rel t) " is (no sha256)")]
            [(cond-> (array-map :what what
                                :rel (:rel t)
                                :sha256 (:sha256 t)
                                :t (tc/round-to (double (:t t)) 3)
                                :source_name (:name t)
                                :tc (:tc t)
                                :name (or name (default-name t)))
               balance (assoc :balance balance))
             nil]))
        (catch clojure.lang.ExceptionInfo e
          (if-let [err (out/error-of e)] [nil (str s ": " (:message err))] (throw e)))))))

(defn- folders
  "Every stills folder with a readable request.json, newest first: [{:id :folder :doc}]."
  [ctx]
  (let [dir (:stills (index/paths ctx))]
    (->> (when (fs/directory? dir) (fs/list-dir dir))
         (keep (fn [f]
                 (let [r (fs/path f request-file)]
                   (when (fs/regular-file? r)
                     (when-let [doc (try (json/parse-string (slurp (str r)) true) (catch Exception _ nil))]
                       {:id (str (fs/file-name f)) :folder (str f) :doc doc})))))
         (sort-by :id #(compare %2 %1)))))

(defn- find-request [ctx id]
  (let [all (folders ctx)
        hits (or (some->> all (filter #(= id (:id %))) first vector)
                 (filter #(str/includes? (:id %) id) all))]
    (cond
      (= 1 (count hits)) (first hits)
      (empty? hits) (out/fail! "not-found" (str "no stills request " id) {:help ["Run `blackbox still list` for them"]})
      :else (out/fail! "ambiguous" (str id " names " (count hits) " requests: " (str/join ", " (map :id hits)))))))

(defn- files-for
  "The files Black Box brought back for the still named `name`, in `folder`, by name."
  [folder name]
  (->> (fs/glob folder (str name ".*"))
       (map str)
       (remove #(str/ends-with? % ".part"))
       sort
       vec))

;; ------------------------------------------------------------------------------------------------ commands

(defn- out-dir
  "The folder `--out` names, absolute, or a usage error when it's somewhere the CLI never writes: media_meta, the NAS
  or the originals' mounts (AGENTS.md rules 1 and 2)."
  [{:keys [meta home]} dir]
  (let [p (str (fs/absolutize (fs/expand-home (str/replace-first dir #"^~(?=/|$)" home))))
        under? (fn [root] (and root (let [r (str (fs/absolutize root))] (or (= p r) (str/starts-with? p (str r "/"))))))]
    (when (or (under? meta) (some under? ["/Volumes" "/VideoSource" "/AssetSource" "/VideoProxies"]))
      (out/fail! "usage" (str "--out " dir " is inside media_meta or the NAS, where nothing is ever written")
                 {:help ["Pass a folder of your own, e.g. --out ~/Desktop/stills"]}))
    (when (and (fs/exists? p) (not (fs/directory? p)))
      (out/fail! "usage" (str "--out " dir " is a file, not a folder")))
    p))

(defn new-cmd
  "`blackbox still new <what…> [--look L] [--tiff] [--out DIR]`."
  [{:keys [env home opts args] :as ctx}]
  (let [path (index/require-index! ctx)
        look-name (:look opts)
        _ (when-not (some #{look-name} looks)
            (out/fail! "usage" (str "--look " look-name " isn't a look; the looks are " (str/join ", " looks))
                       {:help ["Run `blackbox still new --help` for what each look is"]}))
        out (some->> (:out opts) (out-dir ctx))
        results (mapv #(item ctx path %) (:what args))
        problems (vec (keep second results))
        items (mapv first results)
        dupes (->> items (map :name) frequencies (keep (fn [[n c]] (when (> c 1) n))) sort)
        problems (cond-> problems
                   (and (= "log" look-name) (some :balance items))
                   (conj "a balance can't go with --look log, which is the original ungraded")
                   (seq dupes) (conj (str "two stills would have the same name: " (str/join ", " dupes)
                                          " (name them with =name)")))]
    (when (seq problems)
      (out/fail! "invalid-stills" (str (count problems) " problem" (when (next problems) "s") "; nothing was sent")
                 {:problems problems :help ["Fix them and run it again" "Run `blackbox still new --help` for the forms"]}))
    (let [dir (:stills (index/paths ctx))
          folder (fs/path dir (str (cuts/stamp) "-" look-name))
          _ (fs/create-dirs dir)
          _ (fs/create-dir folder)
          formats (if (:tiff opts) ["jpeg" "tiff16"] ["jpeg"])
          numbered (vec (map-indexed (fn [i it] (apply array-map :n (inc i) (mapcat identity it))) items))
          [ans err] (app/ask env ["still" "request" "--out" (str folder)]
                             (json/generate-string (array-map :schema schema :look look-name :formats formats
                                                              :items (mapv #(apply array-map
                                                                                   (mapcat (fn [k] (when (contains? % k) [k (% k)]))
                                                                                           [:n :rel :sha256 :t :name :balance]))
                                                                           numbered))))]
      (when (or err (str/blank? (str (:request ans))))
        (fs/delete-if-exists folder) ; ours, made a moment ago, still empty: nothing was asked
        (let [{:keys [code message]} (or err {:code "black-box-failed" :message "Black Box gave no request id"})
              new? (or (= "filmic" look-name) (some :balance items))]
          (out/fail! code (str "the stills weren't asked for: " message)
                     {:help (if (and new? (= "bad_request" code))
                              ;; an older Black Box turns down what 0.39.0 added before asking the mini
                              ["Update Black Box on this Mac to 0.39.0 or later (filmic and balance came then), then run it again"]
                              (app/help-for code (if new?
                                                   "`still request` with filmic or a balance (Black Box 0.39.0 or later)"
                                                   "`still request` (Black Box 0.37.0 or later)")))})))
      (cuts/write-new! (fs/path folder request-file)
                       (str (json/generate-string (array-map :schema schema
                                                             :request (str (:request ans))
                                                             :look look-name
                                                             :formats formats
                                                             :out out
                                                             :asked_at (str (Instant/now))
                                                             :items numbered)
                                                  {:pretty true})
                            "\n"))
      (let [id (str (fs/file-name folder))]
        [(array-map :id id
                    :look look-name
                    :request (str (:request ans))
                    :state (:state ans)
                    :folder (out/home-path folder home)
                    :out (if out (out/home-path out home) "none (they stay in folder)")
                    :stills (mapv #(array-map :n (:n %) :name (:name %) :file (:source_name %) :at (tc/hms (:t %))
                                              :tc (:tc %))
                                  numbered))
         [(str "Run `blackbox still show " id " --wait` (in the background) to be told when they're done, with their paths")
          (str "Run `blackbox still show " id "` to look now")]]))))

(defn- same-bytes? [a b]
  (and (= (fs/size a) (fs/size b))
       (java.util.Arrays/equals ^bytes (fs/read-all-bytes a) ^bytes (fs/read-all-bytes b))))

(defn- copy-out!
  "Copies `files` into `out`, each created new (a .part renamed into place, never over a file): what happened, as a
  string for the still's row. A file already there with the same bytes counts as copied."
  [files out home]
  (fs/create-dirs out)
  (str/join ", "
            (for [f files
                  :let [to (fs/path out (fs/file-name f))
                        part (fs/path out (str (fs/file-name f) ".part"))]]
              (cond
                (and (fs/exists? to) (same-bytes? f to)) (out/home-path to home)
                (fs/exists? to) (str (fs/file-name f) " is already in --out and differs: not copied")
                :else (try (fs/delete-if-exists part)
                           (fs/copy f part)
                           (fs/move part to)
                           (out/home-path to home)
                           (catch java.nio.file.FileAlreadyExistsException _
                             (fs/delete-if-exists part)
                             (str (fs/file-name f) " appeared in --out meanwhile: not copied")))))))

(defn- balance-words
  "The gains Black Box used for a still's balance, as `r g b`, or \"\" when it had none."
  [b]
  (let [gains (if (map? b) (:gains b) b)]
    (if (sequential? gains) (str/join " " (map #(format "%.3f" (double %)) gains)) "")))

(def ^:private final-states #{"done" "failed"})

(defn- snapshot
  "The request now: Black Box's status, a row per still (copied into --out when done), and whether it's over."
  [env home folder doc]
  (let [[ans err] (app/ask env ["still" "status" (:request doc)])
        by-n (into {} (map (juxt :n identity)) (:items ans))
        rows (mapv (fn [{:keys [n name source_name t]}]
                     (let [files (files-for folder name)
                           theirs (by-n n)
                           ;; Black Box's word first: a still is done once all its files are here (a TIFF may still
                           ;; be fetching after the JPEG); without an answer, the files say
                           state (cond (and theirs (not= "done" (:state theirs))) (:state theirs)
                                       (seq files) "done"
                                       theirs (:state theirs)
                                       :else "waiting")]
                       (apply array-map
                              (concat [:n n
                                       :name name
                                       :file source_name
                                       :at (tc/hms t)
                                       :state state
                                       :size (if (and (:width theirs) (:height theirs))
                                               (str (:width theirs) "x" (:height theirs))
                                               "")
                                       :camera (str (:camera theirs))
                                       :input (str (:input theirs))
                                       :balance (balance-words (:balance theirs))
                                       :flags (str/join ", " (:flags theirs))
                                       :paths (if (seq files) (str/join ", " (map #(out/home-path % home) files)) "")]
                                      (when (:out doc)
                                        [:copied (if (seq files) (copy-out! files (:out doc) home) "")])
                                      [:error (let [e (:error theirs)]
                                                (cond (map? e) (str (:code e) ": " (:message e))
                                                      e (str e)
                                                      :else ""))]))))
                   (:items doc))]
    {:ans ans :err err :rows rows
     :over (or (every? #(final-states (:state %)) rows)
               (= "failed" (:state ans))
               (#{"no-black-box" "black-box-too-old"} (:code err)))}))

(defn- waited-words [ms]
  (let [s (quot ms 1000)]
    (if (< s 60) (str s "s") (str (quot s 60) "m " (mod s 60) "s"))))

(defn show-cmd
  "`blackbox still show <id> [--wait [--timeout M]]`: the request's state from Black Box and the files that have come
  back, copied into --out when the request named one. With --wait it asks again every few seconds until every
  still is done or failed, or the timeout (minutes) passes: for an agent to run in the background and be told."
  [{:keys [env home opts args] :as ctx}]
  (let [{:keys [id folder doc]} (find-request ctx (:id args))
        poll-ms (long (* 1000 (or (some-> (get env "BLACKBOX_STILL_POLL_S") parse-double) 10.0)))
        started (System/currentTimeMillis)
        deadline (+ started (* 60000 (:timeout opts)))
        {:keys [ans err rows over]} (loop []
                                      (let [snap (snapshot env home folder doc)]
                                        (if (or (not (:wait opts)) (:over snap)
                                                (>= (+ (System/currentTimeMillis) poll-ms) deadline))
                                          snap
                                          (do (Thread/sleep poll-ms) (recur)))))
        done (count (filter #(= "done" (:state %)) rows))
        versions (distinct (keep :look_version (:items ans)))]
    [(apply array-map
            (concat [:id id
                     :look (:look doc)
                     :request (:request doc)
                     :state (if err (str "unknown: " (:message err)) (:state ans))
                     :done (str done " of " (count rows))
                     :look_version (if (seq versions) (str/join ", " versions) "")
                     :renderer (str (:renderer ans))]
                    (when (:wait opts)
                      [:waited (str (waited-words (- (System/currentTimeMillis) started))
                                    (when-not over (str ", gave up after " (:timeout opts) "m")))])
                    [:folder (out/home-path folder home)]
                    (when (:out doc) [:out (out/home-path (:out doc) home)])
                    [:stills rows]))
     (cond-> [(cond
                (= done (count rows)) (str "Run `open " (out/shell-quoted (or (:out doc) folder)) "` to see them")
                over "Some stills failed: their `error` says why"
                :else (str "Run `blackbox still show " id " --wait` again: the mini is still rendering"))]
       (some #(str/includes? (:flags %) "not_camera_original") rows)
       (conj (str "A still flagged not_camera_original came from an edited export, not a camera: "
                  "`blackbox like <frame id>` may find the camera's own clip")))]))

(defn list-cmd
  "`blackbox still list`: stills requests made on this Mac, newest first."
  [{:keys [home opts] :as ctx}]
  (let [all (folders ctx)]
    (if (empty? all)
      [(array-map :count 0 :total 0 :result "no stills asked for yet")
       ["Run `blackbox still new <frame id | line id | file@time>…` to ask for some"]]
      (let [shown (take (:limit opts) all)]
        [(array-map :count (count shown)
                    :total (count all)
                    :requests (mapv (fn [{:keys [id folder doc]}]
                                      (array-map :id id :look (:look doc) :stills (count (:items doc))
                                                 :folder (out/home-path folder home)))
                                    shown))
         ["Run `blackbox still show <id>` for one request's files"]]))))
