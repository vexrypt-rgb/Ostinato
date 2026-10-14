package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** How late the server is, read from our own hits: what counts as one, what the measure makes of them, and when the band moves. */
public class CombatLagTest {

    private static final int FOE = 7;
    private static final double D = 1.0e-9, LOCAL = CombatLag.Rules.LOCAL;

    private final CombatLag lag = new CombatLag();
    private int now;

    private void tick(int foe, int hurtTime, boolean clicked) {
        lag.tick(++now, foe, hurtTime, 10, clicked, false);
    }

    /** A click of ours whose flash comes {@code delay} ticks later, and the hurt run out after it. */
    private void hit(int delay) {
        tick(FOE, 0, true);
        for (int i = 1; i < delay; i++) tick(FOE, 0, false);
        for (int h = 10; h >= 0; h--) tick(FOE, h, false);
    }

    private void hits(int times, int... delays) {
        for (int i = 0; i < times; i++) hit(delays[i % delays.length]);
    }

    @Test
    public void withTheServerInOurOwnProcessThereIsNoLag() {
        tick(FOE, 0, false);
        hits(CombatLag.Rules.KEEP, 1, 1, 1, 2); // as recorded with one kit: one tick for most hits, two for a quarter
        assertEquals(CombatLag.Rules.KEEP, lag.hits());
        assertEquals(0, lag.ticks(), D);
        assertEquals(0, lag.band());
        hits(CombatLag.Rules.KEEP, 1, 2); // and with another: as many twos as ones
        assertTrue(lag.ticks() < 0.25);
        assertEquals(0, lag.band());
    }

    @Test
    public void aServerTwoTicksOffReadsAsTwo() {
        tick(FOE, 0, false);
        hits(CombatLag.Rules.KEEP, 3, 3, 3, 4);
        assertEquals(3.25 - LOCAL, lag.ticks(), D);
        assertEquals(2, lag.band());
    }

    @Test
    public void untilEnoughHitsHaveLandedTheServersFigureIsRead() {
        lag.reported(100);
        assertEquals(2, lag.ticks(), D);
        assertEquals(2, lag.band());
        tick(FOE, 0, false);
        hits(CombatLag.Rules.ENOUGH - 1, 1);
        lag.reported(100);
        assertEquals("too few hits to say otherwise", 2, lag.band());
        hit(1);
        assertEquals("the fight says there is none", 0, lag.band());
        lag.reported(100);
        assertEquals(0, lag.band());
    }

    @Test
    public void aServerThatGivesNoFigureIsTakenAsNearUntilTheHitsSay() {
        lag.reported(-1);
        assertEquals(0, lag.band());
        tick(FOE, 0, false);
        hits(CombatLag.Rules.ENOUGH, 5);
        assertEquals(4, lag.band());
    }

    @Test
    public void theBandHoldsAgainstAMeasureBetweenTwo() {
        assertTrue("half a tick would flip with every hit", CombatLag.Rules.HOLD > 0.5 && CombatLag.Rules.HOLD < 1);
        assertEquals(2, CombatLag.Rule.band(2, 2.6));
        assertEquals(2, CombatLag.Rule.band(2, 1.4));
        assertEquals(3, CombatLag.Rule.band(2, 2.8));
        assertEquals(3, CombatLag.Rule.band(3, 2.4));
        assertEquals(1, CombatLag.Rule.band(3, 1.2));
        assertEquals(CombatLag.Rules.TOP, CombatLag.Rule.band(0, 40));
        assertEquals(0, CombatLag.Rule.band(1, 0));
    }

    @Test
    public void aFlashThatIsNotOursIsNotCounted() {
        tick(FOE, 0, false);
        tick(FOE, 10, false); // no click before it
        for (int h = 9; h >= 0; h--) tick(FOE, h, false);
        tick(FOE, 10, true); // on the click's own tick: it was on its way already
        for (int h = 9; h >= 0; h--) tick(FOE, h, false);
        assertEquals(0, lag.hits());

        tick(FOE, 0, true);
        for (int i = 0; i < CombatLag.Rules.WAIT; i++) tick(FOE, 0, false);
        tick(FOE, 10, false); // later than any answer to that click
        for (int h = 9; h >= 0; h--) tick(FOE, h, false);
        assertEquals(0, lag.hits());

        tick(FOE, 0, true);
        tick(FOE + 1, 10, false); // another foe's
        assertEquals(0, lag.hits());

        tick(FOE, 0, false);
        tick(FOE, 0, true);
        now += 40; // the fight stopped and began again
        tick(FOE, 0, false);
        tick(FOE, 10, false);
        assertEquals(0, lag.hits());

        for (int h = 9; h >= 0; h--) tick(FOE, h, false);
        lag.tick(++now, FOE, 0, 10, true, true); // a click at a foe on fire is not waited on
        lag.tick(++now, FOE, 10, 10, false, false);
        for (int h = 9; h >= 0; h--) tick(FOE, h, false);
        tick(FOE, 0, true);
        lag.tick(++now, FOE, 10, 10, false, true); // nor is a flash once it has caught fire
        assertEquals(0, lag.hits());
    }

    @Test
    public void aStrayFlashOrTwoDoesNotMoveTheMeasure() {
        tick(FOE, 0, false);
        hits(CombatLag.Rules.KEEP - 2, 3);
        hits(2, CombatLag.Rules.WAIT); // another hand, long after a click of ours that was blocked
        assertEquals(3 - LOCAL, lag.ticks(), D);
        assertEquals(2, lag.band());
        assertEquals(3, CombatLag.Rule.cluster(new int[]{1, 3, 3, 3, 3, 3, 3, 12}, 8), D);
        assertTrue(Double.isNaN(CombatLag.Rule.cluster(new int[4], 0)));
    }

    /** As fought on 2026-10-12: a foe in our lava flashed eleven ticks after a fifth of our clicks, on a server with no lag at all. */
    @Test
    public void aBeatOfSomeOtherHurtIsNotReadAsLag() {
        assertEquals(13 / 9.0, CombatLag.Rule.cluster(new int[]{1, 2, 11, 1, 11, 2, 1, 11, 11, 2, 1, 11, 12, 1, 2, 11}, 16), D);
        assertEquals("the fullest pair, and of two as full the earlier", 1.5, CombatLag.Rule.cluster(new int[]{1, 2, 11, 12}, 4), D);
        assertEquals(5, CombatLag.Rule.cluster(new int[]{5, 5, 5}, 3), D);
        assertEquals(CombatLag.Rules.WAIT, CombatLag.Rule.cluster(new int[]{CombatLag.Rules.WAIT}, 1), D);
    }

    @Test
    public void aSecondClickInsideTheWaitLeavesTheFirstCounting() {
        tick(FOE, 0, false);
        for (int i = 0; i < CombatLag.Rules.ENOUGH; i++) {
            tick(FOE, 0, true);
            tick(FOE, 0, false);
            tick(FOE, 0, true); // the foe cannot be hurt again this soon: the flash to come is the first click's
            tick(FOE, 0, false);
            for (int h = 10; h >= 0; h--) tick(FOE, h, false);
        }
        assertEquals(4 - LOCAL, lag.ticks(), D); // four ticks from the first click, less what the nearest server takes
    }

    @Test
    public void onlyTheNewestHitsAreKept() {
        tick(FOE, 0, false);
        hits(CombatLag.Rules.KEEP, 5);
        assertEquals(4, lag.band());
        hits(CombatLag.Rules.KEEP, 1);
        assertEquals(CombatLag.Rules.KEEP, lag.hits());
        assertEquals(0, lag.band());
    }

    @Test
    public void anotherServerStartsFromNothing() {
        lag.reported(200);
        tick(FOE, 0, false);
        hits(CombatLag.Rules.KEEP, 5);
        lag.reset();
        assertEquals(0, lag.hits());
        assertEquals(0, lag.band());
        assertEquals(0, lag.ticks(), D);
    }
}
