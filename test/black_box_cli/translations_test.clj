(ns black-box-cli.translations-test
  "The English of lines said in another language (media_meta/translations, invented here): read into the index,
  given only to the very transcript it translates, and shown in a review, one made before it was published too. The
  fixture's C5274 is copied with its third line said in Italian."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.resolve :as resolve]
   [black-box-cli.review :as review]
   [black-box-cli.support :as s]
   [black-box-cli.translations :as translations]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.security MessageDigest)))

(def ^:private rel "media/2026/09/16/src/fx3/C5274.MP4")

(defn- sha256 [path]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes path)))))

(defn- transcript-path [meta]
  (fs/path meta "transcripts" "deepgram-nova-3-multi" (str rel ".json")))

(defn- italian-meta
  "A copy of the fixture with C5274's third line said in Italian, and no recorders synced."
  []
  (let [meta (str (fs/create-temp-dir {:prefix "bbcli-meta-"}))]
    (fs/copy-tree s/fixture-meta meta)
    (let [p (transcript-path meta)
          doc (json/parse-string (slurp (str p)) true)
          said ["Usiamo" "questo" "per" "una" "scultura."]
          cue (get-in doc [:cues 2])
          step (/ (- (:end cue) (:start cue)) (count said))
          words (vec (map-indexed (fn [i w] {:w w :start (+ (:start cue) (* i step)) :end (+ (:start cue) (* (inc i) step))}) said))]
      (spit (str p) (json/generate-string (update-in doc [:cues 2] assoc :text (str/join " " said) :words words))))
    ;; without the sync map: its recorder heard that moment in English, and a recorder's text wins over a camera's
    (fs/delete-tree (fs/path meta "sync"))
    meta))

(defn- publish!
  "Black Box's translation of the transcript whose file has `sha`, in `meta`, as version `v`."
  [meta sha v english]
  (let [root (fs/path meta "translations")
        f (fs/path root "test-model" v (str rel "." (subs sha 0 12) ".en.json"))]
    (fs/create-dirs (fs/parent f))
    (spit (str (fs/path root translations/marker)) "")
    (spit (str f) (json/generate-string
                   {:schema "media-mgmt.translation/1" :to "en"
                    :source {:rel rel :sha256 (apply str (repeat 64 "a"))}
                    :transcript {:version "deepgram-nova-3-multi" :sha256 sha :cues 4}
                    :languages {:en 3 :it 1}
                    :producer {:tool "media-mgmt" :model "test-model" :made_at "2026-11-24T17:00:00Z"}
                    :cues [{:i 2 :start 22.2 :end 24.55 :lang "it" :text english}]}))))

(defn- runner [meta]
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        run (fn [stdin & args]
              (let [res (s/cli* {:home home :stdin stdin :env {"PATH" ""}}
                                (concat ["--meta" meta "--local" local "--format" "edn"] args))]
                (assoc (edn/read-string (:out res)) :exit (:exit res))))]
    {:run run :local local :home home}))

(defn- review-lines [local id]
  (-> (json/parse-string (slurp (str (fs/path local "reviews" id "review.json"))) true)
      :files vals first :lines))

(deftest the-english-comes-with-its-line
  (let [meta (italian-meta)
        sha (sha256 (transcript-path meta))
        _ (publish! meta sha "v1" "We use this for a sculpture.")
        {:keys [run local]} (runner meta)]
    (testing "read into the index"
      (is (= {:to "en" :files 1 :lines 1 :skipped 0} (:translations (run nil "index"))))
      (is (= ["translations" "ok" "1 lines in 1 files, into English"]
             ((juxt :source :state :holds) (last (:sources (run nil "status")))))))
    (testing "a review's line said in Italian has its English, and its language"
      (let [out (run "title: Marmo\nC5274.MP4 0:20-0:25\n" "review" "new" "-")
            lines (review-lines local (:id out))
            html (slurp (str (fs/path local "reviews" (:id out) "review.html")))]
        (is (= 0 (:exit out)))
        (is (= ["en" "en" "it" "en"] (mapv :lang lines)) "a translated transcript's other lines were said in English")
        (is (= [nil "We use this for a sculpture." nil] (mapv :en (rest lines))))
        (is (= ["Usiamo" "questo"] (mapv first (take 2 (:words (nth lines 2))))) "the words as said, for the cut")
        (is (str/includes? html "We use this for a sculpture."))))
    (testing "of several, the highest version's"
      (publish! meta sha "v2" "We use this to make a sculpture.")
      (run nil "index")
      (let [out (run "title: Marmo\nC5274.MP4 0:20-0:25\n" "review" "new" "-")]
        (is (= "We use this to make a sculpture." (:en (nth (review-lines local (:id out)) 2))))))
    (testing "a translation of the transcript as it was is never given to a changed one"
      (spit (str (transcript-path meta)) (str/replace (slurp (str (transcript-path meta))) "scultura" "statua"))
      (run nil "index")
      (let [out (run "title: Marmo\nC5274.MP4 0:20-0:25\n" "review" "new" "-")]
        (is (nil? (:en (nth (review-lines local (:id out)) 2))))
        (is (every? nil? (map :lang (review-lines local (:id out)))) "and what its lines were said in isn't known")))))

(deftest a-review-reads-in-the-editors-language
  (let [meta (italian-meta)
        _ (publish! meta (sha256 (transcript-path meta)) "v1" "We use this for a sculpture.")
        {:keys [run local]} (runner meta)
        italian "title: Marmo\nC5274.MP4 0:22-0:25\n"
        doc (fn [out] (json/parse-string (slurp (str (fs/path local "reviews" (:id out) "review.json"))) true))]
    (run nil "index")
    (testing "English unless said: a clip said in Italian is counted, and the page knows; subtitles in English"
      (let [out (run italian "review" "new" "-")]
        (is (= ["en" "en" 1] ((juxt :read_in :subtitles_in :in_another_language) out)))
        (is (= ["en" "en"] ((juxt :read_in :subtitles_in) (doc out))))))
    (testing "--read-in it: the same clip is in the editor's language; --language is its old name"
      (doseq [flag ["--read-in" "--language"]]
        (let [out (run italian "review" "new" "-" flag "it")]
          (is (= ["it" 0] ((juxt :read_in :in_another_language) out)))
          (is (= "it" (:read_in (doc out)))))))
    (testing "--subtitles-in spoken: Resolve gets what was said, as it was said"
      (is (= "spoken" (:subtitles_in (doc (run italian "review" "new" "-" "--subtitles-in" "spoken"))))))
    (testing "a language's code, nothing else; subtitles only in what there are translations into"
      (let [out (run italian "review" "new" "-" "--read-in" "Italian")]
        (is (= [2 "usage"] [(:exit out) (-> out :error :code)])))
      (let [out (run italian "review" "new" "-" "--subtitles-in" "de")]
        (is (= [2 "usage"] [(:exit out) (-> out :error :code)]))
        (is (str/includes? (-> out :error :message) "wait for translations"))))))

(deftest resolve-gets-subtitles-in-english
  (let [meta (italian-meta)
        _ (publish! meta (sha256 (transcript-path meta)) "v1" "We use this for a sculpture.")
        {:keys [run local]} (runner meta)
        _ (run nil "index")
        idx (str (fs/path local "search" "transcripts.sqlite"))
        out (run "title: Marmo\nC5274.MP4 0:20-0:25\n" "review" "new" "-")
        version (:transcripts (json/parse-string (slurp (str (fs/path local "reviews" (:id out) "review.json"))) true))
        seg {:rel "media/2026/09/16/src/fx3/C5274.MP4" :start 20.0 :end 25.0}
        said (fn [target] (str/join " " (map first (#'resolve/words-in idx version seg target))))]
    (is (str/includes? (said "en") "We use this for a sculpture.") "a line said in Italian, as its English")
    (is (not (str/includes? (said "spoken") "sculpture")) "as spoken: the Italian")
    (is (str/includes? (said "spoken") "scultura"))))

(deftest resolve-gets-the-words-as-spoken-on-a-second-track
  (let [meta (italian-meta)
        _ (publish! meta (sha256 (transcript-path meta)) "v1" "We use this for a sculpture.")
        {:keys [run]} (runner meta)
        _ (run nil "index")
        italian "title: Marmo\nC5274.MP4 0:20-0:25\n"
        english "title: Marmo\nC5274.MP4 0:00-0:05\n"
        subs (fn [cut & flags] (:subtitles (apply run cut "online" "-" "--dry-run" flags)))]
    (testing "a clip said in Italian: English on the track, the Italian above it, hidden"
      (is (re-find #"in English .*; \d+ as spoken for a second track above it, hidden" (subs italian))))
    (testing "--no-spoken-subtitles: only the English"
      (is (not (re-find #"second track" (subs italian "--no-spoken-subtitles")))))
    (testing "nothing said in another language, or subtitles already as spoken: one track"
      (is (not (re-find #"second track" (subs english))))
      (is (not (re-find #"second track" (subs italian "--subtitles-in" "spoken")))))))

(deftest a-review-made-before-gets-the-english-when-served
  (let [meta (italian-meta)
        {:keys [run local home]} (runner meta)
        _ (run nil "index")
        out (run "title: Marmo\nC5274.MP4 0:20-0:25\n" "review" "new" "-")
        doc (json/parse-string (slurp (str (fs/path local "reviews" (:id out) "review.json"))) true)]
    (is (nil? (:en (nth (-> doc :files vals first :lines) 2))) "nothing published yet")
    (publish! meta (sha256 (transcript-path meta)) "v1" "We use this for a sculpture.")
    (run nil "index")
    (let [served (#'review/fresh {:local local :home home} doc)
          line (nth (-> served :files vals first :lines) 2)]
      (is (= ["it" "We use this for a sculpture."] ((juxt :lang :en) line))))))

(deftest a-file-is-only-a-translation-of-its-own-transcript
  (let [doc {:schema "media-mgmt.translation/1" :to "en" :source {:rel "media/a/C1.MP4"}
             :transcript {:sha256 "0123456789abcdef"} :cues [{:i 0 :lang "it" :text " Ciao. "} {:i -1 :text "x"} {:i 1 :text ""}]}]
    (is (= [{:n 1 :lang "it" :text "Ciao."}] (:lines (translations/row doc "media/a/C1.MP4" "0123456789ab"))))
    (is (nil? (translations/row doc "media/b/C1.MP4" "0123456789ab")) "another original")
    (is (nil? (translations/row doc "media/a/C1.MP4" "ffffffffffff")) "another transcript")
    (is (nil? (translations/row (assoc doc :to "fr") "media/a/C1.MP4" "0123456789ab")) "not into English")))
