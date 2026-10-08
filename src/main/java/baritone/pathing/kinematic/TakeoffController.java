package baritone.pathing.kinematic;

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
 * Slow kinematic, standing takeoffs: a plain parkour jump the sprinting driver does not fly (the 1 block gap, or a run
 * that gave up). Walks back to a run-up spot, searches the exact takeoff (where to jump, when to turn, when to let go
 * of W) with {@link JumpSearch} against the real world, flies it, and steps onto the landing block's centre.
 */
public final class TakeoffController {

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final PlayerSim real;
    private JumpSearch js;
    private IMovement current;
    private boolean running, landed;
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
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        return r;
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

    private void reset(IMovement mv) {
        current = mv;
        js = null;
        running = landed = false;
        settle = ticks = 0;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || player.isInWater() || player.isInLava() || player.onClimbable()
                || player.isFallFlying() || player.isPassenger() || player.getAbilities().flying
                || pathPosition >= path.movements().size()) {
            return -1;
        }
        IMovement im = path.movements().get(pathPosition);
        MovementJump jump = im instanceof MovementJump j ? j : null;
        if (jump == null && (!(im instanceof MovementParkour pk) || pk.extensions() > 0)) {
            return -1;
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
        if (++ticks > 240) {
            cooldown = 60;
            reset(null);
            Baritone.settings().movementFault.value.accept("M01", "takeoff stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }
        world.reset();
        Vec3 p = player.position(), m = player.getDeltaMovement();
        real.x = p.x;
        real.y = p.y;
        real.z = p.z;
        real.vx = m.x;
        real.vy = m.y;
        real.vz = m.z;
        real.onGround = player.onGround();
        real.sprinting = player.isSprinting();
        real.collidedH = player.horizontalCollision;
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
            double tx, tz;
            if (jump != null) {
                double lat = Math.min(jump.lateral(), 0.75);
                double[] pt = jump.point(-jump.runUp() + 0.5, 0.5 + lat);
                tx = pt[0];
                tz = pt[1];
            } else {
                boolean room = MovementHelper.canWalkOn(new baritone.utils.BlockStateInterface(ctx), src.x - dx, src.y - 1, src.z - dz);
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
            js.dirX = dx;
            js.dirZ = dz;
            js.edge = (src.x + 0.5 + 0.5 * dx) * dx + (src.z + 0.5 + 0.5 * dz) * dz;
            js.destX = dest.x;
            js.destY = dest.y;
            js.destZ = dest.z;
            boolean found;
            if (jump != null) {
                js.side = jump.frame()[4];
                System.arraycopy(jump.plan(), 0, js.plan, 0, JumpSearch.DIMS);
                found = js.search(real, true) || js.search(real, false);
            } else {
                js.side = 1;
                js.plan[0] = 1; // straight ahead, no turn
                js.plan[3] = 4;
                found = js.search(real, false);
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
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            js.search(real, true); // drifted off the plan: look for a nearby one, else fly it anyway
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
