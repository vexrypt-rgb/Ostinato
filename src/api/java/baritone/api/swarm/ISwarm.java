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
package baritone.api.swarm;

import java.util.List;

/**
 * Ostinato: the sealed swarm link, typed so a host mod can ride it without reflection. Read from
 * {@link baritone.api.IBaritone#getSwarm()}. When the link is not running, {@link #isOnline()} is false,
 * {@link #self()} is null, {@link #myGroups()} is empty and {@link #send} returns false.
 */
public interface ISwarm {

    boolean isOnline();

    /** Why the link is not online (for example "disabled" or "no roster"), or "online". */
    String state();

    /** This member's id, or null when offline. */
    String self();

    /** Groups this member belongs to. */
    List<ISwarmGroup> myGroups();

    /** A roster group by id, or null. */
    ISwarmGroup group(String id);

    /**
     * Seal and send {@code body} as message {@code type} to {@code to} in {@code group}. Returns false when
     * offline, the group is unknown, or the send failed.
     */
    boolean send(String group, String to, String type, String body, SwarmPriority priority);

    /**
     * Route authenticated messages of {@code type} to {@code handler}. Built-in types (PING, PONG, build
     * messages) are reserved and throw {@link IllegalArgumentException}. Returns false when offline; call again
     * after the link comes up (handlers live on the running link).
     */
    boolean registerHandler(String type, SwarmHandler handler);

    @FunctionalInterface
    interface SwarmHandler {
        void onMessage(String from, String group, String body);
    }
}
