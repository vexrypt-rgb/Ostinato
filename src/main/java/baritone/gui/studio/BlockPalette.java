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

/** Blocks the studio can place. Index 0 is air; a cell stores {@code index + 1}. */
public final class BlockPalette {

    public static final class Entry {
        public final String id;
        public final String label;
        public final int argb;

        Entry(String id, String label, int argb) {
            this.id = id;
            this.label = label;
            this.argb = argb;
        }
    }

    public static final Entry[] ALL = {
            new Entry("stone", "Stone", 0xFF8E8E8E),
            new Entry("cobblestone", "Cobble", 0xFF6B6B6B),
            new Entry("oak_planks", "Oak", 0xFFC4A36A),
            new Entry("spruce_planks", "Spruce", 0xFF6E5433),
            new Entry("oak_log", "Log", 0xFF6B542E),
            new Entry("bricks", "Bricks", 0xFFA35040),
            new Entry("stone_bricks", "Brick stone", 0xFF7C7C7A),
            new Entry("deepslate", "Deepslate", 0xFF3A3A42),
            new Entry("sandstone", "Sandstone", 0xFFE4D7A4),
            new Entry("quartz_block", "Quartz", 0xFFEDEAE4),
            new Entry("glass", "Glass", 0x99D0EEF4),
            new Entry("white_concrete", "White", 0xFFD0D0D0),
            new Entry("orange_concrete", "Orange", 0xFFE07B2C),
            new Entry("yellow_concrete", "Yellow", 0xFFF1C233),
            new Entry("lime_concrete", "Lime", 0xFF70C244),
            new Entry("light_blue_concrete", "Sky", 0xFF4AA3D8),
            new Entry("blue_concrete", "Blue", 0xFF2F4E9C),
            new Entry("purple_concrete", "Purple", 0xFF7B419E),
            new Entry("pink_concrete", "Pink", 0xFFE58CB2),
            new Entry("red_concrete", "Red", 0xFFB23333),
            new Entry("black_concrete", "Black", 0xFF1C1C1C),
            new Entry("iron_block", "Iron", 0xFFD9D9D9),
            new Entry("gold_block", "Gold", 0xFFF6D454),
            new Entry("obsidian", "Obsidian", 0xFF241433)
    };

    private BlockPalette() {}

    public static String name(int cell) {
        if (cell <= 0 || cell > ALL.length) {
            return "air";
        }
        return ALL[cell - 1].id;
    }

    public static int cell(String name) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(name)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static int argb(int cell) {
        if (cell <= 0 || cell > ALL.length) {
            return 0;
        }
        return ALL[cell - 1].argb;
    }

    /** Darken or lighten an opaque-or-translucent colour. {@code mul} is about 1 for the lit face. */
    public static int shade(int argb, float mul) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp(((argb >>> 16) & 0xFF) * mul);
        int g = clamp(((argb >>> 8) & 0xFF) * mul);
        int b = clamp((argb & 0xFF) * mul);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(float c) {
        return Math.max(0, Math.min(255, Math.round(c)));
    }
}
