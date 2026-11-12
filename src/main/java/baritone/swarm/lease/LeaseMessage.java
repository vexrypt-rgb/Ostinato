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

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ostinato: one sealed {@code SWL} body. The wire form is
 * {@code 1|lease|op|leaseId|resource|gen|commandId|botNonce|durationMs|extra}. Absent ids are {@code -}.
 * The transport already authenticates the sender; this class only checks shape.
 */
public final class LeaseMessage {

    public static final String TYPE = "SWL";

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

    public String encode() {
        return "1|lease|" + op.wire() + "|" + id(leaseId) + "|" + resource + "|" + gen + "|" + id(commandId)
                + "|" + nonce + "|" + durationMs + "|" + extra;
    }

    /** Null when {@code body} is not a well-formed v1 lease message. */
    public static LeaseMessage parse(String body) {
        if (body == null) {
            return null;
        }
        String[] f = body.split("\\|", -1);
        if (f.length != 10 || !"1".equals(f[0]) || !"lease".equals(f[1])) {
            return null;
        }
        Op op = Op.parse(f[2]);
        if (op == null) {
            return null;
        }
        try {
            UUID lease = uuid(f[3]);
            String resource = f[4];
            if (!"-".equals(resource) && !validResource(resource)) {
                return null;
            }
            long gen = Long.parseLong(f[5]);
            UUID cmd = uuid(f[6]);
            long nonce = Long.parseLong(f[7]);
            long dur = Long.parseLong(f[8]);
            if (gen < 0 || dur < 0 || !EXTRA.matcher(f[9]).matches()) {
                return null;
            }
            return new LeaseMessage(op, lease, resource, gen, cmd, nonce, dur, f[9]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuid(String s) {
        return "-".equals(s) ? null : UUID.fromString(s);
    }

    private static String id(UUID u) {
        return u == null ? "-" : u.toString();
    }

    @Override
    public String toString() {
        return encode();
    }
}
