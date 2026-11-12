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

package baritone.swarm.lease;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Ostinato: per-member SWL message counts over a sliding 60 s window, to check lease traffic against the chat
 * budget (35 whispers per minute).
 */
public final class LeaseRate {

    public static final long WINDOW_MS = 60_000L;
    public static final int CHAT_BUDGET_PER_MIN = 35;

    private final LongSupplier mono;
    private final Map<String, Deque<Long>> out = new TreeMap<>();
    private final Map<String, Deque<Long>> in = new TreeMap<>();
    private final Map<String, Integer> peakOut = new TreeMap<>();

    public LeaseRate(LongSupplier mono) {
        this.mono = mono;
    }

    public synchronized void sent(String member) {
        int n = add(out, member);
        peakOut.merge(key(member), n, Math::max);
    }

    public synchronized void received(String member) {
        add(in, member);
    }

    public synchronized int sentPerMin(String member) {
        return count(out, member);
    }

    public synchronized int receivedPerMin(String member) {
        return count(in, member);
    }

    /** One line per member: {@code swarm-lease-rate bot=x out=n/min in=m/min peakOut=p budget=35}. */
    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        java.util.Set<String> members = new java.util.TreeSet<>(out.keySet());
        members.addAll(in.keySet());
        for (String m : members) {
            lines.add("swarm-lease-rate bot=" + m + " out=" + count(out, m) + "/min in=" + count(in, m)
                    + "/min peakOut=" + peakOut.getOrDefault(m, 0) + " budget=" + CHAT_BUDGET_PER_MIN);
        }
        return lines;
    }

    private int add(Map<String, Deque<Long>> map, String member) {
        Deque<Long> q = map.computeIfAbsent(key(member), k -> new ArrayDeque<>());
        long now = mono.getAsLong();
        q.addLast(now);
        trim(q, now);
        return q.size();
    }

    private int count(Map<String, Deque<Long>> map, String member) {
        Deque<Long> q = map.get(key(member));
        if (q == null) {
            return 0;
        }
        trim(q, mono.getAsLong());
        return q.size();
    }

    private static void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
            q.removeFirst();
        }
    }

    private static String key(String member) {
        return member == null ? "?" : member.toLowerCase(Locale.ROOT);
    }
}
