package baritone.gracenote;

import baritone.api.gracenote.GraceNoteOutcome;
import baritone.api.gracenote.GraceNotePolicy;
import baritone.api.gracenote.IGraceNote.Emergency;
import baritone.api.gracenote.IGraceNote.Mode;
import baritone.api.gracenote.IGraceNote.Phase;
import baritone.api.gracenote.IGraceNote.Result;
import baritone.gracenote.GraceNoteCore.*;
import org.junit.Test;

import java.util.function.IntFunction;

import static org.junit.Assert.*;

/**
 * Grace Note's decisions against a scripted fall: one tick is early (before the executor), late (the press), the
 * player's movement, post. The world is modelled only as far as the core reads it; each test bends one part of it.
 */
public class GraceNoteCoreTest {

    static Forecast fc(int landing, int reach, float damage, boolean lethal) {
        return new Forecast(true, "HIGH", landing, reach, damage, lethal, false, null);
    }

    static Means means() {
        return new Means(3, 0, false, false, true, false, false);
    }

    static Body air() {
        return new Body(true, false, false, null, -1, 5, 20, false);
    }

    static Body ground() {
        return new Body(true, true, false, null, 0, 0, 20, false);
    }

    /** A fall of {@code landing} ticks whose landing comes into reach at {@code reach}. */
    static final class Sim {
        final GraceNoteCore core = new GraceNoteCore();
        long tick = 1000;
        Mode mode = Mode.LIVE;
        int landing = 12, reach = 8, k, threat;
        float damage = 12;
        boolean lethal;
        IntFunction<Forecast> forecast;
        Means means = means();
        // the world's side
        boolean obeys = true, stuckHeld, foreign, rayOn = true, clickTakes = true, serverRejects, pickupWorks = true;
        boolean inWorld = true, worldChanged;
        Hand hand = Hand.WATER_BUCKET;
        boolean water, landed, inWater, alive = true, heldLast;
        boolean supportedInWater = true;
        float health = 20;
        int holdTicks, presses, rejectIn = -1;
        Command last;

        Command step() {
            Body body;
            Forecast f = null;
            if (!alive) body = new Body(false, false, false, "DEAD", 0, 0, 0, false);
            else if (landed) body = new Body(true, !inWater || supportedInWater, inWater, null, 0, 0, health, false);
            else {
                body = new Body(true, false, false, null, -1, k, health, false);
                f = forecast != null ? forecast.apply(k) : fc(landing - k, Math.max(0, reach - k), damage, lethal);
            }
            Command c = core.early(tick, mode, inWorld, worldChanged, body, f, means, heldLast, threat, null);
            boolean heldNow = stuckHeld || c.hold() && obeys && inWorld;
            if (heldNow) holdTicks++;
            boolean recovering = core.phase() == Phase.RECOVERING;
            boolean handOk = recovering ? hand == Hand.BUCKET : hand == Hand.WATER_BUCKET;
            boolean ray = rayOn && (recovering ? water : !landed && k >= reach);
            if (core.late(tick, new Late(heldNow, foreign, handOk, ray, false, false))) {
                presses++;
                if (recovering) {
                    if (pickupWorks) {
                        water = false;
                        hand = Hand.WATER_BUCKET;
                    }
                } else if (clickTakes) {
                    hand = Hand.BUCKET;
                    water = true;
                    if (serverRejects) rejectIn = 3;
                }
            }
            if (alive && !landed) {
                k++;
                if (landing - k <= 0) {
                    landed = true;
                    if (water) inWater = true;
                    else {
                        health -= damage;
                        if (lethal || health <= 0) {
                            alive = false;
                            health = 0;
                        }
                    }
                }
            }
            if (rejectIn > 0 && --rejectIn == 0) {
                water = false;
                hand = Hand.WATER_BUCKET;
            }
            if (!water) inWater = false;
            core.post(tick, new Post(alive, landed && !inWater && alive, inWater, landed ? 0 : k, health, hand, water));
            heldLast = heldNow;
            tick++;
            last = c;
            return c;
        }

        /** Ticks until the next outcome, or null after {@code max}. */
        GraceNoteOutcome run(int max) {
            long seq = core.lastOutcome() == null ? 0 : core.lastOutcome().sequence();
            for (int i = 0; i < max; i++) {
                step();
                GraceNoteOutcome o = core.lastOutcome();
                if (o != null && o.sequence() > seq) return o;
            }
            return null;
        }

        /** A new fall from the ground: reset the body, keep the core (and its guard). */
        void refall() {
            k = 0;
            landed = inWater = water = false;
            alive = true;
            health = 20;
            hand = Hand.WATER_BUCKET;
            // a grounded tick ends the airborne span
            core.early(tick, mode, true, false, ground(), null, means, false, 0, null);
            core.post(tick, new Post(true, true, false, 0, 20, hand, false));
            tick++;
        }
    }

    // ---- the window ----

    @Test
    public void windowStartsAtReachAndEndsTheTickBeforeLanding() {
        GraceNotePolicy p = GraceNotePolicy.DEFAULT;
        Plan plan = GraceNoteCore.waterClutch(fc(10, 6, 12, false), means(), p);
        assertEquals(Verdict.FEASIBLE, plan.verdict());
        assertEquals(6, plan.clickTick());
        assertEquals(3, plan.margin());
        Plan slow = GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 8, false, false, true, false, false), p);
        assertEquals(8, slow.clickTick());
        assertEquals(1, slow.margin());
        assertEquals(Verdict.TOO_LATE, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 10, false, false, true, false, false), p).verdict());
        assertEquals(Verdict.NEVER_IN_REACH, GraceNoteCore.waterClutch(fc(10, -1, 12, false), means(), p).verdict());
        Plan lastTick = GraceNoteCore.waterClutch(fc(1, 0, 12, false), means(), p);
        assertEquals(Verdict.FEASIBLE, lastTick.verdict());
        assertEquals(0, lastTick.margin());
        assertEquals(Verdict.TOO_LATE, GraceNoteCore.waterClutch(fc(0, 0, 12, false), means(), p).verdict());
    }

    @Test
    public void verdictsInOrder() {
        GraceNotePolicy p = GraceNotePolicy.DEFAULT;
        assertEquals(Verdict.UNTRUSTED, GraceNoteCore.waterClutch(Forecast.untrusted("UNSEEN_CELLS"), means(), p).verdict());
        assertEquals(Verdict.UNTRUSTED, GraceNoteCore.waterClutch(null, means(), p).verdict());
        assertEquals(Verdict.NO_LANDING, GraceNoteCore.waterClutch(fc(-1, -1, 0, false), means(), p).verdict());
        assertEquals(Verdict.HAZARD_UNSUPPORTED, GraceNoteCore.waterClutch(new Forecast(true, "HIGH", 10, 6, 30, true, false, "LAVA"), means(), p).verdict());
        assertEquals(Verdict.LANDS_IN_FLUID, GraceNoteCore.waterClutch(new Forecast(true, "HIGH", 10, 6, 0, false, true, null), means(), p).verdict());
        assertEquals(Verdict.NOT_DANGEROUS, GraceNoteCore.waterClutch(fc(10, 6, 3, false), means(), p).verdict());
        assertEquals(Verdict.HAZARD_UNSUPPORTED, GraceNoteCore.waterClutch(new Forecast(true, "HIGH", 10, 6, 0, false, false, "FIRE"), means(), p).verdict());
        GraceNotePolicy deny = new GraceNotePolicy(false, 4, true, true, 10, 4, "t");
        assertEquals(Verdict.POLICY_DENIES, GraceNoteCore.waterClutch(fc(10, 6, 12, false), means(), deny).verdict());
        Means paused = new Means(3, 0, false, false, true, true, false);
        assertEquals(Verdict.INTERACTION_PAUSED, GraceNoteCore.waterClutch(fc(10, 6, 12, false), paused, p).verdict());
        assertEquals("a lethal landing overrides an interaction pause", Verdict.FEASIBLE, GraceNoteCore.waterClutch(fc(10, 6, 30, true), paused, p).verdict());
        assertEquals(Verdict.NO_BUCKET, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(-1, 0, false, false, true, false, false), p).verdict());
        assertEquals(Verdict.EVAPORATES, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 0, false, true, true, false, false), p).verdict());
        assertEquals(Verdict.TARGET_BLOCKED, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 0, false, false, false, false, false), p).verdict());
        assertEquals(Verdict.SCREEN_OPEN, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 0, true, false, true, false, false), p).verdict());
        assertEquals(Verdict.PLANNED_FALL_OWNS, GraceNoteCore.waterClutch(fc(10, 6, 12, false), new Means(3, 0, false, false, true, false, true), p).verdict());
    }

    @Test
    public void policyRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new GraceNotePolicy(true, Float.NaN, true, true, 10, 4, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GraceNotePolicy(true, -1, true, true, 10, 4, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GraceNotePolicy(true, 4, true, true, -1, 4, "t"));
        assertThrows(IllegalArgumentException.class, () -> new GraceNotePolicy(true, 4, true, true, 10, 65, "t"));
        assertEquals("?", new GraceNotePolicy(true, 4, true, true, 10, 4, null).source());
    }

    // ---- the full slice ----

    @Test
    public void liveClutchAvoidsRecoversAndReleases() {
        Sim s = new Sim();
        GraceNoteOutcome o = s.run(100);
        assertNotNull(o);
        assertEquals(Result.AVOIDED, o.result());
        assertEquals(Emergency.FALL, o.emergency());
        assertEquals(0, o.measuredDamage(), 0);
        assertTrue(o.requested() >= 0 && o.started() >= o.requested() && o.executed() >= o.started());
        assertTrue(o.acknowledged() > o.executed());
        assertTrue(o.avoided() >= o.executed() && o.landed() >= o.avoided());
        assertTrue(o.released() > o.landed());
        assertTrue(o.recovered());
        assertEquals("one press to empty, one to pick up", 2, s.presses);
        assertFalse(s.core.holdsControl());
        assertFalse(s.core.wantsHold());
        assertEquals(java.util.List.of(Phase.THREAT_DETECTED, Phase.EVALUATING, Phase.ACQUIRING_CONTROL, Phase.EXECUTING,
                Phase.VERIFYING, Phase.RECOVERING, Phase.RELEASING_CONTROL, Phase.COMPLETED), s.core.trail());
        s.step();
        assertEquals(Phase.MONITORING, s.core.phase());
    }

    @Test
    public void observeNeverHoldsOrPresses() {
        Sim s = new Sim();
        s.mode = Mode.OBSERVE;
        GraceNoteOutcome o = s.run(100);
        assertNotNull(o);
        assertEquals(Result.OBSERVED, o.result());
        assertEquals(-1, o.requested());
        assertEquals(12, o.measuredDamage(), 1e-4);
        assertEquals(0, s.holdTicks);
        assertEquals(0, s.presses);
        assertTrue(o.reason().startsWith("OBSERVE_ONLY_FEASIBLE"));
    }

    @Test
    public void offModeDoesNothing() {
        Sim s = new Sim();
        s.mode = Mode.OFF;
        assertNull(s.run(60));
        assertEquals(0, s.core.counters.emergencies);
        assertEquals(0, s.holdTicks);
    }

    @Test
    public void harmlessOrUntrustedFallsStartNothing() {
        Sim s = new Sim();
        s.damage = 3;
        assertNull(s.run(40));
        assertEquals(0, s.core.counters.emergencies);
        Sim u = new Sim();
        u.forecast = k -> Forecast.untrusted("UNSEEN_CELLS");
        assertNull(u.run(40));
        assertEquals("unseen cells never start a maneuver", 0, u.holdTicks);
    }

    @Test
    public void noBucketObservesInLiveMode() {
        Sim s = new Sim();
        s.means = new Means(-1, 0, false, false, true, false, false);
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.OBSERVED, o.result());
        assertEquals("NO_BUCKET", o.reason());
        assertEquals(0, s.holdTicks);
    }

    @Test
    public void oneEpisodePerAirborneSpan() {
        Sim s = new Sim();
        s.mode = Mode.OBSERVE;
        s.landing = 1000; // a fall longer than an episode
        s.forecast = k -> fc(30, 25, 12, false);
        for (int i = 0; i < 450; i++) s.step();
        assertEquals("the episode times out, the same fall is not taken up again", 1, s.core.counters.emergencies);
        s.refall();
        for (int i = 0; i < 3; i++) s.step();
        assertEquals(2, s.core.counters.emergencies);
    }

    // ---- control ----

    @Test
    public void holdNotObservedFailsControlWithoutPressing() {
        Sim s = new Sim();
        s.obeys = false;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.FAILED_CONTROL, o.result());
        assertEquals(-1, o.started());
        assertTrue(o.reason().contains("HOLD_NOT_OBSERVED"));
        assertEquals(0, s.presses);
        assertTrue("released only when seen", o.released() >= 0);
    }

    @Test
    public void foreignKeysAreLostControl() {
        Sim s = new Sim();
        for (int i = 0; i < 2; i++) s.step();
        s.foreign = true;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.FAILED_CONTROL, o.result());
        assertTrue(o.started() >= 0);
        assertTrue(o.reason().contains("CONTROL_LOST"));
        assertEquals(0, s.presses);
    }

    @Test
    public void releaseNotObservedIsReportedNotAssumed() {
        Sim s = new Sim();
        s.stuckHeld = true;
        GraceNoteOutcome o = s.run(100);
        assertNotNull(o);
        assertEquals(-1, o.released());
        assertTrue(o.reason().contains("RELEASE_NOT_OBSERVED"));
        assertFalse(s.core.holdsControl());
        assertEquals(Result.FAILED_CONTROL, o.result());
    }

    @Test
    public void forecastBecomingUntrustedCannotAuthorizeAUse() {
        Sim s = new Sim();
        s.forecast = k -> k == 0 ? fc(12, 8, 12, false) : Forecast.untrusted("STALE_SNAPSHOT");
        assertEquals(Result.FAILED_WINDOW, s.run(100).result());
        assertEquals(0, s.presses);
    }

    @Test
    public void changedFeasibilityCannotAuthorizeAUse() {
        Sim s = new Sim();
        s.step();
        s.means = new Means(3, 0, false, true, true, false, false);
        assertEquals(Result.FAILED_WINDOW, s.run(100).result());
        assertEquals("water now evaporates", 0, s.presses);
    }

    @Test
    public void lateCallCannotReusePreviousTickAuthorization() {
        GraceNoteCore core = new GraceNoteCore();
        core.early(10, Mode.LIVE, true, false, air(), fc(12, 0, 12, false), means(), false, 0, null);
        assertFalse(core.late(11, new Late(true, false, true, true, false, false)));
    }

    @Test
    public void exemptBodyCannotStartFromAnOldForecast() {
        GraceNoteCore core = new GraceNoteCore();
        Body flying = new Body(true, false, false, "FLYING", -1, 5, 20, false);
        assertFalse(core.early(1, Mode.LIVE, true, false, flying, fc(12, 0, 12, false), means(), false, 0, null).hold());
        assertEquals(0, core.counters.emergencies);
    }

    @Test
    public void lateBucketObservationCannotUndoRelease() {
        GraceNoteCore core = new GraceNoteCore();
        core.early(10, Mode.LIVE, true, false, air(), fc(12, 0, 12, false), means(), false, 0, null);
        assertTrue(core.late(10, new Late(true, false, true, true, false, false)));
        core.early(11, Mode.OFF, true, false, air(), null, null, true, 0, null);
        core.post(11, new Post(true, false, false, 5, 20, Hand.BUCKET, true));
        assertEquals(Phase.RELEASING_CONTROL, core.phase());
        core.early(12, Mode.OFF, true, false, air(), null, null, false, 0, null);
        assertFalse(core.holdsControl());
    }

    @Test
    public void lossOfControlAfterClickIsStillFailure() {
        Sim s = new Sim();
        for (int i = 0; i < 10; i++) s.step();
        assertEquals(Hand.BUCKET, s.hand);
        s.foreign = true;
        assertEquals(Result.FAILED_CONTROL, s.run(100).result());
    }

    @Test
    public void doesNotPickUpWaterWithoutSolidSupport() {
        Sim s = new Sim();
        s.supportedInWater = false;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.AVOIDED, o.result());
        assertFalse(o.recovered());
        assertEquals(1, s.presses);
    }

    @Test
    public void modeSwitchMidManeuverStandsDown() {
        Sim s = new Sim();
        for (int i = 0; i < 3; i++) s.step();
        assertTrue(s.core.wantsHold());
        s.mode = Mode.OBSERVE;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.ABORTED, o.result());
        assertTrue(o.reason().contains("MODE_CHANGED"));
        assertEquals(0, s.presses);
    }

    // ---- execution ----

    @Test
    public void droppedPressIsRetriedThenGivenUp() {
        Sim s = new Sim();
        s.clickTakes = false;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.FAILED_EXECUTION, o.result());
        assertEquals(GraceNoteCore.CLICK_ATTEMPTS, s.presses);
        assertTrue(o.reason().contains("CLICK_NOT_EXECUTED"));
        assertEquals(12, o.measuredDamage(), 1e-4);
    }

    @Test
    public void serverUndoingTheWaterIsRejected() {
        Sim s = new Sim();
        s.serverRejects = true;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.REJECTED, o.result());
        assertTrue(o.executed() >= 0);
        assertFalse("nothing to recover", o.recovered());
    }

    @Test
    public void aimNeverOnTargetMissesTheWindow() {
        Sim s = new Sim();
        s.rayOn = false;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.FAILED_WINDOW, o.result());
        assertEquals(0, s.presses);
        assertEquals(12, o.measuredDamage(), 1e-4);
    }

    @Test
    public void slowLookIsReplannedAsTooLate() {
        Sim s = new Sim();
        s.means = new Means(3, 5, false, false, true, false, false);
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.FAILED_WINDOW, o.result());
        assertTrue(o.reason(), o.reason().contains("WINDOW_MISSED_TOO_LATE"));
    }

    @Test
    public void safeForecastForTwoTicksAborts() {
        Sim s = new Sim();
        s.forecast = k -> fc(12 - k, Math.max(0, 8 - k), k >= 3 ? 0 : 12, false);
        s.damage = 0;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.ABORTED, o.result());
        assertTrue(o.reason().contains("NO_LONGER_DANGEROUS"));
        assertEquals(0, s.presses);
    }

    @Test
    public void oneSafeTickIsNoise() {
        Sim s = new Sim();
        s.forecast = k -> fc(12 - k, Math.max(0, 8 - k), k == 3 ? 0 : 12, false);
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.AVOIDED, o.result());
    }

    @Test
    public void recoveryThatNeverTakesTimesOutAndReleases() {
        Sim s = new Sim();
        s.pickupWorks = false;
        GraceNoteOutcome o = s.run(150);
        assertEquals(Result.AVOIDED, o.result());
        assertFalse(o.recovered());
        assertTrue(o.reason().contains("RECOVERY_TIMEOUT"));
        assertTrue(o.released() >= 0);
        assertEquals(1 + GraceNoteCore.CLICK_ATTEMPTS, s.presses);
    }

    @Test
    public void noRecoveryWhenPolicySaysNo() {
        Sim s = new Sim();
        s.core.setPolicy(new GraceNotePolicy(true, 4, false, true, 10, 4, "t"));
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.AVOIDED, o.result());
        assertFalse(o.recovered());
        assertEquals(1, s.presses);
    }

    // ---- the world going away ----

    @Test
    public void deathMidEpisodeIsDied() {
        Sim s = new Sim();
        s.lethal = true;
        s.damage = 30;
        s.rayOn = false;
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.DIED, o.result());
        assertFalse(o.alive());
        assertFalse(s.core.holdsControl());
    }

    @Test
    public void worldLostMidEpisodeIsInterruptedAndReleased() {
        Sim s = new Sim();
        for (int i = 0; i < 3; i++) s.step();
        assertTrue(s.core.holdsControl());
        s.inWorld = false;
        GraceNoteOutcome o = s.run(10);
        assertNotNull(o);
        assertEquals(Result.INTERRUPTED, o.result());
        assertTrue(o.reason().contains("NO_WORLD"));
        assertFalse(s.core.holdsControl());
    }

    @Test
    public void dimensionChangeIsInterrupted() {
        Sim s = new Sim();
        for (int i = 0; i < 3; i++) s.step();
        s.worldChanged = true;
        GraceNoteOutcome o = s.run(10);
        assertEquals(Result.INTERRUPTED, o.result());
        assertTrue(o.reason().contains("WORLD_CHANGED"));
    }

    @Test
    public void neverStuckEvenWhenNothingResolves() {
        Sim s = new Sim();
        s.landing = 10_000;
        s.forecast = k -> fc(30, 25, 12, false);
        s.rayOn = false;
        GraceNoteOutcome o = s.run(GraceNoteCore.EPISODE_TICKS + GraceNoteCore.RELEASE_TICKS + 5);
        assertNotNull("an episode always ends", o);
        assertTrue(o.reason().contains("EPISODE_TIMEOUT"));
        assertEquals(Result.FAILED_WINDOW, o.result());
        assertFalse(s.core.holdsControl());
    }

    // ---- anti-thrash ----

    @Test
    public void guardWindowAndCooldown() {
        GraceNoteCore.Guard g = new GraceNoteCore.Guard();
        GraceNotePolicy p = new GraceNotePolicy(true, 4, true, true, 50, 2, "t");
        assertNull(g.check(1000, false, p));
        g.took(1000);
        g.ended(1010);
        assertEquals("COOLDOWN", g.check(1030, false, p));
        assertNull("a lethal landing is not held back by the cooldown", g.check(1030, true, p));
        g.took(1030);
        assertEquals("THRASH_WINDOW", g.check(1100, true, p));
        assertNull(g.check(1000 + GraceNotePolicy.WINDOW_TICKS, true, p));
    }

    @Test
    public void thrashWindowBlocksBackToBackTakeovers() {
        Sim s = new Sim();
        s.core.setPolicy(new GraceNotePolicy(true, 4, false, true, 0, 1, "t"));
        assertEquals(Result.AVOIDED, s.run(100).result());
        s.refall();
        GraceNoteOutcome o = s.run(100);
        assertEquals(Result.OBSERVED, o.result());
        assertTrue(o.reason().contains("THRASH_WINDOW"));
        assertEquals(1, s.core.counters.thrashBlocked);
    }

    // ---- what is not a fall ----

    @Test
    public void plannedFallIsLeftToTheExecutor() {
        Sim s = new Sim();
        s.means = new Means(3, 0, false, false, true, false, true);
        GraceNoteOutcome o = s.run(100);
        assertEquals(Emergency.PLANNED_FALL, o.emergency());
        assertEquals(Result.OBSERVED, o.result());
        assertEquals(1, s.core.counters.deferred);
        assertEquals(0, s.holdTicks);
    }

    @Test
    public void hazardLandingIsReportedNotActedOn() {
        Sim s = new Sim();
        s.damage = 0;
        s.forecast = k -> new Forecast(true, "HIGH", 12 - k, Math.max(0, 8 - k), 0, false, false, "FIRE");
        GraceNoteOutcome o = s.run(100);
        assertEquals(Emergency.HAZARD_LANDING, o.emergency());
        assertEquals("HAZARD_UNSUPPORTED", o.reason());
        assertEquals(0, s.holdTicks);
    }

    @Test
    public void entityThreatIsContextOnly() {
        GraceNoteCore core = new GraceNoteCore();
        Command c = core.early(1, Mode.LIVE, true, false, ground(), null, means(), false, 5, "ENTITY_THREAT_REPORTED_ONLY_LEVEL_5");
        assertFalse(c.hold());
        assertEquals(Phase.MONITORING, core.phase());
        assertEquals(Emergency.ENTITY, core.status().emergency());
        assertEquals(0, core.counters.emergencies);
    }

    @Test
    public void missingThreatDataDoesNotSuppressAVerifiedFall() {
        for (int threat : new int[]{0, 5}) {
            Sim s = new Sim();
            s.threat = threat;
            s.step();
            assertTrue("threat level " + threat, s.last.hold());
        }
    }

    @Test
    public void knockbackIsTaggedFromRecentHurt() {
        GraceNoteCore core = new GraceNoteCore();
        Body hurt = new Body(true, false, false, null, -1, 5, 20, true);
        core.early(1, Mode.OBSERVE, true, false, hurt, fc(12, 8, 12, false), means(), false, 0, null);
        assertEquals(Emergency.KNOCKBACK_FALL, core.status().emergency());
    }
}
