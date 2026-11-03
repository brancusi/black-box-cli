(ns black-box-cli.ids
  "Line ids (docs/DESIGN.md, decision 2): `<clip>.<cue>`, e.g. `k7f3q2ab.41`. The clip is the first 8 characters of the
  lowercase base32 SHA-256 of the transcript's `<rel>`, the cue its 1-based place in the transcript, so the same
  media_meta gives the same ids on every Mac and after every rebuild."
  (:require
   [black-box-cli.out :as out]
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(def clip-length
  "Characters of a clip: 40 bits, so a clash stays unlikely as the archive grows."
  8)

(def ^:private alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32
  "RFC 4648 base32 of `bs`, lowercase, without padding."
  [^bytes bs]
  (let [sb (StringBuilder.)]
    (loop [i 0 buffer 0 bits 0]
      (cond
        (>= bits 5) (do (.append sb (.charAt ^String alphabet (bit-and (bit-shift-right buffer (- bits 5)) 31)))
                        (recur i buffer (- bits 5)))
        (< i (alength bs)) (recur (inc i) (bit-or (bit-shift-left (bit-and buffer 0xff) 8) (bit-and (aget bs i) 0xff))
                                  (+ bits 8))
        (pos? bits) (.append sb (.charAt ^String alphabet (bit-and (bit-shift-left buffer (- 5 bits)) 31)))
        :else nil))
    (str sb)))

(defn clip
  "The clip part of a line id for the transcript at `rel`."
  [rel]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String rel StandardCharsets/UTF_8))]
    (subs (base32 digest) 0 clip-length)))

(defn line-id
  "The id of cue `n` (1-based) of the transcript at `rel`."
  [rel n]
  (str (clip rel) "." n))

(def ^:private id-re #"([a-z2-7]{8})\.([1-9][0-9]*)")

(def example "k7f3q2ab.41")

(defn parse
  "A line id as {:clip :cue}, or error.code bad-id."
  [v]
  (if-let [[_ c n] (re-matches id-re (str/lower-case (str/trim (str v))))]
    {:clip c :cue (parse-long n)}
    (out/fail! "bad-id" (str "'" v "' isn't a line id (like " example ")")
               {:help ["Run `blackbox search <words>` to find line ids"]})))
