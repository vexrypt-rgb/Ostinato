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
import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.gui.anim.Anim;
import baritone.gui.model.TextInput;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Icons;
import baritone.gui.tasks.ParamSpec;
import baritone.gui.tasks.StepType;
import baritone.gui.tasks.TaskFiles;
import baritone.gui.tasks.TaskList;
import baritone.gui.tasks.TaskRunner;
import baritone.gui.tasks.TaskStep;
import baritone.gui.tasks.game.TaskService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Tasks tab of the Ostinato screen: build an ordered list of steps (go to, mine, follow, farm, explore, get to
 * block, build, wait, set), run/pause/stop it, loop it, and save/load it as baritone/tasks/&lt;name&gt;.json.
 */
final class TasksPanel {

    private static final int ROW_H = 30;
    private static final String[] PLAY = {"#...", "##..", "###.", "####", "###.", "##..", "#..."};
    private static final String[] PAUSE = {"##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##"};
    private static final String[] STOP = {"######", "######", "######", "######", "######", "######"};
    private static final String[] UP = {"..#..", ".###.", "#####"};
    private static final String[] DOWN = {"#####", ".###.", "..#.."};
    private static final String[] DUP = {"####..", "#..#..", "#..###", "####.#", "..#..#", "..####"};
    private static final String[] DEL = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
    private static final String[] CHECK = {"......#", ".....#.", "#...#..", ".#.#...", "..#...."};
    private static final String[] PLUS = {"..#..", "..#..", "#####", "..#..", "..#.."};

    private final OstinatoScreen screen;
    private final TaskService svc = TaskService.INSTANCE;
    private final TextInput edit = new TextInput(256);
    private final Anim scrollAnim = new Anim(0, 20);
    private float scrollTarget;
    /** step index being edited, or -1 for the list name; null key = nothing edited */
    private int editStep;
    private String editKey;
    private boolean dropdown;
    private List<String> savedNames = Collections.emptyList();
    private long savedNamesAt;
    private long loadConfirmUntil;
    private String loadConfirmName;
    private long newConfirmUntil;
    private long overwriteConfirmUntil;

    // layout
    private float px0, px1, top, bottom, listTop, listBottom;

    TasksPanel(OstinatoScreen screen) {
        this.screen = screen;
    }

    private TaskList list() {
        return svc.list();
    }

    /** The saved lists' names. Asked for every frame, so the folder is read at most twice a second. */
    private List<String> savedNames() {
        long now = System.currentTimeMillis();
        if (now - savedNamesAt > 500) {
            savedNamesAt = now;
            savedNames = svc.files().list();
        }
        return savedNames;
    }

    private TaskRunner runner() {
        return svc.runner;
    }

    private boolean locked() {
        return runner().active();
    }

    boolean editing() {
        return editKey != null;
    }

    void layout(float px0, float px1, float top, float bottom) {
        this.px0 = px0;
        this.px1 = px1;
        this.top = top;
        this.bottom = bottom;
        this.listTop = top + 52;
        this.listBottom = bottom - 6;
    }

    private float contentHeight() {
        return list().size() * ROW_H + 16 + 14;
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - (listBottom - listTop));
    }

    // ------------------------------------------------------------------ footer info

    String footerStatus() {
        if (svc.dirty) {
            return "unsaved changes (Ctrl+S)";
        }
        String file = TaskFiles.sanitize(list().name());
        boolean saved = savedNames().stream().anyMatch(file::equalsIgnoreCase);
        return saved ? "saved to baritone/tasks/" + TaskFiles.sanitize(list().name()) + ".json" : "not saved yet";
    }

    int footerStatusColor() {
        return svc.dirty ? Theme.AMBER : Theme.DIM;
    }

    // ------------------------------------------------------------------ draw

    void draw(GuiGraphics ms, int mx, int my, int accent, long now) {
        TaskList l = list();
        TaskRunner r = runner();
        float tw = GuiDraw.text(ms, "Tasks", px0, top + 9, 1f, Theme.TEXT, true, true);
        GuiDraw.text(ms, l.size() + (l.size() == 1 ? " step" : " steps"), px0 + tw + 6, top + 10.5f, 0.5f, Theme.MUTED, false, false);
        GuiDraw.text(ms, "baritone/tasks", px0 + tw + 6, top + 15.5f, 0.5f, Theme.DIM, false, false);

        // list controls (right)
        float rx = px1;
        rx = smallButton(ms, "New", rx, top + 6, mx, my, now < newConfirmUntil ? Theme.DANGER : 0) - 3;
        rx = smallButton(ms, "Save", rx, top + 6, mx, my, 0) - 3;
        float lx0 = rx - 92;
        boolean nameEdit = editKey != null && editStep == -1;
        boolean lh = OstinatoScreen.in(mx, my, lx0, top + 6, rx, top + 20);
        GuiDraw.roundBorder(ms, lx0, top + 6, rx, top + 20, 3, nameEdit ? accent : (lh ? 0xFF3E4860 : Theme.FIELD_BORDER), Theme.FIELD);
        if (nameEdit) {
            screen.drawInput(ms, edit, lx0 + 6, top + 9.5f, 92 - 22, true, accent, now);
        } else {
            GuiDraw.text(ms, GuiDraw.trim(l.name(), 92 - 22, 1f), lx0 + 6, top + 9.5f, Theme.TEXT, false);
        }
        boolean chevH = OstinatoScreen.in(mx, my, rx - 14, top + 6, rx, top + 20);
        GuiDraw.icon(ms, Icons.CHEVRON, rx - 10, top + 12, chevH || dropdown ? accent : Theme.MUTED, 1f);
        GuiDraw.text(ms, "LIST", lx0 - GuiDraw.width("LIST", 0.5f, false) - 4, top + 11, 0.5f, Theme.DIM, false, false);

        drawControlBar(ms, mx, my, accent, now);

        // steps
        float scroll = scrollAnim.target(scrollTarget).get();
        GuiDraw.scissor(px0 - 2, listTop, px1 + 1, listBottom);
        float ry = listTop - scroll;
        for (int i = 0; i < l.size(); i++, ry += ROW_H) {
            if (ry + ROW_H < listTop || ry > listBottom) {
                continue;
            }
            drawRow(ms, i, ry, mx, my, accent, now);
        }
        boolean ah = !locked() && OstinatoScreen.in(mx, my, px0, ry, px1 - 6, ry + 16) && my >= listTop && my < listBottom;
        GuiDraw.roundBorder(ms, px0, ry, px1 - 6, ry + 16, 3, ah ? GuiDraw.alphaOf(accent, 0x90) : 0xFF263041, ah ? GuiDraw.alphaOf(accent, 0x18) : 0x08FFFFFF);
        String ad = "Add step";
        float adw = GuiDraw.width(ad) + 10, acx = (px0 + px1 - 6) / 2 - adw / 2;
        int ac = locked() ? Theme.DIM : accent;
        GuiDraw.icon(ms, PLUS, acx, ry + 5.5f, ac, 1f);
        GuiDraw.text(ms, ad, acx + 9, ry + 4.5f, ac, false);
        GuiDraw.text(ms, l.size() == 0 ? "Add a step, pick its type, fill in the fields, then Run. Tasks keep running with this screen closed."
                        : "Click the type box to change a step (shift-click: back). Right-click a field to clear it.",
                px0 + 2, ry + 21, 0.5f, Theme.DIM, false, false);
        GuiDraw.endScissor();
        float max = maxScroll();
        if (max > 0) {
            float trk = listBottom - listTop, th = Math.max(16, trk * trk / contentHeight());
            float ty = listTop + (trk - th) * (scroll / max);
            GuiDraw.round(ms, px1 - 3, listTop, px1 - 1, listBottom, 1, 0x12FFFFFF);
            GuiDraw.round(ms, px1 - 3, ty, px1 - 1, ty + th, 1, 0x60FFFFFF);
        }
        if (dropdown) {
            drawDropdown(ms, mx, my, accent, lx0, rx);
        }
    }

    private void drawControlBar(GuiGraphics ms, int mx, int my, int accent, long now) {
        TaskRunner r = runner();
        float cb0 = top + 26;
        GuiDraw.round(ms, px0, cb0, px1, cb0 + 20, 3, 0x0AFFFFFF);
        float cx = px0 + 4;
        boolean active = r.active();
        // Run
        String runLabel = active ? "Running" : "Run";
        float rw = GuiDraw.width(runLabel) + 25;
        boolean rh = !active && OstinatoScreen.in(mx, my, cx, cb0 + 3, cx + rw, cb0 + 17);
        int runTop = active ? GuiDraw.alphaOf(Theme.lighter(accent), 0x66) : (rh ? GuiDraw.lerp(accent, 0xFFFFFFFF, 0.25f) : Theme.lighter(accent));
        GuiDraw.round(ms, cx, cb0 + 3, cx + rw, cb0 + 17, 3, runTop, active ? GuiDraw.alphaOf(Theme.darker(accent), 0x66) : Theme.darker(accent));
        int rc = active ? 0x990B0D12 : 0xFF0B0D12;
        GuiDraw.icon(ms, PLAY, cx + 7, cb0 + 6.5f, rc, 1f);
        GuiDraw.text(ms, runLabel, cx + 16, cb0 + 6.5f, rc, false);
        cx += rw + 4;
        // Pause / Resume
        String pl = r.state() == TaskRunner.State.PAUSED ? "Resume" : "Pause";
        cx = barButton(ms, pl, r.state() == TaskRunner.State.PAUSED ? PLAY : PAUSE, cx, cb0, active, false, mx, my) + 4;
        cx = barButton(ms, "Stop", STOP, cx, cb0, active, true, mx, my) + 8;
        // Loop toggle
        boolean loop = list().loop();
        float k = loop ? 1 : 0;
        GuiDraw.roundBorder(ms, cx, cb0 + 4.5f, cx + 22, cb0 + 15.5f, 5.5f, loop ? Theme.darker(accent) : 0xFF343C4C,
                loop ? Theme.lighter(accent) : 0xFF1E2430, loop ? Theme.darker(accent) : 0xFF1E2430);
        float kx = cx + 1.5f + 10.5f * k;
        GuiDraw.round(ms, kx, cb0 + 5.5f, kx + 9, cb0 + 14.5f, 4.5f, loop ? 0xFFFFFFFF : 0xFF9AA3B4);
        GuiDraw.text(ms, "Loop", cx + 27, cb0 + 6.5f, locked() ? Theme.MUTED : Theme.TEXT, false);
        // status (right)
        String s1, s2;
        int col;
        long el = r.elapsed(now);
        int n = r.list() == null ? list().size() : r.list().size();
        switch (r.state()) {
            case RUNNING:
                s1 = "Step " + (r.index() + 1) + "/" + n;
                s2 = (list().loop() ? "cycle " + r.cycle() + ", " : "") + dur(el);
                col = Theme.GREEN;
                break;
            case PAUSED:
                s1 = "Paused " + (r.index() + 1) + "/" + n;
                s2 = dur(el);
                col = Theme.AMBER;
                break;
            case FINISHED:
                s1 = "Finished";
                s2 = "in " + dur(el);
                col = Theme.GREEN;
                break;
            case FAILED:
                s1 = "Failed at step " + (r.index() + 1);
                s2 = r.message(r.index()) == null ? "" : r.message(r.index());
                col = Theme.DANGER;
                break;
            case STOPPED:
                s1 = "Stopped";
                s2 = "at step " + (r.index() + 1);
                col = Theme.MUTED;
                break;
            default:
                s1 = "Ready";
                s2 = n + (n == 1 ? " step" : " steps");
                col = Theme.DIM;
        }
        float maxW = px1 - 8 - (cx + 50);
        s1 = GuiDraw.trim(s1, maxW, 1f);
        s2 = GuiDraw.trim(s2, maxW, 0.5f);
        float w1 = GuiDraw.width(s1);
        GuiDraw.icon(ms, Icons.DOT, px1 - 8 - w1 - 8, cb0 + 8, col, 0.75f);
        GuiDraw.text(ms, s1, px1 - 8 - w1, cb0 + 4.5f, col, false);
        GuiDraw.text(ms, s2, px1 - 8 - GuiDraw.width(s2, 0.5f, false), cb0 + 13.5f, 0.5f, Theme.MUTED, false, false);
    }

    private static String dur(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m " + String.format(Locale.ROOT, "%02ds", s % 60);
    }

    private float barButton(GuiGraphics ms, String label, String[] icon, float x, float cb0, boolean enabled, boolean danger, int mx, int my) {
        float w = GuiDraw.width(label) + 25;
        boolean h = enabled && OstinatoScreen.in(mx, my, x, cb0 + 3, x + w, cb0 + 17);
        int col;
        if (danger && enabled) {
            GuiDraw.roundBorder(ms, x, cb0 + 3, x + w, cb0 + 17, 3, h ? 0xC0E5534B : 0x80E5534B, h ? 0x40E5534B : 0x22E5534B);
            col = 0xFFF08A84;
        } else {
            GuiDraw.roundBorder(ms, x, cb0 + 3, x + w, cb0 + 17, 3, h ? 0xFF3E4860 : Theme.FIELD_BORDER, h ? 0xFF1C2230 : 0xFF161B25);
            col = enabled ? Theme.TEXT : Theme.DIM;
        }
        GuiDraw.icon(ms, icon, x + 7, cb0 + 10 - icon.length / 2f, col, 1f);
        GuiDraw.text(ms, label, x + 16, cb0 + 6.5f, col, false);
        return x + w;
    }

    private float smallButton(GuiGraphics ms, String label, float xr, float y, int mx, int my, int textColor) {
        float w = GuiDraw.width(label) + 16, x0 = xr - w;
        boolean h = OstinatoScreen.in(mx, my, x0, y, xr, y + 14);
        GuiDraw.roundBorder(ms, x0, y, xr, y + 14, 3, h ? 0xFF3E4860 : Theme.FIELD_BORDER, h ? 0xFF1C2230 : 0xFF161B25);
        GuiDraw.text(ms, label, x0 + 8, y + 3.5f, textColor != 0 ? textColor : Theme.TEXT, false);
        return x0;
    }

    private float[] fieldBounds(TaskStep s, float fx0, float fx1) {
        ParamSpec[] ps = s.type().params;
        float tot = 0;
        for (ParamSpec p : ps) {
            tot += p.weight;
        }
        float gap = 4, avail = fx1 - fx0 - gap * (ps.length - 1);
        float[] out = new float[ps.length * 2];
        float x = fx0;
        for (int i = 0; i < ps.length; i++) {
            float w = avail * ps[i].weight / tot;
            out[i * 2] = x;
            out[i * 2 + 1] = x + w;
            x += w + gap;
        }
        return out;
    }

    private float typeX0() {
        return px0 + 27;
    }

    private float actionsX0() {
        return px1 - 14 - 4 * 13;
    }

    private void drawRow(GuiGraphics ms, int i, float ry, int mx, int my, int accent, long now) {
        TaskStep s = list().get(i);
        TaskRunner r = runner();
        TaskRunner.StepStatus st = r.list() == null ? TaskRunner.StepStatus.PENDING : r.status(i);
        boolean run = st == TaskRunner.StepStatus.RUNNING && r.active();
        boolean failed = st == TaskRunner.StepStatus.FAILED;
        boolean hov = OstinatoScreen.in(mx, my, px0, ry, px1 - 6, ry + ROW_H - 2) && my >= listTop && my < listBottom;
        boolean lock = locked();
        GuiDraw.round(ms, px0, ry, px1 - 6, ry + ROW_H - 2, 3, run ? GuiDraw.alphaOf(accent, 0x14) : failed ? 0x14E5534B : hov ? 0x1AFFFFFF : 0x0CFFFFFF);
        if (run || failed) {
            int c = run ? accent : Theme.DANGER;
            GuiDraw.gradH(ms, px0 + 2, ry, px0 + 90, ry + ROW_H - 2, GuiDraw.alphaOf(c, 0x18), GuiDraw.alphaOf(c, 0));
            GuiDraw.round(ms, px0, ry + 4, px0 + 2, ry + ROW_H - 6, 1, c);
        }
        // index badge
        float bx = px0 + 8, by = ry + 3.5f;
        boolean done = st == TaskRunner.StepStatus.DONE;
        int bcol = done ? Theme.GREEN : run ? accent : failed ? Theme.DANGER : Theme.MUTED;
        GuiDraw.round(ms, bx, by, bx + 13, by + 13, 6.5f, done ? 0x2A5BE38A : run ? GuiDraw.alphaOf(accent, 0x33) : failed ? 0x33E5534B : 0x14FFFFFF);
        if (done) {
            GuiDraw.icon(ms, CHECK, bx + 3, by + 4, Theme.GREEN, 1f);
        } else {
            String n = String.valueOf(i + 1);
            GuiDraw.text(ms, n, bx + 7 - GuiDraw.width(n) / 2f, by + 3, bcol, false);
        }
        // type box
        float tx0 = typeX0(), tx1 = tx0 + 70, cyc = ry + 10;
        boolean th = !lock && OstinatoScreen.in(mx, my, tx0, cyc - 7, tx1, cyc + 7);
        GuiDraw.roundBorder(ms, tx0, cyc - 7, tx1, cyc + 7, 3, th ? 0xFF3E4860 : run ? GuiDraw.lerp(Theme.FIELD_BORDER, accent, 0.4f) : Theme.FIELD_BORDER, Theme.FIELD);
        GuiDraw.text(ms, s.type().label, tx0 + 6, cyc - 3.5f, lock ? Theme.MUTED : Theme.TEXT, false);
        GuiDraw.icon(ms, Icons.CHEVRON, tx1 - 10, cyc - 1, th ? accent : Theme.MUTED, 1f);
        // actions
        String[][] icons = {UP, DOWN, DUP, DEL};
        float ax = actionsX0();
        for (int a = 0; a < 4; a++) {
            float x = ax + a * 13;
            boolean ah = !lock && OstinatoScreen.in(mx, my, x, cyc - 6, x + 12, cyc + 6);
            if (ah) {
                GuiDraw.round(ms, x, cyc - 6, x + 12, cyc + 6, 2.5f, a == 3 ? 0x33E5534B : GuiDraw.alphaOf(accent, 0x33));
            } else if (hov && !lock) {
                GuiDraw.round(ms, x, cyc - 6, x + 12, cyc + 6, 2.5f, 0x10FFFFFF);
            }
            int col = lock ? 0xFF3A4150 : ah ? (a == 3 ? 0xFFF08A84 : accent) : hov ? Theme.MUTED : Theme.DIM;
            String[] ic = icons[a];
            GuiDraw.icon(ms, ic, x + 6 - ic[0].length() / 2f, cyc - ic.length / 2f, col, 1f);
        }
        // fields
        float fx0 = tx1 + 6, fx1 = ax - 6;
        float[] fb = fieldBounds(s, fx0, fx1);
        ParamSpec[] ps = s.type().params;
        for (int p = 0; p < ps.length; p++) {
            float x0 = fb[p * 2], x1 = fb[p * 2 + 1];
            ParamSpec spec = ps[p];
            boolean ed = editKey != null && editStep == i && editKey.equals(spec.key);
            boolean fh = !lock && OstinatoScreen.in(mx, my, x0, cyc - 7, x1, cyc + 7);
            GuiDraw.roundBorder(ms, x0, cyc - 7, x1, cyc + 7, 3, ed ? accent : fh ? 0xFF3E4860 : run ? GuiDraw.lerp(Theme.FIELD_BORDER, accent, 0.25f) : Theme.FIELD_BORDER, Theme.FIELD);
            String v = s.get(spec.key);
            if (ed) {
                screen.drawInput(ms, edit, x0 + 5, cyc - 3.5f, x1 - x0 - 10, true, accent, now);
                continue;
            }
            String hint = spec.hint.toUpperCase(Locale.ROOT);
            float hw = GuiDraw.width(hint, 0.5f, false);
            boolean showHint = x1 - x0 > hw + 14 + (v.isEmpty() ? 0 : GuiDraw.width(v));
            if (spec.kind == ParamSpec.Kind.CHOICE) {
                GuiDraw.text(ms, GuiDraw.trim(v, x1 - x0 - 16, 1f), x0 + 5, cyc - 3.5f, lock ? Theme.MUTED : Theme.TEXT, false);
                GuiDraw.icon(ms, Icons.CHEVRON, x1 - 10, cyc - 1, fh ? accent : Theme.MUTED, 1f);
                continue;
            }
            if (showHint || v.isEmpty()) {
                GuiDraw.text(ms, v.isEmpty() && !spec.required ? hint + " (OPTIONAL)" : hint, v.isEmpty() ? x0 + 5 : x1 - hw - 5, v.isEmpty() ? cyc - 1.5f : cyc - 1.5f, 0.5f, Theme.DIM, false, false);
            }
            if (!v.isEmpty()) {
                GuiDraw.text(ms, GuiDraw.trim(v, x1 - x0 - 10 - (showHint ? hw + 6 : 0), 1f), x0 + 5, cyc - 3.5f, lock ? Theme.MUTED : Theme.TEXT, false);
            }
        }
        // second line
        float ly = ry + 20.5f;
        String prefix = Baritone.settings().prefix.value;
        GuiDraw.text(ms, GuiDraw.trim(s.preview(prefix), (fx1 - tx0) * 0.55f, 0.5f), tx0, ly, 0.5f, run ? GuiDraw.lerp(accent, 0xFFFFFFFF, 0.3f) : Theme.DIM, false, false);
        String note = null;
        int ncol = Theme.MUTED;
        if (run) {
            note = r.progress(now);
            ncol = accent;
            if (r.state() == TaskRunner.State.PAUSED) {
                note = (note == null ? "" : note + ", ") + "paused";
                ncol = Theme.AMBER;
            }
        } else if (failed) {
            note = r.message(i);
            ncol = Theme.DANGER;
        } else if (done) {
            note = r.message(i);
            ncol = 0xFF6FCF97;
        } else if (!lock) {
            String err = s.validate();
            if (err != null) {
                note = err;
                ncol = Theme.AMBER;
            }
        }
        if (note != null) {
            note = GuiDraw.trim(note, (fx1 - tx0) * 0.44f, 0.5f);
            GuiDraw.text(ms, note, fx1 - GuiDraw.width(note, 0.5f, false), ly, 0.5f, ncol, false, false);
        }
    }

    private void drawDropdown(GuiGraphics ms, int mx, int my, int accent, float lx0, float lx1) {
        List<String> names = savedNames();
        int n = Math.min(10, names.size());
        float y0 = top + 21, h = Math.max(1, n) * 12 + 4;
        ms.pose().pushMatrix();
        GuiDraw.shadow(ms, lx0, y0, lx1, y0 + h, 3, 5, 0x80);
        GuiDraw.roundBorder(ms, lx0, y0, lx1, y0 + h, 3, 0xFF34405A, 0xFA151A24, 0xFA0C0F16);
        if (names.isEmpty()) {
            GuiDraw.text(ms, "no saved lists", lx0 + 6, y0 + 4, 1f, Theme.DIM, false, false);
        }
        for (int i = 0; i < n; i++) {
            float iy = y0 + 2 + i * 12;
            boolean h2 = OstinatoScreen.in(mx, my, lx0 + 2, iy, lx1 - 2, iy + 12);
            boolean cur = names.get(i).equalsIgnoreCase(list().name());
            if (h2) {
                GuiDraw.round(ms, lx0 + 2, iy, lx1 - 2, iy + 12, 2, GuiDraw.alphaOf(accent, 0x2A));
            }
            GuiDraw.text(ms, GuiDraw.trim(names.get(i), lx1 - lx0 - 12, 1f), lx0 + 6, iy + 2, cur ? accent : h2 ? Theme.TEXT : 0xFFB8C0CE, false);
        }
        ms.pose().popMatrix();
    }

    // ------------------------------------------------------------------ input

    private void changed() {
        svc.dirty = true;
        runner().clear();
    }

    private void startEdit(int step, String key, String value) {
        commit();
        editStep = step;
        editKey = key;
        edit.set(value);
        edit.selectAll();
    }

    void commit() {
        if (editKey == null) {
            return;
        }
        String v = edit.get().trim();
        if (editStep == -1) {
            String name = TaskFiles.sanitize(v);
            if (!name.equals(list().name())) {
                list().setName(name);
                svc.dirty = true;
            }
        } else if (editStep < list().size()) {
            TaskStep s = list().get(editStep);
            if (!v.equals(s.get(editKey))) {
                s.set(editKey, v);
                changed();
            }
        }
        editKey = null;
    }

    void cancelEdit() {
        editKey = null;
    }

    /** Save the list; returns false (with a message) on failure. */
    boolean save() {
        commit();
        long now = System.currentTimeMillis();
        String name = TaskFiles.sanitize(list().name());
        if (svc.files().exists(name) && !name.equalsIgnoreCase(String.valueOf(svc.fileName)) && now >= overwriteConfirmUntil) {
            overwriteConfirmUntil = now + 3000;
            screen.flash("baritone/tasks/" + name + ".json already exists: save again to overwrite it", Theme.AMBER);
            return false;
        }
        overwriteConfirmUntil = 0;
        try {
            svc.save();
            savedNamesAt = 0;
            svc.dirty = false;
            screen.flash("Saved baritone/tasks/" + list().name() + ".json", Theme.GREEN);
            return true;
        } catch (Exception e) {
            screen.flash("Save failed: " + e.getMessage(), Theme.DANGER);
            return false;
        }
    }

    private void load(String name) {
        long now = System.currentTimeMillis();
        if (svc.dirty && !(name.equals(loadConfirmName) && now < loadConfirmUntil)) {
            loadConfirmName = name;
            loadConfirmUntil = now + 3000;
            screen.flash("Unsaved changes: click " + name + " again to load it anyway", Theme.AMBER);
            dropdown = true;
            return;
        }
        try {
            svc.load(name);
            svc.dirty = false;
            scrollTarget = 0;
            screen.flash("Loaded " + name, Theme.MUTED);
        } catch (Exception e) {
            screen.flash("Could not load " + name + ": " + e.getMessage(), Theme.DANGER);
        }
        dropdown = false;
    }

    private void lockedHint() {
        screen.flash("Stop the running list to edit it", Theme.AMBER);
    }

    boolean mouseClicked(double mx, double my, int button) {
        long now = System.currentTimeMillis();
        // dropdown first
        float rx = px1 - (GuiDraw.width("New") + 16) - 3 - (GuiDraw.width("Save") + 16) - 3, lx0 = rx - 92;
        if (dropdown) {
            // the same names the dropdown is showing, so a click lands on the row that was drawn
            List<String> names = savedNames();
            int n = Math.min(10, names.size());
            float y0 = top + 21;
            for (int i = 0; i < n; i++) {
                float iy = y0 + 2 + i * 12;
                if (OstinatoScreen.in(mx, my, lx0 + 2, iy, rx - 2, iy + 12)) {
                    if (locked()) {
                        lockedHint();
                        dropdown = false;
                    } else {
                        load(names.get(i));
                    }
                    return true;
                }
            }
            dropdown = false;
            if (OstinatoScreen.in(mx, my, rx - 14, top + 6, rx, top + 20)) {
                return true;
            }
        }
        if (editKey != null) {
            commit();
        }
        // header controls
        float newW = GuiDraw.width("New") + 16, saveW = GuiDraw.width("Save") + 16;
        if (OstinatoScreen.in(mx, my, px1 - newW, top + 6, px1, top + 20)) {
            if (locked()) {
                lockedHint();
            } else if (svc.dirty && now >= newConfirmUntil) {
                newConfirmUntil = now + 3000;
                screen.flash("Unsaved changes: click New again to discard them", Theme.AMBER);
            } else {
                svc.setList(new TaskList(uniqueName()));
                svc.dirty = false;
                svc.fileName = null;
                newConfirmUntil = 0;
                scrollTarget = 0;
            }
            return true;
        }
        if (OstinatoScreen.in(mx, my, px1 - newW - 3 - saveW, top + 6, px1 - newW - 3, top + 20)) {
            save();
            return true;
        }
        if (OstinatoScreen.in(mx, my, rx - 14, top + 6, rx, top + 20)) {
            dropdown = true;
            return true;
        }
        if (OstinatoScreen.in(mx, my, lx0, top + 6, rx - 14, top + 20)) {
            if (locked()) {
                lockedHint();
            } else {
                startEdit(-1, "name", list().name());
            }
            return true;
        }
        // control bar
        float cb0 = top + 26, cx = px0 + 4;
        TaskRunner r = runner();
        String runLabel = r.active() ? "Running" : "Run";
        float rw = GuiDraw.width(runLabel) + 25;
        if (OstinatoScreen.in(mx, my, cx, cb0 + 3, cx + rw, cb0 + 17)) {
            if (!r.active()) {
                r.run(list(), now);
                if (r.state() == TaskRunner.State.FAILED) {
                    screen.flash("Cannot run: " + r.error(), Theme.DANGER);
                    scrollTo(r.index());
                }
            }
            return true;
        }
        cx += rw + 4;
        String pl = r.state() == TaskRunner.State.PAUSED ? "Resume" : "Pause";
        float pw = GuiDraw.width(pl) + 25;
        if (OstinatoScreen.in(mx, my, cx, cb0 + 3, cx + pw, cb0 + 17)) {
            if (r.state() == TaskRunner.State.PAUSED) {
                r.resume(now);
            } else {
                r.pause(now);
            }
            return true;
        }
        cx += pw + 4;
        float sw = GuiDraw.width("Stop") + 25;
        if (OstinatoScreen.in(mx, my, cx, cb0 + 3, cx + sw, cb0 + 17)) {
            r.stop(now);
            return true;
        }
        cx += sw + 8;
        if (OstinatoScreen.in(mx, my, cx, cb0 + 3, cx + 50, cb0 + 17)) {
            if (locked()) {
                lockedHint();
            } else {
                list().setLoop(!list().loop());
                svc.dirty = true;
            }
            return true;
        }
        if (my < listTop || my >= listBottom) {
            return false;
        }
        // rows
        float ry = listTop - scrollAnim.get();
        for (int i = 0; i < list().size(); i++, ry += ROW_H) {
            if (my >= ry && my < ry + ROW_H - 2) {
                return clickRow(i, ry, mx, my, button);
            }
        }
        if (OstinatoScreen.in(mx, my, px0, ry, px1 - 6, ry + 16)) {
            if (locked()) {
                lockedHint();
            } else if (!list().add(newStep())) {
                screen.flash("A list holds at most " + TaskList.MAX_STEPS + " steps", Theme.AMBER);
            } else {
                changed();
                scrollTarget = maxScroll();
            }
            return true;
        }
        return false;
    }

    private String uniqueName() {
        String base = "tasks";
        String n = base;
        for (int i = 2; svc.files().exists(n); i++) {
            n = base + "-" + i;
        }
        return n;
    }

    /** New steps default to "Go to" the player's current block, which is the most common first edit. */
    private TaskStep newStep() {
        TaskStep s = new TaskStep(StepType.GOTO);
        try {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player() != null) {
                BetterBlockPos p = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerFeet();
                s.set("coords", p.x + " " + p.y + " " + p.z);
            }
        } catch (RuntimeException ignored) {
            // leave it empty
        }
        return s;
    }

    private void scrollTo(int i) {
        float y = i * ROW_H;
        if (y < scrollTarget || y + ROW_H > scrollTarget + (listBottom - listTop)) {
            scrollTarget = Math.max(0, Math.min(maxScroll(), y - ROW_H));
        }
    }

    private boolean clickRow(int i, float ry, double mx, double my, int button) {
        TaskStep s = list().get(i);
        float cyc = ry + 10, tx0 = typeX0(), tx1 = tx0 + 70;
        boolean inControls = my >= cyc - 7 && my < cyc + 7;
        if (!inControls) {
            return true;
        }
        if (locked()) {
            if (mx >= tx0) {
                lockedHint();
            }
            return true;
        }
        if (mx >= tx0 && mx < tx1) {
            s.setType(s.type().next(button == 1 || Keys.shift() ? -1 : 1));
            changed();
            return true;
        }
        float ax = actionsX0();
        for (int a = 0; a < 4; a++) {
            float x = ax + a * 13;
            if (mx >= x && mx < x + 12) {
                switch (a) {
                    case 0:
                        list().move(i, -1);
                        break;
                    case 1:
                        list().move(i, 1);
                        break;
                    case 2:
                        if (!list().duplicate(i)) {
                            screen.flash("A list holds at most " + TaskList.MAX_STEPS + " steps", Theme.AMBER);
                        }
                        break;
                    default:
                        list().remove(i);
                }
                changed();
                return true;
            }
        }
        float[] fb = fieldBounds(s, tx1 + 6, ax - 6);
        ParamSpec[] ps = s.type().params;
        for (int p = 0; p < ps.length; p++) {
            if (mx >= fb[p * 2] && mx < fb[p * 2 + 1]) {
                ParamSpec spec = ps[p];
                if (spec.kind == ParamSpec.Kind.CHOICE) {
                    int idx = 0;
                    for (int o = 0; o < spec.options.length; o++) {
                        if (spec.options[o].equals(s.get(spec.key))) {
                            idx = o;
                        }
                    }
                    int dir = button == 1 || Keys.shift() ? -1 : 1;
                    s.set(spec.key, spec.options[((idx + dir) % spec.options.length + spec.options.length) % spec.options.length]);
                    changed();
                } else if (button == 1) {
                    s.set(spec.key, "");
                    changed();
                } else {
                    startEdit(i, spec.key, s.get(spec.key));
                }
                return true;
            }
        }
        return true;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) delta * ROW_H));
        return true;
    }

    /** Keys while a field of this panel is focused. */
    boolean keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commit();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancelEdit();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB && editStep >= 0 && editStep < list().size()) {
            // next editable field in the row (wraps to the next row)
            int step = editStep;
            String k = editKey;
            commit();
            ParamSpec[] ps = list().get(step).type().params;
            int at = 0;
            for (int p = 0; p < ps.length; p++) {
                if (ps[p].key.equals(k)) {
                    at = p;
                }
            }
            for (int p = at + 1; p < ps.length; p++) {
                if (ps[p].kind != ParamSpec.Kind.CHOICE) {
                    startEdit(step, ps[p].key, list().get(step).get(ps[p].key));
                    return true;
                }
            }
            if (step + 1 < list().size()) {
                for (ParamSpec p : list().get(step + 1).type().params) {
                    if (p.kind != ParamSpec.Kind.CHOICE) {
                        startEdit(step + 1, p.key, list().get(step + 1).get(p.key));
                        scrollTo(step + 1);
                        return true;
                    }
                }
            }
            return true;
        }
        return screen.editKey(edit, key);
    }

    void charTyped(char c) {
        edit.insert(String.valueOf(c));
    }

    boolean dirty() {
        return svc.dirty;
    }
}
