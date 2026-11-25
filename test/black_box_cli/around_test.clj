(ns black-box-cli.around-test
  "The order a camera shot its clips in, from invented camera facts."
  (:require
   [black-box-cli.around :as around]
   [clojure.test :refer [deftest is testing]]))

(def ^:private rows
  [{:rel "media/2026/03/02/src/fx3/C0003.MP4" :kind "video" :device "FX3" :recorded_at "2026-03-02T11:00:00+01:00"}
   {:rel "media/2026/03/01/src/fx3/C0001.MP4" :kind "video" :device "FX3" :recorded_at "2026-03-01T09:00:00+01:00"}
   {:rel "media/2026/03/01/src/fx3/C0002.MP4" :kind "video" :device "FX3" :recorded_at "2026-03-01T09:10:00+01:00"}
   {:rel "media/2026/03/01/src/a7s/C0002.MP4" :kind "video" :device "A7S III" :recorded_at "2026-03-01T09:05:00+01:00"}
   {:rel "media/2026/03/01/audio/ZOOM0001.WAV" :kind "audio" :device "FX3" :recorded_at "2026-03-01T09:01:00"}
   {:rel "media/2026/03/01/src/fx3/C0009.MP4" :kind "video" :device "FX3" :timecode "08:30:00:00"}])

(deftest one-camera-in-the-order-it-shot
  (testing "the same camera (device and folder), video only, by its clock; a clip without one by its day and timecode,
            as UTC (08:30 there is 09:30 on a clock an hour ahead)"
    (is (= ["C0001" "C0002" "C0009" "C0003"]
           (mapv #(subs (:rel %) (- (count (:rel %)) 9) (- (count (:rel %)) 4))
                 (around/in-order rows "media/2026/03/01/src/fx3/C0002.MP4")))))
  (testing "a clip the facts don't know has no order"
    (is (nil? (around/in-order rows "media/2026/03/01/src/fx3/C0404.MP4"))))
  (testing "seconds on the camera's clock"
    (is (= 600 (- (around/start-of (nth rows 2)) (around/start-of (nth rows 1)))))))
