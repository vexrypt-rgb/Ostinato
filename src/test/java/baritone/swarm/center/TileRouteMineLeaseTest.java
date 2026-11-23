package baritone.swarm.center;

import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: a mine tile aimed at another bot offers a lease on a known vein of its first block, never a plain send. */
public class TileRouteMineLeaseTest {

    @Test
    public void theFirstBlockSkipsTheCountAndTheNamespace() {
        assertEquals("iron_ore", TileRoute.firstBlock("mine 16 iron_ore coal_ore"));
        assertEquals("diamond_ore", TileRoute.firstBlock("mine minecraft:diamond_ore"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMineWithNoBlockIsRefused() {
        TileRoute.firstBlock("mine 16");
    }
}
