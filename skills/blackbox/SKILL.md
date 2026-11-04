---
name: blackbox
description: Search the studio's footage archive with the `blackbox` command — what was said (transcripts, English and Italian), what's in the picture, what happens in each shot, and who is heard or seen — and make rough cuts that open straight away in mpv, then EDLs for DaVinci Resolve. Use when asked to find footage, moments, quotes, shots, b-roll, a person or a clip, to show or play something, to read a transcript, or to make selects, a rough cut or an EDL.
---
<!-- Installed by `blackbox setup agent` and replaced when it runs again. Delete this line to keep your own edits. -->

# blackbox: the footage archive

Run it as `~/.local/bin/blackbox` (it works whether or not `~/.local/bin` is on the PATH). Output is TOON by
default (`--format json` or `--format edn`); after the output, `help[n]:` lines suggest next commands. Every
command has `--help`. Nothing prompts. Errors come on stdout as `error:` with a `code`; exit 1 is an error, 2 a
command line to fix (the valid flags are listed).

## Where things are: everything you need is on this Mac

- **Dropbox `media_meta`** (`blackbox` alone prints where): transcripts, proxies (small copies of every clip;
  `line` and `search --fields all` print each clip's `proxy`), frame embeddings (picture search), and the names
  and voice and face prints (people), and the shot logs. It's read-only: never write there.
- **This Mac's index and frames:** `~/Library/Caches/black-box-cli/` (a cache: `blackbox index` rebuilds it).
  **Cuts:** `~/Library/Application Support/black-box-cli/cuts/`.
- **The originals are on the NAS** (`source_path`, `/Volumes/…`, `/VideoSource/…`). Never open, read, probe
  (ffprobe, mediainfo), copy, play or transcode them, and never browse the NAS: it's slow, shared and holds the
  only copies. Work from proxies, transcripts and `blackbox frame`. If a clip has no proxy, say so; don't fall
  back to the original.

## Start

`blackbox` alone shows what's indexed (months, hours, files), where `media_meta` is and the latest cuts. If the
index is empty or old, run `blackbox index` first (seconds; it reads what Dropbox has synced into `media_meta`).

## Find what was said

1. `blackbox search marble quarry cava --month 2026-09` — use English and Italian words and synonyms (any word
   matches); narrow with `--all` (every word), `--day 2026-09-18`, `--path fx3`, `--video`.
2. `blackbox line k7f3q2ab.41` or `blackbox transcript C5274.MP4` to read around a hit and check the thought is
   complete.
3. `blackbox footage --month 2026-09` lists the files in scope (day, length, lines).

Line ids look like `k7f3q2ab.41`: the clip, then the line. They're the same on every Mac and after every
`index`, so they can be kept. Speaker labels (S1, S2) are per file, not people: for people, see below.

One moment is often heard on several devices: a camera and the recorders on people's collars. Once Black Box
has synced them, `search` shows such a moment once, with the same line on the other devices in `also`
(`--every-device` lists each). A `check` means the devices' transcripts name different speakers: look before
putting the words in someone's mouth.

## Who: people by voice and face

The mini names voices and faces (on Black Box's People page) and publishes the names; this command only reads
them. Everyone filmed has signed a consent form.

- `blackbox people` lists the names, with how many clips each is heard and seen in.
- "Make a cut of <a person> talking about <x>": `blackbox search marble cava --person Mara`, where `--person`
  takes a name, an alias or the start of one (accents and case ignored). Then cut as below.
- `blackbox footage --person Mara --fields file,day,heard,seen,heard_at,seen_at` says where they're heard or seen,
  and when.
- `--fields all` on `search` and `line` shows each line's `person` (by voice; empty when no one is named).
- `blackbox whose k7f3q2ab.41` says who a line's voice may be: the published name, if any, and everyone scored
  against the voices people confirmed (at or above `threshold` is how the mini names).
  `blackbox whose k7f3q2ab.face2` does the same for a face.
- `blackbox like k7f3q2ab.41` finds the same or a similar voice in other clips; `blackbox like k7f3q2ab.face2`, a
  face. Face ids (`<clip>.face<n>`) come from `frame` (the faces on screen at that moment) and `like`.

A name the mini didn't publish isn't a fact: say "may be" for a score, and never name someone from a score alone
when the user needs certainty.

## Find what's in the picture

- `blackbox look two people at a table --month 2026-09 --per-clip 2` — describe what a camera sees. Black Box
  (the app) turns the words into the search; the first `look` on a Mac may download its model once (0.9 GB).
- `blackbox like k7f3q2ab.f12` — frames that look like one you found, from other clips.
- `blackbox frame k7f3q2ab.f12` — that frame as a JPEG; read the file to see it yourself. `frame` also takes a
  line id (the picture where it starts) or a file with `--at 1:23.5`.
- `--open` writes a page of the frames and opens it in the user's browser (`--html` writes it only and prints
  a quoted `open "…"` line).

Frame ids look like `k7f3q2ab.f12`: the clip, then the frame.

## What happens in a shot: the shot logs

Black Box logs every clip's shots on the mini: where each shot starts and ends (a new one only at a hard cut),
its framing (wide, medium, close), the camera's move (static, handheld, pan, tilt, follow…), what happens in a
sentence, its best few seconds and why, and issues (shake, focus, flat log look…), plus a summary and tags for
the clip. Use them for b-roll, cutaways and picking the usable moment of a take.

- `blackbox shots hands carving stone --framing close` — words match what happens, why a moment is best, the
  issues and the clip's summary and tags. Filters work with or without words: `--camera pan`, `--framing wide`
  (repeatable), `--clean` (no issues logged), `--file C5274.MP4`, `--day`, `--month`, `--path`.
- `blackbox shots --file C5274.MP4` lists one file's shots in order, with the clip's summary.
- `line`, `frame` and `look` show the shot a moment is in (its camera move and action), so a hit from what was
  said or from the picture tells you what the camera was doing.
- `blackbox frame k7f3q2ab.s3` is the middle of a shot's best moment as a JPEG: look before you cut it.
- In a cut list, a shot id plays its best moment (`k7f3q2ab.s3`) or the whole shot (`k7f3q2ab.s3 whole`).

Shot ids look like `k7f3q2ab.s3`: the clip, then the shot. The logs are a model's reading of the proxy: check a
shot's frame before relying on its description, and say "logged as" rather than stating it as fact.

## Stills to publish: from the originals, full size and graded

`frame` is for looking: it comes from the proxy (960×540 at most), ungraded. For a picture to publish (a website,
print, a press kit), find the moment with `search`, `look` and `frame`, then ask for it from the original:

1. `blackbox still new k7f3q2ab.f12 C5274.MP4@40=unloading k7f3q2ab.41=quote` takes frame ids, line ids (where
   the line starts) and file@time, several at once; `=name` names the file (default: the original's name and
   time). `--look neutral` (the default, the house look: gentle and slightly warm), `filmic` (neutral with a film
   curve, cool shadows, warm highlights and fine grain), `rec709` (the camera maker's conversion only) or `log`
   (ungraded, for a colourist); `--tiff` adds a 16-bit TIFF beside each JPEG.
   A look never guesses a white balance: a shot that looks too warm or cool was shot that way. Look at the
   proxy frame, find something white or grey in the scene (a wall, a crate, paper) and add `~white=x,y` to that
   still, its place as fractions of the width and height from the top left: `C5274.MP4@40=unloading~white=0.6,0.56`.
   `still show` reports the gains used (`balance`); reuse them on other frames of the shot with `~gains=r,g,b`.
   `--out <folder>` also copies each still there when it's done (never over a file already there), e.g. your
   project's media inbox.
2. The studio's Mac mini renders each frame from the original camera file at its own resolution (4K and up) and
   Black Box brings the files back to this Mac. It takes minutes, not seconds; `still new` answers at once with
   an id.
3. Run `blackbox still show <id> --wait` **in the background** (your shell tool's background mode): it asks again
   every few seconds and exits when every still is done or failed (or after `--timeout`, 60 minutes), so you're
   told when it finishes. Its output has each still's path (and `copied`, its copy in `--out`). Without `--wait`,
   `still show` says where things stand now. `blackbox still list` lists the requests.

Black Box must be open on this Mac (`app_not_running` otherwise). If a clip looks like an edited export (titles,
a filter baked in), `blackbox like <frame id>` may find the camera's own clip of the same moment: ask for that
one instead.

## Rough cuts: proxies, fast, throwaway — and open them

When the user asks for a rough cut, selects, a string-out, or to show, play or bring up footage, build a cut
and **open it yourself with `--play`** unless they say otherwise. Don't ask first, and don't make them
ask you to open it. One moment is a one-clip cut.

1. Write a cut list, one clip per line in play order (`#` starts a comment, ` # note` after a clip is its note):

   ```
   title: Marble
   k7f3q2ab.41..k7f3q2ab.43  # a run of lines from one file (ids from search)
   C5274.MP4 0:26.5-0:30  # a file (name or part of its path) from in to out
   m2xq7rja.7
   sqlknzuz.s2  # a shot's best moment (ids from shots); `sqlknzuz.s2 whole` for all of it
   ```
2. `blackbox edl new - --dry-run` (cut list on stdin) to check it; every problem is listed and nothing is
   written.
3. `blackbox edl new - --play` writes it, renders it to one file and opens it. Tell the user what's in it in a
   line or two.
4. Revising: write a new cut list (each cut gets its own folder; nothing is overwritten) and `--play` again.
   `blackbox edl list` and `blackbox edl show marble --play` reopen earlier ones.

`blackbox edl new --help` has the whole cut list format, including JSON. The rough cut plays proxies only: it
loads at once and never touches the NAS. A clip without a proxy is left out of it (listed under `warnings`) but
stays in `cut.edl`.

**Playing:** `--play` renders the cut into one mp4 (a second or two; clips at 960×540, what's said as
subtitles) and opens it in the Mac's default video app, which each editor chooses in Finder (Get Info → Open
with → Change All). With `BLACKBOX_PLAYER=mpv` it opens in mpv. A recorder's line plays under the picture of a
camera heard with it (the speaker's face on screen first), or over its own waveform, marked "audio only", when no
camera was. `playing` says what happened and counts both; the `help` line has the file's path, to play it again
with `open`. If the render fails, mpv plays `cut.mpv.edl` instead (it can stutter at cuts); if mpv isn't
installed either, say `brew install ffmpeg`.

## Taking a cut to DaVinci Resolve (only when asked)

Only when the user wants the cut in Resolve:

- **A project ready to edit:** `blackbox edl build <id>` asks the studio's Mac mini, through the Black Box app on
  this Mac, to build the cut as a new Resolve project on the shared database, named "<title> (<id>)" in the
  "Black Box cuts" folder: the originals laid out, the recorders' audio synced to them, the proxies linked. It
  answers at once with a `request` and a `state`; the build takes minutes. `blackbox edl show <id>` has a
  `project` block with its state (`queued`, `building`, `done` with a `report`, or `failed` with an `error`);
  tell the user when it's done, and what the report says was missing. Black Box must be open on this Mac
  (`app_not_running` otherwise). Each cut is built once: for changes, make a new cut and build that.
- **Or a timeline to import:** the cut's `cut.edl` (CMX3600, in the clips' source timecode) imports with File →
  Import → Timeline into a project that already holds the originals. Give the user its path from `edl new` or
  `edl show`.

Never drive Resolve yourself, and never delete or archive a project: a person does that.

## Not here

Card offloads, the catalog, proxies and Resolve day timelines belong to the Black Box app, not this command.
