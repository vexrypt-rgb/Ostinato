package baritone.bastion;

import baritone.bastion.BastionRoutes.Step;
import baritone.bastion.BastionScript.*;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BastionScriptTest {
    @Test
    public void everyTacticStepHasAScript() {
        Step[] tactic = {Step.TAKE_THIRD_BASALT_BRIDGE, Step.PILLAR_3_HUG, Step.COVER_2_BLOCKS, Step.BRIDGE_GAP, Step.BUILD_POCKET,
                Step.CROSS_BRICK_BRIDGE, Step.PIGLIN_PIT, Step.DIG_DOWN_3_STRIP_4, Step.T_TUNNEL_GOLD, Step.MINE_WALL_GOLD,
                Step.GOLD_ELEVATOR, Step.LAVA_BLOCK_GAP, Step.BLOCK_LOWER_LAVA, Step.LAVA_STRAGGLERS, Step.UNSTICK_BY_MINING,
                Step.WEAR_GOLD_HELMET, Step.ONE_BY_ONE, Step.RIGHT_FORK_BLOCK_IN, Step.DROP_PIGLINS, Step.STAIR_2};
        for (Step s : tactic) assertFalse(s.toString(), BastionScript.ops(s).isEmpty());
        assertTrue(BastionScript.ops(Step.LOOT_DOUBLE).isEmpty());
        assertTrue(BastionScript.ops(Step.TRADE).isEmpty());
    }

    @Test
    public void pillarPocketAndGap() {
        assertEquals(new Op(Kind.PILLAR, 0, 0, 0, 3), BastionScript.ops(Step.PILLAR_3_HUG).get(0));
        List<Op> cv = BastionScript.ops(Step.COVER_2_BLOCKS);
        assertEquals(new Op(Kind.PLACE, 1, 0, -1, 0), cv.get(0)); // footing on the pillar side first
        assertEquals(new Op(Kind.PLACE, 1, 0, 1, 0), cv.get(2));
        List<Op> p = BastionScript.ops(Step.BUILD_POCKET);
        assertEquals(5, p.size());
        assertTrue(p.stream().allMatch(o -> o.kind() == Kind.PLACE));
        List<Op> g = BastionScript.ops(Step.BRIDGE_GAP);
        assertEquals(4, g.stream().filter(o -> o.kind() == Kind.PLACE).count());
        assertEquals(new Op(Kind.MOVE, 4, 0, 0, 0), g.get(g.size() - 1));
        assertTrue(g.stream().filter(o -> o.kind() == Kind.PLACE).allMatch(o -> o.u() == -1));
    }

    @Test
    public void housingDigAndTunnel() {
        List<Op> d = BastionScript.ops(Step.DIG_DOWN_3_STRIP_4);
        assertEquals(new Op(Kind.MOVE, 0, 0, -3, 0), d.get(5));
        assertEquals(new Op(Kind.MOVE, 4, 0, -3, 0), d.get(d.size() - 1));
        List<Op> t = BastionScript.ops(Step.T_TUNNEL_GOLD);
        assertTrue(t.contains(new Op(Kind.MINE, 4, -1, -3, 0)));
        assertTrue(t.contains(new Op(Kind.MINE, 4, 1, -3, 0)));
        assertEquals(Kind.PUSH_ITEMS, t.get(t.size() - 1).kind());
        assertEquals(Kind.PUSH_ITEMS, BastionScript.ops(Step.MINE_WALL_GOLD).get(0).kind());
    }

    @Test
    public void elevatorAndLava() {
        List<Op> e = BastionScript.ops(Step.GOLD_ELEVATOR);
        assertEquals(5, e.stream().filter(o -> o.kind() == Kind.PLACE).count());
        assertEquals(27, e.get(5).n());
        assertEquals(36, e.get(6).n());
        assertEquals(2, BastionScript.ops(Step.LAVA_BLOCK_GAP).get(0).n());
        assertEquals(3, BastionScript.ops(Step.BLOCK_LOWER_LAVA).get(0).n());
        assertEquals(Kind.LAVA_ON_PIGLIN, BastionScript.ops(Step.LAVA_STRAGGLERS).get(0).kind());
        assertEquals(Kind.UNSTICK, BastionScript.ops(Step.UNSTICK_BY_MINING).get(0).kind());
    }

    @Test
    public void frameAndBasaltBridge() {
        assertArrayEquals(new int[]{2, 0, 0}, BastionScript.toWorld(1, 0, 2, 0, 0));
        assertArrayEquals(new int[]{0, 0, 1}, BastionScript.toWorld(1, 0, 0, 1, 0)); // facing east, right is south
        assertArrayEquals(new int[]{-1, 1, 0}, BastionScript.toWorld(0, 1, 0, 1, 1)); // facing south, right is west
        assertEquals(2, BastionScript.thirdFromLeft(J8.listOf(5, -3, 2, 0)));
        assertEquals(1, BastionScript.thirdFromLeft(J8.listOf(1)));
        assertEquals(Integer.MIN_VALUE, BastionScript.thirdFromLeft(java.util.Collections.emptyList()));
    }

    @Test
    public void runnerAdvancesAndSkips() {
        Runner r = new Runner(BastionScript.ops(Step.STAIR_2));
        r.report(Result.WORKING);
        assertEquals(0, r.index());
        r.report(Result.DONE);
        assertEquals(1, r.index());
        for (int k = 0; k <= Runner.OP_TICKS; k++) r.report(Result.WORKING);
        assertTrue(r.done());
        assertEquals(1, r.skipped());
        Runner f = new Runner(BastionScript.ops(Step.PILLAR_3_HUG));
        f.report(Result.FAILED);
        assertTrue(f.done());
    }
}
