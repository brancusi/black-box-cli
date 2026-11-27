"""Checks how resolve_build finds the originals already in a project, with stand-ins for Resolve's media pool (no
Resolve, no files): run by resolve_test. Prints one JSON object."""
import importlib.util
import json
import os
import sys

spec = importlib.util.spec_from_file_location("resolve_build", sys.argv[1])
rb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rb)
asked = []  # every clip whose path was asked for


class Item:
    def __init__(self, path, bin_):
        self.path, self.bin = path, bin_

    def GetName(self):
        return os.path.basename(self.path)

    def GetMediaId(self):
        return self.bin + ":" + self.path

    def GetClipProperty(self, key):
        asked.append(self.path)
        return self.path if key == "File Path" else ""


class Folder:
    def __init__(self, name, items=(), subs=()):
        self.name, self.items, self.subs = name, [Item(p, name) for p in items], list(subs)

    def GetName(self):
        return self.name

    def GetUniqueId(self):
        return "id-" + self.name

    def GetClipList(self):
        return self.items

    def GetSubFolderList(self):
        return self.subs


class Placed:
    def __init__(self, item):
        self.item = item

    def GetMediaPoolItem(self):
        return self.item


class Timeline:
    def __init__(self, name, tracks):
        self.name, self.tracks = name, tracks

    def GetName(self):
        return self.name

    def GetTrackCount(self, kind):
        return 1 if kind in self.tracks else 0

    def GetItemListInTrack(self, kind, n):
        return [Placed(i) for i in self.tracks.get(kind, [])] if n == 1 else []


class Project:
    def __init__(self, shown):
        self.shown = shown

    def GetCurrentTimeline(self):
        return self.shown

    def SetCurrentTimeline(self, t):
        self.shown = t
        return True


class MediaPool:
    """Resolve's import of an FCP 7 XML, as it behaved on 21.2: each clip to the one at its path in the bins it's
    given (only those, not their sub-bins), else to the first there with its name; the timeline made is shown."""

    def __init__(self, root, project):
        self.root, self.project, self.deleted, self.xml = root, project, [], None

    def GetRootFolder(self):
        return self.root

    def SetCurrentFolder(self, f):
        return True

    def ImportTimelineFromFile(self, path, options):
        import re
        import urllib.parse
        with open(path, encoding="utf-8") as f:
            self.xml = f.read()
        given = [i for f in options["sourceClipsFolders"] for i in f.GetClipList()]

        def link(m):
            p = urllib.parse.unquote(m.group(2)[len("file://"):])
            return (next((i for i in given if i.path == p), None)
                    or next((i for i in given if i.GetName() == os.path.basename(p)), None))
        tracks = {k: [link(m) for m in re.finditer(r'<clipitem id="probe (video|audio) \d+">.*?<pathurl>(.*?)</pathurl>',
                                                   self.xml) if m.group(1) == k]
                  for k in ("video", "audio")}
        self.project.shown = Timeline(options["timelineName"], tracks)
        return self.project.shown

    def DeleteTimelines(self, timelines):
        self.deleted += timelines
        return True


A = "/Volumes/Video/media/2026/09/16/fx3/C0001.MP4"
B = "/Volumes/Video/media/2026/09/17/fx3/C0002.MP4"
C = "/Volumes/Video/media/2026/09/18/fx3/C0003.MP4"
R = "/Volumes/Video/media/2026/09/16/audio/REC01.WAV"
W = "/Volumes/Video/media/2026/09/18/audio/REC02 b's.WAV"
ours = Folder("Black Box", subs=[Folder("an earlier cut", [R, "/Volumes/Video/media/2026/09/01/audio/OTHER.WAV"])])
root = Folder("Master", subs=[
    Folder("Elsewhere", ["/Volumes/Other/cards/C0003.MP4", "/Volumes/Other/cards/C0001.MP4"]),  # same names, found first
    ours,
    Folder("Editor media", [A, "/Volumes/Video-1/media/2026/09/17/fx3/C0002.MP4"] + [f"/x/other{i}.mov" for i in range(500)],
           subs=[Folder("Sound", [W])]),
])
editors = Timeline("the editor's own", {})
project = Project(editors)
pool = MediaPool(root, project)
have = rb.in_our_bin(ours, [A, B, C, R, W])
ours_asked = len(asked)
rest = [{"path": p, "tc": "10:00:00:00", "fps": 25, "video": p.endswith(".MP4")} for p in [A, B, C, W]]
xml = os.path.join(sys.argv[2], "online-x-probe.xml")
found = rb.found_by_resolve(project, pool, ours, xml, rest, "probe")
print(json.dumps({"in_our_bin": {p.split("/media/")[1]: i.bin for p, i in have.items()},
                  "asked_in_our_bin": ours_asked,
                  "by_resolve": {p.split("/media/")[1]: [i.bin, i.path] for p, i in found.items()},
                  "probe_deleted": len(pool.deleted),
                  "shown_after": project.GetCurrentTimeline().GetName(),
                  "xml_written": os.path.isfile(xml),
                  "xml_has_format": "<format>" in pool.xml and "<timecode><string>10:00:00:00</string>" in pool.xml,
                  "xml_quoted": "REC02%20b%27s.WAV" in pool.xml}))
