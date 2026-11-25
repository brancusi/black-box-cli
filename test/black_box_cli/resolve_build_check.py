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
    def __init__(self, items):
        self.items = items

    def GetItemListInTrack(self, kind, n):
        return [Placed(i) for i in self.items] if (kind, n) == ("video", 1) else []


class MediaPool:
    """Resolve's EDL import, as it behaves: each event to the first clip with its name, in any bin."""

    def __init__(self, root, events):
        self.root, self.events, self.deleted = root, events, []

    def GetRootFolder(self):
        return self.root

    def SetCurrentFolder(self, f):
        return True

    def ImportTimelineFromFile(self, edl, options):
        every = [i for f in rb.walk(options["sourceClipsFolders"][0]) for i in f.GetClipList()]
        return Timeline([next((i for i in every if i.GetName() == e), None) for e in self.events])

    def DeleteTimelines(self, timelines):
        self.deleted += timelines
        return True


A = "/Volumes/Video/media/2026/09/16/fx3/C0001.MP4"
B = "/Volumes/Video/media/2026/09/17/fx3/C0002.MP4"
C = "/Volumes/Video/media/2026/09/18/fx3/C0003.MP4"
R = "/Volumes/Video/media/2026/09/16/audio/REC01.WAV"
ours = Folder("Black Box", subs=[Folder("an earlier cut", [R, "/Volumes/Video/media/2026/09/01/audio/OTHER.WAV"])])
root = Folder("Master", subs=[
    Folder("Elsewhere", ["/Volumes/Other/cards/C0003.MP4"]),  # the same name as C, another file, found first
    ours,
    Folder("Editor media", [A, "/Volumes/Video-1/media/2026/09/17/fx3/C0002.MP4"] + [f"/x/other{i}.mov" for i in range(500)]),
])
pool = MediaPool(root, ["C0001.MP4", "C0002.MP4", "C0003.MP4"])
have = rb.in_our_bin(ours, [A, B, C, R])
ours_asked = len(asked)
found = rb.found_by_resolve(pool, ours, "cut.edl", [A, B, C, R], "probe")
print(json.dumps({"in_our_bin": {p.split("/media/")[1]: i.bin for p, i in have.items()},
                  "asked_in_our_bin": ours_asked,
                  "by_resolve": {p.split("/media/")[1]: [i.bin, i.path] for p, i in found.items()},
                  "probe_deleted": len(pool.deleted)}))
