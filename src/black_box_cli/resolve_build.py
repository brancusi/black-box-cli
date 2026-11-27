"""Build a cut as a new timeline in DaVinci Resolve on this Mac: black-box-cli's `online` runs this with
Resolve's own Python and a plan (JSON) it wrote. It finds the originals already in the project by their paths, imports
the rest into a Media Pool folder of their own, links their proxies, and places each clip on a new timeline at its own
frame. It never deletes, renames or changes anything that was there before; the one thing it deletes is a timeline it
made itself a moment ago (the probe that finds the originals, or a timeline that came out wrong). Each step goes into
<plan>-steps.log beside the plan as it starts, so a crash says where.

    ResolvePython resolve_build.py plan.json

Prints one JSON object on stdout: the result, or {"error": {"code", "message"}}.
"""

import importlib.machinery
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import time
from xml.sax.saxutils import escape

LIB = "/Applications/DaVinci Resolve/DaVinci Resolve.app/Contents/Libraries/Fusion/fusionscript.so"
APP = "DaVinci Resolve"


class Fail(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


STEP = {"now": "starting", "log": None}


def import_subtitle(media_pool, folder, ours, srt):
    """The .srt imported into our Subtitles folder: its media pool item, or None."""
    media_pool.SetCurrentFolder(subfolder(media_pool, ours, "Subtitles"))
    found = media_pool.ImportMedia([srt]) or media_pool.ImportMedia([{"FilePath": srt}]) or []
    media_pool.SetCurrentFolder(folder)
    return next((i for i in found if i.GetClipProperty("Type") == "Subtitle"), None)


def step(what):
    """Notes what the script is about to ask Resolve, in the steps log beside the plan (a new file each run): if
    Resolve crashes, the last line says on what."""
    STEP["now"] = what
    if STEP["log"]:
        with open(STEP["log"], "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%H:%M:%S')} {what}\n")


def script_module():
    lib = os.environ.get("RESOLVE_SCRIPT_LIB", LIB)
    loader = importlib.machinery.ExtensionFileLoader("fusionscript", lib)
    spec = importlib.util.spec_from_loader("fusionscript", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def running():
    return subprocess.run(["pgrep", "-x", "Resolve"], capture_output=True).returncode == 0


def gone():
    """Resolve has quit (a crash takes a moment to end the process)."""
    for _ in range(5):
        if not running():
            return True
        time.sleep(1)
    return False


def connect(module, wait_s):
    t0 = time.time()
    while True:
        resolve = module.scriptapp("Resolve")
        if resolve and resolve.GetProjectManager():
            return resolve
        if time.time() - t0 > wait_s:
            return None
        time.sleep(1)


def unique(name, taken):
    if name not in taken:
        return name
    n = 2
    while f"{name} {n}" in taken:
        n += 1
    return f"{name} {n}"


def subfolder(media_pool, parent, name):
    """The bin `name` in `parent`, made when it isn't there. In a project on a shared database (a network project,
    collaboration on), Resolve can refuse to make one, as while another editor has the bin it goes in open: said
    plainly, never left to fail on later."""
    for f in parent.GetSubFolderList() or []:
        if f.GetName() == name:
            return f
    made = media_pool.AddSubFolder(parent, name)
    if made is None:
        raise Fail("resolve-bin", f"Resolve wouldn't make the bin {name} in {parent.GetName()}. In a shared project "
                                  f"another editor may have {parent.GetName()} open: ask them to close it, or make "
                                  f"the bin {name} there yourself, then import again")
    return made


def walk(folder):
    yield folder
    for f in folder.GetSubFolderList() or []:
        yield from walk(f)


def file_path(item):
    return (item.GetClipProperty("File Path") or "") if item else ""


def media_key(path):
    """The path from its last /media/ on, lower case: the same original on the NAS whichever way it was mounted
    (never the file name alone: camera names repeat across cards)."""
    p = (path or "").replace("\\", "/")
    i = p.lower().rfind("/media/")
    return p[i:].lower() if i >= 0 else None


def by_media_key(paths):
    """The originals by their media/ path: {key: [paths]}."""
    keys = {}
    for p in paths:
        k = media_key(p)
        if k:
            keys.setdefault(k, []).append(p)
    return keys


def original_of(fp, paths, keys):
    """Which original a clip's file path `fp` is: itself, or the one original with its media/ path (the NAS mounted
    another way); never by a file name alone (camera names repeat across cards). None when it's none of them."""
    if fp in paths:
        return fp
    same = keys.get(media_key(fp)) or []
    return same[0] if len(same) == 1 else None


def in_our_bin(ours, paths):
    """The originals an earlier cut brought into the Black Box bin: {path: item}. That bin only, never the whole
    project; a clip's path is asked only when its name is an original's."""
    names, keys, found = {os.path.basename(p).lower() for p in paths}, by_media_key(paths), {}
    for f in walk(ours):
        for item in f.GetClipList() or []:
            if (item.GetName() or "").lower() in names:
                p = original_of(file_path(item), paths, keys)
                if p and p not in found:
                    found[p] = item
    return found


def xml_rate(fps):
    f = float(fps or 25)
    base = max(1, round(f))
    return f"<rate><timebase>{base}</timebase><ntsc>{'TRUE' if abs(f - base) > 0.001 else 'FALSE'}</ntsc></rate>"


def probe_xml(originals):
    """An FCP 7 XML with one frame of each original ({path, tc, fps, video}), by its path and its start timecode.
    Resolve links such a clip to the Media Pool clip at that path when one is in the bins it's given, whatever other
    clip has its name (an EDL names only the clip, so Resolve took the first with the name: a camera's name repeated
    on another card); with neither, it may take one by name and timecode, which the paths then turn down. Tried on
    21.2: without the sequence's <format>, or the file's <timecode>, Resolve links nothing."""
    def item(i, o):
        r, kind = xml_rate(o.get("fps")), ("video" if o["video"] else "audio")
        name, tc = escape(os.path.basename(o["path"])), o.get("tc") or "00:00:00:00"
        return (f'<clipitem id="probe {kind} {i}"><name>{name}</name><duration>2</duration>{r}<start>{i}</start>'
                f'<end>{i + 1}</end><enabled>TRUE</enabled><in>0</in><out>1</out><file id="file {kind} {i}">'
                f'<duration>2</duration>{r}<name>{name}</name>'
                f'<pathurl>{escape(pathlib.Path(o["path"]).as_uri())}</pathurl><timecode><string>{escape(tc)}</string>'
                f'<displayformat>{"DF" if ";" in tc else "NDF"}</displayformat>{r}</timecode>'
                f'<media><{kind}><duration>2</duration></{kind}></media></file></clipitem>')
    video = "".join(item(i, o) for i, o in enumerate(o for o in originals if o["video"]))
    audio = "".join(item(i, o) for i, o in enumerate(o for o in originals if not o["video"]))
    r = xml_rate(originals[0].get("fps"))
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE xmeml>\n<xmeml version="5"><sequence>'
            f'<name>probe</name><duration>{len(originals)}</duration>{r}<media><video><track>{video}</track>'
            f'<format><samplecharacteristics><width>1920</width><height>1080</height>{r}</samplecharacteristics>'
            f'</format></video><audio><track>{audio}</track></audio></media></sequence></xmeml>\n')


def found_by_resolve(project, media_pool, ours, xml, originals, name):
    """The originals Resolve finds anywhere in the project: {path: item}. A project can hold tens of thousands of
    clips, on a network database where each question about one is a trip to the server, so its clips are never walked
    from here: Resolve is handed `originals` as an XML of paths (probe_xml, written new at `xml`) with every bin, and
    links each to its clip in one call. Each clip it chose is checked by its path all the same. The timeline it makes
    for this is this run's own, seconds old: the editor's timeline is put back on screen at once, and the probe
    deleted."""
    with open(xml, "x", encoding="utf-8") as f:
        f.write(probe_xml(originals))
    bins = list(walk(media_pool.GetRootFolder()))  # bins, not clips: an XML's clips are looked for in these only
    shown = project.GetCurrentTimeline()
    media_pool.SetCurrentFolder(ours)
    timeline = media_pool.ImportTimelineFromFile(xml, {"timelineName": name, "importSourceClips": False,
                                                       "sourceClipsFolders": bins})
    if timeline is None:
        return {}
    if shown is not None:
        project.SetCurrentTimeline(shown)
    paths = [o["path"] for o in originals]
    keys, found = by_media_key(paths), {}
    try:
        for kind in ("video", "audio"):
            for n in range(1, (timeline.GetTrackCount(kind) or 0) + 1):
                for t in timeline.GetItemListInTrack(kind, n) or []:
                    item = t.GetMediaPoolItem()
                    p = original_of(file_path(item), paths, keys) if item else None
                    if p and p not in found:
                        found[p] = item
    finally:
        media_pool.DeleteTimelines([timeline])
    return found


def blank(project):
    """An Untitled Project nobody has put anything in: what Resolve opens with when it starts."""
    if project is None:
        return True
    if project.GetName() != "Untitled Project":
        return False
    root = project.GetMediaPool().GetRootFolder()
    return project.GetTimelineCount() == 0 and not (root.GetClipList() or root.GetSubFolderList())


def timeline_names(project):
    return {project.GetTimelineByIndex(i).GetName() for i in range(1, project.GetTimelineCount() + 1)}


def channels(item):
    """How many audio channels a Media Pool clip has (a poly WAV from a recorder can have four)."""
    try:
        return max(1, int(str(item.GetClipProperty("Audio Ch") or "1").split()[0]))
    except ValueError:
        return 1


def place_audio(media_pool, timeline, audio, have, at="record_frame"):
    """The recorders synced to the clips, one recorder (the plan's `track`, 1 up) to a block of mono tracks from A2,
    as wide as its channels, at its clip's place on the timeline (`at`: record_frame where the EDL put the clips,
    clip_frame where they were placed by frame). Returns how many are on the timeline after."""
    if not audio:
        return 0
    width = {}
    for a in audio:
        width[a["track"]] = max(width.get(a["track"], 1), channels(have[a["source_path"]]))
    first, n = {}, 2
    for t in sorted(width):
        first[t], n = n, n + width[t]
    while timeline.GetTrackCount("audio") < n - 1:
        if not timeline.AddTrack("audio", "mono"):
            break
    start = timeline.GetStartFrame()
    media_pool.AppendToTimeline([{"mediaPoolItem": have[a["source_path"]], "startFrame": a["in_frame"],
                                  "endFrame": a["out_frame"], "recordFrame": start + a.get(at, a["record_frame"]),
                                  "trackIndex": first[a["track"]], "mediaType": 2} for a in audio])
    placed = 0
    for a in audio:
        items = timeline.GetItemListInTrack("audio", first[a["track"]]) or []
        if any(it.GetStart() - start == a.get(at, a["record_frame"]) and it.GetMediaPoolItem()
               and file_path(it.GetMediaPoolItem()) == a["source_path"] for it in items):
            placed += 1
    return placed


def main(plan_path):
    with open(plan_path, encoding="utf-8") as f:
        plan = json.load(f)
    log = plan_path[:-len("-plan.json")] + "-steps.log" if plan_path.endswith("-plan.json") else plan_path + ".log"
    with open(log, "x", encoding="utf-8"):
        STEP["log"] = log
    audio = plan.get("audio") or []
    paths = list(dict.fromkeys([c["source_path"] for c in plan["clips"]] + [a["source_path"] for a in audio]))
    originals = {}
    for o in plan["clips"] + audio:
        originals.setdefault(o["source_path"], {"path": o["source_path"], "tc": o.get("tc"), "fps": o.get("tc_fps"),
                                                "video": bool(o.get("video"))})
    if not any(os.path.exists(p) for p in paths):
        # Before Resolve is touched, so no project is made for nothing.
        raise Fail("no-originals", "none of the originals is on this Mac (is the NAS mounted?)")
    step("connecting to Resolve")
    module = script_module()
    launched = False
    resolve = connect(module, 3)
    if resolve is None:
        if running():
            raise Fail("resolve-scripting-off",
                       "Resolve is open but doesn't let scripts connect: set Preferences → System → General → "
                       "External scripting using to Local")
        subprocess.run(["open", "-a", APP], check=False)
        launched = True
        resolve = connect(module, plan.get("wait_s", 180))
        if resolve is None:
            raise Fail("resolve-not-ready", "Resolve didn't answer after it was started")
    pm = resolve.GetProjectManager()
    switched = False
    if plan.get("db"):
        dbs = pm.GetDatabaseList() or []
        want = plan["db"].lower()
        db = next((d for d in dbs if d.get("DbName", "").lower() == want
                   or (want == "local" and d.get("DbType") == "Disk")), None)
        if db is None:
            raise Fail("no-database", f"no Resolve database named {plan['db']}; there are: "
                       + ", ".join(d.get("DbName", "?") for d in dbs))
        if db != pm.GetCurrentDatabase():
            if not pm.SetCurrentDatabase(db):
                raise Fail("no-database", f"Resolve couldn't switch to the database {db.get('DbName')}")
            switched = True

    step("finding the open project")
    current = pm.GetCurrentProject()
    into = plan["into"]
    if into == "auto":
        # The project open in Resolve, whatever its name (an editor may be working in an untitled one); a new one
        # only when there's nothing to import into: Resolve was just started, the database was switched, or the
        # open project is the empty Untitled one Resolve starts with.
        into = "new-project" if (launched or switched or blank(current)) else "current"
    if into == "new-project":
        step("creating a project")
        taken = set(pm.GetProjectListInCurrentFolder() or [])
        name = unique(plan["project"], taken)
        project = pm.CreateProject(name)
        if project is None:
            raise Fail("resolve-failed", f"Resolve couldn't create the project {name}")
        project.SetSetting("timelineFrameRate", plan["fps"])
        created = True
    else:
        project = current
        if project is None:
            raise Fail("no-project", "Resolve has no project open")
        created = False

    step(f"opening the project's Media Pool ({project.GetName()})")
    media_pool = project.GetMediaPool()
    root = media_pool.GetRootFolder()
    # One Black Box bin at the top of the project, local or network: the timelines in it, each cut's originals in a
    # bin of their own inside it.
    ours = subfolder(media_pool, root, "Black Box")
    folder = subfolder(media_pool, ours, plan["bin"])
    media_pool.SetCurrentFolder(folder)

    # Originals already in this project, in any folder (the editor's own first, then an earlier cut's under "Black
    # Box"), are used where they are, never imported twice; only the rest come into this cut's Black Box folder. The
    # EDL links to them in their folders.
    # Originals already in this project are used where they are, never imported twice: the camera clips wherever
    # Resolve finds them (the editor's own bins too), the recorders from an earlier cut's Black Box bin; only the rest
    # come into this cut's bin.
    step("looking in the Black Box bin for originals an earlier cut brought in")
    have = in_our_bin(ours, paths)
    mine = {item.GetMediaId() for item in have.values()}
    theirs = set()
    rest = [originals[p] for p in paths if p not in have]
    if rest:
        step(f"asking Resolve to find {len(rest)} originals in the project by their paths")
        xml = (plan_path[:-len("-plan.json")] if plan_path.endswith("-plan.json") else plan_path) + "-probe.xml"
        for p, item in found_by_resolve(project, media_pool, ours, xml, rest,
                                        unique(plan["timeline"] + " (finding clips)", timeline_names(project))).items():
            have[p] = item
            if item.GetMediaId() not in mine:
                theirs.add(p)
    step(f"found {len(have)} of {len(paths)} originals in the project ({len(theirs)} in the editor's bins)")
    new = [p for p in paths if p not in have]
    # Resolve 21.1 answers None to the documented [{"FilePath": …}] form; a list of paths works.
    step(f"importing {len(new)} originals")
    imported = (media_pool.ImportMedia(new) or media_pool.ImportMedia([{"FilePath": p} for p in new])) if new else []
    for item in imported or []:
        have[file_path(item)] = item
    missing = [p for p in paths if p not in have]

    linked = 0
    step("linking proxies")
    if plan.get("proxies"):
        for c in plan["clips"]:
            item, proxy = have.get(c["source_path"]), c.get("proxy")
            # The editor's own clips are left as they are: a proxy is linked only to ours.
            if (item and proxy and c["source_path"] not in theirs and os.path.isfile(proxy)
                    and not item.GetClipProperty("Proxy Media Path")):
                if item.LinkProxyMedia(proxy):
                    linked += 1

    clips = [c for c in plan["clips"] if c["source_path"] in have]
    if not clips:
        raise Fail("no-originals", "none of the originals could be imported (is the NAS mounted on this Mac?)")
    name = unique(plan["timeline"], timeline_names(project))
    subtitles_placed, subtitles_note = 0, None
    spoken_note, spoken_bin = None, None
    spoken = plan.get("subtitles_spoken")
    srt = plan.get("subtitles")
    subtitle_item = None
    major = (resolve.GetVersion() or [0])[0]
    if srt and os.path.isfile(srt) and major < 21:
        subtitles_note = (f"Resolve {resolve.GetVersionString()}: subtitles are placed on 21 or later; "
                          "import the .srt beside the EDL by hand (File > Import > Subtitle)")
    elif srt and os.path.isfile(srt):
        # Into a folder of their own, never the cut's: an editor drags the cut's folder into a timeline, and Resolve
        # crashes placing a subtitle clip that way. Kept there, not deleted (a placed clip's item is never deleted).
        step("importing the subtitles")
        subtitle_item = import_subtitle(media_pool, folder, ours, srt)
        if subtitle_item is None:
            subtitles_note = "Resolve didn't import the subtitles"
    edl_note = None
    # Each clip goes down at its own frame, from the very clips found above by their paths: never by importing the
    # EDL, which Resolve links by name and timecode (a same-named clip from another card showed first, then was
    # replaced). Subtitles first, on the empty timeline: Resolve appends a subtitle clip at the timeline's end, keeping
    # each line's time from there (and crashes if given frames for one), so only an empty timeline puts them at its
    # start. A missing clip leaves its gap.
    method = "subtitles" if subtitle_item is not None else "frames"
    step(f"creating the timeline {name}")
    media_pool.SetCurrentFolder(ours)  # the timeline in the Black Box bin itself
    timeline = media_pool.CreateEmptyTimeline(name)
    media_pool.SetCurrentFolder(folder)
    if timeline is None:
        raise Fail("resolve-failed", f"Resolve couldn't create the timeline {name}")
    project.SetCurrentTimeline(timeline)
    if subtitle_item is not None:
        if timeline.GetTrackCount("subtitle") == 0:
            timeline.AddTrack("subtitle")
        step("placing the subtitles")
        media_pool.AppendToTimeline([{"mediaPoolItem": subtitle_item, "trackIndex": 1}])
        subtitles_placed = len(timeline.GetItemListInTrack("subtitle", 1) or [])
        # The words as they were said, for a track above, hidden: an editor turns it on to cut a line said in
        # another language where it's said. Resolve's scripting can fill only one subtitle track: every subtitle
        # clip is appended to the track that took the first, at the timeline's end, whatever track is asked for
        # (tried on 21.1: trackIndex, a locked or disabled track 1, a track inserted below, a duplicated timeline,
        # ImportIntoTimeline, an FCPXML with captions; again on 21.2.0.6, where a recordFrame for a subtitle clip
        # still crashes Resolve). So the track is made, named and hidden, and the words wait
        # in the Subtitles bin for the editor to drag onto it at the timeline's start, where they snap into place.
        if spoken and os.path.isfile(spoken) and subtitles_placed:
            step("importing the words as spoken")
            spoken_item = import_subtitle(media_pool, folder, ours, spoken)
            if spoken_item is None:
                spoken_note = "Resolve didn't import the words as spoken"
            else:
                while timeline.GetTrackCount("subtitle") < 2:
                    timeline.AddTrack("subtitle")
                timeline.SetTrackName("subtitle", 1, "Subtitles")
                timeline.SetTrackName("subtitle", 2, "As spoken")
                timeline.SetTrackEnable("subtitle", 2, False)
                spoken_bin = spoken_item.GetName()
                spoken_note = ("the words as spoken are in the Subtitles bin as " + spoken_item.GetName() +
                               ": drag them onto ST2 (As spoken, hidden) at the timeline's start")
    start = timeline.GetStartFrame()
    step("placing the clips")
    for c in plan["clips"]:
        if c["source_path"] in have:
            media_pool.AppendToTimeline([{"mediaPoolItem": have[c["source_path"]], "startFrame": c["in_frame"],
                                          "endFrame": c["out_frame"], "recordFrame": start + c["record_frame"]}])
    placed = timeline.GetItemListInTrack("video", 1) if timeline else []
    placed = placed or []
    ok = (timeline is not None and len(placed) == len([c for c in clips if c["video"]])
          and all(file_path(t.GetMediaPoolItem()) == c["source_path"]
                  for t, c in zip(placed, [c for c in clips if c["video"]])))
    if not ok and method == "subtitles":
        if timeline is not None:
            media_pool.DeleteTimelines([timeline])  # this run's own, seconds old, and wrong
        raise Fail("resolve-failed", f"Resolve placed {len(placed)} of {len([c for c in clips if c['video']])} "
                                     f"clips on the timeline {name}; run it again with --no-subtitles")
    if not ok:
        # Resolve didn't put every clip at its frame: put them down one after another instead. The timeline is this
        # run's own, seconds old, and wrong; nothing else is ever deleted.
        edl_note = f"Resolve placed {len(placed)} of {len([c for c in clips if c['video']])} clips at their frames"
        if timeline is not None:
            media_pool.DeleteTimelines([timeline])
        media_pool.SetCurrentFolder(ours)  # the timeline in the Black Box bin itself
        timeline = media_pool.CreateEmptyTimeline(name)
        media_pool.SetCurrentFolder(folder)
        if timeline is None:
            raise Fail("resolve-failed", f"Resolve couldn't create the timeline {name}")
        project.SetCurrentTimeline(timeline)
        step("placing the clips one after another")
        media_pool.AppendToTimeline([{"mediaPoolItem": have[c["source_path"]], "startFrame": c["in_frame"],
                                      "endFrame": c["out_frame"]} for c in clips])
        method = "append"
    project.SetCurrentTimeline(timeline)
    step("placing the recorders")
    audio_placed = place_audio(media_pool, timeline, [a for a in audio if a["source_path"] in have], have,
                               "clip_frame")
    if plan.get("page"):
        resolve.OpenPage(plan["page"])
    step("saving the project")
    pm.SaveProject()
    step("done")
    db = pm.GetCurrentDatabase() or {}
    return {"project": project.GetName(), "created_project": created, "database": db.get("DbName"),
            "timeline": timeline.GetName(), "method": method, "edl_note": edl_note,
            "clips": len(timeline.GetItemListInTrack("video", 1) or []) + len(
                [c for c in clips if not c["video"]]),
            "imported": len(imported or []), "reused": len(paths) - len(new), "in_project": len(theirs),
            "missing": missing,
            "proxies_linked": linked, "launched": launched, "resolve": resolve.GetVersionString(),
            "audio": len(audio), "audio_placed": audio_placed,
            "subtitles_placed": subtitles_placed, "subtitles_note": subtitles_note,
            "spoken_note": spoken_note, "spoken_bin": spoken_bin}


if __name__ == "__main__":
    try:
        result = main(sys.argv[1])
    except Fail as e:
        result = {"error": {"code": e.code, "message": str(e)}}
    except Exception as e:  # anything Resolve threw: report it, never a traceback on stdout
        if STEP["now"] != "starting" and gone():
            result = {"error": {"code": "resolve-crashed",
                                "message": f"Resolve quit while {STEP['now']}"}}
        else:
            result = {"error": {"code": "resolve-failed",
                                "message": f"{type(e).__name__}: {e} (while {STEP['now']})"}}
        step(f"failed: {result['error']['message']}")
    print(json.dumps(result))
