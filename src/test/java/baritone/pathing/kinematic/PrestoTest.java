package baritone.pathing.kinematic;

import org.junit.Test;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;

import static org.junit.Assert.*;

/** Presto's reliability monitor, verdicts and landing audit, without a game. */
public class PrestoTest {

    private final Object world = new Object(), player = new Object(), path = new Object();
    private int tick;

    /** One driven tick: what was expected last tick against what happened, then a new expectation. */
    private Presto.Reason step(Presto p, double dy, double dSpeed, boolean groundMiss) {
        p.begin(world, player, path, tick);
        Presto.Reason r = p.observe(tick, 1, 64 + dy, 1, 0.28 + dSpeed, 0, !groundMiss);
        p.expect(tick, 1, 64, 1, 0.28, 0, true);
        tick++;
        return r;
    }

    private void clean(Presto p, int n) {
        for (int i = 0; i < n; i++) assertEquals(Presto.Reason.NONE, step(p, 0, 0, false));
    }

    @Test
    public void exactPredictionStaysTrusted() {
        Presto p = new Presto();
        step(p, 0, 0, false); // nothing expected yet
        clean(p, 200);
        assertTrue(p.trusted());
        assertEquals(200, p.observations);
        assertEquals(0, p.distrusts);
    }

    @Test
    public void smallErrorsWithinTolerance() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        for (int i = 0; i < 100; i++) assertEquals(Presto.Reason.NONE, step(p, 0.04, 0.07, false));
        assertTrue(p.trusted());
        assertEquals(0.04, p.maxVertical, 1e-9);
    }

    @Test
    public void scatteredMissesDoNotDistrust() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        for (int i = 0; i < 10; i++) {
            assertEquals(Presto.Reason.VERTICAL, step(p, 0.2, 0, false));
            clean(p, Presto.WINDOW); // one miss per window never reaches DISTRUST_AT
        }
        assertTrue(p.trusted());
        assertEquals(10, p.divergences[Presto.Reason.VERTICAL.ordinal()]);
    }

    @Test
    public void repeatedMissesDistrustThenRetrust() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        assertEquals(Presto.Reason.SPEED, step(p, 0, 0.2, false));
        assertEquals(Presto.Reason.GROUND, step(p, 0, 0, true));
        assertTrue(p.trusted());
        assertEquals(Presto.Reason.VERTICAL, step(p, 0.3, 0, false));
        assertFalse(p.trusted());
        assertEquals(Presto.Reason.VERTICAL, p.reason());
        assertEquals(Presto.RETRUST, p.retrustIn());
        clean(p, Presto.RETRUST - 1);
        assertFalse(p.trusted());
        clean(p, 1);
        assertTrue(p.trusted());
        assertEquals(1, p.retrusts);
    }

    @Test
    public void aMissResetsTheCleanRun() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        for (int i = 0; i < 3; i++) step(p, 0.3, 0, false);
        assertFalse(p.trusted());
        clean(p, Presto.RETRUST - 5);
        step(p, 0.3, 0, false);
        clean(p, Presto.RETRUST - 1);
        assertFalse(p.trusted());
        clean(p, 1);
        assertTrue(p.trusted());
    }

    @Test
    public void correctionDistrustsAtOnce() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        assertEquals(Presto.Reason.CORRECTION, step(p, 3, 0, false));
        assertFalse(p.trusted());
        assertEquals(1, p.corrections);
        assertEquals(Presto.Reason.CORRECTION, p.reason());
        assertEquals(0, p.maxVertical, 0); // a correction is not a model error
    }

    @Test
    public void lostAgainSoonWaitsLonger() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        step(p, 3, 0, false);
        clean(p, Presto.RETRUST);
        assertTrue(p.trusted());
        step(p, 3, 0, false); // lost again right away
        assertEquals(2 * Presto.RETRUST, p.retrustIn());
        clean(p, 2 * Presto.RETRUST);
        assertTrue(p.trusted());
        for (int i = 0; i < 10; i++) {
            step(p, 3, 0, false);
            clean(p, p.retrustIn());
        }
        step(p, 3, 0, false);
        assertEquals(Presto.MAX_RETRUST, p.retrustIn());
        clean(p, Presto.MAX_RETRUST);
        clean(p, 4 * Presto.MAX_RETRUST); // a long clean stretch: the next loss starts from the base wait again
        step(p, 3, 0, false);
        assertEquals(Presto.RETRUST, p.retrustIn());
    }

    @Test
    public void gapInDrivenTicksDropsTheExpectation() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        tick += 5; // Baritone drove in between
        assertEquals(Presto.Reason.NONE, step(p, 3, 0, false));
        assertEquals(0, p.observations);
        assertTrue(p.trusted());
    }

    @Test
    public void newPathDropsButKeepsTrust() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        step(p, 3, 0, false);
        assertFalse(p.trusted());
        p.begin(world, player, new Object(), tick);
        assertEquals(Presto.Reason.NONE, p.observe(tick, 9, 9, 9, 0, 0, true));
        assertFalse(p.trusted());
        assertEquals(0, p.contextResets);
    }

    @Test
    public void newWorldOrBodyResetsTrust() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        step(p, 3, 0, false);
        assertFalse(p.trusted());
        p.begin(new Object(), player, path, tick);
        assertTrue(p.trusted());
        assertEquals(1, p.contextResets);
        p.begin(world, new Object(), path, tick + 1);
        assertEquals(2, p.contextResets);
    }

    @Test
    public void verdicts() {
        assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(false, true, 0, false, 64, 64));
        assertEquals(Presto.Verdict.NO_LANDING, Presto.judge(true, false, 0, false, 0, 64));
        assertEquals(Presto.Verdict.DAMAGE, Presto.judge(true, true, 2, false, 64, 64));
        assertEquals(Presto.Verdict.DAMAGE, Presto.judge(true, true, 0, true, 64, 64));
        assertEquals(Presto.Verdict.LEVEL, Presto.judge(true, true, 0, false, 63, 64));
        assertEquals(Presto.Verdict.LEVEL, Presto.judge(true, true, 0, false, 65, 64));
        assertEquals(Presto.Verdict.AGREE, Presto.judge(true, true, 0, false, 64.0001, 64));
        assertEquals(Presto.Verdict.AGREE, Presto.judge(true, true, 0, false, 64.5, 64)); // a slab-high landing still counts
        assertFalse(Presto.Verdict.AGREE.veto());
        assertFalse(Presto.Verdict.UNAVAILABLE.veto());
        assertTrue(Presto.Verdict.NO_LANDING.veto() && Presto.Verdict.DAMAGE.veto() && Presto.Verdict.LEVEL.veto());
    }

    @Test
    public void offLineDistance() {
        assertEquals(0, Presto.offLine(0, 0, 4, 0, 2, 0), 1e-9);
        assertEquals(0.5, Presto.offLine(0, 0, 4, 0, 5, -0.5), 1e-9); // past the end: still the side distance
        assertEquals(Math.sqrt(0.5), Presto.offLine(0, 0, 1, 1, 1, 0), 1e-9);
        assertEquals(5, Presto.offLine(1, 1, 1, 1, 4, 5), 1e-9); // no line: distance to the takeoff
    }

    /** Live run 3: a step-up from a standstill, the engine's held yaw landed 2 blocks to the side, in the pit. */
    @Test
    public void engineLandingOffTheLineIsNoOpinion() {
        double side = Presto.offLine(20.700, 0.308, 21.261, 0.649, 22.476, 2.670);
        assertTrue(side > Presto.OFF_LINE);
        assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(true, true, 3, false, 198, 202, side));
        // the same pit landing along the line is a veto: short into the gap
        assertEquals(Presto.Verdict.DAMAGE, Presto.judge(true, true, 3, false, 198, 202, 0.2));
        assertEquals(Presto.Verdict.NO_LANDING, Presto.judge(true, false, 0, false, 0, 202, 9)); // no landing: no side to rule on
        assertEquals(Presto.Verdict.AGREE, Presto.judge(true, true, 0, false, 202, 202, Presto.OFF_LINE));
    }

    @Test
    public void landingAuditMatches() {
        Presto p = new Presto();
        p.arm(100, 11, 64, 11, 64);
        p.landing(101, 64.4, false);
        p.landing(110, 64.1, false);
        assertTrue(p.armed());
        p.landing(112, 64, true);
        assertFalse(p.armed());
        assertEquals(1, p.landings);
        assertEquals(0, p.landingMisses);
        assertEquals(1, p.sumPpeTickErr, 0);
    }

    @Test
    public void landingMissDistrustsAtOnce() {
        Presto p = new Presto();
        p.arm(0, 11, 64, 11, 64);
        p.landing(1, 64, false);
        p.landing(14, 60, true); // both models had it on the far block; it fell short into the pit
        assertEquals(1, p.landingMisses);
        assertFalse(p.trusted());
        assertEquals(Presto.Reason.LANDING, p.reason());
    }

    @Test
    public void stillFallingWhenOverdueIsAMiss() {
        Presto p = new Presto();
        p.arm(0, 11, 64, 12, 64);
        // Still above the expected support (e.g. an unmodelled updraft); no missed-height signal yet.
        for (int t = 1; t <= 12 + Presto.OVERDUE; t++) p.landing(t, 65, false);
        assertTrue(p.armed());
        p.landing(13 + Presto.OVERDUE, 30, false);
        assertFalse(p.armed());
        assertEquals(1, p.overdue);
        assertFalse(p.trusted());
    }

    @Test
    public void auditOutlivesAPathChange() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        p.arm(tick, 11, 64, 11, 64);
        p.begin(world, player, new Object(), tick + 5); // the path was dropped mid-flight
        assertTrue(p.armed());
        p.landing(tick + 6, 64, false);
        p.landing(tick + 12, 64, true);
        assertEquals(1, p.landings);
        p.arm(tick, 11, 64, 11, 64);
        p.begin(new Object(), player, path, tick); // another world: nothing to compare
        assertFalse(p.armed());
    }

    @Test
    public void landingAuditOneModelRightIsNoMiss() {
        Presto p = new Presto();
        p.arm(0, 11, 63, 11, 64);
        p.landing(1, 64, false);
        p.landing(12, 64, true);
        assertEquals(0, p.landingMisses);
        assertEquals(1, p.maxPpeYErr, 1e-9);
    }

    @Test
    public void simOnlyAuditLeavesEngineStatsAlone() {
        Presto p = new Presto();
        p.armSim(0, 11, 64);
        p.landing(1, 64.3, false);
        p.landing(13, 64, true);
        assertEquals(1, p.landings);
        assertEquals(0, p.ppeLandings);
        assertEquals(0, p.sumPpeTickErr, 0);
        assertEquals(2, p.sumSimTickErr, 0);
        p.armSim(20, 11, 64);
        p.landing(21, 64.3, false);
        p.landing(34, 61, true); // a hop that came down in a hole
        assertEquals(1, p.landingMisses);
        assertFalse(p.trusted());
    }

    @Test
    public void jumpThatNeverLeftDisarms() {
        Presto p = new Presto();
        p.arm(0, 11, 64, 11, 64);
        p.landing(1, 64, true);
        assertTrue(p.armed());
        p.landing(3, 64, true);
        assertFalse(p.armed());
        assertEquals(0, p.landings);
    }

    @Test
    public void correctionDisarmsTheAudit() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        p.arm(tick, 11, 64, 11, 64);
        step(p, 3, 0, false);
        assertFalse(p.armed());
    }

    @Test
    public void summaryNamesEverything() {
        Presto p = new Presto();
        p.checked(Presto.Verdict.AGREE, 120_000);
        String s = p.summary();
        assertTrue(s, s.startsWith("PRESTO trusted=true"));
        assertTrue(s, s.contains("AGREE=1") && s.contains("ppeUs avg=120.0") && s.contains("noGap=0 offLine=0"));
    }

    @Test
    public void auditRejectsWorldAndBodyChangesBeforeDrive() {
        for (boolean newWorld : new boolean[]{false, true}) {
            Presto p = new Presto();
            p.begin(world, player, path, 0);
            p.armSim(0, 12, 64);
            p.landing(1, 65, false);
            assertFalse(p.auditContext(newWorld ? new Object() : world, newWorld ? player : new Object(), true));
            p.landing(12, 10, true);
            assertEquals(0, p.landings);
            assertTrue(p.trusted());
        }
    }

    @Test
    public void disabledDeathAndOwnershipPreemptionDiscardPendingEvidence() {
        // All four live guards (disabled, dead, Grace Note, Pvp/Scherzo) feed the same eligibility boundary.
        Presto p = new Presto();
        p.begin(world, player, path, 0);
        p.expect(0, 0, 64, 0, 0, 0, true);
        p.armSim(0, 12, 64);
        assertFalse(p.auditContext(world, player, false));
        assertFalse(p.armed());
        assertEquals(Presto.Reason.NONE, p.observe(1, 10, 0, 0, 0, 0, true));
        assertTrue(p.auditContext(world, player, true));
        assertFalse(p.armed()); // enabling cannot revive the old jump
    }

    @Test
    public void cancellationInvalidatesWithoutClearingDistrust() {
        Presto p = new Presto();
        step(p, 0, 0, false);
        step(p, 3, 0, false);
        p.armSim(tick, 12, 64);
        p.invalidate();
        assertFalse(p.armed());
        assertFalse(p.trusted());
        assertEquals(Presto.Reason.NONE, p.observe(tick, 100, 0, 0, 0, 0, true));
    }

    @Test
    public void explicitSmallCorrectionWorksWithoutConsecutiveDrive() {
        Presto p = new Presto();
        p.armSim(0, 12, 64);
        p.corrected();
        assertFalse(p.armed());
        assertFalse(p.trusted());
        assertEquals(1, p.corrections);
        p.landing(12, 10, true);
        assertEquals(0, p.landingMisses);
    }

    @Test
    public void versionGateRejectsLegacyAndUnauditedReleases() {
        assertTrue(Presto.supportsVersion("26.3"));
        for (String version : new String[]{null, "1.16.1", "1.21.4", "26.3-pre1", "26.4"}) {
            assertFalse(Presto.supportsVersion(version));
        }
    }

    @Test
    public void changedModernAttributesOrPoseDeclineOptimization() {
        assertTrue(Presto.supportsBody(0.6f, 1.8f, 0.6f, 1, 1, 0));
        assertFalse(Presto.supportsBody(0.6f, 1.5f, 0.6f, 1, 1, 0));
        assertFalse(Presto.supportsBody(1.2f, 3.6f, 0.6f, 1, 1, 0));
        assertFalse(Presto.supportsBody(0.6f, 1.8f, 1, 1, 1, 0));
        assertFalse(Presto.supportsBody(0.6f, 1.8f, 0.6f, 0.5, 1, 0));
        assertFalse(Presto.supportsBody(0.6f, 1.8f, 0.6f, 1, 0.5, 0));
        assertFalse(Presto.supportsBody(0.6f, 1.8f, 0.6f, 1, 1, 0.5));
        assertFalse(Presto.supportsBody(Double.NaN, 1.8f, 0.6f, 1, 1, 0));
    }

    @Test
    public void nonFinitePredictionsCannotAgree() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(true, true, 0, false, bad, 64));
            assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(true, true, 0, false, 64, bad));
            assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(true, true, 0, false, 64, 64, bad));
        }
        assertEquals(Presto.Verdict.UNAVAILABLE, Presto.judge(true, true, Float.NaN, false, 64, 64));
    }

    @Test
    public void unsupportedStalePartialAndShortAnswersHaveNoOpinion() {
        PredictionResult ok = new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING).horizon(40).build();
        assertTrue(Presto.usable(ok, 12));
        assertFalse(Presto.usable(ok, 41));
        assertFalse(Presto.usable(ok, -1));
        assertFalse(Presto.usable(ok.withTiming(0, 21, 0), 12));
        for (PredictionResult.Assumption a : PredictionResult.Assumption.values()) {
            if (a.changesOutcome) assertFalse(a.name(), Presto.usable(ok.withTiming(0, 0, a.bit()), 12));
        }
        for (PredictionResult.Status s : PredictionResult.Status.values()) {
            if (s != PredictionResult.Status.OK) assertFalse(Presto.usable(
                    PredictionResult.failed(s, PredictionResult.Failure.UNSUPPORTED, PredictionRequest.Fidelity.SCREENING, 0), 12));
        }
        assertFalse(Presto.usable(null, 12));
    }

    @Test
    public void missedSupportIsDetectedBeforeFallCancellation() {
        Presto p = new Presto();
        p.arm(0, 12, 64, 12, 64);
        p.landing(11, 64.1, false);
        p.landing(12, 63.6, false);
        assertTrue(p.armed());
        p.landing(13, 63.4, false);
        assertFalse(p.trusted());
        assertEquals(1, p.landingMisses);
        assertEquals(0, p.overdue);
        p.invalidate(); // cancellation must not erase the already-observed miss or count it twice
        p.landing(20, 58, true);
        assertEquals(1, p.landingMisses);
    }

    @Test
    public void ascentBelowRaisedLandingIsNotAnEarlyMiss() {
        Presto p = new Presto();
        p.armSim(0, 8, 65);
        p.landing(1, 63.5, false);
        p.landing(8, 64, false); // rising late, still below the target
        assertTrue(p.armed());
        assertTrue(p.trusted());
        p.landing(10, 65, true);
        assertEquals(0, p.landingMisses);
    }
}
