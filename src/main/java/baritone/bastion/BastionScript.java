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
        /** Mine the block under/beside a stuck piglin so it drops (replaces the Hard/Easy difficulty toggle). */ UNSTICK
    }

    public record Op(Kind kind, int f, int r, int u, int n) {
        static Op at(Kind k, int f, int r, int u) { return new Op(k, f, r, u, 0); }
        static Op n(Kind k, int n) { return new Op(k, 0, 0, 0, n); }
    }

    public static List<Op> ops(Step s) {
        List<Op> o = new ArrayList<>();
        switch (s) {
            // ---- treasure
            case TAKE_THIRD_BASALT_BRIDGE: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            case PILLAR_3_HUG: o.add(Op.n(Kind.PILLAR, 3)); break;
            case COVER_2_BLOCKS: o.add(Op.at(Kind.PLACE, 0, -1, 1)); o.add(Op.at(Kind.PLACE, 0, 1, 1)); break;
            case DISTRACT_CROSSBOWS: o.add(Op.n(Kind.THROW, 3)); break;
            case BRIDGE_GAP:
                o.add(Op.at(Kind.PLACE, 1, 0, -1)); o.add(Op.at(Kind.PLACE, 2, 0, -1)); o.add(Op.at(Kind.MOVE, 2, 0, 0));
                o.add(Op.at(Kind.PLACE, 3, 0, -1)); o.add(Op.at(Kind.PLACE, 4, 0, -1)); o.add(Op.at(Kind.MOVE, 4, 0, 0));
                break;
            case BUILD_POCKET:
                // 2 on the wall side, 3 around us
                o.add(Op.at(Kind.PLACE, 1, 0, 0)); o.add(Op.at(Kind.PLACE, 1, 0, 1));
                o.add(Op.at(Kind.PLACE, 0, -1, 0)); o.add(Op.at(Kind.PLACE, 0, 1, 0)); o.add(Op.at(Kind.PLACE, -1, 0, 0));
                break;
            case WEAR_GOLD_HELMET: case AGGRO_ONE_GOLD_ARMOR: o.add(Op.n(Kind.WEAR_GOLD, 0)); break;
            case CROSS_BRICK_BRIDGE: o.add(Op.n(Kind.MOVE_TARGET, 0)); break;
            case OPEN_ABOVE_STAIRS: o.add(Op.at(Kind.MINE, 1, 0, 2)); break;
            case MINE_BLOCKER_SEAL_BEHIND: o.add(Op.at(Kind.MINE, 0, -1, 0)); o.add(Op.at(Kind.MINE, 0, -1, 1)); o.add(Op.at(Kind.MOVE, 0, -1, 0)); o.add(Op.at(Kind.PLACE, 0, 0, 0)); break;
            case BRIDGE_RAMPARTS: o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 1, 0, 1)); o.add(Op.at(Kind.PLACE, 2, 0, -1)); o.add(Op.at(Kind.PLACE, 3, 0, -1)); break;
            case PIGLIN_PIT: case HOUSING_PIT: o.add(Op.at(Kind.MINE, 1, -1, -1)); o.add(Op.at(Kind.MINE, 1, -1, -2)); o.add(Op.n(Kind.THROW, 1)); break;
            // ---- housing (beginner)
            case RIGHT_FORK_BLOCK_IN: o.add(Op.at(Kind.PLACE, -1, 0, 0)); o.add(Op.at(Kind.PLACE, -1, 0, 1)); o.add(Op.at(Kind.PLACE, -2, 0, 0)); o.add(Op.at(Kind.PLACE, -2, 0, 1)); break;
            case DROP_PIGLINS: o.add(Op.at(Kind.MINE, 1, 0, 2)); o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 1, 0, 1)); break;
            case DISTRACT_HALF_GOLD: o.add(Op.n(Kind.THROW, 0)); break;
            case ONE_BY_ONE: o.add(Op.at(Kind.MINE, 0, 0, -1)); o.add(Op.at(Kind.MOVE, 0, 0, -1)); o.add(Op.at(Kind.PLACE, 0, 0, 1)); break;
            case DIG_DOWN_3_STRIP_4:
                for (int d = 1; d <= 3; d++) { o.add(Op.at(Kind.MINE, 0, 0, -d)); o.add(Op.at(Kind.MOVE, 0, 0, -d)); }
                for (int i = 1; i <= 4; i++) { o.add(Op.at(Kind.MINE, i, 0, -3)); o.add(Op.at(Kind.MINE, i, 0, -2)); o.add(Op.at(Kind.MOVE, i, 0, -3)); }
                break;
            case MINE_WALL_GOLD: o.add(Op.n(Kind.PUSH_ITEMS, 0)); break;
            case T_TUNNEL_GOLD:
                o.add(Op.at(Kind.MINE, 4, -1, -3)); o.add(Op.at(Kind.MINE, 4, -1, -2)); o.add(Op.at(Kind.MINE, 4, 1, -3)); o.add(Op.at(Kind.MINE, 4, 1, -2));
                o.add(Op.n(Kind.PUSH_ITEMS, 0));
                break;
            case UNSTICK_BY_MINING: o.add(Op.n(Kind.UNSTICK, 0)); break;
            // ---- housing (intermediate / lava)
            case BREAK_CHEST_AGGRO_THROW: o.add(Op.n(Kind.THROW, 1)); break;
            case GOLD_ELEVATOR:
                // fill the 3 blocks above the pit column, a cup for the piglins, then carry gold up in batches
                o.add(Op.at(Kind.PLACE, 1, 0, 0)); o.add(Op.at(Kind.PLACE, 1, 0, 1)); o.add(Op.at(Kind.PLACE, 1, 0, 2));
                o.add(Op.at(Kind.PLACE, 1, -1, 1)); o.add(Op.at(Kind.PLACE, 1, 1, 1));
                for (int b : BastionRoutes.ELEVATOR_BATCHES) o.add(Op.n(Kind.THROW, b * 9));
                break;
            case LAVA_BLOCK_GAP: o.add(new Op(Kind.BLOCK_LAVA, 0, 0, 0, 2)); o.add(Op.at(Kind.MINE, 1, 0, 0)); o.add(Op.at(Kind.MINE, 2, 0, -1)); break;
            case STAIR_2: o.add(Op.at(Kind.MINE, 1, 0, -1)); o.add(Op.at(Kind.MINE, 2, 0, -2)); break;
            case BLOCK_LOWER_LAVA: o.add(new Op(Kind.BLOCK_LAVA, 0, 0, 0, 3)); break;
            case LAVA_STRAGGLERS: o.add(Op.n(Kind.LAVA_ON_PIGLIN, 0)); break;
            default: break;
        }
        return o;
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

    /** Walks the ops of one step. Each op gets a tick budget; a failed or timed-out op is skipped, not retried forever. */
    public static final class Runner {
        public static final int OP_TICKS = 200;
        private final List<Op> ops;
        private int i, since, skipped;

        public Runner(List<Op> ops) { this.ops = ops; }

        public Op current() { return i < ops.size() ? ops.get(i) : null; }
        public boolean done() { return i >= ops.size(); }
        public int skipped() { return skipped; }
        public int index() { return i; }

        /** Feed the executor's result for the current op this tick. */
        public void report(Result r) {
            if (done()) return;
            since++;
            if (r == Result.DONE) { i++; since = 0; }
            else if (r == Result.FAILED || since > OP_TICKS) { i++; since = 0; skipped++; }
        }
    }
}
