(ns black-box-cli.tc-test
  "Times and timecode as the old CLI's Python printed them: half to even on the double's exact value."
  (:require
   [black-box-cli.tc :as tc]
   [clojure.test :refer [deftest is]]))

(deftest times-round-like-python
  (is (= "0:00.2" (tc/hms 0.25)))          ; 0.25 is exact: half to even
  (is (= "0:10.8" (tc/hms 10.75)))
  (is (= "0:15.4" (tc/hms 15.45)))         ; 15.45 is a hair under in binary
  (is (= "24:57.0" (tc/hms 1497)))
  (is (= "1:00:00.0" (tc/hms 3600)))
  (is (= "0:00.0" (tc/hms -3)))
  (is (nil? (tc/hms nil)))
  (is (= 0.4 (tc/round-to (/ 1532.5 3600) 1)))
  (is (= 2.675 (tc/round-to 2.675 3))))

(deftest rates-are-fractions-between-1-and-240
  (is (= [24000 1001] (tc/parse-rate "24000/1001")))
  (is (= [25 1] (tc/parse-rate "25")))
  (is (= [2997 100] (tc/parse-rate "29.97")))
  (is (= [25 1] (tc/parse-rate 25)))
  (is (nil? (tc/parse-rate "0")))
  (is (nil? (tc/parse-rate "1000")))
  (is (nil? (tc/parse-rate "fast")))
  (is (nil? (tc/parse-rate nil))))

(deftest source-timecode
  (let [clip {:clock_kind "timecode" :start_tc "03:06:51:08" :rate "24000/1001"}]
    (is (= "03:06:51:08" (tc/source-tc clip 0.0)))
    (is (= "03:07:13:12" (tc/source-tc clip 22.2))))
  (is (= "00:00:15:10" (tc/source-tc {:clock_kind "elapsed"} 15.45)))
  (is (= "10:00:01:00" (tc/source-tc {:clock_kind "time_of_day" :tod_s 36000.0 :rate "25"} 1.0)))
  (is (= "00:01:00;02" (tc/frames->tc 1800 [30000 1001] true)))
  (is (= 1800 (tc/tc->frames "00:01:00;02" [30000 1001]))))
