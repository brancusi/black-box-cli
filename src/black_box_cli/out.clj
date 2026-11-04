(ns black-box-cli.out
  "Everything `blackbox` prints, following AXI: TOON on stdout (JSON or EDN with --format), help[n] next steps after
  the output, errors on stdout as error: {code, message, problems?}. Nothing else in the CLI prints."
  (:require
   [black-box-cli.toon :as toon]
   [cheshire.core :as json]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

(def exit-codes
  "Exit codes: stable, callers branch on them."
  {:ok 0
   :error 1
   :usage 2})

(def text-limit
  "Long text is cut here, with a size hint and --full."
  160)

(defn- with-help
  "`m` with :help last, keeping the order of its keys (assoc past eight keys would lose it)."
  [m help]
  (if (seq help)
    (apply array-map (concat (mapcat identity m) [:help (vec help)]))
    m))

(defn- edn-str
  "`m` as EDN, pretty-printed, keys in the map's own order."
  [m]
  (binding [pprint/*print-right-margin* 120]
    (with-out-str (pprint/pprint m))))

(defn render
  "The text for `m` in `fmt` (:toon, :json or :edn), with `help` lines after it."
  [m help fmt]
  (case fmt
    :json (toon/dumps-json (with-help m help))
    :edn (edn-str (with-help m help))
    (str (toon/dumps m)
         (when (seq help)
           (str "help[" (count help) "]:\n" (apply str (map #(str "  " % "\n") help)))))))

(defn emit!
  "Prints `m` on stdout."
  ([m fmt] (emit! m nil fmt))
  ([m help fmt]
   (print (render m help fmt))
   (flush)))

(defn text!
  "Prints `s` as it is: --help, which is for reading, not parsing."
  [s]
  (print s)
  (flush))

(defn progress!
  "One line of progress on stderr, as JSON (`index --progress`, for Black Box to show): stdout keeps the one
  answer."
  [m]
  (binding [*out* *err*]
    (println (json/generate-string m))
    (flush)))

(defn fail!
  "Stops the command: an error of `code` (a string such as \"no-meta\"), exit 1, or exit 2 when `code` is
  \"usage\". `opts` takes :help (next steps) and :problems (every problem found, for cut lists)."
  ([code message] (fail! code message {}))
  ([code message {:keys [help problems]}]
   (throw (ex-info message {::error {:code code :message message :help help :problems problems}}))))

(defn error-of
  "The AXI error carried by an exception, or nil when it is not one of ours."
  [e]
  (::error (ex-data e)))

(defn error!
  "Prints an AXI error and returns its exit code."
  [{:keys [code message help problems]} fmt]
  (emit! {:error (cond-> (array-map :code code :message message)
                   (seq problems) (assoc :problems (vec problems)))}
         help fmt)
  (if (= code "usage") (exit-codes :usage) (exit-codes :error)))

(defn home-path
  "A path with the home folder written as ~."
  [path home]
  (let [path (str path)]
    (if (or (= path home) (str/starts-with? path (str home "/")))
      (str "~" (subs path (count home)))
      path)))

(defn shell-quoted
  "A path a person can paste into a shell as it is: double-quoted, absolute."
  [path]
  (str "\"" (-> (str path)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\"")
                (str/replace "$" "\\$")
                (str/replace "`" "\\`"))
       "\""))

(defn cut
  "`text` cut at `limit` characters with a size hint, unless `full`."
  ([text full] (cut text full text-limit))
  ([text full limit]
   (if (or (nil? text) full (<= (count text) limit))
     text
     (str (str/trimr (subs text 0 limit)) "… (+" (- (count text) limit) " chars, --full)"))))

(defn pick
  "The fields of `row` to show: `default` (field keywords), or --fields (a comma list, `all` for every field), or
  every field with --full. Keeps the row's order for all, the asked order otherwise."
  [row default {:keys [fields full]}]
  (let [fields (some-> fields str/trim)]
    (if (or full (= fields "all"))
      row
      (let [wanted (if fields (map keyword (remove str/blank? (map str/trim (str/split fields #",")))) default)
            unknown (remove #(contains? row %) wanted)]
        (when (seq unknown)
          (fail! "unknown-field" (str "no field " (str/join ", " (map name unknown))
                                      "; fields: " (str/join ", " (map name (keys row))))))
        (apply array-map (mapcat (fn [k] [k (get row k)]) wanted))))))
