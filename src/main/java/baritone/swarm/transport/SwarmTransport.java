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


package baritone.swarm.transport;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** Moves sealed swarm lines. Only S1C / S2C / S2S tokens; no plaintext mode. */
public interface SwarmTransport extends Closeable {

    String BROADCAST = "*";

    Pattern SEALED_LINE = Pattern.compile("S(?:1C|2C|2S)\\.[a-z0-9]{4}\\.[A-Za-z0-9_-]{38,}");

    String selfId();

    void send(String recipient, String sealedLine) throws IOException;

    default void sendTo(String group, String recipient, String sealedLine, SwarmPriority priority) throws IOException {
        send(recipient, sealedLine);
    }

    /**
     * Ostinato: {@link #sendTo(String, String, String, SwarmPriority)} for a line the sender stops waiting for after
     * {@code deadline}; a rate-limited transport logs once if the line is still queued then.
     */
    default void sendTo(String group, String recipient, String sealedLine, SwarmPriority priority,
                        SwarmRateLimiter.Deadline deadline) throws IOException {
        sendTo(group, recipient, sealedLine, priority);
    }

    /**
     * Ostinato: whether any line left for {@code peer} (directed or one every member reads) less than {@code windowMs}
     * before {@code nowMs}, on the caller's monotonic clock. A peer that got a frame does not need a heartbeat. False
     * when the transport does not know, which keeps every heartbeat.
     */
    default boolean sentWithin(String peer, long nowMs, long windowMs) {
        return false;
    }

    List<String> receive() throws IOException;

    static boolean isSealed(String line) {
        return line != null && SEALED_LINE.matcher(line).matches();
    }

    static void requireSealed(String line) {
        if (!isSealed(line)) {
            throw new IllegalArgumentException("swarm transports only carry single S1C/S2C/S2S tokens");
        }
    }
}
