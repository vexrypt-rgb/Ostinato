package baritone.structure;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BastKitTest {
    @Test
    public void runKitIsARealisticEntry() {
        List<String> k = BastKit.commands("run");
        String all = String.join(";", k);
        assertTrue(all.contains("armor.feet golden_boots"));
        assertTrue(all.contains("water_bucket") && all.contains("flint_and_steel") && all.contains("stone_axe"));
        for (String bad : new String[]{"gold_ingot", "gold_block", "golden_apple", "diamond_sword", "lava_bucket", "iron_chestplate"}) assertFalse(bad, all.contains(bad));
    }

    @Test
    public void richKitKeepsOldGear() {
        assertTrue(String.join(";", BastKit.commands("rich")).contains("gold_ingot 24"));
    }
}
