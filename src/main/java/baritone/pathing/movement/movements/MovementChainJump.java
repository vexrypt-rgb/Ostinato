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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.pathing.kinematic.SimTrace;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.ChainTemplates;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.JumpSearch;
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
 * Idea (jump pairs through a one block pad) from Soprano's momentum jumps, https://github.com/AverWasTaken/soprano.
 * Two jumps through a one block pad from {@link ChainTemplates}: land on the pad with momentum and jump again without
 * stopping, for gaps no single jump makes. Planned by checking the swept cells and the three blocks stood on, flown with
 * one {@link JumpSearch} per jump against the real world, the second one found from wherever the first actually landed.
 */
public class MovementChainJump extends Movement {

    /** Moves slots; slot k takes the k-th feasible chain from a node. */
    public static final int SLOTS = 4;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    /** Approach (ux, uz), lateral (lx, lz) and mirror side of the 8 frames. */
    private static final int[][] FRAMES = new int[8][];

    static {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int i = 0; i < 8; i++) {
            int ux = dirs[i >> 1][0], uz = dirs[i >> 1][1], s = (i & 1) == 0 ? 1 : -1;
            FRAMES[i] = new int[]{ux, uz, -uz * s, ux * s, s};
        }
    }

    private static final class Option {
        final ChainTemplates.Template t;
        final int frame;
        final double cost;
        final int x, y, z;

        Option(ChainTemplates.Template t, int frame, double cost, int x, int y, int z) {
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

    private static final java.util.Map<Long, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private static long failKey(int x, int y, int z, int dx, int dy, int dz) {
        return BetterBlockPos.longHash(x, y, z) * 31 + BetterBlockPos.longHash(dx, dy, dz);
    }

    private final ChainTemplates.Template t;
    private final int[] f;
    private final BetterBlockPos pad;
    private JumpSearch js;
    private PlayerSim real;
    private boolean running, landed, second;
    private int settle;
    private int replans, replanCooldown;

    private MovementChainJump(IBaritone baritone, BetterBlockPos src, ChainTemplates.Template t, int frame) {
        super(baritone, src, at(src, FRAMES[frame], t.a, t.dy, t.b), EMPTY);
        this.t = t;
        this.f = FRAMES[frame];
        this.pad = at(src, f, t.padA, t.padDy, 0);
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
        if (!context.allowParkour || !context.allowMomentumJumps || !context.canSprint) {
            return c.options;
        }
        for (int i = 0; i < FRAMES.length; i++) {
            int[] f = FRAMES[i];
            templates:
            for (ChainTemplates.Template t : ChainTemplates.ALL) {
                int dx = x + t.a * f[0] + t.b * f[2], dz = z + t.a * f[1] + t.b * f[3], dy = y + t.dy;
                int px = x + t.padA * f[0], pz = z + t.padA * f[1], py = y + t.padDy;
                if (!MovementHelper.canWalkOn(context, dx, dy - 1, dz) || !MovementHelper.canWalkOn(context, px, py - 1, pz)) {
                    continue;
                }
                Long until = FAILED.get(failKey(x, y, z, dx, dy, dz));
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
                double cost = (t.ticks + t.runUp * WALK_ONE_BLOCK_COST + 2 * context.jumpPenalty) * context.jumpBias;
                int same = -1;
                for (int k = 0; k < c.options.size() && same < 0; k++) {
                    Option o = c.options.get(k);
                    if (o.x == dx && o.y == dy && o.z == dz) same = k;
                }
                if (same < 0) {
                    c.options.add(new Option(t, i, cost, dx, dy, dz));
                } else if (cost < c.options.get(same).cost) {
                    c.options.set(same, new Option(t, i, cost, dx, dy, dz));
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
        res.x = op.x;
        res.y = op.y;
        res.z = op.z;
        res.cost = op.cost;
    }

    public static MovementChainJump cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        if (slot >= o.size()) {
            return null;
        }
        return new MovementChainJump(context.getBaritone(), src, o.get(slot).t, o.get(slot).frame);
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
        set.add(pad);
        set.add(dest);
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || !running;
    }

    private MovementState fail(MovementState state, String why) {
        FAILED.put(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis() + 30_000);
        logDebug(why + " (" + src + " -> " + pad + " -> " + dest + ", at " + ctx.player().position() + "); avoiding this chain for 30s");
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    /** World x/z of frame point (a, b), block (0, 0) being src. */
    private double wx(double a, double b) {
        return src.x + 0.5 + (a - 0.5) * f[0] + (b - 0.5) * f[2];
    }

    private double wz(double a, double b) {
        return src.z + 0.5 + (a - 0.5) * f[1] + (b - 0.5) * f[3];
    }

    private JumpSearch search(ClientWorld world, BetterBlockPos from, BetterBlockPos to, int[] plan) {
        JumpSearch s = new JumpSearch(world);
        s.dirX = f[0];
        s.dirZ = f[1];
        s.edge = (from.x + 0.5 + 0.5 * f[0]) * f[0] + (from.z + 0.5 + 0.5 * f[1]) * f[1];
        s.destX = to.x;
        s.destY = to.y;
        s.destZ = to.z;
        s.side = f[4];
        s.carry = to == pad;
        System.arraycopy(plan, 0, s.plan, 0, JumpSearch.DIMS);
        return s;
    }

    @Override
    public MovementState updateState(MovementState state) {
        MovementState s = update0(state);
        if (trace != null && s.getStatus() != MovementStatus.RUNNING) {
            trace.finish(s.getStatus() == MovementStatus.SUCCESS);
            trace = null;
        }
        return s;
    }

    private SimTrace trace;

    /** With kinematicTrace on, compare the sim with the real player for this tick (see {@link SimTrace}). */
    private void trace(float yaw, int in, boolean jump) {
        if (!Baritone.settings().kinematicTrace.value || !running) {
            return;
        }
        SimTrace.files = true;
        if (trace == null) {
            trace = new SimTrace("chain pad=" + t.padA + "," + t.padDy + " " + t.a + "," + t.dy + "," + t.b, new ClientWorld(ctx));
        }
        trace.observe(real, ctx.player().getYRot());
        trace.commit(real, yaw, in, in > 0, jump);
    }

    private MovementState update0(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(src.y, Math.min(pad.y, dest.y)) - 0.6) {
            return fail(state, "fell off");
        }
        if (js == null) {
            ClientWorld world = new ClientWorld(ctx);
            js = search(world, src, pad, t.plan1);
            real = new PlayerSim(world);
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
                return fail(state, "no chain from here");
            }
            running = true;
            ctx.player().connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, false));
        } else if (replanCooldown > 0) {
            replanCooldown--;
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            if (++replans > 6) {
                return fail(state, "chain keeps diverging from plan: v=" + m + " sprint=" + real.sprinting + " ground=" + real.onGround);
            }
            if (!js.search(real, true) && second && !js.search(real, false)) {
                return fail(state, "no second jump from the pad");
            }
            replanCooldown = 3;
        }
        if (!second && js.jumped && js.airTicks > 1 && real.onGround) {
            if (!ctx.playerFeet().equals(pad)) {
                return fail(state, "missed the pad");
            }
            // on the pad with the speed the first jump left us: find the second from here and carry straight on
            second = true;
            js = search(new ClientWorld(ctx), pad, dest, t.plan2);
            if (!js.search(real, true) && !js.search(real, false)) {
                return fail(state, "no second jump from the pad");
            }
            replans = 0;
            replanCooldown = 0;
        }
        int in = js.input(js.plan, js.jumped, js.airTicks);
        boolean jump = js.jump(js.plan, real, js.jumped);
        trace(js.yaw(js.plan, js.jumped, js.airTicks), in, jump);
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
            if (second && js.airTicks > 1 && real.onGround) {
                landed = true;
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, false);
                state.setInput(Input.SPRINT, false);
            }
        }
        return state;
    }
}
