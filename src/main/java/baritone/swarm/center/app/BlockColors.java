package baritone.swarm.center.app;

import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: Phase 3 window, the fixed block-to-color table for the war-room terrain. Uncached columns ({@code ?})
 * are 0, meaning "do not draw". Anything else unknown is {@link #OTHER}. The {@code minecraft:} prefix is ignored.
 * Height shading is not applied: the feed is a surface overview, and the Y slider is left out for the same reason.
 */
public final class BlockColors {

    public static final int OTHER = 0x8A8478;

    private static final Map<String, Integer> TABLE = Map.ofEntries(
            Map.entry("grass_block", 0x5D9B41),
            Map.entry("grass", 0x5D9B41),
            Map.entry("dirt", 0x866043),
            Map.entry("coarse_dirt", 0x866043),
            Map.entry("podzol", 0x6B4F32),
            Map.entry("stone", 0x7A7A7A),
            Map.entry("cobblestone", 0x6E6E6E),
            Map.entry("andesite", 0x8A8A8A),
            Map.entry("diorite", 0xC4C4C4),
            Map.entry("granite", 0x9A6A58),
            Map.entry("deepslate", 0x4A4E55),
            Map.entry("tuff", 0x5C655C),
            Map.entry("gravel", 0x847E7A),
            Map.entry("sand", 0xDBD3A0),
            Map.entry("red_sand", 0xBA6A3A),
            Map.entry("sandstone", 0xD8C48A),
            Map.entry("water", 0x3F76E4),
            Map.entry("lava", 0xD35400),
            Map.entry("ice", 0xA0D8E8),
            Map.entry("snow", 0xF0F4F8),
            Map.entry("clay", 0xA0A6B0),
            Map.entry("oak_log", 0x6B5533),
            Map.entry("spruce_log", 0x4A3728),
            Map.entry("birch_log", 0xD6D0C0),
            Map.entry("jungle_log", 0x6B4A32),
            Map.entry("acacia_log", 0x6B5533),
            Map.entry("dark_oak_log", 0x3E2E1E),
            Map.entry("oak_leaves", 0x3A7D32),
            Map.entry("spruce_leaves", 0x2F6B3A),
            Map.entry("birch_leaves", 0x6B9A4A),
            Map.entry("jungle_leaves", 0x2F8A32),
            Map.entry("azalea_leaves", 0x4E8A48),
            Map.entry("coal_ore", 0x2C2C2C),
            Map.entry("deepslate_coal_ore", 0x2C2C2C),
            Map.entry("iron_ore", 0xC4A882),
            Map.entry("deepslate_iron_ore", 0xC4A882),
            Map.entry("copper_ore", 0xC46B3A),
            Map.entry("deepslate_copper_ore", 0xC46B3A),
            Map.entry("gold_ore", 0xF2C14E),
            Map.entry("deepslate_gold_ore", 0xF2C14E),
            Map.entry("redstone_ore", 0xAB2B2B),
            Map.entry("deepslate_redstone_ore", 0xAB2B2B),
            Map.entry("lapis_ore", 0x2A4C9B),
            Map.entry("deepslate_lapis_ore", 0x2A4C9B),
            Map.entry("diamond_ore", 0x4AEDD9),
            Map.entry("deepslate_diamond_ore", 0x4AEDD9),
            Map.entry("emerald_ore", 0x3DDC6A),
            Map.entry("deepslate_emerald_ore", 0x3DDC6A),
            Map.entry("netherrack", 0x6B3030),
            Map.entry("nether_bricks", 0x2C1518),
            Map.entry("soul_sand", 0x5A4536),
            Map.entry("basalt", 0x4A4A4E),
            Map.entry("blackstone", 0x2A242C),
            Map.entry("end_stone", 0xDBDE9A),
            Map.entry("obsidian", 0x1A1028),
            Map.entry("bedrock", 0x555555),
            Map.entry("moss_block", 0x4A6B32),
            Map.entry("other", OTHER));

    private BlockColors() {}

    /** 0xRRGGBB, or 0 when the column should stay the map background. */
    public static int color(String id) {
        if (id == null || id.isBlank() || "?".equals(id)) {
            return 0;
        }
        String key = id.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        Integer known = TABLE.get(key);
        if (known != null) {
            return known;
        }
        if (key.endsWith("_log") || key.endsWith("_wood")) {
            return TABLE.get("oak_log");
        }
        if (key.endsWith("_leaves")) {
            return TABLE.get("oak_leaves");
        }
        if (key.endsWith("_ore")) {
            return 0x8A7048;
        }
        return OTHER;
    }
}
