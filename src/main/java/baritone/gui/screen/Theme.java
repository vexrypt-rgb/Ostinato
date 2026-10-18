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


package baritone.gui.screen;

import baritone.Baritone;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;

import java.awt.Color;

/** Palette shared with TenorClef's T2 menu (dark glass, teal accent). The accent comes from guiAccentColor. */
public final class Theme {

    public static final int TEXT = 0xFFE6EAF0, MUTED = 0xFF8B93A3, DIM = 0xFF5C6475, AMBER = 0xFFE8C96A,
            DANGER = 0xFFE5534B, GREEN = 0xFF5BE38A, BLUE = 0xFF7AA2F7, VIOLET = 0xFFBB9AF7, FIELD = 0xFF0B0E14,
            FIELD_BORDER = 0xFF2E3647;

    private Theme() {}

    public static int accent() {
        Color c = Baritone.settings().guiAccentColor.value;
        int rgb = c == null ? 0xFF4FD1C5 : (0xFF000000 | (c.getRGB() & 0xFFFFFF));
        // the singularity theme brings its own Hawking amber unless the accent was changed by hand
        return rgb == 0xFF4FD1C5 && Skin.singularity() ? Skin.HAWKING : rgb;
    }

    public static int lighter(int c) {
        return GuiDraw.lerp(c, 0xFFFFFFFF, 0.12f);
    }

    public static int darker(int c) {
        return GuiDraw.lerp(c, 0xFF000000, 0.2f);
    }

    /** accent -> blue -> violet across n characters */
    public static int[] logoColors(int accent, int n) {
        if (Skin.singularity()) {
            return Skin.hotColors(n);
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0 : i / (float) (n - 1);
            out[i] = t < 0.5f ? GuiDraw.lerp(accent, BLUE, t * 2) : GuiDraw.lerp(BLUE, VIOLET, (t - 0.5f) * 2);
        }
        return out;
    }
}
