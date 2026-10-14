package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Slow kinematic, placing: sprints along a path while laying its landing blocks as it goes, the way a speedrunner
 * clutches along a fortress leg. The look direction is free to point at whatever face the next block goes against
 * (usually the wall beside the route) because the movement keys are chosen relative to it: W plus A or D walks along
 * the path while the camera looks at the wall. Blocks are placed several cells ahead whenever some face of a
 * neighbour can be hit from the current position, and the player only brakes when the ground ahead is not there yet.
 * Covers flat and one-up bridging ({@link MovementTraverse}, {@link MovementAscend}); anything that breaks blocks goes
 * back to Baritone.
 */
public final class ClutchController {

    private static final boolean TRACE = Boolean.getBoolean("baritone.clutchTrace");
    private static final int MAX_MOVES = 10;
    /** How far from the takeoff a block still to be laid may be counted on as a landing: a sprint jump covers about four. */
    private static final double OVERLAY_RADIUS = Double.parseDouble(System.getProperty("baritone.clutchOverlay", "2.0"));
    private static final double REACH = 4.2;
    private static final double MAX_REL = 70; // degrees between look and travel that W plus a strafe key can still sprint
    private static final double[] FACE_OFFSETS = {0, -0.3, 0.3};

    private final IPlayerContext ctx;
    private double lastX, lastY, lastZ;
    private int stuckTicks, cooldown, placeCooldown, retreat;
    /** Ticks driven and blocks placed, so callers can verify the mode is in use. */
    public static volatile long drivenTicks, placed;

    private final List<Movement> stretch = new ArrayList<>();
    private final List<BlockPos> needs = new ArrayList<>();

    public ClutchController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        return r;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || !Baritone.settings().allowPlace.value
                || player.isInWater() || player.isInLava() || player.isFallFlying() || player.isPassenger()
                || player.getAbilities().flying || !ClientWorld.plainGravity(ctx)) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        if (placeCooldown > 0) {
            placeCooldown--;
        }
        int pos = sync(path, pathPosition, player);
        if (!buildStretch(path, pos) || needs.isEmpty()) {
            return -1;
        }
        BlockPos first0 = needs.get(0);
        if (!baritone.getInventoryBehavior().selectThrowawayForLocation(true, first0.getX(), first0.getY(), first0.getZ())) {
            return -1; // nothing to place (a pick in hand, no blocks): Baritone reports it instead of the bot aiming forever
        }
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double moved = (px - lastX) * (px - lastX) + (py - lastY) * (py - lastY) + (pz - lastZ) * (pz - lastZ);
        lastX = px;
        lastY = py;
        lastZ = pz;
        stuckTicks = moved < 0.0004 ? stuckTicks + 1 : 0;
        if (stuckTicks > 50) {
            stuckTicks = 0;
            cooldown = 80;
            Baritone.settings().movementFault.value.accept("M01", "clutch stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }
        if (py < stretch.get(0).getSrc().y - 3) {
            return -1; // fell
        }

        // a block that has to go in is overlapping the player's own box (an ascend drifted into its cell): vanilla refuses
        // the placement, so step back along the route until the box is clear instead of crawling in place
        if (player.onGround()) {
            AABB body = player.getBoundingBox().inflate(0.1, 0, 0.1).setMinY(player.getBoundingBox().minY + 0.01);
            for (BlockPos c : needs) {
                if (body.intersects(new AABB(c)) && Math.abs(c.getY() + 0.5 - py) < 2 && placeable(c) && retreat++ < 25) {
                    BetterBlockPos d = stretch.get(0).getDest();
                    float yaw = (float) Math.toDegrees(Math.atan2(-(d.x + 0.5 - px), d.z + 0.5 - pz));
                    baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), true);
                    var back = baritone.getInputOverrideHandler();
                    back.clearAllKeys();
                    back.setInputForceState(Input.MOVE_BACK, true);
                    return pos;
                }
            }
        }

        retreat = 0;
        // where to go: the first destination that is not already underfoot
        // (and one that is ahead along the route: in flight the cell just flown over is farther than 0.8 too, and
        // steering back at it swings the camera round)
        BetterBlockPos carrot = null;
        BetterBlockPos end = stretch.get(stretch.size() - 1).getDest();
        BetterBlockPos begin = stretch.get(0).getSrc();
        double rx = end.x - begin.x, rz = end.z - begin.z;
        for (Movement m : stretch) {
            BetterBlockPos d = m.getDest();
            if (Math.hypot(d.x + 0.5 - px, d.z + 0.5 - pz) > 0.8 && ((d.x + 0.5 - px) * rx + (d.z + 0.5 - pz) * rz > 0 || rx == 0 && rz == 0)) {
                carrot = d;
                break;
            }
        }
        if (carrot == null) {
            return -1;
        }
        double travel = Math.toDegrees(Math.atan2(-(carrot.x + 0.5 - px), carrot.z + 0.5 - pz));
        float lookYaw = (float) travel, lookPitch = 0;

        double tx = -Math.sin(Math.toRadians(travel)), tz = Math.cos(Math.toRadians(travel));
        Aim aim = findAim(baritone, px, py, pz, travel, tx, tz);
        boolean clicking = false;
        if (aim != null) {
            lookYaw = aim.rot.getYaw();
            lookPitch = aim.rot.getPitch();
            baritone.getInventoryBehavior().selectThrowawayForLocation(true, aim.target.getX(), aim.target.getY(), aim.target.getZ());
            HitResult over = ctx.objectMouseOver();
            if (placeCooldown == 0 && over instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(aim.against) && hit.getBlockPos().relative(hit.getDirection()).equals(aim.target)) {
                clicking = true;
                placeCooldown = 1;
                placed++;
            }
        }

        // keys relative to the look: W/S and A/D by the travel direction's components in the camera frame
        double rel = Math.toRadians(wrap(travel - lookYaw));
        double fwd = Math.cos(rel), left = -Math.sin(rel);
        boolean w = fwd > 0.38, s = fwd < -0.38, a = left > 0.38, d = left < -0.38;

        // do not walk onto ground that is not there: stand and keep placing
        double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        double reach = 0.3 + speed * 1.8;
        double qx = px - Math.sin(Math.toRadians(travel)) * reach, qz = pz + Math.cos(Math.toRadians(travel)) * reach;
        boolean supported = supported(qx, py, qz) || supported(px - Math.sin(Math.toRadians(travel)) * 0.3, py, pz + Math.cos(Math.toRadians(travel)) * 0.3) && aim != null && placeCooldown <= 1;
        boolean grounded = player.onGround();
        if (!supported && grounded) {
            w = s = a = d = false;
        }

        boolean jump = false;
        // sprint-jump along the bridge: take off whenever the flight, counting the landing blocks within reach that
        // get laid on the way, ends on the route; blocks go down under the feet and ahead while airborne
        if (grounded && supported && supported(px + tx * 1.2, py, pz + tz * 1.2) && sprintJumpLands((float) travel, px, pz)) {
            jump = true;
        }
        Movement first = stretch.get(0);
        if (first instanceof MovementAscend && grounded && supported
                && Math.hypot(first.getDest().x + 0.5 - px, first.getDest().z + 0.5 - pz) < 1.3
                && py < first.getDest().y - 0.05 && solid(first.getDest().below())) {
            jump = true;
        }

        baritone.getLookBehavior().updateTarget(new Rotation(lookYaw, Math.max(-85f, Math.min(85f, lookPitch))), true);
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, w);
        in.setInputForceState(Input.MOVE_BACK, s);
        in.setInputForceState(Input.MOVE_LEFT, a);
        in.setInputForceState(Input.MOVE_RIGHT, d);
        in.setInputForceState(Input.SPRINT, w);
        in.setInputForceState(Input.JUMP, jump);
        in.setInputForceState(Input.CLICK_RIGHT, clicking);
        if (TRACE) {
            System.out.printf("CLUTCHTRACE x=%.2f y=%.2f z=%.2f g=%b v=%.3f yaw=%.0f pit=%.0f travel=%.0f keys=%s%s%s%s%s click=%b aim=%b placed=%d%n",
                    px, py, pz, grounded, speed, lookYaw, lookPitch, travel, w ? "W" : "", s ? "S" : "", a ? "A" : "", d ? "D" : "",
                    jump ? "J" : "", clicking, aim != null, placed);
        }
        return pos;
    }

    /** Something solid beside or under the cell to lay the block against. */
    private boolean placeable(BlockPos c) {
        for (Direction dir : Direction.values()) {
            if (dir != Direction.UP && solid(c.relative(dir)) && !ctx.world().getBlockState(c.relative(dir)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private Aim lastAim;
    private PlayerSim sim;
    private ClientWorld client;
    private double simX, simZ;
    private boolean useOverlay = true;

    /** Where the player comes down if nothing changes (keys held forward), over the blocks that are really there. */
    private double[] airLanding(float travel) {
        useOverlay = false;
        try {
            prime(travel);
            Player p = ctx.player();
            for (int t = 0; t < 40; t++) {
                sim.tick(travel, true, true, false);
                if (sim.onGround || sim.y < p.getY() - 6) {
                    return new double[]{sim.x, sim.z};
                }
            }
        } finally {
            useOverlay = true;
        }
        return null;
    }

    private void prime(float travel) {
        Player p = ctx.player();
        if (sim == null) {
            sprintJumpLands(travel, p.getX(), p.getZ()); // builds the sim
        }
        client.reset();
        sim.x = p.getX(); sim.y = p.getY(); sim.z = p.getZ();
        sim.vx = p.getDeltaMovement().x; sim.vy = p.getDeltaMovement().y; sim.vz = p.getDeltaMovement().z;
        sim.onGround = p.onGround();
        sim.sprinting = p.isSprinting();
        sim.collidedH = false;
        sim.jumpTicks = 0;
        ClientWorld.readEffects(ctx, sim);
    }

    /** Whether a sprint jump from here toward {@code travel} lands on the route, with blocks laid in reach counted as there. */
    private boolean sprintJumpLands(float travel, double px, double pz) {
        simX = px;
        simZ = pz;
        // from a standstill nothing is there yet to count on: only a run that already has speed may land on a block still to be laid
        useOverlay = Math.hypot(ctx.player().getDeltaMovement().x, ctx.player().getDeltaMovement().z) > 0.1;
        if (sim == null) {
            client = new ClientWorld(ctx);
            sim = new PlayerSim(new PlayerSim.World() {
                @Override
                public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                    client.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
                    for (BlockPos c : useOverlay ? needs : List.<BlockPos>of()) {
                        if (c.getX() + 1 > minX && c.getX() < maxX && c.getY() + 1 > minY && c.getY() < maxY && c.getZ() + 1 > minZ && c.getZ() < maxZ
                                && Math.hypot(c.getX() + 0.5 - simX, c.getZ() + 0.5 - simZ) <= OVERLAY_RADIUS && wallBacked(c)) {
                            out.add(new double[]{c.getX(), c.getY(), c.getZ(), c.getX() + 1, c.getY() + 1, c.getZ() + 1});
                        }
                    }
                }

                @Override
                public float slipperiness(int x, int y, int z) {
                    return client.slipperiness(x, y, z);
                }

                @Override
                public float speedFactor(int x, int y, int z) {
                    return client.speedFactor(x, y, z);
                }

                @Override
                public float jumpFactor(int x, int y, int z) {
                    return client.jumpFactor(x, y, z);
                }

                @Override
                public boolean sticky(int x, int y, int z) {
                    return client.sticky(x, y, z);
                }

                @Override
                public boolean bouncy(int x, int y, int z) {
                    return client.bouncy(x, y, z);
                }

                @Override
                public boolean water(int x, int y, int z) {
                    return client.water(x, y, z);
                }

                @Override
                public boolean climbable(int x, int y, int z) {
                    return client.climbable(x, y, z);
                }
            });
        }
        client.reset();
        Player p = ctx.player();
        sim.x = p.getX(); sim.y = p.getY(); sim.z = p.getZ();
        sim.vx = p.getDeltaMovement().x; sim.vy = p.getDeltaMovement().y; sim.vz = p.getDeltaMovement().z;
        sim.onGround = p.onGround();
        sim.sprinting = p.isSprinting();
        sim.collidedH = false;
        sim.jumpTicks = 0;
        ClientWorld.readEffects(ctx, sim);
        boolean left = false;
        for (int t = 0; t < 40; t++) {
            sim.tick(travel, true, true, t == 0);
            if (!sim.onGround) {
                left = true;
            } else if (left) {
                break;
            }
            if (sim.y < p.getY() - 1.5) {
                return false;
            }
        }
        if (!sim.onGround || Math.hypot(sim.x - px, sim.z - pz) < 1.5) {
            return false;
        }
        int lx = PlayerSim.floor(sim.x), lz = PlayerSim.floor(sim.z);
        for (Movement m : stretch) {
            BetterBlockPos d = m.getDest();
            if (d.x == lx && d.z == lz && Math.abs(sim.y - d.y) < 0.01) {
                return true;
            }
        }
        return false;
    }

    /** A block that can be laid against something that is already there, not only against another block still to come. */
    private boolean wallBacked(BlockPos c) {
        for (Direction dir : Direction.values()) {
            if (dir != Direction.UP && solid(c.relative(dir))) {
                return true;
            }
        }
        return false;
    }

    private static double wrap(double deg) {
        deg %= 360;
        if (deg > 180) deg -= 360;
        if (deg <= -180) deg += 360;
        return deg;
    }

    private boolean solid(BlockPos p) {
        BlockState st = ctx.world().getBlockState(p);
        return !st.getCollisionShape(ctx.world(), p).isEmpty();
    }

    private boolean supported(double x, double y, double z) {
        return solid(BlockPos.containing(x, y - 0.5, z));
    }

    /** Advance past moves whose destination the player already stands in. */
    private int sync(IPath path, int pathPosition, Player player) {
        List<IMovement> moves = path.movements();
        int fx = PlayerSim.floor(player.getX()), fz = PlayerSim.floor(player.getZ());
        for (int i = Math.min(moves.size() - 1, pathPosition + MAX_MOVES); i >= pathPosition; i--) {
            BetterBlockPos d = moves.get(i).getDest();
            if (d.x == fx && d.z == fz && Math.abs(player.getY() - d.y) < 0.5 && player.onGround() && solid(d.below())) {
                return i + 1;
            }
        }
        return pathPosition;
    }

    private boolean buildStretch(IPath path, int pathPosition) {
        stretch.clear();
        needs.clear();
        List<IMovement> moves = path.movements();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition; i < moves.size() && i < pathPosition + MAX_MOVES; i++) {
            IMovement mv = moves.get(i);
            if (!(mv instanceof MovementTraverse || mv instanceof MovementAscend)) {
                break;
            }
            Movement m = (Movement) mv;
            m.resetBlockCache();
            if (!m.toBreak(bsi).isEmpty()) {
                break;
            }
            stretch.add(m);
            needs.addAll(m.toPlace(bsi));
        }
        return !stretch.isEmpty();
    }

    private static final class Aim {
        final Rotation rot;
        final BlockPos target, against;
        final Vec3 point;

        Aim(Rotation rot, BlockPos target, BlockPos against, Vec3 point) {
            this.rot = rot;
            this.target = target;
            this.against = against;
            this.point = point;
        }
    }

    /**
     * The nearest unplaced landing block, with the look that hits a face of one of its neighbours from here, picking
     * the look closest to the travel direction so the player can keep sprinting.
     */
    private Aim findAim(Baritone baritone, double px, double py, double pz, double travel, double tx, double tz) {
        Player player = ctx.player();
        Vec3 eye = ctx.playerHead();
        // the body this tick and the next: a block cannot be laid where the player is or is about to be
        Vec3 mv = player.getDeltaMovement();
        AABB box = player.getBoundingBox();
        // a block right under the soles is fine (that is the point), so the vertical extent only grows upward
        AABB body = box.expandTowards(mv.x, Math.max(0, mv.y), mv.z).inflate(0.02, 0, 0.02).setMinY(box.minY + 0.01);
        List<BlockPos> order = needs;
        if (!player.onGround()) { // in flight the block the landing needs comes first
            double[] land = airLanding((float) travel);
            if (land != null) {
                order = new ArrayList<>(needs);
                order.sort(java.util.Comparator.comparingDouble(c -> Math.hypot(c.getX() + 0.5 - land[0], c.getZ() + 0.5 - land[1])));
            }
        }
        // keep the look that was working: a fresh pick every tick swings the camera about and the click, which reads the
        // look the player really has, never lands on the face it was meant for
        if (lastAim != null && needs.contains(lastAim.target) && !body.intersects(new AABB(lastAim.target))
                && eye.distanceToSqr(Vec3.atCenterOf(lastAim.target)) <= REACH * REACH
                && (lastAim.target.getX() + 0.5 - px) * tx + (lastAim.target.getZ() + 0.5 - pz) * tz >= -0.5) {
            Direction face = null;
            for (Direction dir : Direction.values()) {
                if (lastAim.target.relative(dir).equals(lastAim.against)) face = dir.getOpposite();
            }
            Rotation again = RotationUtils.calcRotationFromVec3d(eye, lastAim.point, ctx.playerRotations());
            HitResult res = RayTraceUtils.rayTraceTowards(player, again, REACH);
            if (face != null && Math.abs(wrap(travel - again.getYaw())) < MAX_REL && res instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                    && h.getBlockPos().equals(lastAim.against) && h.getDirection() == face) {
                lastAim = new Aim(again, lastAim.target, lastAim.against, lastAim.point);
                return lastAim;
            }
        }
        for (BlockPos target : order) {
            if (body.intersects(new AABB(target))) {
                continue;
            }
            if (eye.distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH
                    || (target.getX() + 0.5 - px) * tx + (target.getZ() + 0.5 - pz) * tz < -0.5) {
                continue; // out of reach, or already flown past
            }
            Aim best = null;
            double bestRel = MAX_REL;
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) {
                    continue;
                }
                BlockPos against = target.relative(dir);
                if (!solid(against) || ctx.world().getBlockState(against).isAir()) {
                    continue;
                }
                Direction face = dir.getOpposite(); // the face of `against` that looks at the target
                for (double u : FACE_OFFSETS) {
                    for (double v : FACE_OFFSETS) {
                        Vec3 pt = facePoint(against, face, u, v);
                        if (pt.distanceToSqr(eye) > REACH * REACH) {
                            continue;
                        }
                        Rotation rot = RotationUtils.calcRotationFromVec3d(eye, pt, ctx.playerRotations());
                        double rel = Math.abs(wrap(travel - rot.getYaw()));
                        if (rel >= bestRel) {
                            continue;
                        }
                        HitResult res = RayTraceUtils.rayTraceTowards(player, rot, REACH);
                        if (res instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                                && h.getBlockPos().equals(against) && h.getDirection() == face) {
                            bestRel = rel;
                            best = new Aim(rot, target, against, pt);
                        }
                    }
                }
            }
            if (best != null) {
                lastAim = best;
                return best;
            }
        }
        return null;
    }

    private static Vec3 facePoint(BlockPos b, Direction face, double u, double v) {
        double cx = b.getX() + 0.5 + face.getStepX() * 0.5, cy = b.getY() + 0.5 + face.getStepY() * 0.5, cz = b.getZ() + 0.5 + face.getStepZ() * 0.5;
        switch (face.getAxis()) {
            case X:
                return new Vec3(cx, cy + u, cz + v);
            case Y:
                return new Vec3(cx + u, cy, cz + v);
            default:
                return new Vec3(cx + u, cy + v, cz);
        }
    }
}
