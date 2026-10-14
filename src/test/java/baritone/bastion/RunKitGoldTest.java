package baritone.bastion;

import baritone.bastion.BastionRoutes.Step;
import baritone.bastion.BastionScript.Result;
import baritone.bastion.BastionScript.Runner;
import org.junit.Test;

import static org.junit.Assert.*;

public class RunKitGoldTest {
    @Test
    public void noGoldNoWatchedBreak() { // s1161-b10
        assertFalse(BastionPriors.breakGoldUnarmed(10, 0));
        assertTrue(BastionPriors.breakGoldUnarmed(0, 0));
        assertTrue(BastionPriors.breakGoldUnarmed(10, 3));
    }

    @Test
    public void abortSkipsTheRest() { // s1161-b10: cue never found, its mines ran elsewhere
        Runner r = new Runner(BastionScript.ops(Step.HOUSING_ENTRY_BASALT_CUE));
        r.report(Result.FAILED); r.report(Result.FAILED);
        assertTrue(r.event().endsWith("skipped"));
        assertFalse(r.done());
        r.abort();
        assertTrue(r.done());
        assertEquals(BastionScript.ops(Step.HOUSING_ENTRY_BASALT_CUE).size(), r.skipped());
    }
}
