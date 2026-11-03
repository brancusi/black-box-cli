(ns black-box-cli.who
  "The commands about people (docs/DESIGN.md, decisions 8 and 9): `people` lists the names, `whose` scores a
  voice or a face against everyone's confirmed prints, `like` finds voices or faces like one, and `frame` asks
  here for the faces on screen. Names come from the snapshot Black Box publishes; scores from the prints, as the
  People page makes them."
  (:require
   [black-box-cli.archive :as archive]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.people :as people]
   [black-box-cli.prints :as prints]
   [black-box-cli.tc :as tc]
   [clojure.string :as str]))

(defn- score [s] (tc/round-to s 3))

(defn- quoted-name [n] (if (re-find #"\s" n) (str "\"" n "\"") n))

;; ------------------------------------------------------------------------------------------------ people

(defn people-cmd
  "`blackbox people`."
  [{:keys [opts] :as ctx}]
  (let [db (people/require! (:index (index/paths ctx)))
        names (people/names db nil)
        clips (fn [id kind] (count (distinct (map :rel (filter #(and (= id (:person %)) (= kind (:kind %))) names)))))
        everyone (people/people db)
        rows (mapv (fn [{:keys [id name aliases]}]
                     (out/pick (array-map :name name :voices (clips id "voice") :faces (clips id "face")
                                          :aliases (str/join ", " aliases) :id id)
                               [:name :voices :faces] opts))
                   everyone)]
    [(array-map :at (people/snapshot-at db) :count (count rows) :people rows)
     (if-let [n (:name (first everyone))]
       [(str "Run `blackbox search <words> --person " (quoted-name n) "` for what they said")
        (str "Run `blackbox footage --person " (quoted-name n) "` for where they're heard or seen")]
       ["Names are made on Black Box's People page"])]))

;; ------------------------------------------------------------------------------------------------ one voice or face

(defn- the-line
  "The indexed line `id`, with its transcript's fields, or error.code not-found."
  [path id]
  (let [{:keys [clip cue]} (ids/parse id)]
    (or (db/one path "SELECT c.id, c.n, c.speaker, c.start, t.rel, t.source_name, t.version, t.json_sha256
                      FROM cues c JOIN transcripts t ON t.id = c.transcript_id
                      WHERE t.version = :version AND t.clip = :clip AND c.n = :n;"
                {:version index/default-version :clip clip :n cue})
        (out/fail! "not-found" (str "no line " id " in the index") {:help ["Run `blackbox search <words>` for line ids"]}))))

(defn- subject
  "The voice of a line id, or the face of a face id: {:kind :id :rel :label :file :clip, :transcript_sha256 for a
  voice}."
  [ctx id]
  (if (prints/face-id? id)
    (assoc (prints/parse-face-id id) :kind "face" :id (str/lower-case (str/trim id)))
    (let [r (the-line (:index (index/paths ctx)) id)]
      (when-not (:speaker r)
        (out/fail! "no-speaker" (str id " has no speaker label, so no voice print")
                   {:help ["Lines of an audio file without speakers can't be matched by voice"]}))
      {:kind "voice" :id (:id r) :rel (:rel r) :label (:speaker r) :file (:source_name r) :clip (ids/clip (:rel r))
       :transcript (:version r) :transcript_sha256 (:json_sha256 r)})))

(defn- find-print
  "The whole print of `s` among `ps`: by clip and label for a face, by transcript too for a voice."
  [s ps]
  (first (filter #(and (= (:clip s) (:clip %)) (= (:label s) (:label %))
                       (or (= "face" (:kind s)) (= (:transcript_sha256 s) (:transcript_sha256 %))))
                 ps)))

(defn- scoring
  "What scoring `kind` needs: the names, the snapshot's matching, the prints' model, vectors and whole prints. Errors
  when any is missing."
  [ctx kind]
  (let [path (:index (index/paths ctx))
        db (people/require! path)
        matching (people/matching db)
        _ (when-not matching
            (out/fail! "no-matching" "the names snapshot is older than Black Box 0.36.0: it doesn't say how to score prints"
                       {:help ["Names still work: `blackbox people`, `--person`"]}))
        p (prints/require! path)
        model (prints/model-of p matching kind)
        [m dim] (when model (prints/vectors p model))]
    (when-not m
      (out/fail! "no-prints" (str "no " kind " prints in the index") {:help ["Run `blackbox index`"]}))
    {:db db :matching matching :m m :dim dim :prints (prints/whole-prints p model)
     :who (into {} (map (juxt :id :name)) (people/people db))}))

(defn- name-line
  "The names file's answer for a print: `Name (confirmed)`, `Name (match 0.83)`, or nil."
  [names who {:keys [rel label transcript_sha256]} kind]
  (when-let [n (first (filter #(and (= kind (:kind %)) (= rel (:rel %)) (= label (:label %))
                                    (= transcript_sha256 (:transcript_sha256 %)))
                              names))]
    (str (who (:person n)) (if (= "confirmed" (:how n)) " (confirmed)" (str " (match " (:score n) ")")))))

(defn whose-cmd
  "`blackbox whose <line id | face id>`."
  [{:keys [args opts] :as ctx}]
  (let [s (subject ctx (:id args))
        kind (:kind s)
        {:keys [db matching m dim prints who]} (scoring ctx kind)
        names (people/names db kind)
        p (find-print s prints)
        _ (when-not p
            (out/fail! "not-found" (str "no " kind " print for " (:id s) (when (= "voice" kind) (str " (" (:label s) ")")))
                       {:help ["Prints are made for clips with a transcript and a proxy, on the mini"]}))
        refs (prints/references prints (filter #(= "confirmed" (:how %)) names) (:max_refs matching))
        scores (prints/person-scores m dim (:row p) refs (:top_k matching))
        threshold (get-in matching [(keyword kind) :threshold])
        shown (take (:limit opts) scores)]
    [(array-map :id (:id s)
                :kind kind
                :label (:label p)
                :file (some-> (:rel p) (str/split #"/") last)
                :named (or (name-line names who p kind) "no one")
                :threshold threshold
                :count (count shown)
                :people (if (seq shown)
                          (mapv (fn [[person sc]] (array-map :name (who person) :score (score sc)
                                                             :above (>= sc threshold)))
                                shown)
                          "no one has confirmed prints to compare with"))
     [(str "Run `blackbox like " (:id s) "` for " kind "s like this one")
      "Names are made on Black Box's People page; a score at or above the threshold is how it names"]]))

;; ------------------------------------------------------------------------------------------------ like

(defn- in-scope?
  "Whether `rel` is in the scope flags' days, months and path."
  [{:keys [day month path video]} rel]
  (let [d (archive/day-of rel)]
    (and (or (and (empty? day) (empty? month))
             (some #{d} day)
             (some #(and d (str/starts-with? d %)) month))
         (or (nil? path) (str/includes? rel path))
         (or (not video) (some #(str/ends-with? (str/lower-case rel) (str "." %)) archive/video-exts)))))

(defn- first-lines
  "The first line id of each (rel, transcript sha256, speaker) in the transcripts' index: {[rel sha label] id}."
  [path]
  (into {} (map (fn [r] [[(:rel r) (:json_sha256 r) (:speaker r)] (:id r)]))
        (db/query path "SELECT t.rel, t.json_sha256, c.speaker, c.id FROM cues c JOIN transcripts t ON t.id = c.transcript_id
                        WHERE t.version = :version AND c.speaker IS NOT NULL
                          AND c.n = (SELECT min(n) FROM cues x WHERE x.transcript_id = c.transcript_id AND x.speaker = c.speaker);"
                  {:version index/default-version})))

(defn like-cmd
  "`blackbox like <line id | face id>`: voices or faces like one, from other clips."
  [{:keys [args opts] :as ctx}]
  (when (or (:html opts) (:open opts))
    (out/fail! "usage" "--html and --open are for frames" {:help ["Run `blackbox like <frame id> --html`"]}))
  (let [s (subject ctx (:id args))
        kind (:kind s)
        {:keys [db m dim prints who]} (scoring ctx kind)
        names (people/names db kind)
        p (or (find-print s prints)
              (out/fail! "not-found" (str "no " kind " print for " (:id s)) {:help ["Run `blackbox people`"]}))
        lines (when (= "voice" kind) (first-lines (:index (index/paths ctx))))
        person-of (into {} (map (juxt (juxt :rel :transcript_sha256 :label) (comp who :person))) names)
        row (fn [[q sc]]
              (let [file (last (str/split (:rel q) #"/"))]
                (if (= "voice" kind)
                  (when-let [id (get lines [(:rel q) (:transcript_sha256 q) (:label q)])]
                    (array-map :id id :day (archive/day-of (:rel q)) :file file :speaker (:label q)
                               :person (person-of [(:rel q) (:transcript_sha256 q) (:label q)]) :score (score sc)
                               :rel (:rel q)))
                  (array-map :id (prints/face-id (:clip q) (:label q)) :day (archive/day-of (:rel q)) :file file
                             :at (tc/hms (ffirst (:ranges q)))
                             :person (person-of [(:rel q) (:transcript_sha256 q) (:label q)]) :score (score sc)
                             :rel (:rel q)))))
        per-clip (:per-clip opts)
        ranked (->> (prints/like m dim (:row p) prints (:clip p))
                    (filter #(in-scope? opts (:rel (first %))))
                    (keep (fn [x] (some->> (row x) (vector (:clip (first x)))))))
        picked (loop [[[clip r] & more :as xs] ranked seen {} out []]
                 (if (or (empty? xs) (>= (count out) (:limit opts)))
                   out
                   (if (and (pos? per-clip) (>= (get seen clip 0) per-clip))
                     (recur more seen out)
                     (recur more (update seen clip (fnil inc 0)) (conj out r)))))
        default (if (= "voice" kind) [:id :day :file :speaker :person :score] [:id :day :file :at :person :score])
        rows (mapv #(out/pick % default opts) picked)]
    [(array-map :query (str (:id s) " (" (if (= "voice" kind) (str "the voice of " (:label s)) "a face") " in "
                            (last (str/split (:rel p) #"/")) ")")
                :scope (archive/scope-words opts)
                :count (count rows)
                (keyword (str kind "s")) (if (seq rows) rows (str "no other " kind "s in scope")))
     [(str "Run `blackbox whose " (or (:id (first rows)) "<id>") "` for who it may be")
      (if (= "voice" kind)
        "Run `blackbox line <id>` to read what they said"
        "Run `blackbox frame <file> --at <time>` to see them")]]))

;; ------------------------------------------------------------------------------------------------ faces on screen

(defn faces-at
  "The faces on screen at `t` seconds in the clip at `rel` ([{:id :person}], whole seconds as the prints have
  them), or nil without face prints for it."
  [index-path rel t]
  (when (prints/available? index-path)
    (let [p (prints/require! index-path)
          names (when (people/available? index-path) (people/require! index-path))
          matching (when names (people/matching names))
          model (prints/model-of p matching "face")
          faces (when model (filter #(= rel (:rel %)) (prints/whole-prints p model)))
          who (if names (into {} (map (juxt :id :name)) (people/people names)) {})
          named (if names
                  (into {} (map (juxt (juxt :transcript_sha256 :label) (comp who :person)))
                        (filter #(= rel (:rel %)) (people/names names "face")))
                  {})]
      (when (seq faces)
        (vec (for [f faces
                   :when (some (fn [[a b]] (and (<= a t) (< t (inc b)))) (:ranges f))]
               (array-map :id (prints/face-id (:clip f) (:label f))
                          :person (named [(:transcript_sha256 f) (:label f)]))))))))
