package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementParkour;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Slow kinematic, block extension: a 5 or 6 block jump is a sprint jump straight at the far platform, taken at once from
 * the edge, with the missing blocks laid mid-air from the far side: the cell next to the platform first, against the
 * platform's face, and for a 6 block jump the second against the first. Everything is done with the humanized mouse. The
 * takeoff simulation assumes the blocks will be there and uses the camera's real yaw, so mouse tremor never invalidates
 * the window.
 */
public final class ExtensionController {

    private final IPlayerContext ctx;
    private PlayerSim sim;
    private ClientWorld client;
    private int lastPos = -1, ticks, cooldown;
    private boolean backed, placeJump;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    public ExtensionController(IPlayerContext ctx) {
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
                || player.getAbilities().flying || pathPosition >= path.movements().size()) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        IMovement mv = path.movements().get(pathPosition);
        if (!(mv instanceof MovementParkour pk)) {
            lastPos = -1;
            return -1;
        }
        if (pathPosition != lastPos) {
            lastPos = pathPosition;
            backed = false;
            ticks = 0;
            // a jump whose landing block is not there yet is laid in mid-air, against a face next to the landing;
            // a full 4 block gap is also taken here, since it needs the exact edge takeoff Baritone's parkour misses
            BetterBlockPos s0 = mv.getSrc(), d0 = mv.getDest();
            placeJump = pk.extensions() == 0 && (!MovementHelper.canWalkOn(ctx, d0.below())
                    || Math.abs(d0.x - s0.x) + Math.abs(d0.z - s0.z) >= 5);
        }
        if (pk.extensions() == 0 && !placeJump) {
            return -1;
        }
        BetterBlockPos src = mv.getSrc(), dest = mv.getDest();
        int sx = Integer.signum(dest.x - src.x), sz = Integer.signum(dest.z - src.z);
        int ext = pk.extensions();
        if (player.getY() < src.y - 0.5) {
            return -1; // fell
        }
        if (!baritone.getInventoryBehavior().selectThrowawayForLocation(true, dest.x, dest.y - 1, dest.z)) {
            return -1; // nothing to lay the jump with: Baritone's own movement knows what it can still do
        }
        if (++ticks > 600) {
            ticks = 0;
            cooldown = 80;
            Baritone.settings().movementFault.value.accept("M01", "extension jump stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }
        BetterBlockPos feet = ctx.playerFeet();
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        baritone.getLookBehavior().human();
        float travelYaw = (float) Math.toDegrees(Math.atan2(-sx, sz));
        double along = player.getX() * sx + player.getZ() * sz;
        BetterBlockPos from = src;

        if (feet.equals(dest) && player.onGround()) {
            double off = (player.getX() - (dest.x + 0.5)) * sx + (player.getZ() - (dest.z + 0.5)) * sz;
            double v = player.getDeltaMovement().x * sx + player.getDeltaMovement().z * sz;
            if (off > 0.2 && v > 0.05) {
                baritone.getLookBehavior().updateTarget(new Rotation(travelYaw, 0), true);
                return pathPosition; // brake: release everything and let friction work
            }
            return pathPosition + 1;
        }

        if (ext > 0 && !backed && player.onGround()) {
            // run-up: the jump is taken at once from the edge, so there must be room to build up speed first
            int k = 0;
            while (k < 7 && MovementHelper.canWalkOn(ctx, cell(src, sx, sz, -(k + 1)).below())
                    && MovementHelper.fullyPassable(ctx, cell(src, sx, sz, -(k + 1)))
                    && MovementHelper.fullyPassable(ctx, cell(src, sx, sz, -(k + 1)).above())) {
                k++;
            }
            BetterBlockPos start = cell(src, sx, sz, -k);
            double startCentre = (start.x + 0.5) * sx + (start.z + 0.5) * sz;
            baritone.getLookBehavior().updateTarget(new Rotation(travelYaw, 0), true);
            if (k > 0 && along > startCentre + 0.15 && !runUpLands(dest, ext, sx, sz, travelYaw)) {
                in.setInputForceState(Input.MOVE_BACK, true);
                return pathPosition;
            }
            if (Math.abs(Mth.wrapDegrees(player.getYRot() - travelYaw)) > 8 || Math.abs(player.getXRot()) > 25) {
                return pathPosition;
            }
            backed = true;
        }

        // run-up and jump: the heading is held, W and sprint stay down
        baritone.getLookBehavior().updateTarget(new Rotation(travelYaw, 0), true);
        in.setInputForceState(Input.MOVE_FORWARD, true);
        int gap = Math.abs(dest.x - src.x) + Math.abs(dest.z - src.z);
        boolean ascend = dest.y > src.y;
        boolean sprint = ext > 0 || gap >= 4 || ascend;
        in.setInputForceState(Input.SPRINT, sprint);
        boolean airborne = !player.onGround() && player.getY() > src.y + 0.1;
        if (airborne) {
            // coasting alone reaches the landing centre: stop pushing so narrow tops are not overshot
            double centre = (dest.x + 0.5) * sx + (dest.z + 0.5) * sz;
            double v = player.getDeltaMovement().x * sx + player.getDeltaMovement().z * sz;
            double vy = player.getDeltaMovement().y, y = player.getY(), land = along;
            for (int t = 0; t < 40 && (vy > 0 || y > dest.y); t++) {
                vy = (vy - 0.08) * 0.98;
                y += vy;
                v *= 0.91;
                land += v;
            }
            if (along < centre && land > centre) {
                in.setInputForceState(Input.SPRINT, false);
                in.setInputForceState(Input.MOVE_FORWARD, false);
                in.setInputForceState(Input.MOVE_BACK, land > centre + 0.2);
            }
        } else if (player.onGround() && (ext > 0 ? backed
                : !feet.equals(from) && feet.equals(cell(src, sx, sz, 1)))) {
            // at the edge: take off on the last tick that still lands
            boolean wait;
            if (ext > 0) {
                // keep running while a later takeoff still lands, or while none lands yet and there is edge left to run to
                double edgeLeft = (src.x + 0.5) * sx + (src.z + 0.5) * sz + 0.5 - along;
                boolean later = false;
                for (int d = 1; d <= 6 && !later; d++) {
                    later = jumpLands(dest, ext, sx, sz, player.getYRot(), d);
                }
                wait = later || edgeLeft > 0.35 && !jumpLands(dest, ext, sx, sz, player.getYRot(), 0);
            } else if (!ascend && (gap == 3 || gap == 5)) { // 2 or 4 block gap: jump from the very edge
                double dist = Math.max(Math.abs(from.x + 0.5 - player.getX()), Math.abs(from.z + 0.5 - player.getZ()));
                double speed = gap == 5 ? Math.max(Math.abs(player.getDeltaMovement().x), Math.abs(player.getDeltaMovement().z)) / 0.546 : 0;
                wait = gap == 5 ? dist + speed < 1.0 : dist < 0.7;
            } else {
                wait = false;
            }
            in.setInputForceState(Input.JUMP, !wait);
        }
        if ((placeJump || ext > 0) && (!player.onGround() || ext > 0 && backed && along >= (src.x + 0.5) * sx + (src.z + 0.5) * sz - 1.5)) {
            placeMidAir(baritone, dest, ext, sx, sz, travelYaw);
        }
        return pathPosition;
    }

    private static final net.minecraft.core.Direction[] FACES = {
            net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST};

    /**
     * Lay the landing block while in the air: look at a face next to it (preferring straight down, so the camera does not
     * swing sideways mid-jump) and click once the ray hits. The movement keys are re-derived in the camera frame so the
     * push along the jump stays the same wherever the camera points.
     */
    private void placeMidAir(Baritone baritone, BetterBlockPos dest, int ext, int sx, int sz, float travelYaw) {
        Player player = ctx.player();
        BetterBlockPos target = null;
        for (int j = 0; j <= ext && target == null; j++) { // the cell next to the far platform first, each next one against the last
            BetterBlockPos c = cell(dest, sx, sz, -j).below();
            if (!MovementHelper.canWalkOn(ctx, c)) {
                target = c;
            }
        }
        if (target == null) {
            return;
        }
        // a block cannot go where the player's own box is (or will be next tick): keep moving off it, no click
        net.minecraft.world.phys.Vec3 mv = player.getDeltaMovement();
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        boolean inTheWay = box.expandTowards(mv.x, Math.max(0, mv.y), mv.z).inflate(0.02, 0, 0.02).setMinY(box.minY + 0.01)
                .intersects(new net.minecraft.world.phys.AABB(target.x, target.y, target.z, target.x + 1, target.y + 1, target.z + 1));
        baritone.getInventoryBehavior().selectThrowawayForLocation(true, target.x, target.y, target.z);
        Vec3 eye = ctx.playerHead();
        Rotation pre = null; // where the camera heads while the face is still out of reach, so it is there in time
        Rotation best = null; // the point of a reachable face the camera has the least turning to do for
        double bestTurn = Double.MAX_VALUE;
        net.minecraft.core.Direction bestFace = null;
        BetterBlockPos bestAgainst = null;
        Rotation cam = ctx.playerRotations();
        for (var dir : FACES) {
            BetterBlockPos against = new BetterBlockPos(target.x + dir.getStepX(), target.y + dir.getStepY(), target.z + dir.getStepZ());
            if (!MovementHelper.canPlaceAgainst(ctx, against)) {
                continue;
            }
            var face = dir.getOpposite();
            for (double u : new double[]{0, -0.3, 0.3}) {
                for (double v : new double[]{0, -0.3, 0.3}) {
                    Vec3 pt = new Vec3(against.x + 0.5 + face.getStepX() * 0.5 + (face.getStepX() == 0 ? u : 0),
                            against.y + 0.5 + face.getStepY() * 0.5 + (face.getStepY() == 0 ? (face.getStepX() == 0 ? v : u) : 0),
                            against.z + 0.5 + face.getStepZ() * 0.5 + (face.getStepZ() == 0 ? v : 0));
                    Rotation rot = RotationUtils.calcRotationFromVec3d(eye, pt, cam);
                    if (pre == null) {
                        pre = rot;
                    }
                    double turn = Math.abs(Mth.wrapDegrees(rot.getYaw() - cam.getYaw())) + Math.abs(rot.getPitch() - cam.getPitch());
                    if (turn < bestTurn && RayTraceUtils.rayTraceTowards(player, rot, ctx.playerController().getBlockReachDistance()) instanceof BlockHitResult h
                            && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(against) && h.getDirection() == face) {
                        best = rot;
                        bestTurn = turn;
                        bestFace = face;
                        bestAgainst = against;
                    }
                }
            }
        }
        if (best != null) {
            var in = baritone.getInputOverrideHandler();
            baritone.getLookBehavior().updateTarget(best, true);
            double rel = Math.toRadians(Mth.wrapDegrees(travelYaw - best.getYaw()));
            double fwd = Math.cos(rel), left = -Math.sin(rel);
            boolean sprinting = in.isInputForcedDown(Input.SPRINT);
            in.setInputForceState(Input.MOVE_FORWARD, fwd > 0.38);
            in.setInputForceState(Input.MOVE_BACK, fwd < -0.38);
            in.setInputForceState(Input.MOVE_LEFT, left > 0.38);
            in.setInputForceState(Input.MOVE_RIGHT, left < -0.38);
            in.setInputForceState(Input.SPRINT, sprinting && fwd > 0.38);
            if (inTheWay) {
                in.setInputForceState(Input.MOVE_FORWARD, true);
                in.setInputForceState(Input.MOVE_BACK, false);
                in.setInputForceState(Input.SPRINT, true);
            } else if (ctx.objectMouseOver() instanceof BlockHitResult cur && cur.getType() == HitResult.Type.BLOCK
                    && cur.getBlockPos().equals(bestAgainst) && cur.getDirection() == bestFace) {
                in.setInputForceState(Input.CLICK_RIGHT, true);
            }
        } else if (pre != null) {
            baritone.getLookBehavior().updateTarget(pre, true);
        }
    }

    private static BetterBlockPos cell(BetterBlockPos src, int sx, int sz, int k) {
        return new BetterBlockPos(src.x + k * sx, src.y, src.z + k * sz);
    }

    private static boolean planned(BetterBlockPos dest, int ext, int sx, int sz, int x, int z) {
        for (int j = 0; j <= ext; j++) {
            if (dest.x - j * sx == x && dest.z - j * sz == z) {
                return true;
            }
        }
        return false;
    }

    /** Would sprinting forward from the current state and jumping after {@code delay} ticks land on the destination? */
    private boolean jumpLands(BetterBlockPos dest, int ext, int sx, int sz, float yaw, int delay) {
        if (sim == null) {
            client = new ClientWorld(ctx);
            sim = new PlayerSim(new PlayerSim.World() {
                @Override
                public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, java.util.List<double[]> out) {
                    client.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
                    for (int j = 0; j <= ext; j++) { // the blocks that will be laid in mid-air count as there
                        BetterBlockPos c = cell(dest, sx, sz, -j).below();
                        if (c.x + 1 > minX && c.x < maxX && c.y + 1 > minY && c.y < maxY && c.z + 1 > minZ && c.z < maxZ) {
                            out.add(new double[]{c.x, c.y, c.z, c.x + 1, c.y + 1, c.z + 1});
                        }
                    }
                }

                @Override
                public float slipperiness(int x, int y, int z) {
                    return client.slipperiness(x, y, z);
                }
            });
        }
        client.reset();
        var p = ctx.player();
        sim.x = p.getX(); sim.y = p.getY(); sim.z = p.getZ();
        sim.vx = p.getDeltaMovement().x; sim.vy = p.getDeltaMovement().y; sim.vz = p.getDeltaMovement().z;
        sim.onGround = p.onGround();
        sim.sprinting = p.isSprinting();
        sim.collidedH = false;
        sim.jumpTicks = 0;
        boolean left = false;
        for (int t = 0; t < 60; t++) {
            sim.tick(yaw, true, true, t == delay);
            if (!sim.onGround) {
                left = true;
            } else if (left || t > delay + 2) {
                return left && Math.abs(sim.y - dest.y) < 0.01
                        && (planned(dest, ext, sx, sz, PlayerSim.floor(sim.x), PlayerSim.floor(sim.z))
                        || MovementHelper.canWalkOn(ctx, new BetterBlockPos(PlayerSim.floor(sim.x), dest.y - 1, PlayerSim.floor(sim.z))));
            }
            if (sim.y < dest.y - 1.5) {
                return false;
            }
        }
        return false;
    }

    /** From rest (give or take the back-up drift) does a sprint from here and the latest still-landing jump make it across? */
    private boolean runUpLands(BetterBlockPos dest, int ext, int sx, int sz, float yaw) {
        for (int d = 0; d < 40; d++) {
            if (jumpLands(dest, ext, sx, sz, yaw, d)) {
                return true;
            }
        }
        return false;
    }
}
