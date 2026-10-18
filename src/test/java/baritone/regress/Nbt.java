/*
 * Ostinato: regression suite. Minimal NBT and Anvil region reader for headless course worlds. New file, replaces no Baritone code.
 */
package baritone.regress;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Ostinato: reads saved chunks straight from a world's {@code region/r.X.Z.mca} files, with no game classes, so the sim
 * drills can run on map saves (practice parkour, ore parkour) without a client or server. Compounds come back as
 * {@code Map<String, Object>}, lists as {@code List<Object>}, numbers boxed, arrays as primitive arrays.
 */
final class Nbt {

    private Nbt() {}

    static Object read(DataInput in) throws IOException {
        byte type = in.readByte();
        if (type == 0) return null;
        in.readUTF(); // root name
        return payload(in, type);
    }

    private static Object payload(DataInput in, byte type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: {
                byte et = in.readByte();
                int n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(payload(in, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                for (byte t; (t = in.readByte()) != 0; ) {
                    String k = in.readUTF();
                    m.put(k, payload(in, t));
                }
                return m;
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad NBT tag " + type);
        }
    }

    /** One chunk's sections: section Y to (palette as "name[k=v,...]" strings, packed indices or null for a single entry). */
    record Section(String[] palette, long[] data) {
        int index(int lx, int ly, int lz) {
            if (data == null || palette.length == 1) return 0;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            int per = 64 / bits, n = (ly << 8) | (lz << 4) | lx;
            return (int) ((data[n / per] >>> ((n % per) * bits)) & ((1L << bits) - 1));
        }
    }

    /** Anvil region files of one save's overworld, read lazily and cached per region. */
    static final class Regions {
        private final Path dir;
        private final Map<Long, byte[]> files = new HashMap<>();

        Regions(Path save) {
            this.dir = save.resolve("region");
        }

        boolean exists() {
            return Files.isDirectory(dir);
        }

        /** The chunk's sections by section Y, or null when the chunk was never saved. */
        @SuppressWarnings("unchecked")
        Map<Integer, Section> chunk(int cx, int cz) throws IOException {
            int rx = cx >> 5, rz = cz >> 5;
            long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
            byte[] f = files.get(key);
            if (f == null && !files.containsKey(key)) {
                Path p = dir.resolve("r." + rx + "." + rz + ".mca");
                f = Files.exists(p) ? Files.readAllBytes(p) : null;
                files.put(key, f);
            }
            if (f == null || f.length < 8192) return null;
            int i = ((cx & 31) + (cz & 31) * 32) * 4;
            int off = ((f[i] & 0xFF) << 16) | ((f[i + 1] & 0xFF) << 8) | (f[i + 2] & 0xFF);
            if (off == 0) return null;
            int o = off * 4096;
            int len = ((f[o] & 0xFF) << 24) | ((f[o + 1] & 0xFF) << 16) | ((f[o + 2] & 0xFF) << 8) | (f[o + 3] & 0xFF);
            int comp = f[o + 4];
            InputStream raw = new ByteArrayInputStream(f, o + 5, len - 1);
            InputStream in = comp == 1 ? new GZIPInputStream(raw) : comp == 2 ? new InflaterInputStream(raw) : comp == 3 ? raw : null;
            if (in == null) throw new IOException("chunk " + cx + "," + cz + ": unsupported compression " + comp);
            Map<String, Object> root;
            try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
                root = (Map<String, Object>) read(d);
            }
            if (root == null) return null;
            Object secs = root.get("sections");
            if (secs == null && root.get("Level") instanceof Map<?, ?> lvl) secs = lvl.get("sections");
            Map<Integer, Section> out = new HashMap<>();
            if (!(secs instanceof List<?> list)) return out;
            for (Object o2 : list) {
                Map<String, Object> s = (Map<String, Object>) o2;
                Object bs = s.get("block_states");
                if (!(bs instanceof Map<?, ?> bsm)) continue;
                List<Object> pal = (List<Object>) bsm.get("palette");
                if (pal == null || pal.isEmpty()) continue;
                String[] names = new String[pal.size()];
                for (int k = 0; k < names.length; k++) {
                    Map<String, Object> e = (Map<String, Object>) pal.get(k);
                    StringBuilder sb = new StringBuilder((String) e.get("Name"));
                    if (e.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
                        sb.append('[');
                        new TreeMap<>((Map<String, Object>) props).forEach((pk, pv) -> sb.append(pk).append('=').append(pv).append(','));
                        sb.setCharAt(sb.length() - 1, ']');
                    }
                    names[k] = sb.toString();
                }
                out.put(((Number) s.get("Y")).intValue(), new Section(names, (long[]) bsm.get("data")));
            }
            return out;
        }
    }
}
