(ns black-box-cli.test-runner
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn run
  "Runs every test namespace (or the ones named in `args`) and exits non-zero on a failure."
  [args]
  (let [namespaces (if (seq args) (map symbol args) (test-namespaces))]
    (apply require namespaces)
    (let [{:keys [fail error]} (apply t/run-tests namespaces)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
