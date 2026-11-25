(ns black-box-cli.heard-test
  (:require
   [black-box-cli.heard :as heard]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- line
  "A line from `start` to `end` saying `text`, its words spread evenly over it; `from` a recorder's name."
  [start end text & [from]]
  (let [ws (str/split text #" ")
        step (/ (- end start) (count ws))]
    (cond-> {:start start :end end :text text
             :words (vec (map-indexed (fn [i w] [w (+ start (* i step)) (+ start (* (inc i) step))]) ws))}
      from (assoc :from from))))

(deftest the-recorders-win
  (let [camera [(line 0.0 4.0 "Each peace is an addition here.")
                (line 10.0 13.0 "Tell me what you said there.")
                (line 30.0 33.0 "Only the camera heard this one.")
                (line 20.5 21.5 "Anything.")]
        lav-a [(line 0.2 4.1 "Each piece is an edition here." "A.WAV")
               (line 10.0 14.0 "Waterproof material makes sculpture lighter." "A.WAV")
               (line 20.0 24.0 "We have to find another material." "A.WAV")]
        lav-b [(line 0.1 2.0 "Each piece is" "B.WAV")
               (line 10.5 13.5 "Waterproof material makes sculpture" "B.WAV")
               (line 21.0 23.0 "Bring the ladder over tomorrow please." "B.WAV")]
        lines (heard/fill camera (heard/from-recorders (concat lav-a lav-b)))
        at (fn [t] (mapv :text (filter #(and (:from %) (= t (:start %))) lines)))]
    (testing "a moment the camera and a recorder both heard is the recorder's text"
      (is (= ["Each piece is an edition here."] (at 0.2)))
      (is (not-any? #(= "Each peace is an addition here." (:text %)) lines) "the camera's version is dropped"))
    (testing "the same moment on two recorders is kept once, the fullest"
      (is (empty? (at 0.1)))
      (is (= ["Waterproof material makes sculpture lighter."] (at 10.0)))
      (is (empty? (at 10.5))))
    (testing "people talking over each other on two recorders: both kept"
      (is (= ["We have to find another material."] (at 20.0)))
      (is (= ["Bring the ladder over tomorrow please."] (at 21.0))))
    (testing "the camera's own line stays where it says something else (marked also), or where no recorder heard it"
      (is (some #(and (= "Tell me what you said there." (:text %)) (:also %)) lines))
      (is (some #(and (= "Only the camera heard this one." (:text %)) (not (:also %)) (not (:from %))) lines)))
    (testing "a camera fragment too short to tell apart isn't kept beside the recorders"
      (is (not-any? #(= "Anything." (:text %)) lines)))
    (testing "in order of start"
      (is (apply <= (map :start lines))))
    (testing "a clip without recorders is its own lines"
      (is (= (sort-by :start camera) (heard/fill camera []))))))
