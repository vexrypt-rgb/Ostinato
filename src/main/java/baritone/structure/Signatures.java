package baritone.structure;

import java.util.ArrayList;
import java.util.List;

import static baritone.structure.Cat.*;

/**
 * Block-count rules over a 3x3 chunk window. Thresholds are first guesses; calibrate against the server source.
 * Variants that depend on biome (ruined portals, ocean ruins, shipwrecks) are refined by the caller via {@code biome}.
 */
final class Signatures {
    private Signatures() {}

    static final class Hit {
        final String id;
        final double confidence;

        Hit(String id, double confidence) {
            this.id = id;
            this.confidence = confidence;
        }
    }

    private static double ramp(int n, int lo, int hi) {
        return n >= hi ? 1.0 : n < lo ? 0 : 0.5 + 0.5 * (n - lo) / Math.max(1, hi - lo);
    }

    static List<Hit> evaluate(int[] c, String dimension, String biome) {
        List<Hit> out = new ArrayList<>();
        boolean overworld = dimension.equals(StructureInfo.OVERWORLD);
        if (overworld) {
            if (c[END_FRAME.ordinal()] >= 1) out.add(new Hit("stronghold", ramp(c[END_FRAME.ordinal()], 1, 8)));
            if (c[COBWEB.ordinal()] >= 3 && c[RAIL.ordinal()] + c[OAK_FENCE.ordinal()] >= 3) {
                boolean mesa = c[DARK_OAK_PLANKS.ordinal()] + c[DARK_OAK_FENCE.ordinal()] > c[OAK_PLANKS.ordinal()] + c[OAK_FENCE.ordinal()];
                out.add(new Hit(mesa ? "mineshaft_mesa" : "mineshaft", 0.7));
            }
            if (c[SANDSTONE.ordinal()] >= 100 && c[ORANGE_TERRACOTTA.ordinal()] >= 8 && c[BLUE_TERRACOTTA.ordinal()] >= 2) {
                out.add(new Hit("desert_pyramid", 0.9));
            }
            if (c[MOSSY_COBBLE.ordinal()] >= 50 && c[TRAP_PARTS.ordinal()] >= 2 && c[CHEST.ordinal()] >= 1) {
                out.add(new Hit("jungle_pyramid", 0.9));
            }
            if (c[SNOW_BLOCK.ordinal()] >= 20 && c[BED.ordinal()] >= 1 && c[CRAFTING_TABLE.ordinal()] + c[FURNACE.ordinal()] >= 1
                    && c[SPRUCE_PLANKS.ordinal()] + c[OAK_PLANKS.ordinal()] < 40) {
                out.add(new Hit("igloo", 0.8));
            }
            if (c[CAULDRON.ordinal()] >= 1 && c[FLOWER_POT.ordinal()] >= 1 && c[SPRUCE_PLANKS.ordinal()] >= 5 && c[OAK_LOG.ordinal()] >= 4
                    && c[BED.ordinal()] == 0) {
                out.add(new Hit("swamp_hut", 0.8));
            }
            // villages: beds plus paths/farmland, variant from the dominant material
            if (c[BED.ordinal()] >= 2 && c[PATH.ordinal()] + c[FARMLAND.ordinal()] + c[HAY.ordinal()] + c[BELL.ordinal()] >= 4) {
                int plains = c[OAK_PLANKS.ordinal()] + c[OAK_LOG.ordinal()];
                int desert = c[SANDSTONE.ordinal()];
                int savanna = c[ACACIA_PLANKS.ordinal()] + c[ACACIA_LOG.ordinal()];
                int spruce = c[SPRUCE_PLANKS.ordinal()] + c[SPRUCE_LOG.ordinal()];
                String id;
                if (desert > plains && desert > savanna && desert > spruce) id = "village_desert";
                else if (savanna > plains && savanna > spruce) id = "village_savanna";
                else if (spruce > plains) id = c[SNOW_BLOCK.ordinal()] >= 4 || biome.contains("snowy") || biome.contains("frozen") ? "village_snowy" : "village_taiga";
                else id = "village_plains";
                out.add(new Hit(id, 0.85));
            }
            if (c[DARK_OAK_LOG.ordinal()] >= 10 && c[COBBLE.ordinal()] >= 10 && c[CHEST.ordinal()] >= 1 && c[DARK_OAK_PLANKS.ordinal()] < 150) {
                out.add(new Hit("pillager_outpost", 0.65));
            }
            if (c[DARK_OAK_PLANKS.ordinal()] >= 150 && c[COBBLE.ordinal()] >= 50 && c[RED_CARPET.ordinal()] >= 2 && c[COBWEB.ordinal()] < 10) {
                out.add(new Hit("mansion", 0.9));
            }
            if (c[PRISMARINE.ordinal()] >= 200 && c[SEA_LANTERN.ordinal()] >= 4) out.add(new Hit("monument", 0.95));
            if (c[SCULK.ordinal()] >= 50 && c[DEEPSLATE_BRICKS.ordinal()] >= 50 && c[SOUL_LANTERN.ordinal()] >= 1) out.add(new Hit("ancient_city", 0.9));
            if (c[TRIAL_SPAWNER.ordinal()] >= 1 || (c[TUFF_BRICKS.ordinal()] >= 150 && c[COPPER_BULB.ordinal()] >= 1)) out.add(new Hit("trial_chambers", 0.9));
            if (c[SUSPICIOUS_GRAVEL.ordinal()] >= 1 && c[MUD_BRICKS.ordinal()] >= 5 && c[TERRACOTTA.ordinal()] >= 5) out.add(new Hit("trail_ruins", 0.7));
            if (c[OBSIDIAN.ordinal()] >= 5 && c[MAGMA.ordinal()] + c[CRYING_OBSIDIAN.ordinal()] >= 1) {
                out.add(new Hit(portalVariant(biome), 0.6));
            }
            boolean ocean = biome.contains("ocean");
            boolean mined = c[COBWEB.ordinal()] >= 3 || c[RAIL.ordinal()] >= 3;
            if (ocean && !mined && c[CHEST.ordinal()] >= 1 && c[OAK_PLANKS.ordinal()] + c[SPRUCE_PLANKS.ordinal()] + c[DARK_OAK_PLANKS.ordinal()] >= 15) {
                out.add(new Hit("shipwreck", 0.6));
            } else if (!ocean && !mined && c[BED.ordinal()] == 0 && c[PATH.ordinal()] == 0 && c[CHEST.ordinal()] >= 1
                    && c[OAK_LOG.ordinal()] + c[SPRUCE_LOG.ordinal()] >= 8 && c[OAK_PLANKS.ordinal()] + c[SPRUCE_PLANKS.ordinal()] >= 15
                    && (biome.contains("beach") || biome.contains("shore"))) {
                out.add(new Hit("shipwreck_beached", 0.5));
            }
            if (ocean && !mined && (biome.contains("warm") || biome.contains("lukewarm")) && c[SANDSTONE.ordinal()] >= 8
                    && c[SANDSTONE.ordinal()] < 400) {
                out.add(new Hit("ocean_ruin_warm", 0.35));
            }
            if (ocean && c[STONE_BRICKS.ordinal()] + c[SANDSTONE.ordinal()] >= 20 && c[MOSSY_COBBLE.ordinal()] + c[STONE_BRICKS.ordinal()] >= 10
                    && c[PRISMARINE.ordinal()] < 100) {
                out.add(new Hit(biome.contains("warm") || biome.contains("lukewarm") ? "ocean_ruin_warm" : "ocean_ruin_cold", 0.45));
            }
        } else if (dimension.equals(StructureInfo.NETHER)) {
            if (c[NETHER_BRICKS.ordinal()] >= 100 && c[NETHER_FENCE.ordinal()] >= 10) out.add(new Hit("fortress", 0.9));
            if (c[BLACKSTONE.ordinal()] >= 200 && c[GILDED.ordinal()] >= 1) out.add(new Hit("bastion_remnant", 0.9));
            if (c[BONE_BLOCK.ordinal()] >= 5) out.add(new Hit("nether_fossil", 0.8));
            if (c[OBSIDIAN.ordinal()] >= 5 && c[CRYING_OBSIDIAN.ordinal()] + c[MAGMA.ordinal()] >= 1) {
                out.add(new Hit("ruined_portal_nether", 0.6));
            }
        } else if (dimension.equals(StructureInfo.END)) {
            if (c[PURPUR.ordinal()] >= 100 && c[END_ROD.ordinal()] >= 1) out.add(new Hit("end_city", 0.9));
        }
        return out;
    }

    private static String portalVariant(String biome) {
        if (biome.contains("desert") || biome.contains("badlands")) return "ruined_portal_desert";
        if (biome.contains("jungle")) return "ruined_portal_jungle";
        if (biome.contains("swamp") || biome.contains("mangrove")) return "ruined_portal_swamp";
        if (biome.contains("ocean")) return "ruined_portal_ocean";
        if (biome.contains("peaks") || biome.contains("hills") || biome.contains("windswept") || biome.contains("meadow")
                || biome.contains("grove") || biome.contains("slopes")) return "ruined_portal_mountain";
        return "ruined_portal";
    }
}
