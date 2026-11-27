(ns black-box-cli.photos-test
  "Photos beside the footage: in the picture index (kind photo) and described by Black Box (blackbox.photos/1), found
  by `photos`, `look --photos` and `like`, shown by `frame` and asked for full size by `still new`. Two invented
  photos are added to a copy of the fixture: one described, one not."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.frames :as frames]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.nio ByteBuffer ByteOrder)
   (java.util Base64)))

(def ^:private label-dir "embeddings/google/siglip2-base-patch16-256-5s")

(def ^:private described "media/2026/09/16/images/camb/P0001.ARW")

(def ^:private plain "media/2026/09/17/images/phone/IMG_0002.JPG")

(defn- f16-base64
  "Four numbers as float16 little-endian, base64: a frames file's vector."
  [xs]
  (let [b (.order (ByteBuffer/allocate (* 2 (count xs))) ByteOrder/LITTLE_ENDIAN)]
    (doseq [x xs] (.putShort b (Float/floatToFloat16 (float x))))
    (.encodeToString (Base64/getEncoder) (.array b))))

(defn- add-photo!
  "A photo at `rel` in `meta`: its proxy (a JPEG, when ffmpeg is here) and its frames file (one frame, `v`)."
  [meta rel sha v]
  (let [proxy (str rel ".proxy.jpg")
        frames (fs/path meta label-dir (str rel ".frames.json"))
        ffmpeg (tools/find-tool {} "ffmpeg")]
    (fs/create-dirs (fs/parent (fs/path meta "proxies" proxy)))
    (when ffmpeg
      (process/sh ffmpeg "-v" "error" "-f" "lavfi" "-i" "testsrc=size=320x240" "-frames:v" "1"
                  (str (fs/path meta "proxies" proxy))))
    (fs/create-dirs (fs/parent frames))
    (spit (str frames)
          (json/generate-string
           {:schema "media-mgmt.embeddings/1"
            :source {:rel rel :name (str (fs/file-name rel)) :sha256 sha :duration nil :start_timecode nil :rate nil
                     :kind "photo"}
            :proxy {:rel proxy :size 1000 :duration 0.0 :codec "jpeg"}
            :producer {:label "google/siglip2-base-patch16-256-5s" :model {:repo "google/siglip2-base-patch16-256" :dim 4}}
            :dim 4 :dtype "float16" :items [{:n 1 :t 0.0 :pts 0.0 :frame nil}] :vectors (f16-base64 v)}))))

(defn- f32-base64 [xs]
  (let [b (.order (ByteBuffer/allocate (* 4 (count xs))) ByteOrder/LITTLE_ENDIAN)]
    (doseq [x xs] (.putFloat b (float x)))
    (.encodeToString (Base64/getEncoder) (.array b))))

(def ^:private proxy-sha (apply str (repeat 64 "3")))

(defn- add-faces!
  "Two faces in the described photo, as Black Box 0.45.4 publishes them: F1 Mara's (confirmed, the vector of her
  confirmed face in C5274), F2 unnamed (C5274's F1). The names file gains F1's line, and the registry its sha256."
  [meta]
  (let [f (fs/path meta "prints/fal/auraface-glintr100-v1" (str described ".20261127T100000Z.prints.json"))
        reg-f (str (fs/path meta "people/registry/20261020T100000Z.json"))
        reg (json/parse-string (slurp reg-f) true)
        asg (str (fs/path meta "people" (:assignments reg)))]
    (fs/create-dirs (fs/parent f))
    (spit (str f)
          (json/generate-string
           {:schema "media-mgmt.prints/1" :kind "face" :dim 4 :dtype "float32" :byte_order "little" :normalised "L2"
            :source {:rel described :sha256 (apply str (repeat 64 "1")) :kind "photo"}
            :transcript {:label "photo" :sha256 proxy-sha}
            :producer {:label "fal/auraface-glintr100-v1" :tool "black-box" :version "0.45.4"}
            :items [{:label "F1" :seconds 1 :ranges [[0 1]]} {:label "F2" :seconds 1 :ranges [[0 1]]}]
            :vectors (f32-base64 [0 1 0 0 1 0 0 0])}))
    (spit asg (str (json/generate-string
                    {:schema "media-mgmt.people/1" :kind "face" :label "F1" :person "p1a2b3c4d5e6f" :how "confirmed"
                     :rel described :source_sha256 (apply str (repeat 64 "1")) :transcript "photo"
                     :transcript_sha256 proxy-sha :ranges [[0 1]]})
                   "\n")
          :append true)
    (spit reg-f (json/generate-string (assoc reg :assignments_sha256 (frames/sha256-hex (fs/read-all-bytes asg))
                                             :lines (inc (:lines reg)))))))

(defn- copy-meta []
  (let [meta (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")
        desc (fs/path meta "photos/gemini-3.1-pro/v1" (str described ".photo.json"))]
    (fs/copy-tree s/fixture-meta meta)
    (add-photo! meta described (apply str (repeat 64 "1")) [0.6 0.8 0.0 0.0])
    (add-photo! meta plain (apply str (repeat 64 "2")) [0.0 0.6 0.8 0.0])
    (fs/create-dirs (fs/parent desc))
    (spit (str (fs/path meta "photos" ".blackbox-photos-root")) "")
    (spit (str desc)
          (json/generate-string
           {:schema "blackbox.photos/1"
            :photo {:rel described :sha256 (apply str (repeat 64 "1")) :kind "photo" :taken_at "2026-09-16T10:00:00"
                    :make "MAKER" :camera "CAM-B" :lens "35mm F2" :width 6000 :height 4000 :megapixels 24.0}
            :proxy {:rel (str described ".proxy.jpg")}
            :description "A carved stone block on a workbench in a sunny yard."
            :subjects ["stone block" "workbench"] :setting "yard" :framing "medium" :tags ["stone" "carving"]
            :quality {:focus "sharp" :motion_blur false :exposure "good" :noise "low" :issues []}
            :print {:score 4 :why "Sharp and well lit."}
            :orientation "landscape"}))
    (add-faces! meta)
    (str meta)))

(defn- fake-black-box
  "Black Box: `embed query` answers one vector; `still request` keeps what it was sent and queues it."
  [dir]
  (let [f (str (fs/path dir "BlackBox"))]
    (spit f (str "#!/bin/sh\n"
                 "case \"$2\" in\n"
                 "  embed) echo '{\"model\": \"google/siglip2-base-patch16-256\", \"label\": "
                 "\"google/siglip2-base-patch16-256-5s\", \"dim\": 4, \"vector\": [0.6, 0.8, 0.0, 0.0]}' ;;\n"
                 "  still) cat > '" dir "/sent'; echo '{\"request\": \"s-1\", \"state\": \"queued\"}' ;;\n"
                 "esac\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(defn- runner [meta]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        dir (str (fs/create-temp-dir {:prefix "bbcli-app-"}))
        app (fake-black-box dir)
        run (fn [& args]
              (let [res (s/cli* {:home home :env {"BLACKBOX_APP" app}}
                                (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    {:run run :dir dir}))

(deftest photos-stand-beside-the-footage
  (let [{:keys [run dir]} (runner (copy-meta))
        indexed (run "index")
        photo (:id (first (:photos (run "photos" "stone"))))]
    (testing "index reads the descriptions"
      (is (= {:described 1 :skipped 0} (:photos indexed))))
    (testing "photos: every one in scope, newest first; words search what's described"
      (let [all (run "photos")]
        (is (= [2 1 2] ((juxt :in_scope :described :total) all)))
        (is (= ["IMG_0002.JPG" "P0001.ARW"] (map :file (:photos all))))
        (is (= ["not described yet" "A carved stone block on a workbench in a sunny yard."]
               (map :description (:photos all)))))
      (let [hit (run "photos" "workbench" "--fields" "all")]
        (is (= 1 (:count hit)))
        (is (= ["P0001.ARW" "MAKER CAM-B, 35mm F2" "6000x4000 (24 MP)" 4 "stone block, workbench"]
               ((juxt :file :camera :size :print :subjects) (first (:photos hit)))))
        (is (some #(str/includes? % "look workbench --photos") (:help hit)) "only 1 of 2 is described"))
      (is (= 0 (:count (run "photos" "stone" "--min-print" "5"))))
      (is (= 1 (:count (run "photos" "--camera" "cam-b"))))
      (is (= 0 (:count (run "photos" "--day" "2026-09-18")))))
    (testing "look and like: photos with the footage, or only one or the other"
      (let [both (run "look" "stone" "--per-clip" "1" "--limit" "20")
            kinds (frequencies (map :kind (:frames both)))]
        (is (= 2 (kinds "photo")))
        (is (pos? (kinds "video" 0)))
        (is (= #{"photo"} (set (map :kind (:frames (run "look" "stone" "--photos"))))))
        (is (= #{"video"} (set (map :kind (:frames (run "look" "stone" "--video"))))))
        (is (= 2 (:exit (run "look" "stone" "--photos" "--video"))) "nothing left: a usage error")
        (is (= ["" ""] ((juxt :at :tc) (first (filter #(= "photo" (:kind %)) (:frames both))))) "a photo has no time"))
      (is (= (str photo " (P0001.ARW, a photo)") (:query (run "like" photo)))))
    (when (tools/find-tool {} "ffmpeg")
      (testing "frame: the photo, with what Black Box says of it; by its id or its file's name"
        (let [shown (run "frame" photo)]
          (is (= ["photo" "MAKER CAM-B, 35mm F2" "4 of 5: Sharp and well lit."]
                 [(:kind shown) (get-in shown [:photo :camera]) (get-in shown [:photo :print])]))
          (is (str/ends-with? (:jpeg shown) ".jpg"))
          (is (some #(str/includes? % (str "still new " photo)) (:help shown))))
        (is (= photo (:id (run "frame" "P0001.ARW"))))
        (is (= "not described yet (Black Box describes photos on the mini, a month at a time)"
               (:photo (run "frame" "IMG_0002.JPG"))))))
    (testing "faces in photos: counted, named, found, and the photos a person is in"
      (let [clip (first (str/split photo #"\."))]
        (is (= {:name "Mara Vento" :voices 2 :faces 3} (first (:people (run "people")))) "seen in 2 clips and 1 photo")
        (is (= "Mara Vento (confirmed)" (:named (run "whose" (str clip ".face1")))))
        (is (= "no one" (:named (run "whose" (str clip ".face2")))))
        (let [faces (:faces (run "like" "luyk4sl6.face1"))]
          (is (some #(= [(str clip ".face1") "photo" "Mara Vento"] ((juxt :id :at :person) %)) faces)))
        (is (= [(str clip ".face1") (str clip ".face2")] (map :id (:faces (run "frame" photo)))))
        (let [with-mara (run "photos" "--person" "mara" "--fields" "id,people")]
          (is (= [[photo "Mara Vento"]] (map (juxt :id :people) (:photos with-mara))))
          (is (str/includes? (:scope with-mara) "with Mara Vento")))
        (is (= 0 (:count (run "photos" "--person" "tobia"))))
        (is (some #(str/includes? % "photos --person \"Mara Vento\"` for the 1 photo they're in")
                  (:help (run "footage" "--person" "mara"))))))
    (testing "still new: a photo is asked for whole, named by itself"
      (let [res (run "still" "new" photo)
            sent (json/parse-string (slurp (str (fs/path dir "sent"))) true)]
        (is (= [["P0001" "P0001.ARW" "photo"]] (map (juxt :name :file :at) (:stills res))))
        (is (= [{:n 1 :rel described :sha256 (apply str (repeat 64 "1")) :t 0.0 :name "P0001"}] (:items sent)))))))
