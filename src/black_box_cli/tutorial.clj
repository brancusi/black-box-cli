(ns black-box-cli.tutorial
  "`blackbox tutorial`: what an editor can do with Black Box, lesson by lesson, for an agent to teach from (what to
  ask in plain words, the commands behind it, tips). docs/EDITORS.md is the same lessons written for people
  (`bb editors` writes it; a test keeps the two the same). Every example is invented."
  (:require
   [black-box-cli.out :as out]
   [clojure.string :as str]))

(def intro
  "What the tutorial is about, in a sentence or two."
  (str "Black Box knows every clip the studio has filmed: what was said, what's in the picture, each shot, and who is "
       "heard and seen. Ask your agent in your own words; it runs `blackbox` for you, shows you the footage and "
       "builds edits you can watch, then takes them to Resolve from the camera originals."))

(def lessons
  "The lessons in teaching order: :id, :title, :what, :ask (what an editor can say), :runs ([command what it does]),
  :tips."
  [{:id "start"
    :title "Start here: what's in the archive"
    :what (str "Everything Black Box reads is already on your Mac, in Dropbox's media_meta: transcripts (English and "
               "Italian), small proxies of every clip, picture search, shot logs and names. It never touches the NAS, "
               "so it's fast and safe to use while others work.")
    :ask ["What's in the archive?" "What did we film in October?" "Is everything up to date?"]
    :runs [["blackbox" "what's indexed (months, hours, files) and the latest edits"]
           ["blackbox footage --month 2026-10" "the files of a month: day, length, lines"]
           ["blackbox status" "the index's health, and anything Dropbox hasn't brought down yet"]]
    :tips ["New footage shows up on its own: the studio's Mac mini transcribes and logs it, and Dropbox brings it here."
           "If it says `incomplete`, right-click media_meta in Finder → Make Available Offline."
           "On a new Mac: install Black Box, then run `blackbox setup agent` once so your agent knows how to use it."
           "Ask for this tutorial any time (\"give me the Black Box tutorial\"), or one lesson (\"teach me how to online an edit\")."]}
   {:id "said"
    :title "Find what was said"
    :what "Search every transcript for words, in English and Italian, then read around a hit to check the thought is complete."
    :ask ["Find where someone explains why they use this marble"
          "Every time someone says \"cava\" in September"
          "Read me the whole conversation around that line"]
    :runs [["blackbox search marble quarry cava --month 2026-09" "lines with any of the words, best first (--all: every word)"]
           ["blackbox line k7f3q2ab.41" "one line with the lines around it"]
           ["blackbox transcript C5274.MP4" "a whole file's lines"]]
    :tips ["Line ids like k7f3q2ab.41 never change: keep them in notes or a cut list."
           "A moment heard on a camera and a lav mic shows once, with the other devices under `also`."
           "Speaker labels (S1, S2) are per file; for real names, see the people lesson."]}
   {:id "people"
    :title "Who's speaking, who's on screen"
    :what "The studio names voices and faces on Black Box's People page, so you can search by person."
    :ask ["Make a cut of Mara talking about the kiln" "Where is Mara seen in October?" "Who is speaking in this line?"]
    :runs [["blackbox people" "everyone named, and how many clips they're heard and seen in"]
           ["blackbox search kiln fire --person Mara" "what one person said"]
           ["blackbox footage --person Mara --month 2026-10" "the clips they're heard or seen in"]
           ["blackbox whose k7f3q2ab.41" "who a voice may be, with scores"]]
    :tips ["A score is a guess: your agent says \"may be\" unless the studio published the name."]}
   {:id "picture"
    :title "Find by what's in the picture"
    :what "Describe a shot in words, or point at a frame and find others like it, across every clip."
    :ask ["Find shots of two people at a table outdoors" "More shots like this one, from other days"
          "Show me those frames side by side"]
    :runs [["blackbox look two people at a table --month 2026-09 --per-clip 2" "frames that match the words"]
           ["blackbox like k7f3q2ab.f12" "frames that look like this one"]
           ["blackbox frame k7f3q2ab.f12" "that frame as a picture"]
           ["blackbox look boat at sea --open" "a page of the frames in your browser"]]
    :tips ["Picture search needs the Black Box app open; the first search on a Mac downloads its model once (0.9 GB)."]}
   {:id "shots"
    :title "B-roll: shots, camera moves and best moments"
    :what (str "Every clip is logged shot by shot: framing, camera move, what happens, its best few seconds and any "
               "issues (shake, focus).")
    :ask ["Find close-ups of hands carving stone, nothing shaky" "Slow pans over the quarry for b-roll"
          "What's the best moment of C5274?"]
    :runs [["blackbox shots hands carving stone --framing close --clean" "close shots with no issues logged"]
           ["blackbox shots --camera pan --month 2026-09" "every pan that month"]
           ["blackbox shots --file C5274.MP4" "one clip's shots in order, with its summary"]]
    :tips ["The logs are a model's reading: look at the frame before trusting a description."
           "In a cut, a shot plays its best moment (k7f3q2ab.s3) or all of it (k7f3q2ab.s3 whole)."]}
   {:id "cut"
    :title "Rough cuts you can watch at once"
    :what (str "Ask for a cut and it's built from the proxies and opens straight away. Each edit is kept in "
               "~/Black Box/edits with its video (edit.mp4), its EDL and the list of its source clips, so you can play "
               "it again or take it to Resolve any time.")
    :ask ["Make me a 30-second b-roll cut of the quarry, a second per shot" "String out every answer about the kiln"
          "Play yesterday's quarry cut again" "Swap the third shot for something wider"]
    :runs [["blackbox edl new - --play" "a cut from a cut list, played at once"]
           ["blackbox edl list" "every edit, newest first"]
           ["blackbox edl show quarry --play" "an edit's clips, played again"]]
    :tips ["Nothing is overwritten: a change makes a new edit, so you can always go back."
           "It plays proxies (960×540) with what's said as subtitles, in your Mac's default video app (Finder: Get Info → Open with → Change All)."]}
   {:id "review"
    :title "A selects review: choose, then import"
    :what (str "Ask for selects and you get a page in your browser: every clip playing its proxy with its words under "
               "it, in sections such as B-roll and Sound bites. Click a clip to see its whole transcript (a word jumps "
               "there) and trim its in and out. Tick the ones you want and press Import into Resolve: they arrive as "
               "a new timeline from the camera originals, with the recorders synced.")
    :ask ["Build me a selects review for b-roll and sound bites of the kiln firing"
          "Pull selects of Mara talking about the marble, with some quarry b-roll"
          "Open yesterday's selects review again"]
    :runs [["blackbox review new - --dry-run" "check the selects (a cut list with group: lines)"]
           ["blackbox review new - --open" "make the review and open it in the browser"]
           ["blackbox review list" "every review, newest first"]
           ["blackbox review open kiln" "open one again, with Import working"]]
    :tips ["Reviews are kept in ~/Black Box/reviews; review.html opens from Finder to watch and read, but importing needs it opened by your agent."
           "Keys in a clip: space plays, i and o set in and out, x ticks it, ← and → move to the previous or next clip."
           "Italian lines will show their English underneath once Black Box publishes translations."]}
   {:id "online"
    :title "Online an edit in Resolve"
    :what (str "Say \"online this\" and the edit goes back to the camera originals, 2 s of handles each side, as a new "
               "timeline in Resolve on your Mac, ready to grade. The proxies are linked, so it plays smoothly, and the "
               "recorders heard with each clip (lavs, the field recorder) come synced under it, each on its own tracks.")
    :ask ["Online this edit" "Online it with 1 second of handles into a new project"
          "Online yesterday's quarry cut into the project I have open"]
    :runs [["blackbox online quarry --dry-run" "each clip with its handles; Resolve isn't touched"]
           ["blackbox online quarry" "the timeline: in the open project, or a new project if Resolve was closed"]
           ["blackbox online quarry --pre 1 --post 1 --into new-project" "your own handles, in a new project"]
           ["blackbox online quarry --db local" "in this Mac's own Resolve database"]
           ["blackbox online quarry --no-audio" "without the synced recorders"]]
    :tips ["Once, in Resolve: Preferences → System → General → External scripting using → Local."
           "The NAS must be mounted (Finder → Go → Connect to Server); clips it can't find are listed as missing."
           "It only adds: new timelines and projects, and a name already taken gets a number. Nothing in Resolve is changed or deleted."]}
   {:id "project"
    :title "A full project, built by the studio's Mac mini"
    :what (str "For an edit you'll cut properly: the mini builds a Resolve project on the shared database with the "
               "originals, the lav audio synced and the proxies linked. It takes minutes.")
    :ask ["Build this edit as a project on the shared database" "Is the quarry project ready yet?"]
    :runs [["blackbox edl build quarry" "send it to the mini"]
           ["blackbox edl show quarry" "how the build is going, and what it found missing"]]
    :tips ["Black Box must be open on your Mac." "Each edit is built once: for changes, make a new edit and build that."]}
   {:id "source"
    :title "Where did this picture come from?"
    :what "Give it a screenshot, a thumbnail or a crop someone sent, and it finds the clip and moment, or the photo, it came from."
    :ask ["Which clip is this screenshot from?" "Find the originals of these three thumbnails"]
    :runs [["blackbox source ~/Desktop/screenshot.png" "the original and the moment, confirmed"]]
    :tips ["It finds copies, not look-alikes: for \"something like this\", ask for a picture search."
           "Black Box must be open on your Mac."]}
   {:id "stills"
    :title "Stills for print and the web"
    :what "Full-resolution, graded stills rendered from the camera originals by the mini, for a website, press or print."
    :ask ["Give me a full-res still of this frame for the website"
          "Three stills from the kiln scene, filmic look, with TIFFs, in my Downloads"]
    :runs [["blackbox still new k7f3q2ab.f12 --look filmic --tiff --out ~/Downloads" "ask the mini for the still"]
           ["blackbox still list" "every request and where it stands"]]
    :tips ["Looks: neutral (the house look), filmic, rec709, or log for a colourist."
           "It takes minutes; your agent tells you when they're ready."
           "Too warm or too cool? Ask it to balance on something white in the shot."]}])

(defn- lesson-of [id]
  (some #(when (= id (:id %)) %) lessons))

(defn- next-of [id]
  (some (fn [[a b]] (when (= id (:id a)) (:id b))) (partition 2 1 lessons)))

(defn- lesson-out [{:keys [id title what ask runs tips]}]
  (apply array-map (concat [:lesson id :title title :what what :ask ask
                            :runs (mapv (fn [[run does]] (array-map :run run :does does)) runs)
                            :tips tips]
                           (when-let [n (next-of id)] [:next n]))))

(defn tutorial-cmd
  "`blackbox tutorial [lesson|all]`."
  [{:keys [args]}]
  (let [want (:lesson args)]
    (cond
      (nil? want)
      [(array-map :tutorial intro
                  :count (count lessons)
                  :lessons (vec (map-indexed (fn [i {:keys [id title ask]}]
                                               (array-map :n (inc i) :id id :title title :try (first ask)))
                                             lessons)))
       ["Run `blackbox tutorial <id>` for a lesson: what to ask, the commands behind it, tips"
        "Run `blackbox tutorial all` for every lesson"]]

      (= "all" want)
      [(array-map :tutorial intro :count (count lessons) :lessons (mapv lesson-out lessons))
       ["Try a lesson on real footage: ask for something from its `ask`"]]

      :else
      (if-let [l (lesson-of want)]
        [(lesson-out l)
         (cond-> ["Try it on real footage: ask for something from `ask`"]
           (next-of want) (conj (str "Run `blackbox tutorial " (next-of want) "` for the next lesson")))]
        (out/fail! "not-found" (str "no lesson " want)
                   {:help [(str "Lessons: " (str/join ", " (map :id lessons)) ", or all")]})))))

(defn markdown
  "docs/EDITORS.md: the lessons for people."
  []
  (str "# Black Box for editors\n\n"
       "<!-- Written by `bb editors` from src/black_box_cli/tutorial.clj; edit the lessons there. -->\n\n"
       intro "\n\n"
       "You don't need to learn the commands: say what you want, as in the examples below. To be taught by your agent, "
       "ask it: \"give me the Black Box tutorial\". The commands are shown so you can see what it does for you.\n\n"
       (str/join "\n" (map-indexed (fn [i {:keys [id title]}] (str (inc i) ". [" title "](#" id ")")) lessons))
       "\n"
       (apply str
              (for [{:keys [id title what ask runs tips]} lessons]
                (str "\n<a id=\"" id "\"></a>\n\n## " title "\n\n" what "\n\n"
                     "**Ask:**\n\n" (apply str (map #(str "- *" % "*\n") ask)) "\n"
                     "**Behind it:**\n\n" (apply str (map (fn [[run does]] (str "- `" run "`: " does "\n")) runs))
                     (when (seq tips) (str "\n**Tips:**\n\n" (apply str (map #(str "- " % "\n") tips)))))))))
