/*
 * Ostinato: new file (Cadenza v2.1). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static baritone.pathing.cadenza.CadenzaTest.REACH;
import static baritone.pathing.cadenza.CadenzaTest.Y;
import static org.junit.Assert.*;

/**
 * v2.1: the candidate memo and the process's own world changes. Live (PLACE_ROW), Builder placed the first block of a
 * row and then walked to, and stood idle in, cells the memo still held for the next two targets, although the block it
 * had just placed covered the faces those cells were to place against; the sets were refreshed only by the 1 s TTL.
 */
public class CadenzaV21Test {

    private static Set<String> cells(Cadenza.Result r) {
        Set<String> s = new HashSet<>();
        for (Cadenza.Candidate c : r.candidates) s.add(c.x + "," + c.y + "," + c.z + "," + c.face);
        return s;
    }

    private static List<Cadenza.Target> row(int... xs) {
        List<Cadenza.Target> ts = new ArrayList<>();
        for (int x : xs) ts.add(Cadenza.Target.place(new BlockPos(x, Y, 10), REACH));
        return ts;
    }

    private static CadenzaSession session(AtomicLong now) {
        CadenzaSession s = new CadenzaSession();
        s.clock = now::get;
        s.cache.clock = now::get;
        return s;
    }

    private static CadenzaGoal goal(CadenzaSession s, SyntheticWorld w, List<Cadenza.Target> ts) {
        return s.goal(PathBench.context(w), ts, Cadenza.Forbidden.NONE, false, r -> {}, new ArrayList<>());
    }

    /** The PLACE_ROW geometry: placing 12 changes what the cells for 13 and 14 can do. */
    @Test
    public void placingABlockChangesItsNeighboursCandidates() {
        SyntheticWorld w = CadenzaTest.floor();
        Cadenza.Result before = Cadenza.candidates(PathBench.context(w), row(13).get(0), Cadenza.Forbidden.NONE);
        w.set(12, Y, 10, CadenzaTest.stone());
        Cadenza.Result after = Cadenza.candidates(PathBench.context(w), row(13).get(0), Cadenza.Forbidden.NONE);
        assertTrue(before.ok() && after.ok());
        assertNotEquals("the placed block must matter to the next target", cells(before), cells(after));
    }

    /** The defect, pinned: without a confirm the memo keeps the stale set for the TTL (target block unchanged). */
    @Test
    public void withoutAConfirmTheMemoKeepsTheStaleSet() {
        SyntheticWorld w = CadenzaTest.floor();
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        CadenzaGoal g0 = goal(s, w, row(13, 14));
        w.set(12, Y, 10, CadenzaTest.stone());
        now.addAndGet(500);
        CadenzaGoal g1 = goal(s, w, row(13, 14));
        assertSame(g0.results().get(0).candidates, g1.results().get(0).candidates);
    }

    @Test
    public void confirmingAPlacementRefreshesTheTargetsNearIt() {
        SyntheticWorld w = CadenzaTest.floor();
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        goal(s, w, row(12, 13, 14));
        s.attempt(new BlockPos(12, Y, 10), 40);
        w.set(12, Y, 10, CadenzaTest.stone()); // placed
        now.addAndGet(50);
        s.confirm();
        CadenzaGoal g = goal(s, w, row(13, 14));
        for (int i = 0; i < 2; i++) {
            Cadenza.Result fresh = Cadenza.candidates(PathBench.context(w), row(13, 14).get(i), Cadenza.Forbidden.NONE);
            assertEquals(cells(fresh), cells(g.results().get(i)));
        }
        // and the goal equals one built from scratch on the same world (no history in it)
        CadenzaGoal scratch = goal(session(new AtomicLong(50)), w, row(13, 14));
        assertEquals(scratch, g);
        assertEquals(scratch.hashCode(), g.hashCode());
    }

    @Test
    public void confirmingABreakRefreshesTheTargetsNearIt() {
        SyntheticWorld w = CadenzaTest.floor();
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 4; y++) w.set(14, y, z, CadenzaTest.stone());
        w.set(14, Y + 1, 10, CadenzaTest.ore());
        w.set(14, Y + 1, 12, CadenzaTest.ore());
        w.set(13, Y + 1, 11, CadenzaTest.stone()); // a lump between the bot's side and the second ore
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        List<Cadenza.Target> two = List.of(Cadenza.Target.mine(new BlockPos(14, Y + 1, 12), REACH, false));
        Cadenza.Result cached = s.goal(PathBench.context(w), two, Cadenza.Forbidden.NONE, false, r -> {}, new ArrayList<>()).results().get(0);
        s.attempt(new BlockPos(13, Y + 1, 11), 60);
        w.set(13, Y + 1, 11, Blocks.AIR.defaultBlockState()); // broken
        s.confirm();
        Cadenza.Result now1 = s.goal(PathBench.context(w), two, Cadenza.Forbidden.NONE, false, r -> {}, new ArrayList<>()).results().get(0);
        assertNotSame(cached, now1);
        assertEquals(cells(Cadenza.candidates(PathBench.context(w), two.get(0), Cadenza.Forbidden.NONE)), cells(now1));
    }

    @Test
    public void farTargetsKeepTheirSets() {
        SyntheticWorld w = CadenzaTest.floor();
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        List<Cadenza.Target> ts = List.of(Cadenza.Target.place(new BlockPos(13, Y, 10), REACH), Cadenza.Target.place(new BlockPos(13, Y, 25), REACH));
        CadenzaGoal g0 = goal(s, w, ts);
        s.attempt(new BlockPos(12, Y, 10), 40);
        w.set(12, Y, 10, CadenzaTest.stone());
        s.confirm();
        CadenzaGoal g1 = goal(s, w, ts);
        assertNotSame(g0.results().get(0).candidates, g1.results().get(0).candidates);
        assertSame("15 blocks away: beyond reach + margin", g0.results().get(1).candidates, g1.results().get(1).candidates);
    }

    @Test
    public void invalidateNearIsBoundedByReachPlusMargin() {
        SyntheticWorld w = CadenzaTest.floor();
        CadenzaCache c = new CadenzaCache(1000);
        c.clock = () -> 0;
        double edge = REACH + CadenzaCache.NEAR_MARGIN;
        int in = (int) Math.floor(edge), out = in + 1;
        c.resolve(PathBench.context(w), Cadenza.Target.place(new BlockPos(5 + in, Y, 10), REACH), Cadenza.Forbidden.NONE);
        c.resolve(PathBench.context(w), Cadenza.Target.place(new BlockPos(5 + out, Y, 10), REACH), Cadenza.Forbidden.NONE);
        assertEquals(2, c.size());
        assertEquals(1, c.invalidateNear(new BlockPos(5, Y, 10)));
        assertEquals(1, c.size());
    }

    /** Lifecycle: nothing to confirm changes nothing; fail, tick and reset keep their v2 meaning. */
    @Test
    public void lifecycleWithoutAnInteractionKeepsTheMemo() {
        SyntheticWorld w = CadenzaTest.floor();
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        CadenzaGoal g0 = goal(s, w, row(13));
        s.confirm(); // no current
        s.fail();
        assertNull(s.tick());
        assertSame(g0.results().get(0).candidates, goal(s, w, row(13)).results().get(0).candidates);
        s.attempt(new BlockPos(13, Y, 10), 2);
        assertNull(s.tick());
        assertNull(s.tick());
        assertEquals(new BlockPos(13, Y, 10), s.tick()); // past the budget: FAILED, handed back
        assertNull(s.current());
        assertSame("a failure changed nothing in the world", g0.results().get(0).candidates, goal(s, w, row(13)).results().get(0).candidates);
    }

    /** The same target clicked on consecutive ticks stays one interaction; a confirmed target is not re-attempted as current. */
    @Test
    public void repeatedTargetIsOneInteraction() {
        AtomicLong now = new AtomicLong(0);
        CadenzaSession s = session(now);
        BlockPos p = new BlockPos(13, Y, 10);
        s.attempt(p, 40);
        s.tick();
        s.attempt(p, 40);
        assertEquals(1, s.elapsed());
        s.confirm();
        assertNull(s.current());
        assertEquals(-1, s.elapsed());
    }

    /** Candidate sets are a pure function of the world: two sessions, same world, equal goals. */
    @Test
    public void candidatesAreStableAcrossSessions() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(12, Y, 10, CadenzaTest.stone());
        CadenzaGoal a = goal(session(new AtomicLong(0)), w, row(13, 14));
        CadenzaGoal b = goal(session(new AtomicLong(7)), w, row(13, 14));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ---- mining goals: the extras (farther ores, dropped items) are a union, compared in any order ----

    private static final Goal A = new GoalTwoBlocks(11, Y, 15), B = new GoalTwoBlocks(16, Y, 11), C = new GoalTwoBlocks(12, Y, 7);

    private static CadenzaGoal mineGoal(SyntheticWorld w, CadenzaGoal.Options o, Goal... extras) {
        return new CadenzaGoal(List.of(CadenzaTest.mine(w, 10, Y, 10)), 0, o, extras);
    }

    private static SyntheticWorld oreWorld() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y, 10, CadenzaTest.ore());
        return w;
    }

    @Test
    public void extrasInAnotherOrderAreTheSameGoal() {
        SyntheticWorld w = oreWorld();
        for (CadenzaGoal.Options o : List.of(CadenzaGoal.Options.OFF, CadenzaGoal.Options.turnAware(CadenzaV2Test.TURN))) {
            CadenzaGoal g1 = mineGoal(w, o, A, B, C), g2 = mineGoal(w, o, C, A, B);
            assertEquals(g1, g2);
            assertEquals(g1.hashCode(), g2.hashCode());
            assertEquals(1, new HashSet<>(List.of(g1, g2)).size());
        }
    }

    @Test
    public void rebuiltFromTheSameWorldIsTheSameGoal() {
        SyntheticWorld w = oreWorld();
        CadenzaGoal g1 = mineGoal(w, CadenzaGoal.Options.OFF, A, B), g2 = mineGoal(w, CadenzaGoal.Options.OFF, A, B);
        assertNotSame(g1, g2);
        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
    }

    @Test
    public void differentExtrasOfTheSameSizeAreDifferentGoals() {
        SyntheticWorld w = oreWorld();
        assertNotEquals(mineGoal(w, CadenzaGoal.Options.OFF, A, B), mineGoal(w, CadenzaGoal.Options.OFF, A, C));
    }

    @Test
    public void aMovedRemovedOrNewItemChangesTheGoal() {
        SyntheticWorld w = oreWorld();
        CadenzaGoal base = mineGoal(w, CadenzaGoal.Options.OFF, A, B);
        assertNotEquals("moved one block", base, mineGoal(w, CadenzaGoal.Options.OFF, new GoalTwoBlocks(11, Y, 16), B));
        assertNotEquals("picked up", base, mineGoal(w, CadenzaGoal.Options.OFF, B));
        assertNotEquals("a new drop", base, mineGoal(w, CadenzaGoal.Options.OFF, A, B, C));
        assertNotEquals("same cell, another goal kind", base, mineGoal(w, CadenzaGoal.Options.OFF, new GoalBlock(11, Y, 15), B));
    }

    @Test
    public void aRepeatedExtraIsTheSameUnion() {
        SyntheticWorld w = oreWorld();
        CadenzaGoal g1 = mineGoal(w, CadenzaGoal.Options.OFF, A, A, B), g2 = mineGoal(w, CadenzaGoal.Options.OFF, B, A);
        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
    }

    /** Order-insensitive equality is sound only if nothing the planner asks of the goal depends on the order. */
    @Test
    public void inGoalHeuristicAndTerminalDoNotDependOnOrder() {
        SyntheticWorld w = oreWorld();
        CadenzaGoal g1 = mineGoal(w, CadenzaGoal.Options.OFF, A, B, C), g2 = mineGoal(w, CadenzaGoal.Options.OFF, C, B, A);
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = Y - 1; y <= Y + 1; y++) {
            assertEquals(g1.isInGoal(x, y, z), g2.isInGoal(x, y, z));
            assertEquals(g1.heuristic(x, y, z), g2.heuristic(x, y, z), 0);
            assertEquals(g1.terminalCost(x, y, z), g2.terminalCost(x, y, z), 0);
        }
    }

    /** A STRICT batch's targets keep their order (it is part of the task); only the extras became a set. */
    @Test
    public void strictBatchTargetsStayOrdered() {
        SyntheticWorld w = CadenzaTest.floor();
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 5; y++) w.set(14, y, z, CadenzaTest.stone());
        w.set(14, Y + 1, 9, CadenzaTest.ore());
        w.set(14, Y + 1, 11, CadenzaTest.ore());
        CadenzaGoal.Options strict = CadenzaGoal.Options.batched(CadenzaV2Test.TURN, CadenzaGoal.Ordering.STRICT);
        Cadenza.Result r9 = CadenzaTest.mine(w, 14, Y + 1, 9), r11 = CadenzaTest.mine(w, 14, Y + 1, 11);
        assertNotEquals(new CadenzaGoal(List.of(r9, r11), 0, strict, A, B), new CadenzaGoal(List.of(r11, r9), 0, strict, B, A));
        CadenzaGoal.Options any = CadenzaGoal.Options.batched(CadenzaV2Test.TURN, CadenzaGoal.Ordering.ANY);
        assertEquals(new CadenzaGoal(List.of(r9, r11), 0, any, A, B), new CadenzaGoal(List.of(r11, r9), 0, any, B, A));
    }

    /**
     * PathingControlManager.forceRevalidate soft-cancels a path whose end is outside the new goal when the new goal is
     * not equal to the path's (Builder always; the miner under legitMine). A re-sort of the same extras must not count.
     */
    @Test
    public void aReorderAloneWouldNotCancelAPartialPath() {
        SyntheticWorld w = oreWorld();
        CadenzaGoal planned = mineGoal(w, CadenzaGoal.Options.OFF, A, B), now = mineGoal(w, CadenzaGoal.Options.OFF, B, A);
        int[] dest = {3, Y, 3}; // a partial path's end, in neither goal
        assertFalse(now.isInGoal(dest[0], dest[1], dest[2]));
        boolean cancels = !now.isInGoal(dest[0], dest[1], dest[2]) && !now.equals(planned);
        assertFalse(cancels);
        boolean realChange = !now.isInGoal(dest[0], dest[1], dest[2]) && !mineGoal(w, CadenzaGoal.Options.OFF, A, C).equals(planned);
        assertTrue("a different set still re-plans", realChange);
    }
}
