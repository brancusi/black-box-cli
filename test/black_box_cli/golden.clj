(ns black-box-cli.golden
  "`bb golden`: runs test/golden/steps.json in order on one --local folder and reports which steps print exactly
  what the goldens hold (what the CLI it replaced printed, with the deliberate changes in CHANGELOG.md). The steps
  that match run as a test too (golden_test)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.support :as s]
   [clojure.string :as str]))

(defn report!
  "Prints one line per step (or only the steps named in `args`) and a total."
  [args]
  (let [only (set (map parse-long args))
        local (str (fs/create-temp-dir {:prefix "bbcli-golden-"}))
        home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        results (doall
                 (for [{:keys [n argv stdin exit expected]} (s/golden-steps)
                       :when (or (empty? only) (only n))]
                   (let [{got-exit :exit out :out} (s/cli* {:stdin stdin :home home}
                                                           (concat ["--meta" s/fixture-meta "--local" local] argv))
                         got (s/normalise out {:local local :home home})
                         ok (and (= exit got-exit) (= expected got))]
                     (println (format "%s %02d blackbox %s" (if ok "ok  " "FAIL") n (str/join " " argv)))
                     (when (and (seq only) (not ok))
                       (println "--- expected (exit" exit ")\n" expected "--- got (exit" got-exit ")\n" got))
                     ok)))]
    (println (format "%d of %d steps match" (count (filter true? results)) (count results)))
    (when (and (empty? only) (fs/exists? (fs/path local "cuts")))
      (let [want (s/golden-cut-files)
            got (s/cut-files local home)]
        (doseq [[name text] want]
          (println (format "%s cut/%s" (if (= text (get got name)) "ok  " "FAIL") name)))))))
