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

package baritone.utils;

import java.util.Random;

/**
 * Ostinato: the random sources whose draws steer what the bot does (the hand's tremor, the aim's jitter, the
 * learners' coin flips). Unseeded unless -Dostinato.seed=N is set; then each source gets its own stream, fixed by
 * the seed and the source's name, so two runs of the same drill draw the same numbers whatever else differs.
 */
public final class OstinatoRandom {

    private static final Long SEED = Long.getLong("ostinato.seed");

    private OstinatoRandom() {}

    /** Whether -Dostinato.seed is set. */
    public static boolean seeded() {
        return SEED != null;
    }

    /** A seed for the named source: fixed under -Dostinato.seed, otherwise from the clock as before. */
    public static long seed(String name) {
        if (SEED == null) return System.nanoTime() ^ System.currentTimeMillis();
        long z = SEED ^ (name.hashCode() * 0x9e3779b97f4a7c15L);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** A {@link Random} for the named source: its own fixed stream under -Dostinato.seed, an unseeded one otherwise. */
    public static Random create(String name) {
        return SEED == null ? new Random() : new Random(seed(name));
    }
}
