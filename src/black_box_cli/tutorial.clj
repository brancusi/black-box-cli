(ns black-box-cli.tutorial
  "`blackbox tutorial`: what an editor can do with Black Box, lesson by lesson, for an agent to teach from (what to
  ask in plain words, the commands behind it, tips). docs/EDITORS.md is the same lessons written for people
  (`bb editors` writes it; a test keeps the two the same). Every example is invented."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.flags :as flags]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.tools :as tools]
   [black-box-cli.version :as version]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(def intro
  "What the tutorial is about, in a sentence or two."
  (str "Black Box knows every clip the studio has filmed: what was said, what's in the picture, each shot, and who is "
       "heard and seen. Ask your agent in your own words; it runs `blackbox` for you, shows you the footage and "
       "builds edits you can watch, then takes them to Resolve from the camera originals."))

(def lessons
  "The lessons in teaching order: :id, :title, :what, :ask (what an editor can say), :runs ([command what it does]),
  :tips, :punch (the line the animated tutorial shows) and :narration (what its voice says: `bb narrate` makes the
  audio)."
  [{:id "start"
    :punch "Every clip the studio shot, already on your Mac."
    :narration "It all lives on your Mac already: transcripts, proxies and picture search, brought down by Dropbox. Fast, and the NAS is never touched."
    :title "Start here: what's in the archive"
    :what (str "Everything Black Box reads is already on your Mac, in Dropbox's media_meta: transcripts (English and "
               "Italian), small proxies of every clip, picture search, shot logs and names. It never touches the NAS, "
               "so it's fast and safe to use while others work.")
    :ask ["What's in the archive?" "What did we film in October?" "Is everything up to date?"]
    :runs [["blackbox" "what's indexed (months, hours, files) and the latest edits"]
           ["blackbox footage --month 2026-10" "the files of a month: day, length, lines"]
           ["blackbox status" "the index's health, and anything Dropbox hasn't brought down yet"]
           ["blackbox index" "read what Dropbox has brought down (seconds; your agent does it when it's needed)"]
           ["blackbox tutorial --open" "this tutorial, animated, in your browser"]
           ["blackbox setup agent" "once on a new Mac: teaches your agent Black Box"]
           ["blackbox update" "the newest blackbox now (Black Box also checks every few minutes)"]]
    :tips ["New footage shows up on its own: the studio's Mac mini transcribes and logs it, and Dropbox brings it here."
           "If it says `incomplete`, right-click media_meta in Finder → Make Available Offline."
           "On a new Mac: install Black Box, then run `blackbox setup agent` once so your agent knows how to use it."
           "Ask for this tutorial any time (\"give me the Black Box tutorial\"), or one lesson (\"teach me how to online an edit\")."]}
   {:id "said"
    :punch "Every word, English and Italian, searchable."
    :narration "Find what was said. Any word, in English or Italian, across every transcript. Then read around the hit."
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
    :punch "Search by who's talking and who's on screen."
    :narration "Search by person: who's talking, who's on screen, and in how many clips."
    :title "Who's speaking, who's on screen"
    :what "The studio names voices and faces on Black Box's People page, so you can search by person."
    :ask ["Make a cut of Mara talking about the kiln" "Where is Mara seen in October?" "Who is speaking in this line?"]
    :runs [["blackbox people" "everyone named, and how many clips they're heard and seen in"]
           ["blackbox search kiln fire --person Mara" "what one person said"]
           ["blackbox footage --person Mara --month 2026-10" "the clips they're heard or seen in"]
           ["blackbox whose k7f3q2ab.41" "who a voice may be, with scores"]]
    :tips ["A score is a guess: your agent says \"may be\" unless the studio published the name."]}
   {:id "picture"
    :punch "Describe a shot. Get the frames."
    :narration "Describe a shot in plain words and get the frames. Like one? Find more like it."
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
    :punch "B-roll logged shot by shot, the best moment starred."
    :narration "B-roll, logged shot by shot. Close-ups, pans, the best moment starred, the shaky ones left out."
    :title "B-roll: shots, camera moves and best moments"
    :what (str "Every clip is logged shot by shot: framing, camera move, what happens, its best few seconds and any "
               "issues (shake, focus).")
    :ask ["Find close-ups of hands carving stone, nothing shaky" "Slow pans over the quarry for b-roll"
          "What's the best moment of C5274?" "Slow motion from the FX3, only 4K"]
    :runs [["blackbox shots hands carving stone --framing close --clean" "close shots with no issues logged"]
           ["blackbox shots --camera pan --month 2026-09" "every pan that month"]
           ["blackbox shots --file C5274.MP4" "one clip's shots in order, with its summary"]
           ["blackbox footage --device fx3 --slow-motion --res 4k" "FX3 slow motion in 4K, from the files' own metadata"]]
    :tips ["The logs are a model's reading: look at the frame before trusting a description."
           "--device, --slow-motion, --fps, --res, --codec and --bitrate narrow footage, look, shots and search by what shot it."
           "In a cut, a shot plays its best moment (k7f3q2ab.s3) or all of it (k7f3q2ab.s3 whole)."]}
   {:id "cut"
    :punch "Ask for a cut. Watch it seconds later."
    :narration "Ask for a rough cut and it plays seconds later. Every edit is kept, so you can always go back."
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
    :punch "Selects on a page: watch, read, pick, import."
    :narration "Selects on a page. Hover to preview, click to read along. S selects, I imports them into Resolve."
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
           ["blackbox review open kiln" "open one again, with Import working"]
           ["blackbox review serve" "the review server itself, in the foreground: only to see why it won't start"]]
    :tips ["Keep as many reviews open in tabs as you like: one server on your Mac serves them all, and opening another never closes one."
           "Reviews are kept in ~/Black Box/reviews until you delete them there; review.html opens from Finder to watch and read, but importing needs it opened by your agent."
           "Move the mouse across a clip to scrub through it. Keys: ? lists them all. s selects the clip you're on (they gather at the top, in timeline order), i imports them; in an open clip i and o set in and out as in Resolve, shift+I imports, and ← and → move between clips."
           "A line said in Italian shows its English, with the Italian word for word under it, lit as it plays: that's where you cut. English, Both and As spoken at the top (or t) switch between them."]}
   {:id "online"
    :punch "Back to the camera originals, recorders synced."
    :narration "Online it: back to the camera originals, with handles, and every recorder synced underneath."
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
           "Originals already in your project, in any bin, are used where they are; only the rest are imported, into a Black Box bin."
           "What's said comes on the subtitle track, handles included: copy lines straight from it."
           "It only adds: new timelines and projects, and a name already taken gets a number. Nothing in Resolve is changed or deleted."]}
   {:id "project"
    :punch "A whole Resolve project, built by the mini."
    :narration "Need a whole project? The studio's Mac mini builds it on the shared database while you keep working."
    :title "A full project, built by the studio's Mac mini"
    :what (str "For an edit you'll cut properly: the mini builds a Resolve project on the shared database with the "
               "originals, the lav audio synced and the proxies linked. It takes minutes.")
    :ask ["Build this edit as a project on the shared database" "Is the quarry project ready yet?"]
    :runs [["blackbox edl build quarry" "send it to the mini"]
           ["blackbox edl show quarry" "how the build is going, and what it found missing"]]
    :tips ["Black Box must be open on your Mac." "Each edit is built once: for changes, make a new edit and build that."]}
   {:id "source"
    :punch "Any screenshot, traced back to its clip."
    :narration "A screenshot from a client? Black Box finds the clip, and the exact moment, it came from."
    :title "Where did this picture come from?"
    :what "Give it a screenshot, a thumbnail or a crop someone sent, and it finds the clip and moment, or the photo, it came from."
    :ask ["Which clip is this screenshot from?" "Find the originals of these three thumbnails"]
    :runs [["blackbox source ~/Desktop/screenshot.png" "the original and the moment, confirmed"]]
    :tips ["It finds copies, not look-alikes: for \"something like this\", ask for a picture search."
           "Black Box must be open on your Mac."]}
   {:id "stills"
    :punch "Full-res graded stills from the originals."
    :narration "Stills for print and the web, rendered full size from the originals, in the house look or yours."
    :title "Stills for print and the web"
    :what "Full-resolution, graded stills rendered from the camera originals by the mini, for a website, press or print."
    :ask ["Give me a full-res still of this frame for the website"
          "Three stills from the kiln scene, filmic look, with TIFFs, in my Downloads"]
    :runs [["blackbox still new k7f3q2ab.f12 --look filmic --tiff --out ~/Downloads" "ask the mini for the still"]
           ["blackbox still show 20261124T080000Z-kiln --wait" "waits until the stills are back, with their paths"]
           ["blackbox still list" "every request and where it stands"]]
    :tips ["Looks: neutral (the house look), filmic, rec709, or log for a colourist."
           "It takes minutes; your agent tells you when they're ready."
           "Too warm or too cool? Ask it to balance on something white in the shot."]}])

(def voice
  "The animated tutorial's voice: ElevenLabs, used only by `bb narrate` on a development Mac. The CLI ships the audio
  it made and never calls ElevenLabs (AGENTS.md rule 4)."
  {:voice "JBFqnCBsd6RMkjVDRZzb" :model "eleven_v4" :format "mp3_44100_64"})

(def intro-narration
  "What the voice says over the opening."
  "This is Black Box. Every clip, every word, every frame, every face. You ask in plain words. Your agent does the rest.")

(def outro-narration
  "What the voice says over the ending."
  "That's Black Box. Now ask your agent for anything. Every lesson and every command is on the right.")

(declare news)

(defn- news-narration []
  (let [{:keys [version items]} (first news)
        titles (map #(str (str/lower-case (subs (:title %) 0 1)) (subs (:title %) 1)) items)]
    (str "New in version " version ": "
         (str/join ", " (butlast titles)) (when (next titles) ", and ") (last titles) ".")))

(defn narration-lines
  "Every line the voice says, by scene id: the opening, each lesson, what's new, the ending."
  []
  (into (array-map "intro" intro-narration "newintro" (news-narration) "outro" outro-narration)
        (map (juxt :id :narration) lessons)))

(defn line-sha
  "A line's audio file name: the line and the voice it's said in, hashed (so a change to either makes it new)."
  [text]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes (str (:voice voice) "|" (:model voice) "|" (:format voice) "|" text) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 d)))))

(def ^:private voice-dir "black_box_cli/tutorial_voice/")

(defn voice-manifest
  "tutorial_voice/voice.edn: each audio file's {:text :seconds}, as `bb narrate` wrote it."
  []
  (if-let [r (io/resource (str voice-dir "voice.edn"))]
    (edn/read-string (slurp r))
    {}))

(defn- narration-data
  "Each scene's narration for the page: {id {:file :seconds :text}}, for the lines that have audio."
  []
  (let [m (voice-manifest)]
    (into {} (for [[id text] (narration-lines)
                   :let [sha (line-sha text) e (get m sha)]
                   :when (and e (io/resource (str voice-dir sha ".mp3")))]
               [id {:file (str "voice/" sha ".mp3") :seconds (:seconds e) :text text}]))))

(def workflows
  "The lessons by what an editor is trying to do, for the animated tutorial's side panel. Every lesson is in one."
  [{:id "start" :title "Get going" :blurb "What's in the archive, and how to ask" :lessons ["start"]}
   {:id "find" :title "Find selects" :blurb "Words, people, pictures and shots, then choose on a page"
    :lessons ["said" "people" "picture" "shots" "review"]}
   {:id "cut" :title "Cut and watch" :blurb "Rough cuts that play at once" :lessons ["cut"]}
   {:id "resolve" :title "Into Resolve" :blurb "From the camera originals, ready to grade" :lessons ["online" "project"]}
   {:id "stills" :title "Stills and sources" :blurb "Pictures to publish, and where a picture came from"
    :lessons ["stills" "source"]}])

(def news
  "What changed for editors, newest first: :version, :date (nil until it's released), and :items, each {:title :what
  :lesson :run}. A release adds its entry here and in CHANGELOG.md (a test checks the version has one); `blackbox
  tutorial new` and the animated tutorial's What's new show it."
  [{:version "0.1.28" :date "2026-11-25"
    :items [{:title "Captions, and J K L in a closer look" :lesson "review" :run "blackbox review open kiln"
             :what "Clips show what's said as captions in your language. In a closer look at a shot, J K L drive it and o opens the shot right there."}]}
   {:version "0.1.27" :date "2026-11-25"
    :items [{:title "A clearer keys sheet" :lesson "review" :run "blackbox review open kiln"
             :what "Press ? in a selects review for every key, grouped: gallery, playback, marks, moving around."}]}
   {:version "0.1.26" :date "2026-11-25"
    :items [{:title "View all selects" :lesson "review" :run "blackbox review open kiln"
             :what "The top of a selects review shows the clips you picked that fit, and View all opens every one in timeline order, to reorder by dragging."}
            {:title "Rest to play" :lesson "review" :run "blackbox review open kiln"
             :what "In a closer look at a shot (space in the strip), stop the mouse and it plays from there with sound."}]}
   {:version "0.1.25" :date "2026-11-25"
    :items [{:title "A closer look, with sound" :lesson "review" :run "blackbox review open kiln"
             :what "Hover a shot in the strip under an open clip and press space: a larger view opens above it that scrubs with sound. Click it to open the shot right there."}]}
   {:version "0.1.24" :date "2026-11-25"
    :items [{:title "The selection as a reel" :lesson "review" :run "blackbox review open kiln"
             :what "The clips you select line up at the top like the timeline they'll become, each as wide as it is long. Drag one to change the order."}]}
   {:version "0.1.23" :date "2026-11-25"
    :items [{:title "Open a shot where you scrubbed" :lesson "review" :run "blackbox review open kiln"
             :what "Scrub a shot in the strip under an open clip to the moment you want, click, and it opens right there."}]}
   {:version "0.1.22" :date "2026-11-25"
    :items [{:title "Resolve's keys in a review" :lesson "review" :run "blackbox review open kiln"
             :what "Open a clip and edit as in Resolve: J K L shuttle (backward with sound), arrows step frames, up and down change clips, I and O mark. Import is now command-return."}]}
   {:version "0.1.21" :date "2026-11-25"
    :items [{:title "A calmer strip" :lesson "review" :run "blackbox review open kiln"
             :what "The shots around a select now sit evenly spaced with the time between them on the line; Earlier and Later load more, and they slide in."}]}
   {:version "0.1.20" :date "2026-11-25"
    :items [{:title "The shots around a select" :lesson "review" :run "blackbox review open kiln"
             :what "Open a clip in a selects review: the strip under it shows what its camera shot before and after, with the time between. Hover to scrub, click to open, s to add one with your own in and out."}
            {:title "Reviews remember" :lesson "review" :run "blackbox review open kiln"
             :what "What you select, trim and add on a review page comes back when you reload it, kept in the review's folder."}]}
   {:version "0.1.19" :date "2026-11-25"
    :items [{:title "Scrub every card" :lesson "review" :run "blackbox review open kiln"
             :what "Move the mouse across any card in a selects review to scrub its picture, frame by frame, with the line being said lit below it."}]}
   {:version "0.1.18" :date "2026-11-25"
    :items [{:title "Italian in English, word for word" :lesson "review" :run "blackbox review open kiln"
             :what "In a selects review, a clip said in Italian is marked on its picture, and each line shows its English with the Italian under it, lit word by word as it plays: read in English, cut on the Italian. English, Both or As spoken at the top, or t."}]}
   {:version "0.1.17" :date "2026-11-24"
    :items [{:title "Into the project you have open" :lesson "review" :run "blackbox review open kiln"
             :what "Import into Resolve with Open project goes into the project open in Resolve, even an untitled one; a new project only when Resolve wasn't running."}]}
   {:version "0.1.16" :date "2026-11-24"
    :items [{:title "Many reviews, all open" :lesson "review" :run "blackbox review open kiln"
             :what "Keep as many selects reviews open in tabs as you like and move between them: opening a new one never closes an older one."}]}
   {:version "0.1.15" :date "2026-11-24"
    :items [{:title "Safer imports" :lesson "online" :run "blackbox online quarry"
             :what "The subtitles go straight onto the timeline and leave nothing in the bin to drag, so pulling a bin's clips into your edit is safe."}]}
   {:version "0.1.14" :date "2026-11-24"
    :items [{:title "Slow motion on its own timecode" :lesson "cut" :run "blackbox edl new - --play"
             :what "A clip nothing is said in now keeps its camera's own timecode in a cut, so it goes to Resolve like any other."}]}
   {:version "0.1.13" :date "2026-11-24"
    :items [{:title "Slow motion from the FX3" :lesson "shots" :run "blackbox footage --device fx3 --slow-motion"
             :what (str "Find footage by the camera that shot it, its frame rate, slow motion and resolution, read from each "
                        "file's own metadata. Silent slow-motion clips can go straight into a cut.")}]}
   {:version "0.1.12" :date "2026-11-24"
    :items [{:title "Subtitles in Resolve" :lesson "online" :run "blackbox online quarry"
             :what "Importing selects or an edit puts what's said on the timeline's subtitle track, handles included: copy lines straight from it."}]}
   {:version "0.1.11" :date "2026-11-24"
    :items [{:title "A steady review" :lesson "review" :run "blackbox review new - --open"
             :what "Selecting a clip moves nothing on the page, and the open clip's video keeps its shape at any window size."}
            {:title "Updates in minutes" :lesson "start" :run "blackbox update"
             :what (str "Black Box brings each new blackbox and its skill to your Mac minutes after the owner approves it; "
                        "blackbox update checks now.")}]}
   {:version "0.1.10" :date "2026-11-24"
    :items [{:title "Scrub through selects" :lesson "review" :run "blackbox review new - --open"
             :what "In a selects review, move the mouse across a clip to run through it from in to out."}
            {:title "Your bins first" :lesson "online" :run "blackbox online quarry"
             :what (str "Importing into your open project links to the originals already in it, in your own bins; "
                        "only what's missing is imported, into a Black Box bin.")}]}
   {:version "0.1.9" :date "2026-11-24"
    :items [{:title "Ask in your own words" :lesson "start" :run "blackbox tutorial --open"
             :what (str "Say \"teach me about Black Box\", \"build me selects of …\" or \"cut me …\" and your agent "
                        "knows which: a tutorial, a selects review page, or a cut that plays.")}]}
   {:version "0.1.8" :date "2026-11-24"
    :items [{:title "Selects review" :lesson "review" :run "blackbox review new - --open"
             :what (str "Ask for selects and get a page: each clip plays on hover with its words under it; open one for "
                        "its whole transcript. s selects, i imports the selected into Resolve.")}
            {:title "Recorders come synced" :lesson "online" :run "blackbox online quarry"
             :what "Onlining an edit lays the lavs and field recorder under each clip, frame-exact, each on its own tracks."}
            {:title "This tutorial, animated" :lesson "start" :run "blackbox tutorial --open"
             :what "Say \"teach me about Black Box\" and it plays in your browser, with every command and what's new."}]}
   {:version "0.1.7" :date "2026-11-24"
    :items [{:title "Your agent keeps up" :lesson "start" :run "blackbox setup agent"
             :what "When Black Box updates, a running agent session is told to re-read what it can do."}]}
   {:version "0.1.6" :date "2026-11-23"
    :items [{:title "Online an edit" :lesson "online" :run "blackbox online quarry"
             :what "An edit back to the camera originals as a new Resolve timeline on your Mac, with handles."}
            {:title "Edits kept where you find them" :lesson "cut" :run "blackbox edl list"
             :what "Each edit in ~/Black Box/edits, with its video, its EDL and its source clips."}
            {:title "The tutorial" :lesson "start" :run "blackbox tutorial"
             :what "Ask your agent for the tutorial, or read docs/EDITORS.md."}]}])

(def ^:private internal
  "Commands no editor needs to hear about: hooks run them."
  #{["setup" "skill-check"]})

(defn- command-rows
  "Every command an editor can use, from the flags table, so a new one shows up in the tutorial the day it ships."
  []
  (vec (for [{:keys [path args summary flags examples]} flags/commands
             :when (not (internal path))]
         (array-map :command (str/join " " path)
                    :usage (str/join " " (cons (str "blackbox " (str/join " " path))
                                               (map (fn [{:keys [arg many optional]}]
                                                      (if optional (str "[" arg (when many "…") "]") (str "<" arg (when many "…") ">")))
                                                    args)))
                    :summary summary
                    :flags (mapv (fn [{:keys [flag value doc]}] (array-map :flag (if value (str flag " <" value ">") flag) :doc doc))
                                 (remove #(#{"--format" "--fields" "--full" "--help"} (:flag %)) flags))
                    :examples (vec examples)))))

(defn page-data
  "What the animated tutorial page is made from."
  []
  {:version version/version :intro intro :lessons lessons :workflows workflows :news news :commands (command-rows)
   :narration (narration-data)})

(defn page
  "The animated tutorial: tutorial_page.html with its data in it (`</` and the JS line separators escaped, so nothing in
  it can end its script)."
  []
  (str/replace (slurp (io/resource "black_box_cli/tutorial_page.html"))
               "/*TUTORIAL_DATA*/"
               (-> (json/generate-string (page-data))
                   (str/replace "</" "<\\/")
                   (str/replace "\u2028" "\\u2028")
                   (str/replace "\u2029" "\\u2029"))))

(defn- sha8 [^String text]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 4 d)))))

(defn write-page!
  "Writes the page once per version and content (tutorial-<version>-<sha>.html in the tutorial folder; an existing one is
  the same page and is kept) and returns its path."
  [ctx]
  (let [html (page)
        dir (:tutorial (index/paths ctx))
        f (fs/path dir (str "tutorial-" version/version "-" (sha8 html) ".html"))]
    (fs/create-dirs (fs/path dir "voice"))
    (doseq [{:keys [file]} (vals (narration-data))
            :let [to (fs/path dir file)]
            :when (not (fs/exists? to))]
      (with-open [in (io/input-stream (io/resource (str voice-dir (fs/file-name file))))]
        (io/copy in (fs/file to))))
    (when-not (fs/exists? f) (cuts/write-new! f html))
    (str f)))

(defn- lesson-of [id]
  (some #(when (= id (:id %)) %) lessons))

(defn- next-of [id]
  (some (fn [[a b]] (when (= id (:id a)) (:id b))) (partition 2 1 lessons)))

(defn- lesson-out [{:keys [id title what ask runs tips]}]
  (apply array-map (concat [:lesson id :title title :what what :ask ask
                            :runs (mapv (fn [[run does]] (array-map :run run :does does)) runs)
                            :tips tips]
                           (when-let [n (next-of id)] [:next n]))))

(defn- news-out [{:keys [version date items]}]
  (array-map :version version :date (or date "coming")
             :items (mapv (fn [{:keys [title what lesson run]}] (array-map :title title :what what :lesson lesson :run run)) items)))

(defn tutorial-cmd
  "`blackbox tutorial [lesson|all|new] [--open|--html]`."
  [{:keys [args opts] :as ctx}]
  (let [want (:lesson args)]
    (cond
      (or (:open opts) (:html opts))
      (let [path (write-page! ctx)]
        (when (:open opts) (tools/spawn! ["/usr/bin/open" path]))
        [(array-map :tutorial path :opened (boolean (:open opts)) :lessons (count lessons)
                    :commands (count (command-rows)) :whats_new (:version (first news)))
         (cond-> []
           (not (:open opts)) (conj (str "Run `open " (out/shell-quoted path) "` to show it"))
           true (conj "Run `blackbox tutorial new` for what's new, in words"))])

      (= "new" want)
      [(array-map :version version/version :news (mapv news-out news))
       ["Run `blackbox tutorial <lesson>` for a lesson named in an item"
        "Run `blackbox tutorial --open` to see it animated"]]

      (nil? want)
      [(array-map :tutorial intro
                  :count (count lessons)
                  :lessons (vec (map-indexed (fn [i {:keys [id title ask]}]
                                               (array-map :n (inc i) :id id :title title :try (first ask)))
                                             lessons)))
       ["Run `blackbox tutorial --open` to see it animated in the browser"
        "Run `blackbox tutorial <id>` for a lesson: what to ask, the commands behind it, tips"
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
                   {:help [(str "Lessons: " (str/join ", " (map :id lessons)) ", all, or new")]})))))

(defn markdown
  "docs/EDITORS.md: the lessons for people."
  []
  (str "# Black Box for editors\n\n"
       "<!-- Written by `bb editors` from src/black_box_cli/tutorial.clj; edit the lessons there. -->\n\n"
       intro "\n\n"
       "You don't need to learn the commands: say what you want, as in the examples below. To be taught by your agent, "
       "ask it: \"teach me about Black Box\" (it plays an animated tutorial in your browser) or \"give me the Black Box "
       "tutorial\". The commands are shown so you can see what it does for you.\n\n"
       "**What's new in " (:version (first news)) ":** "
       (str/join "; " (map :title (:items (first news)))) " ([more](#whats-new))\n\n"
       (str/join "\n" (map-indexed (fn [i {:keys [id title]}] (str (inc i) ". [" title "](#" id ")")) lessons))
       "\n"
       (apply str
              (for [{:keys [id title what ask runs tips]} lessons]
                (str "\n<a id=\"" id "\"></a>\n\n## " title "\n\n" what "\n\n"
                     "**Ask:**\n\n" (apply str (map #(str "- *" % "*\n") ask)) "\n"
                     "**Behind it:**\n\n" (apply str (map (fn [[run does]] (str "- `" run "`: " does "\n")) runs))
                     (when (seq tips) (str "\n**Tips:**\n\n" (apply str (map #(str "- " % "\n") tips)))))))
       "\n<a id=\"whats-new\"></a>\n\n## What's new\n"
       (apply str
              (for [{:keys [version date items]} news]
                (str "\n### " version (if date (str " (" date ")") " (this version)") "\n\n"
                     (apply str (map (fn [{:keys [title what run]}] (str "- **" title ":** " what " `" run "`\n")) items)))))))
