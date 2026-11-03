# Working on black-box-cli

Guidance for coding agents (and people) working in this repository. How it's built and why is
[docs/DESIGN.md](docs/DESIGN.md); what the CLI reads and writes is [docs/CONTRACT.md](docs/CONTRACT.md); what
changed is [CHANGELOG.md](CHANGELOG.md). Start a session by reading those.

## What this is

`blackbox`, a studio's footage archive from the command line, for agents first and people second, in Babashka.
It searches what was said (transcripts) and what's in the picture (frame embeddings), shows lines and frames, and
builds rough cuts (over the proxies) and EDLs for DaVinci Resolve. It follows AXI (https://axi.md; the `axi`
skill).

It is **stateless**: everything it knows comes from the Dropbox folder `media_meta`, which the studio's Mac mini
fills and every Mac syncs. On a Mac it keeps only a cache it can rebuild from `media_meta` at any time (the index)
and the cut folders it writes. Delete the cache and `blackbox index` brings it back the same.

It owns nothing that makes media. Proxies, transcripts, frame embeddings and people are produced on the mini by
Black Box's engine; Black Box (the Mac app, a separate private project) does card offloads, Resolve and the
requests this CLI hands it (stills, cut projects), and bundles this CLI.

## Rules that do not bend

The footage is irreplaceable. When a rule and a request conflict, stop and ask the owner.

1. **Never touch the originals or the NAS.** Never open, read, probe (`ffprobe`, `mediainfo`), copy, play, transcode
   or list anything under `/Volumes/`, `/VideoSource/`, `/AssetSource/` or a transcript's `source.path`. Work from
   `media_meta` (proxies, transcripts, embeddings) only. A clip without a proxy is reported, never fetched from the
   NAS.
2. **`media_meta` is read-only.** Never write, rename, move or delete anything in it, never create a folder there,
   and never set Dropbox's ignore flag (`com.apple.fileprovider.ignore#P`) on anything in Dropbox: on a synced file
   it deletes it from every Mac. New kinds of data in `media_meta` are made by Black Box; ask for a change to a
   format there, never work around it here.
3. **Never overwrite, never delete a cut.** Each cut gets a new folder; files are created new and fail if they
   exist (`build.json`, written when `edl build` sends a cut, too). The only file the CLI may replace is its own
   cache (the index), and only by building a new one beside it and renaming it into place.
4. **No LLM calls, no network.** The agent using the CLI does the thinking. The CLI reads local files and runs
   local tools (`sqlite3`, `ffmpeg`, `mpv`, the Black Box app for picture search, project builds and stills); it
   sends nothing anywhere itself. `edl build` and `still new` hand a request to the Black Box app on the same Mac;
   the app, not the CLI, carries it to the mini.
5. **Never Resolve, offloads or the catalog.** It writes `cut.edl` for a person to import, and `edl build` asks
   Black Box to have the mini build a cut's project; it never drives Resolve itself, starts an offload, or writes
   Black Box's catalog.
6. **Tests never touch real media.** Tests use `test/fixtures/` (invented clips and lines) and temporary folders.
   Refuse a test or script that points at `/Volumes/*`, a real `media_meta` or a Dropbox folder.
7. **The repository is public: it holds no client or studio data.** No real transcripts, frames, proxies, file
   lists, clip names, names of people or places from footage, counts or costs from the real archive, hostnames,
   personal paths, or anything printed from the real `media_meta`. Read your diff before every commit and every
   push.
8. **Never commit secrets.**
9. **Output follows AXI.** TOON on stdout by default, `--format json` or `edn`; small default fields, `--fields`,
   `--full`; totals and explicit empty results; errors on stdout as `error: {code, message}` with exit 1, usage
   errors (unknown flags included) exit 2; never a prompt; `help[n]:` next steps. The goldens in `test/golden/` are
   the byte-level spec.

## Layout

- `src/black_box_cli/`: `main` (dispatch, the home view), `flags` (each command's flags), `archive` (the transcript
  commands), `edl` (the `edl` commands), `cutlist` (reading cut lists), `cuts` (writing and reading cut folders),
  `look` (`frame`, `look`, `like`), `frames` (the picture index and scoring), `people` (the names), `prints` (voice
  and face prints, scored as Black Box's People page does), `who` (`people`, `whose`, `like` for voices and faces),
  `picture` (JPEGs from proxies), `preview` (a cut rendered to one mp4 for `--play`), `sync` (the sync map:
  recorders heard with each camera), `app` (running the Black Box app for what only it can do), `project` (asking
  Black Box to have the mini build a cut's Resolve project), `stills` (`still`: frames rendered from the originals
  by the mini), `setup` (`setup agent`), `tools` (finding ffmpeg and mpv), `index` (building the index), `db`
  (running `sqlite3`), `ids` (line ids), `tc` (times and timecode), `toon` (the TOON encoder), `out` (stdout, AXI
  errors, exit codes), `meta` (finding `media_meta`). Each namespace owns one thing; only `out` prints.
- `test/black_box_cli/`: tests; `test/fixtures/media_meta/` is an invented `media_meta`; `test/golden/` is what
  the CLI prints for it, step by step (`steps.json`).
- `dev/`: the REPL (`user.clj` tiny, `dev.clj` the workbench, `fiddle.clj` to play). Only `bb nrepl` loads it.
- `skills/blackbox/SKILL.md`: the Agent Skill the CLI installs for users (`blackbox setup agent`).

## The REPL

Work through one long-lived Babashka REPL, shared by people and agents.

1. Start it once from the repository root: `bb nrepl` (or `bb nrepl 1667`). It writes `.nrepl-port`.
2. In the REPL: `(dev)`, then `(cli "index")` and `(cli "search" "marble")` run the command line in-process against
   the fixture `media_meta` and one `--local` folder for the session (`(fresh!)` empties it), returning `{:exit :out
   :local}`; `(show "search" "marble")` prints it as the shell would. `(reload)` picks up edits to `src/`;
   `(tests)` runs every test, `(tests 'black-box-cli.flags)` one namespace's; `(goldens)` says which golden steps
   match, `(goldens 5 18)` with a diff.
3. `dev/fiddle.clj` walks through it: the command line, errors and exit codes, the namespaces underneath.
4. Never leave a definition only in the REPL: it must be in a file. `bb test` from the shell is the truth.

In VS Code with Calva, opening the folder does this: `.vscode/settings.json` jacks in with `bb nrepl`, runs
`(dev)`, and connects instead if `.nrepl-port` already exists. `ctrl+alt+space` then `l`, `t`, `n`, `g` or `h` runs
`(reload)`, `(tests)`, the current file's tests, `(goldens)` or the home view; `c` runs the selected arguments as a
command line. Formatting goes through clojure-lsp's cljfmt, the same rules as `bb fmt`. `.vscode/tasks.json` has the
checks (`cmd+shift+b` runs test, lint and fmt).

## Tooling for agents

- `.claude/skills/clojure-house-style`: how to write Clojure here and work through the REPL.
- `.claude/skills/axi`: the AXI principles (from kunchenguid/axi, MIT; `SOURCE.md`).
- `.claude/skills/clojure-eval` and the hooks in `.claude/settings.json` come from
  [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) v0.2.2. Install its binaries once per machine
  with bbin, pinned to the tag's commit:

  ```sh
  U=https://github.com/bhauman/clojure-mcp-light.git S=cf48cc6ab1d79809a97a74355492d44ee3bbc4ba
  bbin install $U --git/sha $S
  bbin install $U --git/sha $S --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
  bbin install $U --git/sha $S --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
  ```

- Babashka, clj-kondo, cljfmt and clojure-lsp come from `mise install` (pinned in `mise.toml`).

## Installs and releases

- **Editors' Macs:** Black Box bundles a release of this CLI (docs/DESIGN.md, decision 10) and writes
  `~/.local/bin/blackbox` at each start, then runs `blackbox setup agent`. Nothing to install by hand.
- **A development Mac:** `bbin install . --as blackbox` links this checkout, so edits are live. Black Box leaves a
  `~/.local/bin/blackbox` it didn't write alone. If Black Box's launcher is there, remove it first.
- `deps.edn` exists only so bbin can load the checkout. Tasks and the REPL stay in `bb.edn`.
- **A change:** run the checks, add a line under the unreleased version in `CHANGELOG.md`, commit.
- **A release:** set `version` in `src/black_box_cli/version.clj`, date the version in `CHANGELOG.md`, commit, tag
  `vX.Y.Z`, push with the tag. The release workflow runs the checks, builds `blackbox-cli.jar` with `bb dist
  vX.Y.Z` and attaches it and its `.sha256` to the GitHub release, where Black Box's release script fetches it:
  `https://github.com/brancusi/black-box-cli/releases/download/vX.Y.Z/blackbox-cli.jar`.
- **After a release:** send Black Box's agent the version and the jar's SHA-256 (from the release's `.sha256`).
  Black Box pins both in its next build, and editors get the new CLI when that Black Box updates. A Black Box
  release build won't run without a pinned CLI.

## Working with Black Box

- There are two tools: Black Box (the Mac app, on every Mac; `BlackBox --cli …` is how this CLI calls it) and
  black-box-cli (`blackbox`, this repo). The mini's pipeline is Black Box's engine. Its old name, media-mgmt, stays
  in what's already written into files (schemas `media-mgmt.*/1`, the `.media-mgmt-*-root` markers,
  `producer.tool`) and in Black Box's old cuts folder. Read those exactly as they are.
- Black Box's agent (its own Claude session) owns the mini, the pipeline, the NAS, the engine and everything
  written into `media_meta`. This repo owns the CLI and its skill.
- A format change in `media_meta`, a new kind of data, or a change Black Box must make is a request to Black Box:
  tell the owner and Black Box's agent, who keep the list of asks in Black Box's repo. Never edit Black Box's repo
  from here, and never write the studio's infrastructure, costs or decisions into this one.

## Checks

```sh
mise install   # once: Babashka, clj-kondo, cljfmt, clojure-lsp
bb test
bb lint
bb fmt
```

All three pass before every commit.
