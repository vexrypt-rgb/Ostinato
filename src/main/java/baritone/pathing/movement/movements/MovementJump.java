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
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.JumpSearch;
import baritone.pathing.kinematic.JumpTemplates;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A parkour jump from {@link JumpTemplates}: diagonal, knight's-move, down-and-across, 4-block flat, 3-block up and
 * neo jumps round the end of a wall. Planned by checking the template's swept cells, flown with {@link JumpSearch}
 * against the real world so the controls match the ones the jump was found with.
 */
public class MovementJump extends Movement {

    /** Moves slots; slot k takes the k-th feasible jump from a node. */
    public static final int SLOTS = 24;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    /** Approach (ux, uz) and lateral (lx, lz) of the 8 frames: 4 directions, lateral side either way. */
    private static final int[][] FRAMES = new int[8][];

    static {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int i = 0; i < 8; i++) {
            int ux = dirs[i >> 1][0], uz = dirs[i >> 1][1], s = (i & 1) == 0 ? 1 : -1;
            FRAMES[i] = new int[]{ux, uz, -uz * s, ux * s, s};
        }
    }

    private static final class Option {
        final JumpTemplates.Template t;
        final int frame;
        final double cost;
        final int x, y, z;

        Option(JumpTemplates.Template t, int frame, double cost, int x, int y, int z) {
            this.t = t;
            this.frame = frame;
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

    /** Jumps (src, dest) that failed live, and when they may be tried again; stops a replan loop onto the same jump. */
    private static final java.util.Map<Long, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();

    private static long failKey(int x, int y, int z, int dx, int dy, int dz) {
        return BetterBlockPos.longHash(x, y, z) * 31 + BetterBlockPos.longHash(dx, dy, dz);
    }

    private MovementState fail(MovementState state, String why) {
        FAILED.put(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis() + 30_000);
        logDebug(why + " (" + src + " -> " + dest + ", at " + ctx.player().position() + "); avoiding this jump for 30s");
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private final JumpTemplates.Template t;
    private final int[] f;
    private JumpSearch js;
    private PlayerSim real;
    private boolean running, landed;
    private int settle;
    private int replans, replanCooldown;

    public int[] frame() {
        return f;
    }

    public int runUp() {
        return t.runUp;
    }

    public double lateral() {
        return t.lateral;
    }

    public int[] plan() {
        return t.plan;
    }

    public void markFailed() {
        FAILED.put(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis() + 30_000);
    }

    public double[] point(double a, double b) {
        return new double[]{wx(a, b), wz(a, b)};
    }

    private MovementJump(IBaritone baritone, BetterBlockPos src, JumpTemplates.Template t, int frame) {
        super(baritone, src, at(src, FRAMES[frame], t.a, t.dy, t.b), EMPTY, at(src, FRAMES[frame], t.a, t.dy - 1, t.b));
        this.t = t;
        this.f = FRAMES[frame];
    }

    private static BetterBlockPos at(BetterBlockPos src, int[] f, int a, int y, int b) {
        return new BetterBlockPos(src.x + a * f[0] + b * f[2], src.y + y, src.z + a * f[1] + b * f[3]);
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
        if (!context.allowParkour) {
            return c.options;
        }
        for (int i = 0; i < FRAMES.length; i++) {
            int[] f = FRAMES[i];
            // straight ahead is walkable: nothing to jump over
            if (MovementHelper.canWalkOn(context, x + f[0], y - 1, z + f[1]) && MovementHelper.fullyPassable(context, x + f[0], y, z + f[1])) {
                continue;
            }
            templates:
            for (JumpTemplates.Template t : JumpTemplates.ALL) {
                int dx = x + t.a * f[0] + t.b * f[2], dz = z + t.a * f[1] + t.b * f[3];
                if (!MovementHelper.canWalkOn(context, dx, y + t.dy - 1, dz)) {
                    continue;
                }
                Long until = FAILED.get(failKey(x, y, z, dx, y + t.dy, dz));
                if (until != null && until > System.currentTimeMillis()) {
                    continue;
                }
                for (int r = 1; r <= t.runUp; r++) {
                    if (!MovementHelper.canWalkOn(context, x - r * f[0], y - 1, z - r * f[1])) {
                        continue templates;
                    }
                }
                for (int[] cell : t.cells) {
                    if (MovementHelper.fullyPassable(context, x + cell[0] * f[0] + cell[2] * f[2], y + cell[1], z + cell[0] * f[1] + cell[2] * f[3]) == (cell[3] == 1)) {
                        continue templates;
                    }
                }
                double cost = t.ticks + t.runUp * WALK_ONE_BLOCK_COST + context.jumpPenalty;
                int ddx = dx, ddy = y + t.dy, ddz = dz;
                // one option per landing block (the cheapest): the planner only has a fixed number of jump slots
                int same = -1;
                for (int k = 0; k < c.options.size() && same < 0; k++) {
                    Option o = c.options.get(k);
                    if (o.x == ddx && o.y == ddy && o.z == ddz) same = k;
                }
                if (same < 0) {
                    c.options.add(new Option(t, i, cost, ddx, ddy, ddz));
                } else if (cost < c.options.get(same).cost) {
                    c.options.set(same, new Option(t, i, cost, ddx, ddy, ddz));
                }
            }
        }
        return c.options;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int slot, MutableMoveResult res) {
        List<Option> o = options(context, x, y, z);
        if (slot >= o.size()) {
            return;
        }
        Option op = o.get(slot);
        int[] f = FRAMES[op.frame];
        res.x = x + op.t.a * f[0] + op.t.b * f[2];
        res.y = y + op.t.dy;
        res.z = z + op.t.a * f[1] + op.t.b * f[3];
        res.cost = op.cost;
    }

    public static MovementJump cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        if (slot >= o.size()) {
            return null;
        }
        return new MovementJump(context.getBaritone(), src, o.get(slot).t, o.get(slot).frame);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        CACHE.get().context = null; // the world may have changed since
        for (Option o : options(context, src.x, src.y, src.z)) {
            if (o.t == t && FRAMES[o.frame] == f) {
                return o.cost;
            }
        }
        return COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int[] cell : t.cells) {
            if (cell[3] == 0) set.add(at(src, f, cell[0], cell[1], cell[2]));
        }
        for (int r = 0; r <= t.runUp; r++) {
            set.add(at(src, f, -r, 0, 0));
        }
        set.add(dest);
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || !running;
    }

    /** World x/z of frame point (a, b), block (0, 0) being src. */
    private double wx(double a, double b) {
        return src.x + 0.5 + (a - 0.5) * f[0] + (b - 0.5) * f[2];
    }

    private double wz(double a, double b) {
        return src.z + 0.5 + (a - 0.5) * f[1] + (b - 0.5) * f[3];
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(src.y, dest.y) - 0.6) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (js == null) {
            ClientWorld world = new ClientWorld(ctx);
            js = new JumpSearch(world);
            real = new PlayerSim(world);
            js.dirX = f[0];
            js.dirZ = f[1];
            js.edge = (src.x + 0.5 + 0.5 * f[0]) * f[0] + (src.z + 0.5 + 0.5 * f[1]) * f[1];
            js.destX = dest.x;
            js.destY = dest.y;
            js.destZ = dest.z;
            js.side = f[4];
            System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
        }
        Vec3 m = ctx.player().getDeltaMovement();
        real.x = p.x;
        real.y = p.y;
        real.z = p.z;
        real.vx = m.x;
        real.vy = m.y;
        real.vz = m.z;
        real.onGround = ctx.player().onGround();
        real.sprinting = ctx.player().isSprinting();
        real.collidedH = ctx.player().horizontalCollision;
        if (landed) {
            // let the landing settle, then step to the middle for the next movement
            // (a neo lands hanging over the side, feet outside dest: step in once the landing has settled)
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
            // walk to the run-up start, standing clear of the edge so the box stays on the block
            double lat = Math.min(t.lateral, 0.75);
            double tx = wx(-t.runUp + 0.5, 0.5 + lat), tz = wz(-t.runUp + 0.5, 0.5 + lat);
            double dx = tx - p.x, dz = tz - p.z;
            if (dx * dx + dz * dz > 0.15 * 0.15) {
                state.setTarget(new MovementState.MovementTarget(new Rotation((float) Math.toDegrees(Math.atan2(-dx, dz)), ctx.playerRotations().getPitch()), true));
                if (dx * dx + dz * dz > 0.05 || Math.abs(m.x) + Math.abs(m.z) < 0.05) {
                    state.setInput(Input.MOVE_FORWARD, true);
                }
                return state;
            }
            if (!real.onGround) {
                return state;
            }
            boolean still = Math.abs(m.x) + Math.abs(m.z) <= 0.02;
            if (!still && !js.search(real, true)) {
                return state; // no plan from this momentum: come to a stop first
            }
            if (still && !js.search(real, true) && !js.search(real, false)) {
                return fail(state, "no jump from here");
            }
            running = true;
            // the client only reports moves over 0.03, and a neo starts ~0.01 off the wall: sync the server's copy of our
            // position first, or it replays the jump from a stale spot inside the wall and rubber-bands us back
            ctx.player().connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, false));
        } else if (replanCooldown > 0) {
            replanCooldown--;
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            // drifted off the plan: replan around it, else fly it anyway. A full search is expensive, so if the player
            // keeps diverging (it isn't following the inputs) give up and let the path replan instead of searching every tick.
            if (++replans > 4) {
                return fail(state, "jump keeps diverging from plan: v=" + m + " sprint=" + real.sprinting + " ground=" + real.onGround);
            }
            js.search(real, true);
            replanCooldown = 3;
        }
        int in = js.input(js.plan, js.jumped, js.airTicks);
        boolean jump = js.jump(js.plan, real, js.jumped);
        state.setTarget(new MovementState.MovementTarget(new Rotation(js.yaw(js.plan, js.jumped, js.airTicks), ctx.playerRotations().getPitch()), true));
        state.setInput(Input.MOVE_FORWARD, in > 0);
        state.setInput(Input.MOVE_BACK, in < 0);
        state.setInput(Input.SPRINT, in > 0);
        state.setInput(Input.JUMP, jump);
        if (jump && real.x * js.dirX + real.z * js.dirZ >= js.edge + JumpSearch.EDGE[js.plan[2]]) {
            js.jumped = true;
            js.airTicks = 0;
        } else if (js.jumped) {
            js.airTicks++;
            if (js.airTicks > 1 && real.onGround) {
                landed = true;
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, false);
                state.setInput(Input.SPRINT, false);
            }
        }
        return state;
    }
}
