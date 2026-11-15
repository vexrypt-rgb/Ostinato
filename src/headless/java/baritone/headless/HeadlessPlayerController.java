/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The common-side IPlayerController: vanilla's MultiPlayerGameMode destroy state machine cut down to
 * the packets a server actually needs, written straight to the bot's connection. START once, progress
 * accumulated client-side with the same BlockState#getDestroyProgress the client uses, STOP when it
 * completes, ABORT when Baritone walks away. Placement and container clicks are not implemented yet:
 * a bot with no blocks planned never asks for them.
 */
package baritone.headless;

import baritone.api.utils.IPlayerController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class HeadlessPlayerController implements IPlayerController {

    private final HeadlessBot bot;
    private final HeadlessPlayer player;
    private final HeadlessLevel level;

    /** Vanilla's isDestroying: true between START and STOP/ABORT. */
    private boolean destroying;
    // Ostinato: STOP is a request, not proof of a server-side block change.
    private boolean awaitingBreak;
    private BlockPos destroyPos = BlockPos.ZERO;
    private Direction destroySide = Direction.DOWN;
    private float progress;
    private int sequence;

    private long startPackets;
    private long stopPackets;
    private long abortPackets;
    private BlockPos lastBroken;
    private int lastBrokenSequence = -1;

    public HeadlessPlayerController(HeadlessBot bot, HeadlessPlayer player, HeadlessLevel level) {
        this.bot = bot;
        this.player = player;
        this.level = level;
    }

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction side) {
        bot.send(new ServerboundPlayerActionPacket(action, pos, side, sequence++));
    }

    @Override
    public void syncHeldItem() {
        // Vanilla re-sends the held slot here. The headless inventory never changes slot while pathing.
    }

    @Override
    public boolean hasBrokenBlock() {
        return !destroying && !awaitingBreak;
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        // Ostinato: never spam START/STOP while waiting for the authoritative chunk update.
        if (awaitingBreak) {
            if (pos.equals(destroyPos)) {
                if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
                    awaitingBreak = false;
                    return true;
                }
                return false;
            }
            resetBlockRemoving();
        }
        if (destroying && pos.equals(destroyPos)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                destroying = false;
                return false;
            }
            progress += state.getDestroyProgress(player, level, pos);
            if (progress >= 1.0f) {
                send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, destroySide);
                stopPackets++;
                lastBroken = pos.immutable();
                lastBrokenSequence = sequence - 1;
                destroying = false;
                awaitingBreak = true;
                progress = 0f;
                return true;
            }
            return false;
        }
        return startDestroyBlock(pos, side);
    }

    private boolean startDestroyBlock(BlockPos pos, Direction side) {
        // Ostinato: repeated clicks continue the existing operation, including its confirmation wait.
        if (awaitingBreak || (destroying && pos.equals(destroyPos))) return onPlayerDamageBlock(pos, side);
        BlockState state = level.getBlockState(pos);
        if (!level.isLoaded(pos) || state.isAir() || state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        if (destroying) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, Direction.DOWN);
            abortPackets++;
        }
        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side);
        startPackets++;
        destroying = true;
        destroyPos = pos.immutable();
        destroySide = side;
        progress = state.getDestroyProgress(player, level, pos);
        // Ostinato: vanilla instant breaks finish on START; wait for the resulting block update.
        if (progress >= 1f) {
            destroying = false;
            awaitingBreak = true;
            lastBroken = destroyPos;
            lastBrokenSequence = sequence - 1;
        }
        return true;
    }

    @Override
    public void resetBlockRemoving() {
        // Ostinato: ABORT only while START is outstanding. STOP already asked the server to finish,
        // and a second ABORT can cancel a break that is waiting on the block-update confirmation.
        if (destroying) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, Direction.DOWN);
            abortPackets++;
        }
        destroying = false;
        awaitingBreak = false;
        progress = 0f;
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) {
        // No container interaction headlessly.
    }

    @Override
    public GameType getGameType() {
        return player.gameMode();
    }

    @Override
    public InteractionResult processRightClickBlock(Player player, Level world, InteractionHand hand, BlockHitResult result) {
        // Placement is planned only when the planner believes there are throwaway blocks; the headless
        // inventory is empty, so no path asks for one.
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult processRightClick(Player player, Level world, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        return startDestroyBlock(loc, face);
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        // Ostinato: the common helper toggles a client mouse flag; our packet state persists independently.
    }

    public long startPackets() {
        return startPackets;
    }

    public long stopPackets() {
        return stopPackets;
    }

    public long abortPackets() {
        return abortPackets;
    }

    public BlockPos lastBroken() {
        return lastBroken;
    }

    public int lastBrokenSequence() {
        return lastBrokenSequence;
    }

    /** Whether the server has acknowledged the sequence of the last STOP (its block change is confirmed). */
    public boolean lastBreakAcked() {
        return lastBrokenSequence >= 0 && bot.lastBlockChangeSequence() >= lastBrokenSequence
                && level.isLoaded(lastBroken) && level.getBlockState(lastBroken).isAir();
    }
}
