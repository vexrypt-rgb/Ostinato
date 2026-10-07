package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementSwim;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Slow kinematic, swimming: drives the water stretches of a path with a look-ahead over {@link PlayerSim#tickWater}.
 * Each tick it simulates a few ways of swimming (sprint-swim pitched at the line, diving, paddling, jumping)
 * a short way ahead and presses the keys of the one that stays on the 3D line and gets furthest along it.
 * It also surfaces for air and climbs out at the bank; stretches that break or place blocks, and anything
 * that is not water, go back to Baritone and {@link KinematicController}.
 */
public final class SwimController {

    private static final int MAX_LOOKAHEAD_MOVES = 12;
    private static final int HORIZON = 10;
    private static final double CORRIDOR = 1.4;
    private static final double AIM_AHEAD = 1.6;
    private static final float[] PITCH_OFFSETS = {0, -15, 15};
    private static final float[] NO_OFFSET = {0};

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final PlayerSim real;
    private final PlayerSim sim;
    private final List<double[]> line = new ArrayList<>(); // x, y, z, arc length
    private int lastMove;
    private boolean breathing;
    private double lastX, lastY, lastZ;
    private int stuckTicks, cooldown;
    /** Ticks the controller has driven the player, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    // keys of the plan being tried: 0 sprint-swim, 1 dive (sprint and sneak), 2 paddle, 3 paddle and jump
    private boolean sprint, sneak, jump;

    public SwimController(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
        this.sim = new PlayerSim(world);
    }

    /**
     * @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run
     */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        return r;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || !(player.isInWater() || breathing && nearWater(player)) || player.isInLava()
                || player.isFallFlying() || player.isPassenger()) {
            breathing = false;
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        world.reset();
        if (!buildLine(path, pathPosition)) {
            return -1;
        }
        Vec3 p = player.position();
        Vec3 m = player.getDeltaMovement();
        real.x = p.x; real.y = p.y; real.z = p.z;
        real.vx = m.x; real.vy = m.y; real.vz = m.z;
        real.onGround = player.onGround();
        real.sprinting = player.isSprinting();
        real.swimming = player.isSwimming();
        real.collidedH = player.horizontalCollision;

        double[] here = project(real.x, real.y, real.z);
        if (here[1] > (breathing ? CORRIDOR + 8.0 : CORRIDOR + 1.0)) {
            return -1; // swept off the line (current, knockback): let Baritone re-plan
        }
        double moved = (real.x - lastX) * (real.x - lastX) + (real.y - lastY) * (real.y - lastY) + (real.z - lastZ) * (real.z - lastZ);
        lastX = real.x;
        lastY = real.y;
        lastZ = real.z;
        stuckTicks = moved < 0.0025 ? stuckTicks + 1 : 0;
        if (stuckTicks > 30) {
            stuckTicks = 0;
            cooldown = 60;
            Baritone.settings().movementFault.value.accept("M01", "swim stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }
        int newPos = syncPosition(path, pathPosition);
        updateBreathing(player);

        if (breathing) {
            // creep up still sprint-swimming (the pose holds while in water), then stay at the surface
            double[] ahead = pointAt(here[0] + AIM_AHEAD);
            float yaw = yawTo(ahead, real.x, real.z);
            // looking up does not lift a swimmer through the surface (vanilla only does that with water overhead): jump does
            double top = waterTop(player);
            boolean lift;
            if (Double.isNaN(top)) {
                lift = player.isEyeInFluid(FluidTags.WATER);
            } else {
                // hold the feet just under the surface: the swim-pose eyes (0.4 up) are out once the feet pass top - 0.29, and
                // the pose is lost if the feet clear the water, so a level pitch and a jump only when sinking below the target
                lift = real.y + real.vy * 3 < top - 0.12;
            }
            press(baritone, yaw, 0f, true, false, lift);
            return newPos;
        }

        int best = -1;
        float bestOff = 0;
        double bestScore = -1e9;
        for (int mode = 0; mode < 4; mode++) {
            for (float off : mode == 0 ? PITCH_OFFSETS : NO_OFFSET) {
                double score = rollout(mode, off, here[0]);
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = mode;
                    bestOff = off;
                }
            }
        }
        if (best < 0 || bestScore < -1e8) {
            return -1;
        }
        double[] tgt = pointAt(here[0] + AIM_AHEAD);
        applyMode(best, real.y, tgt[1]);
        float pitch = pitchTo(real.x, real.y, real.z, tgt) + bestOff;
        press(baritone, yawTo(tgt, real.x, real.z), pitch, sprint, sneak, jump);
        return newPos;
    }

    private void press(Baritone baritone, float yaw, float pitch, boolean sprint, boolean sneak, boolean jump) {
        baritone.getLookBehavior().human(); // steer with the mouse: a bounded, mouse-stepped camera turn, not a snap
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, Math.max(-85f, Math.min(85f, pitch))), true);
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, true);
        in.setInputForceState(Input.SPRINT, sprint);
        in.setInputForceState(Input.SNEAK, sneak);
        in.setInputForceState(Input.JUMP, jump);
    }

    /** Sets {@link #sprint}, {@link #sneak} and {@link #jump} for a plan, given feet height and the height wanted. */
    private void applyMode(int mode, double y, double targetY) {
        double dy = targetY - y;
        switch (mode) {
            case 0:
                sprint = true;
                sneak = false;
                jump = false;
                break;
            case 1:
                sprint = true;
                sneak = true;
                jump = false;
                break;
            case 2:
                sprint = false;
                sneak = dy < -0.3;
                jump = dy > 0.3;
                break;
            default:
                sprint = false;
                sneak = false;
                jump = true;
        }
    }

    /**
     * Score = arc progress at the end of the horizon, a little credit for speed along the line, minus the distance off it;
     * -1e9 if the plan leaves the corridor or enters lava or fire first.
     */
    private double rollout(int mode, float pitchOffset, double s0) {
        sim.copyFrom(real);
        double s = s0, off = 0;
        for (int t = 0; t < HORIZON; t++) {
            double[] tgt = pointAt(s + AIM_AHEAD);
            applyMode(mode, sim.y, tgt[1]);
            float pitch = pitchTo(sim.x, sim.y, sim.z, tgt) + (t < 5 ? pitchOffset : 0);
            if (sim.inWater()) {
                sim.tickWater(yawTo(tgt, sim.x, sim.z), pitch, true, sprint, jump, sneak);
            } else {
                sim.tick(yawTo(tgt, sim.x, sim.z), 1, sprint, jump); // out of the water: the bank, or a hop onto it
            }
            double[] pr = project(sim.x, sim.y, sim.z);
            if (pr[1] > CORRIDOR || KinematicController.hazard(ctx, sim.x, sim.y, sim.z)) {
                return -1e9;
            }
            s = Math.max(s, pr[0]);
            off = pr[1];
        }
        return s + 0.5 * Math.sqrt(sim.vx * sim.vx + sim.vy * sim.vy + sim.vz * sim.vz) - 0.3 * off;
    }

    /** Surface for air when the lungs won't last the swim up, then stay up: the swim pose holds at the surface, so it keeps
     * sprint-swimming there with the head out for the rest of the stretch. */
    private void updateBreathing(Player player) {
        int air = player.getAirSupply();
        double up = surfaceAbove(player);
        if (up >= 0 && air < up * 12 + 60) {
            breathing = true;
        }
        if (up < 0 && !player.isEyeInFluid(FluidTags.WATER)) {
            breathing = false; // roofed over with the head out: nothing to rise to
        }
    }

    /** Blocks from the eyes up to open air straight above, or -1 if roofed over within 24. */
    private double surfaceAbove(Player player) {
        if (!player.isEyeInFluid(FluidTags.WATER)) {
            return 0;
        }
        BlockPos eyes = BlockPos.containing(player.getEyePosition(1));
        for (int i = 0; i < 24; i++) {
            BlockPos q = eyes.above(i);
            var st = ctx.world().getBlockState(q);
            if (st.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            return st.getCollisionShape(ctx.world(), q).isEmpty() ? Math.max(0, q.getY() - 0.11 - player.getEyeY()) : -1;
        }
        return -1;
    }

    /** Height of the water surface in the player's column, or NaN when the feet are not in water. */
    private double waterTop(Player player) {
        BlockPos q = BlockPos.containing(player.getX(), player.getY() + 0.1, player.getZ());
        if (!ctx.world().getFluidState(q).is(FluidTags.WATER)) {
            return Double.NaN;
        }
        for (int i = 0; i < 24 && ctx.world().getFluidState(q.above()).is(FluidTags.WATER); i++) {
            q = q.above();
        }
        return q.getY() + ctx.world().getFluidState(q).getOwnHeight();
    }

    /**
     * Whether water is within a block and a bit under the feet: a breach or the hop out at the bank, still over the water
     * column. Holding sprint and forward through it keeps the speed for the bank, where handing back would restart from 0.
     */
    private boolean nearWater(Player player) {
        return ctx.world().getFluidState(BlockPos.containing(player.getX(), player.getY() - 1.2, player.getZ())).is(FluidTags.WATER);
    }

    private boolean wet(BetterBlockPos b) {
        return ctx.world().getFluidState(b).is(FluidTags.WATER) || ctx.world().getFluidState(b.above()).is(FluidTags.WATER);
    }

    private static boolean swimmable(IMovement mv) {
        return mv instanceof MovementSwim || mv instanceof MovementTraverse || mv instanceof MovementAscend
                || mv instanceof MovementDescend || mv instanceof MovementDiagonal || mv instanceof MovementFall;
    }

    /** The water stretch ahead as a 3D polyline through each move's destination, stopping at anything that breaks or places. */
    private boolean buildLine(IPath path, int pathPosition) {
        line.clear();
        List<IMovement> moves = path.movements();
        if (pathPosition >= moves.size()) {
            return false;
        }
        BlockStateInterface bsi = null;
        add(moves.get(pathPosition).getSrc());
        int i = pathPosition;
        for (; i < moves.size() && i < pathPosition + MAX_LOOKAHEAD_MOVES; i++) {
            IMovement mv = moves.get(i);
            if (!swimmable(mv) || !(wet(mv.getSrc()) || wet(mv.getDest()))) {
                break;
            }
            Movement movement = (Movement) mv;
            if (movement.toBreakCached == null || movement.toPlaceCached == null) {
                if (bsi == null) {
                    bsi = new BlockStateInterface(ctx);
                }
                movement.toBreak(bsi);
                movement.toPlace(bsi);
            }
            if (!movement.toBreakCached.isEmpty() || !movement.toPlaceCached.isEmpty()) {
                break;
            }
            add(mv.getDest());
        }
        lastMove = i - 1;
        return line.size() >= 2;
    }

    private void add(BetterBlockPos b) {
        double x = b.x + 0.5, z = b.z + 0.5, y = b.y + 0.1;
        double s = 0;
        if (!line.isEmpty()) {
            double[] a = line.get(line.size() - 1);
            s = a[3] + Math.sqrt((x - a[0]) * (x - a[0]) + (y - a[1]) * (y - a[1]) + (z - a[2]) * (z - a[2]));
        }
        line.add(new double[]{x, y, z, s});
    }

    /** Nearest point on the polyline: {arc length, distance}. */
    private double[] project(double x, double y, double z) {
        double bestS = 0, bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = len2 == 0 ? 0 : ((x - a[0]) * dx + (y - a[1]) * dy + (z - a[2]) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + dx * t, py = a[1] + dy * t, pz = a[2] + dz * t;
            double d = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
            if (d < bestD) {
                bestD = d;
                bestS = a[3] + (b[3] - a[3]) * t;
            }
        }
        return new double[]{bestS, Math.sqrt(bestD)};
    }

    private double[] pointAt(double s) {
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                double len = b[3] - a[3];
                double t = len == 0 ? 1 : Math.max(0, Math.min(1, (s - a[3]) / len));
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
            }
        }
        return line.get(line.size() - 1);
    }

    private static float yawTo(double[] tgt, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(tgt[0] - x), tgt[2] - z));
    }

    /** Pitch that raises or sinks the body toward the target; negative looks up. */
    private static float pitchTo(double x, double y, double z, double[] tgt) {
        double hd = Math.max(0.5, Math.hypot(tgt[0] - x, tgt[2] - z));
        return (float) -Math.toDegrees(Math.atan2(tgt[1] - y, hd));
    }

    /** Advance past moves whose destination the player already floats in. A vertical or stepped lane needs the height too. */
    private int syncPosition(IPath path, int pathPosition) {
        int fx = PlayerSim.floor(real.x), fz = PlayerSim.floor(real.z);
        for (int i = lastMove; i >= pathPosition; i--) {
            IMovement mv = path.movements().get(i);
            BetterBlockPos d = mv.getDest();
            double tol = mv.getSrc().y == d.y ? 0.9 : 0.4;
            if (d.x == fx && d.z == fz && Math.abs(real.y - d.y) <= tol) {
                return i + 1;
            }
        }
        return pathPosition;
    }
}
