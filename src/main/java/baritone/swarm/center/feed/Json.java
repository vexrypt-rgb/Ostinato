package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 command-center feed. A small JSON reader and writer with no dependencies, so the standalone window
 * (its own JVM, no Minecraft and no Gson on its class path) and the bots share one codec. Objects read as
 * {@link LinkedHashMap}, arrays as {@link ArrayList}, numbers as {@link Double} or {@link Long}, plus String, Boolean
 * and null. Readers ignore keys they do not know, which is what lets a v1 window read a later v1 feed.
 */
public final class Json {

    /** Deepest nesting a reader accepts. Feeds are three or four levels deep. */
    public static final int MAX_DEPTH = 32;

    private Json() {}

    // ---- writing ---------------------------------------------------------------------------------------------------

    /** Writes maps, iterables, arrays of int/long/double, strings, numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof int[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                sb.append(i == 0 ? "" : ",").append(a[i]);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** Parses one JSON value. Throws {@link IllegalArgumentException} on malformed text or trailing garbage. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no json");
        }
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.i != text.length()) {
            throw new IllegalArgumentException("trailing text at " + r.i);
        }
        return v;
    }

    /** Parses an object; anything else is an error. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("not a json object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return s.charAt(i);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c + " at " + i);
            }
            i++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("too deep");
            }
            char c = peek();
            switch (c) {
                case '{': return obj(depth);
                case '[': return arr(depth);
                case '"': return str();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default: return num();
            }
        }

        void word(String w) {
            if (!s.startsWith(w, i)) {
                throw new IllegalArgumentException("bad literal at " + i);
            }
            i += w.length();
        }

        Map<String, Object> obj(int depth) {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> arr(int depth) {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape at " + i);
                }
            }
        }

        Number num() {
            int start = i;
            boolean real = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    real = true;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) {
                throw new IllegalArgumentException("unexpected " + s.charAt(start) + " at " + start);
            }
            try {
                return real ? (Number) Double.valueOf(t) : (Number) Long.valueOf(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at " + start);
            }
        }
    }

    // ---- typed field access, used by every feed record's fromJson ------------------------------------------------

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof String s ? s : fallback;
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    public static int i32(Map<String, Object> m, String key, int fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, n.longValue()))
                : fallback;
    }

    public static double dbl(Map<String, Object> m, String key, double fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** The objects in an array field; other elements are skipped. Never null. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objs(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    /** The strings in an array field; other elements are skipped. Never null. */
    public static List<String> strs(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** An int array field such as a box or a position, or null when absent or not all numbers. */
    public static int[] ints(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof List<?> l)) {
            return null;
        }
        int[] out = new int[l.size()];
        for (int k = 0; k < out.length; k++) {
            if (!(l.get(k) instanceof Number n)) {
                return null;
            }
            out[k] = n.intValue();
        }
        return out;
    }

    /** Insertion-ordered map builder, so written feeds keep a stable, readable key order. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            m.put((String) kv[k], kv[k + 1]);
        }
        return m;
    }
}
