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

package baritone.gui.play;

import java.util.Random;

/** A small mine grid. The first reveal is never a mine. */
public final class Minefield {

    public final int width;
    public final int height;
    public final int mines;
    private final long seed;
    private final boolean[] mine;
    private final boolean[] open;
    private final boolean[] flag;
    private boolean armed;
    private boolean dead;
    private boolean won;

    public Minefield(int width, int height, int mines, long seed) {
        this.width = width;
        this.height = height;
        this.mines = mines;
        this.seed = seed;
        int n = width * height;
        this.mine = new boolean[n];
        this.open = new boolean[n];
        this.flag = new boolean[n];
    }

    public boolean open(int x, int y) {
        return inside(x, y) && open[at(x, y)];
    }

    public boolean flagged(int x, int y) {
        return inside(x, y) && flag[at(x, y)];
    }

    public boolean mine(int x, int y) {
        return inside(x, y) && mine[at(x, y)];
    }

    public boolean dead() {
        return dead;
    }

    public boolean won() {
        return won;
    }

    public int around(int x, int y) {
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dy != 0) && mine(x + dx, y + dy)) {
                    n++;
                }
            }
        }
        return n;
    }

    public void reveal(int x, int y) {
        if (!inside(x, y) || dead || won || flag[at(x, y)] || open[at(x, y)]) {
            return;
        }
        if (!armed) {
            arm(x, y);
        }
        flood(x, y);
        if (mine[at(x, y)]) {
            dead = true;
            open[at(x, y)] = true;
            return;
        }
        checkWin();
    }

    public void toggleFlag(int x, int y) {
        if (!inside(x, y) || dead || won || open[at(x, y)]) {
            return;
        }
        flag[at(x, y)] = !flag[at(x, y)];
    }

    private void arm(int safeX, int safeY) {
        Random random = new Random(seed);
        int placed = 0;
        int guard = width * height * 8;
        while (placed < mines && guard-- > 0) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            if ((x == safeX && y == safeY) || mine[at(x, y)]) {
                continue;
            }
            mine[at(x, y)] = true;
            placed++;
        }
        armed = true;
    }

    private void flood(int x, int y) {
        if (!inside(x, y) || open[at(x, y)] || flag[at(x, y)] || mine[at(x, y)]) {
            return;
        }
        open[at(x, y)] = true;
        if (around(x, y) != 0) {
            return;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    flood(x + dx, y + dy);
                }
            }
        }
    }

    private void checkWin() {
        for (int i = 0; i < mine.length; i++) {
            if (!mine[i] && !open[i]) {
                return;
            }
        }
        won = true;
    }

    private boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private int at(int x, int y) {
        return y * width + x;
    }
}
