(ns black-box-cli.flags-test
  "Reading command lines against each command's flags (AXI §6, §10), and the output flags --fields, --full and
  --format edn."
  (:require
   [black-box-cli.flags :as flags]
   [black-box-cli.out :as out]
   [black-box-cli.support :as s]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- spec [& words] (first (flags/find-command words)))

(defn- usage-message
  "The message of the usage error `f` throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [code message]} (out/error-of e)]
           (is (= "usage" code))
           message))))

(deftest flags-arguments-and-defaults
  (is (= {:opts {:limit 20 :all true :month ["2026-09" "2026-10"] :path "src/fx3"}
          :args {:terms ["marble" "sculpture"]}}
         (flags/parse (spec "search") ["marble" "--all" "--month" "2026-09" "sculpture" "--month=2026-10"
                                       "--path" "src/fx3"])))
  (is (= {:opts {:context 1} :args {:id "x"}} (flags/parse (spec "line") ["x" "--context" "1"])))
  (is (= {:opts {:dry-run true} :args {:cutlist "-"}} (flags/parse (spec "edl" "new") ["-" "--dry-run"]))
      "- is stdin, not a flag")
  (is (= {:opts {:limit 20} :args {:terms ["--all"]}} (flags/parse (spec "search") ["--" "--all"]))
      "after -- everything is an argument"))

(deftest help-always-passes
  (is (= {:help true} (flags/parse (spec "search") ["x" "--bogus" "--help"])))
  (is (= {:help true} (flags/parse (spec "line") ["-h"]))))

(deftest usage-errors-name-the-problem
  (is (= "unknown flag --bogus for `blackbox search`"
         (usage-message #(flags/parse (spec "search") ["x" "--bogus"]))))
  (is (= "unknown flags --a, --b for `blackbox footage`"
         (usage-message #(flags/parse (spec "footage") ["--a" "--b=1"]))))
  (is (= "--video takes no value" (usage-message #(flags/parse (spec "search") ["x" "--video=yes"]))))
  (is (= "--limit needs a value: <n>" (usage-message #(flags/parse (spec "search") ["x" "--limit"]))))
  (is (= "--limit needs a value: <n>" (usage-message #(flags/parse (spec "search") ["x" "--limit" "--all"]))))
  (is (= "--limit needs a whole number, not 'many'"
         (usage-message #(flags/parse (spec "search") ["x" "--limit" "many"]))))
  (is (= "`blackbox search` needs <terms…>" (usage-message #(flags/parse (spec "search") []))))
  (is (= "unexpected argument 'y' for `blackbox line`" (usage-message #(flags/parse (spec "line") ["x" "y"]))))
  (is (= "--format yaml: use toon, json or edn"
         (usage-message #(flags/parse (spec "search") ["x" "--format" "yaml"]))))
  (testing "flags are per command: transcript has no --video, edl show no --fields"
    (is (some? (usage-message #(flags/parse (spec "transcript") ["f" "--video"]))))
    (is (some? (usage-message #(flags/parse (spec "edl" "show") ["i" "--fields" "id"]))))))

(deftest unknown-flag-lists-the-valid-ones
  (let [{:keys [exit out]} (s/cli "search" "x" "--bogus")]
    (is (= 2 exit))
    (is (= (str "error:\n  code: usage\n  message: unknown flag --bogus for `blackbox search`\nhelp[2]:\n"
                "  Valid flags for `blackbox search`: --all, --limit <n>, --person <name>, --every-device, --month <YYYY-MM>, --day <YYYY-MM-DD>, "
                "--path <text>, --video, --transcripts <version>, --format <toon|json|edn>, --fields <a,b>, --full "
                "(--help always allowed)\n"
                "  Run `blackbox search --help` for the options\n")
           out))))

(deftest every-command-has-help
  (doseq [{:keys [path] :as c} flags/commands]
    (let [{:keys [exit out]} (apply s/cli (concat path ["--help"]))]
      (testing (flags/command-name c)
        (is (= 0 exit))
        (is (str/starts-with? out (flags/command-name c)))
        (is (str/includes? out "\nExamples:\n  blackbox "))
        (doseq [{:keys [flag]} (:flags c)]
          (is (str/includes? out (str "  " flag))))))))

(deftest groups
  (testing "edl --help lists its commands"
    (let [{:keys [exit out]} (s/cli "edl" "--help")]
      (is (= 0 exit))
      (is (str/includes? out "blackbox edl show"))))
  (testing "a bare group, or an unknown command in it, is a usage error"
    (is (= 2 (:exit (s/cli "edl"))))
    (let [{:keys [exit out]} (s/cli "edl" "make")]
      (is (= 2 exit))
      (is (str/includes? out "unknown command `blackbox edl make`")))))

(deftest format-anywhere-on-the-line
  (is (str/starts-with? (:out (s/cli "search" "x" "--format" "json")) "{\n \"error\": {"))
  (is (str/starts-with? (:out (s/cli "--format" "json" "search" "x")) "{\n \"error\": {"))
  (is (str/starts-with? (:out (s/cli "search" "x" "--bogus" "--format=json")) "{\n \"error\": {")
      "a usage error comes out in the format asked for"))

(deftest edn-reads-back
  (let [{:keys [exit out]} (s/cli "search" "x" "--bogus" "--format" "edn")
        m (edn/read-string out)]
    (is (= 2 exit))
    (is (= {:code "usage" :message "unknown flag --bogus for `blackbox search`"} (:error m)))
    (is (= [:error :help] (keys m)))))

(deftest output-keeps-key-order-with-help
  (let [m (apply array-map (mapcat (fn [i] [(keyword (str "k" i)) i]) (range 10)))]
    (is (= (map str (concat (keys m) [:help]))
           (re-seq #":(?:k\d|help)\b" (out/render m ["next"] :edn)))
        "printed in the map's order (reading it back makes a hash map, so look at the text)")
    (is (str/ends-with? (out/render m ["next"] :json) " \"k9\": 9,\n \"help\": [\n  \"next\"\n ]\n}\n"))))

(deftest pick-fields
  (let [row (array-map :id "a" :file "f" :at 1.5 :text "t")]
    (is (= [[:id "a"] [:text "t"]] (seq (out/pick row [:id :text] {}))))
    (is (= [[:text "t"] [:id "a"]] (seq (out/pick row [:id] {:fields "text, id"}))) "the asked order")
    (is (= row (out/pick row [:id] {:fields "all"})))
    (is (= row (out/pick row [:id] {:full true})))
    (is (= {:code "unknown-field" :message "no field nope; fields: id, file, at, text"}
           (select-keys (out/error-of (try (out/pick row [:id] {:fields "id,nope"}) (catch Exception e e)))
                        [:code :message]))
        "the message golden step 17 shows")))
