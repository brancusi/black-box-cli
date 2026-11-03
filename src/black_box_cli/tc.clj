(ns black-box-cli.tc
  "Times and timecode, rounded exactly as the old CLI's Python did (round half to even on the double's exact value):
  `m:ss.s` times, frame rates as fractions, and source timecode (transcript.py, cuts.source_tc)."
  (:require
   [clojure.string :as str])
  (:import
   (java.math BigDecimal RoundingMode)))

(defn round-to
  "`x` rounded to `places` decimals the way Python's round(x, places) does: half to even on the exact double."
  [x places]
  (.doubleValue (.setScale (BigDecimal. (double x)) (int places) RoundingMode/HALF_EVEN)))

(defn fixed
  "`x` with `places` decimals, as Python's format(x, '.Nf') writes it."
  [x places]
  (.toPlainString (.setScale (BigDecimal. (double x)) (int places) RoundingMode/HALF_EVEN)))

(defn hms
  "Seconds as `m:ss.s`, or `h:mm:ss.s` from an hour up (archive_cli._hms); nil stays nil."
  [t]
  (when (some? t)
    (let [t (max 0.0 (double t))
          h (long (quot t 3600))
          m (long (mod (quot t 60) 60))
          s (fixed (mod t 60) 1)
          s (if (< (count (first (str/split s #"\."))) 2) (str "0" s) s)]
      (if (pos? h)
        (format "%d:%02d:%s" h m s)
        (format "%d:%s" m s)))))

(def default-rate
  "The rate used when a file has none: 24000/1001."
  [24000 1001])

(defn- gcd [a b] (if (zero? b) (abs a) (recur b (rem a b))))

(defn- ratio [n d]
  (let [g (* (max 1 (gcd n d)) (if (neg? d) -1 1))]
    [(quot n g) (quot d g)]))

(defn parse-rate
  "A frame rate from a transcript's clock (\"24000/1001\", \"25\", \"29.97\") as [num den], or nil when it isn't
  one between 1 and 240 fps (cuts._rate)."
  [v]
  (let [s (some-> v str str/trim)
        r (cond
            (str/blank? s) nil
            (str/includes? s "/") (let [[n d] (map parse-long (str/split s #"/" 2))]
                                    (when (and n d (not (zero? d))) (ratio n d)))
            :else (try
                    (let [bd (BigDecimal. ^String s)
                          scale (.scale bd)]
                      (if (pos? scale)
                        (ratio (.longValueExact (.unscaledValue bd)) (long (Math/pow 10 scale)))
                        (ratio (.longValueExact (.toBigIntegerExact bd)) 1)))
                    (catch Exception _ nil)))]
    (when-let [[n d] r]
      (when (and (not (zero? n)) (<= 1 (/ (double n) d) 240))
        [n d]))))

(defn rate-value
  "A rate [num den] as a double."
  [[n d]]
  (/ (double n) d))

(defn- nominal
  "The whole frames per second a timecode counts: round(Fraction), half to even."
  [[n d]]
  (let [q (Math/floorDiv (long n) (long d))
        r (- n (* q d))]
    (cond
      (> (* 2 r) d) (inc q)
      (= (* 2 r) d) (if (even? q) q (inc q))
      :else q)))

(defn- drop-rate? [fps] (contains? #{30 60} fps))

(defn tc->frames
  "A timecode `hh:mm:ss:ff` (`;` for drop frame) as frames at `rate`, or nil."
  [tc rate]
  (let [parts (map parse-long (str/split tc #"[:;.]" -1))]
    (when (and (= 4 (count parts)) (every? some? parts))
      (let [[h m s f] parts
            fps (nominal rate)
            frames (+ (* (+ (* (+ (* h 60) m) 60) s) fps) f)]
        (if (and (str/includes? tc ";") (drop-rate? fps))
          (let [d (quot (* 2 fps) 30) mins (+ (* h 60) m)]
            (- frames (* d (- mins (quot mins 10)))))
          frames)))))

(defn frames->tc
  "Frames at `rate` as a timecode, drop frame when `drop` and the rate allows it."
  ([frames rate] (frames->tc frames rate false))
  ([frames rate drop]
   (let [fps (nominal rate)
         drop (and drop (drop-rate? fps))
         frames (if drop
                  (let [d (quot (* 2 fps) 30)
                        per10 (- (* fps 600) (* d 9))
                        per-min (- (* fps 60) d)
                        tens (Math/floorDiv (long frames) (long per10))
                        r (Math/floorMod (long frames) (long per10))]
                    (+ frames (* d 9 tens) (if (> r d) (* d (Math/floorDiv (long (- r d)) (long per-min))) 0)))
                  frames)
         frames (Math/floorMod (long frames) (long (* fps 86400)))
         f (rem frames fps)
         s (quot frames fps)]
     (format "%02d:%02d:%02d%s%02d" (quot s 3600) (rem (quot s 60) 60) (rem s 60) (if drop ";" ":") f))))

(defn source-tc
  "A time `t` in a file as Resolve labels it: from the clip's timecode, from the recorder's time of day, or from
  zero (cuts.source_tc). `clock` holds :clock_kind, :start_tc, :rate and :tod_s as the index has them; a file
  without a rate counts frames at `fallback` (default 24000/1001)."
  ([clock t] (source-tc clock t default-rate))
  ([{:keys [clock_kind start_tc rate tod_s]} t fallback]
   (let [r (parse-rate rate)]
     (if (and (= clock_kind "timecode") start_tc r)
       (frames->tc (+ (tc->frames start_tc r) (long (Math/rint (* t (rate-value r))))) r (str/includes? start_tc ";"))
       (let [r (or r fallback)
             base (if (= clock_kind "time_of_day") (or tod_s 0.0) 0.0)]
         (frames->tc (long (Math/rint (* (+ base t) (rate-value r)))) r))))))
