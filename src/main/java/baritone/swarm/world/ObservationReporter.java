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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: Phase 2c. The bot side of shared world knowledge. It remembers what it last told the coordinator
 * (bounded) and sends only the difference: new or changed records (R), records that vanished from a box it scanned
 * in full (G), a slow refresh so the coordinator's freshness window holds, and a dump on a rebuild request (Q, then
 * E). Corrections from a local verification jump the queue. Pure: the caller supplies the clock, the scan and the
 * sender, so the rate limits and diffs are testable.
 */
public final class ObservationReporter {

    /** Reported records remembered (oldest dropped first). */
    public static final int CAP = 256;
    /** A record still seen is re-sent after this long, well inside the coordinator's 10 min freshness. */
    public static final long RESEND_MS = 300_000L;
    /** Spool (same PC): at most one message per this gap, bodies up to {@link ObservationMessage#MAX_BODY}. */
    public static final long SPOOL_GAP_MS = 2_000L;
    /** Chat fallback: at most one message per this gap, with at most {@link #CHAT_ENTRIES} entries. */
    public static final long CHAT_GAP_MS = 15_000L;
    public static final int CHAT_ENTRIES = 2;
    /** Confidence of a periodic scan and of a correction from the check right before acting. */
    public static final int SCAN_CONFIDENCE = 90;
    public static final int VERIFIED_CONFIDENCE = 100;

    /** One thing the bot sees now. */
    public static final class Seen {
        public final String key;
        public final String detail;
        public final int confidence;

        public Seen(String key, String detail, int confidence) {
            this.key = key;
            this.detail = detail;
            this.confidence = confidence;
        }
    }

    /** Sends one message to the coordinator; false when it could not (the message is kept). */
    public interface Sender {
        boolean send(ObservationMessage m);
    }

    private static final class Known {
        String detail;
        int confidence;
        long observedAt;
        long lastSent = Long.MIN_VALUE;

        Known(String detail, int confidence, long observedAt) {
            this.detail = detail;
            this.confidence = confidence;
            this.observedAt = observedAt;
        }
    }

    private static final class Pending {
        final ObservationMessage.Op op;
        final String key;
        final String detail;
        final int confidence;
        final long observedAt;

        Pending(ObservationMessage.Op op, String key, String detail, int confidence, long observedAt) {
            this.op = op;
            this.key = key;
            this.detail = detail;
            this.confidence = confidence;
            this.observedAt = observedAt;
        }
    }

    private final LinkedHashMap<String, Known> reported = new LinkedHashMap<String, Known>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Known> e) {
            return size() > CAP;
        }
    };
    /** Corrections first, then ordinary changes; one pending entry per key (the latest wins). */
    private final LinkedHashMap<String, Pending> urgent = new LinkedHashMap<>();
    private final LinkedHashMap<String, Pending> normal = new LinkedHashMap<>();
    /** A rebuild dump in progress: whole messages, sent before anything else, then E. */
    private final ArrayDeque<ObservationMessage> dump = new ArrayDeque<>();
    private String worldId;
    private long lastSend = Long.MIN_VALUE / 2;
    private long sentMessages;
    private long sentEntries;
    private long corrections;
    private long queries;

    public synchronized boolean setWorld(String id) {
        if (id == null ? worldId == null : id.equals(worldId)) {
            return false;
        }
        worldId = id;
        reported.clear();
        urgent.clear();
        normal.clear();
        dump.clear();
        return true;
    }

    public synchronized String world() {
        return worldId;
    }

    /**
     * One scan: {@code seen} is everything found in the box {@code scanned} (null when nothing was scanned in
     * full). Queues R for what is new, changed or due for a refresh, and G for a vein or container key reported
     * earlier that lies wholly inside the scanned box but was not seen again.
     */
    public synchronized void observe(List<Seen> seen, ReservationKey scanned, long now) {
        if (worldId == null) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (Seen s : seen) {
            keys.add(s.key);
            Known k = reported.get(s.key);
            if (k == null || !k.detail.equals(s.detail)) {
                reported.remove(s.key);
                Known n = new Known(s.detail, s.confidence, now);
                reported.put(s.key, n);
                queue(normal, new Pending(ObservationMessage.Op.R, s.key, s.detail, s.confidence, now));
            } else {
                k.observedAt = now;
                k.confidence = s.confidence;
                if (now - k.lastSent >= RESEND_MS && !normal.containsKey(s.key) && !urgent.containsKey(s.key)) {
                    queue(normal, new Pending(ObservationMessage.Op.R, s.key, s.detail, s.confidence, now));
                }
            }
        }
        if (scanned == null) {
            return;
        }
        for (Iterator<Map.Entry<String, Known>> it = reported.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Known> e = it.next();
            if (keys.contains(e.getKey()) || !inside(e.getKey(), scanned)) {
                continue;
            }
            it.remove();
            queue(normal, new Pending(ObservationMessage.Op.G, e.getKey(), "-", SCAN_CONFIDENCE, now));
        }
    }

    /** A vein or container key (not manual b:/w:/s: records) whose box lies inside {@code box}. */
    static boolean inside(String key, ReservationKey box) {
        if (!key.startsWith("v:") && !key.startsWith("c:")) {
            return false;
        }
        ReservationKey k = ReservationKey.parse(key);
        return k != null && k.dim == box.dim && k.x1 >= box.x1 && k.x2 <= box.x2 && k.y1 >= box.y1
                && k.y2 <= box.y2 && k.z1 >= box.z1 && k.z2 <= box.z2;
    }

    /** One record typed in by hand (a waypoint, a structure, or a test report with a chosen confidence/age). */
    public synchronized void manual(String key, String detail, int confidence, long ageMs, long now) {
        if (worldId == null) {
            return;
        }
        reported.remove(key);
        reported.put(key, new Known(detail, confidence, now - ageMs));
        queue(urgent, new Pending(ObservationMessage.Op.R, key, detail, confidence, now - ageMs));
    }

    /**
     * The bot checked a shared record right before acting and found the world different: tell the coordinator
     * the old key is gone and, when something is left, what is there now. Sent before ordinary changes.
     */
    public synchronized void correction(String oldKey, String newKey, String newDetail, long now) {
        if (worldId == null || oldKey == null) {
            return;
        }
        corrections++;
        reported.remove(oldKey);
        normal.remove(oldKey);
        queue(urgent, new Pending(ObservationMessage.Op.G, oldKey, "-", VERIFIED_CONFIDENCE, now));
        if (newKey != null && newDetail != null && !newKey.equals(oldKey)) {
            reported.remove(newKey);
            reported.put(newKey, new Known(newDetail, VERIFIED_CONFIDENCE, now));
            normal.remove(newKey);
            queue(urgent, new Pending(ObservationMessage.Op.R, newKey, newDetail, VERIFIED_CONFIDENCE, now));
        } else if (newKey != null && newDetail != null) {
            // Same box, different count: one R replaces the record. Ostinato: queue() drops the G just queued
            // for this same key, so only the R goes out.
            reported.put(newKey, new Known(newDetail, VERIFIED_CONFIDENCE, now));
            queue(urgent, new Pending(ObservationMessage.Op.R, newKey, newDetail, VERIFIED_CONFIDENCE, now));
        }
    }

    /**
     * The coordinator asked for a rebuild: queue everything still reported (all of it over the spool, the newest
     * {@link #CHAT_ENTRIES} over chat), then E. Ordinary pending entries are folded in.
     */
    public synchronized void onQuery(boolean spool, long now) {
        if (worldId == null) {
            return;
        }
        queries++;
        dump.clear();
        List<ObservationMessage.Entry> all = new ArrayList<>();
        List<String> order = new ArrayList<>(reported.keySet());
        for (int i = order.size() - 1; i >= 0; i--) { // Newest first.
            String key = order.get(i);
            Known k = reported.get(key);
            all.add(new ObservationMessage.Entry(key, k.detail, Math.max(0, now - k.observedAt), k.confidence));
            k.lastSent = now;
            if (!spool && all.size() >= CHAT_ENTRIES) {
                break;
            }
        }
        normal.keySet().removeIf(key -> reported.containsKey(key) && normal.get(key).op == ObservationMessage.Op.R);
        if (!all.isEmpty()) {
            dump.addAll(ObservationMessage.pack(ObservationMessage.Op.R, worldId, all, ObservationMessage.MAX_BODY));
        }
        dump.add(new ObservationMessage(ObservationMessage.Op.E, worldId, new ArrayList<>()));
    }

    /** Sends at most one message if the gap for this transport has passed. Returns true when it sent one. */
    public synchronized boolean tick(long now, boolean spool, Sender sender) {
        if (worldId == null || now - lastSend < (spool ? SPOOL_GAP_MS : CHAT_GAP_MS)) {
            return false;
        }
        ObservationMessage m = dump.peek();
        boolean fromDump = m != null;
        List<Pending> taken = new ArrayList<>();
        if (m == null) {
            m = next(spool, now, taken);
            if (m == null) {
                return false;
            }
        }
        if (!sender.send(m)) {
            return false;
        }
        lastSend = now;
        sentMessages++;
        sentEntries += m.entries.size();
        if (fromDump) {
            dump.poll();
            return true;
        }
        for (Pending p : taken) {
            if (urgent.get(p.key) == p) {
                urgent.remove(p.key);
            } else if (normal.get(p.key) == p) {
                normal.remove(p.key);
            }
            Known k = reported.get(p.key);
            if (k != null && p.op == ObservationMessage.Op.R) {
                k.lastSent = now;
            }
        }
        return true;
    }

    /** The next message: pending entries of one op, urgent first, as many as one body (or chat line) holds. */
    private ObservationMessage next(boolean spool, long now, List<Pending> taken) {
        int max = spool ? Integer.MAX_VALUE : CHAT_ENTRIES;
        ObservationMessage.Op op = null;
        List<ObservationMessage.Entry> entries = new ArrayList<>();
        int size = (ObservationMessage.VERSION + "|R|" + worldId + "|").length();
        outer:
        for (LinkedHashMap<String, Pending> q : java.util.Arrays.asList(urgent, normal)) {
            for (Pending p : q.values()) {
                if (op == null) {
                    op = p.op;
                }
                if (p.op != op) {
                    continue;
                }
                // Ages are measured at send time, so a queued entry does not look newer than it is.
                ObservationMessage.Entry e = new ObservationMessage.Entry(p.key, p.detail,
                        Math.max(0, now - p.observedAt), p.confidence);
                if (!entries.isEmpty() && (entries.size() >= max || size + e.size() > ObservationMessage.MAX_BODY)) {
                    break outer;
                }
                entries.add(e);
                taken.add(p);
                size += e.size();
            }
        }
        return op == null ? null : new ObservationMessage(op, worldId, entries);
    }

    /** Pending entry counts (urgent + normal) and the dump size. */
    public synchronized int pending() {
        return urgent.size() + normal.size() + dump.size();
    }

    public synchronized int reportedSize() {
        return reported.size();
    }

    public synchronized boolean reported(String key) {
        return reported.containsKey(key);
    }

    public synchronized long sentMessages() {
        return sentMessages;
    }

    public synchronized long sentEntries() {
        return sentEntries;
    }

    public synchronized long corrections() {
        return corrections;
    }

    public synchronized long queries() {
        return queries;
    }

    private static void queue(LinkedHashMap<String, Pending> q, Pending p) {
        q.remove(p.key); // Re-queued at the back with the latest fact.
        q.put(p.key, p);
    }

    // ---- scan ---------------------------------------------------------------------------------------------

    /** What one scan found, and the box it covered in full (null if part of it was not loaded). */
    public static final class Scan {
        public final List<Seen> seen;
        public final ReservationKey box;

        Scan(List<Seen> seen, ReservationKey box) {
            this.seen = seen;
            this.box = box;
        }
    }

    /** Most veins and containers one scan reports. */
    public static final int SCAN_MAX = 48;

    /**
     * Scans the box of half-width {@code r} and half-height {@code dy} around (cx, cy, cz) for ore veins (flood
     * filled with {@link ReservationResolver#vein}) and chests/barrels. A vein that runs into unloaded chunks or
     * past the cap is skipped; the box only counts as covered (for G) when every block in it was loaded and no
     * vein was skipped for crossing its edge.
     */
    public static Scan scan(ReservationResolver.World w, char dim, int cx, int cy, int cz, int r, int dy) {
        List<Seen> out = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        boolean full = true;
        int x1 = cx - r, x2 = cx + r, y1 = cy - dy, y2 = cy + dy, z1 = cz - r, z2 = cz + r;
        for (int x = x1; x <= x2 && out.size() < SCAN_MAX; x++) {
            for (int z = z1; z <= z2 && out.size() < SCAN_MAX; z++) {
                for (int y = y1; y <= y2 && out.size() < SCAN_MAX; y++) {
                    if (done.contains(pack(x, y, z))) {
                        continue;
                    }
                    String b = w.block(x, y, z);
                    if (b == null) {
                        full = false;
                        continue;
                    }
                    if (b.endsWith("_ore") || b.endsWith("ancient_debris")) {
                        ReservationResolver.Result v = ReservationResolver.vein(dim, x, y, z, w);
                        if (v.error != null) {
                            done.add(pack(x, y, z));
                            if (v.error.contains("unloaded")) {
                                full = false; // A vein it could not see whole: do not call anything gone.
                            }
                            continue;
                        }
                        ReservationKey k = ReservationKey.parse(v.key);
                        for (int ax = k.x1; ax <= k.x2; ax++) {
                            for (int ay = k.y1; ay <= k.y2; ay++) {
                                for (int az = k.z1; az <= k.z2; az++) {
                                    String o = w.block(ax, ay, az);
                                    if (v.ore.equals(o)) {
                                        done.add(pack(ax, ay, az));
                                    }
                                }
                            }
                        }
                        out.add(new Seen(v.key, ObservationVerifier.shortId(v.ore) + "." + v.count, SCAN_CONFIDENCE));
                    } else if (b.endsWith(":chest") || b.endsWith(":trapped_chest") || b.endsWith(":barrel")) {
                        ReservationResolver.Result c = ReservationResolver.resolve("chest:" + x + "," + y + "," + z,
                                dim, w);
                        done.add(pack(x, y, z));
                        if (c.error == null && ObservationRecord.kindOf(c.key) != null) {
                            ReservationKey k = ReservationKey.parse(c.key);
                            done.add(pack(k.x2, k.y2, k.z2));
                            done.add(pack(k.x1, k.y1, k.z1));
                            out.add(new Seen(c.key, ObservationVerifier.shortId(b), SCAN_CONFIDENCE));
                        }
                    }
                }
            }
        }
        if (out.size() >= SCAN_MAX) {
            full = false; // Stopped early: what was not reached was not seen.
        }
        return new Scan(out, full ? ReservationKey.region(dim, x1, y1, z1, x2, y2, z2) : null);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
