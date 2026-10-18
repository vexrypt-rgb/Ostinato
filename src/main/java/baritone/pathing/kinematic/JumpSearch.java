package baritone.pathing.kinematic;

/**
 * Open-loop search for one parkour jump onto a single block: sprint along the approach direction (optionally
 * hopping on the run-up), jump at a chosen point relative to the take-off block's front edge, turn in the air and
 * let go of forward so the landing stops on the block. Used offline to build {@code JumpTemplates} and at run time
 * by {@code MovementJump}, so a planned jump is flown with the same controls it was planned with.
 */
public final class JumpSearch {

    public static final float[] O0 = {-15, 0, 15, 30, 45, 60};
    public static final int[] HOP = {0, 1};
    /** The last is past where a plain block drops you: only searched with {@link #ledge}. */
    public static final double[] EDGE = {-0.5, -0.3, -0.1, 0.1, 0.25, 0.4};
    public static final int PLAIN_EDGES = 5;
    /**
     * Ostinato: +-90 lets a neo-up swing straight back across onto a one-wide block after clearing the wall's end
     * (Neo Practice's second jump settled a sliver off the edge at +-70). Plans are stored as one base-36 digit each.
     */
    public static final float[] O1 = {-90, -70, -45, -25, -10, 0, 10, 25, 45, 70, 90};
    /** Index of O1 = 0: fly straight on. */
    public static final int O1_STRAIGHT = 5;
    public static final int[] SWITCH = {0, 4};
    public static final int NEVER = 999;
    public static final int[] RELEASE = {NEVER, 4, 7, 10};
    /** After release: 0 let go of forward, 1 hold back to brake. */
    public static final int[] BRAKE = {0, 1};
    public static final int DIMS = 7;

    private static final int MAX_GROUND = 40, MAX_AIR = 40, SETTLE = 8, HANG = 3;

    /** Frame of one jump: approach unit vector (dx, dz), front edge coordinate along it, and the landing block. */
    public double dirX, dirZ, edge;
    public int destX, destY, destZ;
    /** 1, or -1 to mirror the yaw offsets when the lateral side is flipped. */
    public int side = 1;
    /** Feet height on a slime block the jump bounces off on the way, else {@link Integer#MAX_VALUE}. */
    public int bounceY = Integer.MAX_VALUE;
    /** Accept wherever the plan settles after a bounce, writing the landing block to destX/Y/Z. */
    public boolean anyDest;
    /** Never press jump: walk off the edge (onto a slime pad right below it). */
    public boolean noJump;
    /**
     * The destination is a ladder or vine cell: the jump is done when the player hangs on to it, rather than when it
     * settles on the ground. Start a leap off a ladder with {@link #jumped} set and {@link #noJump}.
     */
    public boolean grab;
    /**
     * Off a ladder by its top: climb from hanging until standing on the top edge of its plate (a 1 tall ladder ends
     * there), and jump from it at once. Start hanging, with {@link #jumped} clear.
     */
    public boolean standJump;
    /**
     * Done at the landing tick, on the block (destX, destY, destZ), with {@link #sim()} left in the landing state instead
     * of settling: the first half of a chain, whose second jump starts from that momentum.
     */
    public boolean carry;
    /**
     * The take-off block's front edge carries a ledge at its top (a ladder's plate juts 3/16 out): also try jumping from
     * further out than a plain block holds the player.
     */
    public boolean ledge;
    /** Plan indices into the grids above, and the jumped/air-tick state carried between real ticks. */
    public final int[] plan = new int[DIMS];
    public boolean jumped;
    public int airTicks;
    /**
     * The look turns at most {@link #YAW_STEP} degrees a tick (LookBehavior's aim cap). Template search leaves it off.
     * MovementJump turns it on for one search when the player is facing away from the plan, then flies that plan.
     * Waiting out a yaw error dropped the run-up speed and the 2-wall neo missed, so a failed capped search still
     * flies the snap plan. Set this, and {@link #faceYaw}, to ask whether a plan survives the cap.
     */
    public boolean slewYaw;
    public float faceYaw;
    /**
     * Ostinato: only whether some plan lands, not the best one (the planner's cost check throws the plan away): the
     * starting plan is flown first and the grid stops at the first landing. The answer is the same; {@link #plan} is
     * then a landing plan, not the best. Saves most of the grid (thousands of runs) when the jump is good.
     */
    public boolean anyLanding;
    /** Degrees per tick, matching {@code LookBehavior}'s aim cap at the default mouse sensitivity. */
    public static final float YAW_STEP = 32f;
    /** Filled by {@link #run}: ticks until settled and the settled distance from the landing block centre. */
    public int ticks;
    public double miss;
    /** Filled by {@link #run} in {@link #carry} mode: speed along the approach direction at the landing tick. */
    public double landSpeed;

    /**
     * Ostinato: run the full {@link #search} in the native library when it is loaded ({@link NativePlanners}), the grid
     * split over a few threads; the answer is the same. The local search and {@link #searchStraight} stay in Java: a few
     * hundred runs cost about what the call does.
     */
    public boolean nativeSearch;

    private final PlayerSim sim;

    public JumpSearch(PlayerSim.World world) {
        this.sim = new PlayerSim(world);
    }

    public PlayerSim sim() {
        return sim;
    }

    public float approachYaw() {
        return (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
    }

    /** Controls for the next tick of plan {@code p} from state {@code s}: yaw, forward, jump (sprint = forward). */
    public float yaw(int[] p, boolean jumped, int airTicks) {
        return approachYaw() + side * (jumped && airTicks >= SWITCH[p[4]] ? O1[p[3]] : O0[p[0]]);
    }

    /** Shortest-path step from {@code from} toward {@code to}, at most {@link #YAW_STEP} degrees. */
    public static float stepYaw(float from, float to) {
        float d = to - from;
        d %= 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        if (d > YAW_STEP) {
            d = YAW_STEP;
        } else if (d < -YAW_STEP) {
            d = -YAW_STEP;
        }
        return from + d;
    }

    /** Forward key impulse: 1 forward, 0 none, -1 back. */
    public int input(int[] p, boolean jumped, int airTicks) {
        return !jumped || airTicks < RELEASE[p[5]] ? 1 : -BRAKE[p[6]];
    }

    public boolean jump(int[] p, PlayerSim s, boolean jumped) {
        if (jumped || !s.onGround || noJump) {
            return false;
        }
        if (standJump) {
            return true;
        }
        double along = s.x * dirX + s.z * dirZ;
        return along >= edge + EDGE[p[2]] || (HOP[p[1]] == 1 && along < edge + EDGE[p[2]] - 1.2);
    }

    /**
     * Simulates plan {@code p} from {@code start} with the given jump state. The take-off is the jump pressed at or
     * past the edge trigger; hops before it are part of the run-up.
     *
     * @return true if the player settles on the landing block; {@link #ticks} and {@link #miss} describe the result
     */
    public boolean run(PlayerSim start, int[] p, boolean jumped0, int air0, CellSink sweep) {
        sim.copyFrom(start);
        boolean jumped = jumped0;
        int air = air0, settle = -1;
        boolean bounced = false;
        float facing = faceYaw;
        double floor = Math.min(Math.min(PlayerSim.floor(start.y + 0.01), destY), bounceY) - 0.6;
        for (int t = 0; t < MAX_GROUND + MAX_AIR + SETTLE; t++) {
            if (!jumped && t >= MAX_GROUND) {
                return false;
            }
            boolean j = jump(p, sim, jumped);
            boolean takeoff = j && (standJump || sim.x * dirX + sim.z * dirZ >= edge + EDGE[p[2]]);
            int in = settle < 0 ? input(p, jumped, air) : 0;
            float cmd = yaw(p, jumped, air);
            if (slewYaw) {
                facing = stepYaw(facing, cmd);
                cmd = facing;
            }
            if (jumped && sim.inWater()) {
                sim.tickWater(cmd, 0, in > 0, false, false, false); // Ostinato: falling through water onto a pad
            } else {
                sim.tick(cmd, in, in > 0, j);
            }
            if (takeoff || (!jumped && !sim.onGround && bounceY != Integer.MAX_VALUE && sim.x * dirX + sim.z * dirZ > edge)) {
                jumped = true; // toward a slime pad, walking off the edge is a take-off too
                air = 0;
            } else if (jumped) {
                air++;
            }
            if (sweep != null) {
                sweep.box(sim.x, sim.y, sim.z);
            }
            if (sim.y < floor) {
                return false;
            }
            if (grab && jumped && air >= 1 && PlayerSim.floor(sim.x) == destX && PlayerSim.floor(sim.y) == destY
                    && PlayerSim.floor(sim.z) == destZ && sim.climbable()) {
                return holds(t + 1, sweep);
            }
            if (sim.onGround && sim.vy > 0.1) {
                bounced = true;
            }
            if (jumped && air > 1 && sim.onGround && settle < 0 && sim.vy <= 0.1) { // a slime bounce is not the landing
                if (anyDest) {
                    if (!bounced) {
                        return false;
                    }
                    destX = PlayerSim.floor(sim.x);
                    destY = PlayerSim.feetCell(sim.y);
                    destZ = PlayerSim.floor(sim.z);
                }
                if (!PlayerSim.nodeHeight(sim.y, destY)) {
                    return false; // came down somewhere else first
                }
                if (carry) {
                    if (PlayerSim.floor(sim.x) != destX || PlayerSim.floor(sim.z) != destZ) {
                        return false;
                    }
                    ticks = t + 1;
                    miss = Math.max(Math.abs(sim.x - (destX + 0.5)), Math.abs(sim.z - (destZ + 0.5)));
                    landSpeed = sim.vx * dirX + sim.vz * dirZ;
                    return true;
                }
                settle = 0;
            }
            if (settle >= 0 && settle++ >= SETTLE) {
                double cx = sim.x - (destX + 0.5), cz = sim.z - (destZ + 0.5);
                // still on the block, by as little as a sliver of the box; the mover steps to its centre after
                double reach = 0.5 + PlayerSim.HALF_WIDTH - 1e-4;
                if (Math.abs(cx) > reach || Math.abs(cz) > reach || !sim.onGround) {
                    return false;
                }
                ticks = t + 1;
                miss = Math.max(Math.abs(cx), Math.abs(cz));
                return true;
            }
        }
        return false;
    }

    /** The hang on to a ladder has to last: let go of everything and check the cell still holds us for a few ticks. */
    private boolean holds(int tick, CellSink sweep) {
        for (int k = 0; k < HANG; k++) {
            sim.tick(0, 0, false, false);
            if (sweep != null) {
                sweep.box(sim.x, sim.y, sim.z);
            }
            if (!sim.climbable()) {
                return false;
            }
        }
        ticks = tick;
        miss = 0;
        return true;
    }

    /**
     * Best plan over the whole grid (or the neighbourhood of {@link #plan} when {@code local}), written to
     * {@link #plan}. Prefers landing near the centre, then fewer ticks.
     */
    public boolean search(PlayerSim start, boolean local) {
        if (anyLanding && run(start, plan, jumped, airTicks, null)) {
            return true;
        }
        return nativeSearch ? searchNative(start, local) : searchJava(start, local);
    }

    private boolean searchJava(PlayerSim start, boolean local) {
        int[] best = null, p = new int[DIMS], c = plan.clone();
        double bestScore = Double.MAX_VALUE;
        int[] size = {O0.length, HOP.length, ledge ? EDGE.length : PLAIN_EDGES, O1.length, SWITCH.length, RELEASE.length, BRAKE.length};
        int[] lo = new int[DIMS], hi = new int[DIMS];
        for (int k = 0; k < DIMS; k++) {
            boolean fixed = jumped && k <= 2; // run-up choices are spent once in the air
            lo[k] = fixed ? c[k] : local ? Math.max(0, c[k] - 1) : 0;
            hi[k] = fixed ? c[k] : local ? Math.min(size[k] - 1, c[k] + 1) : size[k] - 1;
        }
        for (p[0] = lo[0]; p[0] <= hi[0]; p[0]++) for (p[1] = lo[1]; p[1] <= hi[1]; p[1]++)
            for (p[2] = lo[2]; p[2] <= hi[2]; p[2]++) for (p[3] = lo[3]; p[3] <= hi[3]; p[3]++)
                for (p[4] = lo[4]; p[4] <= hi[4]; p[4]++) for (p[5] = lo[5]; p[5] <= hi[5]; p[5]++)
                    for (p[6] = lo[6]; p[6] <= hi[6]; p[6]++) {
                    if (!run(start, p, jumped, airTicks, null)) {
                        continue;
                    }
                    if (anyLanding) {
                        System.arraycopy(p, 0, plan, 0, DIMS);
                        return true;
                    }
                    // a chain's first jump must land with speed left for the second one, whatever it costs in centring
                    double score = carry ? -landSpeed + 0.004 * ticks : miss + 0.004 * ticks;
                    if (score < bestScore) {
                        bestScore = score;
                        best = p.clone();
                    }
                }
        if (best == null) {
            return false;
        }
        System.arraycopy(best, 0, plan, 0, DIMS);
        run(start, plan, jumped, airTicks, null); // leave ticks/miss describing the chosen plan
        return true;
    }

    private boolean searchNative(PlayerSim start, boolean local) {
        JumpSearch ref = NativePlanners.verify ? copy() : null;
        int r = NativePlanners.jumpSearch(this, start, local);
        if (r == NativePlanners.UNKNOWN) {
            return searchJava(start, local);
        }
        if (ref != null) {
            boolean refOk = ref.searchJava(start, local);
            NativePlanners.checked.incrementAndGet();
            if (refOk != (r == 1) || !java.util.Arrays.equals(plan, ref.plan) || ticks != ref.ticks || Double.compare(miss, ref.miss) != 0
                    || Double.compare(landSpeed, ref.landSpeed) != 0 || destX != ref.destX || destY != ref.destY || destZ != ref.destZ
                    || !java.util.Arrays.equals(NativeTas.state(sim), NativeTas.state(ref.sim))) {
                throw new AssertionError("native jump search " + (r == 1) + " " + java.util.Arrays.toString(plan)
                        + " t" + ticks + " m" + miss + " s" + landSpeed + " d" + destX + "," + destY + "," + destZ + " " + java.util.Arrays.toString(NativeTas.state(sim))
                        + " differs from Java " + refOk + " " + java.util.Arrays.toString(ref.plan) + " t" + ref.ticks + " m" + ref.miss + " s" + ref.landSpeed
                        + " d" + ref.destX + "," + ref.destY + "," + ref.destZ + " " + java.util.Arrays.toString(NativeTas.state(ref.sim)));
            }
        }
        return r == 1;
    }

    /** A search with this one's frame, flags and state, for checking the native answer. */
    private JumpSearch copy() {
        JumpSearch c = new JumpSearch(sim.world());
        c.dirX = dirX;
        c.dirZ = dirZ;
        c.edge = edge;
        c.destX = destX;
        c.destY = destY;
        c.destZ = destZ;
        c.side = side;
        c.bounceY = bounceY;
        c.anyDest = anyDest;
        c.noJump = noJump;
        c.grab = grab;
        c.standJump = standJump;
        c.carry = carry;
        c.ledge = ledge;
        System.arraycopy(plan, 0, c.plan, 0, DIMS);
        c.jumped = jumped;
        c.airTicks = airTicks;
        c.slewYaw = slewYaw;
        c.faceYaw = faceYaw;
        c.anyLanding = anyLanding;
        c.ticks = ticks;
        c.miss = miss;
        c.landSpeed = landSpeed;
        c.sim.copyFrom(sim);
        return c;
    }

    /** Straight-line plans only (no turning): the cheap search the planner runs for slime bounces. */
    public boolean searchStraight(PlayerSim start) {
        int[] best = null, p = new int[DIMS];
        double bestScore = Double.MAX_VALUE;
        p[0] = 1; // O0 = 0
        p[3] = O1_STRAIGHT;
        for (p[1] = 0; p[1] < HOP.length; p[1]++) for (p[2] = 0; p[2] < (ledge ? EDGE.length : PLAIN_EDGES); p[2]++)
            for (p[5] = 0; p[5] < RELEASE.length; p[5]++) for (p[6] = 0; p[6] < BRAKE.length; p[6]++) {
                if (p[5] == 0 && p[6] == 1) {
                    continue; // never released: the brake choice is moot
                }
                if (!run(start, p, false, 0, null)) {
                    continue;
                }
                double score = miss + 0.004 * ticks;
                if (score < bestScore) {
                    bestScore = score;
                    best = p.clone();
                }
            }
        if (best == null) {
            return false;
        }
        System.arraycopy(best, 0, plan, 0, DIMS);
        run(start, plan, false, 0, null);
        return true;
    }

    public interface CellSink {
        void box(double x, double y, double z);
    }
}
