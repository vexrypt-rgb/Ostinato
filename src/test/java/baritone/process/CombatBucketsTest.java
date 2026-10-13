package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the water and the lava bucket come out and when a poured source is taken back: the rules, with the policy's own numbers. */
public class CombatBucketsTest {

    private final CombatPolicy.Buckets p = new CombatPolicy().buckets;

    @Test
    public void waterGoesOnAFireOnLavaAndOnACobweb() {
        assertTrue(CombatBuckets.Rule.douse(true, false, false, false, false, false));
        assertTrue(CombatBuckets.Rule.douse(false, true, false, false, false, false));
        assertTrue(CombatBuckets.Rule.douse(false, false, true, false, false, false));
        assertFalse("nothing wrong, no water", CombatBuckets.Rule.douse(false, false, false, false, false, false));
    }

    @Test
    public void fireThatIsNotFeltIsNotDoused() {
        assertFalse(CombatBuckets.Rule.douse(true, true, false, true, false, false));
        assertTrue("a cobweb holds us whatever we have drunk", CombatBuckets.Rule.douse(true, false, true, true, false, false));
    }

    @Test
    public void noWaterWhereItBoilsOffOrWhenAlreadyWet() {
        assertFalse(CombatBuckets.Rule.douse(true, true, true, false, false, true));
        assertFalse(CombatBuckets.Rule.douse(true, false, false, false, true, false));
        assertTrue("rain puts a fire out and leaves a cobweb where it is", CombatBuckets.Rule.douse(false, false, true, false, true, false));
        assertFalse(CombatBuckets.Rule.falling(p.minFall + 10, p.minFall, -1, false, true));
    }

    @Test
    public void aLongFallGetsWaterAShortOneDoesNot() {
        assertTrue(CombatBuckets.Rule.falling(p.minFall, p.minFall, -1, false, false));
        assertFalse(CombatBuckets.Rule.falling(p.minFall - 0.1, p.minFall, -1, false, false));
        assertFalse("still rising", CombatBuckets.Rule.falling(p.minFall, p.minFall, 0.2, false, false));
        assertFalse("water is already under us", CombatBuckets.Rule.falling(p.minFall, p.minFall, -1, true, false));
        assertTrue("the fall it is used on is one that hurts", p.minFall > 3);
    }

    @Test
    public void lavaIsPouredFromItsBandAtAFoeThatWillStandInIt() {
        double mid = (p.lavaLo + p.lavaHi) / 2;
        assertTrue(CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, true, false, false, false, 0));
        assertFalse("too near: its flow is ours too", CombatBuckets.Rule.pour(p.lavaLo, p.lavaLo, p.lavaHi, true, true, false, false, false, 0));
        assertFalse("beyond the bucket", CombatBuckets.Rule.pour(p.lavaHi, p.lavaLo, p.lavaHi, true, true, false, false, false, 0));
        assertFalse("not seen", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, false, true, false, false, false, 0));
        assertFalse("in the air it lands somewhere else", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, false, false, false, false, 0));
        assertFalse("uphill of us it runs down to us", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, true, false, false, false, 1));
        assertTrue("the bucket reaches less than 4.5 blocks", p.lavaHi <= 4.5 && p.lavaLo < p.lavaHi);
    }

    /** The bucket comes out only when the view is on the foe's cell; a try that does not get there ends, and the next one waits. */
    @Test
    public void aTryThatDoesNotComeToRestIsGivenUp() {
        assertFalse(CombatBuckets.Rule.missed(p.lavaAim, p.lavaAim));
        assertTrue(CombatBuckets.Rule.missed(p.lavaAim + 1, p.lavaAim));
        assertTrue("the wait after a miss is longer than the try was", p.lavaRetry > p.lavaAim);
        assertTrue("and no longer than the wait after a pour", p.lavaRetry <= p.lavaGap);
    }

    @Test
    public void lavaIsNotSpentOnAFoeItCannotSetAlight() {
        double mid = (p.lavaLo + p.lavaHi) / 2;
        assertFalse("already burning", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, true, true, false, false, 0));
        assertFalse("fireproof", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, true, false, true, false, 0));
        assertFalse("standing in water", CombatBuckets.Rule.pour(mid, p.lavaLo, p.lavaHi, true, true, false, false, true, 0));
    }

    @Test
    public void lavaIsTakenBackWhenTheFoeBurnsOrItsTimeIsUp() {
        assertTrue(CombatBuckets.Rule.lavaBack(0, p.lavaHold, true));
        assertFalse(CombatBuckets.Rule.lavaBack(p.lavaHold - 1, p.lavaHold, false));
        assertTrue(CombatBuckets.Rule.lavaBack(p.lavaHold, p.lavaHold, false));
    }

    /** Water under a fall is poured before the landing: taken back on the clock alone it would be gone when we arrive. */
    @Test
    public void waterIsTakenBackOnlyWhenItsWorkIsDone() {
        assertFalse(CombatBuckets.Rule.waterBack(p.limit, p.waterHold, false));
        assertFalse(CombatBuckets.Rule.waterBack(p.waterHold - 1, p.waterHold, true));
        assertTrue(CombatBuckets.Rule.waterBack(p.waterHold, p.waterHold, true));
    }

    /** The limit is what stops a source that cannot be reached from holding the fight for ever. */
    @Test
    public void aSourceThatCannotBeFetchedIsLeft() {
        assertFalse(CombatBuckets.Rule.abandon(p.limit, p.limit, 2, p.far));
        assertTrue(CombatBuckets.Rule.abandon(p.limit + 1, p.limit, 2, p.far));
        assertTrue(CombatBuckets.Rule.abandon(0, p.limit, p.far + 0.1, p.far));
        assertTrue("both holds end inside the limit", p.waterHold < p.limit && p.lavaHold < p.limit);
    }
}
