---
name: blackbox
description: Search the studio's footage archive with the `blackbox` command — what was said (transcripts, English and Italian), what's in the picture, what happens in each shot, who is heard or seen, and what shot it (camera, slow motion, frame rate, resolution) — build selects as a review page (watch, read along, select, import into Resolve), make rough cuts (edits) that play straight away, online an edit in DaVinci Resolve from the camera originals with the recorders synced, and pull full-size stills. Use when asked to find footage, moments, quotes, shots, b-roll, a person or a clip; to build or pull selects, sound bites or options; to cut, play or show something; to read a transcript; to "online" an edit or take it to Resolve; for stills; or "teach me about Black Box", a tutorial, what Black Box can do or what's new.
---
<!-- Installed by `blackbox setup agent` and replaced when it runs again. Delete this line to keep your own edits. -->

# blackbox: the footage archive

Run it as `~/.local/bin/blackbox` (it works whether or not `~/.local/bin` is on the PATH). Output is TOON by
default (`--format json` or `--format edn`); after the output, `help[n]:` lines suggest next commands. Every
command has `--help`. Nothing prompts. The CLI may be newer than this skill: `blackbox --help` lists every
command it has, and `blackbox tutorial` explains them. Errors come on stdout as `error:` with a `code`; exit 1 is
an error, 2 a command line to fix (the valid flags are listed).

## What they ask for, and what you run

| They say | You run |
|---|---|
| "teach me about Black Box", a tour, a tutorial | `blackbox tutorial --open` |
| "what's new in Black Box?" | `blackbox tutorial new` (and offer `--open`: its What's new plays the new parts) |
| "build me selects / a selects review of …", "pull sound bites and b-roll of …", "show me options for …" | find clips, then `blackbox review new - --open` (Selects review, below) |
| "cut me …", "make an edit / a rough cut / a string-out of …", "play / show me …" | find clips, then `blackbox edl new - --play` (Rough cuts) |
| "online this", "put it in Resolve", "go to source" | `blackbox online <edit id>` |
| a still for print or the web | `blackbox still new …`, then `still show <id> --wait` in the background |
| "slow motion from the FX3", "only 4K", "Osmo footage", "shot at 60" | the filters on `footage`, `look`, `shots`, `search` (Find by camera, below) |
| "where is this picture from?" | `blackbox source <picture>` |
| "update Black Box's CLI", "get the latest blackbox" | `blackbox update` |

## Teaching it: "teach me about Black Box", "give me a tutorial"

When someone asks to be taught Black Box, for a tour or a tutorial, run `blackbox tutorial --open` first: it
plays a two-minute animated, narrated tutorial in their browser (a scene for each thing Black Box does, beside a
panel of workflows, every command, and what's new). Tell them it's open and to press Start (browsers play sound
only after a click), then offer to go through any part with them or try one on their own footage. For what's new
in words, `blackbox tutorial new`; when this skill tells you it was updated, tell them in a line what's new.

When they'd rather be taught here, or ask how to do one thing, run `blackbox tutorial` and teach from it. Give the overview in a few lines (the lessons and one thing to ask in each), then go a lesson at
a time with `blackbox tutorial <id>` (`blackbox tutorial online`, say), or straight to the one they asked about.
Teach in their words, not the commands: each lesson's `ask` is what they can say to you, and `runs` is what you do
for them. Offer to try each lesson on their own footage (a real search, a real cut) before moving on. People can
also read the same lessons in docs/EDITORS.md.

## Where things are: everything you need is on this Mac

- **Dropbox `media_meta`** (`blackbox` alone prints where): transcripts, proxies (small copies of every clip;
  `line` and `search --fields all` print each clip's `proxy`), frame embeddings (picture search), and the names
  and voice and face prints (people), and the shot logs. It's read-only: never write there.
- **This Mac's index and frames:** `~/Library/Caches/black-box-cli/` (a cache: `blackbox index` rebuilds it).
  **Edits:** `~/Black Box/edits/<id>/`, each with `edit.mp4` (the edit rendered from the proxies), `cut.edl` and
  `cut.json` (every clip's source file and timecode), kept for good. Earlier ones in
  `~/Library/Application Support/black-box-cli/cuts/` are still listed. **Selects reviews:**
  `~/Black Box/reviews/<id>/` (review.html, review.json, thumbnails; state.jsonl, what the editor picked).
- **The originals are on the NAS** (`source_path`, `/Volumes/…`, `/VideoSource/…`). Never open, read, probe
  (ffprobe, mediainfo), copy, play or transcode them, and never browse the NAS: it's slow, shared and holds the
  only copies. Work from proxies, transcripts and `blackbox frame`. If a clip has no proxy, say so; don't fall
  back to the original.

## Start

`blackbox` alone shows what's indexed (months, hours, files), where `media_meta` is, the latest cuts, when the
index was last built (`indexed`) and its `health`. You don't need to run `blackbox index`: every command brings the
index up to date first when Dropbox has brought new files into `media_meta` (a few seconds; the first build on a
Mac takes a minute or two). Black Box may also keep it up to date itself.

Black Box keeps `blackbox` and this skill up to date by itself, minutes after a release. `blackbox update` asks it
to check now. A release isn't installed when it's `unsigned` (tell the owner) or when it needs a newer Black Box (`needs_black_box` says which). When you're told this skill was updated, re-read it and tell the user what's new (`blackbox tutorial new`).

`blackbox status` says more: each source (transcripts, frames, people, prints, sync, shots), what it holds, and
how many files are `pending` (new, not read yet) or `offline`. If `health` is:

- `incomplete`: some files in `media_meta` are online-only in Dropbox, so searches miss them. Tell the user to
  right-click `media_meta` in Finder → Make Available Offline.
- `broken`: run `blackbox index --rebuild`.
- `not built` or `stale`: run `blackbox index` (or just search: it builds first).

## Find what was said

1. `blackbox search marble quarry cava --month 2026-09` — use English and Italian words and synonyms (any word
   matches); narrow with `--all` (every word), `--day 2026-09-18`, `--path fx3`, `--video`.
2. `blackbox line k7f3q2ab.41` or `blackbox transcript C5274.MP4` to read around a hit and check the thought is
   complete.
3. `blackbox footage --month 2026-09` lists the files in scope (day, length, lines, and the camera, resolution
   and frame rate when Black Box has read them).

Line ids look like `k7f3q2ab.41`: the clip, then the line. The clip part alone (`k7f3q2ab`) stands for its file
wherever a file is taken (`transcript`, `frame`, a cut list line): an editor right-clicks a clip in a review page to
copy it ("k7f3q2ab", "k7f3q2ab at 1:23.4" or "k7f3q2ab 1:20.0-1:31.5", in to out) and pastes it to you with a
question. They're the same on every Mac and after every
`index`, so they can be kept. Speaker labels (S1, S2) are per file, not people: for people, see below.

One moment is often heard on several devices: a camera and the recorders on people's collars. Once Black Box
has synced them, `search` shows such a moment once, with the same line on the other devices in `also`
(`--every-device` lists each; `--fields id,text,also,check` always works). A `check` means the devices' transcripts name different speakers: look before
putting the words in someone's mouth.

## Find by camera, frame rate and resolution

Black Box reads every original's own metadata on the mini (never its folder name): the camera or recorder that
made it, the rate it was shot at, slow motion, resolution, codec and bitrate. `footage`, `search`, `look`, `like`
and `shots` all narrow by it, with each other and with `--month`, `--day` and `--path`:

- `--device fx3`: the camera or recorder by name, model or make (`fx3`, `a7s`, `osmo`, `iphone`, `sony`,
  `dji mic`, `zoom`; repeatable: either).
- `--slow-motion`: shot faster than it plays: S&Q or a phone's slo-mo (plays slow as it is), or a high-frame-rate
  file at 90 fps and up (the editor conforms it).
- `--fps 120`: the rate it was shot at (120 finds 119.88, 24 finds 23.976; `50+` at least 50).
- `--res 4k`: 8k, 6k, 5k, 4k, 2.7k, 1080p, 720p, sd (`4k+` at least 4K; repeatable).
- `--codec hevc` (h264, hevc, prores, or a profile like 422), `--bitrate 100+` (Mb/s).

"Slow motion from the FX3": `blackbox footage --device fx3 --slow-motion` lists the files with `fps` and
`slow_motion` (`5x`: plays 5 times slower as it is; `conform`: a high-frame-rate file to conform); `--fields
file,day,device,res,fps,slow_motion,codec,bitrate,lens,gamma` shows more. Then `look <what> --device fx3
--slow-motion` or `shots <what> --slow-motion` finds the moment. Slow motion records no sound, so `footage` lists
files nothing is said in too (`lines` 0), and a cut list takes them by file name (`C5280.MP4 0:04-0:09`),
with their own start timecode once Black Box has read it (otherwise a warning says `cut.edl` counts from 0:00,
and `online` places that cut by frame). `search` only
finds what was said, so a filter there narrows to files with speech.

`error.code no-probe` means this Mac's index has no camera facts yet: run `blackbox index`. If `index` counts many
`probe` files `skipped`, Dropbox keeps them online-only: in Finder, make `media_meta/probe` available offline.

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
  line id (the picture where it starts), a shot id or a file with `--at 1:23.5`. A recorder has no picture: for
  its lines `frame` shows a camera heard with it (`picture` says which), or says `audio-only` when none was.
- `--open` writes a page of the frames and opens it in the user's browser (`--html` writes it only and prints
  a quoted `open "…"` line).

Frame ids look like `k7f3q2ab.f12`: the clip, then the frame.

**The original of a copy:** given a screenshot, a thumbnail, a crop or a picture someone sent,
`blackbox source ~/Desktop/screenshot.png` finds the photo or the clip and moment it came from (Black Box 0.43.0 or
later, on this Mac; about 7 s a picture, several at once). `matches` are confirmed copies, best first, each with its
frame `id` (for `frame`, `like` and `still new`) and the original's path, shown, never opened; `similar` are
look-alikes it couldn't confirm. It finds copies, not ideas: for pictures like a reference or a repaint, use
`look` with words, or `like` from the closest `similar` frame. Never give it a picture on the NAS.

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

When the user asks for a cut, an edit, a rough cut, a string-out, or to show, play or bring up footage, build a cut
and **open it yourself with `--play`** unless they say otherwise. (Selects, sound bites or options to choose from
are a selects review instead: below.) Don't ask first, and don't make them
ask you to open it. One moment is a one-clip cut.

1. Write a cut list, one clip per line in play order (`#` starts a comment, ` # note` after a clip is its note):

   ```
   title: Marble
   k7f3q2ab.41..k7f3q2ab.43  # a run of lines from one file (ids from search)
   C5274.MP4 0:26.5-0:30  # a file (name, part of its path, or clip id) from in to out
   m2xq7rja.7
   sqlknzuz.s2  # a shot's best moment (ids from shots); `sqlknzuz.s2 whole` for all of it
   ```
2. `blackbox edl new - --dry-run` (cut list on stdin) to check it; every problem is listed and nothing is
   written.
3. `blackbox edl new - --play` saves it as an edit, renders it (`edit.mp4` in its folder) and opens it. Tell the user what's in it in a
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

## Selects review: clips to choose from, then into Resolve

When the user asks for selects ("build me selects for …", "a selects review of b-roll and sound bites of …",
"pull sound bites of …", "show me options for …"), you do the finding; the CLI does the rest in seconds.

1. Find candidates with `search` (what was said, with `--person` for someone), `shots` (b-roll by what happens,
   `--framing`, `--camera`, `--clean`) and `look` (what's in the picture). Read around sound bites with `line` so
   each is a complete thought.
2. Write a cut list with a `group:` line before each section and a note on each clip saying why it's there. Sound
   bites as runs of lines, so in and out follow the words; b-roll as shots' best moments or file ranges. Give them
   more than they need (10 to 30), the best first in each group:

   ```
   title: Kiln firing selects
   group: Sound bites
   k7f3q2ab.41..k7f3q2ab.43  # why she fires at night
   m2xq7rja.7  # "the colour comes from the ash"
   group: B-roll
   k7f3q2ab.s3  # flames through the spy hole, the shot's best moment
   C5274.MP4 0:26.5-0:30  # wide, the kiln at dusk
   ```
3. `blackbox review new - --dry-run` checks it (every problem listed, nothing written).
4. `blackbox review new - --open`: it makes the review in `~/Black Box/reviews/<id>/` (clips without a proxy are
   left out, under `warnings`), opens it in their browser and returns at once. Tell them in a line or two what's
   in it, by group.

One review server on this Mac (127.0.0.1, port 7387) serves every review, each at its own address, so an editor
can have several open in tabs and move between them. `review open` and `--open` start it when it isn't running
(on its own, apart from your shell) and otherwise leave it be. **Never stop it, and never stop an earlier
`review open`**: older reviews in other tabs need it. It stops itself after a day with no page open; a page whose
server stopped says so and reconnects once any review is opened again. `blackbox review serve` runs the server in
the foreground, only to see why it won't start (`error.code review-server`).

What they can do on the page, if they ask: move the mouse across a clip to scrub through it, in to out; click it to open it with sound and
its file's whole transcript (a word jumps there). An open clip takes Resolve's keys, so editors don't change habits:
J K L shuttle (each press faster, backward with its sound; K held with J or L steps a frame or plays slowly),
← → a frame (Shift: a second), ↑ ↓ the previous or next clip, `i` `o` mark in and out, Shift+I/O go to them, ⌥I ⌥O
⌥X clear them. `s` selects the clip under the mouse or the open one; the selected gather at the top, as many as fit, in
the order picked, which is their order on the timeline; View all shows every one (drag one to move it). ⌘↵ or the Import into Resolve button sends them to `online` (below):
a new timeline from the camera originals, with the handles, Into (open project or new project) and Recorders
(synced or off) chosen in the bar at the bottom; a toast says how it went. `?` lists the
keys. A review has a language, English unless `--language it` (any code) says otherwise: a clip said in another
is marked on its picture ("Italian"), and the header counts them (`in_another_language` in the output too). A line
said in Italian shows its English ("Translated from Italian"), with the Italian word for word under it (what the cut
follows, lit word by word as it plays); English, Both and As spoken at the top (or `t`) switch between them.
Under an open clip, a strip shows the shots its camera took before and after it, in the order it shot them, with
the time between them, loading more as it's scrolled (Shift+↑ ↓ step through them; `s` over one selects it); space over one gives a closer look, scrubbed with sound and
playing on where the mouse rests, driven by J K L and the arrows while it's open (`o` or ↵ opens the shot there); a
shot opens like any clip, where it was scrubbed to, and `s` adds it, trimmed with `i`
and `o`, to an "Added by you" group. What they select, trim and add is kept in the review's folder
(`state.jsonl`, the last line is the latest) and comes back when the page is reloaded; read it to see what an
editor picked. The open clip's timeline is its sound's waveform with each line said marked above it (a click on one
goes to it), its in and out shaded; a span dragged on it becomes the in and out with ↵; captions show what's said in the review's language (`c`).

`blackbox review list` lists them; `blackbox review open <id>` opens one again, with Import working. review.html
opened from Finder plays and reads but can't import. Reviews are kept until someone deletes them: to free space,
delete review folders in `~/Black Box/reviews/` in Finder (blackbox never does).

## Taking a cut to DaVinci Resolve (only when asked)

Only when the user wants the cut in Resolve:

- **Online it: a timeline on this Mac, from the source files, ready to grade.** When the user says "online
  this", "online that edit", "go to source" or "put it in Resolve": `blackbox online <edit id>`. "This" or "that
  edit" is the last edit made or played in the conversation; with none, run `blackbox edl list` and confirm which
  one (by title) before touching Resolve. `--dry-run` first shows each clip with its pre- and post-roll. Each clip goes back from its proxy to the camera original, gets 2 s of pre- and
  post-roll (`--pre`, `--post`, or ` pre 1 post 3` at the end of a clip's line in the cut list; clamped to the
  file), and Resolve on this Mac uses the originals already in the project wherever they are (the editor's own
  folders first; `originals_in_project` counts them), imports only the rest into "Black Box/<id>" with their
  proxies linked, and imports the edit's EDL as a new timeline: in the project open in Resolve (an untitled one too), or a new project when Resolve wasn't running (`--into current|new-project`;
  `--db local` for this Mac's database). The recorders heard with each clip come with it, synced by the sync map,
  each on its own tracks from A2 (`--no-audio` to leave them out): the output's `recorders` column and
  `audio_placed` say what came, and a match too weak to place (confidence under 0.5) is named in `warnings`, not
  placed. What's said goes on the timeline's subtitle track (every word in each clip's range, handles included;
  `subtitles_placed` counts them; `--no-subtitles` to leave them out), so the editor can copy lines straight from
  it. It takes seconds (about 15 s more when it has to start Resolve). A cut
  list works too (`blackbox online -`): it's saved as an edit first. `resolve` says where it went; `missing` lists
  originals Resolve couldn't find (the NAS not mounted: `--originals <the folder holding media/>`). If it says
  `resolve-scripting-off`, the user sets Resolve → Preferences → System → General → External scripting → Local.
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

Use `online` for Resolve on this Mac; don't script Resolve yourself, and never delete or archive a project or
timeline: a person does that.

## Not here

Card offloads, the catalog, proxies and Resolve day timelines belong to the Black Box app, not this command.
