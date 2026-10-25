/*
 * Ostinato: piCheapPathing stays off by default and, when on, finds the same routes.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PiCheapPathTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void offAgain() {
        Baritone.settings().piCheapPathing.value = false;
    }

    @Test
    public void settingDefaultsOff() {
        assertFalse(Baritone.settings().piCheapPathing.value);
    }

    @Test
    public void slicesDoNotAliasAndPeekDoesNotAllocate() {
        Baritone.settings().piCheapPathing.value = true;
        CalculationContext ctx = PathBench.context(PathBench.world(Terrain.FLAT));
        AStarPathFinder probe = finder(ctx);
        assertNull(probe.peekNodeAtPosition(0, 64, 0));
        PathNode a = probe.getNodeAtPosition(0, 64, 0, 0);
        assertSame(a, probe.getNodeAtPosition(0, 64, 0, 0));
        assertSame(a, probe.peekNodeAtPosition(0, 64, 0));
        assertNull(probe.peekNodeAtPosition(4, 64, 4));
        PathNode b = probe.getNodeAtPosition(1, 64, 0, 0);
        PathNode up = probe.getNodeAtPosition(0, 65, 0, 0);
        PathNode band = probe.getNodeAtPosition(0, 72, 0, 0);
        PathNode neg = probe.getNodeAtPosition(-1, 63, 2, 0);
        assertNotSame(a, b);
        assertNotSame(a, up);
        assertNotSame(a, band);
        assertNotSame(a, neg);
        Set<PathNode> seen = new HashSet<>();
        for (PathNode n : probe.nodes()) {
            assertTrue(seen.add(n));
        }
        assertEquals(5, seen.size());
        assertTrue(seen.contains(a));
        assertTrue(seen.contains(neg));
    }

    @Test
    public void blockCacheAgreesWithTheWorld() {
        Baritone.settings().piCheapPathing.value = true;
        var world = PathBench.world(Terrain.HILLS);
        CalculationContext ctx = PathBench.context(world);
        long before = PiCheap.blockHits();
        ctx.beginPiCheap(true);
        try {
            var first = ctx.get(12, 70, 20);
            var again = ctx.get(12, 70, 20);
            assertEquals(world.real(12, 70, 20), first);
            assertSame(first, again);
            assertTrue(PiCheap.blockHits() > before);
            assertEquals(world.real(13, 40, 21), ctx.get(13, 40, 21));
        } finally {
            ctx.endPiCheap(true);
        }
        assertEquals(world.real(12, 70, 20), ctx.get(12, 70, 20));
    }

    @Test
    public void pooledOpenSetReusesItsArrays() {
        GoalBlock goal = new GoalBlock(0, 0, 0);
        PathNode a = node(0, 3, goal);
        PathNode b = node(1, 1, goal);
        PathNode c = node(2, 2, goal);
        BinaryHeapOpenSet heap = BinaryHeapOpenSet.pooled();
        heap.insert(a);
        heap.insert(b);
        heap.insert(c);
        assertSame(b, heap.removeLowest());
        assertSame(c, heap.removeLowest());
        assertSame(a, heap.removeLowest());
        assertTrue(heap.isEmpty());
        PathNode[] buf = heap.arrayForTest();
        heap.release();
        BinaryHeapOpenSet again = BinaryHeapOpenSet.pooled();
        try {
            assertSame(buf, again.arrayForTest());
            assertTrue(again.isEmpty());
            assertNull(again.arrayForTest()[1]);
            again.insert(c);
            assertSame(c, again.removeLowest());
        } finally {
            again.release();
        }
        BinaryHeapOpenSet stock = new BinaryHeapOpenSet();
        stock.release();
        stock.insert(b);
        assertSame(b, stock.removeLowest());
    }

    @Test
    public void onMatchesOffOnShortAndOneMedium() {
        Baritone.settings().piCheapPathing.value = false;
        List<PathBench.Query> qs = PathBench.queries(Terrain.values()).stream()
                .filter(q -> q.length() == PathBench.Length.SHORT
                        || (q.length() == PathBench.Length.MEDIUM && q.n() == 0))
                .toList();
        List<PathBench.Result> off = qs.stream().map(q -> PathBench.run(q, PathBench.ASTAR)).toList();
        Baritone.settings().piCheapPathing.value = true;
        List<PathBench.Result> on = qs.stream().map(q -> PathBench.run(q, PathBench.ASTAR)).toList();
        assertEquals(off.size(), on.size());
        for (int i = 0; i < off.size(); i++) {
            PathBench.Result a = off.get(i);
            PathBench.Result b = on.get(i);
            assertEquals(a.q().id(), a.ok(), b.ok());
            assertEquals(a.q().id() + " nodes", a.nodes(), b.nodes());
            if (Double.isNaN(a.cost())) {
                assertTrue(a.q().id(), Double.isNaN(b.cost()));
            } else {
                assertEquals(a.q().id() + " cost", a.cost(), b.cost(), 0);
            }
        }
    }

    private static PathNode node(int x, double key, GoalBlock goal) {
        PathNode n = new PathNode(x, 0, 0, goal);
        n.combinedCost = key;
        return n;
    }

    /** Same package as the finder, so the protected node map is visible without a subclass. */
    private static AStarPathFinder finder(CalculationContext ctx) {
        return new AStarPathFinder(new BetterBlockPos(0, 64, 0), 0, 64, 0, new GoalBlock(3, 64, 0), new Favoring(null, ctx), ctx);
    }
}
