(ns black-box-cli.toon
  "TOON output (https://toonformat.dev) and the JSON twin, exactly as the Black Box repo's `toon.py` writes them (the goldens
  in test/golden/ are its output). An encoder only: the CLI never parses TOON.

  The subset: `key: value` lines, nested objects indented two spaces, arrays of primitives inline (`tags[3]: a,b,c`),
  arrays of objects with the same primitive fields as tables (`hits[2]{id,file,at}:` then one row per line), any
  other array as a list (`- item`). Strings are quoted only when they would read as something else.

  Objects print their keys in the map's own order, so build them with `array-map` (any size) and never `assoc`
  onto one past eight keys: Clojure turns it into a hash map and the order is lost."
  (:require
   [clojure.string :as str]))

(def ^:private indent "  ")

(def ^:private number-re #"-?\d+(\.\d+)?([eE][+-]?\d+)?")

(def ^:private key-re #"[A-Za-z_][A-Za-z0-9_.]*")

(defn- needs-quotes? [^String s]
  (or (= s "")
      (not= s (str/trim s))
      (contains? #{"true" "false" "null"} s)
      (some? (re-matches number-re s))
      (str/starts-with? s "-")
      (some #(str/includes? s %) ["," ":" "\"" "\\" "[" "]" "{" "}" "\n" "\r" "\t"])))

(defn py-float
  "A double as Python's repr() writes it: the shortest digits that read back the same, plain from 1e-4 up to 1e16,
  else 1.5e+16 or 1e-05."
  [d]
  (let [d (double d)]
    (cond
      (Double/isNaN d) "nan"
      (Double/isInfinite d) (if (pos? d) "inf" "-inf")
      (zero? d) (if (neg? (Math/copySign 1.0 d)) "-0.0" "0.0")
      :else
      (let [bd (.stripTrailingZeros (BigDecimal. (Double/toString d)))
            digits (str (.abs (.unscaledValue bd)))
            exp (- (count digits) 1 (.scale bd))
            sign (if (neg? d) "-" "")]
        (cond
          (or (< exp -4) (>= exp 16))
          (str sign (subs digits 0 1) (when (> (count digits) 1) (str "." (subs digits 1)))
               "e" (if (neg? exp) "-" "+") (format "%02d" (abs exp)))

          (neg? exp)
          (str sign "0." (apply str (repeat (dec (- exp)) "0")) digits)

          :else
          (let [whole (inc exp)
                digits (str digits (apply str (repeat (max 0 (- whole (count digits))) "0")))]
            (str sign (subs digits 0 whole) "." (if (= whole (count digits)) "0" (subs digits whole)))))))))

(defn- number-str
  "A number as Python's str() writes it in TOON: a whole double without its .0."
  [n]
  (cond
    (not (double? n)) (str n)
    (Double/isNaN n) "null"
    (Double/isInfinite n) (py-float n)
    (== n (Math/rint n)) (str (.toBigInteger (BigDecimal. (double n))))
    :else (py-float n)))

(defn scalar
  "One value as TOON writes it on a line."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (str v)
    (number? v) (number-str v)
    :else (let [s (if (keyword? v) (name v) (str v))]
            (if (needs-quotes? s)
              (str "\"" (-> s
                            (str/replace "\\" "\\\\")
                            (str/replace "\"" "\\\"")
                            (str/replace "\n" "\\n")
                            (str/replace "\r" "\\r")
                            (str/replace "\t" "\\t"))
                   "\"")
              s))))

(defn- key-str [k]
  (let [k (if (keyword? k) (name k) (str k))]
    (if (re-matches key-re k) k (scalar k))))

(defn- primitive? [v]
  (or (nil? v) (string? v) (keyword? v) (number? v) (boolean? v)))

(defn- tabular-fields
  "The shared field names when every item is a non-empty object of primitives with the same keys in the same order."
  [items]
  (when (and (seq items) (every? map? items))
    (let [fields (vec (keys (first items)))]
      (when (and (seq fields)
                 (every? (fn [i] (and (= (vec (keys i)) fields) (every? primitive? (vals i)))) items))
        fields))))

(defn- pad [depth] (apply str (repeat depth indent)))

(declare lines)

(defn- list-lines [head items depth]
  (let [n (count items)
        item-pad (pad (inc depth))]
    (if (every? primitive? items)
      (if (some #(and (string? %) (> (count %) 40)) items)
        (into [(str head "[" n "]:")] (map #(str item-pad "- " (scalar %)) items))
        [(str head "[" n "]:" (when (seq items) (str " " (str/join "," (map scalar items)))))])
      (if-let [fields (tabular-fields items)]
        (into [(str head "[" n "]{" (str/join "," (map key-str fields)) "}:")]
              (map (fn [i] (str item-pad (str/join "," (map #(scalar (get i %)) fields)))) items))
        (reduce (fn [out i]
                  (if (map? i)
                    (let [sub (lines nil i (+ depth 2))]
                      (if (seq sub)
                        (into (conj out (str item-pad "- " (str/triml (first sub)))) (rest sub))
                        (conj out (str item-pad "-"))))
                    (into out (map #(str item-pad "- " (str/triml %)) (lines nil i 0)))))
                [(str head "[" n "]:")]
                items)))))

(defn- lines [k v depth]
  (let [head (if (some? k) (str (pad depth) (key-str k)) (pad depth))]
    (cond
      (map? v) (into (if (some? k) [(str head ":")] [])
                     (mapcat (fn [[ck cv]] (lines ck cv (if (some? k) (inc depth) depth))) v))
      (sequential? v) (list-lines head (vec v) depth)
      :else [(str head ": " (scalar v))])))

(defn dumps
  "A map as TOON, one line per field, ending in a newline."
  [m]
  (str (str/join "\n" (lines nil m 0)) "\n"))

;; JSON, as Python's json.dumps(obj, ensure_ascii=False, indent=1) writes it.

(defn- json-string [^String s]
  (let [sb (StringBuilder. "\"")]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (str (.append sb "\""))))

(defn- json-number [n]
  (cond
    (not (double? n)) (str n)
    (Double/isNaN n) "NaN"
    (Double/isInfinite n) (if (pos? n) "Infinity" "-Infinity")
    :else (py-float n)))

(defn- json-value [v depth]
  (let [inner (apply str (repeat (inc depth) " "))
        outer (apply str (repeat depth " "))]
    (cond
      (nil? v) "null"
      (boolean? v) (str v)
      (number? v) (json-number v)
      (map? v) (if (empty? v)
                 "{}"
                 (str "{\n"
                      (str/join ",\n" (map (fn [[k x]] (str inner (json-string (if (keyword? k) (name k) (str k)))
                                                            ": " (json-value x (inc depth))))
                                           v))
                      "\n" outer "}"))
      (sequential? v) (if (empty? v)
                        "[]"
                        (str "[\n" (str/join ",\n" (map #(str inner (json-value % (inc depth))) v)) "\n" outer "]"))
      (keyword? v) (json-string (name v))
      :else (json-string (str v)))))

(defn dumps-json
  "A map as JSON with a one-space indent, ending in a newline."
  [m]
  (str (json-value m 0) "\n"))
