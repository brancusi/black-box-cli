"""Build a cut as a new timeline in DaVinci Resolve on this Mac: black-box-cli's `online` runs this with
Resolve's own Python and a plan (JSON) it wrote. It imports the originals by path into a Media Pool folder of their
own, links their proxies, and imports cut.edl as a new timeline that links only to those clips. It never deletes,
renames or changes anything that was there before; the one thing it may delete is a timeline it made itself a moment
ago, when the EDL linked to the wrong clips, before appending them instead.

    ResolvePython resolve_build.py plan.json

Prints one JSON object on stdout: the result, or {"error": {"code", "message"}}.
"""

import importlib.machinery
import importlib.util
import json
import os
import subprocess
import sys
import time

LIB = "/Applications/DaVinci Resolve/DaVinci Resolve.app/Contents/Libraries/Fusion/fusionscript.so"
APP = "DaVinci Resolve"


class Fail(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def script_module():
    lib = os.environ.get("RESOLVE_SCRIPT_LIB", LIB)
    loader = importlib.machinery.ExtensionFileLoader("fusionscript", lib)
    spec = importlib.util.spec_from_loader("fusionscript", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def running():
    return subprocess.run(["pgrep", "-x", "Resolve"], capture_output=True).returncode == 0


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
    for f in parent.GetSubFolderList() or []:
        if f.GetName() == name:
            return f
    return media_pool.AddSubFolder(parent, name)


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


def find_existing(root, ours, paths):
    """The originals already in this project, in any folder: {path: (item, folder, mine)}. A clip the editor brought
    in is preferred to one in a Black Box folder, and an exact path to one matched by its media/ path."""
    ours_ids = {f.GetUniqueId() for f in walk(ours)}
    by_key = {}
    for p in paths:
        k = media_key(p)
        if k:
            by_key.setdefault(k, []).append(p)
    found = {}
    for f in walk(root):
        theirs = f.GetUniqueId() not in ours_ids
        for item in f.GetClipList() or []:
            fp = file_path(item)
            if not fp:
                continue
            if fp in paths:
                p, exact = fp, True
            else:
                same = by_key.get(media_key(fp)) or []
                if len(same) != 1:
                    continue
                p, exact = same[0], False
            rank = (theirs, exact)
            if p not in found or rank > found[p][3]:
                found[p] = (item, f, theirs, rank)
    return {p: (item, f, theirs) for p, (item, f, theirs, _) in found.items()}


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

    current = pm.GetCurrentProject()
    into = plan["into"]
    if into == "auto":
        fresh = current is None or current.GetName() == "Untitled Project"
        into = "new-project" if (launched or fresh or switched) else "current"
    if into == "new-project":
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

    media_pool = project.GetMediaPool()
    root = media_pool.GetRootFolder()
    ours = subfolder(media_pool, root, "Black Box")
    folder = subfolder(media_pool, ours, plan["bin"])
    media_pool.SetCurrentFolder(folder)

    # Originals already in this project, in any folder (the editor's own first, then an earlier cut's under "Black
    # Box"), are used where they are, never imported twice; only the rest come into this cut's Black Box folder. The
    # EDL links to them in their folders.
    audio = plan.get("audio") or []
    paths = list(dict.fromkeys([c["source_path"] for c in plan["clips"]] + [a["source_path"] for a in audio]))
    existing = find_existing(root, ours, paths)
    have = {p: item for p, (item, _, _) in existing.items()}
    theirs = {p for p, (_, _, mine) in existing.items() if mine}
    homes, seen = [folder], {folder.GetUniqueId()}
    for _, f, _ in existing.values():
        if f.GetUniqueId() not in seen:
            seen.add(f.GetUniqueId())
            homes.append(f)
    new = [p for p in paths if p not in have]
    # Resolve 21.1 answers None to the documented [{"FilePath": …}] form; a list of paths works.
    imported = (media_pool.ImportMedia(new) or media_pool.ImportMedia([{"FilePath": p} for p in new])) if new else []
    for item in imported or []:
        have[file_path(item)] = item
    missing = [p for p in paths if p not in have]

    linked = 0
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
    srt = plan.get("subtitles")
    subtitle_item = None
    major = (resolve.GetVersion() or [0])[0]
    if srt and os.path.isfile(srt) and major < 21:
        subtitles_note = (f"Resolve {resolve.GetVersionString()}: subtitles are placed on 21 or later; "
                          "import the .srt beside the EDL by hand (File > Import > Subtitle)")
    elif srt and os.path.isfile(srt):
        found = media_pool.ImportMedia([srt]) or media_pool.ImportMedia([{"FilePath": srt}]) or []
        subtitle_item = next((i for i in found if i.GetClipProperty("Type") == "Subtitle"), None)
        if subtitle_item is None:
            subtitles_note = "Resolve didn't import the subtitles"
    edl_note = None
    if subtitle_item is not None or plan.get("by_frames"):
        # Subtitles first, on the empty timeline: Resolve appends a subtitle clip at the timeline's end, keeping each
        # line's time from there (and crashes if given frames for one), so only an empty timeline puts them at its
        # start. Then each clip goes down at its own frame, as the EDL would have put it; a missing one leaves its gap.
        # A cut with a clip that has no timecode (nothing said in it) is placed this way too, with or without lines.
        method = "subtitles" if subtitle_item is not None else "frames"
        timeline = media_pool.CreateEmptyTimeline(name)
        if timeline is None:
            raise Fail("resolve-failed", f"Resolve couldn't create the timeline {name}")
        project.SetCurrentTimeline(timeline)
        if subtitle_item is not None:
            if timeline.GetTrackCount("subtitle") == 0:
                timeline.AddTrack("subtitle")
            media_pool.AppendToTimeline([{"mediaPoolItem": subtitle_item, "trackIndex": 1}])
            subtitles_placed = len(timeline.GetItemListInTrack("subtitle", 1) or [])
        if subtitle_item is not None:
            # The lines are the timeline's own once placed. Left in the bin, the .srt's item goes into an editor's
            # timeline with the clips when they drag the bin's contents, and Resolve crashes placing a subtitle clip
            # that way. It's this run's own, seconds old; the .srt stays beside the EDL.
            media_pool.DeleteClips([subtitle_item])
            subtitle_item = None
        start = timeline.GetStartFrame()
        for c in plan["clips"]:
            if c["source_path"] in have:
                media_pool.AppendToTimeline([{"mediaPoolItem": have[c["source_path"]], "startFrame": c["in_frame"],
                                              "endFrame": c["out_frame"], "recordFrame": start + c["record_frame"]}])
    else:
        method = "edl"
        timeline = media_pool.ImportTimelineFromFile(plan["edl"], {"timelineName": name, "importSourceClips": False,
                                                                   "sourceClipsFolders": homes})
    placed = timeline.GetItemListInTrack("video", 1) if timeline else []
    placed = placed or []
    ok = (timeline is not None and len(placed) == len([c for c in clips if c["video"]])
          and all(file_path(t.GetMediaPoolItem()) == c["source_path"]
                  for t, c in zip(placed, [c for c in clips if c["video"]])))
    if not ok and method == "frames":
        raise Fail("resolve-failed", f"Resolve placed {len(placed)} of {len([c for c in clips if c['video']])} "
                                     f"clips on the timeline {name}")
    if not ok and method == "subtitles":
        if timeline is not None:
            media_pool.DeleteTimelines([timeline])  # this run's own, seconds old, and wrong
        raise Fail("resolve-failed", f"Resolve placed {len(placed)} of {len([c for c in clips if c['video']])} "
                                     f"clips on the timeline {name}; run it again with --no-subtitles")
    if not ok:
        # The EDL didn't link every event to its own original: put the clips down by frame instead. The timeline it
        # made is this run's own, seconds old, and wrong; nothing else is ever deleted.
        edl_note = f"the EDL linked {len(placed)} of {len(clips)} clips"
        if timeline is not None:
            media_pool.DeleteTimelines([timeline])
        timeline = media_pool.CreateEmptyTimeline(name)
        if timeline is None:
            raise Fail("resolve-failed", f"Resolve couldn't create the timeline {name}")
        project.SetCurrentTimeline(timeline)
        media_pool.AppendToTimeline([{"mediaPoolItem": have[c["source_path"]], "startFrame": c["in_frame"],
                                      "endFrame": c["out_frame"]} for c in clips])
        method = "append"
    project.SetCurrentTimeline(timeline)
    audio_placed = place_audio(media_pool, timeline, [a for a in audio if a["source_path"] in have], have,
                               "record_frame" if method == "edl" else "clip_frame")
    if plan.get("page"):
        resolve.OpenPage(plan["page"])
    pm.SaveProject()
    db = pm.GetCurrentDatabase() or {}
    return {"project": project.GetName(), "created_project": created, "database": db.get("DbName"),
            "timeline": timeline.GetName(), "method": method, "edl_note": edl_note,
            "clips": len(timeline.GetItemListInTrack("video", 1) or []) + len(
                [c for c in clips if not c["video"]]),
            "imported": len(imported or []), "reused": len(paths) - len(new), "in_project": len(theirs),
            "missing": missing,
            "proxies_linked": linked, "launched": launched, "resolve": resolve.GetVersionString(),
            "audio": len(audio), "audio_placed": audio_placed,
            "subtitles_placed": subtitles_placed, "subtitles_note": subtitles_note}


if __name__ == "__main__":
    try:
        result = main(sys.argv[1])
    except Fail as e:
        result = {"error": {"code": e.code, "message": str(e)}}
    except Exception as e:  # anything Resolve threw: report it, never a traceback on stdout
        result = {"error": {"code": "resolve-failed", "message": f"{type(e).__name__}: {e}"}}
    print(json.dumps(result))
