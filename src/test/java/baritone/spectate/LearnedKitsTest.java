/*
 * Ostinato: new file (learned kit tests). Not part of upstream Baritone.
 */
package baritone.spectate;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: a loadout no kit fits becomes a learned kit, is found again, is saved, and is tallied as its own kit. */
public class LearnedKitsTest {
    private static Map<String, Double> odd(double carrot) {
        Map<String, Double> m = new HashMap<>();
        m.put("armor:chainmail", 1.0);
        m.put("iron_sword", 1.0);
        m.put("golden_carrot", carrot);
        return m;
    }

    @Test
    public void learnMergeAndReload() throws Exception {
        Path f = Files.createTempFile("learned", ".txt");
        Files.delete(f);
        LearnedKits l = new LearnedKits(f);
        LearnedKits.Kit a = l.learn(odd(0.4));
        LearnedKits.Kit b = l.learn(odd(0.6)); // near duplicate
        assertEquals(a.label, b.label);
        assertEquals(2, b.n);
        assertEquals("learned-1", a.label);
        assertTrue(a.description, a.description.startsWith("chainmail + iron sword"));
        Map<String, Double> mace = new HashMap<>();
        mace.put("armor:golden", 1.0);
        mace.put("trident", 1.0);
        mace.put("threw:wind_charge", 1.0);
        LearnedKits.Kit c = l.learn(mace);
        assertNotEquals(a.label, c.label);
        LearnedKits again = new LearnedKits(f);
        assertEquals(2, again.kits().size());
        assertEquals(2, again.kits().get(0).n);
        assertEquals(a.label, ((LearnedKits.Kit) again.match(odd(0.5))[0]).label);
        assertEquals("learned-3", again.learn(leatherStick()).label);
        Files.deleteIfExists(f);
    }

    @Test
    public void unknownFightersGetALearnedKitAndItsOwnTally() throws Exception {
        Path f = Files.createTempFile("learned", ".txt");
        Files.delete(f);
        FightBook book = new FightBook(null, null);
        book.learned(new LearnedKits(f));
        for (int fight = 0; fight < 2; fight++) {
            int a = 10 + 2 * fight, b = a + 1, t0 = fight * 2000;
            book.gear(t0, a, odd(0.3));
            book.gear(t0, b, odd(0.3));
            for (int t = t0; t < t0 + 300; t += 10) book.hit(t, t % 20 == 0 ? a : b, "a", t % 20 == 0 ? b : a, "b");
            book.died(t0 + 310, b);
        }
        assertTrue(book.kitsSeen().toString(), book.kitsSeen().contains("learned-1"));
        assertEquals(1, new LearnedKits(f).kits().size());
        assertTrue(new LearnedKits(f).kits().get(0).n >= 2);
        Files.deleteIfExists(f);
    }

    // Ostinato: Java 8 (no Map.of)
    private static java.util.Map<String, Double> leatherStick() {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        m.put("armor:leather", 1.0);
        m.put("stick", 1.0);
        return m;
    }
}
