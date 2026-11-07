package baritone.threat;

import baritone.api.threat.ThreatAssessment;
import baritone.api.threat.ThreatAssessment.*;
import baritone.api.physics.PredictionResult;
import java.util.*;

/** Pure deterministic rules. Numerical thresholds are policy categories, never probabilities. */
public final class ThreatRules {
    private ThreatRules() {}
    /** PRIMED_TNT: Ostinato (Bombard) hook; its fuse is synced and observed, its power is not (vanilla 4 assumed). */
    public enum Kind { MELEE, RANGED, CREEPER, PROJECTILE, PLAYER, UNSUPPORTED, NONE, PRIMED_TNT }
    public record Observation(UUID source, String type, UUID subject, Kind kind, double distance,
            Sight sight, boolean inReach, boolean aggression, boolean activeFuse, boolean lateFuse,
            Double health, Double damageEstimate, long age, Set<String> flags) {
        public Observation { flags = Set.copyOf(flags); }
    }

    public static ThreatAssessment assess(Observation o, PredictionResult p, int predictionAge) {
        List<String> reasons = new ArrayList<>();
        Set<String> flags = new TreeSet<>(o.flags());
        Exposure exposure = o.sight() == Sight.BLOCKED ? Exposure.COVERED : Exposure.POSSIBLE;
        Urgency urgency = Urgency.POTENTIAL;
        Confidence confidence = Confidence.LIMITED;
        Double damage = o.damageEstimate(), contact = null;
        boolean impact = false;
        int level = o.kind() == Kind.NONE ? 0 : 1;
        if (o.kind() != Kind.NONE) {
            reasons.add("CAPABILITY_" + o.kind());
            flags.add("SERVER_INTENT_AND_COOLDOWN_UNKNOWN");
            if (o.sight() != Sight.BLOCKED && o.kind() != Kind.PLAYER) level = 2;
            if (o.sight() == Sight.BLOCKED) reasons.add("DIRECT_SIGHT_BLOCKED_NOT_PROOF_OF_NO_PATH");
            if (o.inReach() && o.sight() == Sight.CLEAR && o.kind() == Kind.MELEE) {
                exposure = Exposure.IN_REACH;
                urgency = o.aggression() ? Urgency.IMMEDIATE : Urgency.SOON;
                level = o.aggression() ? 4 : 3;
                contact = 0.0; // geometric contact, NOT next attack time
                reasons.add("IN_MELEE_GEOMETRY");
            }
            if (o.aggression()) reasons.add("OBSERVED_AGGRESSION");
            if (o.kind() == Kind.PLAYER && !o.aggression()) reasons.add("PLAYER_INTENT_UNKNOWN");
            if (o.kind() == Kind.PLAYER && o.aggression() && o.sight() == Sight.CLEAR) level = 3;
            if (o.activeFuse()) {
                reasons.add("ACTIVE_FUSE");
                flags.add(o.kind() == Kind.PRIMED_TNT ? "TNT_FUSE_OBSERVED_BLAST_POWER_ASSUMED" : "FUSE_TIME_AND_BLAST_POWER_ASSUMED");
                // damage reaches twice the power: creeper 3, powered 6, TNT 4 (26.3 ServerExplosion)
                double radius = o.kind() == Kind.PRIMED_TNT ? 8 : flags.contains("POWERED_CREEPER") ? 12 : 6;
                if (o.distance() <= radius) {
                    urgency = o.lateFuse() ? Urgency.IMMEDIATE : Urgency.SOON;
                    level = Math.max(level, o.sight() == Sight.BLOCKED ? 2 : 4);
                } else reasons.add("OUTSIDE_ASSUMED_CURRENT_BLAST_RADIUS");
            }
        }
        if (p != null) {
            for (PredictionResult.Assumption a : p.assumptionSet()) flags.add("PPE_" + a);
            flags.add("PPE_HORIZON_" + p.horizonTicks + "_AGE_" + predictionAge);
            flags.add("PPE_TERRAIN_AGE_" + p.snapshotAgeTicks);
            boolean usable = p.status == PredictionResult.Status.OK && predictionAge <= 2
                    && p.snapshotAgeTicks <= 20 && !p.has(PredictionResult.Assumption.STALE_SNAPSHOT)
                    && !p.has(PredictionResult.Assumption.UNLOADED_CHUNK)
                    && (p.hit == null || p.hit.tick >= predictionAge);
            if (!usable) flags.add("PPE_UNAVAILABLE_" + p.status + "_" + p.failure);
            else if (p.hit != null && p.hit.targetIndex == 0) {
                impact = true;
                contact = (double) Math.max(0, p.hit.tick - predictionAge);
                urgency = contact <= 10 ? Urgency.IMMEDIATE : Urgency.SOON;
                exposure = Exposure.PREDICTED_IMPACT;
                level = urgency == Urgency.IMMEDIATE ? 4 : 3;
                flags.add("PROJECTILE_DAMAGE_PAYLOAD_UNKNOWN");
                reasons.add("PPE_PROJECTILE_INTERSECTION_FROZEN_SUBJECT");
            } else if (o.activeFuse() && p.explosion != null && !p.explosion.targets.isEmpty()) {
                var blast = p.explosion.targets.get(0);
                damage = (double) blast.damage;
                if (blast.exposure > 0 && blast.damage > 0) {
                    impact = true;
                    exposure = Exposure.PREDICTED_IMPACT;
                    level = Math.max(level, blast.damage <= 2 ? 3 : urgency == Urgency.IMMEDIATE ? 4 : 3);
                    reasons.add("PPE_CONDITIONAL_BLAST_AT_CURRENT_POSITION");
                } else {
                    exposure = Exposure.COVERED;
                    level = Math.min(level, 2);
                    reasons.add("PPE_BLAST_COVER");
                }
            } else if (o.kind() == Kind.PROJECTILE) {
                level = 1;
                urgency = Urgency.POTENTIAL;
                reasons.add("NO_INTERSECTION_IN_PREDICTION_HORIZON");
            }
        }
        Severity severity = damage == null ? Severity.UNKNOWN : damage <= 2 ? Severity.MINOR : Severity.SERIOUS;
        if (severity == Severity.MINOR && level == 4 && !o.activeFuse()) level = 3;
        if (o.health() == null) flags.add("SUBJECT_HEALTH_UNKNOWN");
        else if (damage != null && damage > 0 && damage >= o.health()) {
            severity = Severity.LETHAL;
            if (urgency == Urgency.IMMEDIATE && (impact || o.aggression() && exposure == Exposure.IN_REACH)) {
                level = 5;
                reasons.add("CONDITIONAL_IMMINENT_LETHAL_DAMAGE");
            }
        }
        if (damage == null) flags.add("DAMAGE_UNKNOWN");
        if (o.kind() == Kind.UNSUPPORTED) flags.add("UNSUPPORTED_ENTITY_MECHANICS");
        if (o.age() > 2) {
            flags.add("STALE_OBSERVATION");
            confidence = Confidence.UNKNOWN;
            level = Math.min(level, 3);
        }
        if (flags.contains("SUBJECT_INVULNERABLE_ORDINARY_DAMAGE_SUPPRESSED")) {
            level = Math.min(level, 1);
            flags.add("KNOCKBACK_CONSEQUENCES_UNKNOWN");
        }
        if (reasons.isEmpty()) reasons.add("NO_IDENTIFIED_THREAT_NOT_A_SAFETY_GUARANTEE");
        return new ThreatAssessment(o.source(), o.type(), o.subject(), level, severity, urgency, contact,
                o.distance(), exposure, o.sight(), damage, o.age(), confidence, reasons, flags, "26.3");
    }
    /** Observed ongoing hazards; no invented damage rate or forecast of knockback into terrain. */
    public static ThreatAssessment environment(UUID subject, double health, Set<String> hazards, boolean fireResistant) {
        boolean burning = hazards.contains("IN_LAVA") || hazards.contains("ON_FIRE");
        boolean drowning = hazards.contains("AIR_DEPLETED");
        int level = drowning || burning && !fireResistant ? (health <= 4 ? 4 : 3) : 1;
        return new ThreatAssessment(subject, "environment", subject, level, Severity.UNKNOWN,
                level > 1 ? Urgency.IMMEDIATE : Urgency.POTENTIAL, null, 0, Exposure.IN_REACH, Sight.UNKNOWN,
                null, 0, Confidence.LIMITED, List.copyOf(hazards),
                Set.of("DAMAGE_RATE_AND_ESCAPE_UNKNOWN", "FIRE_RESISTANCE_" + fireResistant), "26.3");
    }
}
