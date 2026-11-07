/*
 * Ostinato: smallest comparison of an existing headless navigator and a trivial alternative.
 * Does not change PlayerSim, PhysicsPathfinder, or the predictive physics engine.
 * Not part of upstream Baritone.
 */
package baritone.contract;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.physics.PhysicsPathfinder;
import baritone.physics.BlockInfo;
import baritone.physics.Predictor;
import baritone.physics.Terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One flat sprint, five blocks along +Z.
 * {@code physics-search} and {@code trivial-sprint} share the PlayerSim world, the settled start, and the budgets.
 * PPE is measured on a stone floor of the same shape and is labeled not equivalent: its tick is 26.3 and its input
 * is one chord held for the whole horizon. Stock A* is named and not executed.
 */
public final class ComparisonHarness {

    public static final String PPE_SEMANTICS = "PPE-26.3-living-tick";
    public static final String PLANNER_SEMANTICS = "planner-cost-not-PlayerSim";
    private static final String REQUIREMENTS = "onGround after 3 idle ticks, velocity cleared, feet y=0, yaw 0";
    private static final int NODE_BUDGET = 1500;
    private static final int TICK_BUDGET = 200;
    private static final int PPE_HORIZON = 40;
    private static final double GOAL_X = 0.5;
    private static final double GOAL_Y = 0;
    private static final double GOAL_Z = 5.5;
    private static final double RADIUS = 0.35;

    private ComparisonHarness() {}

    public static List<Measurement> measureFlat() {
        FlatBlocks blocks = new FlatBlocks();
        for (int z = -1; z <= 8; z++) {
            for (int x = -1; x <= 1; x++) {
                blocks.add(x, -1, z);
            }
        }
        PlayerSimPhysics settled = PlayerSimPhysics.settled(blocks, 0.5, 0.5);
        if (!settled.sim().onGround) {
            throw new IllegalStateException("settle did not land");
        }
        Scenario scenario = new Scenario("flat-sprint-5", new WorldAccess() {
            @Override
            public String semantics() {
                return PlayerSimPhysics.SEMANTICS;
            }
        }, REQUIREMENTS, settled.state(), GOAL_X, GOAL_Y, GOAL_Z, RADIUS, TICK_BUDGET, NODE_BUDGET);
        List<Measurement> rows = new ArrayList<>();
        rows.add(physicsSearch(blocks, settled, scenario));
        rows.add(trivialSprint(settled, scenario));
        rows.add(ppeHeldSprint());
        rows.add(stockAStar());
        return List.copyOf(rows);
    }

    public static String report(List<Measurement> rows) {
        StringBuilder out = new StringBuilder();
        for (Measurement row : rows) {
            out.append(row.line()).append('\n');
        }
        return out.toString();
    }

    private static Measurement physicsSearch(FlatBlocks blocks, PlayerSimPhysics settled, Scenario scenario) {
        PlayerSimPhysics body = settled.copy();
        List<PhysicsPathfinder.Action> plan = new PhysicsPathfinder(blocks, scenario.nodeBudget())
                .plan(body.sim(), scenario.goalX(), scenario.goalY(), scenario.goalZ(), scenario.radius());
        if (plan == null) {
            Trajectory trajectory = new Trajectory(scenario.initialRequirements(), body.state(), new InputSequence(List.of()),
                    List.of(body.state()), "no plan within the node budget", 0, Double.NaN, null);
            return new Measurement("physics-search", Equivalence.EQUIVALENT, PlayerSimPhysics.SEMANTICS, true, false, 0,
                    Double.NaN, "no plan", trajectory);
        }
        List<ControlTick> controls = new ArrayList<>(plan.size());
        for (PhysicsPathfinder.Action action : plan) {
            controls.add(new ControlTick(action.yaw, action.forward, false, false, false, action.sprint, action.jump, false));
        }
        return replay("physics-search", Equivalence.EQUIVALENT, body, scenario, controls, plan.size(), false);
    }

    private static Measurement trivialSprint(PlayerSimPhysics settled, Scenario scenario) {
        PlayerSimPhysics body = settled.copy();
        List<ControlTick> controls = new ArrayList<>();
        for (int i = 0; i < scenario.tickBudget(); i++) {
            controls.add(ControlTick.sprintForward(0));
        }
        return replay("trivial-sprint", Equivalence.EQUIVALENT, body, scenario, controls, 0, true);
    }

    /**
     * @param stopAtGoal trivial sprint stops on the goal; the physics plan is replayed in full, as the pathfinder test flies it
     */
    private static Measurement replay(String name, Equivalence equivalence, PlayerSimPhysics body, Scenario scenario,
                                      List<ControlTick> offered, double plannedCost, boolean stopAtGoal) {
        List<PlayerState> predicted = new ArrayList<>();
        predicted.add(body.state());
        List<ControlTick> used = new ArrayList<>();
        boolean reached = false;
        for (ControlTick tick : offered) {
            if (used.size() >= scenario.tickBudget()) {
                break;
            }
            used.add(tick);
            predicted.add(body.step(tick));
            if (stopAtGoal && inside(body.state(), scenario)) {
                reached = true;
                break;
            }
        }
        if (!stopAtGoal) {
            reached = inside(body.state(), scenario);
        }
        int ticks = used.size();
        double cost = stopAtGoal ? ticks : plannedCost;
        String validity = reached ? "onGround and inside radius" : "budget exhausted";
        Trajectory trajectory = new Trajectory(scenario.initialRequirements(), predicted.get(0), new InputSequence(used),
                predicted, validity, ticks, cost, null);
        PlayerState end = body.state();
        String note = String.format(Locale.ROOT, "end %.4f %.4f %.4f", end.x(), end.y(), end.z());
        return new Measurement(name, equivalence, PlayerSimPhysics.SEMANTICS, true, reached, ticks, cost, note, trajectory);
    }

    private static boolean inside(PlayerState state, Scenario scenario) {
        return state.onGround()
                && Math.abs(state.y() - scenario.goalY()) <= 1e-6
                && Math.hypot(state.x() - scenario.goalX(), state.z() - scenario.goalZ()) <= scenario.radius() + 1e-6;
    }

    /** Same floor shape at PPE's y=64. Held sprint, not a per-tick sequence, and not PlayerSim. */
    private static Measurement ppeHeldSprint() {
        BlockInfo stone = BlockInfo.solid("stone", 0, 0, 0, 1, 1, 1);
        Terrain.Builder terrain = Terrain.builder(-64, 320, 0, 32);
        for (int sx = -1; sx <= 1; sx++) {
            for (int sz = -1; sz <= 1; sz++) {
                // Missing sections read as air and mark the prediction outside the snapshot, so the air the body
                // occupies is copied too. Floor y=63 is section 3; feet y=64 are section 4.
                terrain.put(sx, 3, sz, Terrain.Section.uniform(stone, 0, 0));
                terrain.put(sx, 4, sz, Terrain.Section.uniform(BlockInfo.AIR_INFO, 0, 0));
            }
        }
        BodyState start = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).rot(0, 0).build();
        PredictionRequest request = PredictionRequest.trajectory(start, PPE_HORIZON)
                .fidelity(PredictionRequest.Fidelity.HIGH)
                .input(new PredictionRequest.Input(1, 0, false, true, false, 0, 0))
                .consumer("contract")
                .build();
        PredictionResult result = new Predictor().run(request, terrain.build(), PredictionRequest.Fidelity.HIGH, () -> false);
        boolean executed = result.status == PredictionResult.Status.OK;
        int passed = firstPass(result);
        boolean reached = passed >= 0;
        List<PlayerState> predicted = new ArrayList<>();
        PlayerState initial = new PlayerState(start.x, start.y, start.z, start.vx, start.vy, start.vz, start.onGround, start.yaw);
        predicted.add(initial);
        if (result.path != null) {
            for (int i = 1; i < result.path.length / 3; i++) {
                boolean last = i == result.path.length / 3 - 1;
                predicted.add(new PlayerState(result.path[i * 3], result.path[i * 3 + 1], result.path[i * 3 + 2],
                        last ? result.vx : Double.NaN, last ? result.vy : Double.NaN, last ? result.vz : Double.NaN,
                        last && result.onGround, 0));
            }
        }
        ControlTick held = ControlTick.sprintForward(0);
        List<ControlTick> ticks = new ArrayList<>();
        for (int i = 0; i < result.horizonTicks; i++) {
            ticks.add(held);
        }
        String validity = result.status + (reached ? " passed radius at tick " + passed : " did not pass radius");
        Trajectory trajectory = new Trajectory(
                "PPE player onGround feet y=64 yaw 0; not the PlayerSim settle. Input is held, not a sequence.",
                initial, new InputSequence(ticks), predicted, validity, result.horizonTicks,
                reached ? passed : result.horizonTicks, null);
        String note = String.format(Locale.ROOT, "status %s end %.4f %.4f %.4f held-input different world y",
                result.status, result.x, result.y, result.z);
        return new Measurement("ppe-held-sprint", Equivalence.NOT_EQUIVALENT, PPE_SEMANTICS, executed, reached,
                result.horizonTicks, trajectory.cost(), note, trajectory);
    }

    /** First recorded sample whose horizontal position is inside the flat goal column, or -1. */
    private static int firstPass(PredictionResult result) {
        if (result.path == null) {
            return horizontal(result.x, result.z) ? result.horizonTicks : -1;
        }
        int stride = Math.max(1, result.pathStride);
        for (int i = 0; i < result.path.length / 3; i++) {
            if (horizontal(result.path[i * 3], result.path[i * 3 + 2]) && Math.abs(result.path[i * 3 + 1] - 64f) < 1.5f) {
                return i * stride;
            }
        }
        return -1;
    }

    private static boolean horizontal(double x, double z) {
        return Math.hypot(x - GOAL_X, z - GOAL_Z) <= RADIUS + 1e-4;
    }

    private static Measurement stockAStar() {
        return new Measurement("stock-astar", Equivalence.NOT_EQUIVALENT, PLANNER_SEMANTICS, false, false, -1,
                Double.NaN, "not executed; PathExecutor is not headless and stock A* cost is not a PlayerSim tick", null);
    }

    /** Solid cells, slip 0.6. Same collector as {@code PhysicsPathfinderTest}. */
    private static final class FlatBlocks implements PlayerSim.World {
        final Set<Long> solid = new HashSet<>();

        void add(int x, int y, int z) {
            solid.add(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512));
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                        if (solid.contains(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512))) {
                            out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                        }
                    }
                }
            }
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    }
}
