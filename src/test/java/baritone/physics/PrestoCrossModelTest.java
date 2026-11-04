/*
 * Ostinato: new file (Presto). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionResult;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.Presto;
import org.junit.Test;

import java.util.List;

import static baritone.physics.PpeWorlds.*;
import static org.junit.Assert.*;

/**
 * Ostinato Presto: the takeoff check's two models on the same terrain. A sprint run-up is driven in PlayerSim (the
 * kinematic controller's model) to the gap's edge; that takeoff state is handed to the engine as the controller hands
 * it the captured player, and both fly the jump with W, sprint and jump held. Their first landings are compared, and
 * Presto's verdict on them. Plain stone and air only: what both models claim to get exactly right.
 */
public class PrestoCrossModelTest {

    /** Run-up floor z <= 0 (blocks below y=64), a gap of {@code gap} blocks, the landing after it, a pit floor at y=58. */
    private static Cells course(int gap, int ceilingY, int ceilingFromZ) {
        return (x, y, z) -> {
            if (ceilingY > 0 && y == ceilingY && z >= ceilingFromZ) return STONE;
            if (y < 58) return STONE;
            if (y < 64 && (z <= 0 || z > gap)) return STONE;
            return BlockInfo.AIR_INFO;
        };
    }

    private static PlayerSim.World sim(Cells c) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            if (c.at(bx, by, bz) == STONE) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private record Landing(int tick, double y) {}

    private record Outcome(Landing sim, PredictionResult ppe, Presto.Verdict verdict) {}

    /** Sprint along +z from z=-20 to the last ground tick before the edge, then jump in both models. */
    private static Outcome fly(Cells c, double takeoffZ) {
        PlayerSim s = new PlayerSim(sim(c));
        s.x = 0.5;
        s.y = 64;
        s.z = -20.5;
        s.onGround = true;
        while (s.z + s.vz * 1.0 < takeoffZ) s.tick(0, true, true, false);
        assertTrue("on the run-up at takeoff", s.onGround && s.y == 64);
        BodyState body = BodyState.builder(BodyState.Type.PLAYER).pos(s.x, s.y, s.z).vel(s.vx, s.vy, s.vz).onGround(true)
                .rot(0, 0).flags(s.sprinting ? BodyState.SPRINTING : 0).speed(s.sprinting ? 0.13f : 0.1f).build();
        PredictionRequest r = PredictionRequest.trajectory(body, 40).input(new PredictionRequest.Input(1, 0, true, true, false, 0, Float.NaN))
                .fidelity(Fidelity.SCREENING).build();
        Terrain t = build(-2, 1, 2, 6, -2, 1, c);
        PredictionResult ppe = new Predictor().run(r, t, r.fidelity, () -> false);
        Landing land = null;
        boolean air = false;
        for (int k = 1; k <= 40 && land == null; k++) {
            s.tick(0, true, true, true);
            if (!s.onGround) air = true;
            else if (air) land = new Landing(k, s.y);
        }
        boolean usable = ppe.status == PredictionResult.Status.OK
                && (ppe.confidence == PredictionResult.Confidence.EXACT || ppe.confidence == PredictionResult.Confidence.BOUNDED);
        PredictionResult.Landing l = ppe.landing;
        Presto.Verdict v = land == null ? null
                : Presto.judge(usable, l != null, l == null ? 0 : l.damage, l != null && l.lethal, l == null ? 0 : l.y, land.y());
        System.out.println("PRESTO-XMODEL takeoffZ=" + String.format("%.3f", body.z) + " v=" + String.format("%.4f", body.vz)
                + " sim=" + land + " ppe=" + (l == null ? "none" : "tick " + l.tick + " y " + l.y + " z " + String.format("%.3f", l.z) + " dmg " + l.damage)
                + " status=" + ppe.status + " conf=" + ppe.confidence + " verdict=" + v);
        return new Outcome(land, ppe, v);
    }

    private static void sameLanding(Outcome o) {
        assertNotNull("PlayerSim lands", o.sim());
        assertNotNull("engine lands", o.ppe().landing);
        assertEquals("landing height", o.sim().y(), o.ppe().landing.y, 1e-3);
        assertTrue("landing tick sim " + o.sim().tick() + " ppe " + o.ppe().landing.tick, Math.abs(o.sim().tick() - o.ppe().landing.tick) <= 1);
    }

    @Test
    public void threeGapSprintJumpAgrees() {
        Outcome o = fly(course(3, 0, 0), 0.75);
        sameLanding(o);
        assertEquals(64, o.sim().y(), 1e-6);
        assertEquals(Presto.Verdict.AGREE, o.verdict());
    }

    @Test
    public void twoGapSprintJumpAgrees() {
        Outcome o = fly(course(2, 0, 0), 0.5);
        sameLanding(o);
        assertEquals(64, o.sim().y(), 1e-6);
        assertEquals(Presto.Verdict.AGREE, o.verdict());
    }

    /** Too wide from flat ground: both drop into the pit, and the engine prices the fall. */
    @Test
    public void sixGapFallsShortInBoth() {
        Outcome o = fly(course(6, 0, 0), 0.75);
        sameLanding(o);
        assertEquals(58, o.sim().y(), 1e-6);
        assertTrue(o.ppe().landing.damage > 0);
    }

    /** A ceiling two blocks over the takeoff cuts the jump: a 3-gap made in the open falls short under it. */
    @Test
    public void lowCeilingCutsTheJumpInBoth() {
        Outcome o = fly(course(3, 66, -3), 0.75);
        sameLanding(o);
        assertEquals(58, o.sim().y(), 1e-6);
    }
}
