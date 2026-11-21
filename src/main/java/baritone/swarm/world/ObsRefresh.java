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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Ostinato: Phase 2c part 2, slice 2 (refresh before reuse). An {@code obs:<ore>} lease that finds no usable record
 * but has suspect or out-of-date candidates is held while the swarm re-checks them, then decided again once each asked
 * record is answered (refreshed or gone) or {@link #WAIT_MS} passed. One refresh per request, never a loop.
 */
public final class ObsRefresh {

    /** How long a held request waits for the answers. A refresh is one check and one report, a few seconds on chat. */
    public static final long WAIT_MS = 10_000L;
    /** At most this many records are asked about for one request (best first). */
    public static final int MAX_KEYS = 4;

    /** One held lease request. */
    public static final class Pending {
        public final String bot;
        public final String ore;
        public final String resource;
        public final String commandLine;
        public final List<String> keys;
        public final long deadline;

        Pending(String bot, String ore, String resource, String commandLine, List<String> keys, long deadline) {
            this.bot = bot;
            this.ore = ore;
            this.resource = resource;
            this.commandLine = commandLine;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.deadline = deadline;
        }
    }

    private final List<Pending> pending = new ArrayList<>();

    /** True while a request for {@code bot} and {@code resource} is already held. */
    public synchronized boolean holding(String bot, String resource) {
        for (Pending p : pending) {
            if (p.bot.equalsIgnoreCase(bot) && p.resource.equals(resource)) {
                return true;
            }
        }
        return false;
    }

    /** Hold a request until its {@code keys} are answered or {@code now + WAIT_MS}. */
    public synchronized Pending hold(String bot, String ore, String resource, String commandLine, List<String> keys,
                                     long now) {
        Pending p = new Pending(bot, ore, resource, commandLine, keys, now + WAIT_MS);
        pending.add(p);
        return p;
    }

    /**
     * The held requests to decide now: every asked key answered (by {@code answered}), or past the deadline. They
     * leave the hold; the caller decides each one once.
     */
    public synchronized List<Pending> due(Predicate<String> answered, long now) {
        List<Pending> out = new ArrayList<>();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            boolean all = true;
            for (String k : p.keys) {
                all &= answered.test(k);
            }
            if (all || now >= p.deadline) {
                it.remove();
                out.add(p);
            }
        }
        return out;
    }

    public synchronized int size() {
        return pending.size();
    }
}
