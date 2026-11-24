(ns narrate
  "`bb narrate`: the animated tutorial's voice. Says each narration line that has no audio yet with ElevenLabs
  (the `elevenlabs` CLI, signed in on this Mac), measures it with ffprobe, and writes
  src/black_box_cli/tutorial_voice/<sha>.mp3 and voice.edn. Lines no longer said are removed. A development task:
  the CLI ships the audio and never calls ElevenLabs."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [black-box-cli.tutorial :as tutorial]
   [clojure.pprint :as pp]
   [clojure.string :as str]))

(def ^:private dir "src/black_box_cli/tutorial_voice")

(defn- seconds [f]
  (-> (process/sh "ffprobe" "-v" "error" "-show_entries" "format=duration" "-of" "csv=p=0" (str f))
      :out str/trim parse-double (* 1000) Math/round (/ 1000.0)))

(defn narrate!
  "Makes the missing audio; prints what it did."
  [& {:keys [dry-run]}]
  (fs/create-dirs dir)
  (let [lines (tutorial/narration-lines)
        want (into {} (map (fn [[_ text]] [(tutorial/line-sha text) text])) lines)
        old (tutorial/voice-manifest)
        {:keys [voice model format]} tutorial/voice
        made (reduce-kv
              (fn [m sha text]
                (let [f (fs/path dir (str sha ".mp3"))]
                  (if (and (fs/exists? f) (get old sha))
                    (assoc m sha (get old sha))
                    (do (println "saying" sha (pr-str text))
                        (when-not dry-run
                          (let [{:keys [exit err]} (process/sh "elevenlabs" "say" "--voice" voice "--model" model
                                                               "--output-format" format "-o" (str f) text)]
                            (when-not (zero? exit) (throw (ex-info (str "elevenlabs failed: " err) {:sha sha})))))
                        (assoc m sha {:text text :seconds (if dry-run 0.0 (seconds f))})))))
              (sorted-map) want)]
    (doseq [f (fs/glob dir "*.mp3")
            :let [sha (str/replace (str (fs/file-name f)) ".mp3" "")]
            :when (not (want sha))]
      (println "removing" (str (fs/file-name f)))
      (when-not dry-run (fs/delete f)))
    (when-not dry-run
      (spit (str (fs/path dir "voice.edn"))
            (str ";; Written by `bb narrate`: each narration line's audio and its length. Don't edit by hand.\n"
                 (with-out-str (pp/pprint made)))))
    (println (count want) "lines," (reduce + 0 (map count (vals want))) "characters")))
