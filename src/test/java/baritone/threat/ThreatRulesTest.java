package baritone.threat;

import baritone.api.physics.*;
import baritone.api.threat.*;
import baritone.api.threat.ThreatAssessment.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.threat.ThreatRules.Kind.*;

public class ThreatRulesTest {
    private static final UUID SOURCE = new UUID(0, 1), SUBJECT = new UUID(0, 2);
    static ThreatRules.Observation obs(ThreatRules.Kind kind, Sight sight, boolean reach, boolean aggression,
                                        boolean fuse, Double health, Double damage, long age) {
        return new ThreatRules.Observation(SOURCE, kind.name(), SUBJECT, kind, reach ? 1 : 16, sight, reach,
                aggression, fuse, fuse, health, damage, age, Set.of());
    }
    static ThreatAssessment run(ThreatRules.Observation o) { return ThreatRules.assess(o, null, 0); }
    static PredictionResult hit(int tick) {
        return new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING).horizon(tick)
                .hit(new PredictionResult.Hit(tick, 0, 0, 0, 0, 0, 0, 0, -1, 1)).build();
    }
    @Test public void solidCoverSuppressesImmediateMelee() {
        var covered = run(obs(MELEE, Sight.BLOCKED, true, true, false, 20., 4., 0));
        var open = run(obs(MELEE, Sight.CLEAR, true, true, false, 20., 4., 0));
        assertTrue(covered.level() < open.level()); assertEquals(Exposure.COVERED, covered.exposure());
    }
    @Test public void openApproachIsPlausibleWithoutInventingAttackTime() {
        var a = run(obs(MELEE, Sight.CLEAR, false, false, false, 20., 4., 0));
        assertEquals(2, a.level()); assertNull(a.contactTicks());
    }
    @Test public void distantRangedDoesNotProveImminentImpact() {
        var a = run(obs(RANGED, Sight.CLEAR, false, false, false, 20., null, 0));
        assertTrue(a.level() < 4); assertNull(a.damage());
    }
    @Test public void projectileCollisionIsUrgentWithoutInventedDamage() {
        var a = ThreatRules.assess(obs(PROJECTILE, Sight.CLEAR, false, false, false, 1., null, 0), hit(3), 1);
        assertEquals(4, a.level()); assertEquals(2., a.contactTicks(), 0); assertNull(a.damage());
    }
    @Test public void projectileMissIsLimitedToHorizon() {
        var p = new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING).horizon(20).build();
        var a = ThreatRules.assess(obs(PROJECTILE, Sight.CLEAR, false, false, false, 20., null, 0), p, 0);
        assertEquals(1, a.level()); assertTrue(a.reasons().contains("NO_INTERSECTION_IN_PREDICTION_HORIZON"));
    }
    @Test public void activeFuseOutranksInactive() {
        assertTrue(run(obs(CREEPER, Sight.CLEAR, true, false, true, 20., null, 0)).level()
                > run(obs(CREEPER, Sight.CLEAR, true, false, false, 20., null, 0)).level());
    }
    @Test public void distantFuseDoesNotMeanImminentExposure() {
        var a = run(obs(CREEPER, Sight.CLEAR, false, false, true, 1., null, 0));
        assertTrue(a.level() < 4); assertEquals(Urgency.POTENTIAL, a.urgency());
    }
    @Test public void ongoingEnvironmentCannotReadAsSafeAndResistanceMatters() {
        var a = ThreatRules.environment(SUBJECT, 2, Set.of("IN_LAVA"), false);
        var b = ThreatRules.environment(SUBJECT, 2, Set.of("IN_LAVA"), true);
        assertEquals(4, a.level()); assertTrue(b.level() < a.level()); assertNull(a.damage());
    }
    @Test public void lowHealthChangesConsequenceOnlyWhenExposed() {
        assertEquals(5, run(obs(MELEE, Sight.CLEAR, true, true, false, 2., 4., 0)).level());
        assertTrue(run(obs(MELEE, Sight.BLOCKED, true, true, false, 2., 4., 0)).level() < 4);
    }
    @Test public void armorReducedDamageLowersSeverity() {
        double armored = baritone.health.DamageMath.afterArmor(4, 20, 8, 0);
        var a = run(obs(MELEE, Sight.CLEAR, true, true, false, 20., armored, 0));
        var b = run(obs(MELEE, Sight.CLEAR, true, true, false, 20., 4., 0));
        assertTrue(a.level() < b.level()); assertEquals(Severity.MINOR, a.severity());
    }
    @Test public void neutralNotAttackingIsNotActiveThreat() {
        assertEquals(0, run(obs(NONE, Sight.CLEAR, true, false, false, 20., null, 0)).level());
    }
    @Test public void unknownPlayerIntentIsNotHostility() {
        assertEquals(1, run(obs(PLAYER, Sight.CLEAR, true, false, false, 1., 100., 0)).level());
    }
    @Test public void simultaneousScoresAreNotAdded() {
        var a = run(obs(MELEE, Sight.CLEAR, true, false, false, 20., 4., 0));
        var snapshot = new ThreatSnapshot(0, 1, SUBJECT, "overworld", List.of(a, a), Set.of(), false, "OBSERVED");
        assertEquals(a.level(), snapshot.highestLevel()); assertTrue(snapshot.highestLevel() < 5);
    }
    @Test public void staleCannotBeHighConfidenceCritical() {
        var a = run(obs(MELEE, Sight.CLEAR, true, true, false, 1., 10., 5));
        assertTrue(a.level() < 4); assertEquals(Confidence.UNKNOWN, a.confidence());
    }
    @Test public void missingHealthAndEquipmentStayUnknown() {
        var a = run(obs(MELEE, Sight.CLEAR, true, true, false, null, null, 0));
        assertEquals(Severity.UNKNOWN, a.severity()); assertTrue(a.uncertainties().contains("SUBJECT_HEALTH_UNKNOWN"));
    }
    @Test public void timeoutDoesNotTurnUnknownIntoSafeMiss() {
        var p = PredictionResult.failed(PredictionResult.Status.EXPIRED, PredictionResult.Failure.DEADLINE, PredictionRequest.Fidelity.SCREENING, 0);
        var a = ThreatRules.assess(obs(PROJECTILE, Sight.CLEAR, false, false, false, 20., null, 0), p, 0);
        assertEquals(2, a.level()); assertTrue(a.uncertainties().contains("PPE_UNAVAILABLE_EXPIRED_DEADLINE"));
    }
    @Test public void partialOrUnloadedPredictionDoesNotProveMiss() {
        var p = new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING).partial(PredictionResult.Failure.OUT_OF_SNAPSHOT).build();
        assertEquals(2, ThreatRules.assess(obs(PROJECTILE, Sight.CLEAR, false, false, false, 20., null, 0), p, 0).level());
    }
    @Test public void unsupportedMechanicsExplicit() {
        assertTrue(run(obs(UNSUPPORTED, Sight.CLEAR, false, false, false, 20., null, 0)).uncertainties().contains("UNSUPPORTED_ENTITY_MECHANICS"));
    }
    @Test public void expiredImpactIsNotRepublishedAsFuture() {
        assertEquals(2, ThreatRules.assess(obs(PROJECTILE, Sight.CLEAR, false, false, false, 20., null, 0), hit(0), 2).level());
    }
    @Test public void resultsDefensivelyCopyCollections() {
        var a = run(obs(MELEE, Sight.CLEAR, true, true, false, 20., 4., 0));
        try { a.reasons().add("changed"); fail(); } catch (UnsupportedOperationException expected) {}
    }
    @Test public void worldDimensionAndChunkChangesRejectOldGeneration() {
        for (String reason : List.of("WORLD_CHANGED", "DIMENSION_CHANGED", "CHUNK_CHANGED", "SUBJECT_CHANGED")) {
            var p = new ThreatPublication(); p.invalidate(3, reason);
            assertFalse(p.publish(new ThreatSnapshot(0, 4, SUBJECT, "old", List.of(), Set.of(), false, "OBSERVED")));
            assertEquals(reason, p.latest().status());
        }
    }
    @Test public void disappearanceReplacesInsteadOfMergingOldSources() {
        var p = new ThreatPublication();
        p.publish(new ThreatSnapshot(0, 1, SUBJECT, "world", List.of(run(obs(MELEE, Sight.CLEAR, true, true, false, 20., 4., 0))), Set.of(), false, "OBSERVED"));
        p.publish(new ThreatSnapshot(0, 2, SUBJECT, "world", List.of(), Set.of(), false, "OBSERVED"));
        assertTrue(p.latest().sources().isEmpty());
        assertFalse(p.publish(new ThreatSnapshot(0, 1, SUBJECT, "world", List.of(), Set.of(), false, "OBSERVED")));
    }
    @Test public void suddenCriticalNeverWaitsForHysteresis() {
        var p = new ThreatPublication();
        p.publish(new ThreatSnapshot(0, 1, SUBJECT, "world", List.of(), Set.of(), false, "OBSERVED"));
        p.publish(new ThreatSnapshot(0, 2, SUBJECT, "world", List.of(run(obs(MELEE, Sight.CLEAR, true, true, false, 1., 4., 0))), Set.of(), false, "OBSERVED"));
        assertEquals(5, p.latest().highestLevel());
    }
    @Test public void terrainAgeIsExplicitAndEngineAgeLimitIsRespected() {
        var o = obs(PROJECTILE, Sight.CLEAR, false, false, false, 20., null, 0);
        var valid = ThreatRules.assess(o, hit(3).withTiming(0, 10, 0), 0);
        assertEquals(4, valid.level()); assertTrue(valid.uncertainties().contains("PPE_TERRAIN_AGE_10"));
        assertEquals(2, ThreatRules.assess(o, hit(3).withTiming(0, 21, 0), 0).level());
        assertEquals(2, ThreatRules.assess(o, hit(3).withTiming(0, 0, PredictionResult.Assumption.STALE_SNAPSHOT.bit()), 0).level());
    }
}
