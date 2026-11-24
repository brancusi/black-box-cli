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
    puts it in the open project, or a new one when Resolve had to be started or nothing real is open.
15. **The recorders come synced, and a selects review imports (owner, 2026-11-24).** `online` lays each clip's
    recorders under it from the sync map (Black Box's formula: recorder time = camera time + offset_s + drift; only
    pairs whose overlap covers the clip, confidence 0.5 and up), each recorder on its own block of tracks from A2,
    as wide as its channels. `review new` turns selects (a cut list with `group:` sections) into
    ~/Black Box/reviews/<id>/ (review.html with the review's JSON in it, review.json, thumbnails); `review open`
    serves it on 127.0.0.1 with a key, and its Import sends the ticked clips, trimmed or not, to `online`. English
    under Italian lines waits for Black Box's translations in media_meta (ask 12).
16. **Black Box updates the CLI between its own releases (owner, 2026-11-24).** A release is live on every Mac
    minutes after it's published, with no one signing anything by hand: `bb release` builds it here and publishes it with
    a manifest, waiting on nothing (GitHub only re-runs the tests in the background); it signs the manifest as it publishes
    (Ed25519, the key only in the releasing Mac's keychain, never on GitHub); and Black Box, which checks every 2 minutes
    and on start, update and wake, installs only a signed release, with the skill. Each release names the oldest Black Box it works with (`min-black-box` in
    version.clj; raise it when the CLI needs something new from Black Box), and Black Box never runs a CLI older
    than the one it carries, so neither runs ahead of the other. The CLI still talks to nothing: Black Box
    downloads, and `blackbox update` only asks it to check now (docs/CONTRACT.md, CLI updates).

## Not this repo's

Making proxies, transcripts, embeddings, people, prints, the sync map or shot logs; Black Box itself; the studio's pipeline;
Resolve beyond new timelines and projects on the same Mac (`online`; the mini's project builds are Black Box's,
this CLI only asks); anything on the NAS.
