/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ostinato: an immutable copy of part of the world, made of 16x16x16 {@link Section sections} (vanilla's chunk
 * sections) plus the boxes of collidable entities frozen where they were. Safe to read from any thread. Cells in a
 * section that was not copied read as air and mark the reader {@code OUTSIDE_SNAPSHOT}; cells below or above the
 * build limits read as air, as vanilla's do.
 */
public final class Terrain {

    /** One copied chunk section: a palette of block infos and an index per cell (none when every cell is the same). */
    public static final class Section {
        public static final int UNLOADED = 1;
        private static final AtomicLong IDS = new AtomicLong();

        private final BlockInfo[] palette;
        private final short[] cells;
        /** Last game tick its cells were known to match the world (copied, or re-checked unchanged). */
        public volatile long tick;
        public final int flags;
        /** Unique per copy: two terrains holding the same copies hold the same cells. */
        public final long id = IDS.incrementAndGet();
        /** Set once the world changed in this section after the copy (it is then no longer handed out). */
        private volatile boolean superseded;

        public Section(BlockInfo[] palette, short[] cells, long tick, int flags) {
            if (cells == null && palette.length != 1) throw new IllegalArgumentException("uniform section needs one palette entry");
            if (cells != null && cells.length != 4096) throw new IllegalArgumentException("cells");
            this.palette = palette;
            this.cells = cells;
            this.tick = tick;
            this.flags = flags;
        }

        public static Section uniform(BlockInfo info, long tick, int flags) {
            return new Section(new BlockInfo[]{info}, null, tick, flags);
        }

        public BlockInfo get(int lx, int ly, int lz) {
            return cells == null ? palette[0] : palette[cells[ly << 8 | lz << 4 | lx]];
        }

        public boolean uniform() {
            return cells == null;
        }

        public void supersede() {
            superseded = true;
        }

        public boolean superseded() {
            return superseded;
        }

        /** Its cells were found unchanged at {@code tick}: the same copy (same id) stays current. */
        public Section recheck(long tick) {
            if (tick > this.tick) this.tick = tick;
            return this;
        }

        public int paletteSize() {
            return palette.length;
        }

        public BlockInfo paletteEntry(int i) {
            return palette[i];
        }

        public boolean sameCells(short[] other) {
            return cells != null && Arrays.equals(cells, other);
        }

        /** Approximate retained bytes, for the cache's bound. */
        public int bytes() {
            return 32 + palette.length * 8 + (cells == null ? 0 : 8192 + 16);
        }
    }

    private final long[] keys;
    private final Section[] sections;
    private final int mask;
    /** Build limits: cells with y < minY or y >= maxY are air. */
    public final int minY, maxY;
    /** Game tick of the request this terrain serves (section ages are measured against it). */
    public final long tick;
    /** Frozen collidable-entity boxes, 6 doubles each, in world coordinates. */
    public final double[] entityBoxes;
    /**
     * Identity of the content: equal for terrains of the same section copies, build limits and entity boxes, so a
     * prediction over one is valid over the other. Collisions are 64-bit hash collisions.
     */
    public final long fingerprint;
    private final long[] contentIdentity;

    private Terrain(long[] keys, Section[] sections, int minY, int maxY, long tick, double[] entityBoxes) {
        this.keys = keys;
        this.sections = sections;
        this.mask = keys.length - 1;
        this.minY = minY;
        this.maxY = maxY;
        this.tick = tick;
        this.entityBoxes = entityBoxes;
        long f = 0;
        for (int i = 0; i < sections.length; i++) if (sections[i] != null) f += mix(keys[i] * 31 + sections[i].id);
        this.fingerprint = mix(f ^ mix((long) minY << 32 | maxY & 0xFFFFFFFFL) ^ Arrays.hashCode(entityBoxes));
        int count = sectionCount();
        contentIdentity = new long[count * 2 + 2];
        int n = 0;
        for (int i = 0; i < sections.length; i++) if (sections[i] != null) contentIdentity[n++] = keys[i];
        Arrays.sort(contentIdentity, 0, count);
        for (int i = 0; i < count; i++) {
            int at = slot(contentIdentity[i], mask);
            while (keys[at] != contentIdentity[i] || sections[at] == null) at = (at + 1) & mask;
            contentIdentity[count + i] = sections[at].id;
        }
        contentIdentity[count * 2] = minY;
        contentIdentity[count * 2 + 1] = maxY;
    }

    /** Compact cache identity: no section/block data retained by completed-result keys. */
    long[] contentIdentity() { return contentIdentity.clone(); }

    /** Exact comparison after the hash lookup; section ids are unique copy identities. */
    public boolean sameContent(Terrain other) {
        if (minY != other.minY || maxY != other.maxY || sectionCount() != other.sectionCount()
                || !Arrays.equals(entityBoxes, other.entityBoxes)) return false;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            long k = keys[i];
            int at = slot(k, other.mask);
            while (other.sections[at] != null && other.keys[at] != k) at = (at + 1) & other.mask;
            if (other.sections[at] != sections[i]) return false;
        }
        return true;
    }

    private static long mix(long h) {
        h = (h ^ h >>> 33) * 0xFF51AFD7ED558CCDL;
        h = (h ^ h >>> 33) * 0xC4CEB9FE1A85EC53L;
        return h ^ h >>> 33;
    }

    /** Whether the world changed in any copied section since the copy. */
    public boolean superseded() {
        for (Section s : sections) if (s != null && s.superseded()) return true;
        return false;
    }

    public static long key(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFFL) << 42 | ((long) sz & 0x3FFFFFL) << 20 | ((long) sy & 0xFFFFFL);
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & mask;
    }

    /** The section at section coordinates, or null when it was not copied. */
    public Section section(int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        for (int i = slot(k, mask); ; i = (i + 1) & mask) {
            Section s = sections[i];
            if (s == null) return null;
            if (keys[i] == k) return s;
        }
    }

    /** Oldest section age in ticks, against {@link #tick} (0 for sections re-checked since). */
    public int maxAge() {
        long oldest = tick;
        for (Section s : sections) {
            if (s == null) continue;
            long t = s.tick;
            if (t < oldest) oldest = t;
        }
        return (int) Math.min(Integer.MAX_VALUE, tick - oldest);
    }

    public int sectionCount() {
        int n = 0;
        for (Section s : sections) if (s != null) n++;
        return n;
    }

    public static Builder builder(int minY, int maxY, long tick, int expectedSections) {
        return new Builder(minY, maxY, tick, expectedSections);
    }

    public static final class Builder {
        private final long[] keys;
        private final Section[] sections;
        private final int minY, maxY;
        private final long tick;
        private int count;
        private double[] entityBoxes = new double[0];

        private Builder(int minY, int maxY, long tick, int expected) {
            int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
            this.keys = new long[cap];
            this.sections = new Section[cap];
            this.minY = minY;
            this.maxY = maxY;
            this.tick = tick;
        }

        public Builder put(int sx, int sy, int sz, Section s) {
            if (count * 2 >= keys.length) throw new IllegalStateException("terrain builder full");
            long k = key(sx, sy, sz);
            int mask = keys.length - 1;
            for (int i = slot(k, mask); ; i = (i + 1) & mask) {
                if (sections[i] == null) {
                    keys[i] = k;
                    sections[i] = s;
                    count++;
                    return this;
                }
                if (keys[i] == k) {
                    sections[i] = s;
                    return this;
                }
            }
        }

        public Builder entityBoxes(double[] boxes) {
            this.entityBoxes = boxes;
            return this;
        }

        public Terrain build() {
            return new Terrain(keys, sections, minY, maxY, tick, entityBoxes);
        }
    }
}
