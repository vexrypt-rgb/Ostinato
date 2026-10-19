/*
 * Ostinato: new file (spectator learning tests). Not part of upstream Baritone.
 */
package baritone.spectate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: fights start and end as they should, and what winners did reaches the priors only after enough fights. */
public class FightBookTest {
    @Test
    public void deathDecidesAndPriorsWaitForTwenty() {
        int[] ended = new int[1];
        FightBook book = new FightBook(f -> {}, (f, w, why) -> ended[0]++);
        SpectatePriors pr = SpectatePriors.get();
        pr.enabled(true);
        for (int fight = 0; fight < 25; fight++) {
            int a = 100 + 2 * fight, b = a + 1, t0 = fight * 1000;
            for (int i = 0; i < 6; i++) book.hit(t0 + i * 15, a, "A", b, "B");
            FightBook.Fight f = book.fightsOf(a).get(0);
            f.of(a).ate(t0 + 50, true, 7); // the winner eats at 7
            f.of(b).ate(t0 + 60, true, 3);
            book.died(t0 + 100, b);
            pr.update(book);
            if (fight < SpectatePriors.MIN_FIGHTS - 1) assertEquals(null, pr.prior("heal.eatNear"));
        }
        assertEquals(25, ended[0]);
        assertEquals(25, book.decided());
        assertEquals(7, pr.prior("heal.eatNear").value, 1e-9);
        // baseline 9, step 2: one step down, shrunk by 25/65
        assertEquals(-1 * 25.0 / 65, pr.startSteps("heal.eatNear", 9, 2), 1e-9);
        pr.enabled(false);
        assertEquals(0, pr.startSteps("heal.eatNear", 9, 2), 1e-9);
    }

    @Test
    public void quietFightsEndAndShortOnesAreDropped() {
        FightBook book = new FightBook(null, null);
        book.hit(0, 1, "x", 2, "y");
        book.tick(FightBook.QUIET + 1, (a, b) -> 3.0, id -> 10.0);
        assertTrue(book.active().isEmpty());
        assertEquals(0, book.decided());
    }

    /** Ostinato: a fight joined late still learns habits but stays out of winners and losers unless asked. */
    @Test
    public void lateFightsStayOutOfTallies() {
        FightBook book = new FightBook(null, null);
        for (int i = 0; i < 6; i++) book.hit(i * 15, 1, "A", 2, "B", false, i == 0 ? "hurt:A" : null);
        FightBook.Fight f = book.fightsOf(1).get(0);
        assertEquals("hurt:A", f.late);
        f.of(1).ate(50, true, 7);
        book.died(100, 2);
        assertEquals(0, book.winnerCount("eatHp"));
        assertEquals(1, book.lateSkipped());
        // a later hit's reason never marks a fight seen from its start
        for (int i = 0; i < 6; i++) book.hit(1000 + i * 15, 3, "C", 4, "D", false, i == 0 ? null : "combo:C");
        assertEquals(null, book.fightsOf(3).get(0).late);
        book.fightsOf(3).get(0).of(3).ate(1050, true, 7);
        book.died(1100, 4);
        assertEquals(1, book.winnerCount("eatHp"));
        // the setting lets them in
        book.includeLate(true);
        for (int i = 0; i < 6; i++) book.hit(2000 + i * 15, 5, "E", 6, "F", false, "gapple:E");
        book.fightsOf(5).get(0).of(5).ate(2050, true, 7);
        book.died(2100, 6);
        assertEquals(2, book.winnerCount("eatHp"));
    }

    @Test
    public void lateReasons() {
        assertEquals("arrived:A", SpectateLearner.lateReason("A", 20, 20, 0, false, Integer.MAX_VALUE, false, 40));
        assertEquals("hurt:A", SpectateLearner.lateReason("A", 14, 20, 0, false, Integer.MAX_VALUE, false, 400));
        assertEquals("absorption:A", SpectateLearner.lateReason("A", 20, 20, 4, false, Integer.MAX_VALUE, false, 400));
        assertEquals("combo:A", SpectateLearner.lateReason("A", 20, 20, 0, false, 6, false, 400));
        assertEquals("combo:A", SpectateLearner.lateReason("A", 20, 20, 0, true, Integer.MAX_VALUE, false, 400));
        assertEquals("gapple:A", SpectateLearner.lateReason("A", 20, 20, 0, false, 40, true, 400));
        assertEquals(null, SpectateLearner.lateReason("A", 20, 20, 0, false, 40, false, 400));
    }
}
