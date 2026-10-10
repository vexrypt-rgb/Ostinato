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
        var sites = List.of(s(1, 0, false, false, true, 5), s(-1, 0, false, false, true, 3), s(0, 1, true, false, true, 1), s(0, -1, false, true, true, 1));
        assertEquals(-1, BastionTrap.choose(sites, List.of()).dx()); // lava and edge sites skipped
    }

    @Test
    public void needsWallsAndSeparateHoles() {
        assertNull(BastionTrap.choose(List.of(s(1, 0, false, false, false, 1)), List.of()));
        var sites = List.of(s(1, 0, false, false, true, 1), s(-1, 0, false, false, true, 4));
        assertEquals(-1, BastionTrap.choose(sites, List.<int[]>of(new int[]{1, 0})).dx());
        assertNull(BastionTrap.choose(List.of(s(1, 0, false, false, true, 1)), List.<int[]>of(new int[]{1, 1})));
    }
}