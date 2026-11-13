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

package baritone.swarm.world;

import baritone.swarm.lease.ReservationKey;
import baritone.swarm.lease.ReservationResolver;

/**
 * Ostinato: Phase 2c. A bot re-checks a shared observation against its own loaded world before it acts on it.
 * Pure: it only reads blocks through {@link ReservationResolver.World}.
 * <ul>
 *     <li>UNKNOWN: some block it needs is not loaded here, so it cannot tell. Never act, never auto-retry.</li>
 *     <li>STALE: the world differs from the record. {@link Result#key}/{@link Result#detail} hold what is
 *     there now (null when nothing is left), which the bot reports as a correction.</li>
 *     <li>CONFIRMED: the world matches.</li>
 * </ul>
 */
public final class ObservationVerifier {

    /** Boxes bigger than this are not scanned (a 64-block vein cannot need one). */
    public static final int MAX_BOX = 8192;

    public enum State { CONFIRMED, STALE, UNKNOWN }

    public static final class Result {
        public final State state;
        public final String why;
        /** For STALE: the key of what is there now, or null when it is gone. */
        public final String key;
        public final String detail;

        Result(State state, String why, String key, String detail) {
            this.state = state;
            this.why = why;
            this.key = key;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return state + " (" + why + ")" + (key == null ? "" : " now " + key + " " + detail);
        }
    }

    private ObservationVerifier() {}

    /** The block id without its namespace: {@code minecraft:coal_ore} becomes {@code coal_ore}. */
    public static String shortId(String id) {
        if (id == null) {
            return null;
        }
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    /** Checks {@code key} (expected {@code detail}, may be null) against the world {@code w} of dimension dim. */
    public static Result verify(String key, String detail, char dim, ReservationResolver.World w) {
        if (key == null || key.length() < 4 || key.charAt(2) != dim) {
            return new Result(State.UNKNOWN, "other dimension", null, null);
        }
        switch (key.charAt(0)) {
            case 'v':
                return vein(key, detail, dim, w);
            case 'c':
                return chest(key, w);
            case 'r':
                return region(key, w);
            case 'b':
                return block(key, detail, w);
            default:
                return new Result(State.UNKNOWN, "kind not checkable", null, null);
        }
    }

    private static Result vein(String key, String detail, char dim, ReservationResolver.World w) {
        ReservationKey k = ReservationKey.parse(key);
        if (k == null) {
            return new Result(State.UNKNOWN, "bad key", null, null);
        }
        String ore = null;
        int count = -1;
        if (detail != null) {
            int dot = detail.lastIndexOf('.');
            if (dot > 0) {
                ore = shortId(detail.substring(0, dot));
                try {
                    count = Integer.parseInt(detail.substring(dot + 1));
                } catch (NumberFormatException e) {
                    count = -1;
                }
            }
        }
        long vol = (long) (k.x2 - k.x1 + 1) * (k.y2 - k.y1 + 1) * (k.z2 - k.z1 + 1);
        if (vol > MAX_BOX) {
            return new Result(State.UNKNOWN, "box too big", null, null);
        }
        int[] seed = null;
        for (int x = k.x1; x <= k.x2; x++) {
            for (int y = k.y1; y <= k.y2; y++) {
                for (int z = k.z1; z <= k.z2; z++) {
                    String b = w.block(x, y, z);
                    if (b == null) {
                        return new Result(State.UNKNOWN, "vein not loaded", null, null);
                    }
                    boolean match = ore == null ? isOre(b) : ore.equals(shortId(b));
                    if (match && seed == null) {
                        seed = new int[]{x, y, z};
                    }
                }
            }
        }
        if (seed == null) {
            return new Result(State.STALE, "no " + (ore == null ? "ore" : ore) + " left in the box", null, null);
        }
        ReservationResolver.Result r = ReservationResolver.vein(dim, seed[0], seed[1], seed[2], w);
        if (r.error != null) {
            if (r.error.contains("unloaded")) {
                return new Result(State.UNKNOWN, r.error, null, null);
            }
            return new Result(State.STALE, r.error, null, null);
        }
        String now = shortId(r.ore) + "." + r.count;
        if (r.key.equals(key) && (count < 0 || count == r.count)) {
            return new Result(State.CONFIRMED, "vein " + now, null, null);
        }
        return new Result(State.STALE, "vein changed " + (detail == null ? "?" : detail) + " -> " + now, r.key, now);
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.endsWith("ancient_debris");
    }

    private static Result chest(String key, ReservationResolver.World w) {
        ReservationKey k = ReservationKey.parse(key);
        if (k == null) {
            return new Result(State.UNKNOWN, "bad key", null, null);
        }
        for (int x = k.x1; x <= k.x2; x++) {
            for (int z = k.z1; z <= k.z2; z++) {
                String b = w.block(x, k.y1, z);
                if (b == null) {
                    return new Result(State.UNKNOWN, "chest not loaded", null, null);
                }
                if (!b.endsWith("chest") && !b.endsWith("barrel")) {
                    return new Result(State.STALE, "no container at " + x + "," + k.y1 + "," + z, null, null);
                }
            }
        }
        return new Result(State.CONFIRMED, "container", null, null);
    }

    private static Result region(String key, ReservationResolver.World w) {
        ReservationKey k = ReservationKey.parse(key);
        if (k == null) {
            return new Result(State.UNKNOWN, "bad key", null, null);
        }
        int cx = (k.x1 + k.x2) >> 1, cy = (k.y1 + k.y2) >> 1, cz = (k.z1 + k.z2) >> 1;
        return w.block(cx, cy, cz) == null ? new Result(State.UNKNOWN, "region not loaded", null, null)
                : new Result(State.CONFIRMED, "region loaded", null, null);
    }

    private static Result block(String key, String detail, ReservationResolver.World w) {
        String[] p = key.substring(4).split(",", -1);
        int x, y, z;
        try {
            x = Integer.parseInt(p[0]);
            y = Integer.parseInt(p[1]);
            z = Integer.parseInt(p[2]);
        } catch (RuntimeException e) {
            return new Result(State.UNKNOWN, "bad key", null, null);
        }
        String b = w.block(x, y, z);
        if (b == null) {
            return new Result(State.UNKNOWN, "block not loaded", null, null);
        }
        String now = shortId(b);
        if (detail == null || shortId(detail).equals(now)) {
            return new Result(State.CONFIRMED, now, null, null);
        }
        return new Result(State.STALE, "block " + detail + " -> " + now, key, now);
    }
}
