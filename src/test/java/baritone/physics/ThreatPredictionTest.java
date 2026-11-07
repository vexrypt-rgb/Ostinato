package baritone.physics;

import baritone.api.physics.*;
import baritone.api.threat.ThreatAssessment;
import baritone.threat.ThreatRules;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Real PPE over simulated terrain, not fabricated collision answers. */
public class ThreatPredictionTest {
    private static final BodyState TARGET = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).health(4, 0).build();
    private static ThreatRules.Observation observation(ThreatRules.Kind kind, boolean fuse) {
        return new ThreatRules.Observation(new UUID(0,1), kind.name(), new UUID(0,2), kind, 3,
                ThreatAssessment.Sight.CLEAR, false, false, fuse, fuse, 4., null, 0, Set.of());
    }
    private static PredictionResult run(PredictionRequest r, Terrain t) {
        return new Predictor().run(r, t, r.fidelity, () -> false);
    }
    @Test public void projectileTowardSubjectIsHigherThanAway() {
        BodyState arrow = BodyState.builder(BodyState.Type.ARROW).pos(-4, 65, 0.5).vel(1, 0, 0).build();
        var toward = run(PredictionRequest.projectile(arrow, 12).targets(List.of(TARGET)).build(), PpeWorlds.flat(PpeWorlds.STONE));
        var away = run(PredictionRequest.projectile(arrow.toBuilder().vel(-1, 0, 0).build(), 12).targets(List.of(TARGET)).build(), PpeWorlds.flat(PpeWorlds.STONE));
        assertNotNull(toward.hit); assertEquals(0, toward.hit.targetIndex); assertTrue(away.hit == null || away.hit.targetIndex == -1);
        assertTrue(ThreatRules.assess(observation(ThreatRules.Kind.PROJECTILE, false), toward, 0).level()
                > ThreatRules.assess(observation(ThreatRules.Kind.PROJECTILE, false), away, 0).level());
    }
    @Test public void blastCoverPreventsLethalPromotion() {
        var request = PredictionRequest.explosion(-3, 64, 0.5, 3, false).targets(List.of(TARGET)).build();
        var open = run(request, PpeWorlds.flat(PpeWorlds.STONE));
        var wall = run(request, PpeWorlds.flat((x,y,z) -> x == -1 && y < 70 ? PpeWorlds.OBSIDIAN : null));
        assertTrue(open.explosion.targets.get(0).damage > wall.explosion.targets.get(0).damage);
        var exposed = ThreatRules.assess(observation(ThreatRules.Kind.CREEPER, true), open, 0);
        var covered = ThreatRules.assess(observation(ThreatRules.Kind.CREEPER, true), wall, 0);
        assertEquals(5, exposed.level()); assertTrue(covered.level() < 4);
        assertNotEquals(ThreatAssessment.Confidence.OBSERVED, exposed.confidence());
    }
}
