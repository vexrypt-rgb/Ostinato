/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The common-side IPlayerController: vanilla's MultiPlayerGameMode destroy state machine cut down to
 * the packets a server actually needs, written straight to the bot's connection. START once, progress
 * accumulated client-side with the same BlockState#getDestroyProgress the client uses, STOP when it
 * completes, ABORT when Baritone walks away. Placement sends ServerboundUseItemOnPacket on the same
 * pre-incremented block-change sequence and waits for the chunk store before treating it as confirmed.
 */
package baritone.headless;

import baritone.api.utils.IPlayerController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class HeadlessPlayerController implements IPlayerController {

    /** Ticks an in-flight place may wait for an ack or a block update before the prediction is undone. */
    static final int PLACE_TIMEOUT_TICKS = 40;

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
    /** Vanilla BlockStatePredictionHandler starts at 0 and pre-increments, so the first action is sequence 1. */
    private int sequence;
    /** Last hotbar index sent in ServerboundSetCarriedItemPacket. -1 forces the first sync. */
    private int carriedIndex = -1;

    private long startPackets;
    private long stopPackets;
    private long abortPackets;
    private BlockPos lastBroken;
    private int lastBrokenSequence = -1;

    private boolean awaitingPlace;
    private boolean placeRolledBack;
    private int placeTicks;
    private int placeSlot = -1;
    private ItemStack placeSnapshot = ItemStack.EMPTY;
    private Block expectedBlock;
    private BlockPos lastPlaced;
    private int lastPlacedSequence = -1;
    private volatile boolean lastPlaceAcked;
    private volatile boolean lastPlaceFailed;
    private long placePackets;

    public HeadlessPlayerController(HeadlessBot bot, HeadlessPlayer player, HeadlessLevel level) {
        this.bot = bot;
        this.player = player;
        this.level = level;
    }

    /** @return the sequence written on the packet (pre-increment, matching vanilla prediction). */
    private int send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction side) {
        int seq = ++sequence;
        bot.send(new ServerboundPlayerActionPacket(action, pos, side, seq));
        return seq;
    }

    @Override
    public void syncHeldItem() {
        // Ostinato: vanilla only resends when the selected hotbar slot changed.
        int slot = player.getInventory().getSelectedSlot();
        if (!Inventory.isHotbarSlot(slot) || slot == carriedIndex) {
            return;
        }
        bot.send(new ServerboundSetCarriedItemPacket(slot));
        carriedIndex = slot;
    }

    // Ostinato: eat or otherwise use the held item. Sequence matches vanilla prediction.
    public void useHeldItem(InteractionHand hand, float yRot, float xRot) {
        int seq = ++sequence;
        bot.send(new ServerboundUseItemPacket(hand, seq, yRot, xRot));
    }

    public void releaseUseItem() {
        send(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN);
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
                stopPackets++;
                lastBroken = pos.immutable();
                lastBrokenSequence = send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, destroySide);
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
        int started = send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side);
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
            lastBrokenSequence = started;
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
        // Ostinato: hashed ServerboundContainerClick, same path MultiPlayerGameMode.handleContainerInput uses.
        bot.inventory().windowClick(windowId, slotId, mouseButton, type, player);
    }

    /** Right-click a block without predicting a placement (opening a table, furnace, or chest). */
    void interactBlock(BlockHitResult result) {
        syncHeldItem();
        int seq = ++sequence;
        bot.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, seq));
    }

    void dropHeld(boolean all) {
        ServerboundPlayerActionPacket.Action action = all
                ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                : ServerboundPlayerActionPacket.Action.DROP_ITEM;
        send(action, BlockPos.ZERO, Direction.DOWN);
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            if (all) {
                player.getInventory().setItem(player.getInventory().getSelectedSlot(), ItemStack.EMPTY);
            } else {
                held.shrink(1);
            }
        }
    }

    @Override
    public GameType getGameType() {
        return player.gameMode();
    }

    @Override
    public InteractionResult processRightClickBlock(Player player, Level world, InteractionHand hand, BlockHitResult result) {
        // Ostinato: one in-flight place. Another click while it is open is not a second packet.
        synchronized (this.player.getInventory()) {
            if (awaitingPlace) {
                return InteractionResult.SUCCESS;
            }
            // Ostinato: the real client sends one UseItemOn per pillar layer. After the
            // server (or prediction) has accepted a cell, do not click it again every
            // tick of the jump. Repeating it is Grim Simulation 0.075 / Post placement.
            BlockPos already = lastPlaced;
            if (already != null && lastPlaceAcked && !lastPlaceFailed) {
                ItemStack held = this.player.getItemInHand(hand);
                if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
                    BlockPos clicked = result.getBlockPos();
                    BlockState clickedState = level.getBlockState(clicked);
                    BlockPos dest = clickedState.canBeReplaced() ? clicked : clicked.relative(result.getDirection());
                    if (already.equals(dest)) {
                        return InteractionResult.SUCCESS;
                    }
                }
            }
            syncHeldItem();
            int seq = ++sequence;
            bot.send(new ServerboundUseItemOnPacket(hand, result, seq));
            placePackets++;
            if (predictPlace(hand, result, seq)) {
                return InteractionResult.SUCCESS;
            }
        }
        // Empty hand still sent the packet (vanilla always does) and lets the other hand try.
        return InteractionResult.PASS;
    }

    /**
     * Ostinato: decrement locally only. The chunk store stays server-owned; writing the block here
     * would look like a confirmation the server has not given.
     */
    private boolean predictPlace(InteractionHand hand, BlockHitResult result, int seq) {
        ItemStack held = this.player.getItemInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        if (this.player.gameMode().isCreative()) {
            return true;
        }
        BlockPos clicked = result.getBlockPos();
        if (!level.isLoaded(clicked)) {
            return false;
        }
        BlockState clickedState = level.getBlockState(clicked);
        BlockPos dest = clickedState.canBeReplaced() ? clicked : clicked.relative(result.getDirection());
        if (!level.isLoaded(dest) || !level.getBlockState(dest).canBeReplaced()) {
            return false;
        }
        placeSnapshot = held.copy();
        placeSlot = hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : this.player.getInventory().getSelectedSlot();
        held.shrink(1);
        awaitingPlace = true;
        placeRolledBack = false;
        placeTicks = 0;
        expectedBlock = blockItem.getBlock();
        lastPlaced = dest.immutable();
        lastPlacedSequence = seq;
        lastPlaceAcked = false;
        lastPlaceFailed = false;
        return true;
    }

    @Override
    public InteractionResult processRightClick(Player player, Level world, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    /** Called from the live driver once per tick. Advances the place timeout, then re-checks the world. */
    public void tickPendingPlace() {
        synchronized (player.getInventory()) {
            if (awaitingPlace) {
                placeTicks++;
            }
            pollPlaceLocked();
        }
    }

    /** Ack or block update arrived. Does not advance the timeout. */
    public void pollPlace() {
        synchronized (player.getInventory()) {
            pollPlaceLocked();
        }
    }

    /** Drop a prediction because pathing was cancelled. A later matching block update still re-applies it. */
    public void abandonPlace() {
        synchronized (player.getInventory()) {
            if (awaitingPlace) {
                rollbackLocked();
            }
        }
    }

    private void pollPlaceLocked() {
        if (lastPlaced == null || expectedBlock == null) {
            return;
        }
        boolean shown = level.isLoaded(lastPlaced) && level.getBlockState(lastPlaced).is(expectedBlock);
        if (awaitingPlace && shown) {
            awaitingPlace = false;
            placeRolledBack = false;
            lastPlaceFailed = false;
            lastPlaceAcked = true;
            return;
        }
        if (awaitingPlace) {
            boolean acked = lastPlacedSequence >= 0 && bot.lastBlockChangeSequence() >= lastPlacedSequence;
            if (acked || placeTicks >= PLACE_TIMEOUT_TICKS) {
                rollbackLocked();
            }
        }
        if (placeRolledBack && shown) {
            reapplyLocked();
        }
    }

    private void rollbackLocked() {
        if (!awaitingPlace) {
            return;
        }
        ItemStack current = player.getInventory().getItem(placeSlot);
        int expectedCount = Math.max(0, placeSnapshot.getCount() - 1);
        boolean stillPredicted = expectedCount == 0
                ? current.isEmpty()
                : !current.isEmpty() && current.getCount() == expectedCount && current.getItem() == placeSnapshot.getItem();
        if (stillPredicted) {
            player.getInventory().setItem(placeSlot, placeSnapshot.copy());
        }
        awaitingPlace = false;
        placeRolledBack = true;
        lastPlaceFailed = true;
        lastPlaceAcked = false;
    }

    private void reapplyLocked() {
        ItemStack current = player.getInventory().getItem(placeSlot);
        if (!current.isEmpty() && current.getItem() == placeSnapshot.getItem() && current.getCount() == placeSnapshot.getCount()) {
            current.shrink(1);
        }
        placeRolledBack = false;
        lastPlaceFailed = false;
        lastPlaceAcked = true;
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

    public long placePackets() {
        return placePackets;
    }

    public BlockPos lastPlaced() {
        return lastPlaced;
    }

    public int lastPlacedSequence() {
        return lastPlacedSequence;
    }

    public boolean lastPlaceAcked() {
        return lastPlaceAcked;
    }

    public boolean lastPlaceFailed() {
        return lastPlaceFailed;
    }

    public boolean awaitingPlace() {
        return awaitingPlace;
    }

    public boolean placeConfirmed(BlockPos pos) {
        return lastPlaceAcked && lastPlaced != null && lastPlaced.equals(pos);
    }

    public boolean placeRejected(BlockPos pos) {
        return lastPlaceFailed && !lastPlaceAcked && lastPlaced != null && lastPlaced.equals(pos);
    }
}
