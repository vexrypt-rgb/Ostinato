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

import baritone.gui.play.Minefield;
import baritone.gui.play.Motif;
import baritone.gui.render.GuiDraw;
import baritone.gui.play.Slide2048;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Three quiet games for a pause: a slide puzzle, a minefield, and a repeating phrase.
 */
final class ArcadePanel {

    private enum Game { SLIDE, MINES, MOTIF }

    private final OstinatoScreen screen;
    private Game game = Game.SLIDE;
    private Slide2048 slide = new Slide2048();
    private Minefield mines = new Minefield(9, 9, 10, System.nanoTime());
    private final Motif motif = new Motif();
    private float x0, y0, x1, y1;

    ArcadePanel(OstinatoScreen screen) {
        this.screen = screen;
    }

    void layout(float x0, float x1, float top, float bottom) {
        this.x0 = x0;
        this.x1 = x1;
        this.y0 = top;
        this.y1 = bottom;
    }

    void draw(GuiGraphicsExtractor ms, int mx, int my, int accent, long now) {
        String[] names = {"Slide", "Mines", "Motif"};
        Game[] games = {Game.SLIDE, Game.MINES, Game.MOTIF};
        float cx = x0;
        for (int i = 0; i < names.length; i++) {
            float w = GuiDraw.width(names[i]) + 16;
            boolean on = game == games[i];
            boolean h = OstinatoScreen.in(mx, my, cx, y0, cx + w, y0 + 16);
            GuiDraw.round(ms, cx, y0, cx + w, y0 + 16, 3, on ? GuiDraw.alphaOf(accent, 0x33) : (h ? 0x14FFFFFF : 0x0AFFFFFF));
            GuiDraw.text(ms, names[i], cx + 8, y0 + 4, on ? Theme.TEXT : Theme.MUTED, false);
            cx += w + 4;
        }
        float gy = y0 + 24;
        if (game == Game.SLIDE) {
            drawSlide(ms, gy, accent);
        } else if (game == Game.MINES) {
            drawMines(ms, mx, my, gy, accent);
        } else {
            drawMotif(ms, mx, my, gy, accent, now);
        }
    }

    boolean mouseClicked(double mx, double my, int button) {
        String[] names = {"Slide", "Mines", "Motif"};
        Game[] games = {Game.SLIDE, Game.MINES, Game.MOTIF};
        float cx = x0;
        for (int i = 0; i < names.length; i++) {
            float w = GuiDraw.width(names[i]) + 16;
            if (OstinatoScreen.in(mx, my, cx, y0, cx + w, y0 + 16)) {
                game = games[i];
                return true;
            }
            cx += w + 4;
        }
        float gy = y0 + 24;
        if (game == Game.MINES) {
            return clickMines(mx, my, gy, button);
        }
        if (game == Game.MOTIF) {
            return clickMotif(mx, my, gy);
        }
        return OstinatoScreen.in(mx, my, x0, y0, x1, y1);
    }

    boolean keyPressed(int key) {
        if (game == Game.SLIDE) {
            int dir = switch (key) {
                case InputConstants.KEY_LEFT, InputConstants.KEY_A -> 0;
                case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> 1;
                case InputConstants.KEY_UP, InputConstants.KEY_W -> 2;
                case InputConstants.KEY_DOWN, InputConstants.KEY_S -> 3;
                default -> -1;
            };
            if (dir >= 0) {
                if (!slide.move(dir, true) && slide.stuck()) {
                    screen.flash("No moves left", Theme.AMBER);
                }
                return true;
            }
        }
        if (game == Game.MOTIF) {
            int pad = switch (key) {
                case InputConstants.KEY_1, InputConstants.KEY_NUMPAD1 -> 0;
                case InputConstants.KEY_2, InputConstants.KEY_NUMPAD2 -> 1;
                case InputConstants.KEY_3, InputConstants.KEY_NUMPAD3 -> 2;
                case InputConstants.KEY_4, InputConstants.KEY_NUMPAD4 -> 3;
                default -> -1;
            };
            if (pad >= 0) {
                motif.press(pad, System.currentTimeMillis());
                return true;
            }
        }
        return false;
    }

    String hint() {
        if (game == Game.MINES) {
            return "Left opens a cell. Right plants a flag.";
        }
        if (game == Game.MOTIF) {
            return "Watch the pads, then press 1 to 4.";
        }
        return "Arrows or WASD slide the tiles.";
    }

    private void drawSlide(GuiGraphicsExtractor ms, float gy, int accent) {
        float size = Math.min(36, (y1 - gy - 28) / 4f);
        float ox = (x0 + x1) / 2 - size * 2;
        GuiDraw.text(ms, "Score " + slide.score + "    arrows move", ox, gy, 0.5f, Theme.MUTED, false, false);
        gy += 12;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int v = slide.at(r, c);
                float x = ox + c * size;
                float y = gy + r * size;
                GuiDraw.round(ms, x + 2, y + 2, x + size - 2, y + size - 2, 3, tile(v, accent));
                if (v != 0) {
                    String s = Integer.toString(v);
                    GuiDraw.text(ms, s, x + size / 2 - GuiDraw.width(s) / 2f, y + size / 2 - 4, 0xFF102028, false);
                }
            }
        }
        if (slide.stuck()) {
            GuiDraw.text(ms, "No moves. That one is finished.", ox, gy + size * 4 + 4, Theme.AMBER, false);
        }
    }

    private void drawMines(GuiGraphicsExtractor ms, int mx, int my, float gy, int accent) {
        float size = Math.min(18, (y1 - gy - 20) / 9f);
        float ox = (x0 + x1) / 2 - size * 4.5f;
        String state = mines.dead() ? "Boom" : mines.won() ? "Clear" : "Left opens, right flags";
        GuiDraw.text(ms, state, ox, gy, 0.5f, mines.dead() ? Theme.DANGER : mines.won() ? Theme.GREEN : Theme.MUTED, false, false);
        gy += 12;
        for (int y = 0; y < mines.height; y++) {
            for (int x = 0; x < mines.width; x++) {
                float px = ox + x * size;
                float py = gy + y * size;
                boolean h = OstinatoScreen.in(mx, my, px, py, px + size, py + size);
                boolean open = mines.open(x, y);
                int fill = open ? 0xFF1A2030 : (h ? 0xFF3A465C : 0xFF2A3344);
                GuiDraw.round(ms, px + 1, py + 1, px + size - 1, py + size - 1, 2, fill);
                if (mines.flagged(x, y)) {
                    GuiDraw.text(ms, "F", px + size / 2 - 2, py + 3, Theme.AMBER, false);
                } else if (open && mines.mine(x, y)) {
                    GuiDraw.round(ms, px + 5, py + 5, px + size - 5, py + size - 5, 2, Theme.DANGER);
                } else if (open && mines.around(x, y) > 0) {
                    GuiDraw.text(ms, Integer.toString(mines.around(x, y)), px + size / 2 - 2, py + 3, accent, false);
                }
            }
        }
        if (mines.dead() || mines.won()) {
            GuiDraw.text(ms, "Click New for another field", ox, gy + size * 9 + 2, Theme.DIM, false);
        }
    }

    private boolean clickMines(double mx, double my, float gy, int button) {
        gy += 12;
        float size = Math.min(18, (y1 - (y0 + 24) - 20) / 9f);
        float ox = (x0 + x1) / 2 - size * 4.5f;
        if ((mines.dead() || mines.won()) && my > gy + size * 9) {
            mines = new Minefield(9, 9, 10, System.nanoTime());
            return true;
        }
        int x = (int) Math.floor((mx - ox) / size);
        int y = (int) Math.floor((my - gy) / size);
        if (x < 0 || y < 0 || x >= mines.width || y >= mines.height) {
            return OstinatoScreen.in(mx, my, x0, y0, x1, y1);
        }
        if (button == 1) {
            mines.toggleFlag(x, y);
        } else {
            mines.reveal(x, y);
        }
        return true;
    }

    private void drawMotif(GuiGraphicsExtractor ms, int mx, int my, float gy, int accent, long now) {
        String state;
        if (motif.idle()) {
            state = "Play, then repeat the phrase";
        } else if (motif.failed()) {
            state = "Missed. Play starts again";
        } else if (motif.listening(now)) {
            state = "Your turn  " + motif.heard() + " / " + motif.length();
        } else {
            state = "Listen  " + motif.length();
        }
        GuiDraw.text(ms, state, x0, gy, Theme.MUTED, false);
        float bw = 64;
        float bx = x0;
        boolean playH = OstinatoScreen.in(mx, my, bx, gy + 16, bx + 52, gy + 32);
        GuiDraw.round(ms, bx, gy + 16, bx + 52, gy + 32, 3, playH ? Theme.lighter(accent) : accent);
        GuiDraw.text(ms, "Play", bx + 14, gy + 20, 0xFF102028, false);
        int lit = motif.lit(now);
        int[] cols = {0xFFE07B2C, 0xFFF1C233, 0xFF70C244, 0xFF4AA3D8};
        float pad = gy + 44;
        float pw = Math.min(70, (x1 - x0 - 12) / 4f);
        for (int i = 0; i < 4; i++) {
            float px = x0 + i * (pw + 4);
            boolean h = OstinatoScreen.in(mx, my, px, pad, px + pw, pad + 48);
            int c = cols[i];
            if (lit == i) {
                c = Theme.lighter(Theme.lighter(c));
            } else if (!h) {
                c = GuiDraw.lerp(c, 0xFF000000, 0.35f);
            }
            GuiDraw.round(ms, px, pad, px + pw, pad + 48, 4, c);
            GuiDraw.text(ms, Integer.toString(i + 1), px + pw / 2 - 2, pad + 18, 0xFF102028, false);
        }
    }

    private boolean clickMotif(double mx, double my, float gy) {
        if (OstinatoScreen.in(mx, my, x0, gy + 16, x0 + 52, gy + 32)) {
            motif.start(System.currentTimeMillis());
            return true;
        }
        float pad = gy + 44;
        float pw = Math.min(70, (x1 - x0 - 12) / 4f);
        for (int i = 0; i < 4; i++) {
            float px = x0 + i * (pw + 4);
            if (OstinatoScreen.in(mx, my, px, pad, px + pw, pad + 48)) {
                motif.press(i, System.currentTimeMillis());
                if (motif.failed()) {
                    screen.flash("Missed a note", Theme.AMBER);
                }
                return true;
            }
        }
        return OstinatoScreen.in(mx, my, x0, y0, x1, y1);
    }

    private static int tile(int v, int accent) {
        if (v == 0) {
            return 0xFF1A2030;
        }
        if (v >= 2048) {
            return accent;
        }
        float t = Math.min(1f, (float) (Math.log(v) / Math.log(2) - 1) / 10f);
        return GuiDraw.lerp(0xFFD7E0EA, accent, t);
    }
}
