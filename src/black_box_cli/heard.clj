(ns black-box-cli.heard
  "What was said in a clip, as fully as the archive knows. A camera's own transcript drops what its microphone
  heard badly (far voices, a noisy room), though the lav recorders on the people talking caught it: the sync map
  (Black Box's) puts those recorders' files with the camera's. So a camera's lines are its own, and where it has
  none, its recorders' lines moved onto its time, each marked with the recorder it came from. Every line carries
  who said it when Black Box has named the voice (`person`). Read-only: everything comes from the index."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.people :as people]
   [black-box-cli.sync :as sync]
   [black-box-cli.tc :as tc]
   [black-box-cli.translations :as translations]
   [cheshire.core :as json]))

(def min-confidence
  "The least sync confidence a recorder's lines are trusted at, as `online` lays recorders under a clip."
  0.5)

(def ^:private slack
  "Seconds two lines may share before they count as the same moment said twice."
  0.25)

(defn- overlaps? [{a :start b :end} {c :start d :end}]
  (> (- (min b d) (max a c)) slack))

(defn fill
  "Camera `lines` with `recorded` lines (already on the camera's time, the preferred recorder's first) where the
  camera has none: a recorded line is taken when no line already there overlaps it. In order of start."
  [lines recorded]
  (->> (reduce (fn [have l] (if (some #(overlaps? l %) have) have (conj have l))) (vec lines) recorded)
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
  "Every line said in the file `rel` (transcript `version`): its own, then its recorders' where it has none, each
  with `person` when named, a recorder's with `from` (its file's name). With :recorders, the recorders' names too:
  {:lines [...] :recorders [names]}. Nil when the index has no transcript of it."
  [path version rel]
  (when-let [t (db/one path "SELECT id, rel, version, json_sha256, source_sha256 FROM transcripts
                             WHERE version = :v AND rel = :rel;"
                       {:v version :rel rel})]
    (let [voices (people/voice-names path)
          own (lines-of path voices t)
          recs (if (and (:source_sha256 t) (sync/available? path)) (recorders path voices version t) [])]
      {:lines (fill own (mapcat :lines recs))
       :recorders (mapv #(str (fs/file-name (:recorder_rel (:pair %)))) recs)})))
