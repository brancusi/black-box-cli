(ns black-box-cli.version
  "The version, in a leaf namespace so `--version` answers before anything else loads (AXI §10).")

(def version "0.1.14")

(def min-black-box
  "The oldest Black Box this version works with: the release manifest says it, and Black Box doesn't install this
  version until it is at least this (docs/CONTRACT.md, CLI updates). Raise it when the CLI needs something new from
  Black Box (a `--cli` command, a format)."
  "0.44.4")
