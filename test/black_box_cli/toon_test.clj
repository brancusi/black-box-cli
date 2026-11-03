(ns black-box-cli.toon-test
  "The encoder against what the Black Box repo's toon.py printed for the same values (test/golden/toon/cases.json)."
  (:require
   [black-box-cli.toon :as toon]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest every-case-prints-what-python-printed
  (doseq [{:strs [name in] :as c} (json/read-str (slurp "test/golden/toon/cases.json"))]
    (testing name
      (is (= (get c "toon") (toon/dumps in)))
      (is (= (get c "json") (toon/dumps-json in))))))

(deftest numbers-read-as-python-writes-them
  (is (= ["0.4" "22.2" "1e-05" "12345678.5" "1e+16" "1.5e+16" "0.0001" "-0.0" "100.0"]
         (map toon/py-float [0.4 22.2 1e-5 12345678.5 1e16 1.5e16 1e-4 -0.0 100.0])))
  (is (= "n: 2\n" (toon/dumps {:n 2.0})) "a whole double prints without .0, as toon.py's str(int(v))"))

(deftest key-order-is-the-maps-own
  (let [m (apply array-map (mapcat (fn [i] [(keyword (str "k" i)) i]) (range 12)))]
    (is (= (map #(str "k" % ": " %) (range 12))
           (str/split-lines (toon/dumps m))))))
