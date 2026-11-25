(ns black-box-cli.heard-test
  (:require
   [black-box-cli.heard :as heard]
   [clojure.test :refer [deftest is testing]]))

(defn- line [start end text & [from]]
  (cond-> {:start start :end end :text text} from (assoc :from from)))

(deftest a-cameras-gaps-are-filled-from-its-recorders
  (let [camera [(line 0.0 4.0 "Each piece is an edition.") (line 30.0 33.0 "Very nice.")]
        lav-a [(line 0.2 4.1 "Each piece is an edition." "A.WAV")
               (line 10.0 14.0 "It makes you want to pick them up." "A.WAV")
               (line 20.0 24.0 "We have to make it waterproof." "A.WAV")]
        lav-b [(line 10.1 13.9 "It makes you want to pick them up." "B.WAV")
               (line 25.0 28.0 "Only the second lav heard this." "B.WAV")]
        filled (heard/fill camera (concat lav-a lav-b))]
    (testing "the camera's own lines stay; a recorder's line fills only where nothing is said"
      (is (= ["Each piece is an edition." "It makes you want to pick them up." "We have to make it waterproof."
              "Only the second lav heard this." "Very nice."]
             (mapv :text filled)))
      (is (= [nil "A.WAV" "A.WAV" "B.WAV" nil] (mapv :from filled)) "the preferred recorder first, the next where it has nothing"))
    (testing "in order of start, and a camera with no gaps is left as it is"
      (is (apply <= (map :start filled)))
      (is (= camera (heard/fill camera [(line 0.1 3.9 "Each piece is an edition." "A.WAV")]))))))
