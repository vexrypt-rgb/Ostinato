package baritone.threat;

import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.api.threat.ThreatAssessment;
import baritone.api.threat.ThreatAssessment.*;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static baritone.threat.ThreatRules.Kind.*;
import static org.junit.Assert.*;

/** Ostinato (Bombard): a primed TNT as the Threat Assessment sees it. */
public class ThreatRulesTntTest {
    private static final UUID SOURCE = new UUID(0, 1), SUBJECT = new UUID(0, 2);

    private static ThreatRules.Observation tnt(double distance, Sight sight, boolean late, double health) {
        return new ThreatRules.Observation(SOURCE, "minecraft:tnt", SUBJECT, PRIMED_TNT, distance, sight, false, false,
                true, late, health, null, 0, Set.of());
    }

    private static PredictionResult blast(float damage, float exposure) {
        return new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING).horizon(40)
                .explosion(new PredictionResult.Explosion(40, 0, 64.06, 0, 4, List.of(new PredictionResult.Exposure(exposure, damage, false, 0, 0, 0)),
                        null, null, new long[0])).build();
    }

    @Test
    public void tntReachesEightBlocksNotACreepersSix() {
        ThreatAssessment seven = ThreatRules.assess(tnt(7, Sight.CLEAR, false, 20), null, 0);
        assertEquals(4, seven.level());
        assertEquals(Urgency.SOON, seven.urgency());
        assertTrue(seven.uncertainties().contains("TNT_FUSE_OBSERVED_BLAST_POWER_ASSUMED"));
        assertFalse(seven.uncertainties().contains("FUSE_TIME_AND_BLAST_POWER_ASSUMED"));
        assertFalse(seven.uncertainties().contains("UNSUPPORTED_ENTITY_MECHANICS"));
        ThreatAssessment nine = ThreatRules.assess(tnt(9, Sight.CLEAR, false, 20), null, 0);
        assertTrue(nine.reasons().contains("OUTSIDE_ASSUMED_CURRENT_BLAST_RADIUS"));
        assertTrue(nine.level() < 4);
    }

    @Test
    public void aShortFuseIsImmediate() {
        assertEquals(Urgency.IMMEDIATE, ThreatRules.assess(tnt(3, Sight.CLEAR, true, 20), null, 0).urgency());
    }

    @Test
    public void thePredictedBlastPricesItAndCanMakeItLethal() {
        ThreatAssessment lethal = ThreatRules.assess(tnt(3, Sight.CLEAR, true, 6), blast(10, 1), 0);
        assertEquals(5, lethal.level());
        assertEquals(Severity.LETHAL, lethal.severity());
        assertEquals(Exposure.PREDICTED_IMPACT, lethal.exposure());
        ThreatAssessment covered = ThreatRules.assess(tnt(3, Sight.BLOCKED, true, 6), blast(0, 0), 0);
        assertEquals(Exposure.COVERED, covered.exposure());
        assertTrue(covered.level() <= 2);
    }

    @Test
    public void creepersAreUnchanged() {
        var creeper = new ThreatRules.Observation(SOURCE, "minecraft:creeper", SUBJECT, CREEPER, 7, Sight.CLEAR, false, false,
                true, false, 20., null, 0, Set.of());
        ThreatAssessment a = ThreatRules.assess(creeper, null, 0);
        assertTrue(a.reasons().contains("OUTSIDE_ASSUMED_CURRENT_BLAST_RADIUS"));
        assertTrue(a.uncertainties().contains("FUSE_TIME_AND_BLAST_POWER_ASSUMED"));
    }
}
