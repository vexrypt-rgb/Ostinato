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

    @Test
    public void parsesAuthClick() { // Ostinato: clickable chat prompts during login
        List<String[]> r = ServerSpectate.parseRoute(Arrays.asList("authclick premium account | no", "wait 1"), new ArrayList<>());
        assertArrayEquals(new String[]{"authclick", "premium account | no"}, r.get(0));
        assertArrayEquals(new String[]{"premium account", "no"}, ServerSpectate.parseAuthClick(r.get(0)[1]));
        assertNull(ServerSpectate.parseAuthClick("no bar here"));
        assertNull(ServerSpectate.parseAuthClick(" | no"));
    }

    @Test
    public void picksTheNoButton() {
        // a typical cracked-server prompt, as its parts: only the two buttons carry a run-command click
        List<String> texts = Arrays.asList("\n", " Are you using a premium account?\n", " (after accepting, you will need to re-join.)\n", "\n", "  ", "[\u2714 Yes]", "      ", "[\u2716 No]", "\n");
        List<Boolean> click = Arrays.asList(false, false, false, false, false, true, false, true, false);
        assertEquals(7, ServerSpectate.pickChoice(texts, click, "no"));
        assertEquals(5, ServerSpectate.pickChoice(texts, click, "yes"));
        // a word inside another word, or a part that is not clickable, never counts
        assertEquals(-1, ServerSpectate.pickChoice(Arrays.asList("[Join now]", "No"), Arrays.asList(true, false), "no"));
        assertEquals(0, ServerSpectate.pickChoice(Arrays.asList("\u00a7c[\u00a7lNO\u00a7c]"), Arrays.asList(true), "no"));
    }

    @Test
    public void readsRegisterReminders() { // Ostinato: action-bar reminders count as a prompt
        assertTrue(ServerSpectate.REMINDER.matcher("you have 89 seconds to register.").find());
        assertTrue(ServerSpectate.REMINDER.matcher("you have 1 second to login").find());
        assertFalse(ServerSpectate.REMINDER.matcher("[lobby] playing: music_disc.precipice").find());
    }
}
