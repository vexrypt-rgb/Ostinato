package baritone.spectate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: route file parsing and menu-word scoring for auto-spectate. */
public class ServerSpectateTest {

    @Test
    public void parsesRoute() {
        List<String> words = new ArrayList<>();
        List<String[]> r = ServerSpectate.parseRoute(Arrays.asList("# c", "words spectate, ffa", "wait 2", "cmd /spec", "hotbar Selector", "click FFA", "bogus x"), words);
        assertEquals(Arrays.asList("spectate", "ffa"), words);
        assertEquals(4, r.size());
        assertArrayEquals(new String[]{"cmd", "/spec"}, r.get(1));
        assertArrayEquals(new String[]{"click", "FFA"}, r.get(3));
    }

    @Test
    public void scoresMenuWords() {
        String[] w = {"spectate", "ffa", "duel"};
        assertTrue(ServerSpectate.score("Spectate a match", w) > ServerSpectate.score("FFA Arena", w));
        assertTrue(ServerSpectate.score("FFA Arena", w) > ServerSpectate.score("Duels", w));
        assertEquals(0, ServerSpectate.score("Cosmetics", w));
        assertEquals(-1, ServerSpectate.score("Ranked Duel Queue", w)); // never clicked: it would start a fight
        assertEquals(-1, ServerSpectate.score("Shop", w));
    }
}
