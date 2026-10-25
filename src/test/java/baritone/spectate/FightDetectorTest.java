package baritone.spectate;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

// Ostinato: automatic fight detection without spectate command or server menu
public class FightDetectorTest {
    private static FightDetector.Obs p(int id, double x, double z, float yaw, boolean hurt, boolean swing) {
        return new FightDetector.Obs(id, x, 64, z, yaw, hurt, swing);
    }

    @Test
    public void swingFacingInReachEngagesBoth() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 3, 0, false, false), p(3, 50, 50, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
        assertFalse(d.engaged(3));
    }

    @Test
    public void swingAwayOrOutOfReachIsNotAFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 180, false, true), p(2, 0, 3, 0, false, false)));
        d.tick(2, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 10, 0, false, false)));
        assertFalse(d.anyFight());
    }

    @Test
    public void hurtNearAnotherPlayerEngages_aloneDoesNot() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, true, false)));
        assertFalse(d.anyFight()); // fall damage, lava: no fight
        d.tick(2, List.of(p(1, 0, 0, 0, true, false), p(2, 5, 0, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
    }

    @Test
    public void engagementExpiresAfterHold() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 2, 0, false, false)));
        d.tick(1 + FightDetector.HOLD_TICKS, List.of());
        assertTrue(d.anyFight());
        d.tick(2 + FightDetector.HOLD_TICKS, List.of());
        assertFalse(d.anyFight());
    }

    @Test
    public void sumoKnockOffVoidWithoutDamageIsAWin() {
        FightDetector d = new FightDetector();
        d.voidY(-64);
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 0, 2, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
        d.tick(30, List.of(new FightDetector.Obs(1, 0, -70, 0, 0, false, false, false, false), p(2, 0, 2, 0, false, false)));
        var o = d.drainOutcomes();
        assertEquals(1, o.size());
        assertEquals(new FightDetector.Outcome(1, 2, "void"), o.get(0));
    }

    @Test
    public void knockedAloneIsNotAFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 30, 0, 0, false, false)));
        assertFalse(d.anyFight());
    }
}
