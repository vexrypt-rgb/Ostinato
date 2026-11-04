/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Cadenza: task-aware execution states. For one requested interaction (mine this block, open this container, place
 * into this cell) it finds the cells a player can stand in and do it from, and what is still left to pay once there.
 * <p>
 * It does not choose what to do (the caller does), does not move or click (the existing path executor and the
 * existing process interaction code do), and never reports an interaction as done: it only ranks standing cells.
 * <p>
 * Candidate cells are bounded to the box the reach allows around the target (at most 11 x 10 x 11 cells), filtered in
 * cost order: distance, then standing (floor, two clear cells, no fluid), then hazards, then the pick ray.
 */
public final class Cadenza {

    private Cadenza() {}

    public enum Kind {
        /** Break the target block with the left button. Eye standing. */
        MINE,
        /** Right-click the target block (a container, a table). Eye standing. */
        USE,
        /** Place a block into the (replaceable) target cell against a solid neighbour. Eye sneaking. */
        PLACE
    }

    /** Why no cell was returned. {@link #OK} means at least one candidate. */
    public enum Status {
        OK,
        /** The target's chunk is not loaded: nothing can be said. */
        TARGET_UNLOADED,
        /** The target cannot take this interaction (air to mine, solid to place into, nothing to place against). */
        TARGET_INVALID,
        /** No cell within reach has a floor and room to stand. */
        NO_STANDING,
        /** Cells to stand in exist, but the target cannot be seen (or not safely) from any of them. */
        OCCLUDED
    }

    /** Eye heights: standing and sneaking, the values the processes pass from the live player. */
    public static final double EYE_STANDING = 1.62, EYE_SNEAKING = 1.27;
    /** Keep this much reach in hand: the bot stops up to ~0.3 off the cell centre. */
    public static final double REACH_MARGIN = 0.35;
    /**
     * The eye offsets (in x and z) a stop can plausibly end at: a path ends as soon as the feet enter the cell, so the
     * player can stand up to about a third of a block off centre (live: eye x 13.17 in cell 13). Each offset that loses
     * sight costs {@link #ROBUST_TICKS}.
     */
    static final double[][] EYE_OFFSETS = {{0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {0.3, -0.3}, {-0.3, 0.3}, {-0.3, -0.3}};
    /**
     * What an unusable pose costs (live: dropping the cell and replanning took a chest lap from 60 to 314 ticks),
     * spread over the offsets: losing one of them is roughly that chance of paying it.
     */
    static final double POSE_RECOVERY_TICKS = 80;
    static final double ROBUST_TICKS = POSE_RECOVERY_TICKS / EYE_OFFSETS.length;
    /**
     * Mining needs the crosshair on the block for every tick of the break: one tick on a neighbour restarts it. The live
     * look lands up to about this far off the aim (randomLooking113, 2 by default: +-1 degree of yaw each tick, plus
     * the mouse step), so a cell mines from only if the aim ray, turned this far in yaw or pitch either way, still hits
     * the target. Live (TWO_POSE): from a cell four blocks along the wall the face spans +-1.2 degrees, the crosshair
     * kept slipping onto the next block and the ore was never broken within its budget.
     */
    public static final double AIM_TOLERANCE_DEG = 2.0;
    /** Reach used beyond (reach - this) costs {@link #FAR_TICKS_PER_BLOCK}: a long reach is a fragile reach. */
    static final double FAR_SLACK = 1.25, FAR_TICKS_PER_BLOCK = 2.0;
    /** A drop of 3+ next to the cell, or 5+ (or void). */
    static final double EDGE_TICKS = 3.0, DEEP_EDGE_TICKS = 5.0;
    static final double HAZARD_TICKS = 3.0;
    /** A falling block that would come down on the player when the target above is broken. */
    static final double FALLING_TICKS = 6.0;
    /** The target sits next to the cell at feet or head height: a slightly off-centre stop can block a place. */
    static final double PLACE_BESIDE_TICKS = 2.0;
    /** Look pitch costs this many ticks per degree past {@link #PITCH_FREE}; yaw depends on the approach and is free. */
    static final double PITCH_FREE = 30, PITCH_TICKS_PER_DEG = 1 / 64.0;

    /**
     * Cadenza v2: how long the camera takes to turn onto an aim. The aim processor moves yaw and pitch in the same tick,
     * each by at most {@link #capDeg} degrees (LookBehavior: {@code ostinato.aim.deg}, 32 by default, or less under
     * human input caps), and the processes click only once the crosshair is on the target. So a turn takes
     * ceil(max(|yaw|, |pitch|) / cap) ticks: the two axes overlap, they do not add.
     * <p>
     * The arrival look is estimated: yaw along the last path step (the movement executors face where they walk) and
     * the pitch the walk holds ({@link #walkPitch}; live 5 to 12 degrees down). The real heading is off that by some
     * degrees (live, stock Builder: 12 to 17 per arrival), so the turn is the mean over the step's yaw and
     * {@link #HEADING_SPREAD} either side of it. With no horizontal last step (the path is empty, or the last step is
     * straight up or down) the heading is unknown and {@link #UNKNOWN_YAW} is charged.
     */
    public static final class TurnModel {
        /** The mean yaw error over uniformly random headings (0 to 180 degrees). */
        public static final double UNKNOWN_YAW = 90;
        /** How far off the last step's yaw the arrival look plausibly is, degrees. */
        public static final double HEADING_SPREAD = 15;
        public final double capDeg, walkPitch;

        public TurnModel(double capDeg, double walkPitch) {
            this.capDeg = Math.max(1, capDeg);
            this.walkPitch = walkPitch;
        }

        /** Ticks to turn by these many degrees of yaw and pitch at once. */
        public double ticks(double dYaw, double dPitch) {
            double deg = Math.max(Math.abs(dYaw), Math.abs(dPitch));
            return deg <= 1e-9 ? 0 : Math.ceil(deg / capDeg - 1e-9);
        }

        /** Ticks from a look at {@code headingYaw} (NaN: unknown) and the walk pitch to the aim (yaw, pitch). */
        public double fromArrival(double headingYaw, double yaw, double pitch) {
            double dPitch = pitch - walkPitch;
            if (Double.isNaN(headingYaw)) {
                return ticks(UNKNOWN_YAW, dPitch);
            }
            double d = wrap(yaw - headingYaw);
            return (ticks(d - HEADING_SPREAD, dPitch) + ticks(d, dPitch) + ticks(d + HEADING_SPREAD, dPitch)) / 3;
        }

        /** Ticks to turn between two aims. */
        public double between(double yaw0, double pitch0, double yaw1, double pitch1) {
            return ticks(wrap(yaw1 - yaw0), pitch1 - pitch0);
        }

        /** The yaw of walking from (fx, fz) to (x, z), or NaN with no horizontal step. */
        public static double heading(int fx, int fz, int x, int z) {
            if (fx == x && fz == z) {
                return Double.NaN;
            }
            return Math.toDegrees(Math.atan2(-(x - fx), z - fz));
        }

        /** Into [-180, 180). */
        public static double wrap(double d) {
            d %= 360;
            if (d >= 180) d -= 360;
            if (d < -180) d += 360;
            return d;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TurnModel m && capDeg == m.capDeg && walkPitch == m.walkPitch;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(capDeg) * 31 + Double.hashCode(walkPitch);
        }
    }

    /** One interaction to position for. */
    public static final class Target {
        public final Kind kind;
        public final BetterBlockPos pos;
        public final double reach;
        public final double eye;
        /** The broken block drops an item the caller will walk to; charge the walk from the cell to the drop. */
        public final boolean collectDrop;

        public Target(Kind kind, BlockPos pos, double reach, double eye, boolean collectDrop) {
            this.kind = kind;
            this.pos = new BetterBlockPos(pos);
            this.reach = reach;
            this.eye = eye;
            this.collectDrop = collectDrop;
        }

        public static Target mine(BlockPos pos, double reach, boolean collectDrop) {
            return new Target(Kind.MINE, pos, reach, EYE_STANDING, collectDrop);
        }

        public static Target use(BlockPos pos, double reach) {
            return new Target(Kind.USE, pos, reach, EYE_STANDING, false);
        }

        public static Target place(BlockPos pos, double reach) {
            return new Target(Kind.PLACE, pos, reach, EYE_SNEAKING, false);
        }

        public Target withEye(double eye) {
            return new Target(kind, pos, reach, eye, collectDrop);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Target t && kind == t.kind && pos.equals(t.pos) && reach == t.reach && eye == t.eye && collectDrop == t.collectDrop;
        }

        @Override
        public int hashCode() {
            return (kind.hashCode() * 31 + pos.hashCode()) * 31 + Double.hashCode(reach) * 7 + Double.hashCode(eye) + (collectDrop ? 1 : 0);
        }

        @Override
        public String toString() {
            return kind + "@" + pos.x + "," + pos.y + "," + pos.z;
        }
    }

    /** A cell the target can be acted on from, and the ticks still to pay once standing there. */
    public static final class Candidate {
        public final int x, y, z;
        public final double terminal;
        /** Eye to the hit point, blocks; the look pitch in degrees; how many of the 4 offset eyes still see it. */
        public final double aimDistance, pitch;
        public final int robust;
        /** The face the ray hits (for PLACE, the face of the block placed against). */
        public final Direction face;
        /**
         * v2: the look yaw from the cell's centre eye to the aim (Minecraft degrees), and the part of {@link #terminal}
         * that is the heading-blind pitch charge (a turn model replaces it with the real turn).
         */
        public final double yaw, pitchTicks;

        Candidate(int x, int y, int z, double terminal, double aimDistance, double pitch, int robust, Direction face) {
            this(x, y, z, terminal, aimDistance, pitch, robust, face, 0, 0);
        }

        Candidate(int x, int y, int z, double terminal, double aimDistance, double pitch, int robust, Direction face, double yaw, double pitchTicks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.terminal = terminal;
            this.aimDistance = aimDistance;
            this.pitch = pitch;
            this.robust = robust;
            this.face = face;
            this.yaw = yaw;
            this.pitchTicks = pitchTicks;
        }

        /** The terminal cost without the heading-blind pitch charge. */
        public double base() {
            return terminal - pitchTicks;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Candidate c && x == c.x && y == c.y && z == c.z && terminal == c.terminal && yaw == c.yaw && pitch == c.pitch
                    && pitchTicks == c.pitchTicks && face == c.face;
        }

        @Override
        public int hashCode() {
            return ((x * 31 + y) * 31 + z) * 31 + Double.hashCode(terminal);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%d,%d,%d t=%.2f d=%.2f p=%.0f r=%d", x, y, z, terminal, aimDistance, pitch, robust);
        }
    }

    /** Candidates for one target, best first, or the reason there are none. */
    public static final class Result {
        public final Target target;
        public final Status status;
        public final List<Candidate> candidates;
        /** Cells looked at past the distance filter, standable cells, rays cast. */
        public final int examined, standable, rays;

        Result(Target target, Status status, List<Candidate> candidates, int examined, int standable, int rays) {
            this.target = target;
            this.status = status;
            this.candidates = candidates;
            this.examined = examined;
            this.standable = standable;
            this.rays = rays;
        }

        public boolean ok() {
            return status == Status.OK;
        }

        public Candidate best() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }

        /** Same target, status and candidates (the work counters are not compared). */
        @Override
        public boolean equals(Object o) {
            return o instanceof Result r && target.equals(r.target) && status == r.status && candidates.equals(r.candidates);
        }

        @Override
        public int hashCode() {
            return (target.hashCode() * 31 + status.hashCode()) * 31 + candidates.hashCode();
        }

        @Override
        public String toString() {
            return target + " " + status + " n=" + candidates.size() + (candidates.isEmpty() ? "" : " best=" + best());
        }
    }

    /** Cells that must not be stood on (other targets about to be broken), as {@link BetterBlockPos#longHash}. */
    public interface Forbidden {
        boolean contains(int x, int y, int z);

        Forbidden NONE = (x, y, z) -> false;
    }

    /**
     * The caller's own limits on where it acts from (e.g. the builder only places within its search box). Candidates a
     * filter refuses are dropped before the goal is built.
     */
    public interface CellFilter {
        boolean accept(Target t, int x, int y, int z);

        CellFilter ALL = (t, x, y, z) -> true;
    }

    /**
     * All cells the target can be acted on from, best (lowest terminal cost) first. Reads only {@code ctx.bsi}, so it
     * is safe wherever that is.
     */
    public static Result candidates(CalculationContext ctx, Target t, Forbidden forbidden) {
        BlockStateInterface bsi = ctx.bsi;
        int tx = t.pos.x, ty = t.pos.y, tz = t.pos.z;
        if (!bsi.worldContainsLoadedChunk(tx, tz)) {
            return new Result(t, Status.TARGET_UNLOADED, List.of(), 0, 0, 0);
        }
        BlockState targetState = bsi.get0(tx, ty, tz);
        List<Vec3> aims = new ArrayList<>(8);
        List<Direction> aimFaces = new ArrayList<>(8);
        if (!aimPoints(bsi, t, targetState, aims, aimFaces)) {
            return new Result(t, Status.TARGET_INVALID, List.of(), 0, 0, 0);
        }
        AABB box = new AABB(tx, ty, tz, tx + 1, ty + 1, tz + 1);
        double usable = t.reach - REACH_MARGIN;
        int r = (int) Math.ceil(usable) + 1;
        int yLo = (int) Math.floor(ty - t.eye - usable) - 1, yHi = (int) Math.ceil(ty + 1 - t.eye + usable);
        List<Candidate> out = new ArrayList<>();
        CadenzaRay.Hit hit = new CadenzaRay.Hit();
        int examined = 0, standable = 0, rays = 0;
        for (int y = yLo; y <= yHi; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = tx + dx, z = tz + dz;
                    double ex = x + 0.5, ey = y + t.eye, ez = z + 0.5;
                    if (box.distanceToSqr(new Vec3(ex, ey, ez)) > usable * usable) {
                        continue;
                    }
                    examined++;
                    if (!canStand(ctx, t, x, y, z, forbidden)) {
                        continue;
                    }
                    double risk = risk(bsi, t, x, y, z);
                    if (risk >= ActionCosts.COST_INF) {
                        continue;
                    }
                    standable++;
                    // the centre eye must see the target within the usable reach
                    int best = -1;
                    double bestDist = 0;
                    for (int i = 0; i < aims.size(); i++) {
                        rays++;
                        Vec3 eye = new Vec3(ex, ey, ez);
                        if (sees(bsi, t, eye, aims.get(i), aimFaces.get(i), hit) && hit.distance <= usable) {
                            best = i;
                            bestDist = hit.distance;
                            break;
                        }
                    }
                    if (best < 0) {
                        continue;
                    }
                    if (t.kind == Kind.MINE && !steady(bsi, t, new Vec3(ex, ey, ez), aims.get(best), aimFaces.get(best), hit)) {
                        continue; // the face is too thin from here for the live aim to stay on it
                    }
                    int robust = 0;
                    for (double[] o : EYE_OFFSETS) {
                        Vec3 eye = new Vec3(ex + o[0], ey, ez + o[1]);
                        for (int i = 0; i < aims.size(); i++) {
                            rays++;
                            if (sees(bsi, t, eye, aims.get(i), aimFaces.get(i), hit) && hit.distance <= t.reach) {
                                robust++;
                                break;
                            }
                        }
                    }
                    Vec3 aim = aims.get(best);
                    double pitch = Math.toDegrees(Math.atan2(-(aim.y - ey), Math.hypot(aim.x - ex, aim.z - ez)));
                    double yaw = Math.toDegrees(Math.atan2(-(aim.x - ex), aim.z - ez));
                    double pitchTicks = Math.max(0, Math.abs(pitch) - PITCH_FREE) * PITCH_TICKS_PER_DEG;
                    double terminal = risk
                            + (EYE_OFFSETS.length - robust) * ROBUST_TICKS
                            + Math.max(0, bestDist - (t.reach - FAR_SLACK)) * FAR_TICKS_PER_BLOCK
                            + pitchTicks
                            + setup(ctx, t, x, y, z);
                    out.add(new Candidate(x, y, z, terminal, bestDist, pitch, robust, aimFaces.get(best), yaw, pitchTicks));
                }
            }
        }
        if (out.isEmpty()) {
            return new Result(t, standable == 0 ? Status.NO_STANDING : Status.OCCLUDED, List.of(), examined, standable, rays);
        }
        // best first; ties by position so the order is deterministic
        out.sort((a, b) -> a.terminal != b.terminal ? Double.compare(a.terminal, b.terminal)
                : a.y != b.y ? Integer.compare(a.y, b.y) : a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z));
        return new Result(t, Status.OK, out, examined, standable, rays);
    }

    /**
     * The points to aim at, as {@code RotationUtils.reachable} tries them (MINE / USE: shape centre, then the six face
     * centres), or the face centre and four inset points of every face the block could be placed against (PLACE).
     * False when the target cannot take this interaction.
     */
    static boolean aimPoints(BlockStateInterface bsi, Target t, BlockState state, List<Vec3> aims, List<Direction> faces) {
        int tx = t.pos.x, ty = t.pos.y, tz = t.pos.z;
        BlockPos p = t.pos;
        if (t.kind == Kind.PLACE) {
            if (!MovementHelper.isReplaceable(tx, ty, tz, state, bsi)) {
                return false;
            }
            for (Direction d : Direction.values()) {
                int ax = tx + d.getStepX(), ay = ty + d.getStepY(), az = tz + d.getStepZ();
                BlockState against = bsi.get0(ax, ay, az);
                if (!MovementHelper.canPlaceAgainst(bsi, ax, ay, az, against)) {
                    continue;
                }
                VoxelShape shape = CadenzaRay.outline(against, new BlockPos(ax, ay, az));
                if (shape.isEmpty()) {
                    continue;
                }
                AABB bb = shape.bounds().move(ax, ay, az);
                // the face of the neighbour that looks at the target cell
                Direction face = d.getOpposite();
                double fx = face.getStepX() > 0 ? bb.maxX : face.getStepX() < 0 ? bb.minX : (bb.minX + bb.maxX) / 2;
                double fy = face.getStepY() > 0 ? bb.maxY : face.getStepY() < 0 ? bb.minY : (bb.minY + bb.maxY) / 2;
                double fz = face.getStepZ() > 0 ? bb.maxZ : face.getStepZ() < 0 ? bb.minZ : (bb.minZ + bb.maxZ) / 2;
                aims.add(new Vec3(fx, fy, fz));
                faces.add(face);
                for (double[] o : new double[][]{{0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}}) {
                    double ox = 0, oy = 0, oz = 0;
                    switch (face.getAxis()) {
                        case X -> { oy = o[0]; oz = o[1]; }
                        case Y -> { ox = o[0]; oz = o[1]; }
                        case Z -> { ox = o[0]; oy = o[1]; }
                    }
                    aims.add(new Vec3(fx + ox * (bb.maxX - bb.minX), fy + oy * (bb.maxY - bb.minY), fz + oz * (bb.maxZ - bb.minZ)));
                    faces.add(face);
                }
            }
            return !aims.isEmpty();
        }
        if (state.isAir() || MovementHelper.isWater(state) || MovementHelper.isLava(state)) {
            return false;
        }
        VoxelShape outline = CadenzaRay.outline(state, p);
        if (outline.isEmpty() && !(state.getBlock() instanceof BaseFireBlock)) {
            return false;
        }
        VoxelShape collision;
        try {
            collision = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, p);
        } catch (RuntimeException e) {
            collision = outline;
        }
        if (collision.isEmpty()) {
            aims.add(new Vec3(tx + 0.5, ty + 0.5, tz + 0.5));
        } else {
            AABB c = collision.bounds();
            aims.add(new Vec3(tx + (c.minX + c.maxX) / 2, ty + (c.minY + c.maxY) / 2, tz + (c.minZ + c.maxZ) / 2));
        }
        faces.add(null);
        AABB o = (outline.isEmpty() ? net.minecraft.world.phys.shapes.Shapes.block() : outline).bounds();
        // RotationUtils.BLOCK_SIDE_MULTIPLIERS: down, up, north, south, west, east
        double[][] m = {{0.5, 0, 0.5}, {0.5, 1, 0.5}, {0.5, 0.5, 0}, {0.5, 0.5, 1}, {0, 0.5, 0.5}, {1, 0.5, 0.5}};
        for (double[] s : m) {
            aims.add(new Vec3(tx + o.minX * s[0] + o.maxX * (1 - s[0]), ty + o.minY * s[1] + o.maxY * (1 - s[1]), tz + o.minZ * s[2] + o.maxZ * (1 - s[2])));
            faces.add(null);
        }
        return true;
    }

    /** Whether the ray from {@code eye} toward {@code aim} hits what this interaction needs to hit. */
    /** Whether the ray from {@code eye} at {@code aim}, turned {@link #AIM_TOLERANCE_DEG} in yaw or pitch, still sees the target. */
    static boolean steady(BlockStateInterface bsi, Target t, Vec3 eye, Vec3 aim, Direction face, CadenzaRay.Hit hit) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double yaw = Math.atan2(-dx, dz), pitch = Math.atan2(-dy, Math.hypot(dx, dz)), a = Math.toRadians(AIM_TOLERANCE_DEG);
        for (int i = 0; i < 4; i++) {
            double y = yaw + (i == 0 ? a : i == 1 ? -a : 0), p = pitch + (i == 2 ? a : i == 3 ? -a : 0);
            Vec3 dir = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
            if (!sees(bsi, t, eye, eye.add(dir), face, hit)) {
                return false;
            }
        }
        return true;
    }

    static boolean sees(BlockStateInterface bsi, Target t, Vec3 eye, Vec3 aim, Direction face, CadenzaRay.Hit hit) {
        CadenzaRay.cast(bsi, eye, aim, t.reach, hit);
        if (!hit.hit) {
            return false;
        }
        if (t.kind == Kind.PLACE) {
            // must land on the neighbour's face that looks at the target cell
            return face != null && hit.face == face
                    && hit.x == t.pos.x - face.getStepX() && hit.y == t.pos.y - face.getStepY() && hit.z == t.pos.z - face.getStepZ();
        }
        if (hit.is(t.pos)) {
            return true;
        }
        // RotationUtils: fire is aimed at through the block under it
        return bsi.get0(t.pos.x, t.pos.y, t.pos.z).getBlock() instanceof BaseFireBlock
                && hit.x == t.pos.x && hit.y == t.pos.y - 1 && hit.z == t.pos.z;
    }

    /** Floor under, feet and head clear and dry, not inside or on top of something about to be broken. */
    static boolean canStand(CalculationContext ctx, Target t, int x, int y, int z, Forbidden forbidden) {
        BlockStateInterface bsi = ctx.bsi;
        if (!bsi.worldContainsLoadedChunk(x, z) || y - 1 < ctx.minY || y + 1 > ctx.maxY) {
            return false;
        }
        int tx = t.pos.x, ty = t.pos.y, tz = t.pos.z;
        if (x == tx && z == tz && (ty == y || ty == y + 1 || (t.kind == Kind.MINE && ty == y - 1))) {
            return false; // in the target, or (mining) standing on it
        }
        if (forbidden.contains(x, y - 1, z)) {
            return false;
        }
        BlockState feet = bsi.get0(x, y, z), head = bsi.get0(x, y + 1, z), floor = bsi.get0(x, y - 1, z);
        if (MovementHelper.isWater(feet) || MovementHelper.isWater(head) || MovementHelper.isLava(feet) || MovementHelper.isLava(head)) {
            return false;
        }
        return MovementHelper.canWalkThrough(bsi, x, y, z, feet) && MovementHelper.canWalkThrough(bsi, x, y + 1, z, head)
                && MovementHelper.canWalkOn(bsi, x, y - 1, z, floor);
    }

    /**
     * Ticks of risk standing here, or COST_INF when it is not safe at all: lava or fire next to the feet or the floor
     * is refused; a drop or a walk-into hazard next to the cell, or a falling block about to come down, costs.
     */
    static double risk(BlockStateInterface bsi, Target t, int x, int y, int z) {
        double risk = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy != -1) {
                        continue;
                    }
                    BlockState s = bsi.get0(x + dx, y + dy, z + dz);
                    if (MovementHelper.isLava(s) || s.getBlock() instanceof BaseFireBlock) {
                        return ActionCosts.COST_INF;
                    }
                    if (dy == 0 && (dx == 0 || dz == 0) && MovementHelper.avoidWalkingInto(s)) {
                        risk += HAZARD_TICKS;
                    }
                }
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!MovementHelper.canWalkThrough(bsi, nx, y, nz) || !MovementHelper.canWalkThrough(bsi, nx, y + 1, nz)) {
                continue; // walled: nothing to fall into
            }
            int depth = 0;
            while (depth < 6 && !MovementHelper.canWalkOn(bsi, nx, y - 1 - depth, nz)) {
                depth++;
            }
            if (depth >= 5) {
                risk += DEEP_EDGE_TICKS;
            } else if (depth >= 3) {
                risk += EDGE_TICKS;
            }
        }
        // mining straight up under a falling block brings it down on the head
        if (t.kind == Kind.MINE && t.pos.x == x && t.pos.z == z && t.pos.y > y + 1
                && bsi.get0(t.pos.x, t.pos.y + 1, t.pos.z).getBlock() instanceof FallingBlock) {
            risk += FALLING_TICKS;
        }
        return risk;
    }

    /**
     * Interaction-specific ticks: walking to the drop after a break, or the chance a slightly off-centre stop blocks
     * a place beside the feet. Travel to the cell is the search's job and is never counted here.
     */
    static double setup(CalculationContext ctx, Target t, int x, int y, int z) {
        double cost = 0;
        int tx = t.pos.x, ty = t.pos.y, tz = t.pos.z;
        if (t.kind == Kind.MINE && t.collectDrop) {
            // the drop pops from the target's cell and is picked up within about a block; walk the rest
            double h = Math.hypot(tx + 0.5 - (x + 0.5), tz + 0.5 - (z + 0.5));
            double below = Math.max(0, y - ty - 1); // a drop below the floor has to be walked down to
            cost += (Math.max(0, h - 1.0) + below) * ActionCosts.WALK_ONE_BLOCK_COST;
        }
        if (t.kind == Kind.PLACE && (ty == y || ty == y + 1) && Math.abs(tx - x) + Math.abs(tz - z) == 1) {
            cost += PLACE_BESIDE_TICKS;
        }
        return cost;
    }
}
