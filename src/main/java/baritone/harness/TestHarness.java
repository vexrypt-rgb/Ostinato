/*
 * Ostinato: settings-gated install point for the in-client test harnesses. New file, replaces no Baritone code.
 */
package baritone.harness;

import baritone.Baritone;

/**
 * Ostinato: installs the in-client test harnesses ({@link baritone.structure.PkTest}, {@link baritone.structure.BastTest})
 * that used to be hooked into {@link Baritone}'s constructor by hand and kept out of commits.
 * <p>
 * Two gates, both needed: the {@code testHarness} setting (or {@code -Dostinato.testHarness=true} for a single launch,
 * so a launch script need not edit settings.txt), and each harness's own launch property, which says what to run
 * ({@code -Dostinato.pktest=<save>}, {@code -Dostinato.basttest=true}). With the setting off nothing is registered, and a
 * harness property given anyway gets one log line saying why it did nothing.
 * <p>
 * Used by the regression suite's live-client stages (docs/REGRESSION.md); headless stages need none of this.
 */
public final class TestHarness {

    private TestHarness() {}

    /** True when the {@code testHarness} setting is on, or {@code -Dostinato.testHarness=true} was passed. */
    public static boolean enabled() {
        return Baritone.settings().testHarness.value || Boolean.getBoolean("ostinato.testHarness");
    }

    /** Harness launch properties that were given, for the "ignored" log line. */
    static String requested() {
        StringBuilder sb = new StringBuilder();
        String pk = System.getProperty("ostinato.pktest");
        if (pk != null && !pk.isEmpty()) sb.append("ostinato.pktest ");
        if (Boolean.getBoolean("ostinato.basttest")) sb.append("ostinato.basttest ");
        return sb.toString().trim();
    }

    public static void install(Baritone b) {
        if (!enabled()) {
            String req = requested();
            if (!req.isEmpty()) {
                System.out.println("[harness] " + req + " ignored: testHarness is off (set testHarness true, or launch with -Dostinato.testHarness=true)");
            }
            return;
        }
        baritone.structure.BastTest.install(b);
        baritone.structure.PkTest.install(b);
    }
}
