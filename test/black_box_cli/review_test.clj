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
        (is (= 2 (count (re-seq #"</script>" html))))))
    (testing "list"
      (is (= ["Kiln selects"] (map :title (:reviews (run nil "review" "list"))))))))

(deftest the-page-is-in-the-json-safely
  (let [html (review/page {:title "x</script><script>alert(1)</script>" :clips []})]
    (is (= 2 (count (re-seq #"</script>" html))))
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
        folder (str (fs/path local "reviews" id))
        ctx {:home home :env env :meta s/fixture-meta :local local :opts {} :fmt :edn}
        {:keys [url port stop]} (review/start! ctx folder)
        token (second (re-find #"\?t=(\w+)" url))
        get* #(http/get (str "http://127.0.0.1:" port %1) (merge {:throw false} %2))]
    (try
      (is (str/starts-with? url (str "http://127.0.0.1:" port "/?t=")))
      (testing "the key and the host"
        (is (= 403 (:status (get* "/" {}))))
        (is (= 403 (:status (get* "/?t=nope" {}))))
        (is (str/starts-with? (raw-status port (str "/?t=" token) "elsewhere.example") "HTTP/1.1 403")
            "a page elsewhere that points a name at 127.0.0.1 is refused")
        (let [r (get* (str "/?t=" token) {})]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "Kiln selects"))))
      (testing "the proxy, in ranges; only the review's own"
        (let [r (get* (str "/media/0?t=" token) {:headers {"range" "bytes=0-3"} :as :bytes})]
          (is (= 206 (:status r)))
          (is (= 4 (count (:body r)))))
        (is (= 404 (:status (get* (str "/media/7?t=" token) {})))))
      (testing "Import: the chosen clips, trimmed, to online"
        (let [r (http/post (str "http://127.0.0.1:" port "/api/import?t=" token)
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
      (testing "an Import with nothing chosen says so"
        (let [r (http/post (str "http://127.0.0.1:" port "/api/import?t=" token)
                           {:throw false :body (json/generate-string {:clips []})})]
          (is (= "usage" (get-in (json/parse-string (:body r) true) [:error :code])))))
      (finally (stop)))))

(deftest open-and-wait
  (let [{:keys [run]} (runner)
        {:keys [id]} (run selects "review" "new" "-")
        opened (atom nil)]
    (with-redefs [tools/spawn! (fn [argv] (reset! opened argv))
                  review/start! (fn [_ _] {:url "http://127.0.0.1:1/?t=k" :done (doto (promise) (deliver true))})]
      (let [out (run nil "review" "open" id)]
        (is (= "http://127.0.0.1:1/?t=k" (:url out)))
        (is (= ["/usr/bin/open" "http://127.0.0.1:1/?t=k"] @opened))))))
