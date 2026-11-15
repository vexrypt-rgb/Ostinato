package baritone.headless;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.PlayerSim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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

    HeadlessLiveDriver(HeadlessBot bot, HeadlessPlayer player, HeadlessLevel level) {
        this.player = player;
        this.level = level;
        controller = new HeadlessPlayerController(bot, player, level);
        context = new HeadlessPlayerContext(player, level, controller);
        look = new HeadlessLookBehavior(player);
        // Ostinato: no inventory or placement implementation; forbid all placement-dependent planning.
        Baritone.settings().allowPlace.value = false;
        Baritone.settings().allowParkourPlace.value = false;
        Baritone.settings().allowGodBridge.value = false;
        Baritone.settings().unifiedExecutor.value = false;
        host = new HeadlessBaritone(context, new HeadlessBlockStateInterface(bot.chunks()), input, look);
    }

    /** True while a goal is queued or a mine is waiting on a break. The course must not advance. */
    boolean controlsMovement() {
        return host.getPathingBehavior().isPathing() || mining != null;
    }

    void gotoBlock(int x, int y, int z) {
        cancel();
        host.getPathingBehavior().gotoGoal(new GoalBlock(x, y, z));
    }

    void cancel() {
        mining = null;
        miningStatus = "idle";
        host.getPathingBehavior().cancelEverything();
        host.getPathingBehavior().noteCancelled();
        controller.resetBlockRemoving();
        input.clearAllKeys();
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
        boolean owns = host.getPathingBehavior().isPathing() || mining != null;
        if (!owns) return null;
        try {
            host.getPathingBehavior().tick();
            if (mining != null) tickMining();
            else tickAttack();
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(),
                    key(Input.MOVE_FORWARD) - key(Input.MOVE_BACK), key(Input.MOVE_LEFT) - key(Input.MOVE_RIGHT),
                    down(Input.SPRINT), down(Input.JUMP), down(Input.SNEAK), "pathing");
        } catch (RuntimeException e) {
            cancel();
            System.err.println("[HeadlessPathing] cancelled after executor error: " + e);
            e.printStackTrace(System.err);
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(), 0, 0, false, false, false, "path error");
        }
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
                + "/" + controller.abortPackets() + " confirmed=" + controller.lastBreakAcked();
    }

    void close() {
        cancel();
        host.getPathingBehavior().close();
    }
}
