/*
 * Ostinato 2.0 Phase 04: PlayerSim against PPE, both 26.3 living models, per movement mode. Not upstream Baritone.
 * Tier-S cross-model disagreement on identical hand-built terrain and held input. Neither side is the game, so this is
 * a bound on model disagreement, not on prediction error. Live ground truth comes from SIMTRACE runs (Tier-L).
 * PPE reports positions as floats, so agreement is checked to half a float ulp of each PPE coordinate.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CrossModelModesTest {
    static final BlockInfo LADDER = new BlockInfo.Builder("ladder").flags(BlockInfo.CLIMBABLE).build();

    /** One cell kind per position, shared by both models. */
    enum Cell { AIR, STONE, WATER, CLIMB }

    interface Layout {
        Cell at(int x, int y, int z);
    }

    record Scenario(String mode, Layout layout, double y0, boolean onGround, float forward, boolean sprint, boolean jump, int horizon) {
    }

    record Outcome(String mode, int horizon, double maxOpen, double endOpen, double maxStepDelta, double maxUlps, List<double[]> rows) {
    }

    static final Layout FLOOR = (x, y, z) -> y < 64 ? Cell.STONE : Cell.AIR;
    static final Layout POOL = (x, y, z) -> y < 64 ? Cell.STONE : y < 67 ? Cell.WATER : Cell.AIR;
    static final Layout SHAFT = (x, y, z) -> y < 64 ? Cell.STONE : x == 0 && z == 0 && y < 90 ? Cell.CLIMB : Cell.AIR;

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("walk", FLOOR, 64, true, 1, false, false, 20),
                new Scenario("sprint", FLOOR, 64, true, 1, true, false, 20),
                new Scenario("sprint-jump", FLOOR, 64, true, 1, true, true, 20),
                new Scenario("fall", FLOOR, 84, false, 0, false, false, 30),
                new Scenario("water-walk", POOL, 64, true, 1, false, false, 20),
                new Scenario("ladder-jump", SHAFT, 64, true, 0, false, true, 20));
    }

    @Test
    public void everyModeRunsInBothModelsAndWritesTheTable() throws Exception {
        StringBuilder csv = new StringBuilder("mode,tick,simX,simY,simZ,ppeX,ppeY,ppeZ,openError,stepDelta\n");
        StringBuilder summary = new StringBuilder("Tier-S cross-model: PlayerSim (26.3 model) vs PPE (26.3 living model), held input, identical terrain\n");
        summary.append("mode,horizon,maxOpenError,endOpenError,maxStepDelta,maxFloatUlps\n");
        for (Scenario s : scenarios()) {
            Outcome o = run(s);
            assertEquals(s.mode(), s.horizon(), o.rows().size() - 1);
            assertTrue(s.mode() + " " + o.maxOpen(), Double.isFinite(o.maxOpen()));
            assertTrue(s.mode() + " differs from PPE by " + o.maxUlps() + " float ulps", o.maxUlps() <= 0.5 + 1e-3);
            for (int t = 0; t < o.rows().size(); t++) {
                double[] r = o.rows().get(t);
                csv.append(String.format(Locale.ROOT, "%s,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.3e,%.3e%n", s.mode(), t,
                        r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]));
            }
            summary.append(String.format(Locale.ROOT, "%s,%d,%.3e,%.3e,%.3e,%.3f%n", s.mode(), s.horizon(), o.maxOpen(), o.endOpen(), o.maxStepDelta(), o.maxUlps()));
        }
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS"))
                ? project("docs/ostinato2/raw/review")
                : Paths.get("build", "ostinato2", "raw", "review");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("phase04-crossmodel.csv"), csv.toString(), StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve("phase04-crossmodel-summary.txt"), summary.toString(), StandardCharsets.US_ASCII);
        System.out.print(summary);
    }

    /** Row per tick: sim xyz, ppe xyz, open-loop distance, |per-tick displacement difference|. */
    static Outcome run(Scenario s) {
        PlayerSim sim = new PlayerSim(world(s.layout()));
        sim.x = 0.5;
        sim.y = s.y0();
        sim.z = 0.5;
        sim.onGround = s.onGround();
        List<double[]> simPath = new ArrayList<>();
        simPath.add(new double[]{sim.x, sim.y, sim.z});
        for (int t = 0; t < s.horizon(); t++) {
            if (sim.inWater()) {
                sim.tickWater(0, 0, s.forward() > 0, s.sprint(), s.jump(), false);
            } else {
                sim.tick(0, s.forward() > 0 ? 1 : 0, s.sprint(), s.jump());
            }
            simPath.add(new double[]{sim.x, sim.y, sim.z});
        }

        BodyState start = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, s.y0(), 0.5).onGround(s.onGround()).rot(0, 0).build();
        PredictionRequest request = PredictionRequest.trajectory(start, s.horizon())
                .fidelity(PredictionRequest.Fidelity.HIGH)
                .input(new PredictionRequest.Input(s.forward(), 0, s.jump(), s.sprint(), false, 0, 0))
                .consumer("phase04-crossmodel")
                .build();
        PredictionResult result = new Predictor().run(request, terrain(s.layout()), PredictionRequest.Fidelity.HIGH, () -> false);
        assertEquals(s.mode() + " " + result.status, PredictionResult.Status.OK, result.status);
        int n = result.path.length / 3;
        assertEquals(s.mode(), s.horizon() + 1, n);

        List<double[]> rows = new ArrayList<>();
        double maxOpen = 0;
        double maxStep = 0;
        double maxUlps = 0;
        for (int t = 0; t < n; t++) {
            double[] a = simPath.get(t);
            double px = result.path[t * 3], py = result.path[t * 3 + 1], pz = result.path[t * 3 + 2];
            double open = Math.sqrt(sq(a[0] - px) + sq(a[1] - py) + sq(a[2] - pz));
            double step = 0;
            if (t > 0) {
                double[] b = simPath.get(t - 1);
                double qx = result.path[(t - 1) * 3], qy = result.path[(t - 1) * 3 + 1], qz = result.path[(t - 1) * 3 + 2];
                step = Math.sqrt(sq((a[0] - b[0]) - (px - qx)) + sq((a[1] - b[1]) - (py - qy)) + sq((a[2] - b[2]) - (pz - qz)));
            }
            for (int i = 0; i < 3; i++) {
                float p = result.path[t * 3 + i];
                maxUlps = Math.max(maxUlps, Math.abs(a[i] - p) / Math.ulp(p));
            }
            maxOpen = Math.max(maxOpen, open);
            maxStep = Math.max(maxStep, step);
            rows.add(new double[]{a[0], a[1], a[2], px, py, pz, open, step});
        }
        return new Outcome(s.mode(), s.horizon(), maxOpen, rows.get(rows.size() - 1)[6], maxStep, maxUlps, rows);
    }

    static PlayerSim.World world(Layout layout) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
                    for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                        for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                            if (layout.at(x, y, z) == Cell.STONE) {
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

            @Override
            public boolean water(int x, int y, int z) {
                return layout.at(x, y, z) == Cell.WATER;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return layout.at(x, y, z) == Cell.CLIMB;
            }
        };
    }

    static Terrain terrain(Layout layout) {
        return PpeWorlds.build(-2, 1, 2, 6, -2, 1, (x, y, z) -> switch (layout.at(x, y, z)) {
            case STONE -> PpeWorlds.STONE;
            case WATER -> PpeWorlds.WATER;
            case CLIMB -> LADDER;
            default -> BlockInfo.AIR_INFO;
        });
    }

    private static double sq(double v) {
        return v * v;
    }

    private static Path project(String rel) {
        Path path = Paths.get(rel);
        return Files.exists(path.getParent()) ? path : Paths.get("..").resolve(rel);
    }
}
