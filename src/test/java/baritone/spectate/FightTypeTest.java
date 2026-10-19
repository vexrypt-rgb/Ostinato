/*
 * Ostinato: new file (spectator fight type tests). Not part of upstream Baritone.
 */
package baritone.spectate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: fights are told 1v1, FFA and team apart, teams found, and only 1v1 fights reach the 1v1 tallies. */
public class FightTypeTest {
    private static final class Run {
        final List<FightGroup> groups = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final FightBook book = new FightBook(null, (f, w, why) -> types.add(f.type()));
        final Map<Integer, double[]> pos = new HashMap<>();

        Run() {
            book.onGroup(groups::add);
        }

        void at(int id, double x, double z) {
            pos.put(id, new double[]{x, 64, z});
        }

        void second(int t) {
            book.track(t, pos);
            book.tick(t, (a, b) -> {
                double[] pa = pos.get(a), pb = pos.get(b);
                return pa == null || pb == null ? Double.NaN : Math.hypot(pa[0] - pb[0], pa[2] - pb[2]);
            }, id -> 10.0);
        }

        void end(int t) {
            pos.clear();
            second(t);
        }
    }

    @Test
    public void oneVersusOne() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 2, 0);
        for (int t = 0; t < 200; t += 10) r.book.hit(t, t % 20 == 0 ? 1 : 2, t % 20 == 0 ? "a" : "b", t % 20 == 0 ? 2 : 1, t % 20 == 0 ? "b" : "a");
        r.book.died(210, 2);
        assertEquals(1, r.groups.size());
        assertEquals("1v1", r.groups.get(0).type);
        assertEquals(1, r.book.decided());
        assertEquals(List.of("1v1"), r.types);
    }

    @Test
    public void threeWayFreeForAllIsKeptOut() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 3, 0);
        r.at(3, 0, 3);
        int[][] pairs = {{1, 2}, {2, 3}, {3, 1}, {2, 1}, {3, 2}, {1, 3}};
        for (int t = 0; t < 400; t += 5) {
            int[] p = pairs[(t / 5) % pairs.length];
            r.book.hit(t, p[0], "p" + p[0], p[1], "p" + p[1]);
            if (t % 20 == 0) r.second(t);
        }
        r.end(400 + FightBook.QUIET + 21);
        assertEquals(1, r.groups.size());
        FightGroup g = r.groups.get(0);
        assertEquals("ffa", g.type);
        assertEquals("ffa3", g.shape);
        assertEquals(0, r.book.decided());
        assertEquals(1, (int) r.book.multi().get("ffa"));
        assertEquals(3, r.types.size());
        for (String t : r.types) assertEquals("ffa", t);
        assertTrue(g.skillsLine(), g.skills.get(1).getOrDefault("switches", 0) > 0);
    }

    private static void twoVersusTwo(Run r, boolean scoreboard) {
        if (scoreboard) {
            r.book.clue(1, new FightGroup.Clue("red", 12, -1, -1, null));
            r.book.clue(2, new FightGroup.Clue("red", 12, -1, -1, null));
            r.book.clue(3, new FightGroup.Clue("blue", 9, -1, -1, null));
            r.book.clue(4, new FightGroup.Clue("blue", 9, -1, -1, null));
        }
        // 1 and 2 stand together, 3 and 4 together; only hits across
        r.at(1, 0, 0);
        r.at(2, 1.5, 0);
        r.at(3, 0, 3);
        r.at(4, 1.5, 3);
        int[][] hits = {{1, 3}, {3, 1}, {2, 4}, {4, 2}, {1, 4}, {2, 3}, {4, 1}, {3, 2}, {1, 3}, {2, 3}};
        for (int t = 0, i = 0; t < 600; t += 6, i++) {
            int[] h = hits[i % hits.length];
            r.book.hit(t, h[0], "p" + h[0], h[1], "p" + h[1]);
            if (t % 20 == 0) r.second(t);
        }
        r.end(600 + FightBook.QUIET + 21);
    }

    @Test
    public void twoVersusTwoFromWhoHitsWhom() {
        Run r = new Run();
        twoVersusTwo(r, false);
        assertEquals(1, r.groups.size());
        FightGroup g = r.groups.get(0);
        assertEquals(g.describe(), "team", g.type);
        assertEquals("2v2", g.shape);
        assertEquals(g.teamsLine(), 2, g.teams.size());
        assertTrue(g.teamsLine(), g.teamsLine().equals("p1,p2|p3,p4") || g.teamsLine().equals("p3,p4|p1,p2"));
        assertEquals(0, r.book.decided());
        assertTrue(g.skillsLine(), g.skillsLine().contains("focus"));
    }

    @Test
    public void twoVersusTwoFromTheScoreboard() {
        Run r = new Run();
        twoVersusTwo(r, true);
        assertEquals("2v2", r.groups.get(0).shape);
    }

    @Test
    public void scoreboardTeamsKeepAnFfaAnFfa() {
        // four players on four scoreboard teams; 1 and 2 never happened to hit each other: still FFA
        Run r = new Run();
        for (int i = 1; i <= 4; i++) r.book.clue(i, new FightGroup.Clue("t" + i, i, -1, -1, null));
        r.at(1, 0, 0);
        r.at(2, 2, 0);
        r.at(3, 0, 2);
        r.at(4, 2, 2);
        int[][] hits = {{1, 3}, {3, 1}, {2, 4}, {4, 2}, {1, 4}, {4, 1}, {2, 3}, {3, 2}, {3, 4}, {4, 3}};
        for (int t = 0, i = 0; t < 600; t += 6, i++) {
            int[] h = hits[i % hits.length];
            r.book.hit(t, h[0], "p" + h[0], h[1], "p" + h[1]);
            if (t % 20 == 0) r.second(t);
        }
        r.end(600 + FightBook.QUIET + 21);
        assertEquals("ffa", r.groups.get(0).type);
        assertEquals("ffa4", r.groups.get(0).shape);
    }

    @Test
    public void thirdPartyTurnsAOneVersusOneIntoFfa() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 2, 0);
        r.at(3, 10, 0);
        for (int t = 0; t < 200; t += 10) r.book.hit(t, t % 20 == 0 ? 1 : 2, "a", t % 20 == 0 ? 2 : 1, "b");
        // 3 steps in on 1 while 1 and 2 still trade, and 1 hits back
        for (int t = 200; t < 300; t += 10) {
            r.book.hit(t, 2, "b", 1, "a");
            r.book.hit(t + 5, t % 20 == 0 ? 3 : 1, t % 20 == 0 ? "c" : "a", t % 20 == 0 ? 1 : 3, t % 20 == 0 ? "a" : "c");
        }
        r.end(300 + FightBook.QUIET + 21);
        assertEquals(1, r.groups.size());
        assertEquals("ffa", r.groups.get(0).type);
        assertTrue(r.groups.get(0).skillsLine(), r.groups.get(0).skills.get(3).getOrDefault("thirdParty", 0) > 0);
        assertEquals(0, r.book.decided());
    }

    @Test
    public void kitsAreKeptPerKit() {
        Run r = new Run();
        Map<String, Double> sword = new HashMap<>();
        sword.put("armor:diamond", 1.0);
        sword.put("diamond_sword", 1.0);
        r.book.gear(0, 1, sword);
        r.book.gear(0, 2, sword);
        for (int t = 0; t < 200; t += 10) r.book.hit(t, t % 20 == 0 ? 1 : 2, "a", t % 20 == 0 ? 2 : 1, "b");
        r.book.died(210, 2);
        assertEquals("sword", r.groups.get(0).kits.get(1).label);
        assertTrue(r.book.kitsSeen().contains("sword"));
    }


    /** Ostinato: a 2v2 with no colours whose second pair never stands together (bench, v1.24.0: split into 2v1v1). */
    @Test
    public void twoVersusTwoApartFromTheWholeFight() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 1.5, 0);
        r.at(3, 0, 5);
        r.at(4, 12, 5);
        int[][] hits = {{1, 3}, {3, 1}, {2, 4}, {4, 2}, {1, 4}, {3, 2}, {4, 1}, {2, 3}};
        for (int t = 0, i = 0; t < 600; t += 6, i++) {
            int[] h = hits[i % hits.length];
            r.book.hit(t, h[0], "p" + h[0], h[1], "p" + h[1]);
            if (t % 20 == 0) r.second(t);
        }
        r.end(600 + FightBook.QUIET + 21);
        assertEquals(1, r.groups.size());
        FightGroup g = r.groups.get(0);
        assertEquals(g.describe(), "2v2", g.shape);
        assertTrue(g.teamsLine(), g.teamsLine().equals("p1,p2|p3,p4") || g.teamsLine().equals("p3,p4|p1,p2"));
    }

    /** Ostinato: a chain of duels 1-2, 2-3, 3-4 also splits into two sides, but not every pair across traded hits. */
    @Test
    public void chainOfDuelsIsNotTwoTeams() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 3, 0);
        r.at(3, 6, 0);
        r.at(4, 9, 0);
        int[][] hits = {{1, 2}, {2, 1}, {2, 3}, {3, 2}, {3, 4}, {4, 3}};
        for (int t = 0, i = 0; t < 600; t += 6, i++) {
            int[] h = hits[i % hits.length];
            r.book.hit(t, h[0], "p" + h[0], h[1], "p" + h[1]);
            if (t % 20 == 0) r.second(t);
        }
        r.end(600 + FightBook.QUIET + 21);
        assertEquals(1, r.groups.size());
        // the whole-fight sides rule stays out of it (what the other clues make of it is their call)
        assertEquals(null, r.groups.get(0).foeSides());
    }


    /** Ostinato: a bow duel (bench, v1.24.0: never seen as a fight) is a fight, fought from 35 blocks apart. */
    @Test
    public void bowDuelIsAFight() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 35, 0);
        for (int t = 0; t < 400; t += 20) {
            if (t % 40 == 0) r.book.rangedHit(t, 1, "a", 2, "b");
            else r.book.rangedHit(t, 2, "b", 1, "a");
            r.second(t);
        }
        // 35 blocks is past APART, which ends a melee fight
        assertEquals(1, r.book.active().size());
        assertTrue(r.book.active().iterator().next().ranged);
        r.book.died(410, 2);
        assertEquals(1, r.groups.size());
        assertEquals("1v1", r.groups.get(0).type);
        assertEquals(1, r.book.decided());
    }

    /** Ostinato: arrows shot that miss keep a ranged fight from going quiet, and start none on their own. */
    @Test
    public void arrowsShotKeepItOn() {
        Run r = new Run();
        r.at(1, 0, 0);
        r.at(2, 30, 0);
        r.book.shotAt(0, 1, 2);
        assertEquals(0, r.book.active().size());
        for (int t = 0; t < 60; t += 20) {
            r.book.rangedHit(t, 1, "a", 2, "b");
            r.book.rangedHit(t + 10, 2, "b", 1, "a");
        }
        // then only misses, for longer than QUIET
        for (int t = 100; t < 100 + 2 * FightBook.QUIET; t += 20) {
            r.book.shotAt(t, t % 40 == 0 ? 1 : 2, t % 40 == 0 ? 2 : 1);
            r.second(t);
        }
        assertEquals(1, r.book.active().size());
    }
}
