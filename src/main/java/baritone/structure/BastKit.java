package baritone.structure;

import java.util.ArrayList;
import java.util.List;

/** BastTest kit presets (-Dostinato.basttest.kit=run|rich, run by default). Pure. */
public final class BastKit {
    private BastKit() {}

    /** Commands that give the named kit (after clear). */
    public static List<String> commands(String kit) {
        List<String> c = new ArrayList<>();
        if ("rich".equals(kit)) {
            c.add("replaceitem entity @p armor.feet golden_boots");
            c.add("replaceitem entity @p armor.head iron_helmet");
            c.add("replaceitem entity @p armor.chest iron_chestplate");
            c.add("replaceitem entity @p armor.legs iron_leggings");
            c.add("give @p iron_pickaxe"); c.add("give @p diamond_sword"); c.add("give @p cooked_beef 16");
            c.add("give @p gold_ingot 24"); c.add("give @p gold_block 2"); c.add("give @p netherrack 64"); c.add("give @p soul_sand 32");
            c.add("give @p golden_apple 2"); c.add("give @p lava_bucket"); c.add("give @p bucket");
            return c;
        }
        c.add("replaceitem entity @p armor.feet golden_boots");
        c.add("give @p iron_pickaxe"); c.add("give @p stone_axe");
        c.add("give @p water_bucket"); c.add("give @p bucket");
        c.add("give @p cobblestone 20"); c.add("give @p netherrack 32");
        c.add("give @p flint_and_steel"); c.add("give @p cooked_porkchop 8");
        return c;
    }
}
