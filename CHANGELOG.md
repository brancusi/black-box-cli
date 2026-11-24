# Changelog

## Unreleased

- Releases reach every Mac within minutes, without waiting for a Black Box release (once Black Box has ask 13):
  each release carries a manifest, `blackbox-cli.json` (version, SHA-256, size and `min_black_box`, the oldest
  Black Box it works with), which Black Box checks every few minutes. `blackbox update` asks Black Box to check now.
  The home view says how this blackbox was installed (`installed`: bundled or updated, and Black Box's version).
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
