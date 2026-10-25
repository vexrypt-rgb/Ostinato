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
package baritone.api.process;

import net.minecraft.world.entity.LivingEntity;

/**
 * Ostinato: the combat process, typed so callers do not reflect over {@code baritone.process.PvpProcess}.
 * Read from {@link baritone.api.IBaritone#getPvpProcess()}.
 */
public interface IPvpProcess extends IBaritoneProcess {

    /** Fight the named player. */
    void attackPlayer(String name);

    /** Fight every other player in range. */
    void attackPlayers();

    /** Fight hostile mobs in range. */
    void attackHostiles();

    /** Current target, or null. */
    LivingEntity getTarget();

    /** Counters since the process was created. */
    PvpStats getStats();

    /** One-line human-readable counters, e.g. for chat. */
    String stats();
}
