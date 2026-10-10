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
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import baritone.gui.GuiSettingsStore;
import baritone.gui.OstinatoGui;
import baritone.gui.anim.Anim;
import baritone.gui.hud.PathStatus;
import baritone.gui.model.*;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Icons;
import baritone.gui.tasks.game.TaskService;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.StringUtil;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.*;

/**
 * The Ostinato settings screen: category sidebar, search, typed rows with inline editors, hover tooltips,
 * per-setting reset and debounced saving of GUI edits only. Layout is in GUI units and matches the approved
 * 640x360 (1280x720 at GUI scale 2) mockup.
 */
public final class OstinatoScreen extends Screen {

    private enum Kind { TOGGLE, SLIDER, NUMBER, CYCLE, COLOR, TEXT, KEYBIND }

    private enum Filter { ALL, TOGGLES, MODIFIED }

    private static final class Entry {
        final Settings.Setting<?> s;
        final String name, doc;
        final SettingCategory category;
        final boolean ostinato, experimental;
        final Kind kind;
        final SettingRanges.Range range;
        final String[] options;
        final Anim hover = new Anim(0, 18);
        final Anim knob;

        Entry(Settings.Setting<?> s, String doc) {
            this.s = s;
            this.name = s.getName();
            this.doc = doc == null ? "" : doc;
            this.category = SettingCategorizer.categorize(name);
            this.ostinato = OstinatoPins.isOstinato(name);
            this.experimental = OstinatoPins.isExperimental(this.doc);
            Class<?> c = s.getValueClass();
            String[] opts = null;
            SettingRanges.Range r = null;
            Kind k;
            if (c == Boolean.class) {
                k = Kind.TOGGLE;
            } else if (name.equals("guiKeybind")) {
                k = Kind.KEYBIND;
            } else if (c.isEnum()) {
                k = Kind.CYCLE;
                Object[] consts = c.getEnumConstants();
                opts = new String[consts.length];
                for (int i = 0; i < consts.length; i++) {
                    opts[i] = ((Enum<?>) consts[i]).name();
                }
            } else if (name.equals("movementBackend")) {
                k = Kind.CYCLE;
                opts = new String[]{"baritone", "tungsten", "auto"};
            } else if (name.equals("pathHudAnchor")) {
                k = Kind.CYCLE;
                opts = new String[]{"RIGHT", "LEFT", "TOP_RIGHT", "TOP_LEFT"};
            } else if (Number.class.isAssignableFrom(c)) {
                r = SettingRanges.get(name);
                k = r != null ? Kind.SLIDER : Kind.NUMBER;
            } else if (c == Color.class) {
                k = Kind.COLOR;
            } else {
                k = Kind.TEXT;
            }
            this.kind = k;
            this.range = r;
            this.options = opts;
            this.knob = new Anim(k == Kind.TOGGLE && Boolean.TRUE.equals(s.value) ? 1 : 0, 16);
        }

        boolean on() {
            return Boolean.TRUE.equals(s.value);
        }

        boolean integral() {
            Class<?> c = s.getValueClass();
            return c == Integer.class || c == Long.class;
        }

        double number() {
            return s.value instanceof Number ? ((Number) s.value).doubleValue() : 0;
        }
    }

    private static final int ROW_H = 25;

    private final Settings settings = Baritone.settings();
    private final GuiSettingsStore store = OstinatoGui.STORE;
    private final List<Entry> all = new ArrayList<>();
    private final Map<SettingCategory, List<Entry>> byCat = new EnumMap<>(SettingCategory.class);
    private final Map<SettingCategory, Anim> catHover = new EnumMap<>(SettingCategory.class);
    private final Anim modHover = new Anim(0, 18);
    private final Anim tasksHover = new Anim(0, 18);
    private static final ItemStack TASKS_ICON = new ItemStack(Items.MAP);
    private final Anim open = new Anim(0, 14);
    private final Anim scrollAnim = new Anim(0, 20);
    private final TextInput search = new TextInput(48);
    private final TextInput editor = new TextInput(512);

    private SettingCategory selected = SettingCategory.MOVEMENT;
    private boolean modifiedView;
    private boolean tasksView;
    private final TasksPanel tasks = new TasksPanel(this);
    private Filter filter = Filter.ALL;
    private boolean searchFocused;
    private List<Entry> rows = new ArrayList<>();
    private Entry editing, capturing, dragging, hovered;
    /** CYCLE entry whose option list is open. */
    private Entry dropdown;
    private boolean editError, draggingThumb;
    private float thumbGrab;
    private long hoverSince;
    private float scrollTarget;
    /** Sidebar row height, shrunk (then scrolled) when the window is too short for every category. */
    private float sideH = 15, sideScroll, maxSideScroll;
    private boolean sideCard = true;
    private String status;
    private int statusColor;
    private long statusUntil, resetConfirmUntil;
    private Set<Settings.Setting> modifiedCache = new HashSet<>();
    private long modifiedAt;

    // layout (GUI units)
    private float x0, y0, x1, y1, hb, fy, sbx1, px0, px1, ry0, listBottom;

    public OstinatoScreen() {
        super(Component.literal("Ostinato"));
        Map<String, String> docs = SettingDescriptions.get();
        for (Settings.Setting<?> s : settings.allSettings) {
            if (s.isJavaOnly() || !editable(s)) {
                continue; // callbacks and other values without a text form cannot be edited here
            }
            Entry e = new Entry(s, docs.get(s.getName().toLowerCase(Locale.ROOT)));
            all.add(e);
            byCat.computeIfAbsent(e.category, c -> new ArrayList<>()).add(e);
        }
        for (List<Entry> l : byCat.values()) {
            l.sort((a, b) -> Boolean.compare(b.ostinato, a.ostinato)); // stable: pinned first, else declaration order
        }
        for (SettingCategory c : SettingCategory.values()) {
            catHover.put(c, new Anim(0, 18));
        }
        rebuild();
    }

    private static boolean editable(Settings.Setting<?> s) {
        try {
            SettingsUtil.settingDefaultToString(s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {

    }

    @Override
    public void removed() { // yarn: removed()

        commitEditor();
        tasks.commit();
        store.saveNow();
    }

    // ------------------------------------------------------------------ model helpers

    private Set<Settings.Setting> modified() {
        long now = System.currentTimeMillis();
        if (now - modifiedAt > 250) {
            modifiedCache = new HashSet<>(SettingsUtil.modifiedSettings(settings));
            modifiedAt = now;
        }
        return modifiedCache;
    }

    private boolean isModified(Entry e) {
        return modified().contains(e.s);
    }

    private void invalidate() {
        modifiedAt = 0;
    }

    private void rebuild() {
        String q = search.get().trim();
        List<Entry> out = new ArrayList<>();
        if (!q.isEmpty()) {
            final Map<Entry, Integer> score = new HashMap<>();
            for (Entry e : all) {
                int sc = SettingFilter.score(q, e.name, e.doc);
                if (sc > 0) {
                    score.put(e, sc);
                    out.add(e);
                }
            }
            out.sort((a, b) -> score.get(b) - score.get(a));
        } else if (modifiedView) {
            for (Entry e : all) {
                if (isModified(e)) {
                    out.add(e);
                }
            }
        } else {
            out.addAll(byCat.getOrDefault(selected, Collections.<Entry>emptyList()));
        }
        if (filter == Filter.TOGGLES) {
            out.removeIf(e -> e.kind != Kind.TOGGLE);
        } else if (filter == Filter.MODIFIED) {
            out.removeIf(e -> !isModified(e));
        }
        rows = out;
        dropdown = null; // its row may be gone
        scrollTarget = 0;
        scrollAnim.snap(0);
    }

    private void changed(Entry e) {
        store.touch(e.s);
        invalidate();
        if (e.name.equals("chatControl") && !settings.chatControl.value && !settings.chatControlAnyway.value) {
            flash("Chat commands are now off; re-enable here or with chatControlAnyway", Theme.AMBER);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setRaw(Entry e, Object v) {
        ((Settings.Setting) e.s).value = v;
        changed(e);
    }

    private void setNumber(Entry e, double v) {
        Class<?> c = e.s.getValueClass();
        Object boxed;
        if (c == Integer.class) {
            boxed = (int) Math.round(v);
        } else if (c == Long.class) {
            boxed = Math.round(v);
        } else if (c == Float.class) {
            boxed = (float) v;
        } else {
            boxed = v;
        }
        if (!boxed.equals(e.s.value)) {
            setRaw(e, boxed);
        }
    }

    private void reset(Entry e) {
        e.s.reset();
        changed(e);
        flash("Reset " + e.name, Theme.MUTED);
    }

    private static String valueString(Entry e) {
        try {
            if (e.kind == Kind.COLOR && e.s.value instanceof Color) {
                return String.format("#%06X", ((Color) e.s.value).getRGB() & 0xFFFFFF);
            }
            return SettingsUtil.settingValueToString(e.s);
        } catch (Throwable t) {
            return String.valueOf(e.s.value);
        }
    }

    private static String defaultString(Entry e) {
        try {
            if (e.s.defaultValue instanceof Color) {
                return String.format("#%06X", ((Color) e.s.defaultValue).getRGB() & 0xFFFFFF);
            }
            return SettingsUtil.settingDefaultToString(e.s);
        } catch (Throwable t) {
            return String.valueOf(e.s.defaultValue);
        }
    }

    private static String typeName(Entry e) {
        String t = e.s.getType().getTypeName().replace("java.lang.", "").replace("java.util.", "").replace("java.awt.", "");
        return t.replaceAll("[a-z]+\\.[a-z.]+\\.", "");
    }

    void flash(String msg, int color) {
        status = msg;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + 3500;
    }

    private void startEdit(Entry e) {
        commitEditor();
        editing = e;
        editError = false;
        editor.set(valueString(e));
        editor.selectAll();
        searchFocused = false;
    }

    private void commitEditor() {
        if (editing == null) {
            return;
        }
        Entry e = editing;
        String text = editor.get().trim();
        try {
            if (e.kind == Kind.COLOR && text.startsWith("#") && text.length() == 7) {
                int rgb = Integer.parseInt(text.substring(1), 16);
                text = ((rgb >> 16) & 0xFF) + "," + ((rgb >> 8) & 0xFF) + "," + (rgb & 0xFF);
            }
            Object before = e.s.value;
            SettingsUtil.parseAndApply(settings, e.name.toLowerCase(Locale.ROOT), text);
            if (!Objects.equals(before, e.s.value)) {
                changed(e);
            }
            editing = null;
            editError = false;
        } catch (Throwable t) {
            editError = true;
            flash("Invalid value for " + e.name + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()), Theme.DANGER);
        }
    }

    private void cancelEditor() {
        editing = null;
        editError = false;
    }

    private static int controlWidth(Kind k) {
        switch (k) {
            case TOGGLE:
                return 22;
            case SLIDER:
                return 98;
            case NUMBER:
                return 60;
            case COLOR:
                return 76;
            case TEXT:
                return 96;
            default:
                return 74;
        }
    }

    private static ItemStack icon(SettingCategory c) {
        Item i;
        switch (c) {
            case MOVEMENT: i = Items.FEATHER; break;
            case WATER_AIR: i = Items.WATER_BUCKET; break;
            case MINING: i = Items.DIAMOND_PICKAXE; break;
            case BUILDING: i = Items.BRICKS; break;
            case INVENTORY: i = Items.HOPPER; break;
            case PATHING: i = Items.COMPASS; break;
            case ELYTRA: i = Items.ELYTRA; break;
            case RENDER: i = Items.ENDER_EYE; break;
            case CHAT: i = Items.WRITABLE_BOOK; break;
            case SWARM: i = Items.BELL; break;
            case INTERFACE: i = Items.PAINTING; break;
            case ADVANCED: i = Items.COMPARATOR; break;
            default: i = Items.BOOK;
        }
        return new ItemStack(i);
    }

    // ------------------------------------------------------------------ layout

    private int sideItemCount() {
        int n = 2;
        for (SettingCategory c : SettingCategory.values()) {
            List<Entry> l = byCat.get(c);
            if (l != null && !l.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private void layoutSidebar() {
        int n = sideItemCount();
        float top = hb + 8, sep = 12;
        float withCard = (fy - 36 - 4 - top - sep) / n, noCard = (fy - 4 - top - sep) / n;
        if (withCard >= 16) {
            sideH = 15;
            sideCard = true;
        } else {
            sideCard = false;
            sideH = Math.max(11, Math.min(15, noCard - 1));
        }
        float content = n * (sideH + 1) + sep, region = fy - top - 2;
        maxSideScroll = Math.max(0, content - region);
        sideScroll = Math.max(0, Math.min(maxSideScroll, sideScroll));
    }

    private void layout() {
        float w = width >= 444 ? Math.max(340, Math.min(620, width - 104)) : width - 16;
        float h = Math.max(200, Math.min(400, height - 46));
        if (h > height - 8) {
            h = height - 8;
        }
        x0 = (float) Math.floor((width - w) / 2);
        y0 = (float) Math.floor((height - h) / 2);
        x1 = x0 + w;
        y1 = y0 + h;
        hb = y0 + 30;
        fy = y1 - 24;
        sbx1 = x0 + (w >= 480 ? 122 : 100);
        px0 = sbx1 + 10;
        px1 = x1 - 12;
        layoutSidebar();
        ry0 = hb + 26;
        listBottom = fy - 6;
    }

    private float maxScroll() {
        return Math.max(0, rows.size() * ROW_H - (listBottom - ry0) + 2);
    }

    private float searchX0() {
        return Math.max(x0 + 150, x1 - 30 - 176);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics ms, int mouseX, int mouseY, float partialTicks) {
        layout();
        float a = open.target(1f).get();
        GuiDraw.alpha = a;
        try {
            draw(ms, mouseX, mouseY);
        } finally {
            GuiDraw.alpha = 1f;
            GuiDraw.endScissor();
        }
    }

    private void draw(GuiGraphics ms, int mx, int my) {
        int accent = Theme.accent();
        long now = System.currentTimeMillis();
        GuiDraw.gradV(ms, 0, 0, width, height, 0x8C07090D, 0xB407090D);
        GuiDraw.shadow(ms, x0, y0, x1, y1, 5, 10, 0x90);
        GuiDraw.roundBorder(ms, x0, y0, x1, y1, 5, 0xFF2B3344, 0xF0161B26, 0xF20E1118);
        GuiDraw.rect(ms, x0 + 3, y0 + 0.5f, x1 - 3, y0 + 1, 0x33FFFFFF);

        drawHeader(ms, mx, my, accent, now);
        drawSidebar(ms, mx, my, accent);
        Entry hov = drawContent(ms, mx, my, accent, now);
        drawFooter(ms, mx, my, accent, now);
        drawDropdown(ms, mx, my, accent);

        if (hov != hovered) {
            hovered = hov;
            hoverSince = now;
        }
        if (hovered != null && editing == null && capturing == null && dragging == null && now - hoverSince > 350) {
            drawTooltip(ms, hovered, mx, my, accent);
        }
    }

    private void drawHeader(GuiGraphics ms, int mx, int my, int accent, long now) {
        GuiDraw.icon(ms, Icons.NOTE, x0 + 12, y0 + 9, accent, 1.1f);
        float lw = GuiDraw.textColors(ms, "Ostinato", x0 + 28, y0 + 9, 1.5f, true, Theme.logoColors(accent, 8), true);
        String chip = "Baritone " + SharedConstants.getCurrentVersion().getName();
        float cx = x0 + 28 + lw + 8, cw = GuiDraw.width(chip) + 10;
        if (cx + cw < searchX0() - 6) {
            GuiDraw.round(ms, cx, y0 + 10, cx + cw, y0 + 21, 2.5f, 0x1A7AA2F7);
            GuiDraw.text(ms, chip, cx + 5, y0 + 12, 0xFF9DB4F0, false);
        }
        // search
        float sx0 = searchX0(), sx1 = x1 - 30, sy0 = y0 + 8, sy1 = y0 + 23;
        boolean sHover = in(mx, my, sx0, sy0, sx1, sy1);
        GuiDraw.roundBorder(ms, sx0, sy0, sx1, sy1, 3, searchFocused ? accent : (sHover ? 0xFF3A4458 : 0xFF2A3242), Theme.FIELD);
        GuiDraw.icon(ms, Icons.SEARCH, sx0 + 6, sy0 + 4, searchFocused ? accent : Theme.MUTED, 0.9f);
        float tx = sx0 + 17;
        if (search.isEmpty() && !searchFocused) {
            GuiDraw.text(ms, GuiDraw.trim("Search " + all.size() + " settings...", sx1 - tx - 30, 1f), tx, sy0 + 4, Theme.DIM, false);
            String kb = "Ctrl+F";
            float kw = GuiDraw.width(kb, 0.5f, false) + 6;
            GuiDraw.round(ms, sx1 - kw - 4, sy0 + 3.5f, sx1 - 4, sy1 - 3.5f, 1.5f, 0xFF1C2230);
            GuiDraw.text(ms, kb, sx1 - kw - 1, sy0 + 5.5f, 0.5f, Theme.MUTED, false, false);
        } else {
            drawInput(ms, search, tx, sy0 + 4, sx1 - tx - 5, searchFocused, accent, now);
        }
        // close
        boolean ch = in(mx, my, x1 - 23, y0 + 8, x1 - 8, y0 + 23);
        GuiDraw.round(ms, x1 - 23, y0 + 8, x1 - 8, y0 + 23, 3, ch ? 0x30E5534B : 0x14FFFFFF);
        GuiDraw.icon(ms, Icons.CLOSE, x1 - 19, y0 + 12, ch ? Theme.TEXT : Theme.MUTED, 1f);
        GuiDraw.rect(ms, x0 + 1, hb, x1 - 1, hb + 1, 0x16FFFFFF);
    }

    /** Text with caret / selection, scrolled so the caret stays visible. */
    void drawInput(GuiGraphics ms, TextInput in, float x, float y, float maxW, boolean focused, int accent, long now) {
        String t = in.get();
        String before = t.substring(0, in.caret());
        int start = 0;
        while (start < before.length() && GuiDraw.width(before.substring(start)) > maxW - 4) {
            start++;
        }
        String vis = t.substring(start);
        vis = GuiDraw.trim(vis, maxW, 1f).equals(vis) ? vis : trimNoDots(vis, maxW);
        if (in.isAllSelected() && focused) {
            GuiDraw.rect(ms, x - 1, y - 1, x + GuiDraw.width(vis) + 1, y + 9, GuiDraw.alphaOf(accent, 0x55));
        }
        GuiDraw.text(ms, vis, x, y, Theme.TEXT, false);
        if (focused && (now / 530) % 2 == 0) {
            float cx = x + GuiDraw.width(before.substring(start));
            GuiDraw.rect(ms, cx, y - 1, cx + 0.5f, y + 9, Theme.TEXT);
        }
    }

    private static String trimNoDots(String s, float maxW) {
        String t = s;
        while (!t.isEmpty() && GuiDraw.width(t) > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private void drawSidebar(GuiGraphics ms, int mx, int my, int accent) {
        GuiDraw.rect(ms, x0 + 0.5f, hb + 1, sbx1, fy, 0x70080A0F);
        GuiDraw.rect(ms, sbx1, hb + 1, sbx1 + 1, fy, 0x12FFFFFF);
        GuiDraw.scissor(x0, hb + 1, sbx1, fy);
        float iy = hb + 8 - sideScroll;
        boolean searching = !search.get().trim().isEmpty();
        iy = sideItem(ms, iy, "Tasks", TaskService.INSTANCE.list().size(), TASKS_ICON, tasksView && !searching, tasksHover, mx, my, accent, false,
                TaskService.INSTANCE.runner.active());
        GuiDraw.rect(ms, x0 + 10, iy + 2, sbx1 - 10, iy + 3, 0x10FFFFFF);
        iy += 6;
        iy = sideItem(ms, iy, "Modified", countModified(), null, modifiedView && !tasksView && !searching, modHover, mx, my, accent, true, false);
        GuiDraw.rect(ms, x0 + 10, iy + 2, sbx1 - 10, iy + 3, 0x10FFFFFF);
        iy += 6;
        for (SettingCategory c : SettingCategory.values()) {
            List<Entry> l = byCat.get(c);
            if (l == null || l.isEmpty()) {
                continue;
            }
            iy = sideItem(ms, iy, c.displayName, l.size(), icon(c), !modifiedView && !tasksView && !searching && c == selected, catHover.get(c), mx, my, accent, false, false);
        }
        GuiDraw.endScissor();
        if (maxSideScroll > 0) {
            float trk = fy - hb - 4, th = Math.max(12, trk * trk / (trk + maxSideScroll));
            float ty = hb + 2 + (trk - th) * (sideScroll / maxSideScroll);
            GuiDraw.round(ms, sbx1 - 3, ty, sbx1 - 1, ty + th, 1, 0x40FFFFFF);
        }
        // mini status card if there is room
        float cy0 = fy - 36, cx0 = x0 + 7, cx1 = sbx1 - 7;
        if (!sideCard) {
            return;
        }
        if (settings.swarmEnabled.value && iy + 12 <= cy0 && BaritoneAPI.getProvider().getPrimaryBaritone() instanceof Baritone) {
            String sw = ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getSwarmBehavior().summary();
            boolean up = sw.startsWith("online");
            GuiDraw.icon(ms, Icons.DOT, cx0 + 6, cy0 - 5, up ? Theme.GREEN : Theme.AMBER, 0.75f);
            GuiDraw.text(ms, GuiDraw.trim("Swarm " + sw, cx1 - cx0 - 12, 0.5f), cx0 + 11, cy0 - 5.5f, 0.5f, Theme.MUTED, false, false);
        }
        {
            PathStatus st = PathStatus.capture(BaritoneAPI.getProvider().getPrimaryBaritone());
            GuiDraw.roundBorder(ms, cx0, cy0, cx1, fy - 7, 3, 0xFF232A38, 0xFF0F131B);
            int col = st.state == PathStatus.State.PATHING ? Theme.GREEN : st.state == PathStatus.State.CALCULATING ? Theme.AMBER : Theme.MUTED;
            GuiDraw.icon(ms, Icons.DOT, cx0 + 6, cy0 + 6, col, 0.75f);
            GuiDraw.text(ms, st.state.name(), cx0 + 11, cy0 + 5.5f, 0.5f, col, false, false);
            String eta = st.active() ? "ETA " + HudFormat.seconds(st.etaGoal) : "";
            GuiDraw.text(ms, eta, cx1 - 5 - GuiDraw.width(eta, 0.5f, false), cy0 + 5.5f, 0.5f, Theme.MUTED, false, false);
            GuiDraw.text(ms, GuiDraw.trim(st.goal.isEmpty() ? "No goal" : st.goal, cx1 - cx0 - 10, 0.5f), cx0 + 5, cy0 + 12, 0.5f, Theme.TEXT, false, false);
            GuiDraw.round(ms, cx0 + 5, cy0 + 21, cx1 - 5, cy0 + 23, 1, 0xFF1D2330);
            if (st.length > 0) {
                GuiDraw.round(ms, cx0 + 5, cy0 + 21, cx0 + 5 + Math.max(2, (cx1 - cx0 - 10) * st.position / (float) st.length), cy0 + 23, 1, accent);
            }
        }
    }

    private int countModified() {
        int n = 0;
        for (Entry e : all) {
            if (isModified(e)) {
                n++;
            }
        }
        return n;
    }

    private float sideItem(GuiGraphics ms, float iy, String label, int count, ItemStack icon, boolean sel, Anim hov,
                           int mx, int my, int accent, boolean special, boolean live) {
        float ix0 = x0 + 6, ix1 = sbx1 - 6, h = sideH;
        float hv = hov.target(in(mx, my, ix0, iy, ix1, iy + h)).get();
        if (sel) {
            GuiDraw.round(ms, ix0, iy, ix1, iy + h, 3, GuiDraw.alphaOf(accent, 0x22));
            GuiDraw.gradH(ms, ix0 + 2, iy, ix0 + 50, iy + h, GuiDraw.alphaOf(accent, 0x22), GuiDraw.alphaOf(accent, 0));
            GuiDraw.round(ms, ix0, iy + 3, ix0 + 2, iy + h - 3, 1, accent);
        } else if (hv > 0.01f) {
            GuiDraw.round(ms, ix0, iy, ix1, iy + h, 3, GuiDraw.alphaOf(0xFFFFFF, Math.round(0x10 * hv)));
        }
        if (special) {
            GuiDraw.icon(ms, Icons.DOT, ix0 + 8, iy + 5.5f, Theme.AMBER, 1f);
        } else {
            GuiDraw.item(icon, ix0 + 6, iy + 1.5f, 12);
        }
        float maxLabel = ix1 - (ix0 + 23) - 18;
        GuiDraw.text(ms, GuiDraw.trim(label, maxLabel, 1f), ix0 + 23 + hv, iy + 3.5f, sel ? Theme.TEXT : GuiDraw.lerp(0xFFB8C0CE, Theme.TEXT, hv), sel);
        String cs = String.valueOf(count);
        float cw = GuiDraw.width(cs, 0.5f, false);
        GuiDraw.round(ms, ix1 - cw - 10, iy + 4, ix1 - 4, iy + h - 4, 2, sel ? GuiDraw.alphaOf(accent, 0x33) : 0x14FFFFFF);
        GuiDraw.text(ms, cs, ix1 - cw - 7, iy + 5.5f, 0.5f, sel ? accent : Theme.MUTED, false, false);
        if (live) {
            GuiDraw.icon(ms, Icons.DOT, ix1 - cw - 17, iy + 5.5f, Theme.GREEN, 0.75f);
        }
        return iy + h + 1;
    }

    private String title() {
        if (!search.get().trim().isEmpty()) {
            return "Search";
        }
        return modifiedView ? "Modified" : selected.displayName;
    }

    private boolean showTasks() {
        return tasksView && search.get().trim().isEmpty();
    }

    private static final float DROP_ITEM_H = 12;

    /** Option list box of the open dropdown: {x0, y0, x1, y1, visibleCount}. */
    private float[] dropBox() {
        Entry e = dropdown;
        float cx = px1 - 14, cyc = rowY(e) + (ROW_H - 2) / 2f;
        int n = e.options.length;
        int fit = Math.max(1, (int) ((fy - hb - 4) / DROP_ITEM_H));
        int vis = Math.min(n, fit);
        float h = vis * DROP_ITEM_H + 2;
        float top = cyc + 8;
        if (top + h > fy) {
            top = Math.max(hb + 2, cyc - 8 - h);
        }
        return new float[]{cx - controlWidth(e.kind), top, cx, top + h, vis};
    }

    private int dropScroll;

    private void drawDropdown(GuiGraphics ms, int mx, int my, int accent) {
        if (dropdown == null) {
            return;
        }
        float[] b = dropBox();
        int vis = (int) b[4];
        dropScroll = Math.max(0, Math.min(dropScroll, dropdown.options.length - vis));
        GuiDraw.shadow(ms, b[0], b[1], b[2], b[3], 3, 4, 0x80);
        GuiDraw.roundBorder(ms, b[0], b[1], b[2], b[3], 3, 0xFF3E4860, 0xFA161B26);
        String cur = valueString(dropdown);
        for (int i = 0; i < vis; i++) {
            int idx = i + dropScroll;
            String opt = dropdown.options[idx];
            float iy = b[1] + 1 + i * DROP_ITEM_H;
            boolean h = in(mx, my, b[0], iy, b[2], iy + DROP_ITEM_H);
            boolean sel = opt.equalsIgnoreCase(cur);
            if (h) {
                GuiDraw.rect(ms, b[0] + 1, iy, b[2] - 1, iy + DROP_ITEM_H, 0x22FFFFFF);
            }
            GuiDraw.text(ms, GuiDraw.trim(opt, b[2] - b[0] - 10, 1f), b[0] + 6, iy + 2, sel ? accent : Theme.TEXT, false);
        }
    }

    /** Returns true if the click was consumed by the open dropdown (always closes it). */
    private boolean clickDropdown(double mx, double my) {
        float[] b = dropBox();
        Entry e = dropdown;
        dropdown = null;
        if (in(mx, my, b[0], b[1], b[2], b[3])) {
            int idx = (int) ((my - b[1] - 1) / DROP_ITEM_H) + dropScroll;
            if (idx >= 0 && idx < e.options.length) {
                select(e, idx);
            }
            return true;
        }
        // clicking the same box again just closes it
        float cx = px1 - 14, cyc = rowY(e) + (ROW_H - 2) / 2f;
        return in(mx, my, cx - controlWidth(e.kind), cyc - 7, cx, cyc + 7);
    }

    private Entry drawContent(GuiGraphics ms, int mx, int my, int accent, long now) {
        if (showTasks()) {
            tasks.layout(px0, px1, hb, fy);
            tasks.draw(ms, mx, my, accent, now);
            return null;
        }
        String title = title();
        float tw = GuiDraw.text(ms, title, px0, hb + 9, 1f, Theme.TEXT, true, true);
        int mods = 0;
        for (Entry e : rows) {
            if (isModified(e)) {
                mods++;
            }
        }
        boolean searching = !search.get().trim().isEmpty();
        GuiDraw.text(ms, rows.size() + (searching ? " results" : " settings"), px0 + tw + 6, hb + 10.5f, 0.5f, Theme.MUTED, false, false);
        if (mods > 0) {
            GuiDraw.text(ms, mods + " modified", px0 + tw + 6, hb + 15.5f, 0.5f, Theme.AMBER, false, false);
        }
        // filter chips (right aligned)
        float chx = px1;
        String[] labels = {"Modified", "Toggles", "All"};
        Filter[] fs = {Filter.MODIFIED, Filter.TOGGLES, Filter.ALL};
        for (int i = 0; i < 3; i++) {
            float w = GuiDraw.width(labels[i]) + 12;
            chx -= w;
            boolean on = filter == fs[i], h = in(mx, my, chx, hb + 6, chx + w, hb + 20);
            GuiDraw.round(ms, chx, hb + 6, chx + w, hb + 20, 3, on ? GuiDraw.alphaOf(accent, 0x2A) : (h ? 0x1AFFFFFF : 0x0FFFFFFF));
            GuiDraw.text(ms, labels[i], chx + 6, hb + 9, on ? accent : (h ? Theme.TEXT : Theme.MUTED), false);
            chx -= 3;
        }

        float scroll = scrollAnim.target(scrollTarget).get();
        Entry hov = null;
        GuiDraw.scissor(px0 - 2, ry0, px1 + 1, listBottom);
        boolean mouseInList = in(mx, my, px0, ry0, px1 - 6, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            float ry = ry0 + i * ROW_H - scroll;
            if (ry + ROW_H < ry0 || ry > listBottom) {
                continue;
            }
            Entry e = rows.get(i);
            boolean h = mouseInList && in(mx, my, px0, ry, px1 - 6, ry + ROW_H - 2);
            if (h) {
                hov = e;
            }
            drawRow(ms, e, ry, h, mx, my, accent, now, searching);
        }
        if (rows.isEmpty()) {
            String msg = searching ? "No settings match \"" + search.get().trim() + "\"" : "Nothing here yet";
            GuiDraw.text(ms, msg, (px0 + px1) / 2 - GuiDraw.width(msg) / 2f, ry0 + 30, Theme.DIM, false);
        }
        GuiDraw.endScissor();
        // scrollbar + bottom fade
        float max = maxScroll();
        if (max > 0) {
            float trk0 = ry0, trk1 = listBottom, th = Math.max(16, (trk1 - trk0) * (trk1 - trk0) / (rows.size() * ROW_H));
            float ty = trk0 + (trk1 - trk0 - th) * (scroll / max);
            GuiDraw.round(ms, px1 - 3, trk0, px1 - 1, trk1, 1, 0x12FFFFFF);
            boolean th2 = draggingThumb || in(mx, my, px1 - 5, ty, px1 + 1, ty + th);
            GuiDraw.round(ms, px1 - 3, ty, px1 - 1, ty + th, 1, th2 ? 0x90FFFFFF : 0x60FFFFFF);
            if (scroll < max - 1) {
                GuiDraw.gradV(ms, px0, listBottom - 10, px1 - 6, listBottom, 0x000E1118, 0x900E1118);
            }
        }
        return hov;
    }

    private void drawRow(GuiGraphics ms, Entry e, float ry, boolean hov, int mx, int my, int accent, long now, boolean searching) {
        float hv = e.hover.target(hov).get();
        boolean mod = isModified(e);
        float rb = ry + ROW_H - 2;
        GuiDraw.round(ms, px0, ry, px1 - 6, rb, 3, GuiDraw.lerp(0x0CFFFFFF, 0x1AFFFFFF, hv));
        if (hv > 0.02f) {
            GuiDraw.round(ms, px0, ry + 4, px0 + 2, rb - 4, 1, GuiDraw.alphaOf(accent, Math.round(255 * hv)));
        }
        float nx = px0 + 8;
        if (mod) {
            GuiDraw.icon(ms, Icons.DOT, px0 + 5, ry + 6.5f, Theme.AMBER, 0.75f);
            nx += 2;
        }
        float cx = px1 - 14, cyc = ry + (ROW_H - 2) / 2f;
        int cw = controlWidth(e.kind);
        float descMax = cx - 104 - 16 - nx;
        float nameMax = cx - cw - 20 - nx;
        float w = GuiDraw.text(ms, GuiDraw.trim(e.name, nameMax, 1f), nx, ry + 4, mod ? 0xFFFFFFFF : Theme.TEXT, true);
        float bx = nx + w + 5;
        if (e.ostinato && bx + 40 < cx - cw - 16) {
            bx = badge(ms, bx, ry, "OSTINATO", accent, GuiDraw.alphaOf(accent, 0x22));
        }
        if (e.experimental && bx + 56 < cx - cw - 16) {
            bx = badge(ms, bx, ry, "EXPERIMENTAL", Theme.AMBER, 0x22E8C96A);
        }
        if (searching && bx + 40 < cx - cw - 16) {
            badge(ms, bx, ry, e.category.displayName.toUpperCase(Locale.ROOT), Theme.MUTED, 0x14FFFFFF);
        }
        String d = e.doc.isEmpty() ? "(no description)" : e.doc;
        GuiDraw.text(ms, GuiDraw.trim(d, Math.max(20, descMax), 0.5f), nx, ry + 14.5f, 0.5f, Theme.MUTED, false, false);

        // reset button for modified rows
        if (mod) {
            float rx0 = cx - cw - 16, rx1 = rx0 + 12;
            boolean rh = in(mx, my, rx0, cyc - 6, rx1, cyc + 6);
            GuiDraw.round(ms, rx0, cyc - 6, rx1, cyc + 6, 2.5f, rh ? GuiDraw.alphaOf(accent, 0x33) : (hov ? GuiDraw.alphaOf(accent, 0x1A) : 0x10FFFFFF));
            GuiDraw.icon(ms, Icons.RESET, rx0 + 2, cyc - 4, rh || hov ? accent : Theme.MUTED, 0.9f);
        }
        if (editing == e) {
            float ex1 = cx, ex0 = cx - Math.max(cw, Math.min(150, cx - nx - 40));
            GuiDraw.roundBorder(ms, ex0, cyc - 7, ex1, cyc + 7, 3, editError ? Theme.DANGER : accent, Theme.FIELD);
            drawInput(ms, editor, ex0 + 5, cyc - 3.5f, ex1 - ex0 - 10, true, accent, now);
            return;
        }
        switch (e.kind) {
            case TOGGLE: {
                boolean on = e.on();
                float k = e.knob.target(on).get();
                float tx0 = cx - 22, tx1 = cx;
                int trackTop = GuiDraw.lerp(0xFF1E2430, Theme.lighter(accent), k), trackBot = GuiDraw.lerp(0xFF1E2430, Theme.darker(accent), k);
                GuiDraw.roundBorder(ms, tx0, cyc - 5.5f, tx1, cyc + 5.5f, 5.5f, GuiDraw.lerp(0xFF343C4C, Theme.darker(accent), k), trackTop, trackBot);
                float kx = tx0 + 1.5f + (tx1 - tx0 - 12) * k;
                GuiDraw.round(ms, kx - 0.5f, cyc - 3.5f, kx + 9.5f, cyc + 5.5f, 4.5f, 0x40000000);
                GuiDraw.round(ms, kx, cyc - 4.5f, kx + 9, cyc + 4.5f, 4.5f, GuiDraw.lerp(0xFF9AA3B4, 0xFFFFFFFF, k));
                break;
            }
            case SLIDER: {
                SettingRanges.Range r = e.range;
                float vb = 30, tr1 = cx - vb - 6, tr0 = tr1 - 62;
                float t = (float) r.fraction(e.number());
                GuiDraw.round(ms, tr0, cyc - 1.5f, tr1, cyc + 1.5f, 1.5f, 0xFF252C3A);
                GuiDraw.round(ms, tr0, cyc - 1.5f, tr0 + (tr1 - tr0) * t, cyc + 1.5f, 1.5f, accent);
                float kx = tr0 + (tr1 - tr0) * t;
                boolean kh = dragging == e || in(mx, my, tr0 - 3, cyc - 6, tr1 + 3, cyc + 6);
                GuiDraw.round(ms, kx - 3, cyc - 5, kx + 3, cyc + 5, 2, kh ? 0xFFFFFFFF : 0xFFE0E4EA);
                valueBox(ms, cx - vb, cyc, cx, fmtNumber(e), mx, my);
                break;
            }
            case NUMBER:
                valueBox(ms, cx - cw, cyc, cx, fmtNumber(e), mx, my);
                break;
            case CYCLE: {
                float bx0 = cx - cw;
                boolean bh = in(mx, my, bx0, cyc - 7, cx, cyc + 7);
                GuiDraw.roundBorder(ms, bx0, cyc - 7, cx, cyc + 7, 3, bh ? 0xFF3E4860 : Theme.FIELD_BORDER, Theme.FIELD);
                GuiDraw.text(ms, GuiDraw.trim(valueString(e), cw - 20, 1f), bx0 + 6, cyc - 3.5f, Theme.TEXT, false);
                GuiDraw.icon(ms, Icons.CHEVRON, cx - 10, cyc - 1, bh ? accent : Theme.MUTED, 1f);
                break;
            }
            case COLOR: {
                Color c = (Color) e.s.value;
                int rgb = 0xFF000000 | (c.getRGB() & 0xFFFFFF);
                float sx0 = cx - cw;
                GuiDraw.roundBorder(ms, sx0, cyc - 6, sx0 + 12, cyc + 6, 2.5f, 0xFF3A4458, rgb);
                valueBox(ms, sx0 + 16, cyc, cx, valueString(e), mx, my);
                break;
            }
            case KEYBIND: {
                float bx0 = cx - cw;
                boolean cap = capturing == e;
                boolean bh = in(mx, my, bx0, cyc - 7, cx, cyc + 7);
                GuiDraw.roundBorder(ms, bx0, cyc - 7, cx, cyc + 7, 3, cap ? accent : (bh ? 0xFF3E4860 : Theme.FIELD_BORDER), Theme.FIELD);
                String label = cap ? "Press a key..." : valueString(e);
                GuiDraw.text(ms, GuiDraw.trim(label, cw - 10, 1f), bx0 + (cw - Math.min(cw - 10, GuiDraw.width(label))) / 2f, cyc - 3.5f,
                        cap ? accent : Theme.TEXT, false);
                break;
            }
            default:
                valueBox(ms, cx - cw, cyc, cx, valueString(e), mx, my);
        }
    }

    private void valueBox(GuiGraphics ms, float bx0, float cyc, float bx1, String v, int mx, int my) {
        boolean h = in(mx, my, bx0, cyc - 7, bx1, cyc + 7);
        GuiDraw.roundBorder(ms, bx0, cyc - 7, bx1, cyc + 7, 3, h ? 0xFF3E4860 : Theme.FIELD_BORDER, Theme.FIELD);
        String s = GuiDraw.trim(v, bx1 - bx0 - 8, 1f);
        GuiDraw.text(ms, s, (bx0 + bx1) / 2f - GuiDraw.width(s) / 2f, cyc - 3.5f, Theme.TEXT, false);
    }

    private static String fmtNumber(Entry e) {
        Object v = e.s.value;
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Math.abs(d) >= 100 || d == Math.rint(d)) {
                return String.format(Locale.ROOT, "%.1f", d);
            }
            String s = String.format(Locale.ROOT, "%.3f", d);
            s = s.replaceAll("0+$", "");
            return s.endsWith(".") ? s + "0" : s;
        }
        return String.valueOf(v);
    }

    private float badge(GuiGraphics ms, float bx, float ry, String label, int col, int bg) {
        float w = GuiDraw.width(label, 0.5f, false) + 6;
        GuiDraw.round(ms, bx, ry + 4.5f, bx + w, ry + 11.5f, 1.5f, bg);
        GuiDraw.text(ms, label, bx + 3, ry + 6, 0.5f, col, false, false);
        return bx + w + 3;
    }

    private void drawFooter(GuiGraphics ms, int mx, int my, int accent, long now) {
        GuiDraw.rect(ms, x0 + 1, fy, x1 - 1, fy + 1, 0x12FFFFFF);
        float ty = fy + 9;
        float fx = x0 + 12;
        boolean tv = showTasks();
        if (tv) {
            int n = TaskService.INSTANCE.list().size();
            fx += GuiDraw.text(ms, n + (n == 1 ? " step" : " steps"), fx, ty, Theme.MUTED, false) + 5;
            GuiDraw.rect(ms, fx, ty + 3, fx + 1.5f, ty + 4.5f, Theme.DIM);
            fx += 6;
            boolean loop = TaskService.INSTANCE.list().loop();
            fx += GuiDraw.text(ms, loop ? "loop on" : "loop off", fx, ty, loop ? accent : Theme.MUTED, false) + 5;
        } else {
            fx += GuiDraw.text(ms, all.size() + " settings", fx, ty, Theme.MUTED, false) + 5;
            GuiDraw.rect(ms, fx, ty + 3, fx + 1.5f, ty + 4.5f, Theme.DIM);
            fx += 6;
            int mods = countModified();
            fx += GuiDraw.text(ms, mods + " modified", fx, ty, Theme.AMBER, false) + 5;
        }
        GuiDraw.rect(ms, fx, ty + 3, fx + 1.5f, ty + 4.5f, Theme.DIM);
        fx += 6;
        String info;
        int infoCol;
        if (status != null && now < statusUntil) {
            info = status;
            infoCol = statusColor;
        } else if (tv) {
            info = tasks.footerStatus();
            infoCol = tasks.footerStatusColor();
        } else if (store.lastError() != null) {
            info = "Save failed: " + store.lastError();
            infoCol = Theme.DANGER;
        } else if (store.hasPending()) {
            info = "saving...";
            infoCol = Theme.MUTED;
        } else if (store.lastSaved() > 0 && now - store.lastSaved() < 2500) {
            info = "saved to settings.txt";
            infoCol = Theme.GREEN;
        } else {
            info = "GUI edits save to settings.txt";
            infoCol = Theme.DIM;
        }
        // buttons (right)
        float bxr = button(ms, "Done", x1 - 10, true, mx, my, accent) - 5;
        bxr = button(ms, freecamLabel(), bxr, false, mx, my, accent) - 5;
        boolean confirm = now < resetConfirmUntil;
        if (!tv) {
            bxr = button(ms, confirm ? "Click to confirm" : "Reset category", bxr, false, mx, my, confirm ? Theme.DANGER : accent) - 10;
        } else {
            bxr -= 5;
        }
        // key hints
        String key = KeyNames.name(KeyNames.parse(settings.guiKeybind.value));
        float hintsW = hintWidth("ESC", "close") + hintWidth(key, "toggle");
        float limit = bxr - hintsW;
        GuiDraw.text(ms, GuiDraw.trim(info, Math.max(20, limit - fx - 6), 1f), fx, ty, infoCol, false);
        bxr = hint(ms, bxr, "ESC", "close");
        hint(ms, bxr, key, "toggle");
    }

    private static String freecamLabel() {
        baritone.api.IBaritone b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean on = b instanceof baritone.Baritone && ((baritone.Baritone) b).getFreecamBehavior().isActive();
        return on ? "Freecam: on" : "Freecam";
    }

    private float hintWidth(String k, String label) {
        return GuiDraw.width(label, 0.5f, false) + GuiDraw.width(k, 0.5f, false) + 6 + 3 + 8;
    }

    private float hint(GuiGraphics ms, float bxr, String k, String label) {
        float lw = GuiDraw.width(label, 0.5f, false), kw = GuiDraw.width(k, 0.5f, false) + 6;
        bxr -= lw;
        GuiDraw.text(ms, label, bxr, fy + 10, 0.5f, Theme.DIM, false, false);
        bxr -= kw + 3;
        GuiDraw.roundBorder(ms, bxr, fy + 7.5f, bxr + kw, fy + 16, 1.5f, 0xFF303848, 0xFF1A202B);
        GuiDraw.text(ms, k, bxr + 3, fy + 10, 0.5f, Theme.MUTED, false, false);
        return bxr - 8;
    }

    private float button(GuiGraphics ms, String label, float xr, boolean primary, int mx, int my, int accent) {
        float w = GuiDraw.width(label) + 16, bx0 = xr - w;
        boolean h = in(mx, my, bx0, fy + 5, xr, fy + 19);
        if (primary) {
            int top = h ? GuiDraw.lerp(accent, 0xFFFFFFFF, 0.25f) : Theme.lighter(accent);
            GuiDraw.round(ms, bx0, fy + 5, xr, fy + 19, 3, top, Theme.darker(accent));
            GuiDraw.text(ms, label, bx0 + 8, fy + 8.5f, 0xFF0B0D12, false);
        } else {
            GuiDraw.roundBorder(ms, bx0, fy + 5, xr, fy + 19, 3, h ? 0xFF3E4860 : Theme.FIELD_BORDER, h ? 0xFF1C2230 : 0xFF161B25);
            GuiDraw.text(ms, label, bx0 + 8, fy + 8.5f, accent == Theme.DANGER ? Theme.DANGER : Theme.TEXT, false);
        }
        return bx0;
    }

    private void drawTooltip(GuiGraphics ms, Entry e, int mx, int my, int accent) {
        float tw = 200;
        List<String> lines = GuiDraw.wrap(e.doc.isEmpty() ? "No description." : e.doc, tw - 16, 1f);
        if (lines.size() > 8) {
            lines = new ArrayList<>(lines.subList(0, 8));
            lines.set(7, lines.get(7) + " ...");
        }
        float th = 26 + lines.size() * 10 + 45;
        float tx0 = mx + 10, ty0 = my + 8;
        if (tx0 + tw > width - 4) {
            tx0 = mx - 10 - tw;
        }
        if (ty0 + th > height - 4) {
            ty0 = Math.max(4, my - 8 - th);
        }
        ms.pose().pushPose();
        ms.pose().translate(0, 0, 400); // above item icons
        GuiDraw.shadow(ms, tx0, ty0, tx0 + tw, ty0 + th, 4, 7, 0x80);
        GuiDraw.roundBorder(ms, tx0, ty0, tx0 + tw, ty0 + th, 4, 0xFF34405A, 0xFA151A24, 0xFA0C0F16);
        GuiDraw.gradH(ms, tx0 + 4, ty0 + 0.5f, tx0 + tw - 4, ty0 + 1, GuiDraw.alphaOf(accent, 0), GuiDraw.alphaOf(accent, 0xAA));
        GuiDraw.text(ms, GuiDraw.trim(e.name, tw - 60, 1f), tx0 + 8, ty0 + 8, 1f, 0xFFFFFFFF, true, true);
        String tag = e.ostinato ? "OSTINATO" : e.category.displayName.toUpperCase(Locale.ROOT);
        float tbw = GuiDraw.width(tag, 0.5f, false) + 6, tbx = tx0 + tw - 8 - tbw;
        GuiDraw.round(ms, tbx, ty0 + 8.5f, tbx + tbw, ty0 + 15.5f, 1.5f, e.ostinato ? GuiDraw.alphaOf(accent, 0x22) : 0x14FFFFFF);
        GuiDraw.text(ms, tag, tbx + 3, ty0 + 10, 0.5f, e.ostinato ? accent : Theme.MUTED, false, false);
        float ly = ty0 + 22;
        for (String l : lines) {
            GuiDraw.text(ms, l, tx0 + 8, ly, 0xFFC4CAD6, false);
            ly += 10;
        }
        ly += 3;
        GuiDraw.rect(ms, tx0 + 8, ly, tx0 + tw - 8, ly + 0.5f, 0x18FFFFFF);
        ly += 5;
        boolean mod = isModified(e);
        float kx = tx0 + 8;
        kx = kv(ms, "TYPE", GuiDraw.trim(typeName(e), 50, 1f), kx, ly, Theme.TEXT) + 8;
        kx = kv(ms, "DEFAULT", GuiDraw.trim(defaultString(e), 44, 1f), kx, ly, Theme.TEXT) + 8;
        kv(ms, "NOW", GuiDraw.trim(valueString(e), Math.max(10, tx0 + tw - 8 - kx - 20), 1f), kx, ly, mod ? Theme.AMBER : Theme.TEXT);
        ly += 12;
        GuiDraw.round(ms, tx0 + 8, ly, tx0 + tw - 8, ly + 13, 2, 0xFF07090D);
        String cmd = settings.prefix.value + "set " + e.name + " " + valueString(e);
        GuiDraw.text(ms, GuiDraw.trim(cmd, tw - 26, 1f), tx0 + 13, ly + 3, accent, false);
        GuiDraw.text(ms, mod ? "right-click row or the reset icon: reset to default" : "right-click row: reset to default", tx0 + 9, ly + 17, 0.5f, Theme.DIM, false, false);
        ms.pose().popPose();
    }

    private float kv(GuiGraphics ms, String k, String v, float x, float y, int col) {
        float kw = GuiDraw.text(ms, k, x, y + 1, 0.5f, Theme.DIM, false, false);
        return x + kw + 4 + GuiDraw.text(ms, v, x + kw + 4, y, col, false);
    }

    // ------------------------------------------------------------------ input

    static boolean in(double mx, double my, float ax, float ay, float bx, float by) {
        return mx >= ax && mx < bx && my >= ay && my < by;
    }

    private Entry rowAt(double mx, double my) {
        if (!in(mx, my, px0, ry0, px1 - 6, listBottom)) {
            return null;
        }
        int i = (int) Math.floor((my - ry0 + scrollAnim.get()) / ROW_H);
        if (i < 0 || i >= rows.size()) {
            return null;
        }
        float ry = ry0 + i * ROW_H - scrollAnim.get();
        return my < ry + ROW_H - 2 ? rows.get(i) : null;
    }

    private float rowY(Entry e) {
        return ry0 + rows.indexOf(e) * ROW_H - scrollAnim.get();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (capturing != null) {
            capturing = null;
            return true;
        }
        if (dropdown != null && clickDropdown(mx, my)) {
            return true;
        }
        if (editing != null) {
            float cyc = rowY(editing) + (ROW_H - 2) / 2f, cx = px1 - 14;
            if (!in(mx, my, px0, cyc - 7, cx, cyc + 7)) {
                commitEditor();
                if (editing != null) {
                    return true; // keep the bad value visible
                }
            } else {
                return true;
            }
        }
        if (tasks.editing() && !(showTasks() && in(mx, my, px0, hb, px1, fy))) {
            tasks.commit();
        }
        // header
        if (in(mx, my, x1 - 23, y0 + 8, x1 - 8, y0 + 23)) {
            onClose();
            return true;
        }
        if (in(mx, my, searchX0(), y0 + 8, x1 - 30, y0 + 23)) {
            searchFocused = true;
            if (button == 1) {
                search.set("");
                rebuild();
            }
            return true;
        }
        searchFocused = false;
        // sidebar
        float iy = hb + 8 - sideScroll;
        boolean inSide = in(mx, my, x0, hb + 1, sbx1, fy);
        if (inSide && in(mx, my, x0 + 6, iy, sbx1 - 6, iy + sideH)) {
            tasksView = true;
            modifiedView = false;
            search.set("");
            searchFocused = false;
            rebuild();
            return true;
        }
        iy += sideH + 1 + 6;
        if (inSide && in(mx, my, x0 + 6, iy, sbx1 - 6, iy + sideH)) {
            tasksView = false;
            modifiedView = true;
            search.set("");
            filter = Filter.ALL;
            rebuild();
            return true;
        }
        iy += sideH + 1 + 6;
        for (SettingCategory c : SettingCategory.values()) {
            List<Entry> l = byCat.get(c);
            if (l == null || l.isEmpty()) {
                continue;
            }
            if (inSide && in(mx, my, x0 + 6, iy, sbx1 - 6, iy + sideH)) {
                tasksView = false;
                modifiedView = false;
                selected = c;
                search.set("");
                filter = Filter.ALL;
                rebuild();
                return true;
            }
            iy += sideH + 1;
        }
        float doneW0 = GuiDraw.width("Done") + 16;
        float fcw = GuiDraw.width(freecamLabel()) + 16, fcxr = x1 - 10 - doneW0 - 5;
        if (in(mx, my, fcxr - fcw, fy + 5, fcxr, fy + 19)) {
            baritone.api.IBaritone b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b instanceof baritone.Baritone) {
                baritone.behavior.FreecamBehavior fc = ((baritone.Baritone) b).getFreecamBehavior();
                boolean on = !fc.isActive();
                onClose(); // the camera needs the mouse and keys
                if (on) {
                    fc.enable();
                } else {
                    fc.disable();
                }
            }
            return true;
        }
        if (showTasks()) {
            if (in(mx, my, x1 - 10 - doneW0, fy + 5, x1 - 10, fy + 19)) {
                onClose();
                return true;
            }
            tasks.layout(px0, px1, hb, fy);
            return tasks.mouseClicked(mx, my, button) || super.mouseClicked(mx, my, button);
        }
        // filter chips
        float chx = px1;
        String[] labels = {"Modified", "Toggles", "All"};
        Filter[] fs = {Filter.MODIFIED, Filter.TOGGLES, Filter.ALL};
        for (int i = 0; i < 3; i++) {
            float w = GuiDraw.width(labels[i]) + 12;
            chx -= w;
            if (in(mx, my, chx, hb + 6, chx + w, hb + 20)) {
                filter = fs[i];
                rebuild();
                return true;
            }
            chx -= 3;
        }
        // footer buttons
        float doneW = GuiDraw.width("Done") + 16;
        if (in(mx, my, x1 - 10 - doneW, fy + 5, x1 - 10, fy + 19)) {
            onClose();
            return true;
        }
        long now = System.currentTimeMillis();
        String resetLabel = now < resetConfirmUntil ? "Click to confirm" : "Reset category";
        float rw = GuiDraw.width(resetLabel) + 16, rxr = fcxr - fcw - 5;
        if (in(mx, my, rxr - rw, fy + 5, rxr, fy + 19)) {
            if (now < resetConfirmUntil) {
                int n = 0;
                for (Entry e : rows) {
                    if (isModified(e)) {
                        e.s.reset();
                        store.touch(e.s);
                        n++;
                    }
                }
                invalidate();
                resetConfirmUntil = 0;
                flash("Reset " + n + " setting" + (n == 1 ? "" : "s") + " to default", Theme.MUTED);
            } else {
                resetConfirmUntil = now + 3000;
            }
            return true;
        }
        // scrollbar
        float max = maxScroll();
        if (max > 0 && in(mx, my, px1 - 6, ry0, px1 + 2, listBottom)) {
            float trk = listBottom - ry0, th = Math.max(16, trk * trk / (rows.size() * ROW_H));
            float ty = ry0 + (trk - th) * (scrollAnim.get() / max);
            draggingThumb = true;
            thumbGrab = (my >= ty && my < ty + th) ? (float) (my - ty) : th / 2;
            dragThumb(my);
            return true;
        }
        // rows
        Entry e = rowAt(mx, my);
        if (e != null) {
            return clickRow(e, mx, my, button);
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean clickRow(Entry e, double mx, double my, int button) {
        float ry = rowY(e), cyc = ry + (ROW_H - 2) / 2f, cx = px1 - 14;
        int cw = controlWidth(e.kind);
        if (button == 1) {
            reset(e);
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (isModified(e) && in(mx, my, cx - cw - 16, cyc - 6, cx - cw - 4, cyc + 6)) {
            reset(e);
            return true;
        }
        switch (e.kind) {
            case TOGGLE:
                if (in(mx, my, cx - 30, ry, cx + 4, ry + ROW_H) || in(mx, my, px0, ry, cx - cw - 20, ry + ROW_H)) {
                    setRaw(e, !e.on());
                }
                return true;
            case SLIDER: {
                float vb = 30, tr1 = cx - vb - 6, tr0 = tr1 - 62;
                if (in(mx, my, tr0 - 4, cyc - 7, tr1 + 4, cyc + 7)) {
                    dragging = e;
                    dragSlider(e, mx);
                } else if (in(mx, my, cx - vb, cyc - 7, cx, cyc + 7)) {
                    startEdit(e);
                }
                return true;
            }
            case CYCLE:
                if (in(mx, my, cx - cw, cyc - 7, cx, cyc + 7)) {
                    dropdown = e;
                    dropScroll = 0;
                }
                return true;
            case KEYBIND:
                if (in(mx, my, cx - cw, cyc - 7, cx, cyc + 7)) {
                    capturing = e;
                }
                return true;
            default:
                if (in(mx, my, cx - cw, cyc - 7, cx, cyc + 7)) {
                    startEdit(e);
                }
                return true;
        }
    }

    private void cycle(Entry e, int dir) {
        String cur = valueString(e);
        int idx = 0;
        for (int i = 0; i < e.options.length; i++) {
            if (e.options[i].equalsIgnoreCase(cur)) {
                idx = i;
            }
        }
        select(e, ((idx + dir) % e.options.length + e.options.length) % e.options.length);
    }

    private void select(Entry e, int next) {
        Class<?> c = e.s.getValueClass();
        if (c.isEnum()) {
            setRaw(e, c.getEnumConstants()[next]);
        } else {
            try {
                SettingsUtil.parseAndApply(settings, e.name.toLowerCase(Locale.ROOT), e.options[next]);
                changed(e);
            } catch (Throwable t) {
                flash("Could not set " + e.name + ": " + t.getMessage(), Theme.DANGER);
            }
        }
    }

    private void dragSlider(Entry e, double mx) {
        float cx = px1 - 14, tr1 = cx - 36, tr0 = tr1 - 62;
        double t = Math.max(0, Math.min(1, (mx - tr0) / (tr1 - tr0)));
        SettingRanges.Range r = e.range;
        setNumber(e, r.snap(r.min + t * (r.max - r.min)));
    }

    private void dragThumb(double my) {
        float max = maxScroll(), trk = listBottom - ry0, th = Math.max(16, trk * trk / (rows.size() * ROW_H));
        double t = (my - thumbGrab - ry0) / Math.max(1, trk - th);
        scrollTarget = (float) Math.max(0, Math.min(max, t * max));
        scrollAnim.snap(scrollTarget);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            dragSlider(dragging, mx);
            return true;
        }
        if (draggingThumb) {
            dragThumb(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        draggingThumb = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double delta) {
        layout();
        if (dropdown != null) {
            dropScroll += delta > 0 ? -1 : 1;
            return true;
        }
        if (showTasks()) {
            tasks.layout(px0, px1, hb, fy);
            return tasks.mouseScrolled(mx, my, delta);
        }
        if (maxSideScroll > 0 && in(mx, my, x0, hb + 1, sbx1, fy)) {
            sideScroll = Math.max(0, Math.min(maxSideScroll, sideScroll - (float) delta * 16));
            return true;
        }
        Entry e = rowAt(mx, my);
        if (e != null && editing == null && (e.kind == Kind.NUMBER || e.kind == Kind.SLIDER)) {
            float cyc = rowY(e) + (ROW_H - 2) / 2f, cx = px1 - 14;
            if (in(mx, my, cx - controlWidth(e.kind) - 4, cyc - 8, cx + 4, cyc + 8)) {
                double step = e.range != null ? e.range.step : SettingRanges.defaultStep(e.integral());
                if (Screen.hasShiftDown()) {
                    step *= 10;
                }
                double v = e.number() + Math.signum(delta) * step;
                setNumber(e, e.range != null ? e.range.snap(v) : Math.round(v * 1e6) / 1e6);
                return true;
            }
        }
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) delta * ROW_H * 1.5f));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (dropdown != null && key == GLFW.GLFW_KEY_ESCAPE) {
            dropdown = null;
            return true;
        }
        if (capturing != null) {
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                setRaw(capturing, KeyNames.name(key));
                flash("Settings screen key is now " + KeyNames.name(key), accentOrMuted());
            }
            capturing = null;
            return true;
        }
        if (tasks.editing()) {
            return tasks.keyPressed(key);
        }
        if (showTasks() && key == GLFW.GLFW_KEY_S && Screen.hasControlDown()) {
            tasks.save();
            return true;
        }
        if (editing != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitEditor();
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelEditor();
            } else {
                editKey(editor, key);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            searchFocused = true;
            search.selectAll();
            return true;
        }
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (!search.isEmpty()) {
                    search.set("");
                    rebuild();
                } else {
                    searchFocused = false;
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) {
                searchFocused = false;
                return true;
            }
            String before = search.get();
            if (editKey(search, key)) {
                if (!before.equals(search.get())) {
                    rebuild();
                }
                return true;
            }
        }
        // a letter bound to the screen must still be typable in the search box
        if (key == GLFW.GLFW_KEY_ESCAPE || (!searchFocused && key == KeyNames.parse(settings.guiKeybind.value))) {
            onClose();
            return true;
        }
        float page = listBottom - ry0 - ROW_H;
        switch (key) {
            case GLFW.GLFW_KEY_PAGE_DOWN: scrollTarget = Math.min(maxScroll(), scrollTarget + page); return true;
            case GLFW.GLFW_KEY_PAGE_UP: scrollTarget = Math.max(0, scrollTarget - page); return true;
            case GLFW.GLFW_KEY_DOWN: scrollTarget = Math.min(maxScroll(), scrollTarget + ROW_H); return true;
            case GLFW.GLFW_KEY_UP: scrollTarget = Math.max(0, scrollTarget - ROW_H); return true;
            case GLFW.GLFW_KEY_HOME: scrollTarget = 0; return true;
            case GLFW.GLFW_KEY_END: scrollTarget = maxScroll(); return true;
            default: return super.keyPressed(key, scan, mods);
        }
    }

    private int accentOrMuted() {
        return Theme.accent();
    }

    /** Shared editing keys for the search box and inline editors; true if handled. */
    boolean editKey(TextInput in, int key) {
        if (Screen.isSelectAll(key)) {
            in.selectAll();
        } else if (Screen.isPaste(key)) {
            in.insert(minecraft.keyboardHandler.getClipboard());
        } else if (Screen.isCopy(key)) {
            minecraft.keyboardHandler.setClipboard(in.get());
        } else {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE: in.backspace(); break;
                case GLFW.GLFW_KEY_DELETE: in.delete(); break;
                case GLFW.GLFW_KEY_LEFT: in.left(); break;
                case GLFW.GLFW_KEY_RIGHT: in.right(); break;
                case GLFW.GLFW_KEY_HOME: in.home(); break;
                case GLFW.GLFW_KEY_END: in.end(); break;
                default: return false;
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (capturing != null) {
            return true;
        }
        if (!StringUtil.isAllowedChatCharacter(c)) {
            return false;
        }
        if (editing != null) {
            editor.insert(String.valueOf(c));
            return true;
        }
        if (tasks.editing()) {
            tasks.charTyped(c);
            return true;
        }
        if (!searchFocused && showTasks()) {
            return false; // typing on the Tasks tab does not jump to settings search
        }
        if (!searchFocused) {
            searchFocused = true;
            search.end();
        }
        search.insert(String.valueOf(c));
        rebuild();
        return true;
    }

    @Override
    public void tick() {
        if (scrollTarget > maxScroll()) {
            scrollTarget = maxScroll();
        }
    }
}
