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

(defn- file-segment
  "A file from in to out, exactly."
  [{:keys [path opts] :as ctx} {:keys [file note] t-in-s :in t-out-s :out}]
  (let [t (archive/find-file path (select-keys opts [:transcripts]) file)
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
                    s (update :segments conj s)
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

(defn- play!
  "Renders the cut in `folder` to one file and opens it: in the Mac's default video app (each editor picks theirs in
  Finder), or in mpv when BLACKBOX_PLAYER is mpv; `playing` says how many recorder clips took a camera's picture
  and how many are audio only. When it can't be rendered, mpv plays cut.mpv.edl as before.
  {:playing :preview :why}."
  [{:keys [env] :as ctx} folder]
  (let [mpv (tools/find-tool env "mpv")]
    (try
      (let [{p :path :keys [pictures audio-only]}
            (preview/render! {:env env :cache (:previews (index/paths ctx)) :folder folder
                              :index-path (:index (index/paths ctx)) :meta (:meta ctx)})
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

(defn new-cmd
  "`blackbox edl new <cutlist|->`."
  [{:keys [home env opts args] :as ctx}]
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
                  :help ["Fix them and run it again" "Run `blackbox edl new --help` for the cut list format"]}))
    (let [length (tc/round-to (reduce + 0.0 (map cuts/length segments)) 3)
          rows (vec (map-indexed (fn [i s] (clip-row (inc i) s (:full opts))) segments))
          head [:title title
                :clips (count segments)
                :length (tc/hms length)
                :files (count (distinct (map :rel segments)))
                :warnings (if (seq warnings) warnings "none")]]
      (if (:dry-run opts)
        [(apply array-map (concat head [:written "nothing (--dry-run)" :cut rows]))
         ["Run the same without --dry-run to write the EDL"]]
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
                                   :run (array-map :mode "cut list"
                                                   :cutlist (subs text 0 (min 20000 (count text))))})
              id (str (fs/file-name folder))
              play (when (:play opts) (play! ctx folder))]
          [(apply array-map (concat head
                                    [:id id
                                     :folder (out/home-path folder home)
                                     :made (array-map :mpv "cut.mpv.edl"
                                                      :resolve "cut.edl (CMX3600)"
                                                      :subtitles "cut.srt"
                                                      :page "cut.html"
                                                      :record "cut.json")]
                                    (when play [:playing (:playing play)])
                                    [:cut rows]))
           [(watch-help folder play)
            (str "Import " (out/home-path (fs/path folder "cut.edl") home) " in Resolve (File → Import → Timeline)")
            (str "Run `blackbox edl show " id "` to see it again")]])))))

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
        _ (when-not (= (str (fs/parent folder)) (str (:cuts (index/paths ctx))))
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
