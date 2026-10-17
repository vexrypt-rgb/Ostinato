/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.SeedPlan;
import baritone.pathing.calc.hierarchy.SectionGraph;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Ostinato: far-terrain planning. With the goal past everything the client knows, the hierarchy still routes (through
 * uniformly priced unknown sections) and returns the corridor path up to the known edge instead of falling back to
 * plain A*; turning the setting off restores the fallback; and with a seed prediction the far sections are priced from
 * predicted terrain and the path reaches the goal.
 */
public class FarTerrainTest {

    private SyntheticWorld world;
    private PathBench.Query query;
    private int edge;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void setUp() {
        Settings s = Baritone.settings();
        s.pathingHierarchyLevels.value = 1;
        s.pathingHierarchyCandidates.value = 1;
        s.pathingHierarchyBidirectional.value = false;
        s.pathingHierarchyHeuristicFactor.value = 0.0;
        s.pathingHierarchyCorridorRadius.value = 1;
        world = PathBench.world(Terrain.FLAT);
        query = PathBench.queries(Terrain.FLAT).stream().filter(q -> q.length() == PathBench.Length.LONG).findFirst().orElseThrow();
        int gx = ((IGoalRenderPos) query.goal()).getGoalPos().getX();
        edge = ((query.start().x + gx) / 2) & ~15; // a chunk border halfway to the goal
        world.knownMaxX = edge;
    }

    @After
    public void tearDown() {
        world.knownMaxX = Integer.MAX_VALUE; // worlds are shared with the other bench tests
        SeedPlan.clear();
        Settings s = Baritone.settings();
        s.pathingHierarchyFarTerrain.value = s.pathingHierarchyFarTerrain.defaultValue;
        s.pathingHierarchyLevels.value = s.pathingHierarchyLevels.defaultValue;
        s.pathingHierarchyCandidates.value = s.pathingHierarchyCandidates.defaultValue;
        s.pathingHierarchyBidirectional.value = s.pathingHierarchyBidirectional.defaultValue;
        s.pathingHierarchyHeuristicFactor.value = s.pathingHierarchyHeuristicFactor.defaultValue;
        s.pathingHierarchyCorridorRadius.value = s.pathingHierarchyCorridorRadius.defaultValue;
    }

    private Optional<IPath> plan(HierarchicalPathFinder f) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream())); // A* prints progress lines
        try {
            return f.calculate0(PathBench.TIMEOUT_MS, PathBench.TIMEOUT_MS);
        } finally {
            System.setOut(out);
        }
    }

    private HierarchicalPathFinder finder(SectionGraph g) {
        CalculationContext ctx = PathBench.context(world);
        BetterBlockPos s = query.start();
        return new HierarchicalPathFinder(s, s.x, s.y, s.z, query.goal(), new Favoring(null, ctx), ctx, g, 1);
    }

    @Test
    public void unknownGoalGivesPartialCorridorPath() {
        assertTrue(Baritone.settings().pathingHierarchyFarTerrain.defaultValue);
        SectionGraph g = new SectionGraph();
        HierarchicalPathFinder f = finder(g);
        Optional<IPath> p = plan(f);
        assertEquals("corridor-partial", f.outcome);
        assertTrue(p.isPresent());
        BetterBlockPos d = p.get().getDest();
        assertTrue("stops before the known edge, at " + d.x + " vs " + edge, d.x < edge);
        assertTrue("gets most of the way to the known edge, at " + d.x + " vs " + edge, d.x >= edge - 48);
        assertEquals("unknown sections are not priced from blocks", 0, g.farSections);
    }

    @Test
    public void farTerrainOffFallsBack() {
        Baritone.settings().pathingHierarchyFarTerrain.value = false;
        HierarchicalPathFinder f = finder(new SectionGraph());
        plan(f);
        assertTrue(f.outcome, f.outcome.startsWith("fallback"));
    }

    @Test
    public void seedPredictionPricesFarSectionsAndReachesTheGoal() {
        // a perfect predictor: the world's own blocks past the known edge
        SeedPlan.use(1, new SeedPlan.Predictor() {
            @Override
            public boolean covers(int x, int z) {
                return true;
            }

            @Override
            public BlockState block(int x, int y, int z) {
                return world.real(x, y, z);
            }
        });
        SectionGraph g = new SectionGraph();
        HierarchicalPathFinder f = finder(g);
        Optional<IPath> p = plan(f);
        assertEquals("corridor", f.outcome);
        assertTrue(p.isPresent() && query.goal().isInGoal(p.get().getDest()));
        assertTrue("far sections priced from the prediction: " + g.farSections, g.farSections > 0);
    }
}
