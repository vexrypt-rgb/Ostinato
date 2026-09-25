package baritone.utils.fault;

import java.util.Map;

/** Minimal JSON writer (string values only) so the core needs no library. */
final class Json {

    private Json() {}

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static String obj(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(str(e.getKey())).append(':').append(str(e.getValue()));
        }
        return sb.append('}').toString();
    }
}
