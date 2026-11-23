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


def timeline_names(project):
    return {project.GetTimelineByIndex(i).GetName() for i in range(1, project.GetTimelineCount() + 1)}


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

    # Originals an earlier cut already brought into this project (under "Black Box") are used again, not imported
    # twice: the EDL may link to them in their own folders.
    paths = list(dict.fromkeys(c["source_path"] for c in plan["clips"]))
    have, homes = {}, [folder]
    for f in walk(ours):
        found = False
        for item in f.GetClipList() or []:
            p = file_path(item)
            if p in paths and p not in have:
                have[p], found = item, True
        if found and f.GetUniqueId() != folder.GetUniqueId():
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
            if item and proxy and os.path.isfile(proxy) and not item.GetClipProperty("Proxy Media Path"):
                if item.LinkProxyMedia(proxy):
                    linked += 1

    clips = [c for c in plan["clips"] if c["source_path"] in have]
    if not clips:
        raise Fail("no-originals", "none of the originals could be imported (is the NAS mounted on this Mac?)")
    name = unique(plan["timeline"], timeline_names(project))
    method = "edl"
    timeline = media_pool.ImportTimelineFromFile(plan["edl"], {"timelineName": name, "importSourceClips": False,
                                                               "sourceClipsFolders": homes})
    placed = timeline.GetItemListInTrack("video", 1) if timeline else []
    placed = placed or []
    ok = (timeline is not None and len(placed) == len([c for c in clips if c["video"]])
          and all(file_path(t.GetMediaPoolItem()) == c["source_path"]
                  for t, c in zip(placed, [c for c in clips if c["video"]])))
    edl_note = None
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
    if plan.get("page"):
        resolve.OpenPage(plan["page"])
    pm.SaveProject()
    db = pm.GetCurrentDatabase() or {}
    return {"project": project.GetName(), "created_project": created, "database": db.get("DbName"),
            "timeline": timeline.GetName(), "method": method, "edl_note": edl_note,
            "clips": len(timeline.GetItemListInTrack("video", 1) or []) + len(
                [c for c in clips if not c["video"]]),
            "imported": len(imported or []), "reused": len(paths) - len(new), "missing": missing,
            "proxies_linked": linked, "launched": launched, "resolve": resolve.GetVersionString()}


if __name__ == "__main__":
    try:
        result = main(sys.argv[1])
    except Fail as e:
        result = {"error": {"code": e.code, "message": str(e)}}
    except Exception as e:  # anything Resolve threw: report it, never a traceback on stdout
        result = {"error": {"code": "resolve-failed", "message": f"{type(e).__name__}: {e}"}}
    print(json.dumps(result))
