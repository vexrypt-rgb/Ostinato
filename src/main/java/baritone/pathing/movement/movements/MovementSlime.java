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
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.JumpSearch;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drop or jump off an edge onto a slime block below and ride the bounce onto a block further on. Slime pads are
 * rare, so unlike {@link MovementJump} the jump is found by simulating plans against the planner's world whenever
 * one is in reach, and flown with {@link JumpSearch} like a parkour jump. Ostinato: the pad may lie off to the side
 * (within a forward cone) and the landing off the line to it: angled take-offs and mid-air turns are searched too.
 */
public class MovementSlime extends Movement {

    public static final int SLOTS = 4;
    /** How far ahead the pad may start and how far below the feet it may be. */
    private static final int REACH = 8, DEPTH = 12;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private static final class Option {
        final int dir, side, bounceY, x, y, z;
        final int[] plan;
        final boolean noJump;
        final double cost;

        Option(int dir, int side, int bounceY, int[] plan, boolean noJump, double cost, int x, int y, int z) {
            this.dir = dir;
            this.side = side;
            this.noJump = noJump;
            this.bounceY = bounceY;
            this.plan = plan;
            this.cost = cost;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Cache {
        Object context;
        int x, y, z;
        final List<Option> options = new ArrayList<>();
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private final Option o;
    private final Set<BetterBlockPos> swept;
    private JumpSearch js;
    private PlayerSim real;
    private boolean running, landed;
    private int settle;

    private MovementSlime(IBaritone baritone, BetterBlockPos src, Option o, Set<BetterBlockPos> swept) {
        super(baritone, src, new BetterBlockPos(o.x, o.y, o.z), EMPTY, new BetterBlockPos(o.x, o.y - 1, o.z));
        this.o = o;
        this.swept = swept;
    }

    private static JumpSearch search(PlayerSim.World world, int x, int y, int z, int dir, int side, int bounceY) {
        JumpSearch js = new JumpSearch(world);
        js.side = side;
        int[] d = DIRS[dir];
        js.dirX = d[0];
        js.dirZ = d[1];
        js.edge = (x + 0.5 + 0.5 * d[0]) * d[0] + (z + 0.5 + 0.5 * d[1]) * d[1];
        js.bounceY = bounceY;
        return js;
    }

    private static List<Option> options(CalculationContext context, int x, int y, int z) {
        Cache c = CACHE.get();
        if (c.context == context && c.x == x && c.y == y && c.z == z) {
            return c.options;
        }
        c.context = context;
        c.x = x;
        c.y = y;
        c.z = z;
        c.options.clear();
        if (!context.allowParkour || !MovementHelper.canWalkOn(context, x, y - 1, z) || context.get(x, y - 1, z).getBlock() == Blocks.SLIME_BLOCK) {
            return c.options;
        }
        for (int dir = 0; dir < DIRS.length; dir++) {
            int[] pad = pad(context, x, y, z, DIRS[dir]);
            if (pad == null) {
                continue;
            }
            long key = BetterBlockPos.longHash(x, y, z) * 4 + dir;
            List<Option> known = known(key);
            if (known == null) {
                known = new ArrayList<>();
                search(context, x, y, z, dir, pad[0], pad[1], known);
                KNOWN.put(key, known);
            }
            for (Option opt : known) {
                int same = -1;
                for (int k = 0; k < c.options.size() && same < 0; k++) {
                    Option q = c.options.get(k);
                    if (q.x == opt.x && q.y == opt.y && q.z == opt.z) same = k;
                }
                if (same < 0) {
                    c.options.add(opt);
                } else if (opt.cost < c.options.get(same).cost) {
                    c.options.set(same, opt);
                }
            }
        }
        // the planner has SLOTS slots: keep the cheapest landings
        c.options.sort((a, b) -> Double.compare(a.cost, b.cost));
        while (c.options.size() > SLOTS) {
            c.options.remove(c.options.size() - 1);
        }
        return c.options;
    }

    /** Ostinato: bounce searches per (node, direction), kept ~30 s: the planner revisits edge nodes on every replan. */
    private static final java.util.Map<Long, List<Option>> KNOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long knownBorn = System.currentTimeMillis();

    private static List<Option> known(long key) {
        long now = System.currentTimeMillis();
        if (now - knownBorn > 30000) {
            KNOWN.clear();
            knownBorn = now;
        }
        return KNOWN.get(key);
    }

    /** Every take-off yaw, mid-air turn and release over a pad in reach; the landings found are added to {@code out}. */
    private static void search(CalculationContext context, int x, int y, int z, int dir, int bounceY, int lateral, List<Option> out) {
        PlayerSim start = new PlayerSim(new BsiWorld(context.bsi));
        start.x = x + 0.5;
        start.y = MovementParkour.standingY(context, x, y, z);
        start.z = z + 0.5;
        start.onGround = true;
        start.vy = -0.0784000015258789; // Ostinato: vanilla's resting value, as the templates: at 0 the first tick counts as airborne
        int[] p = new int[JumpSearch.DIMS];
        // a pad straight ahead keeps the straight plans (O0 = O1 = 0); one off to the side turns towards it, the
        // yaw offsets mirrored to the side it is on
        boolean straight = lateral == 0;
        int side = lateral < 0 ? -1 : 1; // a positive yaw offset turns right of d, where a positive lateral lies
        JumpSearch js = search(new BsiWorld(context.bsi), x, y, z, dir, side, bounceY);
        js.anyDest = true;
        for (p[0] = straight ? 1 : 0; p[0] < (straight ? 2 : JumpSearch.O0.length); p[0]++)
            for (p[3] = straight ? JumpSearch.O1_STRAIGHT : 0; p[3] < (straight ? JumpSearch.O1_STRAIGHT + 1 : JumpSearch.O1.length); p[3]++)
                for (p[4] = 0; p[4] < (straight ? 1 : JumpSearch.SWITCH.length); p[4]++) {
                    if (!straight && Math.abs(JumpSearch.O0[p[0]] - JumpSearch.O1[p[3]]) > 30) {
                        continue; // the flight goes where O1 points: a run-up far off it only loses speed
                    }
                    // walk off the edge (the hop and edge choices are moot then), or sprint-jump off it
                    for (int nj = 1; nj >= 0; nj--) for (p[1] = 0; p[1] < (nj == 1 || !straight ? 1 : JumpSearch.HOP.length); p[1]++)
                        for (p[2] = 0; p[2] < (nj == 1 ? 1 : JumpSearch.PLAIN_EDGES); p[2]++)
                            for (p[5] = 0; p[5] < JumpSearch.RELEASE.length; p[5]++) for (p[6] = 0; p[6] < JumpSearch.BRAKE.length; p[6]++) {
                                if (p[5] == 0 && p[6] == 1) {
                                    continue;
                                }
                                js.noJump = nj == 1;
                                js.destY = bounceY;
                                if (!js.run(start, p, false, 0, null)) {
                                    continue;
                                }
                                int dx = js.destX, dy = js.destY, dz = js.destZ;
                                if ((dx == x && dz == z) || dy <= bounceY || !MovementHelper.canWalkOn(context, dx, dy - 1, dz) || context.get(dx, dy - 1, dz).getBlock() == Blocks.SLIME_BLOCK) {
                                    continue; // back where we started, or still down on the pad
                                }
                                double cost = js.ticks + context.jumpPenalty + js.miss;
                                int same = -1;
                                for (int k = 0; k < out.size() && same < 0; k++) {
                                    Option q = out.get(k);
                                    if (q.x == dx && q.y == dy && q.z == dz) same = k;
                                }
                                Option opt = new Option(dir, side, bounceY, p.clone(), js.noJump, cost, dx, dy, dz);
                                if (same < 0) {
                                    out.add(opt);
                                } else if (cost < out.get(same).cost) {
                                    out.set(same, opt);
                                }
                            }
                }
    }

    /**
     * Feet height on a slime pad ahead in direction d below an open drop, and its offset to the side (+ to the
     * right of d, 0 straight ahead), or null if there is none. Ostinato: off to the side too, up to about 40 degrees.
     */
    private static int[] pad(CalculationContext context, int x, int y, int z, int[] d) {
        if (!MovementHelper.fullyPassable(context, x + d[0], y, z + d[1]) || !MovementHelper.fullyPassable(context, x + d[0], y + 1, z + d[1])) {
            return null;
        }
        for (int k = 1; k <= REACH; k++) {
            for (int a = 0; a <= Math.min(k, k * 3 / 5 + 1); a++) for (int sgn = 1; sgn >= (a == 0 ? 1 : -1); sgn -= 2) {
                int l = a * sgn;
                int cx = x + k * d[0] - l * d[1], cz = z + k * d[1] + l * d[0];
                if (!MovementHelper.fullyPassable(context, cx, y, cz) || !MovementHelper.fullyPassable(context, cx, y + 1, cz)) {
                    if (l == 0) {
                        return null; // straight ahead as before: an open drop all the way
                    }
                    continue;
                }
                for (int yy = y - 1; yy >= y - DEPTH; yy--) {
                    if (MovementHelper.fullyPassable(context, cx, yy, cz)) {
                        continue;
                    }
                    if (context.get(cx, yy, cz).getBlock() == Blocks.SLIME_BLOCK && yy <= y - 2) {
                        return new int[]{yy + 1, l};
                    }
                    break; // ground or the floor far below: look at the next column
                }
            }
        }
        return null;
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

    public static MovementSlime cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        if (slot >= o.size()) {
            return null;
        }
        Option op = o.get(slot);
        // the blocks the planned flight passes through: the executor must not count them as off the path
        Set<BetterBlockPos> swept = new HashSet<>();
        JumpSearch js = search(new BsiWorld(context.bsi), src.x, src.y, src.z, op.dir, op.side, op.bounceY);
        js.noJump = op.noJump;
        js.destX = op.x;
        js.destY = op.y;
        js.destZ = op.z;
        PlayerSim start = new PlayerSim(new BsiWorld(context.bsi));
        start.x = src.x + 0.5;
        start.y = MovementParkour.standingY(context, src.x, src.y, src.z);
        start.z = src.z + 0.5;
        start.onGround = true;
        start.vy = -0.0784000015258789;
        js.run(start, op.plan, false, 0, (bx, by, bz) -> {
            swept.add(new BetterBlockPos(PlayerSim.floor(bx), PlayerSim.floor(by), PlayerSim.floor(bz)));
            swept.add(new BetterBlockPos(PlayerSim.floor(bx), PlayerSim.floor(by) + 1, PlayerSim.floor(bz)));
        });
        return new MovementSlime(context.getBaritone(), src, op, swept);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        CACHE.get().context = null; // the world may have changed since
        for (Option q : options(context, src.x, src.y, src.z)) {
            if (q.x == dest.x && q.y == dest.y && q.z == dest.z) {
                return q.cost;
            }
        }
        return COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>(swept);
        set.add(src);
        set.add(dest);
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || !running;
    }

    /** The executor runs a movement again after a setback; a flight that was under way must not be taken as flown. */
    @Override
    public void reset() {
        super.reset();
        js = null;
        real = null;
        running = landed = false;
        settle = 0;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(o.bounceY, dest.y) - 0.6) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (js == null) {
            ClientWorld world = new ClientWorld(ctx);
            js = search(world, src.x, src.y, src.z, o.dir, o.side, o.bounceY);
            js.noJump = o.noJump;
            js.destX = dest.x;
            js.destY = dest.y;
            js.destZ = dest.z;
            real = new PlayerSim(world);
            System.arraycopy(o.plan, 0, js.plan, 0, JumpSearch.DIMS);
        }
        Vec3 m = ctx.player().getDeltaMovement();
        ClientWorld.readPlayer(ctx, real);
        if (landed) {
            settle++;
            if (ctx.playerFeet().equals(dest) && (settle > 3 || Math.abs(m.x) + Math.abs(m.z) < 0.03)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (settle > 3) {
                MovementHelper.moveTowards(ctx, state, dest);
            }
            return state;
        }
        if (!running) {
            // start from the middle of src, standing still, as planned
            double dx = src.x + 0.5 - p.x, dz = src.z + 0.5 - p.z;
            if (dx * dx + dz * dz > 0.15 * 0.15) {
                state.setTarget(new MovementState.MovementTarget(new Rotation((float) Math.toDegrees(Math.atan2(-dx, dz)), ctx.playerRotations().getPitch()), true));
                if (dx * dx + dz * dz > 0.05 || Math.abs(m.x) + Math.abs(m.z) < 0.05) {
                    state.setInput(Input.MOVE_FORWARD, true);
                }
                return state;
            }
            if (Math.abs(m.x) + Math.abs(m.z) > 0.02 || !real.onGround) {
                return state;
            }
            float first = js.yaw(js.plan, false, 0);
            float off = ((first - ctx.player().getYRot()) % 360 + 540) % 360 - 180;
            if (Math.abs(off) > 8) {
                // Ostinato: an angled take-off: face the plan's first yaw standing still, the run-up assumes it
                state.setTarget(new MovementState.MovementTarget(new Rotation(first, ctx.playerRotations().getPitch()), true));
                return state;
            }
            if (!js.run(real, js.plan, false, 0, null) && !js.search(real, true) && !js.searchStraight(real)) {
                logDebug("no bounce from here (" + String.format(java.util.Locale.ROOT, "%.3f, %.3f", p.x, p.z) + ")");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            running = true;
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            js.search(real, true); // drifted off the plan: replan around it, else fly it anyway
        }
        int in = js.input(js.plan, js.jumped, js.airTicks);
        boolean jump = js.jump(js.plan, real, js.jumped);
        state.setTarget(new MovementState.MovementTarget(new Rotation(js.yaw(js.plan, js.jumped, js.airTicks), ctx.playerRotations().getPitch()), true));
        state.setInput(Input.MOVE_FORWARD, in > 0);
        state.setInput(Input.MOVE_BACK, in < 0);
        state.setInput(Input.SPRINT, in > 0);
        state.setInput(Input.JUMP, jump);
        if ((jump && real.x * js.dirX + real.z * js.dirZ >= js.edge + JumpSearch.EDGE[js.plan[2]]) || (!js.jumped && !real.onGround && real.x * js.dirX + real.z * js.dirZ > js.edge)) {
            js.jumped = true;
            js.airTicks = 0;
        } else if (js.jumped) {
            js.airTicks++;
            // touching the pad bounces straight back up; only settling on dest's floor ends the flight
            if (js.airTicks > 1 && real.onGround && m.y <= 0.1 && PlayerSim.feetCell(p.y) == dest.y) {
                landed = true;
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, false);
                state.setInput(Input.SPRINT, false);
            }
        }
        return state;
    }
}
