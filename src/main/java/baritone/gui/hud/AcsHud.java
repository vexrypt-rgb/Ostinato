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

package baritone.gui.hud;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.gui.anim.Anim;
import baritone.gui.model.HudFormat;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;
import baritone.gui.render.Icons;
import baritone.gui.screen.OstinatoScreen;
import baritone.gui.screen.Theme;
import baritone.process.AcsStatus;
import baritone.process.PvpProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * Autotune Combat System card drawn over the in-game HUD (hooked from MixinIngameGui) while {@code #pvp} is
 * fighting, then fades out 3 s after: the value being tuned, where the tuner stands on it, and what the trial under
 * way has shown so far. The background is see-through by {@code acsHudOpacity}; every text is drawn with a shadow so
 * it reads over whatever is behind it.
 */
public final class AcsHud {

    public static final int W = 132, H = 96, H_OFF = 65, H_WHOLE = 8;
    /** Singularity: height of the dome, the orbit of values the tuner can stand on, above the text rows. */
    private static final float DOME = 40, ORBIT_RX = 50, ORBIT_RY = 30;
    private static final int LABEL = 0xFFAEB7C7, RAIL = 0x50FFFFFF;
    private static final Anim FADE = new Anim(0, 10);
    /** Scale of the small print: half the card's unit, rounded so that a font pixel is a whole number of screen pixels. */
    private static float fine = 0.5f;
    private static long lastActive;
    private static AcsStatus last;
    private static boolean failed;

    private AcsHud() {}

    public static void render(GuiGraphicsExtractor ms) {
        if (failed) {
            return;
        }
        try {
            render0(ms);
        } catch (Throwable t) {
            failed = true; // never take the game down over a HUD
            t.printStackTrace();
        } finally {
            GuiDraw.alpha = 1f;
        }
    }

    private static void render0(GuiGraphicsExtractor ms) {
        Minecraft mc = Minecraft.getInstance();
        IBaritone ib = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (!Baritone.settings().renderAcsHud.value || mc.getDebugOverlay().showDebugScreen() || mc.player == null
                || mc.gui.screen() instanceof OstinatoScreen || !(ib instanceof Baritone)) {
            FADE.snap(0);
            return;
        }
        PvpProcess pvp = ((Baritone) ib).getPvpProcess();
        long now = System.currentTimeMillis();
        if (pvp.isActive()) {
            AcsStatus st = pvp.acs();
            if (st.fighting) {
                lastActive = now;
                last = st;
            }
        }
        float a = FADE.target(last != null && now - lastActive < 3000).get();
        if (a <= 0.01f || last == null) {
            return;
        }
        GuiDraw.alpha = a;
        // drawn at its own pixel scale, so a large GUI scale does not make it cover a large part of the screen
        double gui = mc.getWindow().getGuiScale();
        int px = Baritone.settings().acsHudScale.value;
        if (px <= 0) {
            px = HudFormat.cardScale(mc.getWindow().getWidth(), W, (int) Math.round(gui));
        }
        fine = HudFormat.finePrint(px);
        float k = (float) (px / gui);
        float sw = mc.getWindow().getGuiScaledWidth() / k, sh = mc.getWindow().getGuiScaledHeight() / k;
        String anchor = Baritone.settings().acsHudAnchor.value.trim().toUpperCase(Locale.ROOT);
        float h = height(last), x0, y0;
        switch (anchor) {
            case "LEFT":
                x0 = 6;
                y0 = Math.max(6, sh / 2f - h / 2f);
                break;
            case "TOP_RIGHT":
                x0 = sw - 6 - W;
                y0 = 6;
                break;
            case "RIGHT": // below the potion-icon / toast strip
                x0 = sw - 6 - W;
                y0 = Math.min(58 / k, Math.max(6, sh - h - 60));
                break;
            default: // TOP_LEFT
                anchor = "TOP_LEFT";
                x0 = 6;
                y0 = 6;
        }
        // sharing a corner with the path status card: sit under it
        if (Baritone.settings().renderPathHud.value && anchor.equals(Baritone.settings().pathHudAnchor.value.trim().toUpperCase(Locale.ROOT))) {
            y0 += (PathStatusHud.stackHeight() + 14) / k;
        }
        if (y0 + h > sh - 4) {
            y0 = Math.max(4, sh - 4 - h);
        }
        ms.pose().pushMatrix();
        ms.pose().scale(k, k);
        try {
            draw(ms, last, x0, y0, Theme.accent(), Baritone.settings().acsHudOpacity.value, now);
        } finally {
            ms.pose().popMatrix();
        }
    }

    static float height(AcsStatus st) {
        if (Skin.singularity()) {
            return DOME + 51 + 9.5f + (st.phase == AcsStatus.Phase.OFF ? 0 : 7.5f) + (st.whole().isEmpty() ? 0 : 7.5f);
        }
        return st.phase == AcsStatus.Phase.OFF ? H_OFF : st.whole().isEmpty() ? H : H + H_WHOLE;
    }

    static void draw(GuiGraphicsExtractor ms, AcsStatus st, float x0, float y0, int accent, float opacity, long now) {
        if (Skin.singularity()) {
            dome(ms, st, x0, y0, accent, opacity, now);
            return;
        }
        boolean on = st.phase != AcsStatus.Phase.OFF, trial = st.phase == AcsStatus.Phase.TRIAL;
        float x1 = x0 + W, y1 = y0 + height(st), bx0 = x0 + 7, bx1 = x1 - 7;
        // glass: a faint light rim under a dark fill, the two together as solid as the setting asks
        float o = Math.max(0f, Math.min(1f, opacity)), rim = Math.min(0.2f, o * 0.5f), fill = 1 - (1 - o) / (1 - rim);
        Skin.card(ms, x0, y0, x1, y1, accent, GuiDraw.alphaOf(0x8FA3C8, Math.round(rim * 255)),
                GuiDraw.alphaOf(0x141925, Math.round(fill * 255)), GuiDraw.alphaOf(0x0B0E14, Math.round(fill * 255)), false);
        // header
        Skin.title(ms, "Autotune", x0 + 7, y0 + 6, accent, now);
        String state = st.phase.name();
        int stateCol = trial ? Theme.AMBER : on ? Theme.GREEN : Skin.muted();
        float pw = GuiDraw.width(state, fine, false) + 12;
        Skin.pill(ms, x1 - 7 - pw, y0 + 5.5f, x1 - 7, y0 + 14.5f, stateCol, 0x3A);
        GuiDraw.icon(ms, Icons.DOT, x1 - 7 - pw + 3.5f, y0 + 8.5f, stateCol, 0.75f);
        GuiDraw.text(ms, state, x1 - 7 - pw + 8.5f, y0 + 8, fine, stateCol, true, false);
        Skin.divider(ms, x0 + 6, x1 - 6, y0 + 19, 0x20FFFFFF);
        // what is tuned, and for which kit against which kind of foe
        float y = y0 + 22.5f;
        float nw = GuiDraw.text(ms, st.title(), bx0, y, fine, Theme.lighter(accent), true, false);
        String kit = GuiDraw.trim(st.kit(), bx1 - bx0 - nw - 6, fine);
        GuiDraw.text(ms, kit, bx1 - GuiDraw.width(kit, fine, false), y, fine, Skin.label(LABEL), true, false);
        y += 8;
        if (on) {
            if (st.knob >= 0) track(ms, st, bx0 + 4, bx1 - 4, y + 5.5f, accent, now);
            y += 18.5f;
        }
        // what the fights are worth: the candidate against the trusted value, or the value in force by itself
        float tw = (bx1 - bx0 - 4) / 2;
        if (trial) {
            tile(ms, bx0, y, tw, o, "DEALT", HudFormat.signedPct(st.dealtPct), tone(st.zDealt, true), st.fought < 2 ? "" : z(st.zDealt));
            tile(ms, bx0 + tw + 4, y, tw, o, "TAKEN", HudFormat.signedPct(st.takenPct), tone(st.zTaken, false), st.fought < 2 ? "" : z(st.zTaken));
        } else {
            tile(ms, bx0, y, tw, o, "DEALT /S", st.windows > 0 ? HudFormat.perSecond(st.dealtRate) : "--", Skin.text(), "");
            tile(ms, bx0 + tw + 4, y, tw, o, "TAKEN /S", st.windows > 0 ? HudFormat.perSecond(st.takenRate) : "--", Skin.text(), "");
        }
        y += 23.5f;
        if (on) {
            float p = st.progress();
            Skin.bar(ms, bx0, y, bx1, y + 2.5f, p, trial ? Theme.AMBER : accent, 0x38FFFFFF);
            y += 5.5f;
        }
        GuiDraw.text(ms, GuiDraw.trim(st.caption(), bx1 - bx0, fine), bx0, y, fine, Skin.text(), true, false);
        if (on) {
            y += 7.5f;
            float sw = GuiDraw.text(ms, st.score(), bx0, y, fine, Skin.label(LABEL), true, false);
            int against = st.last.indexOf(" against");
            String lastLine = GuiDraw.trim(st.last.isEmpty() ? "" : "last: " + (against < 0 ? st.last : st.last.substring(0, against)), bx1 - bx0 - sw - 6, fine);
            GuiDraw.text(ms, lastLine, bx1 - GuiDraw.width(lastLine, fine, false), y, fine, Skin.muted(), true, false);
            String whole = st.whole();
            if (!whole.isEmpty()) { // the numbers judged by whole fights
                y += 7.5f;
                GuiDraw.text(ms, GuiDraw.trim(whole, bx1 - bx0, fine), bx0, y, fine, st.wholeTrial ? Theme.AMBER : Skin.label(LABEL), true, false);
            }
        }
        // the window being fought right now, as a hairline along the bottom edge
        if (st.windowSize > 0 && st.windowTicks > 0) {
            float wp = Math.min(1f, st.windowTicks / (float) st.windowSize);
            GuiDraw.rect(ms, x0 + 4, y1 - 1.5f, x0 + 4 + (W - 8) * wp, y1 - 1, GuiDraw.alphaOf(accent, 0xB0));
        }
    }

    /** The values the tuner can stand on, as stops on a rail: the baseline, the trusted one, the one on trial, the one in force. */
    private static void track(GuiGraphicsExtractor ms, AcsStatus st, float tx0, float tx1, float cy, int accent, long now) {
        int[] stops = st.stops();
        int n = stops.length;
        GuiDraw.rect(ms, tx0, cy - 0.25f, tx1, cy + 0.25f, RAIL);
        float xt = Float.NaN, xc = Float.NaN;
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (tx0 + tx1) / 2 : tx0 + (tx1 - tx0) * i / (n - 1);
            if (stops[i] == st.trusted) xt = x;
            if (stops[i] == st.candidate) xc = x;
        }
        boolean trial = st.candidate != st.trusted && !Float.isNaN(xt) && !Float.isNaN(xc);
        if (trial) { // the step on trial
            GuiDraw.gradH(ms, Math.min(xt, xc), cy - 0.5f, Math.max(xt, xc), cy + 0.5f, xt < xc ? accent : Theme.AMBER, xt < xc ? Theme.AMBER : accent);
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(now % 1200L / 1200.0 * 2 * Math.PI);
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (tx0 + tx1) / 2 : tx0 + (tx1 - tx0) * i / (n - 1);
            int v = stops[i], col = Skin.label(LABEL);
            if (v == st.base) {
                GuiDraw.rect(ms, x - 0.25f, cy - 3.5f, x + 0.25f, cy + 3.5f, 0x80FFFFFF);
            }
            if (v == st.trusted) {
                col = Theme.lighter(accent);
                GuiDraw.round(ms, x - 3, cy - 3, x + 3, cy + 3, 3, Theme.lighter(accent), Theme.darker(accent));
            } else if (trial && v == st.candidate) {
                col = Theme.AMBER;
                GuiDraw.round(ms, x - 4.5f, cy - 4.5f, x + 4.5f, cy + 4.5f, 4.5f, GuiDraw.alphaOf(Theme.AMBER, 0x18 + Math.round(0x48 * pulse)));
                GuiDraw.round(ms, x - 2.5f, cy - 2.5f, x + 2.5f, cy + 2.5f, 2.5f, Theme.AMBER);
            } else {
                GuiDraw.round(ms, x - 1.5f, cy - 1.5f, x + 1.5f, cy + 1.5f, 1.5f, Skin.label(LABEL));
            }
            if (v == st.value) { // in force this tick
                GuiDraw.icon(ms, Icons.CHEVRON, x - 2.5f, cy - 8.5f, Skin.text(), 1f);
            }
            if (n <= 7 || i == 0 || i == n - 1 || v == st.base || v == st.trusted || v == st.candidate) {
                String label = st.show(v);
                GuiDraw.text(ms, label, x - GuiDraw.width(label, fine, false) / 2, cy + 5.5f, fine, col, true, false);
            }
        }
    }

    /**
     * Singularity: the values the tuner can stand on are stops on an orbit over a black hole whose ring fills as the
     * trial runs; the numbers sit in rows below.
     */
    private static void dome(GuiGraphicsExtractor ms, AcsStatus st, float x0, float y0, int accent, float opacity, long now) {
        boolean on = st.phase != AcsStatus.Phase.OFF, trial = st.phase == AcsStatus.Phase.TRIAL;
        float x1 = x0 + W, y1 = y0 + height(st), bx0 = x0 + 7, bx1 = x1 - 7, cx = (x0 + x1) / 2, cy = y0 + DOME;
        float o = Math.max(0f, Math.min(1f, opacity)), rim = Math.min(0.2f, o * 0.5f), fill = 1 - (1 - o) / (1 - rim);
        int fa = Math.round(fill * 255);
        Skin.dome(ms, x0, y0, x1, cy, y1, accent, fa, Math.round(Math.max(rim, 0.4f) * 255));
        if (on && st.knob >= 0) {
            orbit(ms, st, cx, cy, accent, now);
        }
        Skin.dial(ms, cx, cy, 18.5f, on ? st.progress() : 0, false, trial ? Theme.AMBER : accent, fa, now);
        // header
        float y = cy + 21;
        GuiDraw.textColors(ms, "Autotune", bx0, y, 1f, true, Skin.hotColors(8), true);
        String state = st.phase.name();
        int stateCol = trial ? Theme.AMBER : on ? Theme.GREEN : Skin.muted();
        float pw = GuiDraw.width(state, fine, false) + 12;
        Skin.pill(ms, bx1 - pw, y - 0.5f, bx1, y + 8.5f, stateCol, 0x3A);
        GuiDraw.icon(ms, Icons.DOT, bx1 - pw + 3.5f, y + 2.5f, stateCol, 0.75f);
        GuiDraw.text(ms, state, bx1 - pw + 8.5f, y + 2, fine, stateCol, true, false);
        // what is tuned, and for which kit against which kind of foe
        y = cy + 32;
        float nw = GuiDraw.text(ms, st.title(), bx0, y, fine, Theme.lighter(accent), true, false);
        String kit = GuiDraw.trim(st.kit(), bx1 - bx0 - nw - 6, fine);
        GuiDraw.text(ms, kit, bx1 - GuiDraw.width(kit, fine, false), y, fine, Skin.label(LABEL), true, false);
        // what the fights are worth
        y = cy + 39.5f;
        float tw = (bx1 - bx0 - 4) / 2;
        if (trial) {
            stat(ms, bx0, y, tw, "DEALT", HudFormat.signedPct(st.dealtPct), tone(st.zDealt, true), st.fought < 2 ? "" : z(st.zDealt));
            stat(ms, bx0 + tw + 4, y, tw, "TAKEN", HudFormat.signedPct(st.takenPct), tone(st.zTaken, false), st.fought < 2 ? "" : z(st.zTaken));
        } else {
            stat(ms, bx0, y, tw, "DEALT/S", st.windows > 0 ? HudFormat.perSecond(st.dealtRate) : "--", Skin.text(), "");
            stat(ms, bx0 + tw + 4, y, tw, "TAKEN/S", st.windows > 0 ? HudFormat.perSecond(st.takenRate) : "--", Skin.text(), "");
        }
        y = cy + 51;
        GuiDraw.text(ms, GuiDraw.trim(st.caption(), bx1 - bx0, fine), bx0, y, fine, Skin.text(), true, false);
        if (on) {
            y += 7.5f;
            float sw = GuiDraw.text(ms, st.score(), bx0, y, fine, Skin.label(LABEL), true, false);
            int against = st.last.indexOf(" against");
            String lastLine = GuiDraw.trim(st.last.isEmpty() ? "" : "last: " + (against < 0 ? st.last : st.last.substring(0, against)), bx1 - bx0 - sw - 6, fine);
            GuiDraw.text(ms, lastLine, bx1 - GuiDraw.width(lastLine, fine, false), y, fine, Skin.muted(), true, false);
            String whole = st.whole();
            if (!whole.isEmpty()) {
                y += 7.5f;
                GuiDraw.text(ms, GuiDraw.trim(whole, bx1 - bx0, fine), bx0, y, fine, st.wholeTrial ? Theme.AMBER : Skin.label(LABEL), true, false);
            }
        }
        if (st.windowSize > 0 && st.windowTicks > 0) {
            float wp = Math.min(1f, st.windowTicks / (float) st.windowSize);
            GuiDraw.rect(ms, x0 + 5, y1 - 1.5f, x0 + 5 + (W - 10) * wp, y1 - 1, GuiDraw.alphaOf(accent, 0xB0));
        }
    }

    /** The stops of {@link #track} laid on the upper half of an ellipse, lowest value on the left. */
    private static void orbit(GuiGraphicsExtractor ms, AcsStatus st, float cx, float cy, int accent, long now) {
        int[] stops = st.stops();
        int n = stops.length;
        double[] at = new double[n];
        double tt = Double.NaN, tc = Double.NaN;
        for (int i = 0; i < n; i++) {
            at[i] = n == 1 ? Math.PI * 1.5 : Math.PI + Math.PI * i / (n - 1);
            if (stops[i] == st.trusted) tt = at[i];
            if (stops[i] == st.candidate) tc = at[i];
        }
        Skin.arc(ms, cx, cy, ORBIT_RX, ORBIT_RY, Math.PI, Math.PI * 2, 0.6f, RAIL, RAIL);
        boolean trial = st.candidate != st.trusted && !Double.isNaN(tt) && !Double.isNaN(tc);
        if (!Double.isNaN(tt)) { // the ground the tuner has covered, ember to amber
            Skin.arc(ms, cx, cy, ORBIT_RX, ORBIT_RY, Math.PI, Math.max(Math.PI + 0.01, trial ? Math.min(tt, tc) : tt), 1.2f,
                    GuiDraw.alphaOf(Skin.EMBER, 0x50), GuiDraw.alphaOf(accent, 0xB0));
        }
        if (trial) {
            Skin.arc(ms, cx, cy, ORBIT_RX, ORBIT_RY, Math.min(tt, tc), Math.max(tt, tc), 1.4f, tt < tc ? accent : Theme.AMBER, tt < tc ? Theme.AMBER : accent);
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(now % 1200L / 1200.0 * 2 * Math.PI);
        for (int i = 0; i < n; i++) {
            float x = cx + ORBIT_RX * (float) Math.cos(at[i]), y = cy + ORBIT_RY * (float) Math.sin(at[i]);
            int v = stops[i], col = Skin.label(LABEL);
            if (v == st.base) { // the baseline: a tick across the orbit
                ms.pose().pushMatrix();
                ms.pose().translate(x, y);
                ms.pose().rotate((float) Math.atan2(ORBIT_RX * Math.sin(at[i]), ORBIT_RY * Math.cos(at[i])));
                GuiDraw.rect(ms, -3.5f, -0.25f, 3.5f, 0.25f, 0x80FFFFFF);
                ms.pose().popMatrix();
            }
            if (v == st.trusted) {
                col = Theme.lighter(accent);
                GuiDraw.round(ms, x - 3, y - 3, x + 3, y + 3, 3, Theme.lighter(accent), Theme.darker(accent));
            } else if (trial && v == st.candidate) {
                col = Theme.AMBER;
                GuiDraw.round(ms, x - 4.5f, y - 4.5f, x + 4.5f, y + 4.5f, 4.5f, GuiDraw.alphaOf(Theme.AMBER, 0x18 + Math.round(0x48 * pulse)));
                GuiDraw.round(ms, x - 2.5f, y - 2.5f, x + 2.5f, y + 2.5f, 2.5f, Skin.PHOTON);
            } else {
                GuiDraw.round(ms, x - 1.5f, y - 1.5f, x + 1.5f, y + 1.5f, 1.5f, Skin.label(LABEL));
            }
            if (v == st.value) { // in force this tick: a photon ring round the stop
                Skin.arc(ms, x, y, 5.5f, 5.5f, 0, Math.PI * 2, 0.6f, Skin.PHOTON, Skin.PHOTON);
            }
            if (n <= 7 || i == 0 || i == n - 1 || v == st.base || v == st.trusted || v == st.candidate) {
                String label = st.show(v);
                float lx = cx + (ORBIT_RX + 9) * (float) Math.cos(at[i]), ly = cy + (ORBIT_RY + 6) * (float) Math.sin(at[i]);
                GuiDraw.text(ms, label, lx - GuiDraw.width(label, fine, false) / 2, ly - 2, fine, col, true, false);
            }
        }
    }

    /** A label, a big number and its z on one line, without a tile behind it. */
    private static void stat(GuiGraphicsExtractor ms, float x, float y, float w, String label, String big, int bigCol, String small) {
        float lw = GuiDraw.text(ms, label, x, y + 2.5f, fine, Skin.label(LABEL), true, false);
        float bw = GuiDraw.text(ms, big, x + lw + 3, y, bigCol, true);
        float zw = GuiDraw.width(small, fine, false);
        if (!small.isEmpty() && x + lw + 3 + bw + 3 + zw <= x + w) {
            GuiDraw.text(ms, small, x + w - zw, y + 2.5f, fine, Skin.muted(), true, false);
        }
    }

    private static void tile(GuiGraphicsExtractor ms, float x, float y, float w, float opacity, String label, String big, int bigCol, String small) {
        Skin.tile(ms, x, y, x + w, y + 21, (0x14 + Math.round(0x34 * opacity)) << 24);
        GuiDraw.text(ms, label, x + 4, y + 3, fine, Skin.label(LABEL), true, false);
        GuiDraw.text(ms, small, x + w - 4 - GuiDraw.width(small, fine, false), y + 3, fine, Skin.muted(), true, false);
        GuiDraw.text(ms, big, x + 4, y + 10, bigCol, true);
    }

    /** Green for a difference that is good for us and red for one that is not, once it is a standard error wide. */
    private static int tone(double z, boolean moreIsGood) {
        if (Math.abs(z) < 1) {
            return Skin.text();
        }
        return z > 0 == moreIsGood ? Theme.GREEN : Theme.DANGER;
    }

    private static String z(double z) {
        return String.format(Locale.ROOT, "z %+.1f", z);
    }
}
