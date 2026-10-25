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

package baritone.api;

import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.cache.IWorldProvider;
import baritone.api.command.manager.ICommandManager;
import baritone.api.event.listener.IEventBus;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.process.*;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;

/**
 * @author Brady
 * @since 9/29/2018
 */
public interface IBaritone {

    /**
     * @return The {@link IPathingBehavior} instance
     * @see IPathingBehavior
     */
    IPathingBehavior getPathingBehavior();

    /**
     * @return The {@link ILookBehavior} instance
     * @see ILookBehavior
     */
    ILookBehavior getLookBehavior();

    /**
     * @return The {@link IFollowProcess} instance
     * @see IFollowProcess
     */
    IFollowProcess getFollowProcess();

    /**
     * @return The {@link IMineProcess} instance
     * @see IMineProcess
     */
    IMineProcess getMineProcess();

    /**
     * @return The {@link IBuilderProcess} instance
     * @see IBuilderProcess
     */
    IBuilderProcess getBuilderProcess();

    /**
     * @return The {@link IExploreProcess} instance
     * @see IExploreProcess
     */
    IExploreProcess getExploreProcess();

    /**
     * @return The {@link IFarmProcess} instance
     * @see IFarmProcess
     */
    IFarmProcess getFarmProcess();

    /**
     * @return The {@link ICustomGoalProcess} instance
     * @see ICustomGoalProcess
     */
    ICustomGoalProcess getCustomGoalProcess();

    /**
     * @return The {@link IGetToBlockProcess} instance
     * @see IGetToBlockProcess
     */
    IGetToBlockProcess getGetToBlockProcess();

    /**
     * @return The {@link IElytraProcess} instance
     * @see IElytraProcess
     */
    IElytraProcess getElytraProcess();

    /**
     * @return The {@link IWorldProvider} instance
     * @see IWorldProvider
     */
    IWorldProvider getWorldProvider();

    /**
     * Returns the {@link IPathingControlManager} for this {@link IBaritone} instance, which is responsible
     * for managing the {@link IBaritoneProcess}es which control the {@link IPathingBehavior} state.
     *
     * @return The {@link IPathingControlManager} instance
     * @see IPathingControlManager
     */
    IPathingControlManager getPathingControlManager();

    /**
     * @return The {@link IInputOverrideHandler} instance
     * @see IInputOverrideHandler
     */
    IInputOverrideHandler getInputOverrideHandler();

    /**
     * @return The {@link IPlayerContext} instance
     * @see IPlayerContext
     */
    IPlayerContext getPlayerContext();

    /**
     * @return The {@link IEventBus} instance
     * @see IEventBus
     */
    IEventBus getGameEventHandler();

    /**
     * @return The {@link ISelectionManager} instance
     * @see ISelectionManager
     */
    ISelectionManager getSelectionManager();

    /**
     * @return The {@link ICommandManager} instance
     * @see ICommandManager
     */
    ICommandManager getCommandManager();

    /**
     * Open click
     */
    void openClick();

    /** Ostinato: policy hooks a driving mod injects (break/place avoiders, walk rules, tools, heuristics, fluids). */
    baritone.api.policy.IAgentPolicy getAgentPolicy();

    /** Ostinato: the combat process. */
    baritone.api.process.IPvpProcess getPvpProcess();

    /** Ostinato: the sealed swarm link (always non-null; check {@code isOnline()}). */
    baritone.api.swarm.ISwarm getSwarm();

    /** Ostinato: the movement engine selected by the {@code movementBackend} setting. */
    baritone.api.movement.IMovementEngine getMovementEngine();

    /** Ostinato: which backend {@link #getMovementEngine()} prefers. */
    baritone.api.movement.MovementBackendKind getMovementBackendPreference();

    /**
     * Ostinato: text for the freecam ghost tag (for example the driver's current task). Null restores the
     * default (Baritone's active process). Global, not per-instance.
     */
    void setFreecamStatus(java.util.function.Supplier<String> status);

    /** Ostinato: pathfinder-consistent world questions (water/lava, place-against, avoid-break, tool speed, path block changes). */
    baritone.api.utils.IWorldQueries getWorldQueries();

    // Ostinato: health estimator
    /**
     * @return The estimated health of nearby living entities (see {@link baritone.api.utils.IHealthEstimator}). The
     * default reads what the game shows.
     */
    default baritone.api.utils.IHealthEstimator getHealthEstimator() {
        return e -> new baritone.api.utils.HealthEstimate(e.getHealth(), e.getAbsorptionAmount(), e.getMaxHealth(), 1, 0, "synced");
    }
}
