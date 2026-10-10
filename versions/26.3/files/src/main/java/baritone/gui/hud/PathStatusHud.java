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
import baritone.gui.anim.Anim;
import baritone.gui.model.HudFormat;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Icons;
import baritone.gui.screen.OstinatoScreen;
import baritone.gui.screen.Theme;
import baritone.gui.tasks.game.TaskService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Compact path-status card drawn over the in-game HUD (hooked from MixinIngameGui). Shown while pathing or
 * calculating, then fades out 3 s after.
 */
public final class PathStatusHud {

    public static final int W = 150, H = 92, TASK_H = 14;
    private static final Anim FADE = new Anim(0, 10);
    private static long lastActive;
    private static PathStatus last = new PathStatus();
    private static boolean failed;

    private PathStatusHud() {}

    public static void render(GuiGraphicsExtractor ms, float partialTicks) {
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
        if (!Baritone.settings().renderPathHud.value || mc.getDebugOverlay().showDebugScreen() || mc.player == null
                || mc.gui.screen() instanceof OstinatoScreen) {
            FADE.snap(0);
            return;
        }
        PathStatus st = PathStatus.capture(BaritoneAPI.getProvider().getPrimaryBaritone());
        long now = System.currentTimeMillis();
        String task = TaskService.INSTANCE.runner.hudLine(now);
        if (st.active() || task != null) {
            lastActive = now;
            last = st;
        }
        float a = FADE.target(now - lastActive < 3000 && lastActive > 0).get();
        if (a <= 0.01f) {
            return;
        }
        GuiDraw.alpha = a;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        String anchor = Baritone.settings().pathHudAnchor.value.trim().toUpperCase(Locale.ROOT);
        float x0, y0;
        switch (anchor) {
            case "LEFT":
                x0 = 8;
                y0 = Math.max(8, sh / 2f - H / 2f);
                break;
            case "TOP_LEFT":
                x0 = 8;
                y0 = 8;
                break;
            case "TOP_RIGHT":
                x0 = sw - 8 - W;
                y0 = 8;
                break;
            default: // RIGHT: below the potion-icon / toast strip
                x0 = sw - 8 - W;
                y0 = Math.min(58, Math.max(8, sh - H - 60));
        }
        float totalH = H + (task != null ? TASK_H : 0);
        if (y0 + totalH > sh - 4) {
            y0 = Math.max(4, sh - 4 - totalH);
        }
        draw(ms, st.active() ? st : (task != null ? st : last), x0, y0, Theme.accent(), task);
    }

    static void draw(GuiGraphicsExtractor ms, PathStatus st, float x0, float y0, int accent, String task) {
        float x1 = x0 + W, y1 = y0 + H + (task != null ? TASK_H : 0);
        GuiDraw.shadow(ms, x0, y0, x1, y1, 4, 6, 0x60);
        GuiDraw.roundBorder(ms, x0, y0, x1, y1, 4, 0xB0303A4E, 0xD0141925, 0xC80B0E14);
        GuiDraw.gradH(ms, x0 + 4, y0 + 0.5f, x1 - 4, y0 + 1, GuiDraw.alphaOf(accent, 0xAA), GuiDraw.alphaOf(Theme.BLUE, 0x10));
        // header
        GuiDraw.icon(ms, Icons.NOTE, x0 + 7, y0 + 6, accent, 0.8f);
        GuiDraw.textColors(ms, "Ostinato", x0 + 17, y0 + 6, 1f, true, Theme.logoColors(accent, 8), true);
        String state = st.state == PathStatus.State.IDLE ? "IDLE" : st.state.name();
        int stateCol = st.state == PathStatus.State.PATHING ? Theme.GREEN : st.state == PathStatus.State.CALCULATING ? Theme.AMBER : Theme.MUTED;
        float pw = GuiDraw.width(state, 0.5f, false) + 12;
        GuiDraw.round(ms, x1 - 7 - pw, y0 + 5.5f, x1 - 7, y0 + 14.5f, 2.5f, GuiDraw.alphaOf(stateCol, 0x2A));
        GuiDraw.icon(ms, Icons.DOT, x1 - 7 - pw + 3.5f, y0 + 8.5f, stateCol, 0.75f);
        GuiDraw.text(ms, state, x1 - 7 - pw + 8.5f, y0 + 8, 0.5f, stateCol, false, false);
        GuiDraw.rect(ms, x0 + 6, y0 + 19, x1 - 6, y0 + 19.5f, 0x16FFFFFF);
        // goal
        float y = y0 + 24;
        GuiDraw.text(ms, "GOAL", x0 + 7, y + 1, 0.5f, Theme.DIM, false, false);
        String dist = HudFormat.distance(st.distance);
        float dw = GuiDraw.width(dist, 0.5f, false);
        GuiDraw.text(ms, GuiDraw.trim(st.goal.isEmpty() ? "none" : st.goal, W - 34 - dw - 10, 1f), x0 + 27, y - 1, Theme.TEXT, true);
        GuiDraw.text(ms, dist, x1 - 7 - dw, y + 1, 0.5f, Theme.MUTED, false, false);
        // progress through the current segment
        y += 12;
        float bx0 = x0 + 7, bx1 = x1 - 7;
        float p = st.length > 0 ? st.position / (float) st.length : 0f;
        GuiDraw.round(ms, bx0, y, bx1, y + 3, 1.5f, 0xFF1B212D);
        if (p > 0) {
            GuiDraw.round(ms, bx0, y, bx0 + Math.max(3, (bx1 - bx0) * p), y + 3, 1.5f, Theme.lighter(accent), Theme.darker(accent));
        }
        y += 6;
        GuiDraw.text(ms, st.length > 0 ? "movement " + st.position + "/" + st.length : (st.state == PathStatus.State.CALCULATING ? "calculating..." : ""),
                bx0, y, 0.5f, Theme.MUTED, false, false);
        String nx = st.nextPlanned ? "next segment planned" : "";
        GuiDraw.text(ms, nx, bx1 - GuiDraw.width(nx, 0.5f, false), y, 0.5f, 0xFF9DB4F0, false, false);
        // stat tiles
        y += 8;
        float tw = (bx1 - bx0 - 4) / 2;
        tile(ms, bx0, y, tw, "ETA TO GOAL", HudFormat.seconds(st.etaGoal), HudFormat.ticks(st.etaGoal));
        tile(ms, bx0 + tw + 4, y, tw, "THIS SEGMENT", HudFormat.seconds(st.etaSegment), HudFormat.ticks(st.etaSegment));
        // mover + movement
        y += 25;
        if (!st.mover.isEmpty()) {
            float mw = GuiDraw.width(st.mover, 0.5f, false) + 8;
            GuiDraw.round(ms, bx0, y, bx0 + mw, y + 8, 2, GuiDraw.alphaOf(accent, 0x2A));
            GuiDraw.text(ms, st.mover, bx0 + 4, y + 2, 0.5f, accent, false, false);
            GuiDraw.text(ms, GuiDraw.trim(st.movement, bx1 - bx0 - mw - 5, 1f), bx0 + mw + 5, y, Theme.TEXT, false);
        }
        y += 10;
        GuiDraw.text(ms, GuiDraw.trim(st.process, 70, 0.5f), bx0, y, 0.5f, Theme.DIM, false, false);
        if (!st.nextMovement.isEmpty()) {
            String nm = "next: " + st.nextMovement;
            GuiDraw.text(ms, nm, bx1 - GuiDraw.width(nm, 0.5f, false), y, 0.5f, Theme.MUTED, false, false);
        }
        if (task != null) {
            // "Task 2/5: Mine iron_ore 12/16" -> TASK 2/5 | Mine iron_ore | 12/16
            y += 10;
            GuiDraw.rect(ms, x0 + 6, y, x1 - 6, y + 0.5f, 0x16FFFFFF);
            y += 3.5f;
            GuiDraw.text(ms, "TASK", bx0, y + 1.5f, 0.5f, Theme.DIM, false, false);
            String body = task.startsWith("Task ") ? task.substring(5) : task;
            int colon = body.indexOf(": ");
            String idx = colon > 0 ? body.substring(0, colon) : "";
            String rest = colon > 0 ? body.substring(colon + 2) : body;
            float iw = GuiDraw.text(ms, idx, bx0 + 14, y, accent, false);
            GuiDraw.text(ms, GuiDraw.trim(rest, bx1 - bx0 - 18 - iw, 1f), bx0 + 18 + iw, y, Theme.TEXT, false);
        }
    }

    private static void tile(GuiGraphicsExtractor ms, float x, float y, float w, String label, String big, String small) {
        GuiDraw.round(ms, x, y, x + w, y + 21, 2.5f, 0x0FFFFFFF);
        GuiDraw.text(ms, label, x + 5, y + 3.5f, 0.5f, Theme.DIM, false, false);
        float bw = GuiDraw.text(ms, big, x + 5, y + 10, Theme.TEXT, true);
        GuiDraw.text(ms, small, x + 7 + bw, y + 13, 0.5f, Theme.MUTED, false, false);
    }
}
