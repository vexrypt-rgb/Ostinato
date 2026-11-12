package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: every built-in theme meets WCAG AA, and tile status never relies on color alone. */
public class CenterThemeTest {

    private static final CenterTheme T = CenterTheme.DEFAULT_DARK;

    @Test public void textMeetsAaOnEverySurface() {
        for (int bg : new int[] {T.background(), T.surface(), T.raised()}) {
            assertTrue(CenterTheme.contrast(T.text(), bg) >= 4.5);
            assertTrue(CenterTheme.contrast(T.muted(), bg) >= 4.5);
        }
        assertTrue(CenterTheme.contrast(T.faint(), T.surface()) >= 3.0);
        assertTrue(CenterTheme.contrast(T.onAccent(), T.accent()) >= 4.5);
        for (int status : new int[] {T.ok(), T.warn(), T.danger(), T.accent()}) {
            assertTrue(CenterTheme.contrast(status, T.surface()) >= 4.5);
        }
        assertEquals(21.0, CenterTheme.contrast(0x000000, 0xFFFFFF), 0.01);
    }

    @Test public void everyBuiltInMeetsAa() {
        assertEquals(List.of("dark", "light", "high-contrast", "colorblind"), List.copyOf(ThemeStore.builtIns().keySet()));
        for (java.util.Map.Entry<String, CenterTheme> entry : ThemeStore.builtIns().entrySet()) {
            assertEquals(entry.getKey(), List.of(), entry.getValue().aaProblems());
        }
        CenterTheme hc = CenterTheme.HIGH_CONTRAST;
        assertTrue(CenterTheme.contrast(hc.text(), hc.surface()) >= 7.0);
        assertFalse(new CenterTheme(0, 0, 0, 0, 0x333333, 0, 0, 0, 0, 0, 0, 0).aaProblems().isEmpty());
    }

    // Ostinato: Machado et al. 2009 full-severity matrices on linear RGB.
    private static final double[][] PROTAN = {{0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216},
            {-0.003882, -0.048116, 1.051998}};
    private static final double[][] DEUTAN = {{0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413},
            {-0.011820, 0.042940, 0.968881}};

    private static double[] simulate(int rgb, double[][] m) {
        double[] v = {lin((rgb >> 16) & 0xFF), lin((rgb >> 8) & 0xFF), lin(rgb & 0xFF)};
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(1, m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]));
        }
        return out;
    }

    private static double lin(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double seenApart(int a, int b) {
        double worst = Double.MAX_VALUE;
        for (double[][] m : new double[][][] {PROTAN, DEUTAN}) {
            double[] x = simulate(a, m);
            double[] y = simulate(b, m);
            worst = Math.min(worst, Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2)));
        }
        return worst;
    }

    @Test public void colorblindStatusStaysApartForDeuteranopiaAndProtanopia() {
        CenterTheme cb = CenterTheme.COLORBLIND_SAFE;
        int[] status = {cb.ok(), cb.warn(), cb.danger()};
        for (int i = 0; i < status.length; i++) {
            for (int j = i + 1; j < status.length; j++) {
                assertTrue(seenApart(status[i], status[j]) >= 0.35);
            }
        }
        // Default dark's green and red are the pair this theme exists for.
        assertTrue(seenApart(T.ok(), T.danger()) < 0.35);
    }

    private static BotSnapshot bot(int health, String task, ExecutionState state, ObservationState seen) {
        return new BotSnapshot("Bot", health, 20, task, state, seen, "whisper", null, "local");
    }

    @Test public void statusHasGlyphAndWordNotJustColor() {
        Set<String> glyphs = new HashSet<>();
        for (TileStatus.Level level : TileStatus.Level.values()) {
            glyphs.add(level.glyph);
        }
        assertEquals(TileStatus.Level.values().length, glyphs.size());
        TileStatus failed = TileStatus.of(bot(20, "mine", ExecutionState.FAILED, ObservationState.CONFIRMED));
        assertEquals("✖ failed", failed.badge());
        assertTrue(failed.alert());
        assertEquals("process dead", TileStatus.of(bot(-1, "process dead", null, ObservationState.UNKNOWN)).word());
        assertEquals(TileStatus.Level.WARN, TileStatus.of(bot(4, "", ExecutionState.RUNNING, ObservationState.CONFIRMED)).level());
        assertEquals("running", TileStatus.of(bot(-1, "", ExecutionState.RUNNING, ObservationState.CONFIRMED)).word());
        assertFalse(TileStatus.of(bot(20, "", ExecutionState.SUCCEEDED, ObservationState.CONFIRMED)).alert());
        assertEquals(2, TileStatus.alerts(List.of(
                bot(20, "", ExecutionState.FAILED, ObservationState.CONFIRMED),
                bot(20, "", null, ObservationState.STALE),
                bot(20, "", ExecutionState.RUNNING, ObservationState.CONFIRMED))));
    }
}
