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

import java.util.Locale;

/**
 * Ostinato: Phase 2c. One shared world observation held by the coordinator. The bot that saw it stays
 * authoritative: a record is only ever a hint, verified locally before anything acts on it.
 */
public final class ObservationRecord {

    public enum Kind {
        VEIN, CHEST, BLOCK, WAYPOINT, STRUCTURE, ENTITY;

        public boolean entity() {
            return this == ENTITY;
        }
    }

    public enum State { CONFIRMED, STALE, GONE }

    public final String key;
    public final Kind kind;
    public final char dim;
    public final String worldId;
    /** Store-wide counter, bumped on every accepted change. */
    public long version;
    /** Monotonic coordinator time of the observation: receipt time minus the age the bot reported. */
    public final long observedAt;
    public final long receivedAt;
    /** Provenance: the bot whose frame carried the report (never a field inside the body). */
    public final String bot;
    public final int confidence;
    /** Kind-specific: {@code ore.count} for a vein, block id for a block, {@code -} for a tombstone. */
    public final String detail;
    public State state;
    /** A lease on this record ended obs-unknown: pick() skips it until a fresh report replaces it. */
    public boolean suspect;

    public ObservationRecord(String key, String worldId, long observedAt, long receivedAt, String bot,
                             int confidence, String detail, State state) {
        Kind k = kindOf(key);
        if (k == null) {
            throw new IllegalArgumentException("bad observation key " + key);
        }
        this.key = key;
        this.kind = k;
        this.dim = key.charAt(2);
        this.worldId = worldId;
        this.observedAt = observedAt;
        this.receivedAt = receivedAt;
        this.bot = bot;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.detail = detail;
        this.state = state;
    }

    /** Kind from the key prefix, or null when the key is malformed. */
    public static Kind kindOf(String key) {
        if (key == null || key.length() < 5 || key.charAt(1) != ':' || key.charAt(3) != ':'
                || "oen".indexOf(key.charAt(2)) < 0) {
            return null;
        }
        switch (key.charAt(0)) {
            case 'v':
            case 'c': {
                ReservationKey r = ReservationKey.parse(key);
                if (r == null) {
                    return null; // Ostinato: parse accepts only the canonical spelling, so one vein has one key.
                }
                return r.type == ReservationKey.Type.VEIN ? Kind.VEIN : Kind.CHEST;
            }
            case 'b':
                return xyz(key.substring(4)) != null ? Kind.BLOCK : null;
            case 's':
                return xyz(key.substring(4)) != null ? Kind.STRUCTURE : null;
            case 'w':
                return key.substring(4).matches("[A-Za-z0-9_.-]{1,24}") ? Kind.WAYPOINT : null;
            case 'e':
                return key.substring(4).matches("[A-Za-z0-9_.-]{1,40}") ? Kind.ENTITY : null;
            default:
                return null;
        }
    }

    static int[] xyz(String s) {
        String[] f = s.split(",", -1);
        if (f.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** For a vein record: the ore id from {@code ore.count}; else null. */
    public String ore() {
        if (kind != Kind.VEIN || detail == null) {
            return null;
        }
        int dot = detail.lastIndexOf('.');
        return dot <= 0 ? null : detail.substring(0, dot);
    }

    /** For a vein record: the block count from {@code ore.count}; else -1. */
    public int count() {
        if (kind != Kind.VEIN || detail == null) {
            return -1;
        }
        int dot = detail.lastIndexOf('.');
        try {
            return dot < 0 ? -1 : Integer.parseInt(detail.substring(dot + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean gone() {
        return state == State.GONE;
    }

    /**
     * The conflict rule, deterministic and total: does {@code a} win over {@code b}? The newer observation wins;
     * on equal time the higher confidence; then the lexicographically smaller bot name; then the smaller detail.
     * Arrival order never decides: a late report of an old observation loses to a newer one already held.
     */
    public static boolean beats(ObservationRecord a, ObservationRecord b) {
        if (a.observedAt != b.observedAt) {
            return a.observedAt > b.observedAt;
        }
        if (a.confidence != b.confidence) {
            return a.confidence > b.confidence;
        }
        int c = a.bot.toLowerCase(Locale.ROOT).compareTo(b.bot.toLowerCase(Locale.ROOT));
        if (c != 0) {
            return c < 0;
        }
        return a.detail.compareTo(b.detail) < 0;
    }

    @Override
    public String toString() {
        return key + " " + detail + " by=" + bot + " conf=" + confidence + " v=" + version + " "
                + state.name().toLowerCase(Locale.ROOT) + (suspect ? " suspect" : "");
    }
}
