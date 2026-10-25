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

package baritone.utils;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import net.minecraft.client.player.KeyboardInput;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        if (input == null) return false;
        // Ostinato: after realizePhysicalKeys, the game sees the hand's chord. Before that, and while the
        // setting is off, this is the map the callers wrote.
        if (physicalLive && Baritone.settings().physicalInput.value) return chord.isDown(input);
        return this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Ostinato: start of a client tick, before listeners rewrite the keys. The first tick the setting is on
     * copies the keys already held, so enabling it does not invent a chord change.
     */
    public void openPhysicalTick() {
        if (!wasPhysical) {
            chord.snap(inputForceStateMap);
            stick.disarm();
            wasPhysical = true;
        }
        physicalLive = false;
    }

    /**
     * Ostinato: after every listener has written the keys it wants. At most two of them change this tick.
     * Block breaking runs here so it sees that chord and not the raw request.
     */
    public void realizePhysicalKeys() {
        if (!Baritone.settings().physicalInput.value || physicalLive) return;
        chord.step(inputForceStateMap, PhysicalInput.MAX_EDGES_PER_TICK);
        physicalLive = true;
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
        this.analog = null;
    }

    /** Ostinato: the stick position for the next movement tick, or null. */
    private float[] analog;

    /** Ostinato: the chord actually given to the game while physicalInput is on. */
    private final PhysicalInput.Chord chord = new PhysicalInput.Chord();
    private final PhysicalInput.Stick stick = new PhysicalInput.Stick();
    private boolean physicalLive;
    private boolean wasPhysical;

    @Override
    public void setAnalog(float left, float forward) {
        this.analog = new float[]{Math.max(-1, Math.min(1, left)), Math.max(-1, Math.min(1, forward))};
    }

    /** The stick position, once: a movement sets it again every tick it wants it. Null when unset or off. */
    float[] takeAnalog() {
        float[] a = this.analog;
        this.analog = null;
        if (!Baritone.settings().analogMovement.value) {
            stick.disarm(); // Ostinato: a later physical stick starts from centre
            return null;
        }
        // Ostinato: off, the requested stick is returned as it was. On, it slews instead of jumping.
        if (a == null || !Baritone.settings().physicalInput.value) {
            stick.disarm();
            return a;
        }
        return stick.slew(a[0], a[1]);
    }

    /** Standing on magma: sneaking is the only thing that stops it burning us, whatever else is steering. */
    boolean onMagma() {
        if (ctx.player() == null || ctx.world() == null || !ctx.player().onGround()) {
            return false;
        }
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(ctx.player().position().add(0, -0.2, 0));
        return ctx.world().getBlockState(below).getBlock() == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK;
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (!Baritone.settings().physicalInput.value) {
            physicalLive = false;
            wasPhysical = false;
        }
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            setInputForceState(Input.CLICK_LEFT, false);
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        // Ostinato: while physicalInput is on, the break and place run from realizePhysicalKeys after the chord.
        if (!Baritone.settings().physicalInput.value) {
            blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
            blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));
        }

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    private boolean inControl() {
        if (analog != null && Baritone.settings().analogMovement.value) {
            return true;
        }
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
