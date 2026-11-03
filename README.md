# black-box-cli

`blackbox`: a footage archive from the command line, for agents first. Search what was said and what's in the
picture, find who is heard or seen, read lines in context, and build rough cuts (over the proxies) and EDLs for
DaVinci Resolve. Babashka; stateless (everything comes from a synced `media_meta` folder, which it only reads);
[AXI](https://axi.md) output.

It reads the `media_meta` that the studio's pipeline (Black Box, a separate project) writes, and hands Black Box the
few things only it can do (picture-search queries, stills from the originals, Resolve projects). On its own it's a
reference for an agent-first CLI in Babashka more than a tool you can use as is.

- How it's built: [docs/DESIGN.md](docs/DESIGN.md)
- What it reads and writes: [docs/CONTRACT.md](docs/CONTRACT.md)
- Working on it: [AGENTS.md](AGENTS.md)

```sh
mise install          # Babashka, clj-kondo, cljfmt, clojure-lsp
bb test               # the tests (an invented media_meta only)
bb -m black-box-cli.main --meta test/fixtures/media_meta --local /tmp/bbcli index
bb -m black-box-cli.main --meta test/fixtures/media_meta --local /tmp/bbcli search marble
bb dist               # target/blackbox-cli.jar, the release file (bb target/blackbox-cli.jar --version)
```

MIT licence ([LICENSE](LICENSE)).
