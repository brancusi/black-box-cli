# What the CLI reads and writes

`media_meta` is produced by Black Box on the studio's Mac mini and synced by Dropbox to every Mac, view-only for
editors. The CLI only reads it. Formats change only in Black Box (AGENTS.md, "Working with Black Box").

## Finding `media_meta`

`--meta <folder>`, else `BLACKBOX_META`, else the one folder `~/Library/CloudStorage/Dropbox*/media_meta` that
carries the marker file `.media-mgmt-meta-root`. None found: `error.code: no-meta`, exit 1, with a `--meta` hint.
Files Dropbox keeps online-only are skipped unread and counted (`skipped`), never downloaded by the CLI.

## Inside `media_meta`

| Folder | What | Layout |
|---|---|---|
| `transcripts/` | what was said, one file per clip per version | `<version>/<rel>.json` (also `.srt`, `.vtt`, `.txt`); search uses version `deepgram-nova-3-multi`; the Whisper tree `media/` is older |
| `proxies/` | small H.264 copies of every clip, and exact copies of audio-only files | `<rel>.proxy.mp4` (audio: `<rel>` itself) |
| `embeddings/` | picture-search vectors, one file per clip | `<provider>/<setup>/<rel>.frames.json`, e.g. `google/siglip2-base-patch16-256-5s/…` |
| `people/` | names per clip: voices by speaker label, faces by face track (`media-mgmt.people/1`) | `registry/<stamp>.json` names `assignments/<stamp>.jsonl`; only the newest registry is read, and its assignments must match its `assignments_sha256` |
| `prints/` | voice and face prints, float32 (`media-mgmt.prints/1`) | `<model>/<rel>.<stamp>.prints.json`; only the newest stamp of each (model, rel) is read |
| `shots/` | what happens in each shot of a clip (`blackbox.shots/1`), by a model from the proxy with its sound | `<model label>/<prompt version>/<rel>.shots.json`; the CLI reads `gemini-3.1-pro/v1`; marker `.blackbox-shots-root`; never redone in place |
| `sync/` | which recorder files were heard with each camera file, and the offset (`media-mgmt.sync/1`) | `<YYYY>/<MM>/<DD>.<stamp>.sync.jsonl`; only the newest stamp of each day is read; a pair joins on (rel, sha256) |

`<rel>` is the original's path relative to the NAS media share, e.g. `media/2026/09/16/src/fx3/C5274.MP4`. Its
date folders give `day` and `month`.

### Transcript JSON (`media-mgmt.transcript/1`)

```json
{"schema": "media-mgmt.transcript/1",
 "source": {"path": "<NAS path: never open it>", "name": "C5274.MP4", "sha256": "…", "size": 1, "duration": 32.5},
 "producer": {"engine": "deepgram/1", "label": "deepgram-nova-3-multi"},
 "language": "multi",
 "clock": {"kind": "timecode", "start_timecode": "03:06:51:08", "rate": "24000/1001", "time_of_day_start_s": null},
 "cues": [{"start": 22.2, "end": 24.0, "text": "…", "speaker": "S1",
           "words": [{"w": "We", "start": 22.2, "end": 22.45}]}]}
```

- Times are seconds on the file's own timeline. `clock.kind` is `timecode` (source timecode for `cut.edl`) or
  `elapsed`.
- `speaker` (S1, S2 …) is per file only: S1 in one file is not S1 in the next. Names come from `people/`, joined
  on (`rel`, transcript sha256, speaker): a redone transcript drops its names until the mini names it again.
- A file that isn't this schema (an empty `{}`, a Dropbox conflict copy like `C5900.MP4 2.json`) is skipped.

### Shot log JSON (`blackbox.shots/1`)

```json
{"schema": "blackbox.shots/1",
 "clip": {"rel": "media/2026/09/16/src/fx3/C5274.MP4", "sha256": "…", "duration_s": 32.5},
 "proxy": {"rel": "…/C5274.MP4.proxy.mp4", "sha256": "…"},
 "shots": [{"start_s": 20.5, "end_s": 32.5, "framing": "close", "camera": "static", "action": "…",
            "best": {"start_s": 26.5, "end_s": 29.5, "why": "…"}, "issues": ["shake"]}],
 "summary": "…", "tags": ["…"],
 "producer": {"tool": "…", "model": "…", "label": "…", "prompt_version": "v1", "media_resolution": "…",
              "pieces": 1, "tokens": {"input": 0, "output": 0}, "cost_usd": 0.0, "made_at": "…"}}
```

- Times are seconds on the clip's own timeline, as in transcripts. A new shot starts only at a hard cut; a long
  clip logged in pieces is joined so shots stay whole. `framing` and `camera` come from fixed lists or are null;
  `issues` is `[]` for none.
- A log whose `clip.rel` isn't its own path, or that isn't this schema, is skipped and counted. Shot ids are
  `<clip>.s<n>`, n its place in the log.

### Frames JSON (`media-mgmt.embeddings/1`)

One per clip: the proxy's SHA-256, the original's, the frame rule (a frame every 5 s), and per frame its time
(`pts`) and a float16 vector (768 values, base64). Text queries are embedded by SigLIP 2's text half (Core ML, in
Black Box); image vectors are float32-exact before storage.

## What the CLI writes (on this Mac only)

| What | Where (docs/DESIGN.md, decision 3) | Rule |
|---|---|---|
| The index (cache) | `<local>/search/transcripts.sqlite`; beside it the frames, names (`people.sqlite`), prints (`prints.sqlite`) and their vectors files, the sync map (`sync.sqlite`) and the shot logs (`shots.sqlite`) | rebuilt from `media_meta`; replaced only by rename of a new one |
| Cuts | `<local>/cuts/<UTC stamp>-<slug>/`: `cut.mpv.edl`, `cut.edl`, `cut.srt`, `cut.json`, `cut.html`; `build.json` once `edl build` has sent it | a new folder each time, files created new |
| Frames for `frame` | a temp folder | never in `media_meta` |
| Stills (`still new`) | `<local>/stills/<UTC stamp>-<look>/`: `request.json`, then the stills Black Box brings back (`<name>.jpg`, `<name>.tif`); copies in the `--out` folder when one is named | a new folder each request, files created new; Black Box writes the stills; copies never over a file |
| Rendered cuts (`--play`) | `<local>/previews/<cut id>-<hash of cut.mpv.edl>.mp4` (cache) | made once from the proxies; renamed into place |
| The skill and hook (`setup agent`) | `~/.claude/skills/blackbox/`, `~/.claude/settings.json`; Codex's `~/.agents/skills/blackbox/`, `~/.codex/hooks.json` (settings copied aside first) | never over a hand-edited file |

By default the index is in `~/Library/Caches/black-box-cli/` and cuts in `~/Library/Application
Support/black-box-cli/cuts/` (docs/DESIGN.md, decision 3); `--local <folder>` puts both under that folder. Black Box's old
cuts folder (`~/Library/Application Support/Media Utilities/media-mgmt/cuts`) is read by `edl list` and `edl
show`, never written.

### `cut.json`

Black Box's format (`media-mgmt.cut/1`), so either reads the other's cuts. Each clip also says the line ids it
came from, `from` and `to` (null for a file clip), and keeps the lines' text, so `edl new` can refuse a clip whose
lines no longer say what the cut list expected (a JSON clip's `text`; docs/DESIGN.md, decision 2).

### `build.json` and Black Box's `project` commands

`edl build <id>` runs `<Black Box> --cli project build --cut <cut folder> --format json` (the app's binary by its
full path, or `$BLACKBOX_APP`). The running app forwards the request to the mini, which builds the project; the
CLI talks to nothing else. Black Box answers `{request, state, project}` at once, and the CLI writes `build.json`
in the cut folder, created new: `{schema: "black-box-cli.build/1", request, project, asked_at}`. A cut is sent
once; a cut in Black Box's old folder is refused (never written there). `edl show` runs `project status <request>`
(`{request, state, project, report?, error?}`) and shows `project {name, request, state}`, plus `report` when
`done` (counts and `notes`, shown as given: Black Box may add keys) or `error {code, message}` when `failed`
(always beside the state; `project_exists` arrives that way); if
Black Box can't answer, the state is `unknown: <why>` and the rest of `show` still works. Black Box's errors keep
their codes (`app_not_running`, `no_server`, `server_unreachable`, `unknown_request`, `bad_cut`); status answers only
on the Mac that asked, also after a relaunch; an older Black Box without `project` (usage error, exit 2) is
`black-box-too-old`.

### Stills and Black Box's `still` commands (Black Box 0.37.0)

`still new` resolves each moment as `frame` does (a frame id at its frame's time, a line id at its start,
`file@time`) to the original's `rel` and `sha256` from the index, makes a new folder under `<local>/stills/`,
and runs `<Black Box> --cli still request --out <folder> --format json` with `{schema: "black-box-cli.still/1",
look, formats, items: [{n, rel, sha256, t, name, balance?}]}` on stdin (`balance`, Black Box 0.39.0, from
`~white=x,y` or `~gains=r,g,b` on the moment: `{white: [x, y]}` or `{gains: [r, g, b]}`, never with `log`; the
looks are `neutral`, `filmic` (0.39.0), `rec709` and `log`). Black Box answers `{request, state}`; the CLI then
writes `request.json` (`{schema, request, look, formats, asked_at, items}`, items with `source_name` and `tc` too),
created new. If Black Box can't take it, the empty folder is removed and nothing is kept. `still show` runs `still
status <request>` and reads `{state, items: [{n, state, width, height, camera, input, look_version, t_frame,
flags, balance, error {code, message}}]}` (`balance` the gains used, shown as `r g b`): `camera` the model (`ILME-FX3`), `input` what was decoded (`slog3-sgamut3cine`,
`hlg-bt2020`, `rec709`), `look_version` like `neutral/1`, `t_frame` the time of the frame rendered, `flags`
`["not_camera_original"]` for an edited export (rendered as plain Rec.709 with the look's grade). The answer also has
`renderer` (e.g. `ffmpeg 9.0.2`); an item may be `fetching` while its files come over, and has `files [{name,
size, sha256}]`. Item errors: `no_original`, `bad_time`, `decode_failed`, `unsupported_input` (PQ, Dolby
Vision), `exists`, `fetch_failed`, `interrupted`, `unknown_request`. Request errors (exit 1): `app_not_running`,
`no_server`, `server_unreachable`, `server_too_old`, `no_role`, `bad_request`, `bad_out` (the folder must be
absolute and exist; the CLI makes it first), `exists`. A still is done when Black Box says so; without an answer,
when `<name>.*` is in the folder (Black Box writes `<name>.jpg.part`, checks its SHA-256, renames it). The same
request gives the same bytes. Flags include `dji_profile_assumed_normal` and `sony_gamma_<name>`; `input` also
`sony-<gamma>`, `applelog-bt2020`, `p3-sdr`. JPEG about q95 with an sRGB profile; TIFF 16-bit Rec.709 with a
profile. HDR is tone-mapped to SDR in `neutral` and `rec709`, left alone in `log`. Names are
`[A-Za-z0-9][A-Za-z0-9._-]*`, default `<original's name without extension>_<t>s`.

`still new --out <folder>` keeps the folder in `request.json` (`out`); each time `still show` sees a still done, it
copies its files there, each created new (a `.part` renamed into place). A file already there with the same bytes
counts as copied; a different one is left alone and reported. `--out` inside media_meta, `/Volumes`,
`/VideoSource`, `/AssetSource` or `/VideoProxies` is a usage error. `still show --wait` asks Black Box again every
10 seconds until every still is `done` or `failed`, or `--timeout` minutes (60) pass, and reports `waited`; it keeps
no state and starts nothing in the background.

### The rough cut (`cut.mpv.edl`)

mpv's EDL over the proxies only: `# mpv EDL v0`, then `%<bytes>%<proxy path>,<start>,<length>` per clip. A clip
without a proxy is left out of it, with a warning (`no proxy, left out of the rough cut (cut.edl keeps it for
Resolve)`); it is never replaced by the original.

### `cut.edl`

CMX3600 in the clips' source timecode, for File → Import → Timeline in Resolve, where it matches the clips already
in the shared project (originals with their proxies linked). The CLI never touches Resolve.
