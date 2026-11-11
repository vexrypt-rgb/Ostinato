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

import baritone.api.Settings;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilS1C;
import baritone.swarm.crypto.SigilWire;

/**
 * Immutable snapshot of the {@code swarm*} {@link Settings}, clamped to safe
 * ranges. Core swarm classes take this instead of reading the settings
 * singleton so they can be unit tested without a game instance.
 */
public final class SwarmConfig {

    public static final int MIN_FRAME_BYTES = 48;
    public static final int MIN_SEAL_LINE = 128;

    private final SigilWire wire;
    private final int maxLineChars;
    private final int lineReserveChars;
    private final int maxFrameBytes;
    private final int maxChunks;
    private final long reassemblyTimeoutMs;
    private final int maxPendingMessages;
    private final int replayWindow;
    private final int maxClockSkewSec;
    private final int maxPeers;
    private final String localSpoolDir;
    private final boolean requireSignedSender;

    private SwarmConfig(Builder b) {
        this.wire = SigilWire.parse(b.wireVersion);
        this.maxLineChars = clamp(b.maxLineChars, MIN_SEAL_LINE, SigilS1C.MINECRAFT_MAX_LINE);
        this.lineReserveChars = clamp(b.lineReserveChars, 0, this.maxLineChars - MIN_SEAL_LINE);
        // signed senders seal every line as S2S, whose signature trailer leaves less room than the circle wire
        SigilWire sealed = b.requireSignedSender ? SigilWire.S2S : this.wire;
        int auto = SigilCodec.maxSingleLinePayloadBytes(sealed, this.maxLineChars - this.lineReserveChars);
        this.maxFrameBytes = b.maxFrameBytes <= 0 ? auto : Math.min(auto, Math.max(MIN_FRAME_BYTES, b.maxFrameBytes));
        this.maxChunks = clamp(b.maxChunks, 1, 64);
        this.reassemblyTimeoutMs = Math.max(1000L, Math.min(b.reassemblyTimeoutMs, 600_000L));
        this.maxPendingMessages = clamp(b.maxPendingMessages, 1, 4096);
        this.replayWindow = clamp(b.replayWindow, 16, 65536);
        this.maxClockSkewSec = b.maxClockSkewSec <= 0 ? 0 : Math.max(5, b.maxClockSkewSec);
        this.maxPeers = clamp(b.maxPeers, 1, 4096);
        this.localSpoolDir = b.localSpoolDir == null ? "" : b.localSpoolDir.trim();
        this.requireSignedSender = b.requireSignedSender;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static SwarmConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SwarmConfig fromSettings(Settings s) {
        return builderFromSettings(s).build();
    }

    public static Builder builderFromSettings(Settings s) {
        Builder b = builder();
        b.wireVersion = s.swarmWireVersion.value;
        b.maxLineChars = s.swarmMaxLineChars.value;
        b.lineReserveChars = s.swarmLineReserveChars.value;
        b.maxFrameBytes = s.swarmMaxFrameBytes.value;
        b.maxChunks = s.swarmMaxChunks.value;
        b.reassemblyTimeoutMs = s.swarmReassemblyTimeoutMs.value;
        b.maxPendingMessages = s.swarmMaxPendingMessages.value;
        b.replayWindow = s.swarmReplayWindow.value;
        b.maxClockSkewSec = s.swarmMaxClockSkewSec.value;
        b.maxPeers = s.swarmMaxPeers.value;
        b.localSpoolDir = s.swarmLocalSpoolDir.value;
        b.requireSignedSender = s.swarmRequireSignedSender.value;
        return b;
    }

    public SigilWire wire() { return wire; }
    public int sealLineBudget() { return maxLineChars - lineReserveChars; }
    public int maxFrameBytes() { return maxFrameBytes; }
    public int maxLineChars() { return maxLineChars; }
    public int lineReserveChars() { return lineReserveChars; }
    public int maxChunks() { return maxChunks; }
    public long reassemblyTimeoutMs() { return reassemblyTimeoutMs; }
    public int maxPendingMessages() { return maxPendingMessages; }
    public int replayWindow() { return replayWindow; }
    public int maxClockSkewSec() { return maxClockSkewSec; }
    public int maxPeers() { return maxPeers; }
    public String localSpoolDir() { return localSpoolDir; }
    /** When true, inbound frames must be S2S with a pinned Ed25519 key. */
    public boolean requireSignedSender() { return requireSignedSender; }

    public static final class Builder {
        public String wireVersion = "S2";
        public int maxLineChars = 256;
        public int lineReserveChars = 22;
        public int maxFrameBytes = 0;
        public int maxChunks = 32; // Ostinato: signed command/cancel envelopes exceed eight small frames.
        public long reassemblyTimeoutMs = 30_000L;
        public int maxPendingMessages = 64;
        public int replayWindow = 256;
        public int maxClockSkewSec = 0;
        public int maxPeers = 64;
        public String localSpoolDir = "";
        public boolean requireSignedSender = true;

        private Builder() {}

        public SwarmConfig build() {
            return new SwarmConfig(this);
        }
    }
}
