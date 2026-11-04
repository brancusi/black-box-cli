# Changelog

## Unreleased

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
