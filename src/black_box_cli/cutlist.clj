(ns black-box-cli.cutlist
  "Reading a cut list (archive_cli.parse_cutlist): the text form, one clip per line, or the JSON form. Every problem
  is collected, each naming its line, so the agent fixes them all in one turn; nothing here touches the index."
  (:require
   [black-box-cli.out :as out]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def help
  "The cut list format, for `blackbox edl new --help`."
  "A cut list: one clip per line, in the order they play. Blank lines and lines starting with # are ignored; text after
` # ` on a clip's line is its note (it goes in the EDL as a comment).

  title: Harbour selects            optional, once (or --title)
  k7f3q2ab.41                       one transcript line (ids from `blackbox search`)
  k7f3q2ab.41..k7f3q2ab.45          a run of lines from one file
  C5274.MP4 0:21.9-0:26.6           a file (name or part of its path) from in to out (s, m:ss or h:mm:ss)
  09/16/src/fx3/C5274.MP4 21.9-26.6 # wide of the kiln
  k7f3q2ab.s3                       a shot's best moment (ids from `blackbox shots`)
  k7f3q2ab.s3 whole                 the whole shot

Line clips get 0.25 s handles and in/out from the words' own times; file and shot clips are cut exactly.
JSON works too: {\"title\": …, \"clips\": [{\"from\": \"k7f3q2ab.41\", \"to\": \"k7f3q2ab.43\", \"note\": …},
{\"file\": …, \"in\": …, \"out\": …}, {\"shot\": \"k7f3q2ab.s3\", \"whole\": false}]}. A line clip's \"text\", when given, must still be what its lines say.
")

(defn quoted
  "`s` as Python's repr writes a string, as the old CLI's messages did: 'like this', or \"like this\" when it holds a
  single quote."
  [s]
  (let [s (str/replace (str s) "\\" "\\\\")]
    (if (and (str/includes? s "'") (not (str/includes? s "\"")))
      (str "\"" s "\"")
      (str "'" (str/replace s "'" "\\'") "'"))))

(def ^:private number-re #"[0-9]+(\.[0-9]*)?|\.[0-9]+")

(defn parse-time
  "\"83.5\", \"1:23.5\" or \"0:01:23.5\" as seconds (archive_cli.parse_time), or error.code bad-time."
  [v]
  (let [parts (str/split (str/trim (str v)) #":" -1)]
    (if (and (<= 1 (count parts) 3) (every? #(re-matches number-re %) parts))
      (reduce + 0.0 (map-indexed (fn [i p] (* (parse-double p) (Math/pow 60 i))) (reverse parts)))
      (out/fail! "bad-time" (str (quoted v) " isn't a time (seconds, m:ss or h:mm:ss)")))))

(def ^:private id-part "[A-Za-z2-7]{8}\\.[0-9]+")

(def ^:private lines-re (re-pattern (str "(" id-part ")(?:\\s*(?:\\.\\.|-|–)\\s*(" id-part "))?")))

(def ^:private shot-re #"([A-Za-z2-7]{8}\.[sS][0-9]+)(?:\s+(whole))?")

(def ^:private file-re #"(.+?)\s+([\d:.]+)\s*(?:-|–|\.\.)\s*([\d:.]+)")

(defn- text-item
  "One line of the text form: [:title t], [:item m], [:problem p] or nil for a blank line or a comment."
  [n raw]
  (let [line (str/trim raw)]
    (when-not (or (str/blank? line) (str/starts-with? line "#"))
      (let [[line note] (if-let [i (str/index-of line " # ")]
                          [(str/trim (subs line 0 i)) (str/trim (subs line (+ i 3)))]
                          [line ""])]
        (if (str/starts-with? (str/lower-case line) "title:")
          [:title (str/trim (subs line 6))]
          (if-let [[_ a b] (re-matches lines-re line)]
            [:item {:n n :kind :lines :a a :b (or b a) :note note}]
            (if-let [[_ id whole] (re-matches shot-re line)]
              [:item {:n n :kind :shot :shot id :whole (some? whole) :note note}]
              (if-let [[_ file t-in t-out] (re-matches file-re line)]
                [:item {:n n :kind :file :file (str/trim file) :in t-in :out t-out :note note}]
                [:problem (str "line " n ": " (quoted (str/trim raw))
                               " isn't a clip (a line id, id..id, a shot id, or <file> <in>-<out>)")]))))))))

(defn- parse-text [text]
  (let [parsed (keep identity (map-indexed (fn [i raw] (text-item (inc i) raw)) (str/split-lines text)))
        by (fn [k] (keep (fn [[kind v]] (when (= k kind) v)) parsed))
        items (vec (by :item))
        problems (vec (by :problem))]
    {:title (last (by :title))
     :items items
     :problems (if (and (empty? items) (empty? problems)) ["no clips"] problems)}))

(defn- json-clip [cl]
  (when-not (map? cl) (throw (ex-info "each clip is an object" {})))
  (let [s (fn [k] (let [v (get cl k)] (when (some? v) (str v))))
        note (or (s "note") "")]
    (cond
      (contains? cl "file")
      (do (when-not (and (s "in") (s "out")) (throw (ex-info "a file clip needs in and out" {})))
          {:n 0 :kind :file :file (s "file") :in (s "in") :out (s "out") :note note})

      (s "shot")
      {:n 0 :kind :shot :shot (s "shot") :whole (true? (get cl "whole")) :note note}

      (s "from")
      (cond-> {:n 0 :kind :lines :a (s "from") :b (or (s "to") (s "from")) :note note}
        (s "text") (assoc :text (s "text")))

      :else (throw (ex-info "a clip needs from (a line id), shot or file" {})))))

(defn- parse-json [text]
  (try
    (let [doc (json/parse-string text)
          _ (when-not (map? doc) (throw (ex-info "not an object" {})))
          clips (get doc "clips")
          _ (when-not (or (nil? clips) (sequential? clips)) (throw (ex-info "clips is a list" {})))
          items (mapv json-clip clips)]
      {:title (some-> (get doc "title") str)
       :items items
       :problems (if (seq items) [] ["no clips"])})
    (catch Exception e
      {:title nil :items [] :problems [(str "not a cut list in JSON: " (ex-message e))]})))

(defn parse
  "The cut list in `text`: {:title :items :problems}. Each item is {:n (its line, 0 in JSON) :kind (:lines or
  :file or :shot) :note}, with :a :b (line ids as written) and maybe :text for lines, :file :in :out for a file,
  :shot :whole for a shot."
  [text]
  (if (str/starts-with? (str/trim text) "{")
    (parse-json (str/trim text))
    (parse-text text)))
