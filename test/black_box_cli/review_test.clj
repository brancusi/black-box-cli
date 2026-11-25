(ns black-box-cli.review-test
  "`review`: selects on the fixture media_meta become a review folder (review.json, review.html), and its server
  (127.0.0.1, a key, this host only) serves the page and the proxies in ranges, and imports through a fake Resolve
  Python. Nothing opens a browser or Resolve."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [black-box-cli.review :as review]
   [black-box-cli.support :as s]
   [black-box-cli.tools :as tools]
   [black-box-cli.version :as version]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private selects
  (str "title: Kiln selects\n"
       "group: Sound bites\n"
       "C5274.MP4 0:20-0:25 # the drill line\n"
       "group: B-roll\n"
       "C5274.MP4 0:01-0:03\n"))

(defn- fake-python []
  (let [dir (fs/create-temp-dir {:prefix "bbcli-py-"})
        f (str (fs/path dir "python"))]
    (spit (str (fs/path dir "answer"))
          (str (json/generate-string {:project "Kiln (x)" :created_project false :database "Local Database"
                                      :timeline "Kiln selects (selects)" :method "edl" :clips 2 :imported 1
                                      :reused 0 :missing [] :proxies_linked 1 :launched false :audio 1
                                      :audio_placed 1})
               "\n"))
    (spit f "#!/bin/sh\ncp \"$2\" \"$(dirname \"$0\")/plan.json\"\ncat \"$(dirname \"$0\")/answer\"\n")
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    {:python f :dir (str dir)}))

(defn- runner []
  (let [local (str (fs/create-temp-dir {:prefix "bbcli-local-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        {:keys [python] :as py} (fake-python)
        env {"BLACKBOX_RESOLVE_PYTHON" python "PATH" ""}
        run (fn [stdin & args]
              (let [res (s/cli* {:home home :stdin stdin :env env}
                                (concat ["--meta" s/fixture-meta "--local" local "--format" "edn"] args))]
                (assoc (read-string (:out res)) :exit (:exit res))))]
    (run nil "index")
    (merge py {:local local :home home :env env :run run})))

(deftest selects-become-a-review
  (let [{:keys [run local]} (runner)]
    (testing "--dry-run"
      (let [out (run selects "review" "new" "-" "--dry-run")]
        (is (= 0 (:exit out)))
        (is (= ["Sound bites" "B-roll"] (:groups out)))
        (is (= [["Sound bites" "0:20.0" "0:25.0"] ["B-roll" "0:01.0" "0:03.0"]] (map (juxt :group :in :out) (:review out))))
        (is (not (fs/exists? (fs/path local "reviews"))))))
    (let [out (run selects "review" "new" "-")
          folder (fs/path local "reviews" (:id out))
          doc (json/parse-string (slurp (str (fs/path folder "review.json"))) true)
          html (slurp (str (fs/path folder "review.html")))]
      (is (= 0 (:exit out)))
      (is (str/includes? (first (:help out)) "review open"))
      (is (= "black-box-cli.review/1" (:schema doc)))
      (is (= [[1 "Sound bites" "the drill line" 20.0 25.0] [2 "B-roll" nil 1.0 3.0]]
             (map (juxt :n :group :note :start :end) (:clips doc))))
      (is (= [0 0] (map :media (:clips doc))) "one proxy, named once")
      (let [lines (:lines (get-in doc [:files (keyword "media/2026/09/16/src/fx3/C5274.MP4")]))]
        (is (seq lines) "the file's whole transcript, for the detail view")
        (is (every? #(and (:id %) (vector? (:words %))) lines)))
      (testing "the page carries the review, and nothing in it can end its script early"
        (is (str/includes? html "\"title\":\"Kiln selects\""))
        (is (not (str/includes? html "/*REVIEW_DATA*/")))
        (is (= 3 (count (re-seq #"</script>" html))))))
    (testing "list"
      (is (= ["Kiln selects"] (map :title (:reviews (run nil "review" "list"))))))))

(deftest the-page-is-in-the-json-safely
  (let [html (review/page {:title "x</script><script>alert(1)</script>" :clips []})]
    (is (= 3 (count (re-seq #"</script>" html))))
    (is (str/includes? html "x<\\/script>"))))

(deftest a-file-in-ranges
  (let [f (fs/create-temp-file {:suffix ".mp4"})]
    (spit (str f) "0123456789")
    (is (= 200 (:status (review/file-response f nil))))
    (let [r (review/file-response f "bytes=2-5")]
      (is (= 206 (:status r)))
      (is (= "bytes 2-5/10" (get-in r [:headers "content-range"])))
      (is (= "2345" (String. ^bytes (:body r)))))
    (is (= "789" (String. ^bytes (:body (review/file-response f "bytes=-3")))))
    (is (= "6789" (String. ^bytes (:body (review/file-response f "bytes=6-")))))
    (is (= 416 (:status (review/file-response f "bytes=20-"))))))

(defn- raw-status
  "The status line for GET `path` sent with Host `host` (an HTTP client won't send a forged one)."
  [port path host]
  (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
    (let [o (.getOutputStream sock)]
      (.write o (.getBytes (str "GET " path " HTTP/1.1\r\nHost: " host "\r\nConnection: close\r\n\r\n")))
      (.flush o)
      (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))))))

(deftest the-server
  (let [{:keys [run local home env dir]} (runner)
        {:keys [id]} (run selects "review" "new" "-")
        other (:id (run "title: Other selects\nC5274.MP4 0:01-0:03\n" "review" "new" "-"))
        ctx {:home home :env env :meta s/fixture-meta :local local :opts {} :fmt :edn}
        {:keys [port token stop]} (review/start! ctx 0)
        get* #(http/get (str "http://127.0.0.1:" port %1) (merge {:throw false} %2))]
    (try
      (is (= (str "http://127.0.0.1:" port "/" id "/?t=" token) (review/url port token id)))
      (testing "the key and the host"
        (is (= 403 (:status (get* (str "/" id "/") {}))))
        (is (= 403 (:status (get* (str "/" id "/?t=nope") {}))))
        (is (str/starts-with? (raw-status port (str "/" id "/?t=" token) "elsewhere.example") "HTTP/1.1 403")
            "a page elsewhere that points a name at 127.0.0.1 is refused"))
      (testing "every review, each at its own address"
        (let [a (get* (str "/" id "/?t=" token) {})
              b (get* (str "/" other "/?t=" token) {})]
          (is (= [200 200] [(:status a) (:status b)]))
          (is (str/includes? (:body a) "Kiln selects"))
          (is (str/includes? (:body b) "Other selects")))
        (is (= 404 (:status (get* (str "/nope/?t=" token) {}))))
        (is (= 404 (:status (get* (str "/..%2F/?t=" token) {}))))
        (is (= 302 (:status (get* (str "/" id "?t=" token) {:client (http/client {:follow-redirects :never})}))) "without its slash"))
      (testing "the key outlives the server: the state file keeps it"
        (is (= token (:token (json/parse-string (slurp (str (fs/path local "review-server.json"))) true)))))
      (testing "the proxy, in ranges; only the review's own"
        (let [r (get* (str "/" id "/media/0?t=" token) {:headers {"range" "bytes=0-3"} :as :bytes})]
          (is (= 206 (:status r)))
          (is (= 4 (count (:body r)))))
        (is (= 404 (:status (get* (str "/" id "/media/7?t=" token) {})))))
      (testing "Import: the chosen clips, trimmed, to online"
        (let [r (http/post (str "http://127.0.0.1:" port "/" id "/api/import?t=" token)
                           {:throw false :headers {"content-type" "application/json"}
                            :body (json/generate-string {:clips [{:n 1 :start 21 :end 24}] :pre 1 :post 1
                                                         :into "current" :audio true})})
              res (json/parse-string (:body r) true)
              plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
          (is (= 200 (:status r)))
          (is (= "Kiln selects (selects)" (get-in res [:resolve :timeline])))
          (is (= "current" (:into plan)))
          (is (= [480 599] ((juxt :in_frame :out_frame) (first (:clips plan)))) "21-24 s with 1 s each side, at 23.976")
          (is (= 1 (count (:audio plan))) "the recorder the sync map puts with it")))
      (testing "around a select: the clips its camera shot before and after, in order"
        (let [res (json/parse-string (:body (get* (str "/" id "/api/around?rel=media/2026/09/16/src/fx3/C5274.MP4&t=" token) {})) true)]
          (is (= [["C5274.MP4" true true] ["C5280.MP4" false false] ["C5900.MP4" false false]]
                 (mapv (juxt :name :current :proxy) (:clips res))) "the same FX3, on any day; the A7S isn't it")
          (is (= [nil 0 0] (mapv :gap (:clips res))) "the fixture's clocks overlap: no time between them")
          (is (= [false false] ((juxt :more_before :more_after) res))))
        (is (= [] (:clips (json/parse-string (:body (get* (str "/" id "/api/around?rel=nope.MP4&t=" token) {})) true)))))
      (testing "a file's words, a clip's proxy and still: only of originals the index knows"
        (is (= 200 (:status (get* (str "/" id "/api/file?rel=media/2026/09/16/src/fx3/C5280.MP4&t=" token) {}))))
        (is (= 404 (:status (get* (str "/" id "/api/file?rel=nope.MP4&t=" token) {}))))
        (is (= 206 (:status (get* (str "/" id "/proxy?rel=media/2026/09/16/src/fx3/C5274.MP4&t=" token)
                                  {:headers {"range" "bytes=0-3"}}))))
        (is (= 404 (:status (get* (str "/" id "/proxy?rel=media/2026/09/16/src/fx3/C5280.MP4&t=" token) {}))) "no proxy of it")
        (is (= 404 (:status (get* (str "/" id "/proxy?rel=..%2F..%2Fetc%2Fpasswd&t=" token) {})))))
      (testing "what the editor did is kept beside the review, appended, never in review.json"
        (let [before (slurp (str (fs/path local "reviews" id "review.json")))
              post #(http/post (str "http://127.0.0.1:" port "/" id "/api/state?t=" token)
                               {:throw false :headers {"content-type" "application/json"} :body (json/generate-string %)})]
          (is (= {} (json/parse-string (:body (get* (str "/" id "/api/state?t=" token) {})) true)))
          (is (= 204 (:status (post {:at 1 :order [1]}))))
          (is (= 204 (:status (post {:at 2 :order [1 100001] :extras [{:n 100001 :rel "media/2026/09/16/src/fx3/C5280.MP4"}]}))))
          (is (= 400 (:status (post [1 2]))))
          (is (= [1 100001] (:order (json/parse-string (:body (get* (str "/" id "/api/state?t=" token) {})) true))))
          (is (= 2 (count (str/split-lines (slurp (str (fs/path local "reviews" id "state.jsonl")))))))
          (is (= before (slurp (str (fs/path local "reviews" id "review.json")))))))
      (testing "Import takes a clip added from around a select, with its in and out; never one the index doesn't know"
        (let [r (http/post (str "http://127.0.0.1:" port "/" id "/api/import?t=" token)
                           {:throw false :headers {"content-type" "application/json"}
                            :body (json/generate-string {:clips [{:n 1 :start 21 :end 24}
                                                                 {:n 100001 :rel "media/2026/09/16/src/fx3/C5280.MP4" :start 2 :end 5}
                                                                 {:n 100002 :rel "/Volumes/x/y.MP4" :start 2 :end 5}]
                                                         :pre 0 :post 0 :into "current" :audio false})})
              plan (json/parse-string (slurp (str (fs/path dir "plan.json"))) true)]
          (is (= 200 (:status r)))
          (is (= 2 (count (:clips plan))))))
      (testing "an Import with nothing chosen says so"
        (let [r (http/post (str "http://127.0.0.1:" port "/" id "/api/import?t=" token)
                           {:throw false :body (json/generate-string {:clips []})})]
          (is (= "usage" (get-in (json/parse-string (:body r) true) [:error :code])))))
      (testing "/health says what it is"
        (is (= "black-box-cli.review" (:server (json/parse-string (:body (get* (str "/health?t=" token) {})) true)))))
      (finally (stop)))))

(deftest open-returns-and-keeps-the-server
  (let [{:keys [run local home env]} (runner)
        {:keys [id]} (run selects "review" "new" "-")
        other (:id (run "title: Other selects\nC5274.MP4 0:01-0:03\n" "review" "new" "-"))
        opened (atom [])
        started (atom [])
        servers (atom [])]
    (with-redefs [tools/spawn! (fn [argv] (swap! opened conj argv))
                  review/self-argv (fn [_] ["blackbox"])
                  ;; the detached server, run in this process instead: `review serve` as the spawn would
                  tools/spawn-detached! (fn [argv _]
                                          (swap! started conj argv)
                                          (let [ctx {:home home :env env :meta s/fixture-meta :local local :opts {} :fmt :edn}
                                                {:keys [port token stop]} (review/start! ctx 0)]
                                            (swap! servers conj stop)
                                            (spit (str (fs/path local "review-server.json"))
                                                  (json/generate-string {:token token :port port :cli version/version}))))]
      (try
        (let [a (run nil "review" "open" id)
              b (run nil "review" "open" other)]
          (testing "it returns at once with the page's address, started once"
            (is (= 0 (:exit a)))
            (is (str/includes? (:server a) "started"))
            (is (str/includes? (:server b) "already running"))
            (is (= 1 (count @started)) "the second review uses the running server: the first page stays open")
            (is (some #{"serve"} (first @started)))
            (is (= [["/usr/bin/open" (:url a)] ["/usr/bin/open" (:url b)]] @opened))
            (is (str/includes? (:url a) (str "/" id "/?t=")))
            (is (some #(str/includes? % "never stop") (:help a)))))
        (finally (doseq [stop @servers] (stop)))))))

(deftest the-server-starts-the-way-it-was-run
  (is (= ["/bin/sh" "--local" "/l"]
         (review/self-argv {:env {"BLACKBOX_BIN" "/bin/sh"} :argv ["review" "open" "x"] :local "/l"}))
      "Black Box's launcher, with where things are")
  (is (= ["/bin/sh"] (review/self-argv {:env {"BLACKBOX_BIN" "/bin/sh"} :argv []}))))
