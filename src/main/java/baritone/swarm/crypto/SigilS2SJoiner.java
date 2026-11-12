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

package baritone.swarm.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: joins the lines of a multi-part signed S2S message. {@link SigilS2S#open} decrypts each line on its own
 * but checks the signature only for single-line messages; here the parts are collected by message id and the one
 * signature on the last part is verified over every header and plaintext, so parts of different messages can not
 * be spliced together. Not thread safe.
 */
public final class SigilS2SJoiner {

    /** Outcome of offering one opened part. */
    public enum Status {
        /** Stored; more parts are needed. */
        PENDING,
        /** All parts arrived and the signature verified; {@link Result#text} holds the joined text. */
        JOINED,
        /** A part of a message that was already joined or already stored (chat echo); ignore quietly. */
        DUPLICATE,
        /** Parts disagree, the signer is unknown or the signature does not verify; the message is dropped. */
        BAD
    }

    public static final class Result {
        public final Status status;
        public final SigilCircle circle;
        public final String text;
        public final byte[] keyid;
        public final String sender;
        public final String reason;

        private Result(Status status, SigilCircle circle, String text, byte[] keyid, String sender, String reason) {
            this.status = status;
            this.circle = circle;
            this.text = text;
            this.keyid = keyid;
            this.sender = sender;
            this.reason = reason;
        }

        static Result of(Status s, String reason) {
            return new Result(s, null, null, null, null, reason);
        }
    }

    private static final class Pending {
        final long firstSeen;
        final SigilS2S.Opened[] parts;
        int have;

        Pending(long now, int total) {
            this.firstSeen = now;
            this.parts = new SigilS2S.Opened[total];
        }
    }

    private static final int DONE_CAP = 256;

    private final long timeoutMs;
    private final int maxPending;
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<String, Pending>();
    private final LinkedHashMap<String, Boolean> done = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > DONE_CAP;
        }
    };

    public SigilS2SJoiner(long timeoutMs, int maxPending) {
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.maxPending = Math.max(1, maxPending);
    }

    /** Offers one opened part of a multi-part message ({@code part.total > 1}). */
    public Result offer(SigilS2S.Opened part, Collection<SigilEd25519> verifiers, long now) {
        if (part.total < 2 || part.header == null || part.header.length != 2 + SigilS2C.MID_LEN
                || part.index < 1 || part.index > part.total) {
            return Result.of(Status.BAD, "not a multi-part S2S line");
        }
        String key = part.circle.slug() + ":" + SigilB64.encode(Arrays.copyOfRange(part.header, 2, part.header.length));
        if (done.containsKey(key)) {
            return Result.of(Status.DUPLICATE, "already joined");
        }
        Pending p = pending.get(key);
        if (p == null) {
            while (pending.size() >= maxPending) {
                Iterator<String> it = pending.keySet().iterator();
                it.next();
                it.remove();
            }
            p = new Pending(now, part.total);
            pending.put(key, p);
        } else if (p.parts.length != part.total) {
            pending.remove(key);
            done.put(key, Boolean.TRUE);
            return Result.of(Status.BAD, "part count disagrees");
        }
        if (p.parts[part.index - 1] != null) {
            return Result.of(Status.DUPLICATE, "part already stored");
        }
        p.parts[part.index - 1] = part;
        p.have++;
        if (p.have < p.parts.length) {
            return Result.of(Status.PENDING, null);
        }
        pending.remove(key);
        done.put(key, Boolean.TRUE);
        SigilS2S.Opened last = p.parts[p.parts.length - 1];
        if (last.keyid == null || last.sig == null) {
            return Result.of(Status.BAD, "last part has no signature");
        }
        SigilEd25519 match = null;
        if (verifiers != null) {
            for (SigilEd25519 v : verifiers) {
                if (Arrays.equals(v.keyid(), last.keyid)) {
                    match = v;
                    break;
                }
            }
        }
        if (match == null) {
            return Result.of(Status.BAD, "signer is not a pinned key");
        }
        List<byte[]> headers = new ArrayList<byte[]>(p.parts.length);
        List<byte[]> pts = new ArrayList<byte[]>(p.parts.length);
        StringBuilder text = new StringBuilder();
        for (SigilS2S.Opened o : p.parts) {
            headers.add(o.header);
            pts.add(o.plaintext);
            text.append(o.text);
        }
        try {
            if (!match.verify(SigilS2S.signedBytes(SigilS2S.context(last.circle.name()), last.keyid, headers, pts),
                    last.sig)) {
                return Result.of(Status.BAD, "signature does not verify");
            }
        } catch (SigilException e) {
            return Result.of(Status.BAD, "bad signing input");
        }
        return new Result(Status.JOINED, last.circle, text.toString(), last.keyid, p.parts[0].sender, null);
    }

    /** Drops messages whose parts did not all arrive in time; returns how many were dropped. */
    public int expire(long now) {
        int n = 0;
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().firstSeen >= timeoutMs) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public int pendingCount() {
        return pending.size();
    }
}
