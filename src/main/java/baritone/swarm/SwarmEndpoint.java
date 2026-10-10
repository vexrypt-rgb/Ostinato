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

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilException;
import baritone.swarm.crypto.SigilS2S;
import baritone.swarm.crypto.SigilWire;
import baritone.swarm.frame.SwarmChunker;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmFrameException;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.frame.SwarmReassembler;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.frame.SwarmReplayGuard;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmTransport;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * One swarm member: frames, seals, sends, and on the way in opens, checks and
 * reassembles. Frames are sealed as one S1C, S2C or S2S token under the group's
 * circle. When {@link SwarmConfig#requireSignedSender()} is true or the wire is
 * S2S, outbound uses S2S and inbound unsigned tokens are refused.
 */
public final class SwarmEndpoint implements Closeable {

    private volatile Map<String, byte[]> signerOf;
    private final String self;
    private final SwarmConfig cfg;
    private final Map<String, SigilCircle> groups;
    private final List<SigilCircle> keyring;
    private final SwarmTransport transport;
    private final LongSupplier clockMs;
    private final SwarmChunker chunker;
    private final SwarmReplayGuard replay;
    private final SwarmReassembler reassembler;
    private final Map<SwarmReject, Long> rejects = new EnumMap<>(SwarmReject.class);
    private SwarmReject lastReject;
    private volatile BiPredicate<String, String> memberCheck = (group, from) -> true;
    private volatile SigilEd25519 localSignet;
    private volatile Collection<SigilEd25519> pins = Collections.emptyList();

    public SwarmEndpoint(String selfId, SwarmConfig cfg, Map<String, SigilCircle> groups, SwarmTransport transport,
                         LongSupplier clockMs) throws SwarmFrameException {
        if (!selfId.equals(transport.selfId())) {
            throw new IllegalArgumentException("transport id " + transport.selfId() + " != " + selfId);
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("no groups: there is no plaintext mode");
        }
        Set<String> fingerprints = new HashSet<>();
        for (Map.Entry<String, SigilCircle> e : groups.entrySet()) {
            if (!SwarmFrame.isValidId(e.getKey())) {
                throw new IllegalArgumentException("bad group id");
            }
            if (!fingerprints.add(e.getValue().fingerprint())) {
                throw new IllegalArgumentException("groups must not share a circle");
            }
        }
        this.self = selfId;
        this.cfg = cfg;
        this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        this.keyring = new ArrayList<>(groups.values());
        this.transport = transport;
        this.clockMs = clockMs;
        this.chunker = new SwarmChunker(selfId, clockMs.getAsLong() / 1000L, cfg);
        this.replay = new SwarmReplayGuard(cfg);
        this.reassembler = new SwarmReassembler(cfg);
    }

    public String selfId() { return self; }
    public long epoch() { return chunker.epoch(); }
    public SwarmConfig config() { return cfg; }

    public void setMemberCheck(BiPredicate<String, String> check) {
        this.memberCheck = check;
    }

    /**
     * Local signing key and pinned peer public keys for S2S. Any pinned key may sign for any sender; prefer
     * {@link #setSigning(SigilEd25519, Map)}, which binds each key to its member.
     */
    public void setSigning(SigilEd25519 local, Collection<SigilEd25519> pinned) {
        this.localSignet = local;
        // pins before the binding is lifted: a line read between the two is checked against the stricter pair
        this.pins = pinned == null ? Collections.<SigilEd25519>emptyList() : new ArrayList<SigilEd25519>(pinned);
        this.signerOf = null;
    }

    /**
     * Local signing key and pinned public keys by member name. An S2S frame is then accepted only when it is
     * signed by the key pinned for the member it claims to be from, so one member cannot speak as another.
     */
    public void setSigning(SigilEd25519 local, Map<String, SigilEd25519> pinnedByMember) {
        Map<String, byte[]> m = new HashMap<String, byte[]>();
        for (Map.Entry<String, SigilEd25519> e : pinnedByMember.entrySet()) {
            m.put(e.getKey(), e.getValue().keyid());
        }
        this.localSignet = local;
        // the binding first, so no line is ever checked against the new pins without it
        this.signerOf = m;
        this.pins = new ArrayList<SigilEd25519>(pinnedByMember.values());
    }

    public long send(String group, String to, String type, String body)
            throws SwarmFrameException, SigilException, IOException {
        return send(group, to, type, body, SwarmPriority.NORMAL);
    }

    public long send(String group, String to, String type, String body, SwarmPriority priority)
            throws SwarmFrameException, SigilException, IOException {
        SigilCircle circle = groups.get(group);
        if (circle == null) {
            throw new SwarmFrameException(SwarmReject.WRONG_GROUP, "no circle for group " + group);
        }
        List<SwarmFrame> frames = chunker.chunk(group, to, type, body, clockMs.getAsLong() / 1000L);
        List<String> lines = new ArrayList<>(frames.size());
        boolean signed = signedOut();
        if (signed && localSignet == null) {
            throw new SigilException("S2S send needs a local signet (setSigning).");
        }
        for (SwarmFrame f : frames) {
            String enc = f.encode();
            lines.add(signed
                    ? SigilS2S.sealSingle(circle, localSignet, enc, cfg.sealLineBudget())
                    : SigilCodec.sealSingle(cfg.wire(), circle, enc, cfg.sealLineBudget()));
        }
        for (String line : lines) {
            transport.sendTo(group, to, line, priority);
        }
        return frames.get(0).msgId();
    }

    /** Whether this endpoint speaks S2S: it then signs what it sends and refuses what is not signed. */
    private boolean signedOut() {
        return cfg.requireSignedSender() || cfg.wire() == SigilWire.S2S;
    }

    public List<SwarmMessage> poll() throws IOException {
        List<SwarmMessage> out = new ArrayList<>();
        for (String line : transport.receive()) {
            SwarmMessage m = receiveLine(line);
            if (m != null) {
                out.add(m);
            }
        }
        int expired = reassembler.expire(clockMs.getAsLong());
        for (int i = 0; i < expired; i++) {
            count(SwarmReject.EXPIRED);
        }
        return out;
    }

    public SwarmMessage receiveLine(String line) {
        long now = clockMs.getAsLong();
        String token = line == null ? null : line.trim();
        if (!SwarmTransport.isSealed(token)) {
            return reject(SwarmReject.UNSEALED);
        }
        String text;
        SigilCircle circle;
        int total;
        byte[] signer = null;
        try {
            if (token.startsWith(SigilS2S.VERSION + ".")) {
                SigilS2S.Opened o = SigilS2S.open(token, keyring, pins);
                text = o.text;
                circle = o.circle;
                total = o.total;
                signer = o.keyid;
            } else {
                if (signedOut()) {
                    return reject(SwarmReject.UNSEALED);
                }
                SigilCodec.Opened o = SigilCodec.open(token, keyring);
                text = o.text();
                circle = o.circle();
                total = o.total();
            }
        } catch (SigilException e) {
            return reject(SwarmReject.UNSEALED);
        }
        if (total != 1) {
            return reject(SwarmReject.UNSEALED);
        }
        try {
            SwarmFrame f = SwarmFrame.decode(text);
            if (groups.get(f.group()) != circle) {
                throw new SwarmFrameException(SwarmReject.WRONG_GROUP, "group " + f.group() + " under another circle");
            }
            if (f.from().equals(self)) {
                throw new SwarmFrameException(SwarmReject.SELF, "own frame");
            }
            Map<String, byte[]> owners = signerOf;
            if (signer != null && owners != null && !Arrays.equals(owners.get(f.from()), signer)) {
                throw new SwarmFrameException(SwarmReject.WRONG_SIGNER, f.from() + " not signed by its own signet");
            }
            if (!memberCheck.test(f.group(), f.from())) {
                throw new SwarmFrameException(SwarmReject.NOT_MEMBER, f.from() + " is not in group " + f.group());
            }
            if (!f.to().equals(self) && !f.to().equals(SwarmFrame.BROADCAST)) {
                throw new SwarmFrameException(SwarmReject.NOT_FOR_ME, "for " + f.to());
            }
            replay.check(f, now);
            return reassembler.accept(f, now);
        } catch (SwarmFrameException e) {
            return reject(e.reason());
        }
    }

    private SwarmMessage reject(SwarmReject r) {
        count(r);
        return null;
    }

    private synchronized void count(SwarmReject r) {
        lastReject = r;
        rejects.merge(r, 1L, Long::sum);
    }

    public synchronized long rejectCount(SwarmReject r) {
        return rejects.getOrDefault(r, 0L);
    }

    public synchronized Map<SwarmReject, Long> rejectCounts() {
        return new EnumMap<>(rejects);
    }

    public synchronized SwarmReject lastReject() {
        return lastReject;
    }

    public int pendingMessages() {
        return reassembler.pendingCount();
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }
}
