"""Ostinato 2.0 Phase 07: split JFR CPU samples of a planner run into search / cost evaluation / world access.

usage: jfr print --json --stack-depth 64 --events jdk.ExecutionSample <file.jfr> > samples.json
       python jfr_planner_split.py samples.json

Each sample's top frame is put in one bucket (self time). The inclusive table counts a sample under every bucket that
appears anywhere on its stack (so it does not sum to 100%). Only samples with an A* frame on the stack are counted.
"""
import json
import sys
from collections import Counter

BUCKETS = [
    # (bucket, predicate on "pkg.Class.method")
    ("world access", lambda f: any(k in f for k in (
        "BlockStateInterface.", "SyntheticWorld.", "PalettedContainer", "LevelChunk", "ChunkAccess", "BlockStateInterfaceAccessWrapper",
        "WorldCache", "CachedRegion", "SeedPlan"))),
    ("block properties", lambda f: any(k in f for k in (
        "BlockBehaviour", "BlockState.", "FluidState", "VoxelShape", "Shapes.", "MovementHelper.", "PrecomputedData"))),
    ("movement cost", lambda f: f.startswith("baritone.pathing.movement.")),
    ("node map / open set", lambda f: any(k in f for k in (
        "BinaryHeapOpenSet", "PathNode", "Long2ObjectOpenHashMap", "BetterBlockPos.longHash", "NodeMap",
        "AbstractNodeCostSearch.getNodeAtPosition", "AbstractNodeCostSearch.peekNodeAtPosition", "AbstractNodeCostSearch.nodeInSlice"))),
    ("search loop", lambda f: any(k in f for k in ("AStarPathFinder.", "AbstractNodeCostSearch.", "Favoring.", "Goal"))),
    ("hierarchy", lambda f: "pathing.calc.hierarchy." in f or "HierarchicalPathFinder" in f),
    ("path assembly", lambda f: f.startswith("baritone.pathing.calc.Path.") or "RouteReuse" in f),
]


def frame_name(fr):
    m = fr.get("method", {})
    t = m.get("type", {}).get("name", "?")
    return f"{t.replace('/', '.')}.{m.get('name', '?')}"


def bucket(name):
    for b, p in BUCKETS:
        if p(name):
            return b
    if name.startswith("java.") or name.startswith("jdk.") or name.startswith("it.unimi"):
        return "jdk/collections"
    return "other"


def main():
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    events = data["recording"]["events"]
    self_c, incl_c, top = Counter(), Counter(), Counter()
    n = 0
    for e in events:
        st = e["values"].get("stackTrace")
        if not st:
            continue
        frames = [frame_name(f) for f in st["frames"]]
        if not any("AStarPathFinder" in f or "HierarchicalPathFinder" in f for f in frames):
            continue
        n += 1
        self_c[bucket(frames[0])] += 1
        top[frames[0]] += 1
        for b in {bucket(f) for f in frames}:
            incl_c[b] += 1
    print(f"samples on a planner stack: {n}")
    print("bucket,self%,inclusive%")
    for b in sorted(set(self_c) | set(incl_c), key=lambda b: -self_c[b]):
        print(f"{b},{100 * self_c[b] / n:.1f},{100 * incl_c[b] / n:.1f}")
    print()
    print("top self frames")
    for f, c in top.most_common(25):
        print(f"{100 * c / n:5.1f}% {f}")


if __name__ == "__main__":
    main()
