(ns black-box-cli.heard
  "What was said in a clip, as well as the archive knows it. A camera's own microphone is far from the people talking;
  the lav recorders on them hear them close, and Deepgram transcribes them better. The sync map (Black Box's) puts
  those recorders' files with the camera's. So the recorders win: a clip's lines are its recorders' lines, on its time,
  and a camera line stays only where no recorder heard anything, or where it says something else than the recorders
  at that moment (another conversation, or people talking over each other): then both are kept, for the editor to
  choose. The same moment heard on two recorders is kept once. Every line carries who said it when Black Box has
  named the voice (`person`). Deterministic and read-only: words compared, everything from the index."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.people :as people]
   [black-box-cli.sync :as sync]
   [black-box-cli.tc :as tc]
   [black-box-cli.translations :as translations]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def min-confidence
  "The least sync confidence a recorder's lines are trusted at, as `online` lays recorders under a clip."
  0.5)

(def ^:private slack
  "Seconds two lines may share before they count as the same moment."
  0.25)

(def same-words
  "How much of a line's words another transcript must have around it for the two to be the same speech (two
  transcripts of one voice differ in a word here and there; two conversations share only small words). Measured on
  the archive: camera and lav agree at about 0.8 where they hear the same people."
  0.4)

(defn- overlaps? [{a :start b :end} {c :start d :end}]
  (> (- (min b d) (max a c)) slack))

(defn- tokens
  "The words of `text` that tell speech apart: lower case, without punctuation, three letters and up."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^\p{L}\p{N}']+")
       (filter #(> (count %) 2))))

(def ^:private reach
  "Seconds around a line its words are looked for in another transcript (two transcripts time a word a little
  differently, and split lines in different places)."
  1.0)

(defn covered
  "How much of line `l`'s words `others` (lines with :words [[w start end]]) have around its time, 0 to 1, each word
  counted as often as it's said; nil when `l` has no words that tell speech apart."
  [l others]
  (let [mine (frequencies (tokens (:text l)))
        n (reduce + (vals mine))
        theirs (->> others
                    (mapcat :words)
                    (filter (fn [[_ ws]] (<= (- (:start l) reach) ws (+ (:end l) reach))))
                    (map first)
                    (str/join " ")
                    tokens
                    frequencies)]
    (when (pos? n)
      (/ (double (reduce + (map (fn [[w k]] (min k (get theirs w 0))) mine))) n))))

(defn- said-there?
  "Whether `others` (the lines at `l`'s moment) say what `l` says: most of its words around its time, or it has too few
  words to tell them apart."
  [l others]
  (let [g (covered l others)]
    (or (nil? g) (< (count (tokens (:text l))) 3) (>= g same-words))))

(defn- words-said [l] (count (tokens (:text l))))

(defn from-recorders
  "The recorders' lines (on the camera's time, the preferred recorder's first), each moment once: the fullest
  transcript of it wins (most words, then the preferred recorder); lines at the same moment that say something else
  (people talking over each other) are all kept."
  [recorded]
  (reduce (fn [have l]
            (let [there (filter #(overlaps? l %) have)]
              (if (and (seq there) (said-there? l there)) have (conj have l))))
          []
          (sort-by (comp - words-said) recorded)))

(defn fill
  "A clip's lines: `recorded` (from-recorders) and, of the camera's own `lines`, those no recorder heard (no recorded
  line at that moment) or that say something else than the recorders there (kept beside them, marked :also).
  In order of start."
  [lines recorded]
  (->> lines
       (keep (fn [l]
               (let [there (filter #(overlaps? l %) recorded)]
                 (cond
                   (empty? there) l
                   (said-there? l there) nil
                   :else (assoc l :also true)))))
       (concat recorded)
       (sort-by (juxt :start :end))
       vec))

(defn- lines-of
  "Every line of transcript row `t` ({:id :rel :version :json_sha256}): {:id :start :end :text :speaker :lang :en
  :person :words [[w s e]]}. `en` is the English of a line said in another language (Black Box's translation), `lang`
  what it was said in when known, `person` who said it when Black Box named the voice (`voices`)."
  [path voices t]
  (let [english (translations/for-transcript path (:json_sha256 t))]
    (mapv (fn [q]
            (let [words (mapv (fn [[w s e]] [w (tc/round-to (or s (:start q)) 2) (tc/round-to (or e s (:end q)) 2)])
                              (json/parse-string (:words q)))]
              (array-map :id (:id q) :start (:start q) :end (:end q) :text (:text q)
                         :speaker (:speaker q)
                         :lang (or (:language q) (get-in english [(:n q) :lang]) (when english "en"))
                         :en (get-in english [(:n q) :en])
                         :person (get voices [(:rel t) (:version t) (:json_sha256 t) (:speaker q)])
                         :words words)))
          (db/query path "SELECT id, n, start, end, text, speaker, language, words FROM cues WHERE transcript_id = :t
                          ORDER BY n;"
                    {:t (:id t)}))))

(defn- on-camera
  "Recorder line `l` on the camera's time by `pair`, marked with the recorder it came from; nil outside the time the
  two overlap."
  [pair l]
  (let [at #(tc/round-to (sync/camera-time pair %) 2)
        a (at (:start l)) b (at (:end l))]
    (when (and (>= a (- (:o0 pair) 0.5)) (<= b (+ (:o1 pair) 0.5)))
      (assoc l :start a :end b
             :words (mapv (fn [[w s e]] [w (at s) (at e)]) (:words l))
             :from (str (fs/file-name (:recorder_rel pair)))))))

(defn recorders
  "The recorders the sync map puts with camera transcript `t` that have a transcript of `version`, preferred first
  (surest sync, then most lines on the camera's time): [{:pair :lines}] with the lines on the camera's time."
  [path voices version t]
  (->> (sync/recorders-of path (:rel t) (:source_sha256 t))
       (filter #(>= (or (:confidence %) 0) min-confidence))
       (keep (fn [p]
               (when-let [rt (db/one path (str "SELECT id, rel, version, json_sha256 FROM transcripts "
                                               "WHERE rel = :r AND source_sha256 = :s AND version = :v LIMIT 1;")
                                     {:r (:recorder_rel p) :s (:recorder_sha256 p) :v version})]
                 {:pair p :lines (vec (keep #(on-camera p %) (lines-of path voices rt)))})))
       (sort-by (fn [{:keys [pair lines]}] [(- (or (:confidence pair) 0)) (- (count lines))]))
       vec))

(defn lines
  "Every line said in the file `rel` (transcript `version`): its recorders' (each with `from`, its file's name), and
  its own where no recorder heard it or heard something else (those with `also`), each with `person` when named.
  {:lines [...] :recorders [names]}, or nil when the index has no transcript of it."
  [path version rel]
  (when-let [t (db/one path "SELECT id, rel, version, json_sha256, source_sha256 FROM transcripts
                             WHERE version = :v AND rel = :rel;"
                       {:v version :rel rel})]
    (let [voices (people/voice-names path)
          own (lines-of path voices t)
          recs (if (and (:source_sha256 t) (sync/available? path)) (recorders path voices version t) [])]
      {:lines (fill own (from-recorders (mapcat :lines recs)))
       :recorders (mapv #(str (fs/file-name (:recorder_rel (:pair %)))) recs)})))
