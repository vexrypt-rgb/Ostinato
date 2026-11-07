package baritone.bombard;

import baritone.api.bombard.BombardPermit;
import baritone.api.bombard.BombardPermit.Primitive;
import org.junit.Test;

import static org.junit.Assert.*;

public class BombardPermitTest {
    @Test(expected = IllegalArgumentException.class)
    public void infiniteDamageIsNotAPolicyBudget() {
        new BombardPermit(true, 1, true, true, true, true, true, Float.POSITIVE_INFINITY, "test");
    }

    private static BombardPermit all(String by) {
        return new BombardPermit(true, 4, true, true, true, true, true, 6, by);
    }

    @Test
    public void denyAllowsNothing() {
        for (Primitive p : Primitive.values()) assertFalse(BombardPermit.DENY.allows(p));
        assertFalse(BombardPermit.DENY.any());
        assertEquals("deny(default)", BombardPermit.DENY.toString());
    }

    @Test
    public void everyPrimitiveNeedsTntPlacedLitAndBlocksBreakable() {
        assertTrue(all("t").any());
        assertFalse(new BombardPermit(false, 4, true, true, true, true, true, 6, "t").any());
        assertFalse(new BombardPermit(true, 0, true, true, true, true, true, 6, "t").any());
        assertFalse(new BombardPermit(true, 4, false, true, true, true, true, 6, "t").any());
        assertFalse(new BombardPermit(true, 4, true, true, true, true, false, 6, "t").any());
    }

    @Test
    public void eachPrimitiveIsGrantedOnItsOwn() {
        BombardPermit rodOnly = new BombardPermit(true, 2, true, true, false, false, true, 0, "tenorclef");
        assertTrue(rodOnly.allows(Primitive.ROD));
        assertFalse(rodOnly.allows(Primitive.SPEAR));
        assertFalse(rodOnly.allows(Primitive.CHAIN));
        assertEquals("tnt<=2 rod self<=0.0 (tenorclef)", rodOnly.toString());
    }

    @Test
    public void nonsenseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BombardPermit(true, -1, true, true, true, true, true, 6, "t"));
        assertThrows(IllegalArgumentException.class, () -> new BombardPermit(true, 1, true, true, true, true, true, -1, "t"));
        assertThrows(IllegalArgumentException.class, () -> new BombardPermit(true, 1, true, true, true, true, true, Float.NaN, "t"));
        assertEquals("?", new BombardPermit(false, 0, false, false, false, false, false, 0, null).grantedBy());
    }

    @Test
    public void modeIsOffUnlessAskedFor() {
        assertEquals(Bombard.Mode.OFF, Bombard.parse("off"));
        assertEquals(Bombard.Mode.OBSERVE, Bombard.parse(" Observe "));
        assertEquals(Bombard.Mode.LIVE, Bombard.parse("LIVE"));
        assertEquals(Bombard.Mode.OFF, Bombard.parse("yes"));
        assertEquals(Bombard.Mode.OFF, Bombard.parse(""));
    }
}
