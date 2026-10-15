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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A 4 by 4 slide. 0 is left, 1 right, 2 up, 3 down. */
public final class Slide2048 {

    public final int[][] cell = new int[4][4];
    public int score;
    private final Random random = new Random();

    public Slide2048() {
        spawn();
        spawn();
    }

    public int at(int row, int col) {
        return cell[row][col];
    }

    /** Slides. A spawn happens only when the board changed and {@code spawn} is true. */
    public boolean move(int dir, boolean spawn) {
        int[][] before = copy();
        int gained = 0;
        if (dir == 0 || dir == 1) {
            for (int row = 0; row < 4; row++) {
                int[] line = new int[4];
                for (int col = 0; col < 4; col++) {
                    line[col] = cell[row][col];
                }
                int[] out = compact(line, dir == 0);
                gained += out[4];
                for (int col = 0; col < 4; col++) {
                    cell[row][col] = out[col];
                }
            }
        } else {
            for (int col = 0; col < 4; col++) {
                int[] line = new int[4];
                for (int row = 0; row < 4; row++) {
                    line[row] = cell[row][col];
                }
                int[] out = compact(line, dir == 2);
                gained += out[4];
                for (int row = 0; row < 4; row++) {
                    cell[row][col] = out[row];
                }
            }
        }
        score += gained;
        boolean changed = !same(before);
        if (changed && spawn) {
            spawn();
        }
        return changed;
    }

    public boolean stuck() {
        if (empty() > 0) {
            return false;
        }
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (c + 1 < 4 && cell[r][c] == cell[r][c + 1]) {
                    return false;
                }
                if (r + 1 < 4 && cell[r][c] == cell[r + 1][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    public void set(int row, int col, int value) {
        cell[row][col] = value;
    }

    private void spawn() {
        List<int[]> open = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (cell[r][c] == 0) {
                    open.add(new int[]{r, c});
                }
            }
        }
        if (open.isEmpty()) {
            return;
        }
        int[] spot = open.get(random.nextInt(open.size()));
        cell[spot[0]][spot[1]] = random.nextDouble() < 0.9 ? 2 : 4;
    }

    private int empty() {
        int n = 0;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (cell[r][c] == 0) {
                    n++;
                }
            }
        }
        return n;
    }

    /** First four entries are the line. The fifth is the score gained. */
    private static int[] compact(int[] src, boolean towardStart) {
        int[] v = new int[5];
        int w = towardStart ? 0 : 3;
        int step = towardStart ? 1 : -1;
        boolean pending = false;
        int last = 0;
        for (int k = 0; k < 4; k++) {
            int c = src[towardStart ? k : 3 - k];
            if (c == 0) {
                continue;
            }
            if (!pending) {
                pending = true;
                last = c;
            } else if (last == c) {
                v[w] = last * 2;
                v[4] += last * 2;
                w += step;
                pending = false;
            } else {
                v[w] = last;
                w += step;
                last = c;
            }
        }
        if (pending) {
            v[w] = last;
        }
        return v;
    }

    private int[][] copy() {
        int[][] o = new int[4][4];
        for (int r = 0; r < 4; r++) {
            System.arraycopy(cell[r], 0, o[r], 0, 4);
        }
        return o;
    }

    private boolean same(int[][] o) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (cell[r][c] != o[r][c]) {
                    return false;
                }
            }
        }
        return true;
    }
}
