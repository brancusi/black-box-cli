# Changelog

## Unreleased

- Importing no longer shifts the open clip's player: how it's going is said in the toast (warnings in it too), and
  the bar's own status line (the review server lost or back) floats above the bar instead of growing it.

## 0.1.39 (2026-11-25)

- Subtitles laid in Resolve (`online`, and a review's Import) are in English by default: a line said in Italian goes
  on as its English (Black Box's translation), spread over the line. `--subtitles-in spoken` lays them as said; the
  review page has the same choice beside Recorders (Subtitles: English, As spoken).
- Languages named apart (docs/DESIGN.md, decision 20): spoken (what a line was said in), `--read-in` (the editor's
  language, the review page's; `--language` is its old name and still works) and `--subtitles-in` (the subtitles in
  Resolve, the audience's). `review new` prints and keeps `read_in` and `subtitles_in`.

## 0.1.38 (2026-11-25)

- A card opens where the mouse was on it: the scrub point on its picture, or the word under it in its text, and plays
  on from there to its out.

## 0.1.37 (2026-11-25)

- Captions sit on a tight plate in the page's own dark, each line on its own, in the page's sans (Archivo where
  installed), balanced across lines; no drop shadow.

## 0.1.36 (2026-11-25)

- The right-click menu behaves as a system one: it opens just off the pointer and flips where it would leave the
  window; it closes on a click elsewhere, esc, the wheel or leaving the window, never because the page scrolled
  itself (a card's words following the mouse closed it); ↑ ↓ and ↵ pick from it.

## 0.1.35 (2026-11-25)

- A clip id (`k7f3q2ab`, the first part of its line ids) is taken for its file wherever a file is: `transcript`,
  `frame`, a cut list line.
- Review page: right-click a clip (a card, a shot in the strip, a closer look, the open clip) to copy its clip id,
  the id at the moment under the mouse or the playhead, the id with its in and out, or its path: what an editor pastes
  to an agent with a question.
- The open clip's heading is its place (day › folders) small, then its file name, smaller than before.
- The gallery behind an open clip no longer scrolls (its scrollbar showed, scrolling nothing); what's under a shot in
  the strip wraps instead of being cut off.

## 0.1.34 (2026-11-25)

- A closer look stays open wherever the mouse goes; space, a click outside it, or opening the shot closes it.

## 0.1.33 (2026-11-25)

- On a card's words, the mouse between two words lands on the nearer one, never back on the start of its line; on a
  line known only as a whole (its English), as far along it as the mouse is.

## 0.1.32 (2026-11-25)

- Dragging on the open clip's timeline marks a span (the picture follows the mouse); ↵ makes it the in and out, esc
  drops it, a click still just goes there. Dragged on the lines' lane, the span takes whole lines.

## 0.1.31 (2026-11-25)

- The timeline's bubble says only the line under the mouse (the time where nothing is said), and stays inside the
  timeline at either end.

## 0.1.30 (2026-11-25)

- The open clip's player is the page's own: a tall timeline of its sound's waveform with each line said marked above
  it (click one to go to it), the in and out shaded, drag anywhere to scrub with the time and line under the mouse;
  under it play, the time, the speed (in a fixed place: nothing shifts), captions, sound and full screen (`f`, or a
  double click). No browser controls: no focus rings, and ← → are always one frame. The waveform is made once per
  clip from its proxy by the review server (`/api/peaks`) and kept in this Mac's frames cache.
- The strip of shots around a clip scrolls on and on: it loads more as either end comes near, with a loader there,
  and what's on screen never moves; it slides to a shot only when that shot is opened. Its ends say First or Last
  shot. `/api/around` takes `before` and `after`.
- `s` over a shot in the strip (or in a closer look) selects that shot, and its ✓ shows at once.
- In the gallery, the mouse on a card's words puts the picture where they're said; on the picture it scrubs as before.

## 0.1.29 (2026-11-25)

- The open clip stands out in the strip: a yellow ring around it (it was drawn under the picture and lost), an OPEN
  tag, its time in bold, its in and out and the playhead moving inside it; the shots around it dimmer.

## 0.1.28 (2026-11-25)

- While a closer look is open, J K L, the arrows, Home and End drive it (backward with sound too, its speed on it);
  `o`, ↵ or a click opens the shot right there, playing on; space closes it.
- Captions: what's said, in the review's language (the English of a line said in Italian), over the open clip and
  over a closer look. `c` turns them off or on, remembered.
- A clip opened at a moment (a shot from the strip) plays on from there, instead of stopping at a review clip's out.

## 0.1.27 (2026-11-25)

- The keys sheet (`?`) is rebuilt: Gallery, Playback, Marks, Moving around and Anywhere, each key in a column of its
  own beside a short line, every row on one line; a Close button, and a click outside closes it.

## 0.1.26 (2026-11-25)

- The selection at the top shows as many clips as fit, then "+N more · View all": a panel of every selected clip in
  timeline order, each with its picture, number, length, file and note. Drag a clip (there or at the top) to move
  it, × or ⌥-click to drop it, click to open it; esc or a click outside closes the panel. Replaces 0.1.24's reel,
  which made short clips too thin to read.
- The closer look plays on, picture and sound, where the mouse rests; a click opens the clip where it has got to.

## 0.1.25 (2026-11-25)

- A closer look at a shot in the strip: space over it opens it a few times larger just above, scrubbed with sound
  (a moment of it wherever the mouse lands, finer across the larger view); a click opens it there, space or esc
  closes it. Find it small, look closer with sound, then open it.

## 0.1.24 (2026-11-25)

- In and out as in Resolve: an in marked past the out (or an out before the in) drops the other mark, instead of
  leaving a clip a tenth of a second long that stops as soon as it plays.

## 0.1.23 (2026-11-25)

- Clicking a shot in the strip opens it where it was scrubbed to, playing from there, its transcript at that line:
  the moment an editor was hunting for, not the shot's start.

## 0.1.22 (2026-11-25)

- An open clip in a review takes Resolve's keys: J K L shuttle (L again 2×, 4×, 8×, 16×; J backward, with its sound,
  cut a few seconds at a time from the proxy by the review server and reversed on the page; K held with J or L a
  frame, or slowly when held), ← → a frame (Shift: a second), ↑ ↓ the previous or next clip (Shift: the shot before
  or after, in the strip), Shift+I/O go to the in or out, ⌥I ⌥O ⌥X clear them, Home and End. A badge shows the speed.
- Import is ⌘↵ now, from either view: Shift+I is Resolve's go to in, and `i` in the gallery no longer imports.

## 0.1.21 (2026-11-25)

- The shots-around strip: every gap the same width with its length on the line (a new day by its date), Earlier
  and Later in its header, shots fading in as they load and sliding to their places when more come or another shot
  opens.
- An open clip's player sits centred over its column, not against its left edge.
- The page asks browsers not to translate it: Chrome's Translate turned 16:38 into 4:38 PM and ran keys into
  words. Keys keep their spacing either way.

## 0.1.20 (2026-11-25)

- A review's open clip has a strip under it: the shots its camera took before and after it, in the order it shot
  them (on any day), ghosted, with the time between them (a day apart, ten minutes, a quick retake) and what each
  holds (lines said or no words, slow motion, already in the review, no proxy). Hover one to scrub it, click it to
  open it with its transcript, Shift+← → to step through them; `s` adds it, trimmed with `i` and `o`, to an "Added by
  you" group that imports like the rest. From the proxies only.
- What an editor does on a review page (selected, in order; trimmed; added) survives a reload: the review server
  appends it to the review's `state.jsonl` (review.json stays as made), the browser keeps a copy, and the newer
  wins when the page opens.

## 0.1.19 (2026-11-25)

- Moving across a card scrubs its picture again, frame-exact: one player for the whole gallery moves into the card
  under the mouse (one decoder and one download however many cards; cards of one file share what it loaded), and
  the card shows its still until the player has its frame.
- The selection at the top no longer shifts: its count and length keep one width, and the chips scroll only to show
  the open clip's or one just added, never when moving between clips.
- A card's snippet fades at the top once scrolled, instead of cutting a line in half.

## 0.1.18 (2026-11-25)

- Selects reviews show a line said in Italian in English, with the Italian word for word under it: the English to
  read, the Italian lit word by word as it plays, to cut on. English, Both and As spoken at the top of the page (or
  `t`) switch between them, remembered on the Mac. Reviews made before get it too, when they're served.
- A review has a language: `review new --language <code>`, English unless said. A clip said in another is marked
  on its picture ("Italian"), its snippet says "Translated from Italian", the header counts them, and so does
  `in_another_language` in the output.
- The index reads Black Box's translations (`media_meta/translations`, `media-mgmt.translation/1`) into
  `translations.sqlite`; `index` and `status` count them. A line gets English only from a translation of the very
  transcript the index holds.
- Each card's words are one snippet to read, in English, with the Italian under it, quieter, instead of a list of
  fragments. Moving across the picture lights the line being said there (and the word, in the Italian), and the
  snippet scrolls to keep it in view.
- The review's transcript is easier to read: each line's words hang beside its time however they wrap, the speaker
  shows only where a turn starts, and the line being said is marked, not just the word.

## 0.1.17 (2026-11-24)

- Import into Resolve, "Open project": the project open in Resolve is always the one used, an untitled one too. A new
  project is made only when Resolve had to be started, or the open one is the empty Untitled Project it starts with.
- The subtitles' Media Pool item goes into `Black Box/Subtitles`, never into the cut's folder, and is no longer
  deleted after placing (0.1.15 deleted it, a step that may itself have crashed Resolve).
- Each import writes `online-<time>-steps.log` beside its EDL, a line per step as it starts. When Resolve quits
  mid-import, the error is `resolve-crashed` and says on which step ("Resolve quit while placing the subtitles"),
  where 0.1.16 said `TypeError: 'NoneType' object is not callable`.
- No new project is made when none of the originals is on this Mac (the NAS isn't mounted): it says so first.

## 0.1.16 (2026-11-24)

- Selects reviews stay open: one review server on this Mac (127.0.0.1:7387) serves every review at its own
  address, so editors can keep several in tabs, and opening another never closes one. `review open` and
  `review new --open` start it on its own when it isn't running and return at once (no background task to wait
  on or stop); its key is kept, so a review's address works across restarts, and a page whose server stopped says
  so and reconnects by itself. It stops after a day with no page calling. `blackbox review serve` runs it in the
  foreground, to see why it won't start. Reviews are kept in `~/Black Box/reviews` until someone deletes them.

## 0.1.15 (2026-11-24)

- A crash in Resolve after importing selects: the subtitles' .srt was left in the edit's bin as a subtitle item, and
  an editor dragging the bin's contents into a timeline dragged it too (Resolve crashes placing a subtitle clip
  that way). Once its lines are on the timeline, the import now removes that item, this run's own; the lines stay,
  and the .srt stays beside the EDL. Subtitles are placed only on Resolve 21 or later (tested on 21.1); before
  that, `subtitles_note` says to import the .srt by hand.

## 0.1.14 (2026-11-24)

- The camera facts give a clip nothing is said in its own start timecode (`video.timecode`) or a recorder's time of
  day (`audio.start_s`), so `cut.edl` and `online` place it by timecode like any other; only a file with neither is
  counted from 0:00, with a warning. Each original's facts come from the newest `probe/v<N>` that has it, so a
  version Black Box is still publishing never hides the rest.

## 0.1.13 (2026-11-24)

- Find footage by what shot it, from the files' own metadata (Black Box's `media_meta/probe`, ask 14; the newest
  `v<N>` folder is read): `--device fx3` (camera or recorder by name, model or make), `--slow-motion`, `--fps 120`
  (`50+`), `--res 4k` (`4k+`), `--codec`, `--bitrate 100+` on `footage`, `search`, `look`, `like` and `shots`.
  `footage` shows each file's device, resolution and frame rate (`slow_motion`: `5x`, or `conform` for high frame
  rate), and lists the files nothing is said in too. A cut list, a saved edit and a shot take a clip without a
  transcript (slow motion records no sound) by its facts; `cut.edl` counts it from 0:00 and `online` places such a
  cut by frame.

## 0.1.12 (2026-11-24)

- `online` (and a review's Import) puts what's said on the timeline's subtitle track, so editors can copy lines
  from it: every word in each clip's range, handles included, timed to the clips' frames (`subtitles_placed`;
  `--no-subtitles` leaves them out; the .srt is kept beside the EDL). With subtitles, the clips are placed by frame
  after them (Resolve only puts subtitles at a timeline's start when it's empty); recorders start on their clip's
  frame and are exactly its length. The review's toast says how many subtitles came.
- Releases are signed as they're published: `bb release` signs the manifest (Ed25519, the key only in the releasing
  Mac's keychain; `bb release-key` makes it once) and publishes `blackbox-cli.json.sig`. Black Box, with the public
  key `docs/release-key.pub` built in, installs only a signed release. Nothing is done by hand.
  `bb release X.Y.Z --sign` signs a release published before this.
- `bb release` builds and publishes from the releasing Mac and waits on nothing: GitHub's release workflow is gone
  (GitHub only re-runs the tests in the background on each push). `bb release X.Y.Z --check` checks a published
  release's download.

## 0.1.11 (2026-11-24)

- Releases reach every Mac within minutes, without waiting for a Black Box release (once Black Box has ask 13):
  each release carries a manifest, `blackbox-cli.json` (version, SHA-256, size and `min_black_box`, the oldest
  Black Box it works with), which Black Box checks every few minutes. Nothing is done by hand: GitHub's release workflow
  attests where the jar was built, and the Mac mini checks that and signs the release by itself (the key never on
  GitHub). `blackbox update` asks Black Box to check now (`unsigned`: the mini hasn't signed it yet).
  The home view says how this blackbox was installed (`installed`: bundled or updated, and Black Box's version).
- The selects review never shifts: selecting a clip (in the gallery or an open clip) moves nothing. The selected
  clips' strip keeps one height (the wheel scrolls it sideways), the open clip's video box takes the clip's own
  shape (no black bars as the space changes), and labels that change (Select/Selected, Follow/Following, the count)
  keep their width. In a narrow window the open clip's video is full width with the transcript below it.
- `bb release X.Y.Z` makes a release in one step; GitHub's release workflow builds and publishes it from the tag,
  with the CHANGELOG's notes.

## 0.1.10 (2026-11-24)

- The selects review's gallery scrubs: the mouse's place across a clip is its time from in to out (a playhead and
  the time follow it; one seek at a time, always to where the mouse is now). The open clip is unchanged.
- `online` (and a review's Import) uses originals already in the project wherever they are, the editor's own
  folders first: matched by path, or by the same path from media/ on (another mount of the NAS), never by file name
  alone. Only the rest are imported into "Black Box/<id>". The editor's clips are left as they are (no proxy linked
  to them); `originals_in_project` counts them.

## 0.1.9 (2026-11-24)

- The skill routes what editors say: a table from "teach me about Black Box", "build me selects of …", "cut me …",
  "online this", stills and sources to the command for each. Selects go to a selects review, not a rough cut. Its
  review section has the whole workflow (find, a grouped cut list, `review new - --open` in the background) and the
  page's keys as they are (`s` selects, `i` or Shift+I imports, `i`/`o` trim in an open clip); `online` says how
  recorders are reported. Its description names teaching, what's new, selects and stills.

## 0.1.8 (2026-11-24)

- `blackbox review new <selects|->` (`open`, `list`): a selects review. A cut list with `group:` sections becomes
  ~/Black Box/reviews/<id>/: a page of the clips playing their proxies, each with its words under it; a click opens
  the clip with its file's whole transcript (a word jumps there; i/o trim it). `review open` serves it on 127.0.0.1
  with a key (AGENTS.md rule 4, owner's decision 2026-11-24), and Import into Resolve sends the ticked clips to
  `online`. English under Italian lines waits for Black Box's translations (ask 12).
- `online` lays in the recorders synced with each clip, by the sync map: one block of tracks per recorder from A2,
  as wide as its channels; weak matches (confidence under 0.5) are named, not placed. `--no-audio` to skip.
- Cut lists take `group:` lines (and a JSON clip's "group").
- The tutorial has a review lesson.
- `blackbox tutorial --open` ("teach me about Black Box"): an animated tutorial in the browser, every lesson as a
  quick narrated scene (ElevenLabs voice, made by `bb narrate` and shipped as audio: the CLI never calls ElevenLabs),
  beside a panel of workflows, every command (from the flags table, so a new one shows the day it ships) and what's
  new. `blackbox tutorial new` says what's new in words; docs/EDITORS.md has it too. Tests fail when a command isn't
  taught, a release has no news, or a narration line has no audio.
- The review page and the tutorial take Walkabout's look: mono type, square corners, light and dark (auto, or chosen
  and remembered). The review's bar has labelled groups (handles as steppers, Into and Recorders as two-way
  switches), and an import's result shows as a toast over the gallery or an open clip.
- The sync map: a day whose newest file Dropbox hasn't brought down yet is read from its newest file that is here,
  not dropped; `online` (and a review's Import) brings the sync map up to date first, so recorders come synced with
  the mini's newest map.

## 0.1.7 (2026-11-24)

- A running agent session hears of a new skill: `setup agent` also installs `blackbox setup skill-check` on
  SessionStart and UserPromptSubmit (Claude Code and Codex). It notes which skill a session started with and, when
  a newer one has been installed since (Black Box runs `setup agent` when it updates), adds one line at the next
  prompt telling the agent to re-read it; otherwise it prints nothing. The installed skill carries the version that
  wrote it.
- The skill's description names onlining an edit and the tutorial, so "online that edit" or "give me the Black Box
  tutorial" loads it in a new session; "that edit" is the last one made or played (or confirmed from `edl list`).
  It also says the CLI may be newer than the skill (`blackbox --help`, `blackbox tutorial`).
- The home view shows the CLI's `version` and points to `blackbox tutorial`.

## 0.1.6 (2026-11-23)

- `blackbox tutorial [lesson|all]`: what an editor can do with Black Box, lesson by lesson (what to ask in plain
  words, the commands behind it, tips), for an agent to teach from when someone asks for a tutorial. The same
  lessons are docs/EDITORS.md, for people (`bb editors` writes it).
- Edits are kept in `~/Black Box/edits/<id>/` (the owner's choice), each with `edit.mp4`: the edit rendered from the
  proxies once, when it's made (`--no-render` to skip) or first played, and played from there after. Edits made
  before, in `~/Library/Application Support/black-box-cli/cuts/`, are still listed and found.
- `blackbox online <edit|cutlist|->`: an edit taken back to its source files as a new DaVinci Resolve timeline on
  this Mac, ready to grade. Each clip goes from its proxy to the camera original and gets pre- and post-roll
  (`--pre`/`--post`, default 2 s, or a clip's own `pre`/`post`; clamped to the file); the edit's folder gets
  `online-<time>.edl`. Resolve (started when it isn't running) imports the originals into "Black Box/<id>", reusing
  ones already there, links the proxies and imports the EDL as a new timeline; if the EDL doesn't link every clip,
  they're placed by frame. `--into auto|current|new-project`, `--db`, `--originals`, `--no-proxies`, `--dry-run`.
  AGENTS.md rule 5 changes with it (owner's decision, 2026-11-23).
- Cut lists take a proxy's path as the file (`{"proxy": …}` in JSON too), and a clip's own `pre`/`post`.

## 0.1.5 (2026-11-12)

- `blackbox source <picture…>`: the original of a screenshot, a thumbnail or a crop, photo or footage. Black Box
  (0.43.0 or later) picks look-alikes with its picture search and confirms each point by point and pixel by pixel;
  `matches` are the confirmed copies, best first, `similar` the look-alikes it couldn't confirm, each with the
  picture index's frame `id` (for `frame`, `like` and `still new`) and the original's path, shown, never opened.
  `--candidates` and `--max-diff` pass through. A picture on the NAS is refused; an older Black Box says
  `black-box-too-old`.
- `shots --file` on a clip with no shot log says so (`no shot log for <file> yet`) instead of `0 shots match`.
- `frame --help` says a recorder shows a camera heard with it; `search --help` says what `also` and `check` are.

## 0.1.4 (2026-11-04)

- `frame` on a recorder's line (or a recorder file with `--at`) shows the picture of a camera heard with it, chosen
  as the rough cut chooses it (the speaker's face on screen first); `picture` says which camera and when. With no
  camera heard then, it says so (`audio-only`) instead of failing in ffmpeg.
- `search` groups the same moment on two recorders through a camera both were heard with, also when the camera's
  line isn't among the hits, and a line that is one moment with lines in two groups joins them, so the grouping no
  longer depends on the order of the hits.
- `also` and `check` are always fields of `search`'s hits (empty when nothing is grouped), so `--fields also` never
  fails. Goldens 08, 09 and 17 changed.

## 0.1.3 (2026-11-04)

- The index keeps itself up to date: a command that reads it first builds it when it's missing, or reads what's new
  when it's over a minute old and media_meta has changed since (files Dropbox brought in after the last build).
  `BLACKBOX_REFRESH=never` turns that off. One build runs at a time on a Mac (a lock file beside the index; a
  command doesn't wait for another's build, `index` does).
- `blackbox status`: the index's health (`ok`, `stale`, `incomplete`, `broken`, `not built`, `no media_meta`), when
  it was last built and how long it took, and per source (transcripts, frames, people, prints, sync, shots) what it
  holds, the files `pending` and the files Dropbox keeps `offline`. The home view shows `indexed` and `health`.
- `blackbox index --progress`: JSON lines on stderr, each stage's start and end and every 250 files, for Black Box
  to show; stdout is still the one answer. Each build is recorded in `indexed.json` beside the index.
- Golden 04 (the home view) changed. The skill says commands keep the index up to date and what each health means.

## 0.1.2 (2026-11-04)

- Shot logs: `blackbox index` reads Black Box's shot logs (`media_meta/shots/gemini-3.1-pro/v1`, schema
  `blackbox.shots/1`) into `shots.sqlite`. `blackbox shots [words…]` finds shots by what happens in them (the
  action, why a moment is best, issues, the clip's summary and tags) and by `--camera`, `--framing`, `--clean`,
  `--file`, `--day`, `--month`, `--path`, at most `--per-clip` (2) from one clip. Shot ids are `<clip>.s<n>`.
- `line`, `frame` and `look`/`like` show the shot a moment is in; `frame <shot id>` is the middle of its best
  moment; a cut list takes a shot id (its best moment) or `<shot id> whole`, and JSON `{"shot": …, "whole": …}`.
- Goldens 02, 03 (`shots:` in `index`), 19, 20 (`shot:` and a help line in `line`) and 38 (the cut list message
  names shot ids) changed; the fixture has invented shot logs.

## 0.1.1 (2026-11-03)

- The index builds with a `sqlite3` that has `trusted_schema` off (macOS 15's), which refused the triggers that
  keep the full-text table in step. 0.1.0 was tagged but never released.

## 0.1.0 (2026-11-03)

The first public release.

- Search what was said: `index` (with `--rebuild`), the home view, `search` (with `--person`, and one hit per
  moment heard on several devices), `line`, `transcript`, `footage`, over an index built from `media_meta` with
  the system `sqlite3` and FTS5. Line ids are `<clip>.<cue>`, the same on every Mac.
- Find what's in the picture: `frame`, `look`, `like`, scored exactly over every frame, with Black Box making
  `look`'s query vector.
- Who: `people`, `whose`, `like` for voices and faces, read-only from Black Box's names and prints.
- Rough cuts: `edl new` (a cut list as text or JSON, `--dry-run`, `--play` rendered to one mp4), `edl list`,
  `edl show`; `cut.edl` for Resolve; `edl build` asks Black Box to have the mini build a Resolve project.
- Stills from the originals: `still new` (looks `neutral`, `filmic`, `rec709`, `log`, a white balance per still,
  `--tiff`, `--out`), `still show` (`--wait`), `still list`, through Black Box.
- `setup agent` installs the skill and a SessionStart hook for Claude Code and Codex, never over a hand-edited
  skill, with settings copied aside first. It takes Black Box's launcher (`# black-box: bundled-cli`) as this CLI.
- One release file: `bb dist` builds `blackbox-cli.jar` (skills inside), which Black Box bundles with `bb` and
  ffmpeg. Tools are found in `$BLACKBOX_TOOLS` first, then the PATH and Homebrew. The home folder is `$HOME`,
  then Java's `user.home`.
