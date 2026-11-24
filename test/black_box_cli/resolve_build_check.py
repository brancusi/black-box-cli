"""Checks resolve_build.find_existing with stand-ins for Resolve's media pool (no Resolve, no files): run by
resolve_test. Prints one JSON object."""
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location("resolve_build", sys.argv[1])
rb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rb)


class Item:
    def __init__(self, path):
        self.path = path

    def GetClipProperty(self, key):
        return self.path if key == "File Path" else ""


class Folder:
    def __init__(self, name, items=(), subs=()):
        self.name, self.items, self.subs = name, [Item(p) for p in items], list(subs)

    def GetName(self):
        return self.name

    def GetUniqueId(self):
        return "id-" + self.name

    def GetClipList(self):
        return self.items

    def GetSubFolderList(self):
        return self.subs


A = "/Volumes/Video/media/2026/09/16/fx3/C0001.MP4"
B = "/Volumes/Video/media/2026/09/17/fx3/C0002.MP4"
C = "/Volumes/Video/media/2026/09/18/fx3/C0001.MP4"
ours = Folder("Black Box", subs=[Folder("an earlier cut", [A, B])])
root = Folder("Master", subs=[
    ours,
    Folder("Editor media", [A, "/Volumes/Video-1/media/2026/09/17/fx3/C0002.MP4"]),
    Folder("Elsewhere", ["/Volumes/Other/cards/C0001.MP4"]),  # the same name, another file
])
found = rb.find_existing(root, ours, [A, B, C])
print(json.dumps({p.split("/media/")[1]: [f.GetName(), mine, item.path] for p, (item, f, mine) in found.items()}))
