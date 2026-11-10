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

package baritone.process.interact;

/**
 * Ostinato 2.0 Phase 09: what the player has open, without naming a Minecraft class. The menu classes differ between
 * versions (26.3 {@code ChestMenu}, 1.16 {@code ChestContainer}, ...), so only the version's adapter
 * ({@link ClientContainers} on this tree) reads them. No Minecraft import here, and Java 8 syntax, so the same file
 * builds on every branch.
 * <p>
 * The client's open menu changes only when the server's open-screen packet arrives, so a container kind read here is
 * what the server opened.
 */
public interface ContainerView {

    enum Kind {
        /** No container open: only the player's own inventory, or no player. */
        NONE,
        CHEST,
        CRAFTING,
        FURNACE,
        BLAST_FURNACE,
        /** A container this capability does not open. */
        OTHER
    }

    Kind kind();

    /** The open menu's identity: a new open is a new object. Compare by identity only. Null with no player. */
    Object menu();

    /** Close the open container. */
    void close();
}
