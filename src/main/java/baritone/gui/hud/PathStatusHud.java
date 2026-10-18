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
import baritone.gui.render.Skin;
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
    /** The singularity theme's orbit gauge: a dial at the screen edge and a capsule of text towards the middle. */
    public static final int GAUGE_W = 160, GAUGE_H = 46, GAUGE_TASK_H = 7;
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
        float x0, y0, w = width(), h = height(task != null);
        switch (anchor) {
            case "LEFT":
                x0 = 8;
                y0 = Math.max(8, sh / 2f - h / 2f);
                break;
            case "TOP_LEFT":
                x0 = 8;
                y0 = 8;
                break;
            case "TOP_RIGHT":
                x0 = sw - 8 - w;
                y0 = 8;
                break;
            default: // RIGHT: below the potion-icon / toast strip
                x0 = sw - 8 - w;
                y0 = Math.min(58, Math.max(8, sh - H - 60));
        }
        if (y0 + h > sh - 4) {
            y0 = Math.max(4, sh - 4 - h);
        }
        PathStatus shown = st.active() ? st : (task != null ? st : last);
        if (Skin.singularity()) {
            gauge(ms, shown, x0, y0, Theme.accent(), task, !anchor.endsWith("LEFT"), now);
            return;
        }
        draw(ms, shown, x0, y0, Theme.accent(), task);
    }

    public static float width() {
        return Skin.singularity() ? GAUGE_W : W;
    }

    public static float height(boolean task) {
        return Skin.singularity() ? GAUGE_H + (task ? GAUGE_TASK_H : 0) : H + (task ? TASK_H : 0);
    }

    /** Room another card leaves when it shares this card's corner: the tallest this card gets. */
    public static float stackHeight() {
        return height(true);
    }

    /**
     * Singularity: the black hole is the dial, its accretion ring the progress through the current segment, with the
     * state, ETA, goal and movement in a capsule that runs from the dial towards the middle of the screen.
     */
    static void gauge(GuiGraphicsExtractor ms, PathStatus st, float x0, float y0, int accent, String task, boolean right, long now) {
        float x1 = x0 + GAUGE_W, r = GAUGE_H / 2f, cy = y0 + r;
        float dcx = right ? x1 - r : x0 + r;
        float c0 = y0 + 3, c1 = y0 + GAUGE_H - 2 + (task != null ? GAUGE_TASK_H : 0);
        if (right) {
            Skin.capsule(ms, x0, c0, dcx, c1, accent, 0xD0);
        } else {
            Skin.capsule(ms, dcx, c0, x1, c1, accent, 0xD0);
        }
        float p = st.length > 0 ? st.position / (float) st.length : 0f;
        Skin.dial(ms, dcx, cy, r, p, st.state == PathStatus.State.CALCULATING, accent, 0xD0, now);
        float tx0 = right ? x0 + 11 : dcx + r + 4, tx1 = right ? dcx - r - 4 : x1 - 11;
        // title and state
        GuiDraw.textColors(ms, "Ostinato", tx0, y0 + 8, 1f, true, Skin.hotColors(8), true);
        String state = st.state == PathStatus.State.IDLE ? "IDLE" : st.state.name();
        int stateCol = st.state == PathStatus.State.PATHING ? Theme.GREEN : st.state == PathStatus.State.CALCULATING ? Theme.AMBER : Skin.muted();
        float pw = GuiDraw.width(state, 0.5f, false) + 12, py = y0 + 7.5f;
        Skin.pill(ms, tx1 - pw, py, tx1, py + 9, stateCol, 0x2A);
        GuiDraw.icon(ms, Icons.DOT, tx1 - pw + 3.5f, py + 3, stateCol, 0.75f);
        GuiDraw.text(ms, state, tx1 - pw + 8.5f, py + 2.5f, 0.5f, stateCol, false, false);
        // time to the goal, and the goal
        String eta = st.etaGoal > 0 ? HudFormat.seconds(st.etaGoal) : "--";
        float ew = GuiDraw.text(ms, eta, tx0, y0 + 19, Skin.text(), true);
        String goal = "to " + (st.goal.isEmpty() ? "no goal" : st.goal) + "  " + HudFormat.distance(st.distance);
        GuiDraw.text(ms, GuiDraw.trim(goal, tx1 - tx0 - ew - 4, 0.5f), tx0 + ew + 4, y0 + 22, 0.5f, Skin.muted(), false, false);
        // the movement under way
        float y = y0 + 30;
        if (!st.mover.isEmpty()) {
            float mw = GuiDraw.width(st.mover, 0.5f, false) + 6;
            Skin.pill(ms, tx0, y - 1.5f, tx0 + mw, y + 4.5f, accent, 0x2A);
            GuiDraw.text(ms, st.mover, tx0 + 3, y, 0.5f, accent, false, false);
            GuiDraw.text(ms, GuiDraw.trim(st.movement, tx1 - tx0 - mw - 3, 0.5f), tx0 + mw + 3, y, 0.5f, Skin.text(), false, false);
        } else {
            GuiDraw.text(ms, GuiDraw.trim(st.process, tx1 - tx0, 0.5f), tx0, y, 0.5f, Skin.dim(), false, false);
        }
        String where = st.length > 0 ? "movement " + st.position + "/" + st.length : st.state == PathStatus.State.CALCULATING ? "calculating..." : "";
        String info = where + (st.etaSegment > 0 ? "  seg " + HudFormat.seconds(st.etaSegment) : "")
                + (st.nextMovement.isEmpty() ? "" : "  next " + st.nextMovement);
        GuiDraw.text(ms, GuiDraw.trim(info, tx1 - tx0, 0.5f), tx0, y0 + 37, 0.5f, Skin.muted(), false, false);
        if (task != null) {
            float ty = y0 + 44;
            GuiDraw.text(ms, "TASK", tx0, ty, 0.5f, Skin.dim(), false, false);
            String body = task.startsWith("Task ") ? task.substring(5) : task;
            GuiDraw.text(ms, GuiDraw.trim(body, tx1 - tx0 - 14, 0.5f), tx0 + 14, ty, 0.5f, Skin.text(), false, false);
        }
    }

    static void draw(GuiGraphicsExtractor ms, PathStatus st, float x0, float y0, int accent, String task) {
        float x1 = x0 + W, y1 = y0 + H + (task != null ? TASK_H : 0);
        Skin.card(ms, x0, y0, x1, y1, accent, 0xB0303A4E, 0xD0141925, 0xC80B0E14, true);
        // header
        Skin.title(ms, "Ostinato", x0 + 7, y0 + 6, accent, System.currentTimeMillis());
        String state = st.state == PathStatus.State.IDLE ? "IDLE" : st.state.name();
        int stateCol = st.state == PathStatus.State.PATHING ? Theme.GREEN : st.state == PathStatus.State.CALCULATING ? Theme.AMBER : Skin.muted();
        float pw = GuiDraw.width(state, 0.5f, false) + 12;
        Skin.pill(ms, x1 - 7 - pw, y0 + 5.5f, x1 - 7, y0 + 14.5f, stateCol, 0x2A);
        GuiDraw.icon(ms, Icons.DOT, x1 - 7 - pw + 3.5f, y0 + 8.5f, stateCol, 0.75f);
        GuiDraw.text(ms, state, x1 - 7 - pw + 8.5f, y0 + 8, 0.5f, stateCol, false, false);
        Skin.divider(ms, x0 + 6, x1 - 6, y0 + 19, 0x16FFFFFF);
        // goal
        float y = y0 + 24;
        GuiDraw.text(ms, "GOAL", x0 + 7, y + 1, 0.5f, Skin.dim(), false, false);
        String dist = HudFormat.distance(st.distance);
        float dw = GuiDraw.width(dist, 0.5f, false);
        GuiDraw.text(ms, GuiDraw.trim(st.goal.isEmpty() ? "none" : st.goal, W - 34 - dw - 10, 1f), x0 + 27, y - 1, Skin.text(), true);
        GuiDraw.text(ms, dist, x1 - 7 - dw, y + 1, 0.5f, Skin.muted(), false, false);
        // progress through the current segment
        y += 12;
        float bx0 = x0 + 7, bx1 = x1 - 7;
        float p = st.length > 0 ? st.position / (float) st.length : 0f;
        Skin.bar(ms, bx0, y, bx1, y + 3, p, accent, 0xFF1B212D);
        y += 6;
        GuiDraw.text(ms, st.length > 0 ? "movement " + st.position + "/" + st.length : (st.state == PathStatus.State.CALCULATING ? "calculating..." : ""),
                bx0, y, 0.5f, Skin.muted(), false, false);
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
            Skin.pill(ms, bx0, y, bx0 + mw, y + 8, accent, 0x2A);
            GuiDraw.text(ms, st.mover, bx0 + 4, y + 2, 0.5f, accent, false, false);
            GuiDraw.text(ms, GuiDraw.trim(st.movement, bx1 - bx0 - mw - 5, 1f), bx0 + mw + 5, y, Skin.text(), false);
        }
        y += 10;
        GuiDraw.text(ms, GuiDraw.trim(st.process, 70, 0.5f), bx0, y, 0.5f, Skin.dim(), false, false);
        if (!st.nextMovement.isEmpty()) {
            String nm = "next: " + st.nextMovement;
            GuiDraw.text(ms, nm, bx1 - GuiDraw.width(nm, 0.5f, false), y, 0.5f, Skin.muted(), false, false);
        }
        if (task != null) {
            // "Task 2/5: Mine iron_ore 12/16" -> TASK 2/5 | Mine iron_ore | 12/16
            y += 10;
            Skin.divider(ms, x0 + 6, x1 - 6, y, 0x16FFFFFF);
            y += 3.5f;
            GuiDraw.text(ms, "TASK", bx0, y + 1.5f, 0.5f, Skin.dim(), false, false);
            String body = task.startsWith("Task ") ? task.substring(5) : task;
            int colon = body.indexOf(": ");
            String idx = colon > 0 ? body.substring(0, colon) : "";
            String rest = colon > 0 ? body.substring(colon + 2) : body;
            float iw = GuiDraw.text(ms, idx, bx0 + 14, y, accent, false);
            GuiDraw.text(ms, GuiDraw.trim(rest, bx1 - bx0 - 18 - iw, 1f), bx0 + 18 + iw, y, Skin.text(), false);
        }
    }

    private static void tile(GuiGraphicsExtractor ms, float x, float y, float w, String label, String big, String small) {
        Skin.tile(ms, x, y, x + w, y + 21, 0x0FFFFFFF);
        GuiDraw.text(ms, label, x + 5, y + 3.5f, 0.5f, Skin.dim(), false, false);
        float bw = GuiDraw.text(ms, big, x + 5, y + 10, Skin.text(), true);
        GuiDraw.text(ms, small, x + 7 + bw, y + 13, 0.5f, Skin.muted(), false, false);
    }
}
