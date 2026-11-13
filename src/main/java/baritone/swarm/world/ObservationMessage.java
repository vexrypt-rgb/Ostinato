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
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ostinato: Phase 2c. The SWO body: {@code 1|op|worldId|key~detail~ageMs~conf;...}. Incremental only: a message
 * carries changed keys, never a chunk's blocks. The sender is the frame's sender, never a body field.
 * <ul>
 * <li>R report: new or changed observations.</li>
 * <li>G gone: the bot saw that these keys no longer hold (detail {@code -}); also the correction after a failed
 * local check.</li>
 * <li>Q rebuild request: the coordinator (re)started and asks every bot to resend what it knows.</li>
 * <li>E end: this bot's resend is complete.</li>
 * </ul>
 */
public final class ObservationMessage {

    public static final String TYPE = "SWO";
    public static final int VERSION = 1;
    /** Longest body accepted; senders split before this. */
    public static final int MAX_BODY = 4000;

    public enum Op { R, G, Q, E }

    private static final Pattern WORLD = Pattern.compile("[A-Za-z0-9_.-]{1,24}");
    private static final Pattern KEY = Pattern.compile("[a-z]:[oen]:[A-Za-z0-9_,:.-]{1,60}");
    private static final Pattern DETAIL = Pattern.compile("[A-Za-z0-9_:.,@-]{1,48}");

    public static final class Entry {
        public final String key;
        public final String detail;
        public final long ageMs;
        public final int confidence;

        public Entry(String key, String detail, long ageMs, int confidence) {
            this.key = key;
            this.detail = detail;
            this.ageMs = Math.max(0L, ageMs);
            this.confidence = Math.max(0, Math.min(100, confidence));
        }

        String encode() {
            return key + "~" + detail + "~" + ageMs + "~" + confidence;
        }

        /** Characters this entry takes in a body, separator included. */
        public int size() {
            return encode().length() + 1;
        }

        @Override
        public String toString() {
            return encode();
        }
    }

    public final Op op;
    public final String worldId;
    public final List<Entry> entries;

    public ObservationMessage(Op op, String worldId, List<Entry> entries) {
        if (op == null || worldId == null || !WORLD.matcher(worldId).matches()) {
            throw new IllegalArgumentException("bad SWO header");
        }
        for (Entry e : entries) {
            if (!validKey(e.key) || !DETAIL.matcher(e.detail).matches()) {
                throw new IllegalArgumentException("bad SWO entry " + e.key);
            }
        }
        this.op = op;
        this.worldId = worldId;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches() && ObservationRecord.kindOf(key) != null;
    }

    public static boolean validWorld(String id) {
        return id != null && WORLD.matcher(id).matches();
    }

    public String encode() {
        StringBuilder b = new StringBuilder();
        b.append(VERSION).append('|').append(op.name()).append('|').append(worldId).append('|');
        if (entries.isEmpty()) {
            b.append('-');
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                b.append(';');
            }
            b.append(entries.get(i).encode());
        }
        return b.toString();
    }

    /** Parses a body; null when it is not a well-formed v1 SWO body (the whole message is then dropped). */
    public static ObservationMessage decode(String body) {
        if (body == null || body.length() > MAX_BODY) {
            return null;
        }
        String[] f = body.split("[|]", -1);
        if (f.length != 4 || !String.valueOf(VERSION).equals(f[0])) {
            return null;
        }
        Op op;
        try {
            op = Op.valueOf(f[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<Entry> out = new ArrayList<>();
        if (!"-".equals(f[3])) {
            for (String s : f[3].split(";", -1)) {
                String[] p = s.split("~", -1);
                if (p.length != 4) {
                    return null;
                }
                try {
                    long age = Long.parseLong(p[2]);
                    int conf = Integer.parseInt(p[3]);
                    if (age < 0 || conf < 0 || conf > 100) {
                        return null;
                    }
                    out.add(new Entry(p[0], p[1], age, conf));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if ((op == Op.Q || op == Op.E) && !out.isEmpty()) {
            return null;
        }
        try {
            return new ObservationMessage(op, f[2], out);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Splits entries into bodies of at most {@code maxBody} characters (at least one entry each). */
    public static List<ObservationMessage> pack(Op op, String worldId, List<Entry> entries, int maxBody) {
        List<ObservationMessage> out = new ArrayList<>();
        List<Entry> cur = new ArrayList<>();
        int head = (VERSION + "|" + op.name() + "|" + worldId + "|").length();
        int size = head;
        for (Entry e : entries) {
            if (!cur.isEmpty() && size + e.size() > maxBody) {
                out.add(new ObservationMessage(op, worldId, cur));
                cur = new ArrayList<>();
                size = head;
            }
            cur.add(e);
            size += e.size();
        }
        if (!cur.isEmpty()) {
            out.add(new ObservationMessage(op, worldId, cur));
        }
        return out;
    }
}
