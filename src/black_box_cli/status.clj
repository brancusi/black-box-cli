(ns black-box-cli.status
  "The index's state: building it (`index`), the record of the last build (`indexed.json` beside it, a cache file
  like the index), what media_meta has that the index hasn't read yet, its health (`status` and the home view),
  and keeping it up to date: a command that reads the index first brings it up to date when media_meta has
  changed. One build at a time on a Mac, by a lock beside the index."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.archive :as archive]
   [black-box-cli.db :as db]
   [black-box-cli.frames :as frames]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.people :as people]
   [black-box-cli.prints :as prints]
   [black-box-cli.shots :as shots]
   [black-box-cli.sync :as sync]
   [black-box-cli.version :as version]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Duration Instant)
   (java.time.temporal ChronoUnit)))

(def refresh-after-s
  "A command checks media_meta for changes at most this often (seconds since the last build)."
  60)

;; ------------------------------------------------------------------------------------------------ the record

(defn record-path
  "The record of the last build, beside the index at `index-path`."
  [index-path]
  (str (fs/path (fs/parent index-path) "indexed.json")))

(defn read-record
  "The record of the last build, or nil."
  [index-path]
  (let [p (record-path index-path)]
    (when (fs/exists? p)
      (try (json/parse-string (slurp p) true) (catch Exception _ nil)))))

(defn- write-record! [index-path m]
  (let [p (record-path index-path)
        tmp (str p ".new-" (.pid (java.lang.ProcessHandle/current)))]
    (spit tmp (json/generate-string m))
    (fs/move tmp p {:replace-existing true :atomic-move true})))

;; ------------------------------------------------------------------------------------------------ the lock

(defn- alive? [pid]
  (let [o (java.lang.ProcessHandle/of pid)]
    (and (.isPresent o) (.isAlive ^java.lang.ProcessHandle (.get o)))))

(defn- take-lock!
  "Creates the lock file with this process's id; true when it did. A lock whose process has gone is taken over."
  [lock]
  (try
    (fs/create-file lock)
    (spit (str lock) (str (.pid (java.lang.ProcessHandle/current))))
    true
    (catch java.nio.file.FileAlreadyExistsException _
      (let [pid (some-> (try (slurp (str lock)) (catch Exception _ nil)) str/trim parse-long)]
        (when (and pid (not (alive? pid)))
          (fs/delete-if-exists lock)
          (take-lock! lock))))))

(defn- with-lock
  "Runs `f` holding the build lock beside `index-path` (a file with the builder's process id), waiting for it when
  `wait`; nil without running `f` when another build holds it and `wait` is false."
  [index-path wait f]
  (fs/create-dirs (fs/parent index-path))
  (let [lock (fs/path (fs/parent index-path) ".index.lock")]
    (when (loop []
            (cond (take-lock! lock) true
                  wait (do (Thread/sleep 250) (recur))
                  :else false))
      (try (f) (finally (fs/delete-if-exists lock))))))

;; ------------------------------------------------------------------------------------------------ building

(defn- index-result
  "Builds every part of the index and returns what `index` prints."
  [{:keys [meta home opts progress] :as ctx}]
  (let [path (:index (index/paths ctx))
        args {:meta meta :index-path path :rebuild (:rebuild opts) :progress progress}
        stage (fn [k f] (when progress (progress {:stage k :state "start"}))
                (let [r (f)] (when progress (progress {:stage k :state "done"})) r))
        res (stage "transcripts" #(index/build! (assoc args :version (archive/version-of opts))))
        pics (stage "frames" #(frames/build! args))
        names (stage "people" #(people/build! args))
        voices (stage "prints" #(prints/build! args))
        synced (stage "sync" #(sync/build! args))
        logged (stage "shots" #(shots/build! args))]
    (array-map :index (out/home-path path home)
               :transcripts (archive/version-of opts)
               :added (:added res)
               :changed (:changed res)
               :unchanged (:unchanged res)
               :removed (:removed res)
               :skipped (:skipped res)
               :lines_added (:lines_added res)
               :frames (if pics
                         (array-map :added (:added pics)
                                    :changed (:changed pics)
                                    :unchanged (:unchanged pics)
                                    :removed (:removed pics)
                                    :skipped (:skipped pics)
                                    :total (:frames pics))
                         "none: media_meta has no embeddings folder")
               :people (or names "none: media_meta has no people folder")
               :prints (or voices "none: media_meta has no prints folder")
               :sync (or synced "none: media_meta has no sync folder")
               :shots (or logged "none: media_meta has no shots folder"))))

(defn build!
  "Builds the index under the lock (waiting for another build to finish when `wait`), records it, and returns what
  `index` prints; nil when another build held the lock and `wait` is false."
  [ctx wait]
  (let [path (:index (index/paths ctx))]
    (with-lock path wait
      (fn []
        (let [t0 (System/nanoTime)
              m (index-result ctx)]
          (write-record! path {:schema "black-box-cli.indexed/1"
                               :at (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS))
                               :took_s (/ (Math/round (/ (- (System/nanoTime) t0) 1e8)) 10.0)
                               :cli version/version
                               :result m})
          m)))))

(defn index-cmd
  "`blackbox index [--rebuild] [--progress]`."
  [{:keys [opts] :as ctx}]
  (let [m (build! (cond-> ctx (:progress opts) (assoc :progress out/progress!)) true)
        res m
        pics (:frames m)
        skipped? (some #(and (map? %) (pos? (:skipped % 0))) [pics (:prints m) (:sync m) (:shots m)])]
    [m
     (cond-> []
       (or (pos? (:skipped res)) skipped?)
       (conj "Skipped files are online-only or not transcripts; make media_meta available offline in Finder")
       true (conj "Run `blackbox` for what the index holds"))]))

;; ------------------------------------------------------------------------------------------------ what changed

(defn- sources
  "The parts of media_meta the index reads: name, folder, and whether Black Box has published it."
  [meta opts]
  (let [p (fn [& xs] (apply fs/path meta xs))]
    [{:source "transcripts" :dir (p "transcripts" (archive/version-of opts))
      :published (fs/directory? (p "transcripts" (archive/version-of opts)))}
     {:source "frames" :dir (p "embeddings" frames/label) :published (fs/exists? (p "embeddings" frames/marker))}
     {:source "people" :dir (p "people") :published (fs/exists? (p "people" people/marker))}
     {:source "prints" :dir (p "prints") :published (fs/exists? (p "prints" prints/marker))}
     {:source "sync" :dir (p "sync") :published (fs/exists? (p "sync" sync/marker))}
     {:source "shots" :dir (p "shots" shots/version) :published (fs/exists? (p "shots" shots/marker))}]))

(defn- count-files
  "How many json files under `dir` match the `find` tests in `more`."
  [dir & more]
  (if-not (fs/directory? dir)
    0
    (let [{:keys [exit out]} (apply process/sh "find" (str dir) "-type" "f" "(" "-name" "*.json" "-o" "-name" "*.jsonl" ")"
                                    more)]
      (if (zero? exit) (count (remove str/blank? (str/split-lines out))) 0))))

(defn- scan
  "Each source with `pending` (files Dropbox has brought in or changed since the last build, or nil without a
  record) and `offline` (files Dropbox keeps online-only, which the index can't read)."
  [meta opts index-path]
  (let [record (record-path index-path)
        recorded (fs/exists? record)]
    (mapv (fn [{:keys [dir published] :as s}]
            (assoc s
                   :pending (when recorded (if published (count-files dir "-cnewer" record) 0))
                   :offline (if published (count-files dir "-flags" "+dataless") 0)))
          (sources meta opts))))

;; ------------------------------------------------------------------------------------------------ keeping it up to date

(defn- age-s [record]
  (some-> (:at record) Instant/parse (Duration/between (Instant/now)) .getSeconds))

(defn refresh!
  "Brings the index up to date before a command reads it: built when it's missing, or when it's over a minute old
  and media_meta has changed since. Quiet; a refresh another process is already doing isn't waited for. Never
  with BLACKBOX_REFRESH=never (Black Box, when it keeps the index itself; the tests)."
  [{:keys [env meta opts] :as ctx}]
  (when (and meta (not= "never" (get env "BLACKBOX_REFRESH")))
    (let [path (:index (index/paths ctx))
          record (read-record path)
          missing (not (fs/exists? path))
          ctx (assoc ctx :opts (select-keys opts [:transcripts]))]
      (cond
        missing (build! ctx true)

        (nil? record) (build! ctx false)

        (< (or (age-s record) 0) refresh-after-s) nil

        (some #(pos? (or (:pending %) 0)) (scan meta opts path))
        (try (build! ctx false)
             (catch clojure.lang.ExceptionInfo _ nil)))))) ; a failed refresh leaves the index as it was

;; ------------------------------------------------------------------------------------------------ health

(defn- broken?
  "Whether the index can't be read, or is of another schema (the next build makes it new)."
  [path]
  (try (not= (str index/schema-version)
             (:value (db/one path "SELECT value FROM meta WHERE key = 'schema_version';")))
       (catch clojure.lang.ExceptionInfo _ true)))

(defn- holds
  "What the record says one source holds, in words."
  [result source]
  (let [v (get result (keyword source))
        n (fn [m & ks] (reduce + (map #(or (get m %) 0) ks)))]
    (cond
      (= "transcripts" source) (str (n result :added :changed :unchanged) " files")
      (string? v) v
      (nil? v) "not indexed yet"
      (= "frames" source) (str (:total v) " frames in " (n v :added :changed :unchanged) " files")
      (= "people" source) (str (:people v) " people, " (:names v) " names")
      (= "prints" source) (str (:voices v) " voices, " (:faces v) " faces")
      (= "sync" source) (str (:days v) " days, " (:pairs v) " pairs")
      (= "shots" source) (str (:shots v) " shots in " (:clips v) " clips")
      :else "")))

(defn age-words
  "Seconds as `just now`, `12 min ago`, `3 h ago` or `2 days ago`."
  [s]
  (cond (nil? s) nil
        (< s 60) "just now"
        (< s 3600) (str (quot s 60) " min ago")
        (< s 172800) (str (quot s 3600) " h ago")
        :else (str (quot s 86400) " days ago")))

(defn state
  "The index's health and what's behind it: {:health :record :sources}. Health is `not built`, `broken` (run
  `blackbox index --rebuild`), `stale` (media_meta has files the index hasn't read; the next command or `blackbox
  index` reads them), `incomplete` (Dropbox keeps some files online-only), or `ok`."
  [{:keys [meta opts] :as ctx}]
  (let [path (:index (index/paths ctx))
        record (read-record path)
        scanned (when meta (scan meta opts path))
        rows (mapv (fn [{:keys [source published pending offline]}]
                     (array-map :source source
                                :state (cond (not published) "none"
                                             (nil? record) "unknown"
                                             (pos? (or pending 0)) "stale"
                                             (pos? offline) "incomplete"
                                             :else "ok")
                                :holds (if record (holds (:result record) source) "unknown")
                                :pending (or pending 0)
                                :offline offline))
                   scanned)]
    {:record record
     :sources rows
     :health (cond (not meta) "no media_meta"
                   (not (fs/exists? path)) "not built"
                   (broken? path) "broken"
                   (or (nil? record) (some #(= "stale" (:state %)) rows)) "stale"
                   (some #(= "incomplete" (:state %)) rows) "incomplete"
                   :else "ok")}))

(defn status-cmd
  "`blackbox status`: the index's health, when it was last built, and each part of media_meta it reads."
  [{:keys [meta home] :as ctx}]
  (let [path (:index (index/paths ctx))
        {:keys [health record sources]} (state ctx)
        pending (reduce + (map :pending sources))
        offline (reduce + (map :offline sources))]
    [(array-map :health health
                :index (out/home-path path home)
                :indexed_at (or (:at record) (if (fs/exists? path) "unknown (before 0.1.3)" "never"))
                :age (or (age-words (age-s record)) "unknown")
                :took_s (:took_s record)
                :by (:cli record)
                :media_meta (if meta (out/home-path meta home) "not found")
                :pending pending
                :offline offline
                :sources (if (seq sources) sources "none: no media_meta"))
     (case health
       "ok" ["Run `blackbox` for what the index holds"]
       "stale" [(str "Run `blackbox index` to read " (if (pos? pending) (str pending " new or changed files") "media_meta")
                     " now (commands also do it themselves)")]
       "incomplete" [(str offline " files in media_meta are online-only: in Finder, make media_meta available"
                          " offline (right-click → Make Available Offline), then run `blackbox index`")]
       "broken" ["Run `blackbox index --rebuild` to build the index again"]
       "not built" ["Run `blackbox index` to build it (the first build takes a minute or two)"]
       ["Pass --meta <the media_meta folder>, or make Dropbox's media_meta available on this Mac"])]))
