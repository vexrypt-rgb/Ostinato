/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The IPathingHost for a bot: the headless behaviours and world view, with every client process
 * (follow, mine, builder, explore, farm, custom goal, get-to-block, elytra, world provider, event bus,
 * selection, command manager, swarm, movement engine, predictive physics, Grace Note, threat, PvP,
 * secondo) absent. Nothing on the executor's path calls those; the ones that read them are configured
 * off, and the host reports them as null rather than inventing a client.
 */
package baritone.headless;

import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.cache.IWorldData;
import baritone.api.cache.IWorldProvider;
import baritone.api.command.manager.ICommandManager;
import baritone.api.event.listener.IEventBus;
import baritone.api.gracenote.IGraceNote;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.physics.IPredictivePhysics;
import baritone.api.policy.IAgentPolicy;
import baritone.api.process.*;
import baritone.api.selection.ISelectionManager;
import baritone.api.swarm.ISwarm;
import baritone.api.threat.IThreatAssessment;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IWorldQueries;
import baritone.pathing.IPathingHost;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.utils.BlockBreakHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputArbiter;

import java.util.Optional;
import java.util.function.Supplier;

public final class HeadlessBaritone implements IPathingHost {

    private final HeadlessPlayerContext ctx;
    private final HeadlessBlockStateInterface bsi;
    private final HeadlessInputOverrideHandler input;
    private final HeadlessLookBehavior look;
    private final HeadlessPathingBehavior pathing;
    private final BlockBreakHelper breakHelper;

    private CalculationContext context;

    public HeadlessBaritone(HeadlessPlayerContext ctx, HeadlessBlockStateInterface bsi,
                            HeadlessInputOverrideHandler input, HeadlessLookBehavior look) {
        this.ctx = ctx;
        this.bsi = bsi;
        this.input = input;
        this.look = look;
        this.breakHelper = new BlockBreakHelper(ctx);
        this.pathing = new HeadlessPathingBehavior(this);
    }

    /** The context the last search was planned with; re-costing reads it instead of a client world. */
    public void setCalculationContext(CalculationContext context) {
        this.context = context;
    }

    // ------------------------------------------------------------------ IPathingHost

    @Override
    public BlockStateInterface bsi() {
        return bsi;
    }

    @Override
    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.empty();
    }

    @Override
    public BlockBreakHelper blockBreakHelper() {
        return breakHelper;
    }

    @Override
    public boolean hasGenericThrowaway() {
        // An empty inventory plans no bridges and no extension jumps.
        return false;
    }

    @Override
    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        return false;
    }

    @Override
    public UnifiedExecutor getUnifiedExecutor() {
        return null;
    }

    @Override
    public InputArbiter getInputArbiter() {
        return null;
    }

    // ------------------------------------------------------------------ IBaritone

    @Override
    public HeadlessPathingBehavior getPathingBehavior() {
        return pathing;
    }

    @Override
    public ILookBehavior getLookBehavior() {
        return look;
    }

    @Override
    public IInputOverrideHandler getInputOverrideHandler() {
        return input;
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return ctx;
    }

    @Override
    public IFollowProcess getFollowProcess() {
        return null;
    }

    @Override
    public IMineProcess getMineProcess() {
        return null;
    }

    @Override
    public IBuilderProcess getBuilderProcess() {
        return null;
    }

    @Override
    public IExploreProcess getExploreProcess() {
        return null;
    }

    @Override
    public IFarmProcess getFarmProcess() {
        return null;
    }

    @Override
    public ICustomGoalProcess getCustomGoalProcess() {
        return null;
    }

    @Override
    public IGetToBlockProcess getGetToBlockProcess() {
        return null;
    }

    @Override
    public IElytraProcess getElytraProcess() {
        return null;
    }

    @Override
    public IWorldProvider getWorldProvider() {
        return null;
    }

    @Override
    public IPathingControlManager getPathingControlManager() {
        return null;
    }

    @Override
    public IEventBus getGameEventHandler() {
        return null;
    }

    @Override
    public ISelectionManager getSelectionManager() {
        return null;
    }

    @Override
    public ICommandManager getCommandManager() {
        return null;
    }

    @Override
    public void openClick() {
    }

    @Override
    public IAgentPolicy getAgentPolicy() {
        return null;
    }

    @Override
    public IPvpProcess getPvpProcess() {
        return null;
    }

    @Override
    public ISwarm getSwarm() {
        return null;
    }

    @Override
    public IMovementEngine getMovementEngine() {
        return null;
    }

    @Override
    public MovementBackendKind getMovementBackendPreference() {
        return null;
    }

    @Override
    public void setFreecamStatus(Supplier<String> status) {
    }

    @Override
    public IWorldQueries getWorldQueries() {
        return null;
    }

    @Override
    public IPredictivePhysics getPredictivePhysics() {
        return null;
    }

    @Override
    public IThreatAssessment getThreatAssessment() {
        return null;
    }

    @Override
    public IGraceNote getGraceNote() {
        return null;
    }

    @Override
    public ISecondoProcess getSecondoProcess() {
        return null;
    }
}
