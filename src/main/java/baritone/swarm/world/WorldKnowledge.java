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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Ostinato: Phase 2c. The coordinator's Shared World Knowledge Service. Memory only: after a restart it is rebuilt
 * from bot reports (Q / E) before {@link #pick} serves anything.
 * <p>
 * Terrain (veins, chests, blocks, waypoints, structures) and entities are separate bounded LRU stores with
 * separate freshness: terrain stays usable for {@link #TERRAIN_FRESH_MS}, entities for {@link #ENTITY_TTL_MS}.
 * Every record is bound to one world id; a different id drops them all.
 */
public final class WorldKnowledge {

    public static final int TERRAIN_CAP = 512;
    public static final int ENTITY_CAP = 128;
    public static final long TERRAIN_FRESH_MS = 600_000L;
    public static final long ENTITY_TTL_MS = 5_000L;
    public static final long REBUILD_TIMEOUT_MS = 25_000L;
    /** Reported ages above this are clamped (a bot cannot claim an observation older than it can remember). */
    public static final long MAX_AGE_MS = TERRAIN_FRESH_MS;

    /** What happened to one entry. */
    public enum Outcome { NEW, REFRESH, OLDER, WON, LOST, EXPIRED, REJECTED }

    /** A pick: the chosen record, or why there is none. */
    public static final class Pick {
        public final ObservationRecord record;
        public final String reason;

        Pick(ObservationRecord record, String reason) {
            this.record = record;
            this.reason = reason;
        }
    }

    private final LongSupplier mono;
    private final Consumer<String> log;
    private final int terrainCap;
    private final int entityCap;
    private final LinkedHashMap<String, ObservationRecord> terrain;
    private final LinkedHashMap<String, ObservationRecord> entities;
    private String worldId;
    private long version;

    private boolean rebuilding = true;
    private boolean rebuildAsked;
    private long rebuildDeadline = Long.MAX_VALUE;
    private final TreeSet<String> pendingEnd = new TreeSet<>();

    private long accepted;
    private long refreshed;
    private long conflictsWon;
    private long conflictsLost;
    private long worldRejected;
    private long invalidations;
    private long evicted;
    private long lastWorldRejectLog = Long.MIN_VALUE / 2;

    public WorldKnowledge(LongSupplier mono, Consumer<String> log) {
        this(mono, log, TERRAIN_CAP, ENTITY_CAP);
    }

    public WorldKnowledge(LongSupplier mono, Consumer<String> log, int terrainCap, int entityCap) {
        this.mono = mono;
        this.log = log;
        this.terrainCap = terrainCap;
        this.entityCap = entityCap;
        this.terrain = lru(terrainCap);
        this.entities = lru(entityCap);
    }

    private LinkedHashMap<String, ObservationRecord> lru(int cap) {
        return new LinkedHashMap<String, ObservationRecord>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ObservationRecord> e) {
                if (size() > cap) {
                    evicted++;
                    return true;
                }
                return false;
            }
        };
    }

    // ---- world binding ------------------------------------------------------------------------------------

    /** Binds the store to {@code id}. A different id than before drops every record. True when it changed. */
    public synchronized boolean setWorld(String id) {
        if (id == null || id.equals(worldId)) {
            return false;
        }
        String was = worldId;
        if (was != null) {
            invalidate("world " + was + " -> " + id);
        }
        worldId = id;
        return true;
    }

    public synchronized String world() {
        return worldId;
    }

    /** Drops every record (world or session change). */
    public synchronized void invalidate(String why) {
        int n = terrain.size() + entities.size();
        terrain.clear();
        entities.clear();
        invalidations++;
        version++;
        log.accept("swarm-obs: invalidated " + n + " records (" + why + ")");
    }

    // ---- reports ------------------------------------------------------------------------------------------

    /** Applies one SWO message from {@code bot} (the frame sender). Returns per-entry outcomes. */
    public synchronized List<Outcome> apply(String bot, ObservationMessage m) {
        List<Outcome> out = new ArrayList<>();
        long now = mono.getAsLong();
        if (worldId == null || !worldId.equals(m.worldId)) {
            worldRejected++;
            if (now - lastWorldRejectLog >= 10_000L) {
                lastWorldRejectLog = now;
                log.accept("swarm-obs: rejected " + m.op + " from " + bot + " world=" + m.worldId + " (mine="
                        + worldId + ")");
            }
            for (int i = 0; i < m.entries.size(); i++) {
                out.add(Outcome.REJECTED);
            }
            return out;
        }
        switch (m.op) {
            case R:
            case G:
                for (ObservationMessage.Entry e : m.entries) {
                    out.add(put(bot, e, m.op == ObservationMessage.Op.G, now));
                }
                break;
            case E:
                onEnd(bot);
                break;
            default:
                break;
        }
        return out;
    }

    private Outcome put(String bot, ObservationMessage.Entry e, boolean gone, long now) {
        long age = Math.min(e.ageMs, MAX_AGE_MS);
        ObservationRecord n;
        try {
            n = new ObservationRecord(e.key, worldId, now - age, now, bot, e.confidence, gone ? "-" : e.detail,
                    gone ? ObservationRecord.State.GONE : ObservationRecord.State.CONFIRMED);
        } catch (IllegalArgumentException ex) {
            return Outcome.REJECTED;
        }
        LinkedHashMap<String, ObservationRecord> store = n.kind.entity() ? entities : terrain;
        if (n.kind.entity() && now - n.observedAt > ENTITY_TTL_MS) {
            return Outcome.EXPIRED;
        }
        ObservationRecord old = store.get(n.key);
        if (old != null && old.detail.equals(n.detail) && old.state == n.state) {
            if (n.observedAt < old.observedAt) {
                return Outcome.OLDER; // Same fact, already held newer.
            }
            n.version = ++version;
            store.put(n.key, n); // Refresh: newer time, clears suspect.
            refreshed++;
            return Outcome.REFRESH;
        }
        // Contenders: the record on the same key with a different fact (or state), and live veins of the same ore
        // whose box overlaps this one (two bots flood-filled one vein differently, or the vein changed).
        List<ObservationRecord> rivals = new ArrayList<>();
        if (old != null) {
            rivals.add(old);
        }
        if (n.kind == ObservationRecord.Kind.VEIN && !gone && n.ore() != null) {
            ReservationKey nk = ReservationKey.parse(n.key);
            for (ObservationRecord o : terrain.values()) {
                if (o != old && o.kind == ObservationRecord.Kind.VEIN && !o.gone() && n.ore().equals(o.ore())) {
                    ReservationKey ok = ReservationKey.parse(o.key);
                    if (nk != null && ok != null && nk.overlaps(ok)) {
                        rivals.add(o);
                    }
                }
            }
        }
        // The same rule decides a bot's update of its own record, but only two different bots make a conflict
        // (counted and logged); a bot superseding its own report is an ordinary update.
        for (ObservationRecord o : rivals) {
            if (!ObservationRecord.beats(n, o)) {
                if (!o.bot.equalsIgnoreCase(n.bot)) {
                    conflictsLost++;
                    log.accept("swarm-obs: conflict " + n.key + " winner=" + o.bot + " (" + o.key + " " + o.detail
                            + ") loser=" + n.bot + " (" + n.detail + ") rule=" + rule(o, n));
                }
                return Outcome.LOST;
            }
        }
        for (ObservationRecord o : rivals) {
            if (!o.bot.equalsIgnoreCase(n.bot)) {
                conflictsWon++;
                log.accept("swarm-obs: conflict " + n.key + " winner=" + n.bot + " (" + n.detail + ") loser="
                        + o.bot + " (" + o.key + " " + o.detail + ") rule=" + rule(n, o));
            }
            if (o != old) {
                terrain.remove(o.key);
            }
        }
        n.version = ++version;
        store.put(n.key, n);
        accepted++;
        return rivals.isEmpty() ? Outcome.NEW : Outcome.WON;
    }

    /** Which step of the rule decided that {@code w} beats {@code l}. */
    static String rule(ObservationRecord w, ObservationRecord l) {
        if (w.observedAt != l.observedAt) {
            return "newer";
        }
        if (w.confidence != l.confidence) {
            return "confidence";
        }
        if (!w.bot.equalsIgnoreCase(l.bot)) {
            return "bot-name";
        }
        return "detail";
    }

    /**
     * A lease chosen from {@code key} ended with an observation reason. obs-unknown makes the record suspect (no
     * pick until a fresh report); obs-stale marks it stale until the bot's correction replaces it.
     */
    public synchronized void leaseReport(String key, String reason, String bot) {
        ObservationRecord r = terrain.get(key);
        if (r == null || reason == null) {
            return;
        }
        if (reason.startsWith("obs-unknown")) {
            r.suspect = true;
        } else if (reason.startsWith("obs-stale") && r.state == ObservationRecord.State.CONFIRMED) {
            r.state = ObservationRecord.State.STALE;
        } else {
            return;
        }
        r.version = ++version;
        log.accept("swarm-obs: lease report " + reason + " from " + bot + " marks " + r);
    }

    // ---- rebuild ------------------------------------------------------------------------------------------

    /** Starts the rebuild: the caller broadcasts Q. With no live peer it is done at once. */
    public synchronized void startRebuild(Collection<String> livePeers) {
        long now = mono.getAsLong();
        rebuilding = true;
        rebuildAsked = true;
        rebuildDeadline = now + REBUILD_TIMEOUT_MS;
        pendingEnd.clear();
        for (String p : livePeers) {
            pendingEnd.add(p.toLowerCase(Locale.ROOT));
        }
        log.accept("swarm-obs: rebuild asked peers=" + pendingEnd);
        if (pendingEnd.isEmpty()) {
            finishRebuild("no-peers");
        }
    }

    private void onEnd(String bot) {
        if (!rebuilding) {
            return;
        }
        pendingEnd.remove(bot.toLowerCase(Locale.ROOT));
        if (rebuildAsked && pendingEnd.isEmpty()) {
            finishRebuild("all-peers");
        }
    }

    private void finishRebuild(String why) {
        rebuilding = false;
        log.accept("swarm-obs: rebuild done records=" + (terrain.size() + entities.size()) + " (" + why + ")");
    }

    public synchronized boolean rebuilding() {
        return rebuilding;
    }

    public synchronized boolean rebuildAsked() {
        return rebuildAsked;
    }

    /** Ends a rebuild that waited too long, and drops expired entities. */
    public synchronized void tick() {
        long now = mono.getAsLong();
        if (rebuilding && rebuildAsked && now >= rebuildDeadline) {
            finishRebuild("timeout pending=" + pendingEnd);
        }
        purgeEntities(now);
    }

    private void purgeEntities(long now) {
        for (Iterator<ObservationRecord> it = entities.values().iterator(); it.hasNext(); ) {
            if (now - it.next().observedAt > ENTITY_TTL_MS) {
                it.remove();
            }
        }
    }

    // ---- reads --------------------------------------------------------------------------------------------

    /** The record on {@code key}, or null. Entities past their TTL are dropped on read. */
    public synchronized ObservationRecord get(String key) {
        long now = mono.getAsLong();
        ObservationRecord e = entities.get(key);
        if (e != null) {
            if (now - e.observedAt > ENTITY_TTL_MS) {
                entities.remove(key);
                return null;
            }
            return e;
        }
        return terrain.get(key);
    }

    public synchronized boolean fresh(ObservationRecord r) {
        long now = mono.getAsLong();
        return now - r.observedAt <= (r.kind.entity() ? ENTITY_TTL_MS : TERRAIN_FRESH_MS);
    }

    /**
     * Best vein of {@code ore} (substring of the ore id) in {@code dim}: confirmed, fresh and not suspect; then
     * highest confidence, newest, smallest key. {@code busy} filters keys that already have a live lease.
     */
    public synchronized Pick pick(String ore, char dim, Predicate<String> busy) {
        if (rebuilding) {
            return new Pick(null, "observations rebuilding" + (rebuildAsked ? " (waiting for " + pendingEnd + ")"
                    : " (waiting for the link)"));
        }
        long now = mono.getAsLong();
        List<ObservationRecord> c = new ArrayList<>();
        int seen = 0;
        for (ObservationRecord r : terrain.values()) {
            if (r.kind != ObservationRecord.Kind.VEIN || r.dim != dim || r.ore() == null || !r.ore().contains(ore)) {
                continue;
            }
            seen++;
            if (r.state == ObservationRecord.State.CONFIRMED && !r.suspect && now - r.observedAt <= TERRAIN_FRESH_MS
                    && (busy == null || !busy.test(r.key))) {
                c.add(r);
            }
        }
        if (c.isEmpty()) {
            return new Pick(null, "no usable " + ore + " observation (" + seen + " known)");
        }
        c.sort(Comparator.comparingInt((ObservationRecord r) -> -r.confidence)
                .thenComparingLong(r -> -r.observedAt)
                .thenComparing(r -> r.key));
        return new Pick(c.get(0), null);
    }

    /** A live vein record whose box holds the position (fallback when the coordinator cannot see the vein). */
    public synchronized ObservationRecord covering(char dim, int x, int y, int z) {
        if (rebuilding) {
            return null;
        }
        long now = mono.getAsLong();
        ObservationRecord best = null;
        for (ObservationRecord r : terrain.values()) {
            if (r.kind != ObservationRecord.Kind.VEIN || r.state != ObservationRecord.State.CONFIRMED || r.suspect
                    || now - r.observedAt > TERRAIN_FRESH_MS) {
                continue;
            }
            ReservationKey k = ReservationKey.parse(r.key);
            if (k != null && k.contains(dim, x, y, z) && (best == null || ObservationRecord.beats(r, best))) {
                best = r;
            }
        }
        return best;
    }

    public synchronized int terrainSize() {
        return terrain.size();
    }

    public synchronized int entitySize() {
        purgeEntities(mono.getAsLong());
        return entities.size();
    }

    public synchronized long version() {
        return version;
    }

    public synchronized long conflictsWon() {
        return conflictsWon;
    }

    public synchronized long conflictsLost() {
        return conflictsLost;
    }

    public synchronized long worldRejected() {
        return worldRejected;
    }

    public synchronized long invalidations() {
        return invalidations;
    }

    public synchronized long evicted() {
        return evicted;
    }

    /** Status lines for {@code #swarm obs}. */
    public synchronized List<String> status(int maxRecords) {
        long now = mono.getAsLong();
        purgeEntities(now);
        List<String> out = new ArrayList<>();
        out.add("swarm-obs: world=" + worldId + " terrain=" + terrain.size() + "/" + terrainCap + " entities="
                + entities.size() + "/" + entityCap + " v=" + version + " rebuilding=" + rebuilding
                + " accepted=" + accepted + " refreshed=" + refreshed + " conflicts=" + conflictsWon + "w/"
                + conflictsLost + "l worldRejected=" + worldRejected + " invalidations=" + invalidations
                + " evicted=" + evicted);
        List<ObservationRecord> all = new ArrayList<>(terrain.values());
        all.sort(Comparator.comparingLong((ObservationRecord r) -> -r.version));
        int n = 0;
        for (ObservationRecord r : all) {
            if (n++ >= maxRecords) {
                break;
            }
            out.add("swarm-obs:  " + r + " age=" + (now - r.observedAt) / 1000 + "s");
        }
        return out;
    }
}
