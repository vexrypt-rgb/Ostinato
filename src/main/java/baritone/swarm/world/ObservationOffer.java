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

package baritone.swarm.world;

import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Ostinato: Phase 2c. Hands one observation message of this bot to the link toward the coordinator.
 *
 * <p>Every observation message goes at one priority (NORMAL), so a bot's messages leave in the order they were
 * numbered (p2ch run 4: a gone report at HIGH overtook an earlier report at NORMAL). Over chat a message is offered
 * only when no line of equal or higher priority is queued: entry ages are read when the frame is built, and a frame
 * queued behind other lines would leave seconds later with that stale age and look newer to the coordinator (p2ch
 * run 3: ~24 s); the entries stay pending in the reporter and age honestly.
 *
 * <p>Over chat the frame also carries a deadline of {@link #CHAT_DEADLINE_MS}: still queued then (higher-priority
 * lines kept overtaking it), the limiter takes it back and the reporter re-queues its entries, which leave later with
 * their own observation times and a new number (p2ch run 7: a report that waited 22 s looked 22 s newer than it was
 * and beat a real "gone"). That bounds the age error of anything the coordinator applies from chat.
 *
 * <p>Each message handed over is logged as {@code swarm-obs: offered <op> #<seq> run <epoch> via chat|spool} so a
 * live gate can see which path a numbered message took. The line is written only after the link accepted the
 * message and changes nothing else (ObservationOfferTest).
 */
public final class ObservationOffer {

    public static final SwarmPriority PRIORITY = SwarmPriority.NORMAL;

    /**
     * How long an observation frame may wait in the chat queue: 5 s. On a link that is not saturated a line leaves
     * within one token wait (2 s at the vanilla-safe 0.5 lines/s), so 5 s lets a frame wait behind a couple of
     * overtaking HIGH lines and still go; it also keeps the age error well under the ~10 s seen live between a bot's
     * scan and the next fact about the same blocks (row 11: report at 20:01:11, vein mined out at 20:01:22).
     */
    public static final long CHAT_DEADLINE_MS = 5_000L;

    /** The sealed send toward one member. {@code deadline} is null over the spool. */
    public interface Link {
        boolean send(String to, String body, SwarmPriority priority, SwarmRateLimiter.Deadline deadline);
    }

    private ObservationOffer() {
    }

    /**
     * @param chat the chat limiter when this message goes over chat, null when it goes over the local spool
     * @param log  where the "offered" line goes; null writes nothing
     * @param withdrawn told the message's seq if the chat limiter takes the frame back (must only record it)
     * @return true if the link took the message (the reporter then numbers the next one)
     */
    public static boolean offer(ObservationMessage m, String to, SwarmRateLimiter chat, Link link, Consumer<String> log,
                                LongConsumer withdrawn) {
        if (chat != null && chat.queuedAtOrAbove(PRIORITY) > 0) {
            return false;
        }
        long seq = m.seq;
        SwarmRateLimiter.Deadline deadline = chat == null ? null : new SwarmRateLimiter.Deadline(CHAT_DEADLINE_MS,
                "obs " + m.op + " #" + seq, withdrawn == null ? () -> { } : () -> withdrawn.accept(seq));
        boolean ok = link.send(to, m.encode(), PRIORITY, deadline);
        if (ok && log != null) {
            log.accept(line(m, chat == null));
        }
        return ok;
    }

    static String line(ObservationMessage m, boolean spool) {
        return "swarm-obs: offered " + m.op + " #" + m.seq + " run " + m.epoch + " via " + (spool ? "spool" : "chat")
                + " (" + m.entries.size() + " entries)";
    }
}
