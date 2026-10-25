/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ostinato: walk sideways into an upward (soul sand) bubble column, ride it up and drift off onto a ledge beside the
 * top. A column whose top has air above it throws the rider out: the ledge may be up to {@link #LAUNCH} blocks above
 * the top water block. The planner otherwise never enters a bubble column (it avoids walking into one).
 */
public class MovementBubbleLift extends Movement {

    public static final int SLOTS = 2;
    /** Highest landing (feet) above the top water block: the throw out of the column clears about four blocks. */
    static final int LAUNCH = 3;
    /** Tallest column followed. */
    private static final int MAX_RISE = 64;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final int[][] AROUND = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Option {
        final int cx, cz, top, x, y, z;
        final double cost;

        Option(int cx, int cz, int top, int x, int y, int z, double cost) {
            this.cx = cx;
            this.cz = cz;
            this.top = top;
            this.x = x;
            this.y = y;
            this.z = z;
            this.cost = cost;
        }
    }

    private final Option o;
    private int phase, ticks, settle;

    private MovementBubbleLift(IBaritone baritone, BetterBlockPos src, Option o) {
        super(baritone, src, new BetterBlockPos(o.x, o.y, o.z), EMPTY, new BetterBlockPos(o.x, o.y - 1, o.z));
        this.o = o;
    }

    static boolean upColumn(BlockState s) {
        return s.getBlock() == Blocks.BUBBLE_COLUMN && !s.getValue(BubbleColumnBlock.DRAG_DOWN);
    }

    private static List<Option> options(CalculationContext context, int x, int y, int z) {
        // Ostinato: no list unless a bubble column is next to us (almost never), so the common case allocates nothing
        if (!MovementHelper.canWalkOn(context, x, y - 1, z) || upColumn(context.get(x, y, z))) {
            return List.of();
        }
        List<Option> out = null;
        for (int[] d : DIRS) {
            int cx = x + d[0], cz = z + d[1];
            if (!upColumn(context.get(cx, y, cz)) || !upColumn(context.get(cx, y + 1, cz))) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>();
            }
            int top = y + 1;
            while (top < y + MAX_RISE && upColumn(context.get(cx, top + 1, cz))) {
                top++;
            }
            if (!context.get(cx, top + 1, cz).isAir()) {
                continue; // no throw: a column under a roof or open water only bobs at its top
            }
            for (int[] a : AROUND) {
                int lx = cx + a[0], lz = cz + a[1];
                for (int ly = top + 1; ly <= top + 1 + LAUNCH; ly++) {
                    if (!MovementHelper.fullyPassable(context, cx, ly + 1, cz)) {
                        break; // the shaft above the top is closed off: no higher landing either
                    }
                    if (!MovementHelper.canWalkOn(context, lx, ly - 1, lz) || !MovementHelper.fullyPassable(context, lx, ly, lz)
                            || !MovementHelper.fullyPassable(context, lx, ly + 1, lz) || !MovementHelper.fullyPassable(context, cx, ly + 2, cz)) {
                        continue;
                    }
                    if (a[0] != 0 && a[1] != 0 && (!MovementHelper.fullyPassable(context, cx + a[0], ly, cz) || !MovementHelper.fullyPassable(context, cx + a[0], ly + 1, cz))
                            && (!MovementHelper.fullyPassable(context, cx, ly, cz + a[1]) || !MovementHelper.fullyPassable(context, cx, ly + 1, cz + a[1]))) {
                        continue; // a diagonal ledge needs one open side to slip past the corner
                    }
                    // walk in, rise ~0.6 blocks a tick, then the throw and the drift over
                    double cost = WALK_ONE_BLOCK_COST + (top - y + 1) / 0.6 + (ly - top) * 3 + 10;
                    out.add(new Option(cx, cz, top, lx, ly, lz, cost));
                    break; // the lowest floor of this column of the rim
                }
            }
        }
        if (out == null) {
            return List.of();
        }
        out.sort((p, q) -> Double.compare(p.cost, q.cost));
        while (out.size() > SLOTS) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int slot, MutableMoveResult res) {
        List<Option> o = options(context, x, y, z);
        if (slot >= o.size()) {
            return;
        }
        Option op = o.get(slot);
        res.x = op.x;
        res.y = op.y;
        res.z = op.z;
        res.cost = op.cost;
    }

    public static MovementBubbleLift cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        return slot < o.size() ? new MovementBubbleLift(context.getBaritone(), src, o.get(slot)) : null;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        for (Option q : options(context, src.x, src.y, src.z)) {
            if (q.x == dest.x && q.y == dest.y && q.z == dest.z) {
                return q.cost;
            }
        }
        return COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        set.add(dest);
        for (int yy = src.y; yy <= dest.y + 2; yy++) {
            set.add(new BetterBlockPos(o.cx, yy, o.cz));
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || phase == 0;
    }

    @Override
    public void reset() {
        super.reset();
        phase = ticks = settle = 0;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (++ticks > 20 * 20) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Vec3 p = ctx.player().position();
        double ox = o.cx + 0.5 - p.x, oz = o.cz + 0.5 - p.z;
        BetterBlockPos column = new BetterBlockPos(o.cx, (int) Math.floor(p.y + 1e-4), o.cz);
        if (phase == 0) {
            // walk into the column; it takes over once we are in it
            if (Math.abs(ox) < 0.3 && Math.abs(oz) < 0.3) {
                phase = 1;
            } else {
                MovementHelper.moveTowards(ctx, state, column);
                return state;
            }
        }
        if (phase == 1) {
            if (p.y < o.top) {
                if (p.y < src.y - 1.5) {
                    return state.setStatus(MovementStatus.UNREACHABLE); // sank: not an up column after all
                }
                // ride it centred, swimming up too
                if (ox * ox + oz * oz > 0.2 * 0.2) {
                    MovementHelper.moveTowards(ctx, state, column);
                }
                state.setInput(Input.JUMP, true);
                return state;
            }
            phase = 2;
        }
        // thrown out of the top: press for the ledge. Its side holds us in the shaft until we clear its floor.
        if (ctx.player().onGround()) {
            if (ctx.playerFeet().equals(dest)) {
                if (++settle > 2) {
                    return state.setStatus(MovementStatus.SUCCESS);
                }
                MovementHelper.moveTowards(ctx, state, dest);
                return state;
            }
            if (p.y > o.top + 0.5) {
                return state.setStatus(MovementStatus.UNREACHABLE); // landed somewhere else
            }
        }
        if (p.y < o.top - 2) {
            phase = 1; // fell back into the column: ride it again
            return state;
        }
        MovementHelper.moveTowards(ctx, state, dest);
        return state;
    }
}
