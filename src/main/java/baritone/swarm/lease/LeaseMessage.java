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

package baritone.swarm.lease;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ostinato: one sealed {@code SWL} body. The wire form is
 * {@code 1|lease|op|leaseId|resource|gen|commandId|botNonce|durationMs|extra}. Absent ids are {@code -}.
 * <p>
 * Ostinato: sent as the compact v2 form {@code 2|o|leaseId|resource|gen|commandId|botNonce|durationMs|extra}: a
 * one-letter op, ids as 22-character base64url and numbers in base 36, so a GRANT fits a 2-line chat frame.
 * {@link #parse} accepts both forms.
 * The transport already authenticates the sender; this class only checks shape.
 */
public final class LeaseMessage {

    public static final String TYPE = "SWL";

    /** Ostinato: v2 op codes, in {@link Op} order. */
    private static final String CODES = "oqgnvkrp";

    public enum Op {
        /** coordinator → bot: a lease is reserved for you, ask for it. */
        OFFER,
        /** bot → coordinator: grant me this lease; nonce is my monotonic send time. */
        REQ,
        /** coordinator → bot: valid until nonce + durationMs on the bot's clock. */
        GRANT,
        /** bot → coordinator: extend; nonce is a fresh monotonic send time. */
        RENEW,
        /** coordinator → bot: stop now; answer with RACK once stopped. */
        REVOKE,
        /** bot → coordinator: the leased work is stopped (or never ran). */
        RACK,
        /** coordinator → bot: report every lease you remember (reconciliation). */
        RREQ,
        /** bot → coordinator: one lease's state; {@code leaseId -} with extra {@code end} closes a report. */
        REPORT;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Ostinato: the one-letter v2 code. */
        char code() {
            return CODES.charAt(ordinal());
        }

        static Op ofCode(String s) {
            int i = s.length() == 1 ? CODES.indexOf(s.charAt(0)) : -1;
            return i < 0 ? null : values()[i];
        }

        static Op parse(String s) {
            for (Op o : values()) {
                if (o.wire().equals(s)) {
                    return o;
                }
            }
            return null;
        }

        /** Ops only a coordinator may send. */
        public boolean fromCoordinator() {
            return this == OFFER || this == GRANT || this == REVOKE || this == RREQ;
        }
    }

    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9_:.,-]{1,64}");
    private static final Pattern EXTRA = Pattern.compile("[A-Za-z0-9_:.,=+/-]{0,512}");

    public final Op op;
    /** Null when absent. */
    public final UUID leaseId;
    public final String resource;
    public final long gen;
    /** Null when absent. */
    public final UUID commandId;
    public final long nonce;
    public final long durationMs;
    public final String extra;

    public LeaseMessage(Op op, UUID leaseId, String resource, long gen, UUID commandId, long nonce,
                        long durationMs, String extra) {
        this.op = op;
        this.leaseId = leaseId;
        this.resource = resource == null || resource.isEmpty() ? "-" : resource;
        this.gen = gen;
        this.commandId = commandId;
        this.nonce = nonce;
        this.durationMs = durationMs;
        this.extra = extra == null || extra.isEmpty() ? "-" : extra;
    }

    public static boolean validResource(String r) {
        return r != null && RESOURCE.matcher(r).matches();
    }

    /** Ostinato: the compact v2 form (see the class comment). */
    public String encode() {
        return "2|" + op.code() + "|" + id2(leaseId) + "|" + resource + "|" + Long.toString(gen, 36) + "|"
                + id2(commandId) + "|" + Long.toString(nonce, 36) + "|" + Long.toString(durationMs, 36) + "|" + extra;
    }

    /** The original v1 form; still accepted by {@link #parse}. */
    public String encodeV1() {
        return "1|lease|" + op.wire() + "|" + id(leaseId) + "|" + resource + "|" + gen + "|" + id(commandId)
                + "|" + nonce + "|" + durationMs + "|" + extra;
    }

    /** Null when {@code body} is not a well-formed v1 or v2 lease message. */
    public static LeaseMessage parse(String body) {
        if (body == null) {
            return null;
        }
        String[] f = body.split("\\|", -1);
        boolean v1 = f.length == 10 && "1".equals(f[0]) && "lease".equals(f[1]);
        boolean v2 = f.length == 9 && "2".equals(f[0]);
        if (!v1 && !v2) {
            return null;
        }
        int o = v1 ? 2 : 1; // index of the op field
        int radix = v1 ? 10 : 36;
        Op op = v1 ? Op.parse(f[o]) : Op.ofCode(f[o]);
        if (op == null) {
            return null;
        }
        try {
            UUID lease = v1 ? uuid(f[o + 1]) : uuid2(f[o + 1]);
            String resource = f[o + 2];
            if (!"-".equals(resource) && !validResource(resource)) {
                return null;
            }
            long gen = Long.parseLong(f[o + 3], radix);
            UUID cmd = v1 ? uuid(f[o + 4]) : uuid2(f[o + 4]);
            long nonce = Long.parseLong(f[o + 5], radix);
            long dur = Long.parseLong(f[o + 6], radix);
            if (gen < 0 || dur < 0 || !EXTRA.matcher(f[o + 7]).matches()) {
                return null;
            }
            return new LeaseMessage(op, lease, resource, gen, cmd, nonce, dur, f[o + 7]);
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException e) {
            return null;
        }
    }

    private static UUID uuid(String s) {
        return "-".equals(s) ? null : UUID.fromString(s);
    }

    private static String id2(UUID u) {
        if (u == null) {
            return "-";
        }
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    private static UUID uuid2(String s) {
        if ("-".equals(s)) {
            return null;
        }
        if (s.length() != 22) {
            throw new IllegalArgumentException("bad id");
        }
        ByteBuffer b = ByteBuffer.wrap(Base64.getUrlDecoder().decode(s));
        return new UUID(b.getLong(), b.getLong());
    }

    private static String id(UUID u) {
        return u == null ? "-" : u.toString();
    }

    @Override
    public String toString() {
        return encode();
    }
}
