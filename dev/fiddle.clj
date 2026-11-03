(ns fiddle
  "A place to play with blackbox from the REPL. Nothing here runs when the file loads: put the cursor in a form and
  evaluate it (alt+enter in Calva evaluates the top-level form, ctrl+enter the one under the cursor). From any file
  under src/, ctrl+alt+c f opens this file; ctrl+alt+c f again goes back.

  Everything runs against the invented media_meta in test/fixtures and a fresh --local folder, never a real one."
  (:require
   [black-box-cli.cutlist :as cutlist]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.flags :as flags]
   [black-box-cli.out :as out]
   [black-box-cli.toon :as toon]
   [black-box-cli.ids :as ids]
   [black-box-cli.tc :as tc]
   [black-box-cli.support :as support]
   [dev :refer [cli fresh! goldens reload show tests]]))

;; 1. The command line, in-process. (cli ...) returns {:exit :out :local}; (show ...) prints like the shell.
;; Every call shares this REPL's --local folder: build the index once, (fresh!) to start again.
(comment
  (show "index")
  (show)
  (show "search" "marble")
  (show "search" "marble" "--fields" "all")
  (show "line" "r54qb74c.1")
  (show "transcript" "C5274.MP4")
  (show "footage" "--video")
  (show "edl" "new" "-" "--dry-run") ; reads stdin: use cli* below for a cut list
  (support/cli* {:stdin "title: Fiddle\nr54qb74c.1..r54qb74c.2 # why\nC5274.MP4 20-26.6\n"}
                ["--meta" support/fixture-meta "--local" (dev/local) "edl" "new" "-"])
  (show "edl" "list")
  (show "edl" "show" "fiddle")
  (show "like" "r54qb74c.f2") ; picture search from a frame; `look <words>` needs Black Box for the query vector
  (show "frame" "r54qb74c.f2") ; the fixture's proxies aren't video: no-frame
  (show "people")
  (show "search" "marble" "--person" "mara") ; by alias, start or name, accents ignored
  (show "footage" "--person" "tobia" "--fields" "file,day,heard,seen,heard_at,seen_at")
  (show "whose" "pm4c24b5.1") ; a voice: the names file's answer and everyone scored by print
  (show "like" "r54qb74c.face2" "--per-clip" "0") ; faces like one
  (fresh!)
  (show "--help")
  (show "search" "--help")
  (show "edl" "--help")
  (show "--format" "json")
  (show "--format" "edn"))

;; 2. Errors and exit codes: 1 error, 2 usage. Errors go to stdout, in the format asked for.
(comment
  out/exit-codes
  (cli "nonsense") ; the raw result: {:exit 2 :out "error:…" :local "/var/…"}
  (show "nonsense")
  (show "search" "x" "--bogus")
  (show "search" "x" "--bogus" "--format" "edn")
  (show "search" "x" "--limit" "many")
  (show "line")
  (show "edl")
  (show "line" "c3") ; old ids are gone: bad-id
  (show "like" "r54qb74c.2")) ; a line id where a frame id goes: bad-id

;; 3. The pieces underneath, called directly.
(comment
  (map flags/command-name flags/commands)
  (def search (first (flags/find-command ["search"])))
  (flags/parse search ["marble" "--all" "--month" "2026-09" "--month=2026-10"])
  (print (flags/help-text search))
  (out/pick (array-map :id "a" :file "f" :at 1.5 :text "t") [:id :text] {:fields "text,id"})
  (out/cut (apply str (repeat 200 "a")) false)
  (ids/clip "media/2026/09/16/src/fx3/C5274.MP4")
  (tc/hms 15.45)
  (tc/source-tc {:clock_kind "timecode" :start_tc "03:06:51:08" :rate "24000/1001"} 22.2)
  (cutlist/parse "title: T\nr54qb74c.1..r54qb74c.2 # why\nC5274.MP4 0:20-26.6\nnonsense\n")
  (cuts/slug "Marble, the quote")

  ;; The encoder: a table, and a list that isn't one.
  (print (toon/dumps (array-map :hits [{:id "c2" :at "0:09.0"} {:id "c5" :at "0:22.2"}])))
  (print (toon/dumps {:items [{:a 1} "x"]}))
  (print (out/render (array-map :a 1 :b [1 2]) ["Run `blackbox`"] :edn)))

;; 4. After editing src/: reload, test, and see how far the goldens are.
(comment
  (reload)
  (tests)
  (tests 'black-box-cli.flags) ; one namespace's tests
  (goldens)
  (goldens 5 18))
