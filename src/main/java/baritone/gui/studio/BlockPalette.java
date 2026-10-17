/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.gui.studio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every placeable block, stored as a block-state registry id. Cell 0 is air.
 * {@code .ostinato} files still write names ({@code stone} or {@code minecraft:stone},
 * and {@code oak_stairs[facing=east,...]} when the state is not the default).
 */
public final class BlockPalette {

    private static final Map<String, Integer> FALLBACK = new HashMap<>();
    private static final Map<Integer, Integer> COLOR = new HashMap<>();
    private static boolean registryBroken;
    private static int[] placeable = new int[0];
    private static boolean listed;

    static {
        String[] names = {
                "stone", "cobblestone", "oak_planks", "spruce_planks", "oak_log", "bricks", "stone_bricks",
                "deepslate", "sandstone", "quartz_block", "glass", "white_concrete", "orange_concrete",
                "yellow_concrete", "lime_concrete", "light_blue_concrete", "blue_concrete", "purple_concrete",
                "pink_concrete", "red_concrete", "black_concrete", "iron_block", "gold_block", "obsidian"
        };
        for (int i = 0; i < names.length; i++) {
            FALLBACK.put(names[i], i + 1);
        }
    }

    private BlockPalette() {}

    /** Registry id of this state, or 0 for air and for anything the registry cannot name. */
    public static int idOf(BlockState state) {
        if (state == null || state.isAir() || !registryOk()) {
            return 0;
        }
        try {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            return id <= 0 ? 0 : id;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** The state for a cell, or null when it is air or the registry is down. */
    public static BlockState stateOf(int cell) {
        if (cell <= 0 || !registryOk()) {
            return null;
        }
        try {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(cell);
            if (state == null || state.isAir()) {
                return null;
            }
            return state;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Accepts {@code stone}, {@code minecraft:stone}, and {@code name[prop=value,...]}. Unknown names are 0. */
    public static int cell(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        String raw = token.trim();
        int bracket = raw.indexOf('[');
        String body = bracket >= 0 ? raw.substring(0, bracket) : raw;
        String props = bracket >= 0 && raw.endsWith("]") ? raw.substring(bracket + 1, raw.length() - 1) : "";
        if (!body.contains(":")) {
            body = "minecraft:" + body;
        }
        if (!registryOk()) {
            return fallback(raw);
        }
        try {
            Optional<? extends net.minecraft.core.Holder.Reference<Block>> found = BuiltInRegistries.BLOCK.get(Identifier.parse(body));
            if (found.isEmpty()) {
                return 0;
            }
            Block block = found.get().value();
            BlockState state = block.defaultBlockState();
            if (!props.isEmpty()) {
                for (String part : props.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length != 2) {
                        continue;
                    }
                    Property<?> property = block.getStateDefinition().getProperty(kv[0]);
                    if (property != null) {
                        state = set(state, property, kv[1]);
                    }
                }
            }
            return idOf(state);
        } catch (Throwable ignored) {
            return fallback(raw);
        }
    }

    /** File name for a cell. Minecraft blocks omit the namespace. Air is {@code air}. */
    public static String name(int cell) {
        if (cell <= 0) {
            return "air";
        }
        BlockState state = stateOf(cell);
        if (state == null) {
            for (Map.Entry<String, Integer> e : FALLBACK.entrySet()) {
                if (e.getValue() == cell) {
                    return e.getKey();
                }
            }
            return "air";
        }
        try {
            Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String base = "minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString();
            if (state.equals(state.getBlock().defaultBlockState())) {
                return base;
            }
            StringBuilder sb = new StringBuilder(base).append('[');
            boolean first = true;
            for (Property<?> property : state.getProperties()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(property.getName()).append('=').append(valueName(state, property));
            }
            return sb.append(']').toString();
        } catch (Throwable ignored) {
            return "air";
        }
    }

    /** Palette label, without block-state properties. */
    public static String label(int cell) {
        String n = name(cell);
        int bracket = n.indexOf('[');
        if (bracket >= 0) {
            n = n.substring(0, bracket);
        }
        int colon = n.indexOf(':');
        if (colon >= 0) {
            n = n.substring(colon + 1);
        }
        return n.replace('_', ' ');
    }

    /** Default state of every non-air block, sorted by name. Cached. */
    public static int[] placeable() {
        if (listed) {
            return placeable;
        }
        listed = true;
        if (!registryOk()) {
            placeable = FALLBACK.values().stream().mapToInt(Integer::intValue).sorted().toArray();
            return placeable;
        }
        try {
            List<Integer> ids = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                int id = idOf(block.defaultBlockState());
                if (id > 0) {
                    ids.add(id);
                }
            }
            ids.sort(Comparator.comparing(BlockPalette::name));
            placeable = ids.stream().mapToInt(Integer::intValue).toArray();
        } catch (Throwable ignored) {
            placeable = FALLBACK.values().stream().mapToInt(Integer::intValue).sorted().toArray();
        }
        return placeable;
    }

    public static ItemStack icon(int cell) {
        BlockState state = stateOf(cell);
        if (state == null) {
            return ItemStack.EMPTY;
        }
        try {
            Item item = state.getBlock().asItem();
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** Map colour, or a stable grey when the block cannot answer without a world. */
    public static int argb(int cell) {
        if (cell <= 0) {
            return 0;
        }
        Integer cached = COLOR.get(cell);
        if (cached != null) {
            return cached;
        }
        int color = 0xFF8E8E8E;
        BlockState state = stateOf(cell);
        if (state != null) {
            try {
                int col = state.getMapColor(null, BlockPos.ZERO).col;
                if (col != 0) {
                    color = 0xFF000000 | (col & 0xFFFFFF);
                }
            } catch (Throwable ignored) {
                int h = name(cell).hashCode();
                color = 0xFF000000 | (h & 0x00FFFFFF);
            }
        }
        COLOR.put(cell, color);
        return color;
    }

    /** Darken or lighten an opaque-or-translucent colour. {@code mul} is about 1 for the lit face. */
    public static int shade(int argb, float mul) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp(((argb >>> 16) & 0xFF) * mul);
        int g = clamp(((argb >>> 8) & 0xFF) * mul);
        int b = clamp((argb & 0xFF) * mul);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int fallback(String token) {
        String n = token.trim().toLowerCase(Locale.ROOT);
        int bracket = n.indexOf('[');
        if (bracket >= 0) {
            n = n.substring(0, bracket);
        }
        if (n.startsWith("minecraft:")) {
            n = n.substring("minecraft:".length());
        }
        Integer id = FALLBACK.get(n);
        return id == null ? 0 : id;
    }

    private static boolean registryOk() {
        if (registryBroken) {
            return false;
        }
        try {
            return BuiltInRegistries.BLOCK.size() > 0;
        } catch (Throwable ignored) {
            registryBroken = true;
            return false;
        }
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            return state;
        }
        return state.setValue(property, parsed.get());
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static int clamp(float c) {
        return Math.max(0, Math.min(255, Math.round(c)));
    }
}
