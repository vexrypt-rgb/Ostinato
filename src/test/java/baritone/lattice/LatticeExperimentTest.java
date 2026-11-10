/*
 * Ostinato: Phase 03 lattice gate. Not part of upstream Baritone.
 */
package baritone.lattice;

import baritone.contract.PlayerSimPhysics;
import baritone.contract.PlayerState;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class LatticeExperimentTest {

    @Test
    public void eachKindIsFeasibleSomewhere() {
        LatticeBlocks flat = new LatticeBlocks().fill(-1, 1, -1, -1, 8);
        PlayerSimPhysics body = PlayerSimPhysics.settled(flat, 0.5, 0.5);
        assertTrue(body.state().onGround());
        Set<String> kinds = new HashSet<>();
        for (PrimitiveCatalog.Template template : PrimitiveCatalog.templates()) {
            PrimitiveCatalog.Sim sim = PrimitiveCatalog.simulate(body, template, 0, 0, 0, -1);
            if (sim != null) {
                kinds.add(sim.primitive.kind());
                assertFalse(sim.primitive.controls().isEmpty());
                assertEquals(sim.primitive.ticks(), sim.primitive.controls().size());
                assertTrue(sim.primitive.robustnessMargin() >= 0);
                assertFalse(sim.primitive.preconditions().isEmpty());
                assertFalse(sim.primitive.robustnessRule().isEmpty());
            }
        }
        assertTrue(kinds.toString(), kinds.contains("WALK"));
        assertTrue(kinds.toString(), kinds.contains("TURN"));
        assertTrue(kinds.toString(), kinds.contains("SPRINT"));
        assertTrue(kinds.toString(), kinds.contains("SPRINT_JUMP"));

        LatticeBlocks step = new LatticeBlocks();
        step.fill(-1, 1, -1, -1, 1);
        step.fill(-1, 1, -1, 2, 5);
        step.fill(-1, 1, 0, 2, 5);
        PlayerSimPhysics climber = PlayerSimPhysics.settled(step, 0.5, 0.5);
        boolean ascended = false;
        for (PrimitiveCatalog.Template template : PrimitiveCatalog.templates()) {
            if (!template.kind.equals("ASCEND")) {
                continue;
            }
            PrimitiveCatalog.Sim sim = PrimitiveCatalog.simulate(climber, template, 0, 0, 0, -1);
            if (sim != null) {
                ascended = true;
                PlayerState end = sim.primitive.predicted().get(sim.primitive.ticks() - 1);
                assertTrue(end.y() > 0.6 && end.y() < 1.4);
            }
        }
        assertTrue("no ascend primitive cleared the one-block step", ascended);
    }

    @Test
    public void sprintFourMovesAlongYawZero() {
        LatticeBlocks flat = new LatticeBlocks().fill(-1, 1, -1, -1, 8);
        PlayerSimPhysics body = PlayerSimPhysics.settled(flat, 0.5, 0.5);
        PrimitiveCatalog.Template sprint = template("sprint-4");
        PrimitiveCatalog.Sim sim = PrimitiveCatalog.simulate(body, sprint, 0.5, 0, 5.5, -1);
        assertNotNull(sim);
        PlayerState end = sim.primitive.predicted().get(sim.primitive.ticks() - 1);
        assertTrue("sprint-4 z=" + end.z(), end.z() > 1.0);
        assertEquals(0.5, end.x(), 0.05);
        assertEquals(0, end.y(), 0.05);
    }

    @Test
    public void flagDefaultsOffAndProductionDoesNotReferenceTheLattice() throws Exception {
        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"));
        assertTrue(settings.contains("public final Setting<Boolean> latticeExperiment = new Setting<>(false);"));
        String[] files = {
                "src/main/java/baritone/pathing/path/PathExecutor.java",
                "src/main/java/baritone/pathing/calc/AStarPathFinder.java",
                "src/main/java/baritone/behavior/PathingBehavior.java",
                "src/main/java/baritone/pathing/kinematic/KinematicController.java",
                "src/main/java/baritone/pathing/physics/PhysicsPathfinder.java",
                "src/main/java/baritone/pathing/kinematic/PlayerSim.java",
                "src/main/java/baritone/utils/InputOverrideHandler.java",
                "src/main/java/baritone/pathing/travel/TravelStack.java"
        };
        for (String file : files) {
            String text = Files.readString(project(file));
            assertFalse(file, text.contains("baritone.lattice"));
        }
        assertTrue(Files.readString(project("src/main/java/baritone/gui/model/SettingCategorizer.java"))
                .contains("latticeExperiment"));
    }

    @Test
    public void corporaWereFixedBeforeTheRun() {
        assertTrue(LatticeCase.development().size() >= 10);
        assertEquals(16, LatticeCase.heldOut().size());
        Set<String> dev = new HashSet<>();
        for (LatticeCase item : LatticeCase.development()) {
            assertTrue(dev.add(item.id()));
            assertEquals("dev", item.split());
        }
    }

    @Test
    public void decisionRulesFireOnSyntheticRows() {
        assertEquals("REJECT", LatticeBench.decide(List.of(row("beam", true, 10, 4, 0.3)))[0]);
        List<LatticeBench.Trial> prefer = new ArrayList<>();
        prefer.add(row("beam", false, -1, 20, Double.NaN));
        prefer.add(row("weighted", false, -1, 20, Double.NaN));
        prefer.add(row("physics", true, 12, -1, Double.NaN));
        assertEquals("PREFER SIMULATED A*", LatticeBench.decide(prefer)[0]);
        List<LatticeBench.Trial> revise = new ArrayList<>();
        revise.add(row("beam", true, 18, 6, 0));
        revise.add(row("weighted", true, 18, 8, 0));
        revise.add(row("physics", true, 18, -1, Double.NaN));
        String[] tied = LatticeBench.decide(revise);
        assertEquals("REVISE", tied[0]);
        assertTrue(tied[1], tied[1].contains("UNKNOWN"));

        // Review fix: with a baseline counter the 3x expansion clause is evaluated.
        List<LatticeBench.Trial> counted = new ArrayList<>();
        counted.add(row("beam", true, 18, 6, 0));
        counted.add(row("weighted", true, 18, 8, 0));
        counted.add(row("physics", true, 18, 2, Double.NaN));
        assertEquals("CONTINUE", LatticeBench.decide(counted)[0]);
        List<LatticeBench.Trial> costly = new ArrayList<>();
        costly.add(row("beam", true, 18, 7, 0));
        costly.add(row("weighted", true, 18, 9, 0));
        costly.add(row("physics", true, 18, 2, Double.NaN));
        String[] over = LatticeBench.decide(costly);
        assertEquals("REVISE", over[0]);
        assertTrue(over[1], over[1].contains("exceed 3x"));
    }

    @Test
    public void benchIsReproducibleAndWritesTheRawTable() throws Exception {
        LatticeBench.Report first = LatticeBench.run();
        LatticeBench.Report second = LatticeBench.run();
        assertEquals(first.decision(), second.decision());
        assertEquals(first.trials().size(), second.trials().size());
        for (int i = 0; i < first.trials().size(); i++) {
            LatticeBench.Trial a = first.trials().get(i);
            LatticeBench.Trial b = second.trials().get(i);
            assertEquals(a.split(), b.split());
            assertEquals(a.id(), b.id());
            assertEquals(a.trial(), b.trial());
            assertEquals(a.method(), b.method());
            assertEquals(a.success(), b.success());
            assertEquals(a.travelTicks(), b.travelTicks());
            assertEquals(a.expansions(), b.expansions());
            assertEquals(Double.doubleToLongBits(a.divergence()), Double.doubleToLongBits(b.divergence()));
        }
        assertTrue(LatticeBench.reproducible(first.trials()));
        double max = LatticeBench.maxDivergence(first.trials());
        if (max > 0.25) {
            assertEquals("REJECT", first.decision());
        }
        assertTrue(List.of("CONTINUE", "REVISE", "PREFER SIMULATED A*", "REJECT").contains(first.decision()));
        // Review fix: committed evidence under docs/ is written only on request (OSTINATO_WRITE_DOCS=1).
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS"))
                ? project("docs/ostinato2/raw")
                : Paths.get("build", "ostinato2", "raw");
        LatticeBench.write(first, dir);
        String csv = Files.readString(dir.resolve("phase03-trials.csv")).replace("\r", "");
        assertTrue(csv.startsWith("split,id,trial,method,success,travelTicks,searchTicks,expansions,latencyMs,allocBytes,divergence\n"));
        assertTrue(csv.contains("flat-5"));
        String summary = Files.readString(dir.resolve("phase03-summary.txt"));
        assertTrue(summary.startsWith("decision: " + first.decision()));
        Files.writeString(dir.resolve("phase03-summary.txt"), first.summary(), StandardCharsets.US_ASCII);
    }

    private static LatticeBench.Trial row(String method, boolean success, int ticks, int expansions, double divergence) {
        return new LatticeBench.Trial("dev", "only", 0, method, success, ticks, ticks, expansions, 1, -1, divergence);
    }

    private static PrimitiveCatalog.Template template(String name) {
        for (PrimitiveCatalog.Template template : PrimitiveCatalog.templates()) {
            if (template.name.equals(name)) {
                return template;
            }
        }
        throw new AssertionError(name);
    }

    private static Path project(String rel) {
        Path path = Paths.get(rel);
        return Files.exists(path) ? path : Paths.get("..").resolve(rel);
    }
}
