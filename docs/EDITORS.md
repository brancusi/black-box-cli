# Black Box for editors

<!-- Written by `bb editors` from src/black_box_cli/tutorial.clj; edit the lessons there. -->

Black Box knows every clip the studio has filmed: what was said, what's in the picture, each shot, and who is heard and seen. Ask your agent in your own words; it runs `blackbox` for you, shows you the footage and builds edits you can watch, then takes them to Resolve from the camera originals.

You don't need to learn the commands: say what you want, as in the examples below. To be taught by your agent, ask it: "teach me about Black Box" (it plays an animated tutorial in your browser) or "give me the Black Box tutorial". The commands are shown so you can see what it does for you.

**What's new in 0.1.21:** A calmer strip ([more](#whats-new))

1. [Start here: what's in the archive](#start)
2. [Find what was said](#said)
3. [Who's speaking, who's on screen](#people)
4. [Find by what's in the picture](#picture)
5. [B-roll: shots, camera moves and best moments](#shots)
6. [Rough cuts you can watch at once](#cut)
7. [A selects review: choose, then import](#review)
8. [Online an edit in Resolve](#online)
9. [A full project, built by the studio's Mac mini](#project)
10. [Where did this picture come from?](#source)
11. [Stills for print and the web](#stills)

<a id="start"></a>

## Start here: what's in the archive

Everything Black Box reads is already on your Mac, in Dropbox's media_meta: transcripts (English and Italian), small proxies of every clip, picture search, shot logs and names. It never touches the NAS, so it's fast and safe to use while others work.

**Ask:**

- *What's in the archive?*
- *What did we film in October?*
- *Is everything up to date?*

**Behind it:**

- `blackbox`: what's indexed (months, hours, files) and the latest edits
- `blackbox footage --month 2026-10`: the files of a month: day, length, lines
- `blackbox status`: the index's health, and anything Dropbox hasn't brought down yet
- `blackbox index`: read what Dropbox has brought down (seconds; your agent does it when it's needed)
- `blackbox tutorial --open`: this tutorial, animated, in your browser
- `blackbox setup agent`: once on a new Mac: teaches your agent Black Box
- `blackbox update`: the newest blackbox now (Black Box also checks every few minutes)

**Tips:**

- New footage shows up on its own: the studio's Mac mini transcribes and logs it, and Dropbox brings it here.
- If it says `incomplete`, right-click media_meta in Finder → Make Available Offline.
- On a new Mac: install Black Box, then run `blackbox setup agent` once so your agent knows how to use it.
- Ask for this tutorial any time ("give me the Black Box tutorial"), or one lesson ("teach me how to online an edit").

<a id="said"></a>

## Find what was said

Search every transcript for words, in English and Italian, then read around a hit to check the thought is complete.

**Ask:**

- *Find where someone explains why they use this marble*
- *Every time someone says "cava" in September*
- *Read me the whole conversation around that line*

**Behind it:**

- `blackbox search marble quarry cava --month 2026-09`: lines with any of the words, best first (--all: every word)
- `blackbox line k7f3q2ab.41`: one line with the lines around it
- `blackbox transcript C5274.MP4`: a whole file's lines

**Tips:**

- Line ids like k7f3q2ab.41 never change: keep them in notes or a cut list.
- A moment heard on a camera and a lav mic shows once, with the other devices under `also`.
- Speaker labels (S1, S2) are per file; for real names, see the people lesson.

<a id="people"></a>

## Who's speaking, who's on screen

The studio names voices and faces on Black Box's People page, so you can search by person.

**Ask:**

- *Make a cut of Mara talking about the kiln*
- *Where is Mara seen in October?*
- *Who is speaking in this line?*

**Behind it:**

- `blackbox people`: everyone named, and how many clips they're heard and seen in
- `blackbox search kiln fire --person Mara`: what one person said
- `blackbox footage --person Mara --month 2026-10`: the clips they're heard or seen in
- `blackbox whose k7f3q2ab.41`: who a voice may be, with scores

**Tips:**

- A score is a guess: your agent says "may be" unless the studio published the name.

<a id="picture"></a>

## Find by what's in the picture

Describe a shot in words, or point at a frame and find others like it, across every clip.

**Ask:**

- *Find shots of two people at a table outdoors*
- *More shots like this one, from other days*
- *Show me those frames side by side*

**Behind it:**

- `blackbox look two people at a table --month 2026-09 --per-clip 2`: frames that match the words
- `blackbox like k7f3q2ab.f12`: frames that look like this one
- `blackbox frame k7f3q2ab.f12`: that frame as a picture
- `blackbox look boat at sea --open`: a page of the frames in your browser

**Tips:**

- Picture search needs the Black Box app open; the first search on a Mac downloads its model once (0.9 GB).

<a id="shots"></a>

## B-roll: shots, camera moves and best moments

Every clip is logged shot by shot: framing, camera move, what happens, its best few seconds and any issues (shake, focus).

**Ask:**

- *Find close-ups of hands carving stone, nothing shaky*
- *Slow pans over the quarry for b-roll*
- *What's the best moment of C5274?*
- *Slow motion from the FX3, only 4K*

**Behind it:**

- `blackbox shots hands carving stone --framing close --clean`: close shots with no issues logged
- `blackbox shots --camera pan --month 2026-09`: every pan that month
- `blackbox shots --file C5274.MP4`: one clip's shots in order, with its summary
- `blackbox footage --device fx3 --slow-motion --res 4k`: FX3 slow motion in 4K, from the files' own metadata

**Tips:**

- The logs are a model's reading: look at the frame before trusting a description.
- --device, --slow-motion, --fps, --res, --codec and --bitrate narrow footage, look, shots and search by what shot it.
- In a cut, a shot plays its best moment (k7f3q2ab.s3) or all of it (k7f3q2ab.s3 whole).

<a id="cut"></a>

## Rough cuts you can watch at once

Ask for a cut and it's built from the proxies and opens straight away. Each edit is kept in ~/Black Box/edits with its video (edit.mp4), its EDL and the list of its source clips, so you can play it again or take it to Resolve any time.

**Ask:**

- *Make me a 30-second b-roll cut of the quarry, a second per shot*
- *String out every answer about the kiln*
- *Play yesterday's quarry cut again*
- *Swap the third shot for something wider*

**Behind it:**

- `blackbox edl new - --play`: a cut from a cut list, played at once
- `blackbox edl list`: every edit, newest first
- `blackbox edl show quarry --play`: an edit's clips, played again

**Tips:**

- Nothing is overwritten: a change makes a new edit, so you can always go back.
- It plays proxies (960×540) with what's said as subtitles, in your Mac's default video app (Finder: Get Info → Open with → Change All).

<a id="review"></a>

## A selects review: choose, then import

Ask for selects and you get a page in your browser: every clip playing its proxy with its words under it, in sections such as B-roll and Sound bites. Click a clip to see its whole transcript (a word jumps there) and trim its in and out. Tick the ones you want and press Import into Resolve: they arrive as a new timeline from the camera originals, with the recorders synced.

**Ask:**

- *Build me a selects review for b-roll and sound bites of the kiln firing*
- *Pull selects of Mara talking about the marble, with some quarry b-roll*
- *Open yesterday's selects review again*

**Behind it:**

- `blackbox review new - --dry-run`: check the selects (a cut list with group: lines)
- `blackbox review new - --open`: make the review and open it in the browser
- `blackbox review list`: every review, newest first
- `blackbox review open kiln`: open one again, with Import working
- `blackbox review serve`: the review server itself, in the foreground: only to see why it won't start

**Tips:**

- Keep as many reviews open in tabs as you like: one server on your Mac serves them all, and opening another never closes one.
- Reviews are kept in ~/Black Box/reviews until you delete them there; review.html opens from Finder to watch and read, but importing needs it opened by your agent.
- Move the mouse across a clip to scrub through it. Keys: ? lists them all. s selects the clip you're on (they gather at the top, in timeline order), i imports them; in an open clip i and o set in and out as in Resolve, shift+I imports, and ← and → move between clips.
- A line said in Italian shows its English, with the Italian word for word under it, lit as it plays: that's where you cut. English, Both and As spoken at the top (or t) switch between them.

<a id="online"></a>

## Online an edit in Resolve

Say "online this" and the edit goes back to the camera originals, 2 s of handles each side, as a new timeline in Resolve on your Mac, ready to grade. The proxies are linked, so it plays smoothly, and the recorders heard with each clip (lavs, the field recorder) come synced under it, each on its own tracks.

**Ask:**

- *Online this edit*
- *Online it with 1 second of handles into a new project*
- *Online yesterday's quarry cut into the project I have open*

**Behind it:**

- `blackbox online quarry --dry-run`: each clip with its handles; Resolve isn't touched
- `blackbox online quarry`: the timeline: in the open project, or a new project if Resolve was closed
- `blackbox online quarry --pre 1 --post 1 --into new-project`: your own handles, in a new project
- `blackbox online quarry --db local`: in this Mac's own Resolve database
- `blackbox online quarry --no-audio`: without the synced recorders

**Tips:**

- Once, in Resolve: Preferences → System → General → External scripting using → Local.
- The NAS must be mounted (Finder → Go → Connect to Server); clips it can't find are listed as missing.
- Originals already in your project, in any bin, are used where they are; only the rest are imported, into a Black Box bin.
- What's said comes on the subtitle track, handles included: copy lines straight from it.
- It only adds: new timelines and projects, and a name already taken gets a number. Nothing in Resolve is changed or deleted.

<a id="project"></a>

## A full project, built by the studio's Mac mini

For an edit you'll cut properly: the mini builds a Resolve project on the shared database with the originals, the lav audio synced and the proxies linked. It takes minutes.

**Ask:**

- *Build this edit as a project on the shared database*
- *Is the quarry project ready yet?*

**Behind it:**

- `blackbox edl build quarry`: send it to the mini
- `blackbox edl show quarry`: how the build is going, and what it found missing

**Tips:**

- Black Box must be open on your Mac.
- Each edit is built once: for changes, make a new edit and build that.

<a id="source"></a>

## Where did this picture come from?

Give it a screenshot, a thumbnail or a crop someone sent, and it finds the clip and moment, or the photo, it came from.

**Ask:**

- *Which clip is this screenshot from?*
- *Find the originals of these three thumbnails*

**Behind it:**

- `blackbox source ~/Desktop/screenshot.png`: the original and the moment, confirmed

**Tips:**

- It finds copies, not look-alikes: for "something like this", ask for a picture search.
- Black Box must be open on your Mac.

<a id="stills"></a>

## Stills for print and the web

Full-resolution, graded stills rendered from the camera originals by the mini, for a website, press or print.

**Ask:**

- *Give me a full-res still of this frame for the website*
- *Three stills from the kiln scene, filmic look, with TIFFs, in my Downloads*

**Behind it:**

- `blackbox still new k7f3q2ab.f12 --look filmic --tiff --out ~/Downloads`: ask the mini for the still
- `blackbox still show 20261124T080000Z-kiln --wait`: waits until the stills are back, with their paths
- `blackbox still list`: every request and where it stands

**Tips:**

- Looks: neutral (the house look), filmic, rec709, or log for a colourist.
- It takes minutes; your agent tells you when they're ready.
- Too warm or too cool? Ask it to balance on something white in the shot.

<a id="whats-new"></a>

## What's new

### 0.1.21 (2026-11-25)

- **A calmer strip:** The shots around a select now sit evenly spaced with the time between them on the line; Earlier and Later load more, and they slide in. `blackbox review open kiln`

### 0.1.20 (2026-11-25)

- **The shots around a select:** Open a clip in a selects review: the strip under it shows what its camera shot before and after, with the time between. Hover to scrub, click to open, s to add one with your own in and out. `blackbox review open kiln`
- **Reviews remember:** What you select, trim and add on a review page comes back when you reload it, kept in the review's folder. `blackbox review open kiln`

### 0.1.19 (2026-11-25)

- **Scrub every card:** Move the mouse across any card in a selects review to scrub its picture, frame by frame, with the line being said lit below it. `blackbox review open kiln`

### 0.1.18 (2026-11-25)

- **Italian in English, word for word:** In a selects review, a clip said in Italian is marked on its picture, and each line shows its English with the Italian under it, lit word by word as it plays: read in English, cut on the Italian. English, Both or As spoken at the top, or t. `blackbox review open kiln`

### 0.1.17 (2026-11-24)

- **Into the project you have open:** Import into Resolve with Open project goes into the project open in Resolve, even an untitled one; a new project only when Resolve wasn't running. `blackbox review open kiln`

### 0.1.16 (2026-11-24)

- **Many reviews, all open:** Keep as many selects reviews open in tabs as you like and move between them: opening a new one never closes an older one. `blackbox review open kiln`

### 0.1.15 (2026-11-24)

- **Safer imports:** The subtitles go straight onto the timeline and leave nothing in the bin to drag, so pulling a bin's clips into your edit is safe. `blackbox online quarry`

### 0.1.14 (2026-11-24)

- **Slow motion on its own timecode:** A clip nothing is said in now keeps its camera's own timecode in a cut, so it goes to Resolve like any other. `blackbox edl new - --play`

### 0.1.13 (2026-11-24)

- **Slow motion from the FX3:** Find footage by the camera that shot it, its frame rate, slow motion and resolution, read from each file's own metadata. Silent slow-motion clips can go straight into a cut. `blackbox footage --device fx3 --slow-motion`

### 0.1.12 (2026-11-24)

- **Subtitles in Resolve:** Importing selects or an edit puts what's said on the timeline's subtitle track, handles included: copy lines straight from it. `blackbox online quarry`

### 0.1.11 (2026-11-24)

- **A steady review:** Selecting a clip moves nothing on the page, and the open clip's video keeps its shape at any window size. `blackbox review new - --open`
- **Updates in minutes:** Black Box brings each new blackbox and its skill to your Mac minutes after the owner approves it; blackbox update checks now. `blackbox update`

### 0.1.10 (2026-11-24)

- **Scrub through selects:** In a selects review, move the mouse across a clip to run through it from in to out. `blackbox review new - --open`
- **Your bins first:** Importing into your open project links to the originals already in it, in your own bins; only what's missing is imported, into a Black Box bin. `blackbox online quarry`

### 0.1.9 (2026-11-24)

- **Ask in your own words:** Say "teach me about Black Box", "build me selects of …" or "cut me …" and your agent knows which: a tutorial, a selects review page, or a cut that plays. `blackbox tutorial --open`

### 0.1.8 (2026-11-24)

- **Selects review:** Ask for selects and get a page: each clip plays on hover with its words under it; open one for its whole transcript. s selects, i imports the selected into Resolve. `blackbox review new - --open`
- **Recorders come synced:** Onlining an edit lays the lavs and field recorder under each clip, frame-exact, each on its own tracks. `blackbox online quarry`
- **This tutorial, animated:** Say "teach me about Black Box" and it plays in your browser, with every command and what's new. `blackbox tutorial --open`

### 0.1.7 (2026-11-24)

- **Your agent keeps up:** When Black Box updates, a running agent session is told to re-read what it can do. `blackbox setup agent`

### 0.1.6 (2026-11-23)

- **Online an edit:** An edit back to the camera originals as a new Resolve timeline on your Mac, with handles. `blackbox online quarry`
- **Edits kept where you find them:** Each edit in ~/Black Box/edits, with its video, its EDL and its source clips. `blackbox edl list`
- **The tutorial:** Ask your agent for the tutorial, or read docs/EDITORS.md. `blackbox tutorial`
