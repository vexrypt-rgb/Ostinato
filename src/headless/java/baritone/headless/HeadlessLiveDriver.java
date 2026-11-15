package baritone.headless;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.PlayerSim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Ostinato: tick-owned bridge between the protocol body, path executor and PlayerSim intents.
final class HeadlessLiveDriver implements HeadlessMovement.Driver {
    private final HeadlessPlayer player;
    private final HeadlessLevel level;
    private final HeadlessPlayerController controller;
    private final HeadlessPlayerContext context;
    private final HeadlessInputOverrideHandler input = new HeadlessInputOverrideHandler();
    private final HeadlessLookBehavior look;
    private final HeadlessBaritone host;
    private volatile BlockPos mining;
    private int miningTicks;
    private volatile String miningStatus = "idle";
    private volatile BlockPos placing;
    private int placeWait;
    private int placeAttempts;
    private boolean placeAimed;
    private volatile String placeStatus = "idle";

    /** Down from the target first (click the top of the floor), then the four sides, then the ceiling. */
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    HeadlessLiveDriver(HeadlessBot bot, HeadlessPlayer player, HeadlessLevel level) {
        this.player = player;
        this.level = level;
        controller = new HeadlessPlayerController(bot, player, level);
        context = new HeadlessPlayerContext(player, level, controller);
        look = new HeadlessLookBehavior(player);
        // Ostinato: placement packets are real, so bridges, pillars and parkour-place may plan.
        // Parkour moves are not even considered unless allowParkour is on, so parkour-place needs both.
        // allowGodBridge stays off: executing it walks backward off the edge into a sub-block window
        // PlayerSim has not been shown to hit, and this slice does not change PlayerSim.
        // unifiedExecutor stays off; the live driver runs the legacy path executor.
        Baritone.settings().allowPlace.value = true;
        Baritone.settings().allowParkour.value = true;
        Baritone.settings().allowParkourPlace.value = true;
        Baritone.settings().allowGodBridge.value = false;
        Baritone.settings().unifiedExecutor.value = false;
        host = new HeadlessBaritone(context, new HeadlessBlockStateInterface(bot.chunks()), input, look);
    }

    /** True while a goal is queued, a mine is waiting on a break, or a place command is in progress. */
    boolean controlsMovement() {
        return host.getPathingBehavior().isPathing() || mining != null || placing != null;
    }

    HeadlessBaritone baritone() {
        return host;
    }

    HeadlessPlayerController controller() {
        return controller;
    }

    HeadlessPlayer player() {
        return player;
    }

    void gotoBlock(int x, int y, int z) {
        cancel();
        host.getPathingBehavior().gotoGoal(new GoalBlock(x, y, z));
    }

    void cancel() {
        mining = null;
        miningStatus = "idle";
        placing = null;
        placeStatus = "idle";
        placeAimed = false;
        placeAttempts = 0;
        host.getPathingBehavior().cancelEverything();
        host.getPathingBehavior().noteCancelled();
        controller.resetBlockRemoving();
        controller.abandonPlace();
        input.clearAllKeys();
    }

    /**
     * Ostinato: place one throwaway into {@code (x, y, z)} against an adjacent sturdy face.
     * The first owned tick only aims, so the look packet is on the wire before UseItemOn.
     */
    void place(int x, int y, int z) {
        cancel();
        placing = new BlockPos(x, y, z);
        placeWait = 0;
        placeAttempts = 0;
        placeAimed = false;
        placeStatus = "placing " + placing;
    }

    // Ostinato: a bounded nearest-loaded-block operation, not the client #mine process.
    void mine(String block) {
        cancel();
        String id = block.contains(":") ? block : "minecraft:" + block;
        BlockPos feet = player.blockPosition();
        double best = Double.POSITIVE_INFINITY;
        for (int x = feet.getX() - 16; x <= feet.getX() + 16; x++) {
            for (int z = feet.getZ() - 16; z <= feet.getZ() + 16; z++) {
                for (int y = Math.max(level.getMinY(), feet.getY() - 8);
                     y <= Math.min(level.getMinY() + level.getHeight() - 1, feet.getY() + 8); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    var state = level.getBlockState(pos);
                    if (state.isAir() || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id)
                            || state.getDestroySpeed(level, pos) < 0) continue;
                    double distance = pos.distSqr(feet);
                    if (distance < best) {
                        mining = pos;
                        best = distance;
                    }
                }
            }
        }
        if (mining == null) throw new IllegalArgumentException("No breakable loaded " + id + " within 16 horizontal / 8 vertical blocks");
        miningTicks = 0;
        miningStatus = "approaching " + mining;
        host.getPathingBehavior().gotoGoal(new GoalGetToBlock(mining));
    }

    @Override
    public HeadlessMovement.Step tick(HeadlessBot bot, HeadlessMovement movement) {
        sync(movement);
        controller.tickPendingPlace();
        boolean owns = host.getPathingBehavior().isPathing() || mining != null || placing != null;
        if (!owns) return null;
        try {
            host.getPathingBehavior().tick();
            if (mining != null) tickMining();
            else if (placing != null) tickPlaceCommand();
            else {
                tickAttack();
                tickUse();
            }
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(),
                    key(Input.MOVE_FORWARD) - key(Input.MOVE_BACK), key(Input.MOVE_LEFT) - key(Input.MOVE_RIGHT),
                    down(Input.SPRINT), down(Input.JUMP), down(Input.SNEAK), placing != null ? "place" : "pathing");
        } catch (RuntimeException e) {
            cancel();
            System.err.println("[HeadlessPathing] cancelled after executor error: " + e);
            e.printStackTrace(System.err);
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(), 0, 0, false, false, false, "path error");
        }
    }

    /** Right click from the path executor. Left click wins, matching the client place helper. */
    private void tickUse() {
        if (!down(Input.CLICK_RIGHT) || down(Input.CLICK_LEFT)) {
            return;
        }
        HitResult hit = context.objectMouseOver();
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, block) == InteractionResult.SUCCESS) {
            return;
        }
        controller.processRightClickBlock(player, level, InteractionHand.OFF_HAND, block);
    }

    private void tickPlaceCommand() {
        BlockPos target = placing;
        if (controller.placeConfirmed(target)) {
            finishPlace("placed " + target.toShortString());
            return;
        }
        if (++placeWait > 80 || (controller.placeRejected(target) && placeAttempts >= 4)) {
            finishPlace(controller.placeRejected(target) ? "rejected " + target.toShortString() : "timed out " + target.toShortString());
            return;
        }
        if (!level.isLoaded(target)) {
            finishPlace("target not loaded " + target.toShortString());
            return;
        }
        BlockState cell = level.getBlockState(target);
        if (!cell.canBeReplaced()) {
            finishPlace(isThrowawayBlock(cell) ? "already placed " + target.toShortString() : "cell occupied " + target.toShortString());
            return;
        }
        BlockPos support = findSupport(target);
        if (support == null) {
            finishPlace("no adjacent support " + target.toShortString());
            return;
        }
        Direction face = faceToward(support, target);
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double reach = controller.getBlockReachDistance();
        if (player.getEyePosition().distanceToSqr(hit) > reach * reach) {
            finishPlace("out of reach " + target.toShortString());
            return;
        }
        if (!host.selectThrowawayForLocation(true, target.getX(), target.getY(), target.getZ())) {
            finishPlace("no throwaway " + target.toShortString());
            return;
        }
        if (alreadyHolding(target)) {
            finishPlace("already placed " + target.toShortString());
            return;
        }
        Rotation rotation = RotationUtils.calcRotationFromVec3d(player.getEyePosition(), hit,
                new Rotation(player.getYRot(), player.getXRot()));
        look.updateTarget(rotation, true);
        // Ostinato: the server is still on last tick's look until this tick's PosRot lands.
        if (!placeAimed) {
            placeAimed = true;
            placeStatus = "aiming " + target.toShortString();
            return;
        }
        if (controller.awaitingPlace()) {
            placeStatus = "waiting " + target.toShortString();
            return;
        }
        BlockHitResult at = new BlockHitResult(hit, face, support, false);
        InteractionResult result = controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, at);
        if (result != InteractionResult.SUCCESS) {
            controller.processRightClickBlock(player, level, InteractionHand.OFF_HAND, at);
        }
        placeAttempts++;
        placeStatus = "clicked " + target.toShortString();
    }

    private BlockPos findSupport(BlockPos target) {
        for (Direction towardSupport : SUPPORT_ORDER) {
            BlockPos support = target.relative(towardSupport);
            if (!level.isLoaded(support)) {
                continue;
            }
            if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(support.getX(), support.getY(), support.getZ())) {
                continue;
            }
            Direction face = towardSupport.getOpposite();
            BlockState state = level.getBlockState(support);
            if (state.isFaceSturdy(level, support, face)) {
                return support;
            }
        }
        return null;
    }

    private static Direction faceToward(BlockPos support, BlockPos target) {
        for (Direction face : Direction.values()) {
            if (support.relative(face).equals(target)) {
                return face;
            }
        }
        return Direction.UP;
    }

    private boolean alreadyHolding(BlockPos target) {
        BlockState cell = level.getBlockState(target);
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BlockItem block && cell.is(block.getBlock())) {
            return true;
        }
        ItemStack off = player.getInventory().getItem(Inventory.SLOT_OFFHAND);
        return off.getItem() instanceof BlockItem block && cell.is(block.getBlock());
    }

    private static boolean isThrowawayBlock(BlockState state) {
        Item item = state.getBlock().asItem();
        return Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    private void finishPlace(String result) {
        placing = null;
        placeAimed = false;
        placeStatus = result;
        input.clearAllKeys();
        System.out.println("[HeadlessPlace] " + result);
    }

    void noteWorldChange() {
        controller.pollPlace();
    }

    void noteAck() {
        controller.pollPlace();
    }

    private void tickMining() {
        if (!level.isLoaded(mining)) {
            finishMining("target chunk unloaded");
        } else if (level.getBlockState(mining).isAir()) {
            finishMining("server-confirmed air at " + mining);
        } else if (++miningTicks > 1200) {
            finishMining("timed out awaiting approach/break confirmation");
        } else if (!host.getPathingBehavior().isPathing()) {
            input.clearAllKeys();
            var rotation = RotationUtils.reachable(context, mining, controller.getBlockReachDistance());
            if (rotation.isEmpty()) {
                finishMining("target unreachable from path endpoint");
                return;
            }
            look.updateTarget(rotation.get(), true);
            miningStatus = "breaking / waiting for server at " + mining;
            HitResult hit = context.objectMouseOver();
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                    && block.getBlockPos().equals(mining)) {
                controller.onPlayerDamageBlock(mining, block.getDirection());
            }
        } else {
            tickAttack();
        }
    }

    private void tickAttack() {
        HitResult hit = down(Input.CLICK_LEFT) ? context.objectMouseOver() : null;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            controller.onPlayerDamageBlock(block.getBlockPos(), block.getDirection());
        } else {
            controller.resetBlockRemoving();
        }
    }

    private void finishMining(String result) {
        cancel();
        miningStatus = result;
        System.out.println("[HeadlessMine] " + result);
    }

    private boolean down(Input key) { return input.isInputForcedDown(key); }
    private int key(Input key) { return down(key) ? 1 : 0; }

    @Override
    public float[] pollAnalog() { return input.pollAnalog(); }

    // Ostinato: entity tick is never called; copy the authoritative sim body before AND after execution.
    void sync(HeadlessMovement movement) {
        PlayerSim sim = movement.sim();
        player.setPos(sim.x, sim.y, sim.z);
        player.setDeltaMovement(sim.vx, sim.vy, sim.vz);
        player.setOnGround(sim.onGround);
        player.horizontalCollision = sim.collidedH;
        player.setYRot(movement.yaw());
        player.setXRot(movement.pitch());
        player.setSprinting(sim.sprinting);
        player.setSwimming(sim.swimming);
        player.setShiftKeyDown(movement.shift());
        player.setPose(sim.swimming ? Pose.SWIMMING : movement.shift() ? Pose.CROUCHING : Pose.STANDING);
    }

    @Override
    public void afterTick(HeadlessMovement movement) { sync(movement); }

    String status() {
        return "pathing=" + host.getPathingBehavior().status() + " mining=" + miningStatus
                + " destroy(start/stop/abort)=" + controller.startPackets() + "/" + controller.stopPackets()
                + "/" + controller.abortPackets() + " confirmed=" + controller.lastBreakAcked()
                + " place=" + placeStatus + " throwaway=" + host.hasGenericThrowaway()
                + " placePackets=" + controller.placePackets()
                + " placeAcked=" + controller.lastPlaceAcked()
                + " placeFailed=" + controller.lastPlaceFailed();
    }

    void close() {
        cancel();
        host.getPathingBehavior().close();
    }
}
