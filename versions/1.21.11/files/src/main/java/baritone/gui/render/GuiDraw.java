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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every draw call of the Ostinato GUI goes through here, and only vanilla facilities are used: coloured
 * quads through GuiGraphics.drawSpecial, the vanilla font, item icons and GL scissor. Rounded corners are stepped fills
 * at screen-pixel resolution (no textures, no anti-aliasing). Coordinates are GUI units and may be fractional.
 */
public final class GuiDraw {

    /** Multiplies the alpha of everything drawn; used for the open fade. */
    public static float alpha = 1f;

    private GuiDraw() {}

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static Font font() {
        return mc().font;
    }

    public static double guiScale() {
        return mc().getWindow().getGuiScale();
    }

    public static int withAlpha(int argb) {
        if (alpha >= 1f) {
            return argb;
        }
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int s = 0; s <= 24; s += 8) {
            int x = (a >>> s) & 0xFF, y = (b >>> s) & 0xFF;
            out |= (Math.round(x + (y - x) * t) & 0xFF) << s;
        }
        return out;
    }

    public static int alphaOf(int argb, int newAlpha) {
        return (newAlpha << 24) | (argb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- quads

    /** Last GuiGraphics handed to a draw call; item icons and scissor use it. */
    private static GuiGraphics cur;

    private static void quads(GuiGraphics g, Runnable q) {
        cur = g;
        q.run();
    }

    /** Fractional-coordinate fill: the pose matrix carries the sub-pixel placement. */
    private static void quad(GuiGraphics g, float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        tl = withAlpha(tl);
        tr = withAlpha(tr);
        br = withAlpha(br);
        bl = withAlpha(bl);
        g.pose().pushMatrix();
        g.pose().translate(x1, y1);
        g.pose().scale(x2 - x1, y2 - y1);
        if (tl == tr && bl == br) {
            if (tl == bl) {
                g.fill(0, 0, 1, 1, tl);
            } else {
                g.fillGradient(0, 0, 1, 1, tl, bl);
            }
        } else {
            int n = 16; // horizontal gradient: vertical strips
            for (int i = 0; i < n; i++) {
                int c = lerp(tl, tr, (i + 0.5f) / n), cb = lerp(bl, br, (i + 0.5f) / n);
                g.pose().pushMatrix();
                g.pose().translate(i / (float) n, 0);
                g.pose().scale(1f / n, 1f);
                if (c == cb) {
                    g.fill(0, 0, 1, 1, c);
                } else {
                    g.fillGradient(0, 0, 1, 1, c, cb);
                }
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    public static void rect(GuiGraphics ms, float x1, float y1, float x2, float y2, int argb) {
        gradient(ms, x1, y1, x2, y2, argb, argb, argb, argb);
    }

    public static void gradV(GuiGraphics ms, float x1, float y1, float x2, float y2, int top, int bottom) {
        gradient(ms, x1, y1, x2, y2, top, top, bottom, bottom);
    }

    public static void gradH(GuiGraphics ms, float x1, float y1, float x2, float y2, int left, int right) {
        gradient(ms, x1, y1, x2, y2, left, right, right, left);
    }

    private static void gradient(GuiGraphics ms, float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        quads(ms, () -> quad(ms, x1, y1, x2, y2, tl, tr, br, bl));
    }

    /** Rounded rectangle, solid. */
    public static void round(GuiGraphics ms, float x1, float y1, float x2, float y2, float r, int argb) {
        round(ms, x1, y1, x2, y2, r, argb, argb);
    }

    /**
     * Rounded rectangle with a vertical gradient. Corners are cut per screen-pixel row, so they stay crisp at
     * any GUI scale.
     */
    public static void round(GuiGraphics ms, float x1, float y1, float x2, float y2, float r, int top, int bottom) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        double scale = guiScale();
        float px = (float) (1.0 / scale);
        int rp = (int) Math.round(Math.min(r, Math.min(x2 - x1, y2 - y1) / 2f) * scale);
        float h = y2 - y1;
        quads(ms, () -> {
        for (int i = 0; i < rp; i++) {
            double dy = rp - i - 0.5;
            float ins = (float) Math.round(rp - Math.sqrt(Math.max(0, rp * rp - dy * dy))) * px;
            float ta = y1 + i * px, tb = ta + px;
            int c1 = lerp(top, bottom, (ta - y1) / h), c2 = lerp(top, bottom, (tb - y1) / h);
            quad(ms, x1 + ins, ta, x2 - ins, tb, c1, c1, c2, c2);
            float ba = y2 - (i + 1) * px, bbv = ba + px;
            int c3 = lerp(top, bottom, (ba - y1) / h), c4 = lerp(top, bottom, (bbv - y1) / h);
            quad(ms, x1 + ins, ba, x2 - ins, bbv, c3, c3, c4, c4);
        }
        float my1 = y1 + rp * px, my2 = y2 - rp * px;
        int cm1 = lerp(top, bottom, (my1 - y1) / h), cm2 = lerp(top, bottom, (my2 - y1) / h);
        quad(ms, x1, my1, x2, my2, cm1, cm1, cm2, cm2);
        });
    }

    /** 1-screen-pixel-ish border (half a GUI unit) around a filled rounded rect. */
    public static void roundBorder(GuiGraphics ms, float x1, float y1, float x2, float y2, float r, int border, int top, int bottom) {
        round(ms, x1, y1, x2, y2, r, border);
        round(ms, x1 + 0.5f, y1 + 0.5f, x2 - 0.5f, y2 - 0.5f, Math.max(0, r - 0.5f), top, bottom);
    }

    public static void roundBorder(GuiGraphics ms, float x1, float y1, float x2, float y2, float r, int border, int fill) {
        roundBorder(ms, x1, y1, x2, y2, r, border, fill, fill);
    }

    /** Soft drop shadow from stacked low-alpha rounded rects. */
    public static void shadow(GuiGraphics ms, float x1, float y1, float x2, float y2, float r, int size, int maxAlpha) {
        for (int i = size; i > 0; i--) {
            float f = 1f - i / (float) (size + 1);
            int a = Math.round(maxAlpha * f * f / 2.2f);
            round(ms, x1 - i, y1 - i + 2, x2 + i, y2 + i + 3, r + i, a << 24);
        }
    }

    public static void icon(GuiGraphics ms, String[] rows, float x, float y, int argb, float k) {
        quads(ms, () -> {
        for (int j = 0; j < rows.length; j++) {
            String row = rows[j];
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) == '#') {
                    quad(ms, x + i * k, y + j * k, x + (i + 1) * k, y + (j + 1) * k, argb, argb, argb, argb);
                }
            }
        }
        });
    }

    // ---------------------------------------------------------------- text

    private static final String BOLD = "\u00a7l";

    public static int width(String s) {
        return font().width(s);
    }

    public static float width(String s, float k, boolean bold) {
        return font().width(bold ? BOLD + s : s) * k;
    }

    /** Draws text; returns its width in GUI units. */
    public static float text(GuiGraphics ms, String s, float x, float y, int argb, boolean shadow) {
        return text(ms, s, x, y, 1f, argb, shadow, false);
    }

    public static float text(GuiGraphics ms, String s, float x, float y, float k, int argb, boolean shadow, boolean bold) {
        int c = withAlpha(argb);
        String str = bold ? BOLD + s : s;
        if (((c >>> 24) & 0xFF) >= 6) { // the font renders alpha < 4 as opaque
            cur = ms;
            ms.pose().pushMatrix();
            ms.pose().translate(x, y);
            if (k != 1f) {
                ms.pose().scale(k, k);
            }
            ms.drawString(font(), str, 0, 0, c, shadow);
            ms.pose().popMatrix();
        }
        return font().width(str) * k;
    }

    /** Per-character colours (used for the gradient logo). */
    public static float textColors(GuiGraphics ms, String s, float x, float y, float k, boolean bold, int[] colors, boolean shadow) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int col = colors[Math.min(colors.length - 1, i)];
            cx += text(ms, ch, cx, y, k, col, shadow, bold);
        }
        return cx - x;
    }

    public static String trim(String s, float maxW, float k) {
        if (width(s, k, false) <= maxW) {
            return s;
        }
        String t = s;
        while (!t.isEmpty() && width(t + "...", k, false) > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "...";
    }

    public static List<String> wrap(String s, float maxW, float k) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (width(t, k, false) > maxW && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            } else {
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- items + scissor

    /** Item icon at (x, y) drawn {@code size} GUI units wide. */
    public static void item(ItemStack stack, float x, float y, float size) {
        if (cur == null) {
            return;
        }
        cur.pose().pushMatrix();
        cur.pose().translate(x, y);
        cur.pose().scale(size / 16f, size / 16f);
        cur.renderItem(stack, 0, 0);
        cur.pose().popMatrix();
    }

    /** Clip to a GUI-space rectangle. */
    private static GuiGraphics scissorOwner;
    private static int scissorDepth;

    public static void scissor(float x1, float y1, float x2, float y2) {
        if (cur != null) {
            if (scissorOwner != cur) {
                scissorOwner = cur;
                scissorDepth = 0;
            }
            scissorDepth++;
            cur.enableScissor((int) Math.floor(x1), (int) Math.floor(y1), (int) Math.ceil(x2), (int) Math.ceil(y2));
        }
    }

    /** GuiGraphics throws on an unbalanced pop, where GL scissor was forgiving; only pop what we pushed. */
    public static void endScissor() {
        if (cur != null && cur == scissorOwner && scissorDepth > 0) {
            scissorDepth--;
            cur.disableScissor();
        }
    }
}
