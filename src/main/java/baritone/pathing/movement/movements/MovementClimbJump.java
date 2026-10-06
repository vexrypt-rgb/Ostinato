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
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.ClimbTemplates;
import baritone.pathing.kinematic.JumpSearch;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catch a ladder or vine in mid air across a gap of one to three blocks ({@link ClimbTemplates#GRAB}), or let go of one
 * and land on a ledge or another ladder ({@link ClimbTemplates#LEAP}). Planned from the templates by checking the swept
 * cells and that the ladder is stuck to the wall the template was found for; flown with {@link JumpSearch} against the
 * real world, like {@link MovementJump}.
 * <p>
 * Idea (ladder and vine jumps) from Soprano's ClimbJump, https://github.com/AverWasTaken/soprano; this version is found by simulation.
 * You can't start sprinting while you hang on to something, so a leap is short; a grab can have a run-up.
 */
public class MovementClimbJump extends Movement {

    /** Moves slots; slot k takes the k-th feasible climb jump from a node. */
    public static final int SLOTS = 6;

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
        final ClimbTemplates.Template t;
        final int frame;
        final double cost;
        final int x, y, z;

        Option(ClimbTemplates.Template t, int frame, double cost, int x, int y, int z) {
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

    private final ClimbTemplates.Template t;
    private final int[] f;
    private JumpSearch js;
    private PlayerSim real;
    private boolean running, landed;
    private int settle, waited;
    private int replans, replanCooldown;

    private MovementClimbJump(IBaritone baritone, BetterBlockPos src, ClimbTemplates.Template t, int frame) {
        super(baritone, src, at(src, FRAMES[frame], t.a, t.dy, t.b), EMPTY);
        this.t = t;
        this.f = FRAMES[frame];
    }

    private static BetterBlockPos at(BetterBlockPos src, int[] f, int a, int y, int b) {
        return new BetterBlockPos(src.x + a * f[0] + b * f[2], src.y + y, src.z + a * f[1] + b * f[3]);
    }

    /** World (dx, dz) a wall of the template's side lies in, for frame {@code f}. */
    private static int[] wallDir(int[] f, int wall) {
        switch (wall) {
            case ClimbTemplates.AHEAD: return new int[]{f[0], f[1]};
            case ClimbTemplates.BEHIND: return new int[]{-f[0], -f[1]};
            case ClimbTemplates.LEFT: return new int[]{f[2], f[3]};
            default: return new int[]{-f[2], -f[3]};
        }
    }

    /** Whether {@code state} is a ladder or vine stuck to the wall at horizontal offset (dx, dz). */
    private static boolean stuckTo(BlockState state, int dx, int dz) {
        if (state.getBlock() == Blocks.LADDER) {
            Direction wall = state.getValue(LadderBlock.FACING).getOpposite();
            return wall.getStepX() == dx && wall.getStepZ() == dz;
        }
        if (state.getBlock() == Blocks.VINE) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (d.getStepX() == dx && d.getStepZ() == dz) {
                    return state.getValue(VineBlock.getPropertyForFace(d));
                }
            }
        }
        return false;
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
        if (!context.allowParkour || !context.allowClimbJumps) {
            return c.options;
        }
        BlockState here = context.get(x, y, z);
        boolean onLadder = here.getBlock() == Blocks.LADDER || here.getBlock() == Blocks.VINE;
        boolean onGround = !onLadder && MovementHelper.canWalkOn(context, x, y - 1, z);
        if (!onLadder && !onGround) {
            return c.options;
        }
        for (int i = 0; i < FRAMES.length; i++) {
            int[] f = FRAMES[i];
            templates:
            for (ClimbTemplates.Template t : ClimbTemplates.ALL) {
                if ((t.mode == ClimbTemplates.LEAP) != onLadder) {
                    continue;
                }
                int dx = x + t.a * f[0] + t.b * f[2], dz = z + t.a * f[1] + t.b * f[3], dy = y + t.dy;
                Long until = FAILED.get(failKey(x, y, z, dx, dy, dz));
                if (until != null && until > System.currentTimeMillis()) {
                    continue;
                }
                int[] wall = wallDir(f, t.wall);
                if (t.mode == ClimbTemplates.LEAP && !stuckTo(here, wall[0], wall[1])) {
                    continue;
                }
                BlockState dest = context.get(dx, dy, dz);
                if (t.destLadder) {
                    if (!stuckTo(dest, wall[0], wall[1])) {
                        continue;
                    }
                } else if (!MovementHelper.canWalkOn(context, dx, dy - 1, dz)) {
                    continue;
                }
                if (t.mode == ClimbTemplates.GRAB) {
                    for (int r = 1; r <= t.runUp; r++) {
                        if (!MovementHelper.canWalkOn(context, x - r * f[0], y - 1, z - r * f[1])) {
                            continue templates;
                        }
                    }
                }
                for (int[] cell : t.cells) {
                    if (cell[3] == 2) {
                        continue;
                    }
                    if (MovementHelper.fullyPassable(context, x + cell[0] * f[0] + cell[2] * f[2], y + cell[1], z + cell[0] * f[1] + cell[2] * f[3]) == (cell[3] == 1)) {
                        continue templates;
                    }
                }
                double cost = (t.ticks + t.runUp * WALK_ONE_BLOCK_COST + context.jumpPenalty) * context.jumpBias;
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

    public static MovementClimbJump cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        if (slot >= o.size()) {
            return null;
        }
        return new MovementClimbJump(context.getBaritone(), src, o.get(slot).t, o.get(slot).frame);
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
            if (cell[3] != 1) set.add(at(src, f, cell[0], cell[1], cell[2]));
        }
        if (t.mode == ClimbTemplates.GRAB) {
            for (int r = 0; r <= t.runUp; r++) {
                set.add(at(src, f, -r, 0, 0));
            }
        }
        set.add(src);
        set.add(dest);
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // hanging on a ladder is as safe as it gets; in the air or running up is not
        return state.getStatus() != MovementStatus.RUNNING || !running && (t.mode == ClimbTemplates.LEAP || ctx.player().onGround());
    }

    private void fail(String why) {
        FAILED.put(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis() + 30_000);
        logDebug(why + " (" + src + " -> " + dest + ", at " + ctx.player().position() + "); avoiding this climb jump for 30s");
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
            trace = new SimTrace("climb " + (t.mode == ClimbTemplates.LEAP ? "leap" : "grab") + " wall=" + t.wall + " " + t.a + "," + t.dy + "," + t.b + (t.destLadder ? " ladder" : ""), new ClientWorld(ctx));
        }
        trace.observe(real, ctx.player().getYRot());
        trace.commit(real, yaw, in, in > 0 && t.mode != ClimbTemplates.LEAP, jump);
    }

    private MovementState update0(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        boolean leap = t.mode == ClimbTemplates.LEAP;
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(src.y, dest.y) - 0.6) {
            fail("fell off");
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
            js.grab = t.destLadder;
            js.noJump = leap;
            js.jumped = leap;
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
            settle++;
            if (t.destLadder) {
                return state.setStatus(MovementStatus.SUCCESS); // hanging on: the next movement climbs
            }
            if (ctx.playerFeet().equals(dest) && (settle > 3 || Math.abs(m.x) + Math.abs(m.z) < 0.03)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (settle > 3) {
                MovementHelper.moveTowards(ctx, state, dest);
            }
            return state;
        }
        if (!running) {
            if (leap) {
                // we arrive still climbing: hold still on the ladder (sneaking stops the slide), then go
                if (!ctx.playerFeet().equals(src) || !real.climbable()) {
                    if (++waited > 40) {
                        fail("never reached the ladder");
                        return state.setStatus(MovementStatus.UNREACHABLE);
                    }
                    return state;
                }
                if (Math.abs(m.y) > 0.03 && waited++ < 20) {
                    return state.setInput(Input.SNEAK, true);
                }
                if (!js.search(real, true) && !js.search(real, false)) {
                    fail("no leap from here");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                running = true;
            } else {
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
                    fail("no climb jump from here");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                running = true;
                ctx.player().connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, false));
            }
        } else if (replanCooldown > 0) {
            replanCooldown--;
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            if (++replans > 4) {
                fail("climb jump keeps diverging from plan: v=" + m + " ground=" + real.onGround);
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            js.search(real, true);
            replanCooldown = 3;
        }
        int in = js.input(js.plan, js.jumped, js.airTicks);
        boolean jump = js.jump(js.plan, real, js.jumped);
        trace(js.yaw(js.plan, js.jumped, js.airTicks), in, jump);
        state.setTarget(new MovementState.MovementTarget(new Rotation(js.yaw(js.plan, js.jumped, js.airTicks), ctx.playerRotations().getPitch()), true));
        state.setInput(Input.MOVE_FORWARD, in > 0);
        state.setInput(Input.MOVE_BACK, in < 0);
        state.setInput(Input.SPRINT, in > 0 && !leap);
        state.setInput(Input.JUMP, jump);
        if (!js.jumped && jump && real.x * js.dirX + real.z * js.dirZ >= js.edge + JumpSearch.EDGE[js.plan[2]]) {
            js.jumped = true;
            js.airTicks = 0;
        } else if (js.jumped) {
            js.airTicks++;
            boolean grabbed = t.destLadder && js.airTicks > 1 && ctx.playerFeet().equals(dest) && real.climbable();
            if (grabbed || !t.destLadder && js.airTicks > 1 && real.onGround && !(leap && js.airTicks < 3)) {
                landed = true;
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, false);
                state.setInput(Input.SPRINT, false);
            }
        }
        return state;
    }
}
