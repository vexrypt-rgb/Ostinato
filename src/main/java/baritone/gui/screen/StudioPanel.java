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
import baritone.gui.model.TextInput;
import baritone.gui.render.GuiDraw;
import baritone.gui.studio.BlockPalette;
import baritone.gui.studio.BlockTextures;
import baritone.gui.studio.EditOps;
import baritone.gui.studio.EditSession;
import baritone.gui.studio.OstinatoSchematic;
import baritone.gui.studio.StudioCamera;
import baritone.gui.studio.StudioPick;
import baritone.gui.studio.StudioVolume;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Schematic studio. Drag to orbit, scroll to zoom, middle-drag or shift-drag to pan.
 * The palette is every block. Keys 1-9 pick a tool. Type to filter the palette.
 */
final class StudioPanel {

    private final OstinatoScreen screen;
    private final StudioCamera camera = new StudioCamera();
    private final TextInput name = new TextInput(24);
    private final EditSession session = new EditSession();
    private StudioVolume volume = new StudioVolume(24, 12, 24);
    private float vx0, vy0, vx1, vy1, col0, col1;
    private float pressX, pressY, dragX, dragY;
    private int dragButton = -1;
    private boolean moved;
    private boolean nameFocused;
    private int paletteScroll;
    private long clearUntil;
    private String filter = "";
    private String viewKey;
    private int[] view = new int[0];

    StudioPanel(OstinatoScreen screen) {
        this.screen = screen;
        name.set("studio");
        session.bind(volume);
        session.brush = BlockPalette.cell("stone");
        camera.lookAt(volume.sizeX() / 2.0, volume.sizeY() / 3.0, volume.sizeZ() / 2.0);
    }

    void layout(float x0, float x1, float top, float bottom) {
        col1 = x1;
        col0 = x1 - 108;
        vx0 = x0;
        vx1 = col0 - 8;
        vy0 = top;
        vy1 = bottom;
    }

    boolean nameFocused() {
        return nameFocused;
    }

    boolean dragging() {
        return dragButton >= 0;
    }

    int blockCount() {
        return volume.count();
    }

    void draw(GuiGraphicsExtractor ms, int mx, int my, int accent, long now) {
        if (session.brush == 0) {
            session.brush = BlockPalette.cell("stone");
        }
        GuiDraw.roundBorder(ms, vx0, vy0, vx1, vy1, 4, 0xFF2A3344, 0xFF10141C);
        GuiDraw.scissor(vx0 + 1, vy0 + 1, vx1 - 1, vy1 - 1);
        drawStage(ms);
        drawBlocks(ms, mx, my);
        GuiDraw.endScissor();
        if (volume.count() == 0) {
            String hint = "Left click the floor to place a block";
            GuiDraw.text(ms, hint, (vx0 + vx1) / 2 - GuiDraw.width(hint) / 2f, (vy0 + vy1) / 2 - 4, Theme.MUTED, false);
        }
        String help = "1-9 tools   type to filter   drag orbits   scroll zooms";
        GuiDraw.text(ms, help, vx0 + 8, vy1 - 12, 0.5f, Theme.DIM, false, false);

        float toolY = vy0;
        boolean toolHot = OstinatoScreen.in(mx, my, col0, toolY, col1, toolY + 14);
        GuiDraw.roundBorder(ms, col0, toolY, col1, toolY + 14, 3, toolHot ? accent : Theme.FIELD_BORDER, Theme.FIELD);
        String tool = session.tool.wire;
        if (session.tool == EditOps.Tool.SPHERE || session.tool == EditOps.Tool.HOLLOW) {
            tool = tool + " " + session.radius;
        }
        GuiDraw.text(ms, tool, col0 + 4, toolY + 3, Theme.TEXT, false);

        float listTop = vy0 + 18;
        String caption = filter.isEmpty() ? "Block" : filter;
        GuiDraw.text(ms, caption, col0, listTop, 0.5f, filter.isEmpty() ? Theme.DIM : accent, false, false);
        int[] blocks = view();
        float row = 16;
        int fit = Math.max(1, (int) ((vy1 - listTop - 78) / row));
        paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, blocks.length - fit)));
        GuiDraw.scissor(col0, listTop + 8, col1, listTop + 8 + fit * row);
        if (blocks.length == 0) {
            GuiDraw.text(ms, "No match", col0 + 2, listTop + 10, Theme.MUTED, false);
        }
        for (int i = 0; i < fit; i++) {
            int idx = i + paletteScroll;
            if (idx >= blocks.length) {
                break;
            }
            int id = blocks[idx];
            float y = listTop + 8 + i * row;
            boolean on = session.brush == id;
            boolean h = OstinatoScreen.in(mx, my, col0, y, col1, y + row - 1);
            if (on || h) {
                GuiDraw.round(ms, col0, y, col1, y + row - 2, 2, on ? GuiDraw.alphaOf(accent, 0x33) : 0x14FFFFFF);
            }
            ItemStack icon = BlockPalette.icon(id);
            if (icon.isEmpty()) {
                GuiDraw.round(ms, col0 + 2, y + 2, col0 + 12, y + 12, 1.5f, BlockPalette.argb(id));
            } else {
                GuiDraw.item(icon, col0 + 1, y + 1, 12);
            }
            String label = BlockPalette.label(id);
            if (label.length() > 12) {
                label = label.substring(0, 11) + "…";
            }
            GuiDraw.text(ms, label, col0 + 16, y + 3, on ? Theme.TEXT : Theme.MUTED, false);
        }
        GuiDraw.endScissor();

        float by = vy1 - 62;
        boolean nh = nameFocused || OstinatoScreen.in(mx, my, col0, by, col1, by + 14);
        GuiDraw.roundBorder(ms, col0, by, col1, by + 14, 3, nameFocused ? accent : (nh ? 0xFF3E4860 : Theme.FIELD_BORDER), Theme.FIELD);
        if (name.isEmpty() && !nameFocused) {
            GuiDraw.text(ms, "name", col0 + 4, by + 3, Theme.DIM, false);
        } else {
            screen.drawInput(ms, name, col0 + 4, by + 3, col1 - col0 - 8, nameFocused, accent, now);
        }
        button(ms, "Build", col0, by + 18, (col1 - col0) / 2 - 2, mx, my, accent, true);
        button(ms, "Save", col0 + (col1 - col0) / 2 + 2, by + 18, (col1 - col0) / 2 - 2, mx, my, accent, false);
        button(ms, "Load", col0, by + 36, (col1 - col0) / 2 - 2, mx, my, accent, false);
        String clear = now < clearUntil ? "Sure?" : "Clear";
        button(ms, clear, col0 + (col1 - col0) / 2 + 2, by + 36, (col1 - col0) / 2 - 2, mx, my, now < clearUntil ? Theme.DANGER : accent, false);
        GuiDraw.text(ms, volume.count() + " blocks", col0, by + 54, 0.5f, Theme.DIM, false, false);
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (OstinatoScreen.in(mx, my, col0, vy0, col1, vy0 + 14)) {
            session.cycle();
            if (session.note != null) {
                screen.flash(session.note, Theme.MUTED);
            }
            return true;
        }
        if (OstinatoScreen.in(mx, my, col0, vy1 - 62, col1, vy1 - 48)) {
            nameFocused = true;
            return true;
        }
        nameFocused = false;
        float by = vy1 - 44;
        float half = (col1 - col0) / 2 - 2;
        if (OstinatoScreen.in(mx, my, col0, by, col0 + half, by + 14)) {
            build();
            return true;
        }
        if (OstinatoScreen.in(mx, my, col0 + half + 4, by, col1, by + 14)) {
            save();
            return true;
        }
        by += 18;
        if (OstinatoScreen.in(mx, my, col0, by, col0 + half, by + 14)) {
            load();
            return true;
        }
        if (OstinatoScreen.in(mx, my, col0 + half + 4, by, col1, by + 14)) {
            clear();
            return true;
        }
        float listTop = vy0 + 18;
        float row = 16;
        int fit = Math.max(1, (int) ((vy1 - listTop - 78) / row));
        int[] blocks = view();
        if (OstinatoScreen.in(mx, my, col0, listTop + 8, col1, listTop + 8 + fit * row)) {
            int idx = (int) ((my - (listTop + 8)) / row) + paletteScroll;
            if (idx >= 0 && idx < blocks.length) {
                session.brush = blocks[idx];
            }
            return true;
        }
        if (OstinatoScreen.in(mx, my, vx0, vy0, vx1, vy1)) {
            dragButton = button;
            pressX = dragX = (float) mx;
            pressY = dragY = (float) my;
            moved = false;
            return true;
        }
        return false;
    }

    void mouseDragged(double mx, double my) {
        if (dragButton < 0) {
            return;
        }
        float dx = (float) mx - dragX;
        float dy = (float) my - dragY;
        if (Math.hypot(mx - pressX, my - pressY) > 3) {
            moved = true;
        }
        if (dragButton == 2 || Keys.shift()) {
            double scale = camera.distance / Math.max(48f, vy1 - vy0);
            camera.pan(-dx * scale, dy * scale);
        } else if (moved) {
            camera.orbit(dx * 0.01, -dy * 0.01);
        }
        dragX = (float) mx;
        dragY = (float) my;
    }

    void mouseReleased(double mx, double my) {
        int button = dragButton;
        boolean click = dragButton >= 0 && !moved;
        dragButton = -1;
        if (!click || !OstinatoScreen.in(mx, my, vx0, vy0, vx1, vy1)) {
            return;
        }
        session.click(pick((float) mx, (float) my), button);
        if (session.note != null) {
            screen.flash(session.note, Theme.MUTED);
        }
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        float listTop = vy0 + 18;
        float row = 16;
        int fit = Math.max(1, (int) ((vy1 - listTop - 78) / row));
        if (OstinatoScreen.in(mx, my, col0, listTop, col1, listTop + 8 + fit * row)) {
            paletteScroll += delta > 0 ? -1 : 1;
            return true;
        }
        if (OstinatoScreen.in(mx, my, vx0, vy0, vx1, vy1)) {
            camera.dolly(delta);
            return true;
        }
        return false;
    }

    boolean keyPressed(int key) {
        if (nameFocused) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                nameFocused = false;
                return true;
            }
            return screen.editKey(name, key);
        }
        if (key == InputConstants.KEY_Z && Keys.ctrl()) {
            session.undo();
            if (session.note != null) {
                screen.flash(session.note, Theme.MUTED);
            }
            return true;
        }
        if (key == InputConstants.KEY_S && Keys.ctrl()) {
            save();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE && !filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            paletteScroll = 0;
            viewKey = null;
            return true;
        }
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
            session.hotkey(key - InputConstants.KEY_1);
            if (session.note != null) {
                screen.flash(session.note, Theme.MUTED);
            }
            return true;
        }
        if (key == InputConstants.KEY_MINUS) {
            session.bumpRadius(-1);
            screen.flash(session.note, Theme.MUTED);
            return true;
        }
        if (key == InputConstants.KEY_EQUALS) {
            session.bumpRadius(1);
            screen.flash(session.note, Theme.MUTED);
            return true;
        }
        int[] blocks = view();
        if (key == InputConstants.KEY_LBRACKET && blocks.length > 0) {
            session.brush = neighbor(blocks, -1);
            return true;
        }
        if (key == InputConstants.KEY_RBRACKET && blocks.length > 0) {
            session.brush = neighbor(blocks, 1);
            return true;
        }
        double step = 0.08;
        switch (key) {
            case InputConstants.KEY_LEFT: camera.orbit(-step, 0); return true;
            case InputConstants.KEY_RIGHT: camera.orbit(step, 0); return true;
            case InputConstants.KEY_UP: camera.orbit(0, step); return true;
            case InputConstants.KEY_DOWN: camera.orbit(0, -step); return true;
            default: return false;
        }
    }

    boolean charTyped(char c) {
        if (nameFocused) {
            name.insert(String.valueOf(c));
            return true;
        }
        if (Character.isLetterOrDigit(c) || c == '_' || c == ':') {
            if (filter.length() < 24) {
                filter = filter + Character.toLowerCase(c);
                paletteScroll = 0;
                viewKey = null;
            }
            return true;
        }
        return false;
    }

    private int neighbor(int[] blocks, int dir) {
        int at = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == session.brush) {
                at = i;
                break;
            }
        }
        int next = at + dir;
        if (next < 0) {
            next = blocks.length - 1;
        }
        if (next >= blocks.length) {
            next = 0;
        }
        return blocks[next];
    }

    private int[] view() {
        if (filter.equals(viewKey)) {
            return view;
        }
        int[] all = BlockPalette.placeable();
        if (filter.isEmpty()) {
            view = all;
        } else {
            int n = 0;
            int[] tmp = new int[all.length];
            for (int id : all) {
                if (BlockPalette.name(id).contains(filter)) {
                    tmp[n++] = id;
                }
            }
            view = java.util.Arrays.copyOf(tmp, n);
        }
        viewKey = filter;
        return view;
    }

    private void clear() {
        long now = System.currentTimeMillis();
        if (now >= clearUntil) {
            clearUntil = now + 2500;
            return;
        }
        session.clearVolume();
        clearUntil = 0;
        screen.flash("Cleared the studio", Theme.MUTED);
    }

    private StudioPick pick(float mx, float my) {
        double[] origin = new double[3];
        double[] dir = new double[3];
        camera.ray(mx, my, vx0, vy0, vx1 - vx0, vy1 - vy0, origin, dir);
        return StudioPick.trace(origin[0], origin[1], origin[2], dir[0], dir[1], dir[2], volume);
    }

    private void drawStage(GuiGraphicsExtractor ms) {
        int sx = volume.sizeX();
        int sy = volume.sizeY();
        int sz = volume.sizeZ();
        line(ms, 0, 0, 0, sx, 0, 0, 0x664FD1C5);
        line(ms, 0, 0, 0, 0, 0, sz, 0x664FD1C5);
        line(ms, sx, 0, 0, sx, 0, sz, 0x334FD1C5);
        line(ms, 0, 0, sz, sx, 0, sz, 0x334FD1C5);
        line(ms, 0, 0, 0, 0, sy, 0, 0x334FD1C5);
        line(ms, sx, 0, 0, sx, sy, 0, 0x224FD1C5);
        line(ms, 0, 0, sz, 0, sy, sz, 0x224FD1C5);
        line(ms, sx, 0, sz, sx, sy, sz, 0x224FD1C5);
        for (int i = 4; i < sx; i += 4) {
            line(ms, i, 0, 0, i, 0, sz, 0x183A4458);
        }
        for (int i = 4; i < sz; i += 4) {
            line(ms, 0, 0, i, sx, 0, i, 0x183A4458);
        }
        EditOps.Box box = session.selection();
        if (box != null) {
            boxLines(ms, box.x0, box.y0, box.z0, box.x1 + 1, box.y1 + 1, box.z1 + 1, 0xCCFFC440);
        }
        if (session.hasLine) {
            boxLines(ms, session.lx, session.ly, session.lz, session.lx + 1, session.ly + 1, session.lz + 1, 0xCC78BEFF);
        }
    }

    private void boxLines(GuiGraphicsExtractor ms, int x0, int y0, int z0, int x1, int y1, int z1, int argb) {
        line(ms, x0, y0, z0, x1, y0, z0, argb);
        line(ms, x0, y0, z1, x1, y0, z1, argb);
        line(ms, x0, y0, z0, x0, y0, z1, argb);
        line(ms, x1, y0, z0, x1, y0, z1, argb);
        line(ms, x0, y1, z0, x1, y1, z0, argb);
        line(ms, x0, y1, z1, x1, y1, z1, argb);
        line(ms, x0, y1, z0, x0, y1, z1, argb);
        line(ms, x1, y1, z0, x1, y1, z1, argb);
        line(ms, x0, y0, z0, x0, y1, z0, argb);
        line(ms, x1, y0, z0, x1, y1, z0, argb);
        line(ms, x0, y0, z1, x0, y1, z1, argb);
        line(ms, x1, y0, z1, x1, y1, z1, argb);
    }

    private void drawBlocks(GuiGraphicsExtractor ms, int mx, int my) {
        List<Face> faces = new ArrayList<>();
        collect(faces, -1, -1, -1, 0);
        StudioPick hit = OstinatoScreen.in(mx, my, vx0, vy0, vx1, vy1) ? pick((float) mx, (float) my) : StudioPick.miss();
        if (session.tool == EditOps.Tool.BRUSH && hit.canPlace) {
            collect(faces, hit.placeX, hit.placeY, hit.placeZ, session.brush);
        }
        faces.sort(Comparator.comparingDouble(f -> -f.depth));
        for (Face f : faces) {
            fill(ms, f);
        }
    }

    private void collect(List<Face> faces, int onlyX, int onlyY, int onlyZ, int ghost) {
        int x0 = onlyX >= 0 ? onlyX : 0;
        int x1 = onlyX >= 0 ? onlyX + 1 : volume.sizeX();
        int y0 = onlyY >= 0 ? onlyY : 0;
        int y1 = onlyY >= 0 ? onlyY + 1 : volume.sizeY();
        int z0 = onlyZ >= 0 ? onlyZ : 0;
        int z1 = onlyZ >= 0 ? onlyZ + 1 : volume.sizeZ();
        double[] eye = new double[3];
        camera.eye(eye);
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    int cell = ghost != 0 && x == onlyX && y == onlyY && z == onlyZ ? ghost : volume.get(x, y, z);
                    if (cell == 0) {
                        continue;
                    }
                    boolean ghostCell = ghost != 0 && x == onlyX && y == onlyY && z == onlyZ;
                    emitFace(faces, eye, x, y, z, cell, 1, 0, 0, 0, 1.0f, ghostCell);
                    emitFace(faces, eye, x, y, z, cell, -1, 0, 0, 1, 0.62f, ghostCell);
                    emitFace(faces, eye, x, y, z, cell, 0, 1, 0, 2, 1.12f, ghostCell);
                    emitFace(faces, eye, x, y, z, cell, 0, -1, 0, 3, 0.48f, ghostCell);
                    emitFace(faces, eye, x, y, z, cell, 0, 0, 1, 4, 0.82f, ghostCell);
                    emitFace(faces, eye, x, y, z, cell, 0, 0, -1, 5, 0.7f, ghostCell);
                }
            }
        }
    }

    private void emitFace(List<Face> faces, double[] eye, int x, int y, int z, int cell,
                          int nx, int ny, int nz, int face, float shade, boolean ghost) {
        if (!ghost && volume.get(x + nx, y + ny, z + nz) != 0) {
            return;
        }
        double cx = x + 0.5 + nx * 0.5;
        double cy = y + 0.5 + ny * 0.5;
        double cz = z + 0.5 + nz * 0.5;
        if (nx * (eye[0] - cx) + ny * (eye[1] - cy) + nz * (eye[2] - cz) <= 0) {
            return;
        }
        BlockTextures.FacePaint paint = BlockTextures.paint(cell, face);
        int[] pixels = paint == null ? null : paint.pixels;
        int flat = paint == null ? BlockPalette.argb(cell) : paint.flat;
        double[] xs = new double[4];
        double[] ys = new double[4];
        double[] us = new double[4];
        double[] vs = new double[4];
        double depth = 0;
        int n = 0;
        for (int du = 0; du <= 1; du++) {
            for (int dv = 0; dv <= 1; dv++) {
                double wx;
                double wy;
                double wz;
                if (nx != 0) {
                    wx = x + Math.max(nx, 0);
                    wy = y + du;
                    wz = z + dv;
                } else if (ny != 0) {
                    wx = x + du;
                    wy = y + Math.max(ny, 0);
                    wz = z + dv;
                } else {
                    wx = x + du;
                    wy = y + dv;
                    wz = z + Math.max(nz, 0);
                }
                double[] p = new double[3];
                camera.project(wx, wy, wz, vx0, vy0, vx1 - vx0, vy1 - vy0, p);
                if (p[2] < 0.15) {
                    return;
                }
                xs[n] = p[0];
                ys[n] = p[1];
                if (nx != 0) {
                    us[n] = dv;
                    vs[n] = 1 - du;
                } else if (ny != 0) {
                    us[n] = du;
                    vs[n] = dv;
                } else {
                    us[n] = du;
                    vs[n] = 1 - dv;
                }
                depth += p[2];
                n++;
            }
        }
        swap(xs, 2, 3);
        swap(ys, 2, 3);
        swap(us, 2, 3);
        swap(vs, 2, 3);
        faces.add(new Face(xs, ys, us, vs, depth / 4, pixels, flat, shade, ghost));
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private void fill(GuiGraphicsExtractor ms, Face f) {
        double minY = f.y[0];
        double maxY = f.y[0];
        for (int i = 1; i < 4; i++) {
            minY = Math.min(minY, f.y[i]);
            maxY = Math.max(maxY, f.y[i]);
        }
        int y0 = (int) Math.ceil(minY);
        int y1 = (int) Math.floor(maxY);
        if (y1 - y0 > 96) {
            y1 = y0 + 96;
        }
        int flat = BlockPalette.shade(f.flat, f.shade);
        if (f.ghost) {
            flat = (flat & 0x00FFFFFF) | 0x66000000;
        }
        for (int y = y0; y <= y1; y++) {
            double left = 1e9;
            double right = -1e9;
            double lu = 0, lv = 0, ru = 0, rv = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                double yA = f.y[i];
                double yB = f.y[j];
                if ((yA <= y && yB > y) || (yB <= y && yA > y)) {
                    double t = (y - yA) / (yB - yA);
                    double x = f.x[i] + (f.x[j] - f.x[i]) * t;
                    double u = f.u[i] + (f.u[j] - f.u[i]) * t;
                    double v = f.v[i] + (f.v[j] - f.v[i]) * t;
                    if (x < left) {
                        left = x;
                        lu = u;
                        lv = v;
                    }
                    if (x > right) {
                        right = x;
                        ru = u;
                        rv = v;
                    }
                }
            }
            if (right <= left) {
                continue;
            }
            if (f.pixels == null) {
                GuiDraw.rect(ms, (float) left, y, (float) right, y + 1, flat);
                continue;
            }
            int x0 = (int) Math.ceil(left);
            int x1 = (int) Math.floor(right);
            if (x1 - x0 > 160) {
                x1 = x0 + 160;
            }
            int runX = x0;
            int runColor = 0;
            boolean run = false;
            for (int x = x0; x <= x1; x++) {
                double t = (x - left) / (right - left);
                int color = sample(f, lu + (ru - lu) * t, lv + (rv - lv) * t);
                if (!run) {
                    runColor = color;
                    runX = x;
                    run = true;
                } else if (color != runColor) {
                    if (runColor != 0) {
                        GuiDraw.rect(ms, runX, y, x, y + 1, runColor);
                    }
                    runColor = color;
                    runX = x;
                }
            }
            if (run && runColor != 0) {
                GuiDraw.rect(ms, runX, y, x1 + 1, y + 1, runColor);
            }
        }
    }

    private static int sample(Face f, double u, double v) {
        u = u - Math.floor(u);
        v = v - Math.floor(v);
        int ix = Math.min(15, (int) (u * 16));
        int iy = Math.min(15, (int) (v * 16));
        int color = BlockPalette.shade(f.pixels[iy * 16 + ix], f.shade);
        if (((color >>> 24) & 0xFF) == 0) {
            return 0;
        }
        if (f.ghost) {
            int a = ((color >>> 24) & 0xFF) * 0x66 / 255;
            color = (color & 0x00FFFFFF) | (a << 24);
        }
        return color;
    }

    private void line(GuiGraphicsExtractor ms, double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        double[] a = new double[3];
        double[] b = new double[3];
        camera.project(x1, y1, z1, vx0, vy0, vx1 - vx0, vy1 - vy0, a);
        camera.project(x2, y2, z2, vx0, vy0, vx1 - vx0, vy1 - vy0, b);
        if (a[2] < 0.15 || b[2] < 0.15) {
            return;
        }
        int n = (int) Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
        n = Math.max(1, Math.min(n, 64));
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float x = (float) (a[0] + (b[0] - a[0]) * t);
            float y = (float) (a[1] + (b[1] - a[1]) * t);
            GuiDraw.rect(ms, x, y, x + 1, y + 1, argb);
        }
    }

    private void build() {
        if (volume.count() == 0) {
            screen.flash("Place a block first", Theme.AMBER);
            return;
        }
        if (!(BaritoneAPI.getProvider().getPrimaryBaritone() instanceof Baritone baritone)) {
            screen.flash("No player to build from", Theme.DANGER);
            return;
        }
        var feet = baritone.getPlayerContext().playerFeet();
        if (feet == null) {
            screen.flash("Stand in a world first", Theme.AMBER);
            return;
        }
        String label = name.get().trim().isEmpty() ? "studio" : name.get().trim();
        baritone.getBuilderProcess().build(label, OstinatoSchematic.from(volume), feet);
        screen.flash("Building " + label + " from your feet", Theme.GREEN);
    }

    private void save() {
        try {
            java.io.File file = file();
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), volume.write(), StandardCharsets.UTF_8);
            screen.flash("Saved " + file.getName(), Theme.GREEN);
        } catch (IOException e) {
            screen.flash("Save failed", Theme.DANGER);
        }
    }

    private void load() {
        java.io.File file = file();
        if (!file.isFile()) {
            screen.flash("No file " + file.getName(), Theme.AMBER);
            return;
        }
        try {
            volume = StudioVolume.read(Files.readString(file.toPath()));
            session.bind(volume);
            if (session.brush == 0) {
                session.brush = BlockPalette.cell("stone");
            }
            camera.lookAt(volume.sizeX() / 2.0, volume.sizeY() / 3.0, volume.sizeZ() / 2.0);
            screen.flash("Loaded " + file.getName(), Theme.GREEN);
        } catch (RuntimeException | IOException e) {
            screen.flash(e.getMessage() == null ? "Could not load" : e.getMessage(), Theme.DANGER);
        }
    }

    private java.io.File file() {
        String raw = name.get().trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (raw.isEmpty()) {
            raw = "studio";
        }
        return new java.io.File(new java.io.File(Minecraft.getInstance().gameDirectory, "schematics"), raw + ".ostinato");
    }

    private void button(GuiGraphicsExtractor ms, String label, float x, float y, float w, int mx, int my, int accent, boolean solid) {
        boolean h = OstinatoScreen.in(mx, my, x, y, x + w, y + 14);
        if (solid) {
            GuiDraw.round(ms, x, y, x + w, y + 14, 3, h ? Theme.lighter(accent) : accent);
            GuiDraw.text(ms, label, x + (w - GuiDraw.width(label)) / 2f, y + 3, 0xFF102028, false);
        } else {
            GuiDraw.roundBorder(ms, x, y, x + w, y + 14, 3, h ? accent : Theme.FIELD_BORDER, h ? GuiDraw.alphaOf(accent, 0x22) : Theme.FIELD);
            GuiDraw.text(ms, label, x + (w - GuiDraw.width(label)) / 2f, y + 3, h ? Theme.TEXT : Theme.MUTED, false);
        }
    }

    private static final class Face {
        final double[] x;
        final double[] y;
        final double[] u;
        final double[] v;
        final double depth;
        final int[] pixels;
        final int flat;
        final float shade;
        final boolean ghost;

        Face(double[] x, double[] y, double[] u, double[] v, double depth, int[] pixels, int flat, float shade, boolean ghost) {
            this.x = x;
            this.y = y;
            this.u = u;
            this.v = v;
            this.depth = depth;
            this.pixels = pixels;
            this.flat = flat;
            this.shade = shade;
            this.ghost = ghost;
        }
    }
}
