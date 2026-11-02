/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Input;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Failure;
import baritone.api.physics.PredictionResult.Status;
import org.junit.Test;

import java.util.List;

import static baritone.physics.PpeWorlds.*;
import static org.junit.Assert.*;

/**
 * Ostinato: unit tests of the engine against hand-built terrain: movement, falls, TNT, explosions and projectiles,
 * checked against vanilla numbers worked out independently (known speeds and heights, the published formulas).
 */
public class PpeCoreTest {
    private static final Terrain FLAT = flat(STONE);
    private static final Input FORWARD = new Input(1, 0, false, false, false, 0, 0);
    private static final Input SPRINT = new Input(1, 0, false, true, false, 0, 0);
    private static final Input JUMP = new Input(0, 0, true, false, false, 0, 0);

    private static PredictionResult run(PredictionRequest r, Terrain t) {
        return new Predictor().run(r, t, r.fidelity, () -> false);
    }

    private static BodyState.Builder player(double x, double y, double z) {
        return BodyState.builder(BodyState.Type.PLAYER).pos(x, y, z).onGround(y == 64);
    }

    @Test
    public void standingStillStaysPut() {
        PredictionResult r = run(PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 40).build(), FLAT);
        assertEquals(Status.OK, r.status);
        assertEquals(40, r.horizonTicks);
        assertEquals(0.5, r.x, 0);
        assertEquals(64, r.y, 0);
        assertEquals(0.5, r.z, 0);
        assertTrue(r.onGround);
        assertNull(r.landing);
    }

    @Test
    public void walkAndSprintReachVanillaTopSpeeds() {
        // yaw 0 faces +z. On stone a tick moves v + a and keeps (v + a) * 0.546, so the top displacement is
        // a / (1 - 0.546): walk 0.098 / 0.454, sprint 0.13 * 0.98 / 0.454 (the well-known 4.317 and 5.612 m/s)
        PredictionResult walk = run(PredictionRequest.trajectory(player(0.5, 64, -20).build(), 60).input(FORWARD).fidelity(Fidelity.HIGH).build(), FLAT);
        assertEquals(Status.OK, walk.status);
        assertEquals(4.317, step(walk) * 20, 0.001);
        assertEquals(0.546 * 0.098 / 0.454, walk.vz, 1e-4);
        assertEquals(0, walk.vx, 1e-9);
        PredictionResult sprint = run(PredictionRequest.trajectory(player(0.5, 64, -30).build(), 60).input(SPRINT).fidelity(Fidelity.HIGH).build(), FLAT);
        assertEquals(5.612, step(sprint) * 20, 0.001);
        assertTrue(sprint.z + 30 > walk.z + 20 + 3);
    }

    private static double step(PredictionResult r) {
        int n = r.path.length / 3;
        return r.path[(n - 1) * 3 + 2] - r.path[(n - 2) * 3 + 2];
    }

    @Test
    public void jumpPeaksAtVanillaHeight() {
        PredictionResult r = run(PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 12).input(JUMP).fidelity(Fidelity.HIGH).build(), FLAT);
        assertEquals(1, r.pathStride);
        float top = 0;
        int topTick = -1;
        for (int i = 0; i < r.path.length / 3; i++) {
            if (r.path[i * 3 + 1] - 64 > top) {
                top = r.path[i * 3 + 1] - 64;
                topTick = i;
            }
        }
        assertEquals(1.2522, top, 1e-3); // the well-known 1.2522 block jump
        assertEquals(6, topTick);
        // held jump lands on tick 12 and jumps again
        assertTrue(r.firstCollisionTick > 0);
    }

    @Test
    public void fallOntoStoneIsPricedAndLethal() {
        // dropped from 23.5 blocks: floor(23.5 - 3) = 20 damage, exactly a full health bar
        PredictionResult r = run(PredictionRequest.fall(player(0.5, 87.5, 0.5).build(), 200).build(), FLAT);
        assertNotNull(r.landing);
        assertEquals(64, r.landing.y, 0);
        assertEquals(63, r.landing.blockY);
        assertEquals(23.5, r.landing.fallDistance, 1e-4);
        assertEquals(20, r.landing.damage, 0);
        assertTrue(r.landing.lethal);
        assertEquals(r.landing.tick, r.horizonTicks);
        // a full feather falling IV set at 12 points (+ protection 0): 20 * (1 - 12/25)
        BodyState ff = player(0.5, 87.5, 0.5).armor(0, 0, 12, 0).build();
        PredictionResult s = run(PredictionRequest.fall(ff, 200).build(), FLAT);
        assertEquals(20 * (1 - 12 / 25f), s.landing.damage, 1e-4);
        assertFalse(s.landing.lethal);
    }

    @Test
    public void fallDamageByBlock() {
        BodyState p = player(0, 70, 0).build();
        assertEquals(4, Predictor.fallDamage(p, HAY, 23.5, false), 0); // floor(20.5 * 0.2)
        assertEquals(0, Predictor.fallDamage(p, SLIME, 23.5, false), 0);
        assertEquals(0, Predictor.fallDamage(p, STONE, 3.4, false), 0);
        assertEquals(1, Predictor.fallDamage(p, STONE, 4, false), 0);
        BodyState creative = player(0, 70, 0).flags(BodyState.MAY_FLY).build();
        assertEquals(0, Predictor.fallDamage(creative, STONE, 50, false), 0);
        // Resistance II (stored as amplifier + 1 = 2) takes 40%
        BodyState res = player(0, 70, 0).effects(0, 0, 2).build();
        assertEquals(10 * 0.6f, Predictor.fallDamage(res, STONE, 13, false), 1e-5);
    }

    @Test
    public void landingOnSlimeBounces() {
        Terrain slime = flat(SLIME);
        PredictionResult r = run(PredictionRequest.trajectory(player(0.5, 74, 0.5).build(), 40).build(), slime);
        assertNotNull(r.landing);
        assertTrue(r.landing.bounced);
        assertEquals(0, r.landing.damage, 0);
        assertTrue(r.y > 64.5); // back in the air or on the way up again at some point after the landing
    }

    @Test
    public void tntFuseRunsOutAndExplodes() {
        BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).entityId(7).pos(0.5, 64, 0.5).vel(0, 0.2, 0).fuse(80, 4).build();
        BodyState near = player(3.5, 64, 0.5).build();
        PredictionResult r = run(PredictionRequest.trajectory(tnt, 200).targets(List.of(near, tnt)).fidelity(Fidelity.HIGH).build(), FLAT);
        assertNotNull(r.explosion);
        assertEquals(80, r.explosion.tick);
        assertEquals(80, r.horizonTicks);
        assertEquals(64 + 0.98f * 0.0625, r.explosion.y, 1e-9); // back at rest on the ground
        assertEquals(2, r.explosion.targets.size());
        assertEquals(0, r.explosion.targets.get(1).exposure, 0); // the TNT itself
        PredictionResult.Exposure e = r.explosion.targets.get(0);
        assertEquals(1, e.exposure, 0);
        assertTrue(e.damage > 0);
        assertTrue(e.kx > 0);
        // on a stone floor every roll breaks the same 3x3 under it: nothing random to report
        assertArrayEquals(r.explosion.certainBlocks, r.explosion.possibleBlocks);
        assertEquals(9, r.explosion.certainBlocks.length);
        for (long p : r.explosion.certainBlocks) {
            assertEquals(63, PhysMath.unpackY(p));
            assertTrue(Math.abs(PhysMath.unpackX(p)) <= 1 && Math.abs(PhysMath.unpackZ(p)) <= 1);
        }
        assertFalse(r.has(Assumption.RANDOM_BLOCK_DAMAGE));
        // the player is met 80 ticks after capture: held where it stood
        assertTrue(r.has(Assumption.TARGETS_FROZEN));
        assertEquals(PredictionResult.Confidence.APPROXIMATE, r.confidence);
        PredictionResult alone = run(PredictionRequest.trajectory(tnt, 200).targets(List.of(tnt)).fidelity(Fidelity.HIGH).build(), FLAT);
        assertEquals(PredictionResult.Confidence.EXACT, alone.confidence);
    }

    @Test
    public void capturedTntReportsThatItsBlastPowerWasAssumed() {
        // capture() cannot read PrimedTnt.explosionPower (26.3 syncs the fuse and the block state, not the power), so it
        // flags the body; the answer must say the power was assumed rather than implying it was observed.
        BodyState captured = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 64, 0.5).fuse(80, 4)
                .flag(BodyState.POWER_ASSUMED, true).build();
        PredictionResult r = run(PredictionRequest.trajectory(captured, 200).fidelity(Fidelity.SCREENING).build(), FLAT);
        assertTrue(r.has(Assumption.TNT_POWER_ASSUMED));
        // every vanilla-primed TNT is 4, so the flag must not downgrade the answer by itself
        assertEquals(PredictionResult.Confidence.EXACT, r.confidence);
        assertNotNull(r.explosion);
        assertEquals(80, r.explosion.tick);
        // a caller that states the power itself carries no such assumption
        BodyState stated = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 64, 0.5).fuse(80, 4).build();
        PredictionRequest statedRequest = PredictionRequest.trajectory(stated, 200).fidelity(Fidelity.SCREENING).build();
        assertFalse(run(statedRequest, FLAT).has(Assumption.TNT_POWER_ASSUMED));
        // and the two must not share a cache entry: the assumption set is part of the answer
        PredictionRequest capturedRequest = PredictionRequest.trajectory(captured, 200).fidelity(Fidelity.SCREENING).build();
        assertFalse(capturedRequest.sameSimulation(statedRequest));
        assertNotEquals(capturedRequest.key(), statedRequest.key());
    }

    @Test
    public void softFloorBreaksDependOnTheRoll() {
        PredictionResult r = run(PredictionRequest.explosion(0.5, 64.5, 0.5, 4, true).fidelity(Fidelity.HIGH).build(), flat(HAY));
        long[] c = r.explosion.certainBlocks, p = r.explosion.possibleBlocks;
        assertTrue(c.length > 0 && p.length > c.length);
        for (long q : c) assertTrue(contains(p, q));
        assertTrue(r.has(Assumption.RANDOM_BLOCK_DAMAGE));
        assertEquals(PredictionResult.Confidence.BOUNDED, r.confidence);
    }

    @Test
    public void explosionDamageMatchesTheFormula() {
        BodyState p = player(0.5, 64, 4.5).build();
        PredictionResult r = run(PredictionRequest.explosion(0.5, 66, 0.5, 4, false).targets(List.of(p)).build(), FLAT);
        assertEquals(Status.OK, r.status);
        PredictionResult.Exposure e = r.explosion.targets.get(0);
        assertEquals(1, e.exposure, 0);
        double dist = Math.sqrt(4 * 4 + 2 * 2) / 8.0, pow = 1 - dist;
        float raw = (float) ((pow * pow + pow) / 2 * 7 * 8 + 1);
        assertEquals(raw, e.damage, 1e-4);
        assertNull(r.explosion.certainBlocks); // breaksBlocks false
        // hard difficulty, 20 armour: scaled, then armour
        PredictionResult h = run(PredictionRequest.explosion(0.5, 66, 0.5, 4, false).targets(List.of(player(0.5, 64, 4.5).armor(20, 0, 0, 0).build()))
                .difficulty(3).build(), FLAT);
        float hard = raw * 1.5f;
        float armoured = hard * (1 - Math.max(20 - hard / 2, 4) / 25f);
        assertEquals(armoured, h.explosion.targets.get(0).damage, 1e-4);
    }

    @Test
    public void wallBlocksExposure() {
        Terrain walled = flat((x, y, z) -> z == 2 && x >= -4 && x <= 4 && y >= 64 && y <= 69 ? OBSIDIAN : null);
        BodyState p = player(0.5, 64, 4.5).build();
        PredictionResult r = run(PredictionRequest.explosion(0.5, 65, 0.5, 4, true).targets(List.of(p)).fidelity(Fidelity.HIGH).build(), walled);
        PredictionResult.Exposure e = r.explosion.targets.get(0);
        assertEquals(0, e.exposure, 0);
        assertEquals(1, e.damage, 1e-6); // the formula's floor of 1 for a target in range
        for (long q : r.explosion.possibleBlocks) assertFalse(PhysMath.unpackZ(q) == 2 && PhysMath.unpackY(q) >= 64 && Math.abs(PhysMath.unpackX(q)) <= 4);
    }

    @Test
    public void tntBlocksInRangeChain() {
        Terrain t = flat((x, y, z) -> x == 2 && y == 64 && z == 0 ? TNT : null);
        PredictionResult r = run(PredictionRequest.explosion(0.5, 64.5, 0.5, 4, true).fidelity(Fidelity.HIGH).build(), t);
        assertArrayEquals(new long[]{PhysMath.pack(2, 64, 0)}, r.explosion.chainTnt);
        assertTrue(r.has(Assumption.RANDOM_FUSE));
    }

    @Test
    public void arrowHitsWallFace() {
        Terrain walled = flat((x, y, z) -> x == 5 && y >= 64 && y <= 75 ? STONE : null);
        BodyState arrow = BodyState.builder(BodyState.Type.ARROW).pos(0.5, 70, 0.5).vel(1.5, 0, 0).build();
        PredictionResult r = run(PredictionRequest.projectile(arrow, 100).build(), walled);
        assertNotNull(r.hit);
        assertEquals(-1, r.hit.targetIndex);
        assertEquals(5, r.hit.blockX);
        assertEquals(4, r.hit.face); // WEST
        assertEquals(5.0, r.hit.x, 1e-9);
        assertEquals(4, r.hit.tick); // x 0.5, 2.0, 3.485, 4.955, then the wall
    }

    @Test
    public void arrowHitsTargetBeforeWall() {
        Terrain walled = flat((x, y, z) -> x == 12 && y >= 64 && y <= 75 ? STONE : null);
        BodyState arrow = BodyState.builder(BodyState.Type.ARROW).pos(0.5, 65, 0.5).vel(1.5, 0, 0).build();
        BodyState target = player(8.5, 64, 0.5).build();
        PredictionResult r = run(PredictionRequest.projectile(arrow, 100).targets(List.of(target)).build(), walled);
        assertNotNull(r.hit);
        assertEquals(0, r.hit.targetIndex);
        assertEquals(-1, r.hit.face);
        assertEquals(8.2, r.hit.x, 1e-6);
        assertTrue(r.has(Assumption.TARGETS_FROZEN));
    }

    @Test
    public void snowballFallsUnderGravityAndDrag() {
        BodyState ball = BodyState.builder(BodyState.Type.THROWABLE).pos(0.5, 80, 0.5).build();
        PredictionResult r = run(PredictionRequest.projectile(ball, 1).build(), FLAT);
        assertEquals(-0.03 * 0.99f, r.vy, 1e-9);
        assertEquals(80 - 0.03 * 0.99f, r.y, 1e-9);
    }

    @Test
    public void walkingOffTheSnapshotIsPartialAndExact() {
        PredictionResult r = run(PredictionRequest.trajectory(player(0.5, 64, 20).build(), 200).input(FORWARD).build(), FLAT);
        assertEquals(Status.PARTIAL, r.status);
        assertEquals(Failure.OUT_OF_SNAPSHOT, r.failure);
        assertFalse(r.has(Assumption.OUTSIDE_SNAPSHOT));
        assertTrue(r.horizonTicks > 0 && r.horizonTicks < 200);
        assertTrue(r.z < 32);
        assertEquals(PredictionResult.Confidence.EXACT, r.confidence); // the simulated prefix is exact
    }

    @Test
    public void clientViewBodiesAreApproximate() {
        PredictionResult r = run(PredictionRequest.fall(player(0.5, 70, 0.5).flag(BodyState.CLIENT_VIEW, true).build(), 100).build(), FLAT);
        assertEquals(Status.OK, r.status);
        assertTrue(r.has(Assumption.CLIENT_STATE));
        assertEquals(PredictionResult.Confidence.APPROXIMATE, r.confidence);
    }

    @Test
    public void deadlineAndCancellation() {
        PredictionRequest late = PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 400).input(JUMP).deadlineIn(1).build();
        PredictionResult r = run(late, FLAT);
        assertEquals(Status.PARTIAL, r.status);
        assertEquals(Failure.DEADLINE, r.failure);
        assertEquals(7, r.horizonTicks);
        PredictionRequest q = PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 400).build();
        PredictionResult c = new Predictor().run(q, FLAT, Fidelity.STANDARD, () -> true);
        assertEquals(Status.CANCELLED, c.status);
    }

    @Test
    public void projectileKindNeedsAProjectile() {
        PredictionResult r = run(PredictionRequest.projectile(player(0.5, 64, 0.5).build(), 10).build(), FLAT);
        assertEquals(Status.FAILED, r.status);
        assertEquals(Failure.UNSUPPORTED, r.failure);
    }

    @Test
    public void requestKeysSeparateWhatMatters() {
        BodyState p = player(0.5, 64, 0.5).build();
        assertEquals(PredictionRequest.trajectory(p, 20).build().key(), PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 20).build().key());
        long base = PredictionRequest.trajectory(p, 20).build().key();
        assertNotEquals(base, PredictionRequest.trajectory(p, 21).build().key());
        assertNotEquals(base, PredictionRequest.trajectory(p, 20).input(FORWARD).build().key());
        assertNotEquals(base, PredictionRequest.trajectory(p, 20).fidelity(Fidelity.HIGH).build().key());
        assertNotEquals(base, PredictionRequest.trajectory(player(0.5, 64, 0.5).armor(1, 0, 0, 0).build(), 20).build().key());
        // priority, deadline and consumer do not change the answer
        assertEquals(base, PredictionRequest.trajectory(p, 20).priority(PredictionRequest.Priority.EMERGENCY).consumer("x").deadlineIn(5).build().key());
    }

    @Test
    public void fidelityChangesRecordingNotPhysics() {
        PredictionRequest.Builder b = PredictionRequest.trajectory(player(0.5, 64, -20).build(), 40).input(SPRINT);
        PredictionResult screen = run(b.fidelity(Fidelity.SCREENING).build(), FLAT);
        PredictionResult high = run(b.fidelity(Fidelity.HIGH).build(), FLAT);
        assertNull(screen.path);
        assertEquals(41 * 3, high.path.length);
        assertEquals(high.z, screen.z, 0);
        assertEquals(high.vz, screen.vz, 0);
    }

    private static boolean contains(long[] sorted, long v) {
        return java.util.Arrays.binarySearch(sorted, v) >= 0;
    }
    @Test
    public void ceilingStopsJumpAndWaterPreventsFallDamage() {
        Terrain ceiling = flat((x, y, z) -> y == 66 ? STONE : null);
        PredictionResult jump = run(PredictionRequest.trajectory(player(0.5, 64, 0.5).build(), 8)
                .input(JUMP).fidelity(Fidelity.HIGH).build(), ceiling);
        assertTrue(jump.firstCollisionTick > 0);
        for (int i = 1; i < jump.path.length; i += 3) assertTrue(jump.path[i] <= 64.20001);
        Terrain pool = flat((x, y, z) -> y >= 64 && y <= 67 ? WATER : null);
        PredictionResult wet = run(PredictionRequest.fall(player(0.5, 85, 0.5).build(), 100).build(), pool);
        assertNotNull(wet.landing);
        assertTrue(wet.landing.inFluid);
        assertEquals(0, wet.landing.damage, 0);
    }

    @Test
    public void slowFallingLevitationAndClimbingChangeTrajectory() {
        BodyState initial = player(0.5, 80, 0.5).vel(0, -1, 0).build();
        PredictionResult normal = run(PredictionRequest.trajectory(initial, 5).build(), FLAT);
        PredictionResult slow = run(PredictionRequest.trajectory(initial.toBuilder().vel(0, 0, 0)
                .flag(BodyState.SLOW_FALLING, true).build(), 5).build(), FLAT);
        PredictionResult falling = run(PredictionRequest.trajectory(initial.toBuilder().vel(0, 0, 0).build(), 5).build(), FLAT);
        assertTrue(slow.y > falling.y);
        PredictionResult levitating = run(PredictionRequest.trajectory(initial.toBuilder().vel(0, 0, 0).effects(0, 1, 0).build(), 5).build(), FLAT);
        assertTrue(levitating.y > initial.y);
        BlockInfo ladder = new BlockInfo.Builder("ladder").flags(BlockInfo.CLIMBABLE).build();
        Terrain climb = flat((x, y, z) -> x == 0 && z == 0 && y >= 64 && y < 90 ? ladder : null);
        PredictionResult climbing = run(PredictionRequest.trajectory(initial, 5).build(), climb);
        assertTrue(climbing.y > normal.y);
    }

    @Test
    public void zeroPowerExplosionIsFiniteAndStandardOmitsBlockBounds() {
        var zero = run(PredictionRequest.explosion(0.5, 64, 0.5, 0, true)
                .targets(List.of(player(0.5, 64, 0.5).build())).build(), FLAT);
        assertEquals(0, zero.explosion.targets.get(0).damage, 0);
        assertEquals(0, zero.explosion.targets.get(0).kx, 0);
        var standard = run(PredictionRequest.explosion(0.5, 64, 0.5, 4, true).build(), FLAT);
        assertNull(standard.explosion.certainBlocks);
        assertNull(standard.explosion.possibleBlocks);
    }

}
