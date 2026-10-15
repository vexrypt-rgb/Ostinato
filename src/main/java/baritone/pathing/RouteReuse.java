package baritone.pathing;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.JumpFailures;
import baritone.pathing.path.CutoffPath;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Remembers a few paths that reached their goal and replays one when the same goal is asked again,
 * the start still lies on it, the blocks under and around those cells are unchanged, and the cost
 * stamp is unchanged. A jump failure or a calibration scale that left 1 drops the stamp, so the
 * search runs again. Mob costs are not remembered: the caller leaves reuse off for those searches.
 */
public final class RouteReuse {

    public static final int MAX = 8;

    /** Block-state id at a cell. Tests supply this; the world search reads the calculation context. */
    public interface Blocks {
        int id(int x, int y, int z);
    }

    private static final class Entry {
        final Goal goal;
        final long stamp;
        final int[] x;
        final int[] y;
        final int[] z;
        /** Three ids per position: floor, feet, head. */
        final int[] ids;
        final IPath path;

        Entry(Goal goal, long stamp, int[] x, int[] y, int[] z, int[] ids, IPath path) {
            this.goal = goal;
            this.stamp = stamp;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ids = ids;
            this.path = path;
        }
    }

    private static final Entry[] ENTRIES = new Entry[MAX];
    private static int cursor;

    private RouteReuse() {}

    public static void clear() {
        synchronized (ENTRIES) {
            for (int i = 0; i < MAX; i++) {
                ENTRIES[i] = null;
            }
            cursor = 0;
        }
    }

    /** {@link Long#MIN_VALUE} while a jump failure is still priced, so nothing is stored or replayed. */
    public static long stamp(long now) {
        if (!JumpFailures.settled(now)) {
            return Long.MIN_VALUE;
        }
        return CostCalibration.scaleStamp();
    }

    public static long stamp() {
        return stamp(System.currentTimeMillis());
    }

    public static void remember(IPath path, CalculationContext context) {
        if (path == null || context == null) {
            return;
        }
        remember(path.getGoal(), path.positions(), blocks(context), stamp(), path);
    }

    public static void remember(Goal goal, List<BetterBlockPos> positions, Blocks blocks, long stamp, IPath path) {
        if (goal == null || positions == null || blocks == null || stamp == Long.MIN_VALUE || positions.size() < 2) {
            return;
        }
        BetterBlockPos end = positions.get(positions.size() - 1);
        if (!goal.isInGoal(end.x, end.y, end.z)) {
            return;
        }
        int n = positions.size();
        int[] x = new int[n];
        int[] y = new int[n];
        int[] z = new int[n];
        int[] ids = new int[n * 3];
        for (int i = 0; i < n; i++) {
            BetterBlockPos p = positions.get(i);
            x[i] = p.x;
            y[i] = p.y;
            z[i] = p.z;
            ids[i * 3] = blocks.id(p.x, p.y - 1, p.z);
            ids[i * 3 + 1] = blocks.id(p.x, p.y, p.z);
            ids[i * 3 + 2] = blocks.id(p.x, p.y + 1, p.z);
        }
        Entry entry = new Entry(goal, stamp, x, y, z, ids, path);
        synchronized (ENTRIES) {
            ENTRIES[cursor] = entry;
            cursor = (cursor + 1) % MAX;
        }
    }

    /**
     * Index to resume from, or -1. The last cell is not a resume point: there is no movement left.
     * Cells behind the resume point may have changed.
     */
    public static int find(Goal goal, int sx, int sy, int sz, Blocks blocks, long stamp) {
        Entry entry = locate(goal, sx, sy, sz, blocks, stamp);
        if (entry == null) {
            return -1;
        }
        return indexOf(entry, sx, sy, sz);
    }

    /** The stored path cut to start at this cell, or null. */
    public static IPath cut(int sx, int sy, int sz, Goal goal, CalculationContext context) {
        if (goal == null || context == null) {
            return null;
        }
        long stamp = stamp();
        if (stamp == Long.MIN_VALUE) {
            return null;
        }
        Blocks blocks = blocks(context);
        Entry entry;
        synchronized (ENTRIES) {
            entry = locate(goal, sx, sy, sz, blocks, stamp);
        }
        if (entry == null || entry.path == null || entry.path.positions().size() != entry.x.length) {
            return null;
        }
        int index = indexOf(entry, sx, sy, sz);
        if (index < 0) {
            return null;
        }
        return new CutoffPath(entry.path, index, entry.x.length - 1);
    }

    private static Entry locate(Goal goal, int sx, int sy, int sz, Blocks blocks, long stamp) {
        if (goal == null || blocks == null || stamp == Long.MIN_VALUE) {
            return null;
        }
        synchronized (ENTRIES) {
            for (int n = 0; n < MAX; n++) {
                int slot = (cursor - 1 - n + MAX * 2) % MAX;
                Entry entry = ENTRIES[slot];
                if (entry == null || entry.stamp != stamp || !goal.equals(entry.goal)) {
                    continue;
                }
                int index = indexOf(entry, sx, sy, sz);
                if (index < 0) {
                    continue;
                }
                if (!sameFrom(entry, index, blocks)) {
                    continue;
                }
                return entry;
            }
        }
        return null;
    }

    private static int indexOf(Entry entry, int sx, int sy, int sz) {
        int found = -1;
        for (int i = 0; i < entry.x.length - 1; i++) {
            if (entry.x[i] == sx && entry.y[i] == sy && entry.z[i] == sz) {
                found = i;
            }
        }
        return found;
    }

    private static boolean sameFrom(Entry entry, int index, Blocks blocks) {
        for (int i = index; i < entry.x.length; i++) {
            int base = i * 3;
            if (blocks.id(entry.x[i], entry.y[i] - 1, entry.z[i]) != entry.ids[base]
                    || blocks.id(entry.x[i], entry.y[i], entry.z[i]) != entry.ids[base + 1]
                    || blocks.id(entry.x[i], entry.y[i] + 1, entry.z[i]) != entry.ids[base + 2]) {
                return false;
            }
        }
        return true;
    }

    private static Blocks blocks(CalculationContext context) {
        return (x, y, z) -> {
            BlockState state = context.get(x, y, z);
            if (state == null) {
                return -1;
            }
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        };
    }
}
