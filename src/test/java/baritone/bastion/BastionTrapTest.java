package baritone.bastion;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BastionTrapTest {
    private static BastionTrap.Site s(int dx, int dz, boolean lava, boolean edge, boolean walled, double d) {
        return new BastionTrap.Site(dx, dz, true, true, walled, true, lava, edge, d);
    }

    @Test
    public void choosesNearestSafeSite() {
        java.util.List<BastionTrap.Site> sites = J8.listOf(s(1, 0, false, false, true, 5), s(-1, 0, false, false, true, 3), s(0, 1, true, false, true, 1), s(0, -1, false, true, true, 1));
        assertEquals(-1, BastionTrap.choose(sites, java.util.Collections.emptyList()).dx()); // lava and edge sites skipped
    }

    @Test
    public void needsWallsAndSeparateHoles() {
        assertNull(BastionTrap.choose(J8.listOf(s(1, 0, false, false, false, 1)), java.util.Collections.emptyList()));
        java.util.List<BastionTrap.Site> sites = J8.listOf(s(1, 0, false, false, true, 1), s(-1, 0, false, false, true, 4));
        assertEquals(-1, BastionTrap.choose(sites, J8.<int[]>listOf(new int[]{1, 0})).dx());
        assertNull(BastionTrap.choose(J8.listOf(s(1, 0, false, false, true, 1)), J8.<int[]>listOf(new int[]{1, 1})));
    }
}