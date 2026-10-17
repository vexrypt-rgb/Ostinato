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


package baritone.gui.render;

import baritone.Baritone;
import baritone.gui.screen.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * The {@code guiTheme} switch for everything Ostinato draws over the game: the menu and every HUD card. A HUD card
 * draws its frame, header, tiles, bars and text colours through here, so a new card follows the theme without
 * code of its own. The classic branch of each method draws exactly what the cards drew before themes existed.
 */
public final class Skin {

    /** Singularity palette: warm white text, violet-grey secondary text, Hawking amber accent. */
    public static final int S_TEXT = 0xFFEDE6DC, S_MUTED = 0xFF9A92A4, S_DIM = 0xFF6C6378, S_LABEL = 0xFFB9B0C4,
            HAWKING = 0xFFFFAE57, PHOTON = 0xFFFFF1D6, EMBER = 0xFFD24B2A, S_FIELD = 0xFF07060B, S_FIELD_BORDER = 0xFF2F2838,
            S_BORDER = 0xFF3A2C2A;

    private static final int[] HOT = {0xFFFFF8EC, 0xFFFFF1D6, 0xFFFFE1A8, 0xFFFFC87A, HAWKING, 0xFFFF8F45, 0xFFF06A32, EMBER};

    private Skin() {}

    /** Pure parse of the setting, kept apart for tests. */
    public static boolean isSingularity(String theme) {
        return theme != null && theme.trim().toLowerCase(Locale.ROOT).equals("singularity");
    }

    public static boolean singularity() {
        return isSingularity(Baritone.settings().guiTheme.value);
    }

    public static int text() {
        return singularity() ? S_TEXT : Theme.TEXT;
    }

    public static int muted() {
        return singularity() ? S_MUTED : Theme.MUTED;
    }

    public static int dim() {
        return singularity() ? S_DIM : Theme.DIM;
    }

    /** Small-caps labels on the HUD cards. */
    public static int label(int classic) {
        return singularity() ? S_LABEL : classic;
    }

    /** Accretion temperature ramp, white-hot to ember, spread over n characters. */
    public static int[] hotColors(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0 : i / (float) (n - 1) * (HOT.length - 1);
            int a = Math.min(HOT.length - 1, (int) t);
            out[i] = GuiDraw.lerp(HOT[a], HOT[Math.min(HOT.length - 1, a + 1)], t - a);
        }
        return out;
    }

    // ---------------------------------------------------------------- HUD chrome

    /**
     * Card background. Classic: the given rim and fill colours, an optional drop shadow, and an accent hairline
     * along the top. Singularity: void glass of the same opacity, a warm rim, and a photon ring along the top edge.
     */
    public static void card(GuiGraphicsExtractor ms, float x0, float y0, float x1, float y1, int accent, int rim, int top, int bottom, boolean shadow) {
        if (!singularity()) {
            if (shadow) {
                GuiDraw.shadow(ms, x0, y0, x1, y1, 4, 6, 0x60);
            }
            GuiDraw.roundBorder(ms, x0, y0, x1, y1, 4, rim, top, bottom);
            GuiDraw.gradH(ms, x0 + 4, y0 + 0.5f, x1 - 4, y0 + 1, GuiDraw.alphaOf(accent, 0xAA), GuiDraw.alphaOf(Theme.BLUE, 0x10));
            return;
        }
        int fa = Math.max((top >>> 24) & 0xFF, (bottom >>> 24) & 0xFF), ra = (rim >>> 24) & 0xFF;
        if (shadow) {
            GuiDraw.shadow(ms, x0, y0, x1, y1, 3, 6, 0x70);
        }
        GuiDraw.roundBorder(ms, x0, y0, x1, y1, 3, GuiDraw.alphaOf(S_BORDER, Math.max(ra, Math.min(0xC0, fa))),
                GuiDraw.alphaOf(0x0A0910, fa), GuiDraw.alphaOf(0x040307, fa));
        // the side facing the core is warmed by the disk
        GuiDraw.gradH(ms, x0 + 0.5f, y0 + 3, x0 + Math.min(40, (x1 - x0) / 3), y1 - 3, GuiDraw.alphaOf(0xFF8A3C, Math.round(0x1C * fa / 255f)), 0x00FF8A3C);
        photonLine(ms, x0 + 3, x1 - 3, y0 + 0.5f, accent);
    }

    /** A hairline that is white-hot in the middle and cools to amber and nothing at the ends. */
    public static void photonLine(GuiGraphicsExtractor ms, float x0, float x1, float y, int accent) {
        float m = (x0 + x1) / 2;
        GuiDraw.gradH(ms, x0, y, m, y + 0.5f, GuiDraw.alphaOf(accent, 0), GuiDraw.alphaOf(PHOTON, 0xE0));
        GuiDraw.gradH(ms, m, y, x1, y + 0.5f, GuiDraw.alphaOf(PHOTON, 0xE0), GuiDraw.alphaOf(EMBER, 0));
    }

    /** Header mark and title: the note icon and accent-to-violet title, or a tiny black hole and a hot title. */
    public static void title(GuiGraphicsExtractor ms, String title, float x, float y, int accent, long now) {
        if (!singularity()) {
            GuiDraw.icon(ms, Icons.NOTE, x, y, accent, 0.8f);
            GuiDraw.textColors(ms, title, x + 10, y, 1f, true, Theme.logoColors(accent, title.length()), true);
            return;
        }
        SingularityArt.glyph(ms, x + 3.5f, y + 3.5f, 5.5f, now);
        GuiDraw.textColors(ms, title, x + 10, y, 1f, true, hotColors(title.length()), true);
    }

    public static void divider(GuiGraphicsExtractor ms, float x0, float x1, float y, int classic) {
        GuiDraw.rect(ms, x0, y, x1, y + 0.5f, singularity() ? 0x1CFFB070 : classic);
    }

    /** Stat tile background; classic keeps the caller's fill. */
    public static void tile(GuiGraphicsExtractor ms, float x0, float y0, float x1, float y1, int classic) {
        if (!singularity()) {
            GuiDraw.round(ms, x0, y0, x1, y1, 2.5f, classic);
            return;
        }
        int a = Math.max(0x10, (classic >>> 24) & 0xFF);
        GuiDraw.round(ms, x0, y0, x1, y1, 2, GuiDraw.alphaOf(0x1A1420, Math.min(0xFF, a * 2)));
        GuiDraw.rect(ms, x0 + 0.5f, y0 + 2, x0 + 1, y1 - 2, GuiDraw.alphaOf(HAWKING, 0x50));
    }

    /**
     * Progress bar over a track. Classic: a solid track and an accent bar shaded top to bottom. Singularity: the
     * bar is the accretion ramp, ember at the start to white-hot at the head.
     */
    public static void bar(GuiGraphicsExtractor ms, float x0, float y0, float x1, float y1, float p, int color, int track) {
        float r = (y1 - y0) / 2;
        float head = x0 + Math.max(y1 - y0, (x1 - x0) * Math.max(0, Math.min(1, p)));
        if (!singularity()) {
            GuiDraw.round(ms, x0, y0, x1, y1, r, track);
            if (p > 0) {
                GuiDraw.round(ms, x0, y0, head, y1, r, Theme.lighter(color), Theme.darker(color));
            }
            return;
        }
        GuiDraw.round(ms, x0, y0, x1, y1, r, 0xFF1A1420);
        if (p > 0) {
            GuiDraw.gradH(ms, x0, y0, head, y1, GuiDraw.lerp(EMBER, color, 0.3f), GuiDraw.lerp(color, PHOTON, 0.55f));
            GuiDraw.rect(ms, head - 0.5f, y0 - 0.5f, head, y1 + 0.5f, PHOTON);
        }
    }

    /** Pill behind a short label such as a state or a mover name. */
    public static void pill(GuiGraphicsExtractor ms, float x0, float y0, float x1, float y1, int color, int alpha) {
        if (!singularity()) {
            GuiDraw.round(ms, x0, y0, x1, y1, 2.5f, GuiDraw.alphaOf(color, alpha));
            return;
        }
        GuiDraw.roundBorder(ms, x0, y0, x1, y1, 2, GuiDraw.alphaOf(color, Math.min(0xFF, alpha * 2)), GuiDraw.alphaOf(0x0A0910, 0xC0));
    }

    /** Plain panel fill used by HUDs that are not cards (the freecam inventory), w by h from (x, y) floored. */
    public static void panel(GuiGraphicsExtractor ms, float x, float y, int w, int h, int classic) {
        int x0 = (int) x, y0 = (int) y;
        if (!singularity()) {
            ms.fill(x0, y0, x0 + w, y0 + h, classic);
            return;
        }
        ms.fill(x0, y0, x0 + w, y0 + h, GuiDraw.alphaOf(0x07060C, (classic >>> 24) & 0xFF));
        ms.fill(x0, y0, x0 + w, y0 + 1, GuiDraw.alphaOf(S_BORDER, 0xFF));
        photonLine(ms, x0 + 2, x0 + w - 2, y0, Theme.accent());
    }

    /** Item cell of a HUD panel; the selected one glows. */
    public static int cell(boolean selected, int classicSelected, int classicNormal) {
        if (!singularity()) {
            return selected ? classicSelected : classicNormal;
        }
        return selected ? 0xE05A3416 : 0xA0141019;
    }
}
