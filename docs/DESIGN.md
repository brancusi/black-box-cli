# How black-box-cli is built

`blackbox` searches a footage archive by what was said and what's in the picture, and makes rough cuts and EDLs for
DaVinci Resolve. It is written for agents first (AXI, https://axi.md) and runs in Babashka. What it reads and
writes is [CONTRACT.md](CONTRACT.md).

## Stateless

- The only input is `media_meta` (a Dropbox folder, read-only) plus the arguments. Black Box's engine fills it on
  the studio's Mac mini; every Mac syncs it.
- The index is a **cache**: derived from `media_meta`, rebuilt at any time with the same result, never synced,
  never the only copy of anything. `blackbox index` builds or updates it; `blackbox index --rebuild` builds a new
  one beside it, then renames it into place.
- Cut folders are outputs, written once each, never overwritten. They are the only thing the CLI makes that a
  rebuild can't reproduce, and they're throwaway by nature (rough cuts).
- No config file, no daemon, no login, no network. Flags and environment variables only (`BLACKBOX_META`,
  `BLACKBOX_FORMAT`, `BLACKBOX_APP`, `BLACKBOX_TOOLS`, `BLACKBOX_BIN`, `BLACKBOX_PLAYER`, `BLACKBOX_REFRESH`).

## Commands

```
blackbox                                  home view: bin, description, what the cache holds, recent cuts, help
blackbox index [--rebuild] [--progress]   build or refresh the cache from media_meta (commands also do it)
blackbox status                           the cache's health, last build, what's pending or online-only
blackbox search <words…> [--all] [--person NAME] [--month YYYY-MM] [--day YYYY-MM-DD] [--path P] [--video] [--limit N]
blackbox line <id> [--context N]          one line in context: file, timecode, proxy
blackbox transcript <file> [--limit N]    a file's lines
blackbox footage [--person NAME] [--month …] [--day …] [--video]
blackbox edl new <cutlist|-> [--title T] [--dry-run] [--play]
blackbox edl list | edl show <id> [--play] | edl build <id>
blackbox online <edit|cutlist|-> [--pre S] [--post S] [--into auto|current|new-project] [--db name]   the edit in Resolve here, from source
blackbox frame <file|line id|frame id|shot id> [--at T]   a JPEG from the proxy (ffmpeg)
blackbox look <words…> | like <frame id> [--open|--html]   picture search
blackbox source <picture…> [--candidates N] [--max-diff X]   the original of a copy (Black Box finds it)
blackbox shots [words…] [--camera M] [--framing F] [--clean] [--file F] [--per-clip N]   the shot logs
blackbox people | whose <line id|face id> | like <line id|face id>   names, voices and faces
blackbox still new <moment…> [--look L] [--tiff] [--out DIR] | still show <id> [--wait] | still list
blackbox setup agent                      the skill and the session hook (Claude Code, Codex)
blackbox --version | -v | -V              bare version, before anything loads
```

Every command takes `--format json` or `--format edn` (TOON by default); list commands take `--fields a,b|all`
and `--full`; `--meta <folder>` names `media_meta`, `--local <folder>` the cache and cuts folder. The goldens in
`test/golden/` are the byte-level spec; a deliberate change updates them in the same commit, with a line in
CHANGELOG.md.

## Decisions

1. **The index is the system `sqlite3` with FTS5**, driven through `babashka.process` and read back with `-json`.
   No pod: Babashka fetches pods over the network on first use. The index records the SQLite version and a schema
   version and is rebuilt when either changes. Ranking is FTS5's bm25 (`porter unicode61 remove_diacritics 2`).
2. **Line ids survive a rebuild**: `<clip>.<cue>`, e.g. `k7f3q2ab.41`. The clip is the first 8 characters (40
   bits) of the lowercase base32 SHA-256 of the transcript's `<rel>`, the cue its 1-based place in the
   transcript. The same `media_meta` gives the same ids on every Mac; indexing fails loudly on a clip clash. Files
   are indexed in sorted order, equal scores are ordered by id, and no times or machine paths go into the data.
   Cuts keep each line's text, and `edl new` refuses an id whose text has changed.
3. **The cache and the cuts**: the cache in `~/Library/Caches/black-box-cli/`, cuts in `~/Library/Application
   Support/black-box-cli/cuts/`. Black Box's older cuts folder is read, never written, so `edl list` still shows
   earlier cuts.
4. **No stored stills.** `frame` makes a JPEG from the proxy on demand; stills to publish come from the originals
   through Black Box (`still new`).
5. **Output is TOON by default**, `--format json`, and `--format edn` for Clojure (pretty-printed, keys in output
   order). `--format` is read anywhere on the line. Commands build plain data and only `out` renders it, so
   Clojure code can call them as functions.
6. **Black Box does the Core ML work.** `look`'s words become a SigLIP 2 vector in the Black Box app on the same
   Mac (`BlackBox --cli embed query …`, or `$BLACKBOX_APP`). This repo builds no Swift, ships no model and
   downloads nothing. The CLI checks the query's `model` and `label` against the frames files' and refuses a
   mismatch. Scoring is exact over every frame in scope, on all cores, with no vector database. `like` and every
   other command work without Black Box.
7. **The `bin:` line** (AXI §7): `$BLACKBOX_BIN` when set (Black Box's launcher sets it), else the script, with
   `~` for the home folder.
8. **Every search runs locally, from `media_meta` alone.** Voice and face prints are stored in `media_meta` by
   Black Box, like the frame embeddings; the CLI indexes them and scores them exactly (a voice or face like this
   one, whose voice a line is, where a person is heard or seen). It never makes prints.
9. **`people` is this CLI's command**: the read-only list of names. Naming happens on Black Box's People page.
10. **One release file.** A release is an uberjar, `blackbox-cli.jar`, that Babashka runs (`bb blackbox-cli.jar
    …`), with `skills/` inside. Black Box bundles it with `bb` and ffmpeg and writes `~/.local/bin/blackbox` as a
    small script of its own (marked `# black-box: bundled-cli`) that sets `BLACKBOX_TOOLS` to the bundled tools and
    runs the jar. `tools/find-tool` looks in `$BLACKBOX_TOOLS` first, then the PATH and Homebrew. A
    `~/.local/bin/blackbox` that bbin installed from a checkout is left alone by Black Box.

11. **Shot logs are read, never made.** Black Box logs each clip's shots on the mini with a pinned model and
    prompt; the CLI reads one version (`gemini-3.1-pro/v1`), whole, whenever the folder's list of files changes,
    into `shots.sqlite` with a full-text table. A shot id (`<clip>.s<n>`) is a cut list clip: its best moment, or
    the whole shot; the file's transcript gives the timecode.

12. **The cache keeps itself up to date.** A command that reads it first updates it when media_meta has changed
    since the last build (a `find -cnewer` over the sources, checked at most once a minute), so an agent never
    searches yesterday's index without knowing. Each build is recorded beside the index; `status` reads the
    record and media_meta and says the cache's health, for people, agents and Black Box's window alike.
13. **Black Box finds a copy's original.** `source` hands the pictures to Black Box (0.43.0 or later, `--cli source`),
    which picks look-alikes with its picture search and confirms each point by point and pixel by pixel; the CLI
    shows the answer with the picture index's frame id for each hit. A picture on the NAS is refused before Black Box
    is asked, so neither reads an original.

14. **Edits are kept where people find them, and `online` takes one to Resolve here (owner, 2026-11-23).** New
    edits go in ~/Black Box/edits/<id>/ with edit.mp4 (rendered once from the proxies), cut.edl and cut.json; the
    earlier folder is still read. `online` adds pre- and post-roll (default 2 s, clamped to the file), writes
    online-<time>.edl and its plan into the edit's folder, and runs resolve_build.py
    with Resolve's own Python: originals already in the project are used where they are, in any folder (the
    editor's own first; matched by path, or by the same path from media/ on for another mount, never by file name
    alone), and only the rest are imported by path into "Black Box/<id>" with the proxies linked (the editor's clips
    are left as they are); cut.edl comes in as a new timeline that may link only to those clips. If the
    EDL doesn't link every clip to its own original, the clips are appended by frame instead (the same frames). auto
    puts it in the open project, whatever its name, or a new one when Resolve had to be started or the open one is
    the empty Untitled Project Resolve starts with (owner, 2026-11-24: a project that is open is always used first).
15. **The recorders come synced, and a selects review imports (owner, 2026-11-24).** `online` lays each clip's
    recorders under it from the sync map (Black Box's formula: recorder time = camera time + offset_s + drift; only
    pairs whose overlap covers the clip, confidence 0.5 and up), each recorder on its own block of tracks from A2,
    as wide as its channels. `review new` turns selects (a cut list with `group:` sections) into
    ~/Black Box/reviews/<id>/ (review.html with the review's JSON in it, review.json, thumbnails); `review open`
    serves it on 127.0.0.1 with a key, and its Import sends the ticked clips, trimmed or not, to `online`. A line
    said in Italian shows its English (Black Box's translation, ask 12), with the Italian word for word under it
    (owner, 2026-11-25): the English to read, the words as said to cut on, since only they are timed.
16. **Black Box updates the CLI between its own releases (owner, 2026-11-24).** A release is live on every Mac
    minutes after it's published, with no one signing anything by hand: `bb release` builds it here and publishes it with
    a manifest, waiting on nothing (GitHub only re-runs the tests in the background); it signs the manifest as it publishes
    (Ed25519, the key only in the releasing Mac's keychain, never on GitHub); and Black Box, which checks every 2 minutes
    and on start, update and wake, installs only a signed release, with the skill. Each release names the oldest Black Box it works with (`min-black-box` in
    version.clj; raise it when the CLI needs something new from Black Box), and Black Box never runs a CLI older
    than the one it carries, so neither runs ahead of the other. The CLI still talks to nothing: Black Box
    downloads, and `blackbox update` only asks it to check now (docs/CONTRACT.md, CLI updates).
17. **`online` puts what's said on the subtitle track (owner, 2026-11-24).** Every word in each clip's range,
    handles included, as an .srt beside the EDL. Resolve 21.1 has no call to write subtitle items and crashes when
    given frames for a subtitle clip, and it appends one at the timeline's end, keeping each line's time from there;
    so the subtitles go first onto an empty timeline, and then each clip at its own frame (where the frames before it
    end, as the plan counts them), its recorders at their clip's frame and exactly its length. Without anything
    said, the EDL is imported as before. The .srt's Media Pool item goes into `Black Box/Subtitles`, never the cut's
    folder: Resolve crashes when an editor drags a subtitle item into a timeline with the clips.
18. **Footage is found by what shot it, from the files' own metadata (owner, 2026-11-24; Black Box ask 14).**
    Black Box reads each original's camera, rates, resolution, codec and bitrate on the mini into
    `media_meta/probe`; never a folder name, never a location. The CLI indexes the newest file per original and
    narrows every finding command by it (`--device`, `--fps`, `--slow-motion`, `--res`, `--codec`, `--bitrate`).
    Slow motion records no sound, so a file with facts but no transcript is still footage: `footage` lists it, a
    cut list takes it by name (its original's path from the root the transcripts give) with the start timecode
    the facts carry; without one, `cut.edl` counts it from 0:00 and `online` places any cut holding one by frame.
    Each original's facts come from the newest version folder that has it, so a version being republished
    overlays the last.
19. **One review server serves every review, and nothing closes a review (owner, 2026-11-24).** Editors keep
    several selects reviews open in tabs. A server per review on a random port died with its page's quiet or its
    agent's task, and its tab could never reconnect. Now one server on 127.0.0.1:7387 (a free port when that's
    taken) serves each review at `/<id>/`, with a key kept in Application Support, so an address keeps working
    across restarts. `review open` starts it in a session of its own (perl's setsid, which macOS has) when it
    isn't running and returns at once; a newer blackbox replaces an older one's server on the same port. It stops
    itself after a day with no page calling; a page whose server stopped says so and reconnects. Reviews, like
    edits, are kept until a person deletes them.
20. **Three languages, named apart (owner, 2026-11-25).** What's said and who reads it are different things, and
    the flags keep them apart so each can grow on its own:
    - **spoken**: the language a line was said in, by the person in the clip. It's data (a line's `lang`, from
      the transcript and Black Box's translations), never a flag.
    - **read in** (`--read-in`, `review new`): the editor's own language, what the review page reads in (its
      captions, a line's English beside the Italian). `--language` is its old name and still works.
    - **subtitles in** (`--subtitles-in`, `online` and `review new`, and the review's Import): the language of the
      subtitles laid on the Resolve timeline, the audience's; an editor reading German can cut for an English
      audience. `spoken` lays every line as it was said. Beside a track in another language than what was said, the
      words as spoken go on a second track above it, hidden (the owner, 2026-11-25): a translation is spread over its
      line, so only the spoken words, each at its own time, show where to cut. `--no-spoken-subtitles` leaves it out.
      Resolve's scripting (21.1, and 21.2.0.6) fills only one subtitle track: every subtitle clip goes to the track
      that took the first, at the timeline's end, whatever track is asked for, and a `recordFrame` for one crashes
      Resolve. So the build makes the second track, names and hides
      it, and leaves the words in the Subtitles bin for the editor to drag onto it.
    Today there are translations into English only, so `--read-in` marks a clip said in another language (with its
    English when reading in `en`) and `--subtitles-in` takes `en` or `spoken`; another language fails as a usage
    error, saying it waits for translations. Later: Black Box translating into each editor's language (a request to
    it, one per target), `--subtitles-in de` and the page reading in it, a per-editor default (the editor's
    language kept on their Mac), and rough cuts' subtitles (`cut.srt`) in a chosen language too; they're as spoken
    now.

## Not this repo's

Making proxies, transcripts, embeddings, people, prints, the sync map, shot logs or camera facts; Black Box itself; the studio's pipeline;
Resolve beyond new timelines and projects on the same Mac (`online`; the mini's project builds are Black Box's,
this CLI only asks); anything on the NAS.
