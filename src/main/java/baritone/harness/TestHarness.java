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
        if (Boolean.getBoolean("ostinato.mazetest")) sb.append("ostinato.mazetest ");
        if (Boolean.getBoolean("ostinato.ppetest")) sb.append("ostinato.ppetest ");
        if (Boolean.getBoolean("ostinato.threattest")) sb.append("ostinato.threattest ");
        if (Boolean.getBoolean("ostinato.gracenotetest")) sb.append("ostinato.gracenotetest ");
        if (Boolean.getBoolean("ostinato.prestotest")) sb.append("ostinato.prestotest ");
        if (Boolean.getBoolean("ostinato.legatotest")) sb.append("ostinato.legatotest ");
        if (Boolean.getBoolean("ostinato.cadenzatest")) sb.append("ostinato.cadenzatest ");
        if (Boolean.getBoolean("ostinato.bombardtest")) sb.append("ostinato.bombardtest ");
        if (Boolean.getBoolean("ostinato.tolerancetrace")) sb.append("ostinato.tolerancetrace ");
        if (Boolean.getBoolean("ostinato.interacttest")) sb.append("ostinato.interacttest ");
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
        baritone.maze.MazeTest.install(b);
        baritone.physics.mc.PpeLiveTest.install(b);
        baritone.threat.ThreatLiveTest.install(b);
        baritone.gracenote.GraceNoteLiveTest.install(b);
        baritone.pathing.kinematic.PrestoLiveTest.install(b);
        baritone.pathing.legato.LegatoLiveTest.install(b);
        baritone.pathing.cadenza.CadenzaLiveTest.install(b);
        baritone.bombard.BombardLiveTest.install(b);
        baritone.scherzo.ScherzoSparTest.install(b); // Ostinato: Scherzo LIVE/OBSERVE spar A/B harness
        baritone.contract.ToleranceTraceTest.install(b); // Ostinato 2.0 Phase 04 validation: live PredictionTolerance traces
        InteractLiveTest.install(b); // Ostinato 2.0 Phase 08: navigate -> interact -> verify
        InteractTerrainLiveTest.install(b); // Ostinato 2.0 Phase 10: the same check on natural terrain
    }
}
