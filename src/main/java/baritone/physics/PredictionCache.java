/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ostinato: finished results by question and world content, least recently used out first. A key is the request's
 * {@link PredictionRequest#key()} mixed with the {@link Terrain#fingerprint}: the same question over the same section
 * copies and entity boxes has the same answer. Hash matches are verified against complete inputs. A changed section is a new
 * copy with a new id, so it never matches an old entry, which simply ages out. Bounded by entry count. Thread safe.
 */
public final class PredictionCache {
    private final int capacity;
    private final LinkedHashMap<Key, PredictionResult> map;

    public PredictionCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, PredictionResult> e) {
                return size() > PredictionCache.this.capacity;
            }
        };
    }

    public static final class Key {
        private final PredictionRequest request;
        private final long[] sections;
        private final double[] boxes;
        private final int hash;

        private Key(PredictionRequest request, Terrain terrain) {
            this.request = request;
            this.sections = terrain.contentIdentity();
            this.boxes = terrain.entityBoxes.clone();
            this.hash = Long.hashCode(request.key() ^ terrain.fingerprint);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object obj) {
            return obj instanceof Key other && hash == other.hash
                    && request.sameSimulation(other.request) && java.util.Arrays.equals(sections, other.sections) && java.util.Arrays.equals(boxes, other.boxes);
        }
    }

    public static Key key(PredictionRequest request, Terrain terrain) {
        return new Key(request, terrain);
    }

    /** Whether a result is the same whenever its question is asked again over the same terrain. */
    public static boolean cacheable(PredictionResult r) {
        if (r.has(PredictionResult.Assumption.DEGRADED)) return false;
        return r.status == PredictionResult.Status.OK
                || r.status == PredictionResult.Status.PARTIAL && r.failure == PredictionResult.Failure.OUT_OF_SNAPSHOT;
    }

    public synchronized PredictionResult get(Key key) {
        return map.get(key);
    }

    public synchronized void put(Key key, PredictionResult r) {
        if (cacheable(r)) map.put(key, r.withTiming(r.queueNanos, r.snapshotAgeTicks, 0));
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }
}
