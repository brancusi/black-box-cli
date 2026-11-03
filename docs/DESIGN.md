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
  `BLACKBOX_FORMAT`, `BLACKBOX_APP`, `BLACKBOX_TOOLS`, `BLACKBOX_BIN`, `BLACKBOX_PLAYER`).

## Commands

```
blackbox                                  home view: bin, description, what the cache holds, recent cuts, help
blackbox index [--rebuild]                build or refresh the cache from media_meta
blackbox search <words…> [--all] [--person NAME] [--month YYYY-MM] [--day YYYY-MM-DD] [--path P] [--video] [--limit N]
blackbox line <id> [--context N]          one line in context: file, timecode, proxy
blackbox transcript <file> [--limit N]    a file's lines
blackbox footage [--person NAME] [--month …] [--day …] [--video]
blackbox edl new <cutlist|-> [--title T] [--dry-run] [--play]
blackbox edl list | edl show <id> [--play] | edl build <id>
blackbox frame <file|line id|frame id> [--at T]   a JPEG from the proxy (ffmpeg)
blackbox look <words…> | like <frame id> [--open|--html]   picture search
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

## Not this repo's

Making proxies, transcripts, embeddings, people, prints or the sync map; Black Box itself; the studio's pipeline;
Resolve (project builds are Black Box's; this CLI only asks); anything on the NAS.
