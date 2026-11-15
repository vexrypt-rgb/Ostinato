package baritone.headless;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.path.PathExecutor;
import net.minecraft.util.Mth;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

// Ostinato: tick-owned bridge between the protocol body, path executor and PlayerSim intents.
final class HeadlessLiveDriver implements HeadlessMovement.Driver {
    private final HeadlessPlayer player;
    private final HeadlessLevel level;
    private final HeadlessPlayerController controller;
    private final HeadlessPlayerContext context;
    private final HeadlessInputOverrideHandler input = new HeadlessInputOverrideHandler();
    private final HeadlessLookBehavior look;
    private final HeadlessBaritone host;
    private final HeadlessCombat combat;
    private final HeadlessCraft craft;
    private volatile BlockPos mining;
    private int miningTicks;
    private volatile String miningStatus = "idle";
    private volatile BlockPos placing;
    private int placeWait;
    private int placeAttempts;
    private boolean placeAimed;
    private volatile String placeStatus = "idle";
    /** When true, tickPlaceCommand keeps the already selected hotbar block instead of a throwaway. */
    private boolean placeHeldItem;
    // Ostinato: stay crouched across a partial-path A* wait and while hanging off a
    // placed 1-wide block. Clearing sneak on SUCCESS drops the bot into the gap.
    private boolean bridgeHold;
    // Ostinato: look packet is written after tickUse. Remember a re-aim so the next
    // tick can click it; Movement.update() would otherwise overwrite the look first
    // and we would re-aim forever without sending UseItemOn.
    private Rotation pendingPlaceLook;
    private Set<BlockPos> pendingPlaceWant;


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
        combat = new HeadlessCombat(bot, player, controller, host, bot.entities());
        craft = new HeadlessCraft(bot, player, controller, host, this);
    }

    /** True while a goal is queued, a mine is waiting on a break, or a place command is in progress. */
    boolean controlsMovement() {
        return host.getPathingBehavior().isPathing() || mining != null || placing != null
                || combat.active() || craft.active();
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

    HeadlessPlayerContext context() {
        return context;
    }

    HeadlessLookBehavior look() {
        return look;
    }

    void gotoBlock(int x, int y, int z) {
        cancel();
        host.getPathingBehavior().gotoGoal(new GoalBlock(x, y, z));
    }

    // Ostinato: fight matching monsters (or a type like "zombie") until none remain.
    void kill(String typeFilter) {
        cancel();
        combat.start(typeFilter);
    }

    void container(HeadlessCommands.Parsed parsed) {
        switch (parsed.kind()) {
            case CRAFT -> {
                var item = HeadlessRecipes.resolve(parsed.text());
                if (item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startCraft(item, parsed.x());
            }
            case SMELT -> {
                var item = HeadlessRecipes.resolve(parsed.text());
                if (item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startSmelt(item, Items.COAL, parsed.x());
            }
            case CHEST -> {
                var item = HeadlessRecipes.resolve(parsed.text());
                if (item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startChest(parsed.y() == 0, item, parsed.x());
            }
            case DROP -> {
                var item = HeadlessRecipes.resolve(parsed.text());
                if (item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startDrop(item, parsed.x());
            }
            case PICKUP -> {
                var item = parsed.text().isEmpty() ? null : HeadlessRecipes.resolve(parsed.text());
                if (!parsed.text().isEmpty() && item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startPickup(item);
            }
            case EQUIP -> {
                var item = parsed.text().isEmpty() ? null : HeadlessRecipes.resolve(parsed.text());
                if (!parsed.text().isEmpty() && item == null) {
                    throw new IllegalArgumentException("unknown item " + parsed.text());
                }
                cancelMovement();
                craft.startEquip(item);
            }
            default -> throw new IllegalArgumentException("not a container command: " + parsed.kind());
        }
        System.out.println("[HeadlessCraft] " + parsed.kind().name().toLowerCase() + " " + parsed.text());
    }

    void requestGoto(BlockPos pos) {
        host.getPathingBehavior().gotoGoal(new GoalGetToBlock(pos));
    }

    void requestPlace(BlockPos pos) {
        mining = null;
        miningStatus = "idle";
        placing = pos;
        placeWait = 0;
        placeAttempts = 0;
        placeAimed = false;
        placeHeldItem = true;
        placeStatus = "placing " + placing;
    }

    boolean isPlacing() {
        return placing != null;
    }

    void cancel() {
        craft.cancel();
        combat.cancel();
        mining = null;
        miningStatus = "idle";
        placing = null;
        placeStatus = "idle";
        placeAimed = false;
        placeAttempts = 0;
        placeHeldItem = false;
        pendingPlaceLook = null;
        pendingPlaceWant = null;
        host.getPathingBehavior().cancelEverything();
        host.getPathingBehavior().noteCancelled();
        controller.resetBlockRemoving();
        controller.abandonPlace();
        input.clearAllKeys();
    }

    /** Stop pathing, mining, placing and combat, but leave a running craft/smelt/chest task. */
    private void cancelMovement() {
        combat.cancel();
        mining = null;
        miningStatus = "idle";
        placing = null;
        placeStatus = "idle";
        placeAimed = false;
        placeAttempts = 0;
        placeHeldItem = false;
        pendingPlaceLook = null;
        pendingPlaceWant = null;
        host.getPathingBehavior().cancelEverything();
        host.getPathingBehavior().noteCancelled();
        controller.resetBlockRemoving();
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
        // Ostinato: Player is never ticked, so the vanilla attack-strength ticker must advance here.
        player.tickAttackStrength();
        if (combat.active()) {
            combat.tick();
        }
        if (craft.active()) {
            craft.tick();
        }
        boolean owns = host.getPathingBehavior().isPathing() || mining != null || placing != null
                || combat.active() || craft.active();
        if (!owns) return null;
        try {
            host.getPathingBehavior().tick();
            if (mining != null) tickMining();
            else if (placing != null) tickPlaceCommand();
            else if (combat.active() || craft.active()) {
                // Look is already on the player; do not left-click blocks during combat or container use.
            } else {
                tickAttack();
                tickUse();
            }
            holdSneakIfHanging(movement);
            String why = placing != null ? "place" : craft.active() ? "craft" : combat.active() ? "combat" : "pathing";
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(),
                    key(Input.MOVE_FORWARD) - key(Input.MOVE_BACK), key(Input.MOVE_LEFT) - key(Input.MOVE_RIGHT),
                    down(Input.SPRINT), down(Input.JUMP), down(Input.SNEAK), why);
        } catch (RuntimeException e) {
            cancel();
            System.err.println("[HeadlessPathing] cancelled after executor error: " + e);
            e.printStackTrace(System.err);
            return new HeadlessMovement.Step(1, player.getYRot(), player.getXRot(), 0, 0, false, false, false, "path error");
        }
    }

    /**
     * Ostinato: path-executor right click. Aim at the intended toPlace support face and
     * only click when the raytrace actually hits it. A nearer wall must never win just
     * because it is first on the crosshair. Left click wins, matching the client place helper.
     *
     * Ostinato: Movement Traverse ATTEMPTING can leave the look on the air cell
     * (almost straight down) without ever setting CLICK_RIGHT. When toPlace is
     * non-empty, aim the nearest support face that actually exists and click on
     * the following tick, after the look packet has gone out. Re-apply that look
     * before the click so this tick's movement update cannot wipe it.
     */
    private void tickUse() {
        if (down(Input.CLICK_LEFT)) {
            pendingPlaceLook = null;
            pendingPlaceWant = null;
            return;
        }
        Set<BlockPos> want = intendedPlaces();
        if (pendingPlaceLook != null && pendingPlaceWant != null) {
            look.updateTarget(pendingPlaceLook, true);
            HitResult pendingHit = context.objectMouseOver();
            Set<BlockPos> pendingWant = pendingPlaceWant;
            pendingPlaceLook = null;
            pendingPlaceWant = null;
            if (placesInto(pendingHit, pendingWant) && pendingHit instanceof BlockHitResult block
                    && pendingHit.getType() == HitResult.Type.BLOCK) {
                clickPlace(block);
                return;
            }
        }
        HitResult hit = context.objectMouseOver();
        if (!want.isEmpty()) {
            if (placesInto(hit, want) && hit instanceof BlockHitResult block
                    && hit.getType() == HitResult.Type.BLOCK) {
                clickPlace(block);
                return;
            }
            Optional<Rotation> aim = aimAtPlace(want);
            if (aim.isEmpty()) {
                return;
            }
            look.updateTarget(aim.get(), true);
            pendingPlaceLook = aim.get();
            pendingPlaceWant = Set.copyOf(want);
            return;
        }
        if (!down(Input.CLICK_RIGHT)) {
            return;
        }
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        clickPlace(block);
    }

    private void clickPlace(BlockHitResult block) {
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
        if (placeHeldItem) {
            if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
                finishPlace("no held block " + target.toShortString());
                return;
            }
        } else if (!host.selectThrowawayForLocation(true, target.getX(), target.getY(), target.getZ())) {
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
        // Ostinato: click only the raytrace that actually hits this support face. A wall
        // closer along the look must not be clicked, and we do not invent a hit the ray missed.
        HitResult traced = context.objectMouseOver();
        if (!placesInto(traced, target)) {
            placeStatus = "aiming " + target.toShortString();
            return;
        }
        BlockHitResult at = (BlockHitResult) traced;
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

    // Ostinato: cells the path executor (or the operator place command) wants this click to fill.
    Set<BlockPos> intendedPlaces() {
        if (placing != null) {
            return Set.of(placing);
        }
        var exec = host.getPathingBehavior().getCurrent();
        if (exec instanceof PathExecutor path) {
            return path.toPlace();
        }
        return Set.of();
    }

    /** True when this hit would place into one of the intended cells, not merely whatever the crosshair sees first. */
    boolean placesInto(HitResult hit, BlockPos want) {
        return placesInto(hit, Set.of(want));
    }

    boolean placesInto(HitResult hit, Set<BlockPos> want) {
        if (want.isEmpty() || !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = block.getBlockPos();
        // Ostinato: PathExecutor.toPlace is a HashSet of BetterBlockPos, whose hashCode
        // is not BlockPos.hashCode. Set.contains(pos.relative(...)) therefore misses
        // the intended cell even when the ray is already on the support face.
        if (wantHas(want, pos.relative(block.getDirection()))) {
            return true;
        }
        return wantHas(want, pos) && level.getBlockState(pos).canBeReplaced();
    }

    private static boolean wantHas(Set<BlockPos> want, BlockPos pos) {
        for (BlockPos w : want) {
            if (w.getX() == pos.getX() && w.getY() == pos.getY() && w.getZ() == pos.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ostinato: rotation onto a ray-traceable support face that places into {@code want}.
     * Prefers the face whose yaw is closest to the current look so a 1-wide bridge stays
     * on the previous block instead of snapping at a side wall.
     */
    Optional<Rotation> aimAtPlace(Set<BlockPos> want) {
        boolean sneaking = player.isShiftKeyDown();
        double reach = controller.getBlockReachDistance();
        Vec3 eye = sneaking ? RayTraceUtils.inferSneakingEyePosition(player) : player.getEyePosition(1.0F);
        Rotation current = new Rotation(player.getYRot(), player.getXRot());
        Rotation best = null;
        double bestDestDist = Double.MAX_VALUE;
        double bestYaw = Double.MAX_VALUE;
        // Ostinato: only a dest with a real support in the current world, and the
        // closest such dest first. Future toPlace cells over air have no support yet;
        // aiming at them looks nearly straight down and never becomes a click.
        for (BlockPos dest : want) {
            BlockState cell = level.getBlockState(dest);
            if (!cell.isAir() && !cell.canBeReplaced()) {
                continue;
            }
            double destDist = player.position().distanceToSqr(Vec3.atCenterOf(dest));
            for (Direction towardSupport : SUPPORT_ORDER) {
                BlockPos support = dest.relative(towardSupport);
                if (!level.isLoaded(support)) {
                    continue;
                }
                Direction face = towardSupport.getOpposite();
                BlockState state = level.getBlockState(support);
                if (!state.isFaceSturdy(level, support, face)) {
                    continue;
                }
                // Ostinato: the geometric face center is often hidden under the top of the
                // support (the player stands on it). Try the center, then the closest visible
                // point on the face, so a sneak-bridge hang can still see the vertical face.
                for (Vec3 at : facePoints(support, face, eye)) {
                    if (eye.distanceToSqr(at) > reach * reach) {
                        continue;
                    }
                    Rotation rot = RotationUtils.calcRotationFromVec3d(eye, at, current);
                    HitResult traced = RayTraceUtils.rayTraceTowards(player, rot, reach, sneaking);
                    if (!placesInto(traced, dest)) {
                        continue;
                    }
                    double err = Math.abs(Mth.wrapDegrees(rot.getYaw() - current.getYaw()));
                    if (destDist < bestDestDist - 1.0E-6
                            || (Math.abs(destDist - bestDestDist) < 1.0E-6 && err < bestYaw)) {
                        bestDestDist = destDist;
                        bestYaw = err;
                        best = rot;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Ostinato: a partial sneak-bridge path ends by clearing keys, then A* runs for the rest.
     * Releasing sneak while the box still hangs over the next air cell walks off the 1-wide
     * line (the last live gate drifted after three correct places). Hold sneak until the
     * player is fully supported again, or a jump is requested.
     */
    private void holdSneakIfHanging(HeadlessMovement movement) {
        if (down(Input.JUMP)) {
            bridgeHold = false;
            return;
        }
        PlayerSim sim = movement.sim();
        if (!intendedPlaces().isEmpty() || host.getPathingBehavior().planning()) {
            bridgeHold = true;
        }
        if (!bridgeHold) {
            return;
        }
        boolean hanging = sim.onGround && hanging(sim);
        if (!hanging && intendedPlaces().isEmpty() && !host.getPathingBehavior().planning()) {
            bridgeHold = false;
            return;
        }
        input.setInputForceState(Input.SNEAK, true);
    }

    private static boolean hanging(PlayerSim sim) {
        double step = 0.05;
        return sim.wouldWalkOff(step, 0.0) || sim.wouldWalkOff(-step, 0.0)
                || sim.wouldWalkOff(0.0, step) || sim.wouldWalkOff(0.0, -step);
    }

    private static Vec3[] facePoints(BlockPos support, Direction face, Vec3 eye) {
        double cx = support.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = support.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = support.getZ() + 0.5 + face.getStepZ() * 0.5;
        // Closest point on the face, inset so clip classifies the face not an adjacent one.
        double ix = face.getAxis() == Direction.Axis.X ? cx : Math.max(support.getX() + 0.05, Math.min(support.getX() + 0.95, eye.x));
        double iy = face.getAxis() == Direction.Axis.Y ? cy : Math.max(support.getY() + 0.05, Math.min(support.getY() + 0.95, eye.y));
        double iz = face.getAxis() == Direction.Axis.Z ? cz : Math.max(support.getZ() + 0.05, Math.min(support.getZ() + 0.95, eye.z));
        return new Vec3[]{new Vec3(cx, cy, cz), new Vec3(ix, iy, iz)};
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

    @Override
    public void afterPackets(HeadlessBot bot) {
        combat.flushAttack();
    }

    String status() {
        return "pathing=" + host.getPathingBehavior().status() + " mining=" + miningStatus
                + " destroy(start/stop/abort)=" + controller.startPackets() + "/" + controller.stopPackets()
                + "/" + controller.abortPackets() + " confirmed=" + controller.lastBreakAcked()
                + " place=" + placeStatus + " throwaway=" + host.hasGenericThrowaway()
                + " placePackets=" + controller.placePackets()
                + " placeAcked=" + controller.lastPlaceAcked()
                + " placeFailed=" + controller.lastPlaceFailed()
                + " combat=" + combat.status() + " combatHits=" + combat.hits()
                + " craft=" + craft.status();
    }

    void close() {
        cancel();
        host.getPathingBehavior().close();
    }
}
