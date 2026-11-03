(ns black-box-cli.cuts
  "Cut folders on this Mac: writing one (cuts.write_cut: cut.mpv.edl, cut.edl, cut.srt, cut.json, cut.html) and
  reading the ones this CLI wrote and, read only, the ones Black Box wrote before this CLI (docs/DESIGN.md,
  decision 3). A cut is a vector of segments, maps of :rel :source_name :source_path :source_sha256 :play_path
  :proxy :start :end :clock_kind :start_tc :rate :tod_s :text :why :words :from :to."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.out :as out]
   [black-box-cli.tc :as tc]
   [black-box-cli.toon :as toon]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.nio.file Files OpenOption StandardOpenOption)
   (java.text Normalizer Normalizer$Form)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

(def schema
  "cut.json's schema: Black Box's, so either can read the other's cuts."
  "media-mgmt.cut/1")

(def no-proxy
  "Why a clip without a proxy is missing from the rough cut: it plays proxies only, never the NAS."
  "left out of the rough cut (cut.edl keeps it for Resolve)")

(defn length
  "A segment's length in seconds."
  [{:keys [start end]}]
  (- end start))

;; ------------------------------------------------------------------------------------------------ the files

(defn mpv-edl
  "mpv's own EDL: each playable segment as file,start,length, the path length-prefixed so commas are safe."
  [segments]
  (str (str/join "\n" (into ["# mpv EDL v0"]
                            (for [s segments :when (:play_path s)]
                              (str "%" (count (.getBytes ^String (:play_path s) StandardCharsets/UTF_8)) "%"
                                   (:play_path s) "," (tc/fixed (:start s) 3) "," (tc/fixed (length s) 3)))))
       "\n"))

(defn timeline-rate
  "The cut's frame rate: its first clip with one, else 24000/1001."
  [segments]
  (or (some (comp tc/parse-rate :rate) segments) tc/default-rate))

(defn- stem [name]
  (let [i (str/last-index-of name ".")]
    (if (and i (pos? i)) (subs name 0 i) name)))

(defn cmx3600
  "A CMX3600 EDL for Resolve: source timecode in, record timecode out from 01:00:00:00, the clip's file name on
  each event. Returns [text warnings]."
  [segments title]
  (let [rate (timeline-rate segments)
        title (or (not-empty (let [t (str/replace title #"[^\x20-\x7e]" "")] (subs t 0 (min 70 (count t))))) "Cut")
        frames (fn [secs r] (long (Math/rint (* secs (tc/rate-value r)))))
        events (loop [[s & more :as ss] segments i 1 rec (tc/tc->frames "01:00:00:00" rate) out []]
                 (if (empty? ss)
                   out
                   (let [r (tc/parse-rate (:rate s))
                         src-rate (or r rate)
                         src-in (tc/source-tc s (:start s) src-rate)
                         src-out (tc/frames->tc (+ (tc/tc->frames src-in src-rate) (frames (length s) src-rate)) src-rate)
                         rec-len (frames (length s) rate)
                         reel (or (not-empty (let [x (str/replace (stem (:source_name s)) #"[^A-Za-z0-9_]" "")]
                                               (subs x 0 (min 8 (count x)))))
                                  "AX")
                         why (some-> (:why s) not-empty (str/replace #"\s+" " "))]
                     (recur more (inc i) (+ rec rec-len)
                            (into out (cond-> [(format "%03d  %-8s %-5s C        %s %s %s %s" i reel (if r "AA/V" "AA")
                                                       src-in src-out (tc/frames->tc rec rate)
                                                       (tc/frames->tc (+ rec rec-len) rate))
                                               (str "* FROM CLIP NAME: " (:source_name s))]
                                        why (conj (str "* COMMENT: " (subs why 0 (min 200 (count why)))))
                                        true (conj "")))))))]
    [(str/join "\n" (concat [(str "TITLE: " title) "FCM: NON-DROP FRAME" ""] events))
     (if (some #(when-let [r (tc/parse-rate (:rate %))] (not= r rate)) segments)
       [(str "clips at more than one frame rate; the EDL records at " (tc/fixed (tc/rate-value rate) 3) " fps")]
       [])]))

(defn srt-time
  "Seconds as an SRT time, hh:mm:ss,mmm."
  [t]
  (let [ms (long (Math/rint (* t 1000)))]
    (format "%02d:%02d:%02d,%03d" (quot ms 3600000) (rem (quot ms 60000) 60) (rem (quot ms 1000) 60) (rem ms 1000))))

(defn- srt-entries
  "One segment's subtitles on the cut's timeline from `offset`: a run of words up to a sentence's end or 12 words."
  [s offset]
  (let [groups (loop [[w & more :as ws] (:words s) group [] out []]
                 (cond
                   (empty? ws) (cond-> out (seq group) (conj group))
                   :else (let [group (conj group w)]
                           (if (or (re-find #"[.?!]$" (str (first w))) (>= (count group) 12))
                             (recur more [] (conj out group))
                             (recur more group out)))))]
    (for [g groups
          :let [a (max (second (first g)) (:start s))
                b (min (or (nth (last g) 2) (second (last g))) (:end s))]
          :when (> b a)]
      [(+ offset (- a (:start s))) (+ offset (- b (:start s))) (str/join " " (map first g))])))

(defn srt
  "What's said, timed on the cut's own timeline (mpv --sub-file); clips left out of the rough cut are left out."
  [segments]
  (let [playable (filter :play_path segments)
        offsets (reductions + 0.0 (map length playable))
        entries (mapcat srt-entries playable offsets)]
    (apply str (map-indexed (fn [i [a b text]] (str (inc i) "\n" (srt-time a) " --> " (srt-time b) "\n" text "\n\n"))
                            entries))))

(defn- esc
  "Python's html.escape: & < > \" '."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

(def ^:private page-css
  "body{font:14px/1.4 -apple-system,Helvetica,sans-serif;margin:24px;background:#1d1f22;color:#e4e4e4}
h1{font-size:20px;margin:0 0 4px}h2{font-size:14px;margin:24px 0 8px;color:#9aa0a6;font-weight:600}
p.sub{color:#9aa0a6;margin:0 0 16px}table{border-collapse:collapse;width:100%}
td{vertical-align:top;padding:6px 10px 6px 0;border-top:1px solid #34373b}td.t{white-space:nowrap;color:#9aa0a6}
.why{color:#9aa0a6;font-size:12px;margin-top:4px}.cap{color:#6b7075;font-size:11px}")

(defn- page-hms [t]
  (let [t (max 0.0 (double t))
        s (tc/fixed (mod t 60) 1)]
    (format "%02d:%02d:%s" (long (quot t 3600)) (long (mod (quot t 60) 60)) (if (< (count s) 4) (str "0" s) s))))

(def ^:private pictures-css
  "\n.pics{display:flex;gap:4px}.pics img,.none{width:160px;height:90px;object-fit:contain;background:#000;flex:none}
.none{display:flex;align-items:center;justify-content:center;color:#6b7075;font-size:11px;background:#2a2c30}")

(defn- pictures-cell [urls]
  (str "<td><div class=\"pics\">"
       (apply str (map #(if % (str "<img src=\"" (esc %) "\" loading=\"lazy\">") "<div class=\"none\">no picture</div>")
                       urls))
       "</div></td>"))

(defn- page*
  [segments title subtitle pictures]
  (str/join "\n"
            (concat [(str "<!doctype html><html><head><meta charset=\"utf-8\"><title>" (esc title) "</title>")
                     (str "<style>" page-css (when pictures pictures-css) "</style></head><body>")
                     (str "<h1>" (esc title) "</h1>")
                     (str "<p class=\"sub\">" (esc subtitle) "</p>")
                     "<h2>Clips, in order</h2><table>"]
                    (for [[i s] (map-indexed vector segments)]
                      (str "<tr>" (when pictures (pictures-cell (get pictures i [nil nil nil])))
                           "<td class=\"t\">" (esc (str (page-hms (:start s)) " – " (page-hms (:end s)))) "</td>"
                           "<td>" (esc (str (:source_name s) ": " (:text s)))
                           (when (seq (:why s)) (str "<div class=\"why\">" (esc (:why s)) "</div>"))
                           "</td></tr>"))
                    ["</table>" "</body></html>\n"])))

(defn page
  "cut.html: a plain local page (no network), one row per clip with its times, file, text and note, and its frames
  at in, middle and out when `pictures` has any (file:// URLs, one vector of three per clip, nil where there's
  none; decision 4)."
  ([segments title subtitle] (page segments title subtitle nil))
  ([segments title subtitle pictures]
   (let [pictures? (some some? (flatten pictures))]
     (page* segments title subtitle (when pictures? (vec pictures))))))

;; ------------------------------------------------------------------------------------------------ writing

(defn now
  "The time now, as cut.json writes it: UTC to the millisecond."
  []
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") (.atOffset (Instant/now) ZoneOffset/UTC)))

(defn stamp
  "The time now as a folder name's stamp: UTC to the second."
  []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") (.atOffset (Instant/now) ZoneOffset/UTC)))

(defn slug
  "A folder name from a title: ASCII, lowercase, dashes, at most 40 characters."
  [text]
  (let [s (-> (Normalizer/normalize (str text) Normalizer$Form/NFKD)
              (str/replace #"[^\x00-\x7f]" "")
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (or (not-empty (subs s 0 (min 40 (count s)))) "cut")))

(defn- new-folder!
  "A folder for the cut that didn't exist before: <stamp>-<slug>, or with -2, -3 … in the same second."
  [cuts-dir name]
  (fs/create-dirs cuts-dir)
  (loop [k 1]
    (let [folder (fs/path cuts-dir (if (= 1 k) name (str name "-" k)))]
      (if (try (fs/create-dir folder) true (catch java.nio.file.FileAlreadyExistsException _ false))
        (str folder)
        (if (< k 100)
          (recur (inc k))
          (out/fail! "cut-exists" (str "can't make a new folder for " name " in " cuts-dir)))))))

(defn write-new!
  "Writes `text` to a file that must not exist yet (never overwrite a cut, AGENTS.md rule 3)."
  [path text]
  (Files/write (fs/path path) (.getBytes ^String text StandardCharsets/UTF_8)
               ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                                                      StandardOpenOption/WRITE])))

(defn record
  "cut.json's content."
  [{:keys [title scope segments warnings run made]}]
  (let [rate (timeline-rate segments)]
    (array-map :schema schema
               :made_at made
               :idea title
               :scope scope
               :title title
               :notes "assembled from a cut list by `blackbox edl new`"
               :warnings warnings
               :run run
               :length_s (tc/round-to (reduce + 0.0 (map length segments)) 3)
               :clips (mapv (fn [s]
                              (array-map :source_name (:source_name s)
                                         :source_path (:source_path s)
                                         :source_sha256 (:source_sha256 s)
                                         :rel (:rel s)
                                         :play_path (:play_path s)
                                         :proxy (boolean (:proxy s))
                                         :start (:start s)
                                         :end (:end s)
                                         :source_in (tc/source-tc s (:start s) rate)
                                         :source_out (tc/source-tc s (:end s) rate)
                                         :text (:text s)
                                         :why (:why s)
                                         :from (:from s)
                                         :to (:to s)))
                            segments))))

(defn write!
  "A new cut folder under `cuts-dir` with its five files, each created new. Returns the folder. `cut` holds :title
  :scope :segments :warnings :run, and :pictures for cut.html."
  [cuts-dir {:keys [title scope segments warnings pictures] :as cut}]
  (let [made (now)
        [edl edl-warnings] (cmx3600 segments title)
        doc (record (assoc cut :made made :warnings (into (vec warnings) edl-warnings)))
        subtitle (str title " · " (count segments) " clips · " (tc/fixed (:length_s doc) 1) " s · " scope " · " made)
        folder (new-folder! cuts-dir (str (stamp) "-" (slug title)))]
    (doseq [[name text] [["cut.mpv.edl" (mpv-edl segments)]
                         ["cut.edl" edl]
                         ["cut.srt" (srt segments)]
                         ["cut.json" (toon/dumps-json doc)]
                         ["cut.html" (page segments title subtitle pictures)]]]
      (write-new! (fs/path folder name) text))
    folder))

;; ------------------------------------------------------------------------------------------------ reading

(defn- read-cut [cut-json]
  (try
    (let [doc (json/parse-string (slurp (str cut-json)))]
      {:id (str (fs/file-name (fs/parent cut-json)))
       :title (or (get doc "title") (get doc "idea"))
       :clips (count (get doc "clips"))
       :length (tc/hms (get doc "length_s"))
       :made (get doc "made_at")
       :folder (str (fs/parent cut-json))})
    (catch Exception _ nil)))

(defn edls
  "Every cut with a readable cut.json in the folders of `paths` (:cuts and :old-cuts), newest first by id."
  [{:keys [cuts old-cuts]}]
  (->> [cuts old-cuts]
       (remove nil?)
       (filter fs/directory?)
       (mapcat #(fs/glob % "*/cut.json"))
       (keep read-cut)
       (sort-by :id #(compare %2 %1))
       vec))

(defn find-cut
  "The folder of the cut `id` names: exactly, else the one cut whose id contains it. error.code not-found or
  ambiguous otherwise."
  [{:keys [cuts old-cuts]} id]
  (let [dirs (filter #(and % (fs/directory? %)) [cuts old-cuts])
        exact (some #(let [f (fs/path % id)] (when (fs/regular-file? (fs/path f "cut.json")) (str f))) dirs)
        hits (or (some-> exact vector)
                 (->> dirs
                      (mapcat #(fs/glob % "*/cut.json"))
                      (map (comp str fs/parent))
                      (filter #(str/includes? (str (fs/file-name %)) id))
                      distinct
                      sort))]
    (when (not= 1 (count hits))
      (out/fail! (if (empty? hits) "not-found" "ambiguous")
                 (str (if (empty? hits) "no EDL" (str (count hits) " EDLs")) " matching '" id "'")
                 {:help ["Run `blackbox edl list` for ids"]}))
    (first hits)))
