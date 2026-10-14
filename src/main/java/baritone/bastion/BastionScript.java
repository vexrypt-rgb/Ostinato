package baritone.bastion;

import baritone.bastion.BastionRoutes.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted actions for the route's movement steps (Couriway's treasure and housing videos). A step expands to ops in a
 * local frame: f forward (toward the bastion centre or the step's target), r right, u up, all relative to the block
 * we stand on when the step starts. BastionProcess executes the ops with its own placement, mining and pathing code;
 * {@link Runner} is the pure state machine that walks them. Steps with no script (looting, trading) return an empty
 * list: the chest/gold plan handles those.
 */
public final class BastionScript {
    private BastionScript() {}

    public enum Kind {
        /** Stand on the cell. */ MOVE,
        /** Path to the step's world target (bridge column, stairs). */ MOVE_TARGET,
        /** Place a block in the cell. */ PLACE,
        /** Mine the cell. */ MINE,
        /** Jump-place n blocks under us (the shelter tower). */ PILLAR,
        /** Throw n ingots toward the nearest calm piglin (0 = half of what we carry). */ THROW,
        /** Put a block on each gold-block item near us so we do not pick it up (push it away). */ PUSH_ITEMS,
        /** Cover n lava cells next to the route; gap=1 leaves one open for piglins to path through. */ BLOCK_LAVA,
        /** Pour lava under the nearest hostile straggler, then scoop it back. */ LAVA_ON_PIGLIN,
        /** Equip a gold helmet (or any gold armour). */ WEAR_GOLD,
        /** Mine the block under/beside a stuck piglin so it drops (replaces the Hard/Easy difficulty toggle). */ UNSTICK,
        /** Craft a gold helmet (5 ingots) at a crafting table we carry or make from nether stems. */ CRAFT_ARMOR,
        /** Craft n gold blocks we hold into ingots (2x2 grid). */ CRAFT_INGOTS,
        /** Mine the highest gold block within reach (n = blocks). */ MINE_GOLD_NEAR,
        /** Jump to the cell over a 1-block gap; fills the gap when the landing has no floor. */ JUMP,
        /** Trade from where we stand for up to n seconds: hand ingots to piglins that come, collect the barters. */ TRADE_AT,
        /** Gold elevator batch: craft n gold blocks into ingots up top and throw them into the cup. */ CARRY_UP
    }

    public static final class Op {
        public final Kind kind;
        public final int f;
        public final int r;
        public final int u;
        public final int n;
        public Op(Kind kind, int f, int r, int u, int n) {
            this.kind = kind;
            this.f = f;
            this.r = r;
            this.u = u;
            this.n = n;
        }
        public Kind kind() { return kind; }
        public int f() { return f; }
        public int r() { return r; }
        public int u() { return u; }
        public int n() { return n; }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof Op)) return false; Op that__ = (Op) o; return java.util.Objects.equals(kind, that__.kind) && java.util.Objects.equals(f, that__.f) && java.util.Objects.equals(r, that__.r) && java.util.Objects.equals(u, that__.u) && java.util.Objects.equals(n, that__.n); }
        @Override public int hashCode() { return java.util.Objects.hash(kind, f, r, u, n); }
        @Override public String toString() { return "Op[kind=" + kind + ", f=" + f + ", r=" + r + ", u=" + u + ", n=" + n + "]"; }
        static Op at(Kind k, int f, int r, int u) { return new Op(k, f, r, u, 0); }
        static Op n(Kind k, int n) { return new Op(k, 0, 0, 0, n); }
    }

    /** Seconds a trade-location step trades for before the route moves on. */
    public static final int TRADE_SECONDS = 90;

    public static List<Op> ops(Step s) {
        List<Op> o = new ArrayList<>();
        switch (s) {
            // ---- treasure
            case TAKE_THIRD_BASALT_BRIDGE: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            case PILLAR_3_HUG: o.add(Op.n(Kind.PILLAR, 3)); break;
            case COVER_2_BLOCKS:
                // on a bare pillar the only support is the pillar block: a footing beside it first, then 2 cover blocks on top (r142: both skipped, no support)
                o.add(Op.at(Kind.PLACE, 1, 0, -1)); o.add(Op.at(Kind.PLACE, 1, 0, 0)); o.add(Op.at(Kind.PLACE, 1, 0, 1)); break;
            // the high gold above the pillar, crafted into 9 ingots at once so the crossbow distraction has gold to throw
            case MINE_HIGH_GOLD_CRAFT_INGOTS: o.add(Op.n(Kind.MINE_GOLD_NEAR, 1)); o.add(Op.n(Kind.CRAFT_INGOTS, 1)); break;
            case DISTRACT_CROSSBOWS: o.add(Op.n(Kind.CRAFT_INGOTS, 1)); o.add(Op.n(Kind.THROW, 3)); break;
            case BRIDGE_GAP:
                // video: place 2, jump the gap, place 2 (the jump saves 2 blocks)
                o.add(Op.at(Kind.PLACE, 1, 0, -1)); o.add(Op.at(Kind.PLACE, 2, 0, -1)); o.add(Op.at(Kind.MOVE, 2, 0, 0));
                o.add(Op.at(Kind.JUMP, 4, 0, 0));
                o.add(Op.at(Kind.PLACE, 5, 0, -1)); o.add(Op.at(Kind.PLACE, 6, 0, -1)); o.add(Op.at(Kind.MOVE, 6, 0, 0));
                break;
            case BUILD_POCKET:
                // 2 on the wall side, 3 around us
                o.add(Op.at(Kind.PLACE, 1, 0, 0)); o.add(Op.at(Kind.PLACE, 1, 0, 1));
                o.add(Op.at(Kind.PLACE, 0, -1, 0)); o.add(Op.at(Kind.PLACE, 0, 1, 0)); o.add(Op.at(Kind.PLACE, -1, 0, 0));
                break;
            // craft the helmet from the ingots, wear it, and drop gold from the pocket (video step 9)
            case WEAR_GOLD_HELMET: o.add(Op.n(Kind.CRAFT_ARMOR, 0)); o.add(Op.n(Kind.WEAR_GOLD, 0)); o.add(Op.n(Kind.THROW, 1)); break;
            case AGGRO_ONE_GOLD_ARMOR: o.add(Op.n(Kind.CRAFT_ARMOR, 0)); o.add(Op.n(Kind.WEAR_GOLD, 0)); o.add(Op.n(Kind.THROW, 1)); break;
            case CROSS_BRICK_BRIDGE: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            case OPEN_ABOVE_STAIRS: o.add(Op.at(Kind.MINE, 1, 0, 2)); break;
            case MINE_BLOCKER_SEAL_BEHIND: o.add(Op.at(Kind.MINE, 0, -1, 0)); o.add(Op.at(Kind.MINE, 0, -1, 1)); o.add(Op.at(Kind.MOVE, 0, -1, 0)); o.add(Op.at(Kind.PLACE, 0, 0, 0)); break;
            // open 2 wall blocks, bridge 2 across the ramparts, and cross them
            case BRIDGE_RAMPARTS: o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 1, 0, 1)); o.add(Op.at(Kind.PLACE, 2, 0, -1)); o.add(Op.at(Kind.PLACE, 3, 0, -1)); o.add(Op.at(Kind.MOVE, 3, 0, 0)); break;
            case PIGLIN_PIT: case HOUSING_PIT: o.add(Op.at(Kind.MINE, 1, -1, -1)); o.add(Op.at(Kind.MINE, 1, -1, -2)); o.add(Op.n(Kind.THROW, 1)); break;
            // back to the pit, mine a corner block to stand in, and trade the rest from there
            case TRADE_AT_PIT: o.add(Op.n(Kind.MOVE_TARGET, 0)); o.add(Op.n(Kind.TRADE_AT, TRADE_SECONDS)); break;
            // ---- housing (beginner)
            case HOUSING_ENTRY_BASALT_CUE: o.add(Op.n(Kind.MOVE_TARGET, 0)); o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 1, 0, 1)); o.add(Op.at(Kind.MOVE, 1, 0, 0)); o.add(Op.n(Kind.THROW, 1)); break;
            case WALK_RIGHT_FORK: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            case RIGHT_FORK_BLOCK_IN: o.add(Op.at(Kind.PLACE, -1, 0, 0)); o.add(Op.at(Kind.PLACE, -1, 0, 1)); o.add(Op.at(Kind.PLACE, -2, 0, 0)); o.add(Op.at(Kind.PLACE, -2, 0, 1)); break;
            case DROP_PIGLINS: o.add(Op.at(Kind.MINE, 1, 0, 2)); o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 1, 0, 1)); break;
            case DISTRACT_HALF_GOLD: o.add(Op.n(Kind.THROW, 0)); break;
            // go to the corner, mine down, drop in, block overhead (1x1)
            case ONE_BY_ONE: o.add(Op.n(Kind.MOVE_TARGET, 0)); o.add(Op.at(Kind.MINE, 0, 0, -1)); o.add(Op.at(Kind.MINE, 0, 0, -2)); o.add(Op.at(Kind.MOVE, 0, 0, -2)); o.add(Op.at(Kind.PLACE, 0, 0, 0)); break;
            // dig down 3 and the first tunnel block; the pit is dug at that first block (video), the rest after it
            case DIG_DOWN_3_STRIP_4:
                for (int d = 1; d <= 3; d++) { o.add(Op.at(Kind.MINE, 0, 0, -d)); o.add(Op.at(Kind.MOVE, 0, 0, -d)); }
                o.add(Op.at(Kind.MINE, 1, 0, -3)); o.add(Op.at(Kind.MINE, 1, 0, -2)); o.add(Op.at(Kind.MOVE, 1, 0, -3));
                break;
            case STRIP_REST:
                for (int i = 1; i <= 3; i++) { o.add(Op.at(Kind.MINE, i, 0, 0)); o.add(Op.at(Kind.MINE, i, 0, 1)); o.add(Op.at(Kind.MOVE, i, 0, 0)); }
                break;
            // gold pushed away only after it is mined (the block step before claims the gold)
            case PUSH_WALL_GOLD: case T_TUNNEL_PUSH: o.add(Op.n(Kind.PUSH_ITEMS, 0)); break;
            case T_TUNNEL_GOLD:
                o.add(Op.at(Kind.MINE, 0, -1, 0)); o.add(Op.at(Kind.MINE, 0, -1, 1)); o.add(Op.at(Kind.MINE, 0, 1, 0)); o.add(Op.at(Kind.MINE, 0, 1, 1));
                break;
            case UNSTICK_BY_MINING: o.add(Op.n(Kind.UNSTICK, 0)); break;
            case TRADE_CORNER: case TRADE_TRIPLE: case TRADE_SINGLE: case TRADE: o.add(Op.n(Kind.MOVE_TARGET, 0)); o.add(Op.n(Kind.TRADE_AT, TRADE_SECONDS)); break;
            // ---- stables
            case TOP_OF_MIDDLE_STAIRS: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            // mine the top-right block at the stair top and the mid-stairs wall to see the gaps
            case PROBE_GAPS: o.add(Op.at(Kind.MINE, 1, 1, 1)); o.add(Op.at(Kind.MINE, 1, -1, 1)); break;
            // ---- housing (intermediate / lava)
            case BREAK_CHEST_AGGRO_THROW: o.add(Op.n(Kind.THROW, 1)); break;
            case GOLD_ELEVATOR:
                // the gold is mined by ELEVATOR_GOLD first; fill the 3 blocks above the pit column, a cup, then carry 3 then 4 blocks up
                o.add(Op.at(Kind.PLACE, 1, 0, 0)); o.add(Op.at(Kind.PLACE, 1, 0, 1)); o.add(Op.at(Kind.PLACE, 1, 0, 2));
                o.add(Op.at(Kind.PLACE, 1, -1, 1)); o.add(Op.at(Kind.PLACE, 1, 1, 1));
                for (int b : BastionRoutes.ELEVATOR_BATCHES) o.add(Op.n(Kind.CARRY_UP, b));
                break;
            case LAVA_BLOCK_GAP: o.add(new Op(Kind.BLOCK_LAVA, 0, 0, 0, 2)); o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 2, 0, -1)); break;
            case STAIR_2: o.add(Op.at(Kind.MINE, 1, 0, -1)); o.add(Op.at(Kind.MINE, 2, 0, -2)); break;
            case BLOCK_LOWER_LAVA: o.add(new Op(Kind.BLOCK_LAVA, 0, 0, 0, 3)); break;
            case LAVA_STRAGGLERS: o.add(Op.n(Kind.LAVA_ON_PIGLIN, 0)); break;
            default: break;
        }
        return o;
    }

    /** True for ops that move us: the frame re-anchors on where we stand after a MOVE_TARGET. */
    public static boolean reanchors(Kind k) {
        return k == Kind.MOVE_TARGET;
    }

    /** Route-fixed facing toward the bastion centre from (dx, dz): a unit step {fx, fz}. Computed once per route. */
    public static int[] facing(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{dx >= 0 ? 1 : -1, 0};
        return new int[]{0, dz >= 0 ? 1 : -1};
    }

    /**
     * Bridge columns from basalt cells given as {lateral, height-of-column}: lateral offsets of columns at least 3 tall,
     * neighbouring offsets merged into one column (a 2-wide column is one bridge). Sorted left to right.
     */
    public static List<Integer> columns(java.util.Map<Integer, Integer> heightByLateral) {
        List<Integer> lat = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : heightByLateral.entrySet()) if (e.getValue() >= 3) lat.add(e.getKey());
        lat.sort(null);
        List<Integer> cols = new ArrayList<>();
        Integer prev = null;
        for (int l : lat) { if (prev == null || l - prev > 1) cols.add(l); prev = l; }
        return cols;
    }

    /** Treasure entry: the 3rd basalt bridge from the left, facing the bastion. Columns given as lateral offsets. */
    public static int thirdFromLeft(List<Integer> lateralOffsets) {
        List<Integer> s = new ArrayList<>(lateralOffsets);
        s.sort(null);
        if (s.isEmpty()) return Integer.MIN_VALUE;
        return s.get(Math.min(2, s.size() - 1));
    }

    /** Local (f, r, u) to world (dx, dy, dz) for a facing given as a unit step (fx, fz). Right = (-fz, fx). */
    public static int[] toWorld(int fx, int fz, int f, int r, int u) {
        return new int[]{fx * f - fz * r, u, fz * f + fx * r};
    }

    public enum Result { WORKING, DONE, FAILED }

    /**
     * Walks the ops of one step. Each op gets a tick budget; a failed or timed-out op is retried once, then skipped.
     * {@link #event()} reports retries and skips so the process can log them loudly, and {@link #noEffect()} says whether
     * every op finished on its first tick (the step did nothing).
     */
    public static final class Runner {
        public static final int OP_TICKS = 200;
        private final List<Op> ops;
        private int i, since, skipped, instant;
        private boolean retried;
        private String event;

        public Runner(List<Op> ops) { this.ops = ops; }

        public Op current() { return i < ops.size() ? ops.get(i) : null; }
        public boolean done() { return i >= ops.size(); }
        public int skipped() { return skipped; }
        public int index() { return i; }
        /** Skip the rest of the step's ops (counted as skipped). */
        public void abort() { while (!done()) { i++; skipped++; } }

        public boolean noEffect() { return done() && !ops.isEmpty() && instant == ops.size(); }

        /** The retry/skip that happened on the last report, or null; cleared on read. */
        public String event() { String e = event; event = null; return e; }

        /** Feed the executor's result for the current op this tick. */
        public void report(Result r) {
            if (done()) return;
            if (r == Result.DONE) {
                if (since == 0 && !retried) instant++;
                i++; since = 0; retried = false;
                return;
            }
            since++;
            if (r == Result.FAILED || since > OP_TICKS) {
                String why = r == Result.FAILED ? "failed" : "timed out";
                if (!retried) { retried = true; since = 0; event = "op " + (i + 1) + " " + ops.get(i).kind() + " " + why + ", retrying once"; }
                else { event = "op " + (i + 1) + " " + ops.get(i).kind() + " " + why + " again, skipped"; i++; since = 0; skipped++; retried = false; }
            }
        }
    }
}
