(ns review-dev
  "`bb review-dev [review-id] [--port n]`: this checkout's review server beside the installed one, for iterating on the
  review page against this Mac's own reviews. The page (src/black_box_cli/review_page.html) is read on every request,
  so an edit shows on reload; an edit to a .clj under src/ restarts the server. Its pages say DEV, and it never takes
  the installed server's place (`blackbox review open` still goes there). Stop it with ctrl+c."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.index :as index]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private paths
  "This Mac's own folders, as the installed CLI finds them: the reviews and the review key."
  (index/paths {:home (System/getProperty "user.home")}))

(defn- stamp
  "The newest change among the server's Clojure sources."
  []
  (->> (fs/glob "src" "**.clj") (map #(.toMillis (fs/last-modified-time %))) (reduce max 0)))

(defn- serve! [port]
  (process/process {:extra-env {"BLACKBOX_REVIEW_DEV" "1"} :out :inherit :err :inherit}
                   "bb" "-cp" "src:skills" "-m" "black-box-cli.main" "review" "serve" "--port" (str port)))

(defn- newest-review []
  (some->> (fs/list-dir (:reviews paths)) (filter fs/directory?) (sort-by str) last fs/file-name str))

(defn- up? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true) (catch Exception _ false)))

(defn run
  "The task: serve, open the review, restart on source changes until interrupted."
  [args]
  (let [port (or (some-> (second (drop-while #(not= "--port" %) args)) parse-long) 7388)
        id (or (first (remove #(or (str/starts-with? % "--") (re-matches #"\d+" %)) args)) (newest-review))
        token (:token (json/parse-string (slurp (:review-server paths)) true))]
    (loop [p (serve! port) seen (stamp) opened false]
      (let [opened (or opened
                       (when (and id (loop [i 0] (cond (up? port) true (< i 100) (do (Thread/sleep 100) (recur (inc i))))))
                         (let [u (str "http://127.0.0.1:" port "/" id "/?t=" token)]
                           (println "review-dev:" u)
                           (process/shell "open" u)
                           true)))]
        (Thread/sleep 700)
        (let [now (stamp)]
          (if (= now seen)
            (recur p seen opened)
            (do (println "review-dev: a source changed, restarting")
                (process/destroy-tree p) @p
                (loop [i 0] (when (and (up? port) (< i 50)) (Thread/sleep 100) (recur (inc i))))
                (recur (serve! port) now opened))))))))
