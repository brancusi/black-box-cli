(ns black-box-cli.people-test
  "People on the fixture's invented names and prints (four numbers a print): the names snapshot and the prints read
  from media_meta, `people`, `--person` on search and footage, a line's `person`, and `whose` and `like` scored as
  Black Box's People page scores them. Only the newest snapshot and the newest prints of a clip count, and a name
  never outlives its transcript."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- copy-meta []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- runner
  ([] (runner s/fixture-meta))
  ([meta]
   (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
         run (fn [& args]
               (let [res (s/cli* {} (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                 (assoc res :edn (read-string (:out res)))))]
     (run "index")
     run)))

(defn- code [res] (-> res :edn :error :code))

(def ^:private c5274-voice "prints/wespeaker/wespeaker-resnet34-lm-v1/media/2026/09/16/src/fx3/C5274.MP4")

(deftest the-names-and-who-is-in-how-many-clips
  (let [run (runner)]
    (is (= {:at "2026-10-20T10:00:00Z" :count 3
            :people [{:name "Mara Vento" :voices 2 :faces 2}
                     {:name "Tobia Sàsso" :voices 1 :faces 1}
                     {:name "Nadia Fumo" :voices 0 :faces 0}]}
           (dissoc (:edn (run "people")) :help)))
    (testing "the index reads the newest snapshot, and the newest prints of each clip"
      (is (= {:snapshot "20261020T100000Z" :at "2026-10-20T10:00:00Z" :people 3 :names 6}
             (:people (:edn (run "index")))))
      (is (= {:added 0 :changed 0 :unchanged 6 :removed 0 :skipped 0 :voices 3 :faces 4}
             (:prints (:edn (run "index"))))))))

(deftest a-person-by-name-alias-or-start
  (let [run (runner)
        hits #(mapv :id (:hits (:edn (apply run "search" "marble" "lagoon" "day" "drill" %&))))]
    (is (= ["pm4c24b5.1" "r54qb74c.4" "r54qb74c.3"] (hits "--person" "Mara")) "by alias, every clip she's heard in")
    (is (= (hits "--person" "mara vento") (hits "--person" "MARA")))
    (is (= ["luyk4sl6.1"] (hits "--person" "tobia sasso")) "accents ignored")
    (is (= ["luyk4sl6.1"] (hits "--person" "Tob")) "a unique start")
    (is (= "Tobia Sàsso" (:person (:edn (run "search" "drill" "--person" "tob")))))
    (is (= "not-found" (code (run "search" "x" "--person" "nobody"))))
    (testing "a line's person is its voice's name"
      (is (= [["2npshqu5.2" nil] ["r54qb74c.3" "Mara Vento"]]
             (mapv (juxt :id :person) (:hits (:edn (run "search" "marble" "--fields" "id,person")))))))))

(deftest ambiguous-names-list-who-they-could-be
  (let [meta (copy-meta)
        reg (str (fs/path meta "people/registry/20261020T100000Z.json"))]
    (spit reg (str/replace (slurp reg) "{\"aliases\": [], \"id\": \"p9f8e7d6c5b4a\", \"name\": \"Nadia Fumo\"}"
                           "{\"aliases\": [], \"id\": \"p9f8e7d6c5b4a\", \"name\": \"Marta Fumo\"}"))
    (let [{:keys [edn]} ((runner meta) "search" "x" "--person" "mar")]
      (is (= "ambiguous" (:code (:error edn))))
      (is (= "'mar' could be Mara Vento, Marta Fumo" (:message (:error edn)))))))

(deftest where-a-person-is-heard-and-seen
  (let [run (runner)]
    (is (= [{:file "C5274.MP4" :day "2026-09-16" :heard "0:07.0" :seen "0:08.0"
             :heard_at "0:00.0-0:02.0, 0:20.8-0:24.6, 0:26.7-0:27.9" :seen_at "0:12.0-0:20.0"}
            {:file "C5900.MP4" :day "2026-10-02" :heard "0:01.1" :seen nil :heard_at "0:01.0-0:02.1" :seen_at nil}
            {:file "C5900.MP4" :day "2026-10-03" :heard nil :seen "0:06.0" :heard_at nil :seen_at "0:03.0-0:09.0"}]
           (:files (:edn (run "footage" "--person" "mara" "--fields" "file,day,heard,seen,heard_at,seen_at")))))
    (is (= "0 files where Nadia Fumo is heard or seen in scope" (:result (:edn (run "footage" "--person" "nadia")))))))

(deftest whose-voice-and-whose-face
  (let [run (runner)]
    (is (= {:id "pm4c24b5.1" :kind "voice" :label "S1" :file "C5900.MP4" :named "Mara Vento (match 0.8)"
            :threshold 0.7 :count 2
            :people [{:name "Mara Vento" :score 0.8 :above true} {:name "Tobia Sàsso" :score 0.0 :above false}]}
           (dissoc (:edn (run "whose" "pm4c24b5.1")) :help))
        "the names file's score is the one computed here")
    (testing "a confirmed voice isn't its own reference"
      (is (= [{:name "Tobia Sàsso" :score 0.0 :above false}] (:people (:edn (run "whose" "r54qb74c.1"))))))
    (is (= "Mara Vento (match 0.6)" (:named (:edn (run "whose" "luyk4sl6.face1")))))
    (is (= [{:name "Mara Vento" :score 0.6 :above true}] (:people (:edn (run "whose" "luyk4sl6.face1")))))
    (is (= "no-speaker" (code (run "whose" "2npshqu5.1"))))
    (is (= "not-found" (code (run "whose" "r54qb74c.face9"))))))

(deftest voices-and-faces-like-one
  (let [run (runner)]
    (is (= [{:id "pm4c24b5.1" :day "2026-10-02" :file "C5900.MP4" :speaker "S1" :person "Mara Vento" :score 0.8}
            {:id "luyk4sl6.1" :day "2026-10-03" :file "C5900.MP4" :speaker "S1" :person "Tobia Sàsso" :score 0.0}]
           (:voices (:edn (run "like" "r54qb74c.2"))))
        "any line of the voice; never its own clip")
    (is (= [["pm4c24b5.face1" nil 0.8] ["luyk4sl6.face1" "Mara Vento" 0.6]]
           (mapv (juxt :id :person :score) (:faces (:edn (run "like" "r54qb74c.face2"))))))
    (is (= ["luyk4sl6.face1"] (mapv :id (:faces (:edn (run "like" "r54qb74c.face2" "--day" "2026-10-03"))))))
    (is (= "usage" (code (run "like" "r54qb74c.2" "--html"))))))

(deftest only-the-newest-prints-of-a-clip
  (let [meta (copy-meta)
        run (runner meta)
        newer (str (fs/path meta (str c5274-voice ".20261025T100000Z.prints.json")))
        doc (json/parse-string (slurp (str (fs/path meta (str c5274-voice ".20261020T100000Z.prints.json")))))]
    (spit newer (json/generate-string (assoc doc "items" [] "vectors" "")))
    (is (= {:added 0 :changed 1 :unchanged 5 :removed 0 :skipped 0 :voices 2 :faces 4} (:prints (:edn (run "index")))))
    (is (= "not-found" (code (run "whose" "r54qb74c.1"))) "an opted-out voice is gone, though older prints stay")))

(deftest a-name-never-outlives-its-transcript
  (let [meta (copy-meta)
        t (str (fs/path meta "transcripts/deepgram-nova-3-multi/media/2026/10/02/src/fx3/C5900.MP4.json"))
        _ (spit t (str/replace (slurp t) "\"S1\"" "\"S1\" "))
        run (runner meta)]
    (is (= [["r54qb74c.4" "Mara Vento"] ["r54qb74c.3" "Mara Vento"]]
           (mapv (juxt :id :person) (:hits (:edn (run "search" "marble" "lagoon" "drill" "--person" "mara"
                                                      "--fields" "id,person"))))))
    (is (= ["2026-09-16" "2026-10-03"] (mapv :day (:files (:edn (run "footage" "--person" "mara"))))))))

(deftest snapshots-old-broken-or-missing
  (testing "a snapshot from before 0.36.0 names, but doesn't score"
    (let [meta (copy-meta)]
      (fs/delete (fs/path meta "people/registry/20261020T100000Z.json"))
      (let [run (runner meta)]
        (is (= {:snapshot "20261001T000000Z" :at "2026-10-01T00:00:00Z" :people 1 :names 1}
               (:people (:edn (run "index")))))
        (is (= ["r54qb74c.3"] (mapv :id (:hits (:edn (run "search" "marble" "--person" "mara"))))))
        (is (= "no-matching" (code (run "whose" "r54qb74c.1")))))))
  (testing "an assignments file that doesn't match its sha256 is left out"
    (let [meta (copy-meta)
          f (str (fs/path meta "people/assignments/20261020T100000Z.jsonl"))]
      (spit f (str (slurp f) "\n"))
      (is (= "skipped: assignments/20261020T100000Z.jsonl doesn't match its sha256"
             (:people (:edn ((runner meta) "index")))))))
  (testing "no people folder"
    (let [meta (copy-meta)]
      (fs/delete-tree (fs/path meta "people"))
      (fs/delete-tree (fs/path meta "prints"))
      (let [run (runner meta)]
        (is (= "none: media_meta has no people folder" (:people (:edn (run "index")))))
        (is (= "none: media_meta has no prints folder" (:prints (:edn (run "index")))))
        (is (= "no-people" (code (run "people"))))
        (is (nil? (:person (first (:hits (:edn (run "search" "marble" "--fields" "all")))))))))))
