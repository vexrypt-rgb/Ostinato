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

/**
 * Repeat a growing phrase on four pads. An ostinato: the same figure, one note longer each time.
 */
public final class Motif {

    public static final int PADS = 4;
    public static final int NOTE_MS = 420;
    public static final int GAP_MS = 160;

    private final int[] seq = new int[12];
    private final Random random = new Random();
    private int len;
    private int heard;
    private long playFrom;
    private boolean failed;
    private boolean idle = true;

    public void start(long now) {
        len = 1;
        seq[0] = random.nextInt(PADS);
        heard = 0;
        failed = false;
        idle = false;
        playFrom = now;
    }

    /** Test hook: this phrase is already waiting for the player. */
    public void script(int[] notes, long now) {
        len = notes.length;
        System.arraycopy(notes, 0, seq, 0, len);
        heard = 0;
        failed = false;
        idle = false;
        playFrom = now - (long) len * (NOTE_MS + GAP_MS);
    }

    public boolean idle() {
        return idle;
    }

    public boolean failed() {
        return failed;
    }

    public int length() {
        return len;
    }

    public int heard() {
        return heard;
    }

    /** Pad lit by the playback, or -1. */
    public int lit(long now) {
        if (idle || failed || listening(now)) {
            return -1;
        }
        long t = now - playFrom;
        long span = NOTE_MS + GAP_MS;
        int i = (int) (t / span);
        if (i < 0 || i >= len) {
            return -1;
        }
        return t % span < NOTE_MS ? seq[i] : -1;
    }

    public boolean listening(long now) {
        if (idle || failed) {
            return false;
        }
        return now - playFrom >= (long) len * (NOTE_MS + GAP_MS);
    }

    public void press(int pad, long now) {
        if (!listening(now) || heard >= len) {
            return;
        }
        if (pad != seq[heard]) {
            failed = true;
            return;
        }
        heard++;
        if (heard < len) {
            return;
        }
        if (len == seq.length) {
            return;
        }
        seq[len] = random.nextInt(PADS);
        len++;
        heard = 0;
        playFrom = now + 360;
    }
}
