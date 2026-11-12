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


package baritone.swarm;

/**
 * Ostinato: one performance and link sample a member broadcasts as a {@code STS} message, and the
 * coordinator shows in the performance strip and the link monitor. Every figure is {@code -1} (or blank
 * for {@link #command()}) when the sender did not report it, so an older bot, or one that has not sent a
 * sample yet, reads as "n/a" rather than zero.
 *
 * <p>The body is compact {@code key=value} pairs joined by {@code ;} so that it fits in one chat frame:
 * {@code p} last path search ms, {@code t} client ticks per second times ten, {@code g} server ping ms,
 * {@code m} used/max heap MB, {@code w} chat lines sent in the window/cap, {@code d} dropped lines,
 * {@code r} retried frames, {@code c} the chat command the link sends with. Unknown keys are ignored, so
 * a newer sender can add keys without breaking this parser.
 */
public record SwarmSample(long pathMs, int tpsX10, int pingMs, int ramUsedMb, int ramMaxMb,
                          int chatUsed, int chatCap, long dropped, long retried, String command) {

    public static final SwarmSample UNKNOWN = new SwarmSample(-1, -1, -1, -1, -1, -1, -1, -1, -1, "");
    /** Largest figure accepted from a peer; anything above reads as unknown. */
    static final long MAX_VALUE = 99_999_999L;
    static final int MAX_COMMAND = 16;

    public SwarmSample {
        pathMs = bound(pathMs);
        tpsX10 = (int) bound(tpsX10);
        pingMs = (int) bound(pingMs);
        ramUsedMb = (int) bound(ramUsedMb);
        ramMaxMb = (int) bound(ramMaxMb);
        chatUsed = (int) bound(chatUsed);
        chatCap = (int) bound(chatCap);
        dropped = bound(dropped);
        retried = bound(retried);
        command = cleanCommand(command);
    }

    /** Whether this sample carries nothing at all (an older bot, or none received yet). */
    public boolean isUnknown() {
        return pathMs < 0 && tpsX10 < 0 && pingMs < 0 && ramUsedMb < 0 && ramMaxMb < 0 && chatUsed < 0
                && chatCap < 0 && dropped < 0 && retried < 0 && command.isEmpty();
    }

    /** Same figures with the link fields replaced. */
    public SwarmSample withLink(int chatUsed, int chatCap, long dropped, long retried, String command) {
        return new SwarmSample(pathMs, tpsX10, pingMs, ramUsedMb, ramMaxMb, chatUsed, chatCap, dropped, retried,
                command);
    }

    public String encode() {
        StringBuilder b = new StringBuilder();
        put(b, "p", pathMs);
        put(b, "t", tpsX10);
        put(b, "g", pingMs);
        if (ramUsedMb >= 0 || ramMaxMb >= 0) {
            sep(b).append("m=").append(ramUsedMb).append('/').append(ramMaxMb);
        }
        if (chatUsed >= 0 || chatCap >= 0) {
            sep(b).append("w=").append(chatUsed).append('/').append(chatCap);
        }
        put(b, "d", dropped);
        put(b, "r", retried);
        if (!command.isEmpty()) {
            sep(b).append("c=").append(command);
        }
        return b.toString();
    }

    /** Never throws: a malformed or empty body gives {@link #UNKNOWN} figures. */
    public static SwarmSample parse(String body) {
        long p = -1, d = -1, r = -1;
        int t = -1, g = -1, mu = -1, mm = -1, wu = -1, wc = -1;
        String c = "";
        if (body != null) {
            for (String part : body.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq);
                String v = part.substring(eq + 1);
                switch (k) {
                    case "p": p = num(v); break;
                    case "t": t = (int) num(v); break;
                    case "g": g = (int) num(v); break;
                    case "d": d = num(v); break;
                    case "r": r = num(v); break;
                    case "c": c = v; break;
                    case "m": { long[] pair = pair(v); mu = (int) pair[0]; mm = (int) pair[1]; break; }
                    case "w": { long[] pair = pair(v); wu = (int) pair[0]; wc = (int) pair[1]; break; }
                    default: break; // a newer sender's key
                }
            }
        }
        return new SwarmSample(p, t, g, mu, mm, wu, wc, d, r, c);
    }

    /**
     * The command a chat template sends with, for the link monitor: {@code "/msg {to} {msg}"} gives
     * {@code "/msg"}, a template that is only {@code "{msg}"} gives {@code "chat"}.
     */
    public static String commandOf(String template) {
        if (template == null) {
            return "";
        }
        int brace = template.indexOf('{');
        String head = (brace < 0 ? template : template.substring(0, brace)).trim();
        int space = head.indexOf(' ');
        if (space > 0) {
            head = head.substring(0, space);
        }
        return head.isEmpty() ? "chat" : head;
    }

    private static long bound(long v) {
        return v < 0 || v > MAX_VALUE ? -1 : v;
    }

    private static String cleanCommand(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_COMMAND; i++) {
            char ch = s.charAt(i);
            b.append(ch < 0x21 || ch == ';' || ch == '=' || ch == '§' || ch == 0x7f ? '_' : ch);
        }
        return b.toString();
    }

    private static long num(String v) {
        if (v.isEmpty() || v.length() > 9) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            n = n * 10 + (ch - '0');
        }
        return n;
    }

    private static long[] pair(String v) {
        int slash = v.indexOf('/');
        if (slash < 0) {
            return new long[]{num(v), -1};
        }
        return new long[]{num(v.substring(0, slash)), num(v.substring(slash + 1))};
    }

    private static void put(StringBuilder b, String key, long v) {
        if (v >= 0) {
            sep(b).append(key).append('=').append(v);
        }
    }

    private static StringBuilder sep(StringBuilder b) {
        return b.length() == 0 ? b : b.append(';');
    }
}
