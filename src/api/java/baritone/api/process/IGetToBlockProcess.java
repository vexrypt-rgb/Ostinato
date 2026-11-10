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

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.world.level.block.Block;

/**
 * but it rescans the world every once in a while so it doesn't get fooled by its cache
 */
public interface IGetToBlockProcess extends IBaritoneProcess {

    void getToBlock(BlockOptionalMeta block);

    default void getToBlock(Block block) {
        getToBlock(new BlockOptionalMeta(block));
    }

    boolean blacklistClosest();

    /**
     * Ostinato 2.0 Phase 08: the outcome of the latest {@link #getToBlock} request. With {@code verifiedGetToBlock}
     * on, a container counts as reached only once the server opened the matching container for this request.
     */
    default ProcessOutcome outcome() {
        return ProcessOutcome.NONE;
    }

    /**
     * Ostinato 2.0 Phase 09: the identity of the menu the player has open now, for {@link #reached}. Compare by
     * identity only; null with no player, or from an implementation that cannot observe menus.
     */
    default Object openMenu() {
        return null;
    }

    /**
     * Ostinato 2.0 Phase 09: what the player sees now, independent of {@link #outcome()}. For a container opened on
     * arrival, the matching container is open and is not {@code before} (from {@link #openMenu()} when the request
     * started); otherwise a matching block is next to the player. An implementation that cannot observe this reports
     * false, never true.
     */
    default boolean reached(BlockOptionalMeta target, Object before) {
        return false;
    }
}
