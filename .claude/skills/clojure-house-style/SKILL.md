---
name: clojure-house-style
description: How to write Clojure in black-box-cli (Babashka, AXI output through `out` only, ordered maps for TOON, the goldens as the spec, tests on the fixture media_meta) and how to work through the shared REPL. Use before writing or changing any .clj or .edn file here, and whenever you need to evaluate, reload or test code.
---

# Clojure in black-box-cli

The project's rules are in `AGENTS.md`; this skill is how to apply them in code. When this skill and AGENTS.md
disagree, AGENTS.md wins. The pattern comes from brancusi/xero-cli.

## Work through the REPL

There is one long-lived Babashka nREPL, shared with the person you work with.

- The port is in `.nrepl-port` at the repository root. If no REPL is running, ask the person before starting one
  (`bb nrepl`, in the background).
- Evaluate with `clj-nrepl-eval -p "$(cat .nrepl-port)"` and a heredoc (`<<'EOF'`); the `clojure-eval` skill has
  the details. Start with `(in-ns 'dev)`: each call may land in `user`.
- `dev/dev.clj`: `(cli "search" "marble")` runs the command line in-process against `test/fixtures/media_meta` and
  the session's `--local` folder (`(cli "index")` first; `(fresh!)` empties it) and returns `{:exit :out :local}`; `(show ...)` prints it instead; `(reload)` reloads
  `src/`; `(tests)` runs the tests, `(tests 'black-box-cli.flags)` one namespace's; `(goldens)` or `(goldens 5 18)`
  says which golden steps match, with a diff for the ones named.
- The person may be in the same REPL through Calva (AGENTS.md, The REPL). Don't restart it or redefine `dev`'s vars
  under them; `(reload)` is shared and safe.
- Never point the REPL at a real `media_meta` unless the person asks. What comes back stays in the REPL: don't write
  it to a file and don't quote it into the repository (AGENTS.md rule 7).
- Every definition lives in a file. Before saying something works, show it: evaluate it, or run its test.
- The shell is the truth: `bb test`, `bb lint` and `bb fmt` before every commit.
- Edit files with Edit and Write, not sed: a hook repairs delimiters and runs cljfmt on those. If you must change a
  file another way, run `clj-paren-repair <file>` after.

## Shape of the code

- Namespaces under `src/black_box_cli/` each own one thing (AGENTS.md, Layout). Commands live in `main` (split per
  command group, such as `search` or `edl`, when it grows); the others return data and never print.
- `out` owns stdout: `(out/emit! m help fmt)` for results, `(out/fail! "code" "What went wrong." {:help [...]})` for
  errors. Never `println` elsewhere (clj-kondo warns). stderr is for nothing an agent needs.
- **Key order is output.** TOON and JSON print a map's keys in its own order, and the goldens fix that order. Build
  every output object with `(array-map ...)` in the order the golden shows, and never `assoc` onto one past eight
  keys (it becomes a hash map). Rows of a table must share their keys in the same order.
- **Numbers are output too.** A double prints as Python's repr (`toon/py-float`), a whole double in TOON without
  `.0`. Keep longs as longs and doubles as doubles where the golden has `9` or `9.0`.
- Flags: each command declares its own; an unknown flag is a usage error (exit 2) that lists the command's flags
  (AXI §6). `--help` always passes.
- Pure functions where it costs nothing; I/O at the edges (reading `media_meta`, the index, writing cuts, running
  `sqlite3`, `ffmpeg` or `mpv` through `babashka.process`).
- Docstrings on every public var. Private helpers with `defn-`. No `:refer :all`; alias namespaces.
- Follow the [Clojure style guide](https://github.com/bbatsov/clojure-style-guide): threading over nesting, `when`
  over single-branch `if`, destructuring in argument lists, small functions.

## Babashka

- Use what Babashka bundles (`babashka.fs`, `babashka.process`, `babashka.cli`, `clojure.data.json`) before adding
  a dependency. A pod (such as go-sqlite3) is a decision: record it in docs/DESIGN.md.
- `bb.edn` is EDN, not Clojure: no `#(...)`, `@`, or `'` in task bodies.
- `--version` must answer before the command graph loads: `version` is a leaf namespace and `main` checks the
  flag first. Keep heavy requires out of `main`'s top (require them when a command runs) if startup grows.

## Safety in code

- Paths from `media_meta` are read with `babashka.fs` and never resolved to the NAS. A transcript's `source.path`
  is shown, never opened.
- Cut folders: create the folder new (`fs/create-dirs` on a name that can't exist yet, a UTC stamp plus slug), then
  each file with `CREATE_NEW` semantics; fail if anything is there.
- The index: build into a temporary file beside it, then `fs/move` with `:replace-existing` only for the cache.

## Tests

- Tests sit in `test/black_box_cli/*_test.clj` and use `black-box-cli.support`: the fixture `media_meta` and a new
  temporary folder each time. Never a real `media_meta`, never `/Volumes`.
- A golden step a phase finishes becomes a test: move it from `bb golden`'s report into a `deftest` that asserts the
  exact output, so it can't slip back.
- Every name, line and clip in a fixture is invented.
