(ns black-box-cli.index-test
  "The index is a cache: the same media_meta gives the same rows however it was built (decision 2), changes and
  removals in media_meta come through, and a build never leaves anything but the index behind."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.db :as db]
   [black-box-cli.ids :as ids]
   [black-box-cli.index :as index]
   [black-box-cli.support :as s]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private tree "transcripts/deepgram-nova-3-multi")

(defn- copy-meta
  "A copy of the fixture media_meta in a new temporary folder, to change."
  []
  (let [dir (fs/path (fs/create-temp-dir {:prefix "bbcli-meta-"}) "media_meta")]
    (fs/copy-tree s/fixture-meta dir)
    (str dir)))

(defn- build [meta local & {:as more}]
  (index/build! (merge {:meta meta :index-path (:index (index/paths {:local local})) :version index/default-version}
                       more)))

(defn- dump
  "Every row a query can see, without row ids or file times: what must be the same however the index was built."
  [local]
  (let [path (:index (index/paths {:local local}))]
    [(db/query path "SELECT version, rel, clip, json_sha256, json_size, source_path, source_name, source_sha256,
                            duration, clock_kind, start_tc, rate, tod_s, language, engine
                     FROM transcripts ORDER BY version, rel;")
     (db/query path "SELECT c.id, t.rel, c.n, c.start, c.end, c.text, c.speaker, c.language, c.words
                     FROM cues c JOIN transcripts t ON t.id = c.transcript_id ORDER BY c.id;")
     (db/query path "SELECT c.id FROM cues_fts f JOIN cues c ON c.rowid = f.rowid
                     WHERE cues_fts MATCH 'marble OR marmo OR lagoon' ORDER BY bm25(cues_fts), c.id;")]))

(defn- temp [] (str (fs/create-temp-dir {:prefix "bbcli-local-"})))

(deftest a-fresh-build-reads-every-transcript
  (let [local (temp)]
    (is (= {:added 4 :changed 0 :unchanged 0 :removed 0 :skipped 1 :lines_added 8}
           (build s/fixture-meta local)))
    (testing "and the next build finds nothing new"
      (is (= {:added 0 :changed 0 :unchanged 4 :removed 0 :skipped 1 :lines_added 0}
             (build s/fixture-meta local))))
    (testing "only the index is left in its folder"
      (is (= ["transcripts.sqlite"] (map (comp str fs/file-name) (fs/list-dir (fs/path local "search"))))))))

(deftest every-build-gives-the-same-rows
  (let [meta (copy-meta)
        fresh (temp)
        updated (temp)
        f (str (fs/path meta tree "media/2026/09/16/src/fx3/C5274.MP4.json"))
        original (slurp f)]
    ;; `updated` sees a change and its undoing; `fresh` only ever sees the end state.
    (build meta updated)
    (spit f (str/replace original "marble sculpture" "stone sculpture"))
    (is (= 1 (:changed (build meta updated))))
    (is (some #(= "We use this to make a stone sculpture." (:text %)) (second (dump updated))))
    (spit f original)
    (is (= 1 (:changed (build meta updated))))
    (build meta fresh)
    (is (= (dump fresh) (dump updated)))
    (testing "a rebuild from scratch, too"
      (build meta updated :rebuild true)
      (is (= (dump fresh) (dump updated))))
    (testing "ids come from the path and the cue: the same on any Mac"
      (is (= ["2npshqu5.1" "2npshqu5.2" "r54qb74c.1" "r54qb74c.2" "r54qb74c.3" "r54qb74c.4" "pm4c24b5.1" "luyk4sl6.1"]
             (map :id (sort-by (juxt :rel :n) (second (dump fresh)))))))))

(deftest a-transcript-gone-from-media-meta-leaves-the-index
  (let [meta (copy-meta)
        local (temp)]
    (build meta local)
    (fs/delete (fs/path meta tree "media/2026/10/03/src/a7s/C5900.MP4.json"))
    (is (= {:added 0 :changed 0 :unchanged 3 :removed 1 :skipped 1 :lines_added 0} (build meta local)))
    (is (= 3 (count (first (dump local)))))
    (is (empty? (db/query (:index (index/paths {:local local})) "SELECT id FROM cues WHERE id = 'luyk4sl6.1';")))))

(deftest what-isnt-a-transcript-is-skipped
  (let [meta (copy-meta)
        local (temp)]
    (spit (str (fs/path meta tree "media/2026/09/16/src/fx3/C5275.MP4.json")) "{}")
    (spit (str (fs/path meta tree "media/2026/09/16/src/fx3/C5276.MP4.json")) "not json")
    (spit (str (fs/path meta tree "media/2026/09/16/src/fx3/notes.json")) "{}")
    (fs/create-dirs (fs/path meta tree "media/.hidden"))
    (spit (str (fs/path meta tree "media/.hidden/C1.MP4.json")) "{}")
    (is (= {:added 4 :changed 0 :unchanged 0 :removed 0 :skipped 4 :lines_added 8} (build meta local)))))

(deftest two-paths-with-one-clip-fail-loudly
  ;; A real clash needs about a million paths at 40 bits, so every path gets one clip here.
  (let [local (temp)]
    (let [e (with-redefs [ids/clip (constantly "aaaaaaaa")]
              (try (build s/fixture-meta local) nil (catch clojure.lang.ExceptionInfo e e)))]
      (is (= "id-clash" (get-in (ex-data e) [:black-box-cli.out/error :code])))
      (is (str/includes? (ex-message e) "aaaaaaaa")))
    (testing "and leave no index and nothing half-built"
      (is (not (fs/exists? (fs/path local "search" "transcripts.sqlite"))))
      (is (empty? (fs/list-dir (fs/path local "search")))))))

(deftest an-index-of-another-schema-is-rebuilt
  (let [local (temp)
        path (:index (index/paths {:local local}))]
    (build s/fixture-meta local)
    (db/run! path "UPDATE meta SET value = '0' WHERE key = 'schema_version';")
    (is (= 4 (:added (build s/fixture-meta local))))
    (is (= (str index/schema-version) (:value (db/one path "SELECT value FROM meta WHERE key = 'schema_version';"))))))
