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

import baritone.gui.anim.Anim;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Icons;
import baritone.gui.render.SingularityArt;
import baritone.gui.render.Skin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The singularity theme's navigation: the menu's desks and categories as bodies on an arc around a black hole at
 * the left edge. Hovering lenses the arc like a dock (the hovered body grows and its neighbours are pushed apart),
 * but clicks are tested against the fixed slots, so nothing moves out from under the cursor. Opening flings the
 * bodies out of the core along a spiral; closing pulls them back in.
 */
final class Orbit {

    static final class Body {
        final String label;
        final int count;
        final ItemStack icon;
        final boolean special, live, gapBefore;

        Body(String label, int count, ItemStack icon, boolean special, boolean live, boolean gapBefore) {
            this.label = label;
            this.count = count;
            this.icon = icon;
            this.special = special;
            this.live = live;
            this.gapBefore = gapBefore;
        }
    }

    float cx, cy, rs, step, ro;
    boolean labels;
    private float[] sx = new float[0], sy = new float[0];
    private final List<Anim> hover = new ArrayList<>();

    /** Places n bodies (some after a short gap) on the arc for a screen w by h. */
    void layout(List<Body> bodies, float w, float h) {
        int n = bodies.size(), gaps = 0;
        for (Body b : bodies) {
            if (b.gapBefore) {
                gaps++;
            }
        }
        cx = 30;
        cy = h / 2;
        rs = Math.min(40, h * 0.11f);
        step = Math.min(18, (h - 46) / Math.max(1, n + gaps * 0.35f));
        float gapStep = step * 0.35f, span = n * step + gaps * gapStep;
        ro = Math.max(rs * SingularityArt.SPAN + 16, span / 2 + 8);
        // leave the panel at least as wide as the classic menu's narrowest: drop the labels, then slide the core
        // partly off the left edge, which flattens the arc
        float room = w - 10 - 340;
        labels = w >= 520 && cx + ro + 92 <= room;
        if (!labels && cx + ro + 22 > room) {
            cx = Math.max(-rs * 0.8f, room - 22 - ro);
        }
        sx = new float[n];
        sy = new float[n];
        float y = cy - span / 2 + step / 2;
        for (int i = 0; i < n; i++) {
            if (bodies.get(i).gapBefore) {
                y += gapStep;
            }
            float dy = y - cy;
            sx[i] = cx + (float) Math.sqrt(Math.max(0, ro * ro - dy * dy));
            sy[i] = y;
            y += step;
        }
        while (hover.size() < n) {
            hover.add(new Anim(0, 16));
        }
    }

    /** Left edge for the panel beside the arc and its labels. */
    float panelX0() {
        return cx + ro + (labels ? 92 : 22);
    }

    int slotAt(double mx, double my) {
        for (int i = 0; i < sx.length; i++) {
            if (Math.abs(my - sy[i]) <= step / 2 && mx > sx[i] - 10 && mx < (labels ? sx[i] + 88 : sx[i] + 10)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Draws the core and the bodies. {@code open} runs 0 to 1 as the menu opens and back to 0 as it closes;
     * {@code closing} turns the outward spiral into an inward one.
     */
    void draw(GuiGraphicsExtractor ms, List<Body> bodies, int sel, int mx, int my, float open, boolean closing, int accent, long now) {
        int n = Math.min(bodies.size(), sx.length);
        int hv = slotAt(mx, my);
        float[] hov = new float[n];
        for (int i = 0; i < n; i++) {
            hov[i] = hover.get(i).target(i == hv).get();
        }
        float eo = ease(open);
        // the core grows out of nothing as the menu opens
        SingularityArt.hole(ms, cx, cy, rs * (0.55f + 0.45f * eo), eo, now);
        // the orbit, as a faint dotted arc
        float rg = ro * eo;
        for (float t = -1.45f; t <= 1.45f; t += 3.2f / Math.max(8, rg)) {
            float x = cx + (float) Math.cos(t) * rg, y = cy + (float) Math.sin(t) * rg;
            GuiDraw.rect(ms, x - 0.25f, y - 0.25f, x + 0.25f, y + 0.25f, GuiDraw.alphaOf(Skin.HAWKING, Math.round(0x30 * eo)));
        }
        float base = GuiDraw.alpha;
        try {
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                float dy = 0, push = 0, mag = 0;
                for (int j = 0; j < n; j++) {
                    if (hov[j] < 0.01f) {
                        continue;
                    }
                    float d = i - j;
                    mag += hov[j] * Math.exp(-d * d * 0.9f);
                    push += hov[j] * 7 * Math.exp(-d * d * 0.5f);
                    dy += hov[j] * Math.signum(d) * 5 * Math.exp(-d * d * 0.35f);
                }
                boolean s = i == sel;
                float lt = (open - i * 0.025f) / 0.62f, ti = ease(lt);
                // aim in polar form around the core so the motion spirals
                double ang = Math.atan2(sy[i] + dy - cy, sx[i] - cx), rad = Math.hypot(sx[i] - cx, sy[i] + dy - cy) + push + (s ? 5 : 0);
                ang += (1 - ti) * (closing ? -1.6 : 1.9);
                rad *= closing ? ti : easeBack(lt);
                float x = cx + (float) (Math.cos(ang) * rad), y = cy + (float) (Math.sin(ang) * rad);
                if (ti < 0.02f) {
                    continue;
                }
                GuiDraw.alpha = base * ti;
                float z = 1 + 0.45f * mag + (s ? 0.15f : 0), r = Math.min(6.2f, step * 0.42f) * z;
                if (s) { // tether to the core and a Hawking glow
                    double ca = Math.cos(ang), sa = Math.sin(ang);
                    float r0 = rs * 1.2f, r1 = (float) rad - r - 2;
                    for (int k = 0; k <= 16; k++) {
                        float f = k / 16f, rr = r0 + (r1 - r0) * f;
                        float tx = cx + (float) (ca * rr), ty = cy + (float) (sa * rr);
                        GuiDraw.rect(ms, tx - 0.3f, ty - 0.3f, tx + 0.3f, ty + 0.3f, GuiDraw.alphaOf(Skin.HAWKING, Math.round(0x90 * f)));
                    }
                    for (int k = 3; k >= 1; k--) {
                        float gr = r + 3.5f * k;
                        GuiDraw.round(ms, x - gr, y - gr, x + gr, y + gr, gr, GuiDraw.alphaOf(0xFF8A3C, 0x16));
                    }
                }
                int ring = s ? accent : GuiDraw.lerp(0xFF3A3346, 0xFFD9C7FF, Math.min(1, mag));
                GuiDraw.round(ms, x - r - 0.6f, y - r - 0.6f, x + r + 0.6f, y + r + 0.6f, r + 0.6f, ring);
                GuiDraw.round(ms, x - r, y - r, x + r, y + r, r, s ? 0xFF2A1A10 : 0xFF120F18);
                if (b.special) {
                    GuiDraw.icon(ms, Icons.DOT, x - 1.5f * z, y - 1.5f * z, Theme.AMBER, z);
                } else if (b.icon != null && ti > 0.6f) { // items draw opaque, so they wait until the body is nearly in
                    float is = r * 1.45f;
                    GuiDraw.item(b.icon, x - is / 2, y - is / 2, is);
                }
                if (b.live) {
                    GuiDraw.round(ms, x + r * 0.5f, y - r, x + r * 0.5f + 3, y - r + 3, 1.5f, Theme.GREEN);
                }
                if (labels || s || mag > 0.3f) {
                    float k = (s ? 1f : 0.9f) * (1 + 0.18f * Math.min(1, mag)), lx = x + r + 4;
                    int lc = s ? Skin.PHOTON : GuiDraw.lerp(Skin.S_LABEL, 0xFFFFFFFF, Math.min(1, mag));
                    float lw = GuiDraw.text(ms, b.label, lx, y - 4 * k, k, lc, true, s);
                    GuiDraw.text(ms, String.valueOf(b.count), lx + lw + 3, y - 1.5f, 0.5f, s ? accent : Skin.S_DIM, false, false);
                }
            }
        } finally {
            GuiDraw.alpha = base;
        }
    }

    static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Overshoots a little, so a body flung out settles back onto its orbit. */
    static float easeBack(float t) {
        t = Math.max(0, Math.min(1, t));
        float c = 1.5f, u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    }
}
