(ns black-box-cli.edl
  "The `edl` commands: `edl new` turns a cut list into segments from this Mac's index and writes a cut folder
  (black-box-cli.cuts); `edl list` and `edl show` read cut folders. Rough cuts play proxies from media_meta only,
  never the NAS; a clip without one stays in cut.edl for Resolve."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.app :as app]
   [black-box-cli.archive :as archive]
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.picture :as picture]
   [black-box-cli.preview :as preview]
   [black-box-cli.project :as project]
   [black-box-cli.resolve :as resolve]
   [black-box-cli.shots :as shots]
   [black-box-cli.tc :as tc]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(def handle
  "Seconds added before a line run's first word and after its last, clamped to the file."
  0.25)

(def cut-text-limit
  "A clip's text in the command's output is cut here (--full for all of it)."
  80)

;; ------------------------------------------------------------------------------------------------ segments

(defn- words-of [cue]
  (json/parse-string (:words cue)))

(defn- segment
  "A segment of transcript `t` from `start` to `end`, playing its proxy when media_meta has one."
  [meta t m]
  (let [play (archive/proxy-of meta (:rel t))]
    (merge {:rel (:rel t)
            :source_name (:source_name t)
            :source_path (:source_path t)
            :source_sha256 (:source_sha256 t)
            :play_path play
            :proxy (some? play)
            :clock_kind (:clock_kind t)
            :start_tc (:start_tc t)
            :rate (:rate t)
            :tod_s (:tod_s t)
            :duration (:duration t)
            :from nil
            :to nil}
           m)))

(defn- same-text? [a b]
  (let [norm #(str/replace (str/trim (Normalizer/normalize (str %) Normalizer$Form/NFC)) #"\s+" " ")]
    (= (norm a) (norm b))))

(defn- lines-segment
  "A run of lines (`a`..`b`) as a segment: in and out from the words' own times plus the handles, clamped to the
  file. Returns [segment warning], segment nil when it has no length."
  [{:keys [meta path version]} {:keys [a b text note]}]
  (let [pa (ids/parse a)
        pb (ids/parse b)
        [a b] (map #(str (:clip %) "." (:cue %)) [pa pb])
        row (fn [{:keys [clip cue]}]
              (db/one path "SELECT c.transcript_id, c.n FROM cues c JOIN transcripts t ON t.id = c.transcript_id
                            WHERE t.version = :version AND t.clip = :clip AND c.n = :n;"
                      {:version version :clip clip :n cue}))
        ra (row pa)
        rb (row pb)
        missing (distinct (keep (fn [[id r]] (when-not r id)) [[a ra] [b rb]]))]
    (when (seq missing)
      (out/fail! "not-found" (str "no line " (str/join ", " missing) " in the index")))
    (when (not= (:transcript_id ra) (:transcript_id rb))
      (out/fail! "bad-clip" (str a " and " b " are in different files")))
    (when (> (:n ra) (:n rb))
      (out/fail! "bad-clip" (str a " comes after " b)))
    (let [t (db/one path "SELECT * FROM transcripts WHERE id = :t;" {:t (:transcript_id ra)})
          cues (db/query path "SELECT * FROM cues WHERE transcript_id = :t AND n BETWEEN :a AND :b ORDER BY n;"
                         {:t (:id t) :a (:n ra) :b (:n rb)})
          said (str/join " " (map :text cues))
          _ (when (and text (not (same-text? text said)))
              (out/fail! "changed" (str "the text of " (if (= a b) a (str a ".." b)) " has changed since the cut list"
                                        " was made; it now says " (cutlist/quoted said))))
          words (vec (for [q cues w (words-of q) :when (and (some? (second w)) (some? (nth w 2)))] w))
          start (max 0.0 (- (if (seq words) (second (first words)) (:start (first cues))) handle))
          end (+ (if (seq words) (nth (peek words) 2) (:end (last cues))) handle)
          end (min (or (:duration t) end) end)]
      (if (<= end start)
        [nil "no length, left out"]
        (let [s (segment meta t {:start (tc/round-to start 3) :end (tc/round-to end 3) :text said :why note
                                 :words words :from a :to b})]
          [s (when-not (:play_path s) (str "no proxy, " cuts/no-proxy))])))))

(defn- range-segment
  "File `t` (a transcript row) from `t-in` to `t-out` seconds, exactly; the words said inside it are its text and
  subtitles."
  [{:keys [meta path]} t t-in t-out note]
  (let [cues (db/query path "SELECT * FROM cues WHERE transcript_id = :t AND end > :a AND start < :b ORDER BY n;"
                       {:t (:id t) :a t-in :b t-out})
        per-cue (for [q cues]
                  (filterv #(and (some? (second %)) (<= t-in (second %)) (< (second %) t-out)) (words-of q)))
        s (segment meta t {:start (tc/round-to t-in 3) :end (tc/round-to t-out 3)
                           :text (str/join " " (remove str/blank? (map #(str/join " " (map first %)) per-cue)))
                           :why note
                           :words (vec (apply concat per-cue))})]
    [s (when-not (:play_path s) (str "no proxy, " cuts/no-proxy))]))

(defn proxy->file
  "The file a proxy's path or name stands for: media_meta/proxies/<rel>.proxy.mp4 is <rel>; anything else as it is."
  [file]
  (let [f (str/trim (str file))
        f (if-let [i (str/last-index-of f "/proxies/")] (subs f (+ i (count "/proxies/"))) f)]
    (str/replace f #"\.proxy\.[A-Za-z0-9]+$" "")))

(defn- file-segment
  "A file (or its proxy) from in to out, exactly."
  [{:keys [path opts] :as ctx} {:keys [file note] t-in-s :in t-out-s :out}]
  (let [t (archive/find-file path (select-keys opts [:transcripts]) (proxy->file file))
        t-in (cutlist/parse-time t-in-s)
        t-out (cutlist/parse-time t-out-s)
        dur (:duration t)]
    (when (<= t-out t-in)
      (out/fail! "bad-clip" (str "out " t-out-s " isn't after in " t-in-s)))
    (when (and dur (>= t-in dur))
      (out/fail! "bad-clip" (str "in " t-in-s " is past the end of " (:source_name t) " (" (tc/hms dur) ")")))
    (range-segment ctx t t-in (if dur (min t-out dur) t-out) note)))

(defn- shot-segment
  "A shot from the shot logs: its best moment, or all of it when `whole`. Its file's transcript gives the
  timecode cut.edl needs; the note says what the shot is unless the cut list gives one."
  [{:keys [path version home] :as ctx} {:keys [shot whole note]}]
  (let [r (shots/lookup path home shot)
        t (or (db/one path "SELECT * FROM transcripts WHERE version = :version AND rel = :rel;"
                      {:version version :rel (:rel r)})
              (out/fail! "not-found" (str "no transcript for " (:rel r) ", which cut.edl needs for its timecode")))
        [a b] (if whole [(:start r) (:end r)] (shots/best r))
        b (if (:duration t) (min b (:duration t)) b)]
    (when (<= b a)
      (out/fail! "bad-clip" (str (:id r) " has no length")))
    (range-segment ctx t a b (if (str/blank? note) (str (:id r) ": " (:action r)) note))))

(defn segments
  "The cut list's clips as segments, in order: {:segments :warnings :problems}. Any problem means nothing is
  written; each names its clip and line."
  [ctx items]
  (reduce (fn [acc [k it]]
            (let [where (if (pos? (:n it)) (str "clip " k " (line " (:n it) ")") (str "clip " k))]
              (try
                (let [[s warning] (case (:kind it)
                                    :lines (lines-segment ctx it)
                                    :shot (shot-segment ctx it)
                                    (file-segment ctx it))]
                  (cond-> acc
                    s (update :segments conj (merge s (select-keys it [:pre :post])))
                    warning (update :warnings conj (str where ": " warning))))
                (catch clojure.lang.ExceptionInfo e
                  (if-let [err (out/error-of e)]
                    (update acc :problems conj (str where ": " (:message err)))
                    (throw e))))))
          {:segments [] :warnings [] :problems []}
          (map-indexed (fn [i it] [(inc i) it]) items)))

;; ------------------------------------------------------------------------------------------------ playing

(def mpv-flags
  "mpv for a rough cut: a window about 70% of the screen at once, staying on the last frame."
  ["--autofit=70%" "--force-window=immediate" "--keep-open=yes"])

(def edit-video
  "An edit rendered to one video (proxies, 960×540, what's said as subtitles), kept in its folder."
  "edit.mp4")

(defn- render-edit!
  "The edit in `folder` as one video: edit.mp4 in its folder, copied there from the render cache the first time and
  kept. Black Box's old cuts folder is never written (its cuts play from the cache). {:path :pictures :audio-only};
  fails as preview/render! does."
  [{:keys [env] :as ctx} folder]
  (let [kept (fs/path folder edit-video)
        r (preview/render! {:env env :cache (:previews (index/paths ctx)) :folder folder
                            :index-path (:index (index/paths ctx)) :meta (:meta ctx)})]
    (cond
      (fs/exists? kept) (assoc r :path (str kept))
      (= (str (fs/parent folder)) (str (:old-cuts (index/paths ctx)))) r
      :else (do (fs/copy (:path r) kept) ; a new file: copy fails rather than replace one
                (assoc r :path (str kept))))))

(defn- play!
  "Renders the cut in `folder` to one file and opens it: in the Mac's default video app (each editor picks theirs in
  Finder), or in mpv when BLACKBOX_PLAYER is mpv; `playing` says how many recorder clips took a camera's picture
  and how many are audio only. When it can't be rendered, mpv plays cut.mpv.edl as before.
  {:playing :preview :why}."
  [{:keys [env] :as ctx} folder]
  (let [mpv (tools/find-tool env "mpv")]
    (try
      (let [{p :path :keys [pictures audio-only]}
            (render-edit! ctx folder)
            notes (cond-> []
                    (pos? pictures) (conj (str pictures " recorder clip" (when (> pictures 1) "s")
                                               " with a camera's picture"))
                    (pos? audio-only) (conj (str audio-only " audio only")))
            where (if (and mpv (= "mpv" (get env "BLACKBOX_PLAYER"))) "in mpv" "in the default video app")]
        (tools/spawn! (if (= "in mpv" where) (concat [mpv] mpv-flags [p]) ["/usr/bin/open" p]))
        {:playing (str "yes, " where (when (seq notes) (str " (" (str/join ", " notes) ")"))) :preview p})
      (catch clojure.lang.ExceptionInfo e
        (let [why (or (:message (out/error-of e)) (throw e))]
          (if mpv
            (do (tools/spawn! (concat [mpv] mpv-flags [(str "--sub-file=" (fs/path folder "cut.srt"))
                                                       (str (fs/path folder "cut.mpv.edl"))]))
                {:playing (str "yes, in mpv from cut.mpv.edl (not rendered: " why ")") :why why})
            {:playing (str "no: not rendered (" why ") and mpv isn't installed") :why why}))))))

(defn- watch-help [folder {:keys [playing preview]}]
  (cond
    preview (str "It's playing, rendered to one file; `open " (out/shell-quoted preview) "` plays it again")
    (some-> playing (str/starts-with? "yes")) "It's playing in mpv: space pauses, ←/→ seek 5 s, f full screen, q quits"
    :else (str "Run `mpv " (str/join " " mpv-flags) " --sub-file=" (out/shell-quoted (fs/path folder "cut.srt")) " "
               (out/shell-quoted (fs/path folder "cut.mpv.edl")) "` to watch it")))

;; ------------------------------------------------------------------------------------------------ commands

(defn- read-cutlist [arg]
  (if (= "-" arg)
    (slurp *in*)
    (let [p (fs/expand-home arg)]
      (if (fs/regular-file? p)
        (slurp (str p))
        (out/fail! "not-found" (str "no cut list at " arg) {:help ["Pass a file, or - to read it from stdin"]})))))

(defn- clip-row [n s full]
  (array-map :n n
             :file (:source_name s)
             :in (tc/hms (:start s))
             :out (tc/hms (:end s))
             :length (tc/round-to (cuts/length s) 1)
             :text (out/cut (:text s) full cut-text-limit)))

(defn- read-cut
  "The cut list in `args` as {:text :title :segments :warnings :version}, or error.code invalid-cutlist listing every
  problem."
  [{:keys [opts args] :as ctx} command]
  (let [path (index/require-index! ctx)
        version (archive/version-of opts)
        text (read-cutlist (:cutlist args))
        {:keys [title items problems]} (cutlist/parse text)
        title (or (:title opts) title)
        problems (cond-> problems
                   (and (empty? problems) (str/blank? title))
                   (conj "no title: add `title: …` to the cut list, or --title"))
        {:keys [segments warnings] more :problems} (segments (assoc ctx :path path :version version) items)
        problems (into problems more)]
    (when (seq problems)
      (out/fail! "invalid-cutlist" (str (count problems) " problem" (when (next problems) "s")
                                        " in the cut list; nothing was written")
                 {:problems problems
                  :help ["Fix them and run it again" (str "Run `blackbox " command " --help` for the cut list format")]}))
    {:text text :title title :segments segments :warnings warnings :version version}))

(defn- make-edit!
  "A new edit folder for the cut read from a cut list (read-cut), with its pictures for cut.html; edit.mp4 is
  rendered into it unless `render?` is false. [folder why], `why` saying why it wasn't rendered."
  [{:keys [env] :as ctx} {:keys [text title segments warnings version]} mode render?]
  (let [cache (:frames (index/paths ctx))
        pictures (vec (pmap (fn [s]
                              (let [last-t (max (:start s) (- (:end s) 0.05))]
                                (mapv #(some-> (picture/try-jpeg {:env env :cache cache :clip (ids/clip (:rel s))
                                                                  :proxy (:play_path s) :t % :width 320})
                                               picture/url)
                                      [(:start s) (/ (+ (:start s) (:end s)) 2) last-t])))
                            segments))
        folder (cuts/write! (:cuts (index/paths ctx))
                            {:title title
                             :pictures pictures
                             :scope (str "everything (" version ")")
                             :segments segments
                             :warnings warnings
                             :run (array-map :mode mode
                                             :cutlist (subs text 0 (min 20000 (count text))))})]
    [folder (when render?
              (try (render-edit! ctx folder) nil
                   (catch clojure.lang.ExceptionInfo e
                     (some-> (or (:message (out/error-of e)) (throw e))
                             (str/replace #"\[[^\]]* @ 0x[0-9a-f]+\] " "")))))]))

(defn- video-made [folder why]
  (cond (fs/exists? (fs/path folder edit-video)) edit-video
        why (str "not rendered: " why)
        :else "not rendered (--no-render; --play renders it)"))

(defn new-cmd
  "`blackbox edl new <cutlist|->`."
  [{:keys [home opts] :as ctx}]
  (let [{:keys [title segments warnings] :as cut} (read-cut ctx "edl new")
        length (tc/round-to (reduce + 0.0 (map cuts/length segments)) 3)
        rows (vec (map-indexed (fn [i s] (clip-row (inc i) s (:full opts))) segments))
        head [:title title
              :clips (count segments)
              :length (tc/hms length)
              :files (count (distinct (map :rel segments)))
              :warnings (if (seq warnings) warnings "none")]]
    (if (:dry-run opts)
      [(apply array-map (concat head [:written "nothing (--dry-run)" :cut rows]))
       ["Run the same without --dry-run to write the EDL"]]
      (let [[folder why] (make-edit! ctx cut "cut list" (not (or (:play opts) (:no-render opts))))
            id (str (fs/file-name folder))
            play (when (:play opts) (play! ctx folder))]
        [(apply array-map (concat head
                                  [:id id
                                   :folder (out/home-path folder home)
                                   :made (array-map :video (video-made folder why)
                                                    :mpv "cut.mpv.edl"
                                                    :resolve "cut.edl (CMX3600)"
                                                    :subtitles "cut.srt"
                                                    :page "cut.html"
                                                    :record "cut.json")]
                                  (when play [:playing (:playing play)])
                                  [:cut rows]))
         [(watch-help folder play)
          (str "Run `blackbox online " id "` to build it in Resolve from the source files, with pre- and post-roll")
          (str "Run `blackbox edl show " id "` to see it again")]]))))

(defn- edit-segments
  "A saved edit's clips as segments again, from this Mac's index (each file's timecode, rate and length), with
  the pre- and post-roll its cut list gave."
  [{:keys [meta opts] :as ctx} folder]
  (let [path (index/require-index! ctx)
        version (archive/version-of opts)
        doc (json/parse-string (slurp (str (fs/path folder "cut.json"))) true)
        found (map-indexed (fn [i cl]
                             (if-let [t (db/one path "SELECT * FROM transcripts WHERE version = :version AND rel = :rel;"
                                                {:version version :rel (:rel cl)})]
                               [(segment meta t (select-keys cl [:start :end :text :why :pre :post])) nil]
                               [nil (str "clip " (inc i) " (" (:source_name cl) "): not in this Mac's index")]))
                           (:clips doc))
        problems (vec (keep second found))]
    (when (seq problems)
      (out/fail! "invalid-edit" (str (count problems) " clip" (when (next problems) "s")
                                     " of the edit can't be found; nothing was written")
                 {:problems problems :help ["Run `blackbox index`, then run it again"]}))
    {:title (or (:title doc) (:idea doc)) :segments (mapv first found) :warnings []}))

(defn- online-row [n s full]
  (apply array-map (concat [:n n
                            :file (:source_name s)
                            :in (tc/hms (:start s))
                            :out (tc/hms (:end s))
                            :pre (:pre_s s)
                            :post (:post_s s)
                            :length (tc/round-to (cuts/length s) 1)]
                           (when full [:source_in (tc/source-tc s (:start s) (cuts/timeline-rate [s]))
                                       :original (:original s)]))))

(defn- online-help
  "What to do about `online`'s error `code`."
  [code edl]
  (case code
    "resolve-scripting-off" ["In Resolve: Preferences → System → General → External scripting using → Local, then run it again"]
    "resolve-not-ready" ["Open Resolve, wait until it shows the Project Manager, then run it again"]
    "no-originals" ["Mount the NAS on this Mac (Finder → Go → Connect to Server), or pass --originals <the folder that holds media/>"]
    "no-database" ["Pass --db with a database Resolve lists in its Project Manager, or leave it out for the current one"]
    "no-project" ["Open a project in Resolve, or pass --into new-project"]
    "no-resolve" ["Install DaVinci Resolve on this Mac"]
    [(str "Import " edl " in Resolve by hand meanwhile (File → Import → Timeline)")]))

(defn online-cmd
  "`blackbox online <edit|cutlist|->`: an edit taken back to its source files, each clip with its pre- and
  post-roll, as a new timeline in DaVinci Resolve on this Mac (the owner's decision, 2026-11-23). A cut list is
  saved as an edit first, as `edl new` saves it."
  [{:keys [home opts args] :as ctx}]
  (let [place (:into opts)
        _ (when-not (resolve/intos place)
            (out/fail! "usage" (str "--into " place " isn't a choice; it's one of auto, current, new-project")
                       {:help ["Run `blackbox online --help` for what each does"]}))
        handles {:pre (cutlist/parse-time (:pre opts)) :post (cutlist/parse-time (:post opts))}
        arg (:edit args)
        cut-list? (or (= "-" arg) (fs/regular-file? (fs/expand-home arg)))
        cut (when cut-list? (read-cut (assoc-in ctx [:args :cutlist] arg) "online"))
        edit (when-not cut-list? (cuts/find-cut (index/paths ctx) arg))
        {:keys [title segments warnings]} (or cut (edit-segments ctx edit))
        [segments short] (resolve/with-handles segments handles)
        originals (some-> (:originals opts) fs/expand-home str)
        segments (mapv #(assoc % :original (resolve/original % originals)) segments)
        unplaced (keep-indexed (fn [i s] (when (str/blank? (:original s))
                                           (str "clip " (inc i) " (" (:source_name s) "): its transcript has no "
                                                "source path, so Resolve can't find the original")))
                               segments)
        _ (when (seq unplaced)
            (out/fail! "invalid-cutlist" (str (count unplaced) " clip" (when (next unplaced) "s")
                                              " without an original; nothing was written")
                       {:problems (vec unplaced) :help ["Leave those clips out, or ask the owner why they have no source path"]}))
        _ (when (and edit (= (str (fs/parent edit)) (str (:old-cuts (index/paths ctx)))))
            (out/fail! "old-cut" (str (fs/file-name edit) " is in Black Box's old cuts folder, which this CLI never writes to")
                       {:help ["Make it again with `blackbox edl new` (`edl show` has its cut list), then online that"]}))
        [audio weak] (if (:no-audio opts)
                       [[] []]
                       (resolve/synced-audio (index/require-index! ctx) segments originals))
        warnings (-> (vec warnings) (into short) (into weak))
        heard (group-by :clip audio)
        rows (vec (map-indexed (fn [i s] (cond-> (online-row (inc i) s (:full opts))
                                           true (assoc :recorders (count (heard (inc i))))))
                               segments))
        length (tc/round-to (reduce + 0.0 (map cuts/length segments)) 3)
        head [:title title
              :clips (count segments)
              :length (tc/hms length)
              :handles (str "pre " (:pre opts) " s, post " (:post opts) " s (a clip's own pre/post first)")
              :synced_audio (cond
                              (:no-audio opts) "none (--no-audio)"
                              (seq audio) (str (count audio) " recorder clip" (when (next audio) "s") " from "
                                               (count (distinct (map :track audio))) " recorder"
                                               (when (next (distinct (map :track audio))) "s")
                                               ", each on its own tracks from A2, synced by the sync map")
                              :else "none: no recorder synced with these clips")
              :warnings (if (seq warnings) warnings "none")]]
    (if (:dry-run opts)
      [(apply array-map (concat head [:written "nothing, and Resolve wasn't asked (--dry-run)" :cut rows]))
       ["Run the same without --dry-run to build the timeline in Resolve"]]
      (let [folder (or edit (first (make-edit! ctx cut "cut list" (not (:no-render opts)))))
            id (str (fs/file-name folder))
            stamp (cuts/stamp)
            [edl-text _] (cuts/cmx3600 segments title)
            edl (fs/path folder (str "online-" stamp ".edl"))
            _ (cuts/write-new! edl edl-text)
            the-plan (resolve/plan segments {:edl edl :title title :id id :into place :db (:db opts)
                                             :proxies (not (:no-proxies opts)) :originals originals :audio audio
                                             :rate (cuts/timeline-rate segments)})
            plan-path (fs/path folder (str "online-" stamp "-plan.json"))
            _ (cuts/write-new! plan-path (json/generate-string the-plan {:pretty true}))
            [res err] (resolve/build! (:env ctx) plan-path)
            _ (cuts/write-new! (fs/path folder (str "online-" stamp ".json"))
                               (json/generate-string (or res {:error err}) {:pretty true}))]
        (when err
          (out/fail! (:code err) (str "Resolve didn't make the timeline for " id ": " (:message err))
                     {:help (online-help (:code err) (out/home-path edl home))}))
        [(apply array-map
                (concat head
                        [:resolve (array-map :project (:project res)
                                             :new_project (:created_project res)
                                             :database (:database res)
                                             :timeline (:timeline res)
                                             :clips (:clips res)
                                             :by (if (= "edl" (:method res)) "the EDL, imported" "frames, appended")
                                             :originals_imported (:imported res)
                                             :originals_reused (:reused res)
                                             :missing (if (seq (:missing res)) (:missing res) "none")
                                             :proxies_linked (:proxies_linked res)
                                             :audio_placed (str (:audio_placed res) " of " (:audio res))
                                             :started_resolve (:launched res))
                         :id id
                         :folder (out/home-path folder home)
                         :edl (str (fs/file-name edl))
                         :cut rows]))
         (cond-> [(str "The timeline \"" (:timeline res) "\" is open in Resolve, in the project \"" (:project res) "\"")]
           (not= "edl" (:method res))
           (conj (str (:edl_note res) ", so the clips were placed by frame instead (the same ins and outs)"))
           (seq (:missing res))
           (conj (str (count (:missing res)) " original" (when (next (:missing res)) "s")
                      " weren't found: mount the NAS, or pass --originals <the folder that holds media/>"))
           true (conj (str "Run `blackbox online " id "` again for another timeline (--pre/--post to change the roll)")))]))))

(defn list-cmd
  "`blackbox edl list`."
  [{:keys [home opts] :as ctx}]
  (let [rows (cuts/edls (index/paths ctx))]
    (if (empty? rows)
      [(array-map :count 0 :total 0 :result "no EDLs yet") ["Run `blackbox edl new <cutlist|->` to make one"]]
      (let [shown (take (:limit opts) rows)]
        [(array-map :count (count shown)
                    :total (count rows)
                    :edls (mapv (fn [r] (out/pick (array-map :id (:id r)
                                                             :title (:title r)
                                                             :clips (:clips r)
                                                             :length (:length r)
                                                             :made (:made r)
                                                             :folder (out/home-path (:folder r) home))
                                                  [:id :title :clips :length] opts))
                                shown))
         ["Run `blackbox edl show <id>` for its clips and files"]]))))

(defn build-cmd
  "`blackbox edl build <id>`: hands the cut to the Black Box app on this Mac, which asks the mini to build it as a
  new Resolve project. It doesn't wait for the build; `edl show` says how it's going."
  [{:keys [env args] :as ctx}]
  (let [folder (cuts/find-cut (index/paths ctx) (:id args))
        id (str (fs/file-name folder))
        _ (when (= (str (fs/parent folder)) (str (:old-cuts (index/paths ctx))))
            (out/fail! "old-cut" (str id " is in Black Box's old cuts folder, which this CLI never writes to")
                       {:help ["Make it again with `blackbox edl new` (`edl show` has its cut list under cut.json's run), then build that"]}))
        title (get (json/parse-string (slurp (str (fs/path folder "cut.json")))) "title")]
    (when-let [{:keys [request project]} (project/sent folder)]
      (out/fail! "already-sent" (str id " was already sent to the mini as " project " (request " request ")")
                 {:help [(str "Run `blackbox edl show " id "` for how its build is going")
                         "To build it again, make the cut again (`blackbox edl new`): each cut is built once"]}))
    (let [[{:keys [request state] :as ans} err] (project/build! env folder)]
      (when err
        (out/fail! (:code err) (str "the cut wasn't sent: " (:message err)) {:help (app/help-for (:code err) "`project build`")}))
      [(array-map :id id :title title :project (:project ans) :request request :state state)
       [(str "Run `blackbox edl show " id "` to see how the build goes")
        "When it's done, open the project from Resolve's \"Black Box cuts\" folder: on proxies, the audio synced"]])))

(defn show-cmd
  "`blackbox edl show <id>`; a cut sent to the mini shows its project's state."
  [{:keys [home env opts args] :as ctx}]
  (let [folder (cuts/find-cut (index/paths ctx) (:id args))
        doc (json/parse-string (slurp (str (fs/path folder "cut.json"))))
        rows (vec (map-indexed (fn [i cl]
                                 (array-map :n (inc i)
                                            :file (get cl "source_name")
                                            :in (get cl "source_in")
                                            :out (get cl "source_out")
                                            :length (tc/round-to (- (get cl "end") (get cl "start")) 1)
                                            :text (out/cut (get cl "text") (:full opts) cut-text-limit)))
                               (get doc "clips")))
        built (some->> (project/sent folder) (project/status env))
        play (when (:play opts) (play! ctx folder))]
    [(apply array-map (concat [:id (str (fs/file-name folder))
                               :title (get doc "title")
                               :made (get doc "made_at")
                               :length (tc/hms (get doc "length_s"))
                               :folder (out/home-path folder home)
                               :files (vec (sort (remove #(str/starts-with? % ".")
                                                         (map (comp str fs/file-name) (fs/list-dir folder)))))
                               :warnings (or (not-empty (get doc "warnings")) "none")]
                              (when built [:project built])
                              (when play [:playing (:playing play)])
                              [:count (count rows) :cut rows]))
     [(watch-help folder play)
      (str "Run `open " (out/shell-quoted (fs/path folder "cut.html")) "` for the cut as a page")]]))
