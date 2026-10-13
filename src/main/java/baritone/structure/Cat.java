package baritone.structure;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Block families the client-side signatures count. A block can belong to several. */
public enum Cat {
    END_FRAME(p -> p.equals("end_portal_frame")),
    TRIAL_SPAWNER(p -> p.equals("trial_spawner") || p.equals("vault")),
    COPPER_BULB(p -> p.endsWith("copper_bulb")),
    TUFF_BRICKS(p -> p.contains("tuff_brick") || p.startsWith("polished_tuff") || p.equals("chiseled_tuff")),
    SANDSTONE(p -> p.contains("sandstone") && !p.startsWith("red_")),
    ORANGE_TERRACOTTA(p -> p.equals("orange_terracotta")),
    BLUE_TERRACOTTA(p -> p.equals("blue_terracotta")),
    TERRACOTTA(p -> p.endsWith("terracotta") && !p.startsWith("glazed")),
    MOSSY_COBBLE(p -> p.startsWith("mossy_cobblestone")),
    COBBLE(p -> p.startsWith("cobblestone")),
    STONE_BRICKS(p -> p.equals("stone_bricks") || p.equals("mossy_stone_bricks") || p.equals("cracked_stone_bricks")
            || p.equals("chiseled_stone_bricks")),
    TRAP_PARTS(p -> p.equals("tripwire_hook") || p.equals("lever") || p.equals("dispenser") || p.equals("tripwire")),
    SNOW_BLOCK(p -> p.equals("snow_block")),
    BED(p -> p.endsWith("_bed")),
    FURNACE(p -> p.equals("furnace")),
    CRAFTING_TABLE(p -> p.equals("crafting_table")),
    CAULDRON(p -> p.endsWith("cauldron")),
    FLOWER_POT(p -> p.equals("flower_pot") || p.startsWith("potted_")),
    OAK_LOG(p -> p.equals("oak_log") || p.equals("stripped_oak_log") || p.equals("oak_wood")),
    OAK_PLANKS(p -> p.equals("oak_planks") || p.equals("oak_slab") || p.equals("oak_stairs")),
    SPRUCE_PLANKS(p -> p.equals("spruce_planks") || p.equals("spruce_slab") || p.equals("spruce_stairs")),
    SPRUCE_LOG(p -> p.equals("spruce_log") || p.equals("stripped_spruce_log") || p.equals("spruce_wood")),
    ACACIA_PLANKS(p -> p.equals("acacia_planks") || p.equals("acacia_slab") || p.equals("acacia_stairs")),
    ACACIA_LOG(p -> p.equals("acacia_log") || p.equals("stripped_acacia_log")),
    DARK_OAK_PLANKS(p -> p.equals("dark_oak_planks") || p.equals("dark_oak_slab") || p.equals("dark_oak_stairs")),
    DARK_OAK_LOG(p -> p.equals("dark_oak_log") || p.equals("stripped_dark_oak_log") || p.equals("dark_oak_wood")),
    BIRCH_PLANKS(p -> p.equals("birch_planks") || p.equals("birch_slab") || p.equals("birch_stairs")),
    OAK_FENCE(p -> p.equals("oak_fence")),
    DARK_OAK_FENCE(p -> p.equals("dark_oak_fence")),
    RAIL(p -> p.equals("rail") || p.equals("powered_rail") || p.equals("detector_rail") || p.equals("activator_rail")),
    COBWEB(p -> p.equals("cobweb")),
    CHEST(p -> p.equals("chest") || p.equals("trapped_chest")),
    RED_CARPET(p -> p.equals("red_carpet")),
    PRISMARINE(p -> p.contains("prismarine")),
    SEA_LANTERN(p -> p.equals("sea_lantern")),
    SUSPICIOUS_GRAVEL(p -> p.equals("suspicious_gravel")),
    MUD_BRICKS(p -> p.contains("mud_brick") || p.equals("packed_mud")),
    DEEPSLATE_BRICKS(p -> p.contains("deepslate_brick") || p.contains("deepslate_tile") || p.equals("chiseled_deepslate")),
    SCULK(p -> p.startsWith("sculk")),
    SOUL_LANTERN(p -> p.equals("soul_lantern") || p.equals("soul_torch")),
    OBSIDIAN(p -> p.equals("obsidian")),
    CRYING_OBSIDIAN(p -> p.equals("crying_obsidian")),
    MAGMA(p -> p.equals("magma_block")),
    BELL(p -> p.equals("bell")),
    FARMLAND(p -> p.equals("farmland")),
    PATH(p -> p.equals("dirt_path")),
    HAY(p -> p.equals("hay_block")),
    NETHER_BRICKS(p -> p.equals("nether_bricks") || p.equals("red_nether_bricks") || p.equals("chiseled_nether_bricks")
            || p.equals("cracked_nether_bricks") || p.equals("nether_brick_stairs") || p.equals("nether_brick_slab")),
    NETHER_FENCE(p -> p.equals("nether_brick_fence")),
    NETHER_WART(p -> p.equals("nether_wart") || p.equals("nether_wart_block")),
    BLACKSTONE(p -> p.contains("blackstone")),
    GILDED(p -> p.equals("gilded_blackstone")),
    BONE_BLOCK(p -> p.equals("bone_block")),
    PURPUR(p -> p.startsWith("purpur")),
    END_ROD(p -> p.equals("end_rod")),
    DRAGON_HEAD(p -> p.equals("dragon_head") || p.equals("dragon_wall_head")),
    SPAWNER(p -> p.equals("spawner"));

    public static final Cat[] ALL = values();
    private static final Map<Block, Long> MASK = new HashMap<>();
    private final Predicate<String> test;

    Cat(Predicate<String> test) {
        this.test = test;
    }

    static {
        for (Block b : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            long m = 0;
            for (Cat c : ALL) if (c.test.test(path)) m |= 1L << c.ordinal();
            if (m != 0) MASK.put(b, m);
        }
    }

    /** Bitmask of every category this block is in (0 when none). */
    public static long mask(Block b) {
        Long m = MASK.get(b);
        return m == null ? 0 : m;
    }
}
