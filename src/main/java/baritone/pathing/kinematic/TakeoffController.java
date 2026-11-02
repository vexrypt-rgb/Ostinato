package baritone.pathing.kinematic;

import baritone.utils.ExperimentalMovement;
import baritone.utils.PositionSync;
import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementJump;
import baritone.pathing.movement.movements.MovementParkour;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Slow kinematic (and kinematic travel, plain parkour only), standing takeoffs: a plain parkour jump the sprinting driver does not fly (the 1 block gap, or a run
 * that gave up). Walks back to a run-up spot, searches the exact takeoff (where to jump, when to turn, when to let go
 * of W) with {@link JumpSearch} against the real world, flies it, and steps onto the landing block's centre.
 */
public final class TakeoffController {

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final PlayerSim real;
    private JumpSearch js;
    private IMovement current;
    private boolean running, landed, inPlay, engaged;
    private int settle, ticks, cooldown;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    public TakeoffController(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        inPlay = false;
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        engaged = r >= 0 && r == pathPosition && current != null;
        if (inPlay && Baritone.settings().kinematicTrace.value) {
            // Ostinato: per-tick trace of a standing takeoff: who drives, the phase, where the player is, how it moves
            Vec3 v = ctx.player().getDeltaMovement();
            System.out.printf("KTRACE takeoff r=%d run=%b jumped=%b landed=%b air=%d cd=%d pos=%.3f,%.3f,%.3f v=%.3f,%.3f,%.3f og=%b%n", r,
                    running, js != null && js.jumped, landed, js != null ? js.airTicks : -1, cooldown,
                    ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(), v.x, v.y, v.z, ctx.player().onGround());
        }
        return r;
    }

    /**
     * Whether a takeoff has started (walking back, in flight or settling): the other movers keep out until it hands
     * over. Switching back and forth lost the run-up: the sprinting driver jumped from the takeoff's braking walk.
     */
    public boolean engaged() {
        return current != null && engaged;
    }

    /** Whether the camera already points within 30 degrees of the offset. */
    private static boolean facing(Player player, double ox, double oz) {
        return facing(player, ox, oz, 30);
    }

    private static boolean facing(Player player, double ox, double oz, double within) {
        double want = Math.toDegrees(Math.atan2(-ox, oz)), d = (want - player.getYRot()) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return Math.abs(d) < within;
    }

    /** Ostinato (Presto v4): furthest across the approach line a plain takeoff's plan may settle from the landing centre. */
    static final double MAX_LATERAL = 0.5;

    /** How far across the approach line the plan {@code js} last ran settles from the landing block's centre. */
    static double lateralMiss(JumpSearch js) {
        PlayerSim s = js.sim();
        return Math.abs(js.dirX != 0 ? s.z - (js.destZ + 0.5) : s.x - (js.destX + 0.5));
    }

    /** Ostinato (Presto v4): how far to either side of the real start a plain takeoff's plan must still land from. */
    static final double SHIFT = 0.05;

    /**
     * Whether a plain takeoff may fly the plan {@code js} just found from {@code start}: it settles with the centre over
     * the landing block, and still does from the start shifted {@link #SHIFT} across the line either way. The capped
     * 1-gap plans one block back swing a metre off the line in the air and turn back late; from z 0.4 one landed and
     * from z 0.5 it fell (TakeoffSlewTest), so live, where the run-up starts anywhere in a decimetre, it is a coin toss.
     * Leaves {@code js} describing the plan from {@code start}.
     */
    static boolean takeable(JumpSearch js, PlayerSim start) {
        int[] p = js.plan.clone();
        PlayerSim probe = new PlayerSim(start.world());
        boolean ok = true;
        for (double d : new double[]{-SHIFT, SHIFT}) {
            probe.copyFrom(start);
            if (js.dirX != 0) {
                probe.z += d;
            } else {
                probe.x += d;
            }
            ok &= js.run(probe, p, js.jumped, js.airTicks, null) && lateralMiss(js) <= MAX_LATERAL;
        }
        return js.run(start, p, js.jumped, js.airTicks, null) && lateralMiss(js) <= MAX_LATERAL && ok;
    }

    private void reset(IMovement mv) {
        current = mv;
        js = null;
        running = landed = false;
        settle = ticks = 0;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        boolean slowMode = Baritone.settings().slowKinematic.value;
        if (!slowMode && !ExperimentalMovement.kinematicTravel() || player.isInWater() || player.isInLava() || player.onClimbable()
                || player.isFallFlying() || player.isPassenger() || player.getAbilities().flying
                || pathPosition >= path.movements().size() || !ClientWorld.plainGravity(ctx)) {
            return -1;
        }
        IMovement im = path.movements().get(pathPosition);
        MovementJump jump = im instanceof MovementJump j ? j : null;
        if (jump == null && (!(im instanceof MovementParkour pk) || pk.extensions() > 0)) {
            return -1;
        }
        if (jump != null && !slowMode) {
            return -1; // kinematic travel: the template jump's own mover flies it; a plain parkour jump handed back
            // by the sprinting driver is taken here rather than walked off at Baritone's pace (honey, soul sand)
        }
        IMovement mv = im;
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        BetterBlockPos src = mv.getSrc(), dest = mv.getDest();
        int dx = jump != null ? jump.frame()[0] : Integer.signum(dest.x - src.x);
        int dz = jump != null ? jump.frame()[1] : Integer.signum(dest.z - src.z);
        if (dx * dz != 0 || dx == dz) {
            return -1;
        }
        // a path left over from before a goal change is not the player's jump
        if (Math.hypot(player.getX() - (src.x + 0.5), player.getZ() - (src.z + 0.5)) > 6
                || player.getY() < Math.min(src.y, dest.y) - 0.6 || player.getY() > Math.max(src.y, dest.y) + 2) {
            return -1;
        }
        if (mv != current) {
            reset(mv);
        }
        inPlay = true;
        if (++ticks > 240) {
            cooldown = 60;
            reset(null);
            Baritone.settings().movementFault.value.accept("M01", "takeoff stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }
        world.reset();
        Vec3 p = player.position(), m = player.getDeltaMovement();
        ClientWorld.readPlayer(ctx, real);
        var in = baritone.getInputOverrideHandler();
        var look = baritone.getLookBehavior();

        if (landed) {
            settle++;
            boolean slow = Math.abs(m.x) + Math.abs(m.z) < 0.03;
            if (ctx.playerFeet().equals(dest) && (settle > 3 || slow) && player.onGround()) {
                in.clearAllKeys();
                return pathPosition + 1;
            }
            double tx = dest.x + 0.5 - p.x, tz = dest.z + 0.5 - p.z;
            look.human();
            look.updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-tx, tz)), 6f), true);
            in.clearAllKeys();
            in.setInputForceState(Input.MOVE_FORWARD, settle > 3 && Math.hypot(tx, tz) > 0.2 && facing(player, tx, tz));
            return pathPosition;
        }

        if (!running) {
            if (ctx.playerFeet().equals(dest) && player.onGround()) {
                // Ostinato: already on the landing (a jump flown before this takeoff took over): walking back to the
                // run-up from here crosses the gap on foot
                in.clearAllKeys();
                return pathPosition + 1;
            }
            double tx, tz;
            if (jump != null) {
                double lat = Math.min(jump.lateral(), 0.75);
                double[] pt = jump.point(jump.startA(), 0.5 + lat);
                tx = pt[0];
                tz = pt[1];
            } else {
                baritone.utils.BlockStateInterface bsi = new baritone.utils.BlockStateInterface(ctx);
                // Ostinato: and room to stand: a fence post behind the take-off block left the walk back pushing into
                // it for 240 ticks before handing back (cubed level 6)
                boolean room = MovementHelper.canWalkOn(bsi, src.x - dx, src.y - 1, src.z - dz)
                        && MovementHelper.fullyPassable(ctx, new BetterBlockPos(src.x - dx, src.y, src.z - dz))
                        && MovementHelper.fullyPassable(ctx, new BetterBlockPos(src.x - dx, src.y + 1, src.z - dz));
                double back = room ? 1.0 : 0.0;
                tx = src.x + 0.5 - dx * back;
                tz = src.z + 0.5 - dz * back;
            }
            double ex = tx - p.x, ez = tz - p.z;
            boolean slow = Math.abs(m.x) + Math.abs(m.z) <= 0.02;
            double jumpYaw = Math.toDegrees(Math.atan2(-dx, dz));
            boolean there = ex * ex + ez * ez <= 0.15 * 0.15;
            if (there && slow && !facing(player, dx, dz, 6)) {
                // at the spot: turn to the jump before the search, the flight assumes the camera already points along it
                look.human();
                look.updateTarget(new Rotation((float) jumpYaw, 6f), true);
                in.clearAllKeys();
                return pathPosition;
            }
            if (!there || !slow) {
                look.human();
                look.updateTarget(new Rotation(there ? (float) jumpYaw : (float) Math.toDegrees(Math.atan2(-ex, ez)), 6f), true);
                in.clearAllKeys();
                double d = Math.hypot(ex, ez);
                double speed = Math.hypot(m.x, m.z);
                boolean aligned = facing(player, ex, ez); // W only once the camera has turned to the spot, else the walk veers
                in.setInputForceState(Input.MOVE_FORWARD, aligned && d > 0.15 && speed < Math.min(0.2, d * 0.3 + 0.04));
                in.setInputForceState(Input.MOVE_BACK, aligned && speed > 0.05 && d < 0.6 && speed > d * 0.4);
                return pathPosition;
            }
            if (!real.onGround) {
                return pathPosition;
            }
            js = new JumpSearch(world);
            js.nativeSearch = Baritone.settings().nativeAcceleration.value;
            js.dirX = dx;
            js.dirZ = dz;
            js.edge = (src.x + 0.5 + 0.5 * dx) * dx + (src.z + 0.5 + 0.5 * dz) * dz;
            js.destX = dest.x;
            js.destY = dest.y;
            js.destZ = dest.z;
            // Ostinato (Presto v4): the plan is flown through a look that turns at most 32° a tick, so search with that
            // cap from the yaw the movement uses now. Searched with the look snapping, the course's one-wide 1-gap got a
            // run-up 30° off the line and a 90° turn in the air to brake; capped, the turn came three ticks late and the
            // landing went off the side of the walkway (presto-v4-trace1: 6 of 16 laps fell at z 1.30, TakeoffSlewTest).
            js.slewYaw = true;
            js.faceYaw = ctx.playerRotations().getYaw();
            boolean found;
            if (jump != null) {
                js.side = jump.frame()[4];
                System.arraycopy(jump.plan(), 0, js.plan, 0, JumpSearch.DIMS);
                found = js.search(real, true) || js.search(real, false);
                if (!found) {
                    // a template keeps MovementJump's rule: no capped plan, fly the snap one rather than give up the jump
                    js.slewYaw = false;
                    System.arraycopy(jump.plan(), 0, js.plan, 0, JumpSearch.DIMS);
                    found = js.search(real, true) || js.search(real, false);
                }
            } else {
                js.side = 1;
                js.plan[0] = 1; // straight ahead, no turn
                js.plan[3] = JumpSearch.O1_STRAIGHT;
                // a plain parkour jump with no plan the capped look can fly is handed back below, to the movement's own
                // run and jump, rather than flown off the side
                found = js.search(real, false);
                // JumpSearch counts a landing that holds the box by a sliver; across the line that is the edge of a
                // one-wide walkway, and the capped 1-gap plan settled at z 1.29 on a block ending at 1.0. Only take a
                // plain jump whose landing has the body's centre over the block, from a start a little to either side too
                if (found && !takeable(js, real)) {
                    found = false;
                }
            }
            if (Baritone.settings().kinematicTrace.value) {
                StringBuilder plan = new StringBuilder();
                for (int k : js.plan) plan.append(k);
                System.out.printf("KTRACE takeoff search found=%b plan=%s edge=%.3f slew=%b face=%.1f from=%.3f,%.3f,%.3f v=%.4f,%.4f%n", found, plan, js.edge,
                        js.slewYaw, js.faceYaw, real.x, real.y, real.z, real.vx, real.vz);
            }
            if (!found) {
                if (jump != null) {
                    jump.markFailed();
                }
                cooldown = 60;
                reset(null);
                return -1; // no takeoff from here
            }
            running = true;
            PositionSync.sync(ctx.player());
        } else {
            if (js.slewYaw) {
                js.faceYaw = ctx.playerRotations().getYaw(); // run() resims from this tick: the cap starts at the look we have now
            }
            if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
                js.search(real, true); // drifted off the plan: look for a nearby one, else fly it anyway
            }
        }
        int fwd = js.input(js.plan, js.jumped, js.airTicks);
        boolean press = js.jump(js.plan, real, js.jumped);
        look.updateTarget(new Rotation(js.yaw(js.plan, js.jumped, js.airTicks), player.getXRot()), false);
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, fwd > 0);
        in.setInputForceState(Input.MOVE_BACK, fwd < 0);
        in.setInputForceState(Input.SPRINT, fwd > 0);
        in.setInputForceState(Input.JUMP, press);
        if (press && real.x * js.dirX + real.z * js.dirZ >= js.edge + JumpSearch.EDGE[js.plan[2]]) {
            js.jumped = true;
            js.airTicks = 0;
        } else if (js.jumped) {
            js.airTicks++;
            if (js.airTicks > 1 && real.onGround) {
                landed = true;
                in.clearAllKeys();
            }
        }
        return pathPosition;
    }
}
