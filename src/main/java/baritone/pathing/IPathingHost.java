/*
 * Ostinato: the host surface the path executor and its movement drivers need.
 *
 * PathExecutor, the kinematic/physics/sim drivers and their controllers used to take the concrete
 * client `Baritone` (and a concrete `PathingBehavior`) because they read `baritone.bsi`, the inventory
 * behavior, the unified executor and the in-progress search. Those are the only members that are not
 * on `IBaritone`, so they are lifted here; the client's `Baritone` implements this interface unchanged,
 * and a headless bot implements it over its protocol-owned world. Nothing in this file is a second
 * execution engine: the same PathExecutor and the same controllers drive both hosts.
 */
package baritone.pathing;

import baritone.api.IBaritone;
import baritone.api.gracenote.IGraceNote;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.utils.BlockBreakHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputArbiter;

import java.util.Optional;

public interface IPathingHost extends IBaritone {

    /** The world view the executor schedules against (client cache or protocol chunk store). */
    BlockStateInterface bsi();

    /** The context future movements are re-costed with when the world changes mid-path. */
    CalculationContext secretInternalGetCalculationContext();

    /** The search running in parallel with the executor, if any (the client has one; a headless bot plans inline). */
    Optional<AbstractNodeCostSearch> getInProgress();

    /** The break helper that owns the CLICK_LEFT state machine. */
    BlockBreakHelper blockBreakHelper();

    /** Whether the inventory holds a block it may throw away for a bridge or an extension. */
    boolean hasGenericThrowaway();

    /** Selects a throwaway block for a placement at the given position. */
    boolean selectThrowawayForLocation(boolean select, int x, int y, int z);

    /**
     * A vine or ladder to clutch a fall, or {@code null}. Headless has no clutch inventory
     * policy yet, so the default is {@code null}.
     */
    default net.minecraft.world.item.Item pickClutchItem(boolean select) {
        return null;
    }

    /** The physics driver's input ownership, null when that driver is off (a headless bot never creates one). */
    UnifiedExecutor getUnifiedExecutor();

    /** Per-bot input leases, read only while input arbitration is on. */
    InputArbiter getInputArbiter();

    /** Whether PvP currently owns the player; a host without combat is never in it. */
    default boolean pvpActive() {
        var pvp = getPvpProcess();
        return pvp != null && pvp.isActive();
    }

    /** Grace Note warming its predictor, if the host has one. */
    default boolean physicsWarmUp() {
        var physics = getPredictivePhysics();
        return physics == null || physics.warmUp();
    }

    /** Whether Grace Note owns movement this tick; a host without Grace Note never does. */
    default boolean graceNoteBusy() {
        IGraceNote gn = getGraceNote();
        if (gn == null) {
            return false;
        }
        if (gn.holdsControl()) {
            return true;
        }
        IGraceNote.Phase phase = gn.status().phase();
        return phase != IGraceNote.Phase.MONITORING && phase != IGraceNote.Phase.COMPLETED
                && phase != IGraceNote.Phase.FAILED;
    }
}
