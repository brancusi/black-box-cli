# Changelog

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
