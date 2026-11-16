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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// Ostinato: tick-owned bridge between the protocol body, path executor and PlayerSim intents.
final class HeadlessLiveDriver implements HeadlessMovement.Driver {
    private static final boolean BOT_TRACE = Boolean.getBoolean("ostinato.botTrace");
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
    // Ostinato: last-sent body, set in sync() before tickUse. Place uses this AABB.
    private HeadlessMovement movement;
    // Ostinato: one UseItemOn per dest until the prediction is rejected or the movement advances.
    private BlockPos sentPlaceDest;
    // Ostinato: the click is chosen before physics and sent after it, so UseItemOn
    // and the position packet share the post-sim pose (the vanilla client order).
    private BlockHitResult armedPlace;


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
        // Ostinato parity: the same Baritone settings the real 26.3 client bot runs with, i.e. Ostinato defaults
        // plus TenorClef's AltoClef.initializeBaritoneSettings (TenorClef-swarm-beta @ 5c18e626). No parkour.
        applyClientBotSettings();
        if (BOT_TRACE) Baritone.settings().kinematicTrace.value = true; // Ostinato parity diagnostics only
        host = new HeadlessBaritone(context, new HeadlessBlockStateInterface(bot.chunks()), input, look);
        combat = new HeadlessCombat(bot, player, controller, host, bot.entities());
        craft = new HeadlessCraft(bot, player, controller, host, this);
    }

    /**
     * Ostinato parity: mirror of TenorClef AltoClef.initializeBaritoneSettings on the real client bot, so planning
     * and execution choices start from the same settings. TenorClef-only hooks (end portal walking, bucket
     * placement) live in its own mixins and have no setting here.
     */
    static void applyClientBotSettings() {
        var s = Baritone.settings();
        s.freeLook.value = false;
        s.overshootTraverse.value = false;
        s.allowOvershootDiagonalDescend.value = true;
        s.allowInventory.value = true;
        s.allowParkour.value = false;
        s.sprintJump.value = false;
        s.kinematicTravel.value = !"false".equals(System.getProperty("tenorclef.kinematic"));
        s.allowParkourAscend.value = false;
        s.allowParkourPlace.value = false;
        s.allowDiagonalDescend.value = false;
        s.costHeuristic.value = 4.5;
        s.allowDiagonalAscend.value = false;
        s.blocksToAvoid.value = new java.util.LinkedList<>(java.util.List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN));
        s.blocksToAvoidBreaking.value.add(Blocks.NETHER_PORTAL);
        s.blocksToDisallowBreaking.value.add(Blocks.NETHER_PORTAL);
        s.fadePath.value = true;
        s.mineScanDroppedItems.value = false;
        s.mineDropLoiterDurationMSThanksLouca.value = 0L;
        s.randomLooking.value = 0.0;
        s.randomLooking113.value = 0.0;
        s.failureTimeoutMS.reset();
        s.planAheadFailureTimeoutMS.reset();
        s.movementTimeoutTicks.reset();
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

    // Ostinato: stand on the block itself. Used for item pickup, where GoalGetToBlock
    // succeeds from an adjacent tile that is still outside vanilla pickup range.
    void requestStandOn(BlockPos pos) {
        if (host.getPathingBehavior().isPathing() || host.getPathingBehavior().planning()) {
            return;
        }
        host.getPathingBehavior().gotoGoal(new GoalBlock(pos.getX(), pos.getY(), pos.getZ()));
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
        sentPlaceDest = null;
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
        sentPlaceDest = null;
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
            boolean sprintBefore = player.isSprinting();
            host.getPathingBehavior().tick();
            if (BOT_TRACE) {
                // Ostinato parity diagnostics (-Dostinato.botTrace=true): what the executor did this tick.
                var cur = host.getPathingBehavior().getCurrent();
                String pos = "-", mv = "-", drv = "-";
                if (cur instanceof PathExecutor pe) {
                    pos = String.valueOf(pe.getPosition());
                    int i = pe.getPosition();
                    mv = i < pe.getPath().movements().size() ? pe.getPath().movements().get(i).getClass().getSimpleName() : "end";
                    drv = String.valueOf(pe.getLastDriver());
                }
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[BotTrace] sim=%.6f,%.6f,%.6f pathPos=%s move=%s driver=%s sprintBefore=%b sprintAfterExec=%b simSprint=%b fwd=%b sprintKey=%b jump=%b",
                        movement.sim().x, movement.sim().y, movement.sim().z, pos, mv, drv, sprintBefore, player.isSprinting(),
                        movement.sim().sprinting, down(Input.MOVE_FORWARD), down(Input.SPRINT), down(Input.JUMP)));
            }
            // Ostinato parity: PathExecutor stops sprint with player.setSprinting(false) before a movement that must
            // not sprint (LocalPlayer.setSprinting on the real client; aiStep then travels without sprint and
            // sendPosition sends STOP_SPRINTING). sync() only copies sim -> player, so carry that stop back to the sim.
            // Without it the bot sprint-jumped (+0.2 boost) into a ladder and NCP kicked it (notes section 25).
            if (!player.isSprinting() && movement.sim().sprinting) {
                movement.sim().sprinting = false;
            }
            // Ostinato: vanilla/Baritone apply look at tick HEAD, place in handleKeybinds
            // against that look (already on the wire from last tick's sendChanges, or the
            // pre-place Rot), then may update look again before PosRot. Pathing.tick()
            // writes a new look immediately; placing against it in the same tick is
            // Grim RotationPlace (UseItemOn before the rotation packet). Snapshot the
            // desired look, restore last-sent look for attack/place, then re-apply.
            Rotation desiredLook = new Rotation(player.getYRot(), player.getXRot());
            boolean restoredSentLook = false;
            if (!movement.lookMatchesLastSent(desiredLook.getYaw(), desiredLook.getPitch())
                    && !Float.isNaN(movement.lastSentYaw())) {
                look.updateTarget(new Rotation(movement.lastSentYaw(), movement.lastSentPitch()), true);
                restoredSentLook = true;
            }
            if (mining != null) tickMining();
            else if (placing != null) tickPlaceCommand();
            else if (combat.active() || craft.active()) {
                // Look is already on the player; do not left-click blocks during combat or container use.
            } else {
                tickAttack();
                tickUse();
            }
            // Ostinato: a place aim taken this tick must go out on this tick's
            // movement packet. Step used to keep the pre-tickUse look, so the
            // rotation never landed and canSendPlace waited forever.
            if (pendingPlaceLook != null) {
                desiredLook = pendingPlaceLook;
                look.updateTarget(pendingPlaceLook, true);
            } else if (placing != null) {
                desiredLook = new Rotation(player.getYRot(), player.getXRot());
            } else if (restoredSentLook) {
                look.updateTarget(desiredLook, true);
            }
            holdSneakIfHanging(movement);
            String why = placing != null ? "place" : craft.active() ? "craft" : combat.active() ? "combat" : "pathing";
            return new HeadlessMovement.Step(1, desiredLook.getYaw(), desiredLook.getPitch(),
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
        releaseSentPlaceIfLayerAdvanced(want);
        if (pendingPlaceLook != null && pendingPlaceWant != null) {
            look.updateTarget(pendingPlaceLook, true);
            HitResult pendingHit = context.objectMouseOver();
            Set<BlockPos> pendingWant = pendingPlaceWant;
            if (placesInto(pendingHit, pendingWant) && pendingHit instanceof BlockHitResult block
                    && pendingHit.getType() == HitResult.Type.BLOCK) {
                // Ostinato: keep the pending look until flush sends. The apex check
                // runs after physics, on the pose the position packet will carry.
                armPlace(block);
                return;
            }
            pendingPlaceLook = null;
            pendingPlaceWant = null;
        }
        HitResult hit = context.objectMouseOver();
        if (!want.isEmpty()) {
            if (placesInto(hit, want) && hit instanceof BlockHitResult block
                    && hit.getType() == HitResult.Type.BLOCK) {
                armPlace(block);
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
        // Ostinato: MovementPillar holds CLICK_RIGHT for the whole jump after the
        // block exists (or after toPlace becomes empty). The real client clicks
        // once per layer, at the jump apex (about 0.249 above the dest top, the
        // *.7508 tick). Clicking at the first tick the box clears (about 0.001
        // above the top) is two ticks early and Grim reports Simulation
        // 0.075444 then 0.076891. A bare CLICK_RIGHT here would also spam
        // UseItemOn every tick on the top of the block we just placed.
        if (!down(Input.CLICK_RIGHT)) {
            return;
        }
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (placing == null && want.isEmpty()) {
            return;
        }
        if (placing == null && !placesInto(block, want)) {
            return;
        }
        armPlace(block);
    }

    private void armPlace(BlockHitResult block) {
        armedPlace = block;
    }

    /** Send the armed click against the post-sim body, before the position packet. */
    @Override
    public void flushPlaceAfterPhysics() {
        BlockHitResult block = armedPlace;
        armedPlace = null;
        if (block == null || !canSendPlace(block)) {
            return;
        }
        pendingPlaceLook = null;
        pendingPlaceWant = null;
        clickPlace(block);
    }

    private void clickPlace(BlockHitResult block) {
        if (!canSendPlace(block)) {
            return;
        }
        BlockPos dest = placeDest(block);
        logPlaceGate("send UseItemOn", dest);
        if (controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, block) == InteractionResult.SUCCESS) {
            markSentPlace(dest);
            return;
        }
        if (controller.processRightClickBlock(player, level, InteractionHand.OFF_HAND, block) == InteractionResult.SUCCESS) {
            markSentPlace(dest);
        }
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
        if (!canSendPlace(at)) {
            placeStatus = "waiting " + target.toShortString();
            return;
        }
        logPlaceGate("send UseItemOn", target);
        InteractionResult result = controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, at);
        if (result != InteractionResult.SUCCESS) {
            controller.processRightClickBlock(player, level, InteractionHand.OFF_HAND, at);
        }
        markSentPlace(target);
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
            // Ostinato: only the current movement's toPlace. PathExecutor.toPlace() is
            // every remaining cell, so a later pillar block is clicked as soon as the
            // ray hits the block we just placed (mid-first-jump, often with the wrong
            // look). The real client only right-clicks the current MovementPillar.
            // That early UseItemOn is Grim RotationPlace / Simulation 0.075.
            int i = path.getPosition();
            var moves = path.getPath().movements();
            if (i >= 0 && i < moves.size()) {
                var movement = (baritone.pathing.movement.Movement) moves.get(i);
                return Set.copyOf(movement.toPlace(host.bsi()));
            }
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
        boolean hanging = sim.onGround && hanging(sim);
        // Ostinato parity: an A* wait alone is no reason to crouch. The real client bot never sneaks while it plans on
        // firm ground; the bot's sneak pulse before every command left NoCheatPlus applying a sneak speed limit to the
        // sprint start that followed (setback, then a setback loop on the ladder; notes section 27). Only a body that
        // actually hangs over an edge keeps sneak through the wait.
        if (!intendedPlaces().isEmpty() || host.getPathingBehavior().planning() && hanging) {
            bridgeHold = true;
        }
        if (!bridgeHold) {
            return;
        }
        if (!hanging && intendedPlaces().isEmpty()) {
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

    boolean absorbServerBlockUpdate(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return controller.absorbServerBlockUpdate(pos, state);
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
            // Ostinato: the real client sends no PlayerAction during a 5-block pillar.
            // MovementPillar can set CLICK_LEFT on a just-placed throwaway at src, and
            // tickAttack would START/STOP destroy (Grim FastBreak). Only dig cells the
            // path actually listed as toBreak.
            if (ignorePathingBreak(block.getBlockPos())) {
                controller.resetBlockRemoving();
                return;
            }
            controller.onPlayerDamageBlock(block.getBlockPos(), block.getDirection());
        } else {
            controller.resetBlockRemoving();
        }
    }

    /** True when this left-click is not aimed at a path toBreak cell. */
    private boolean ignorePathingBreak(BlockPos pos) {
        var exec = host.getPathingBehavior().getCurrent();
        if (!(exec instanceof PathExecutor path)) {
            return false;
        }
        return !wantHas(path.toBreak(), pos);
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
        this.movement = movement;
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

    /**
     * Ostinato: Paper rejects a place whose block would intersect the player's
     * bounding box, and Grim RotationPlace / Simulation can cancel a place whose
     * look is not already on the wire. Beside-the-feet places, and a place made
     * while still on the ground, send once the box clears the cell. An airborne
     * pillar cell under the body waits for the jump apex
     * (feet about 0.249 above the dest top, the client's *.7508 tick). Sending
     * at the first clear tick (about 0.001 above) is what Grim reports as
     * Simulation 0.075444 then 0.076891. The click is flushed after travel,
     * so this body is the pose the position packet will carry.
     */
    private boolean canSendPlace(BlockHitResult hit) {
        BlockPos dest = placeDest(hit);
        if (dest == null) {
            return false;
        }
        if (movement == null || !movement.lookMatchesLastSent(player.getYRot(), player.getXRot())) {
            logPlaceGate("hold look-not-on-wire", dest);
            return false;
        }
        AABB feet = player.getBoundingBox();
        AABB cell = new AABB(dest);
        if (feet.intersects(cell)) {
            logPlaceGate("hold aabb-intersect", dest);
            return false;
        }
        if (!pillarApexReady(dest, feet)) {
            logPlaceGate("hold pillar-apex", dest);
            return false;
        }
        if (sentPlaceDest != null && sentPlaceDest.getX() == dest.getX()
                && sentPlaceDest.getY() == dest.getY() && sentPlaceDest.getZ() == dest.getZ()
                && !controller.placeRejected(dest)) {
            return false;
        }
        return true;
    }

    /**
     * Ostinato: an airborne dest in the column under the body is a pillar. The
     * client clicks when the feet are about 0.249 above that cell's top and
     * vertical velocity is still near zero (the apex), not on the first tick
     * the box clears the cell and not on the next tick when vy is about
     * -0.075444. A sneak bridge also occupies that column once the body edges
     * over the cell, but the feet stay on the ground at the dest top, so the
     * apex window must not apply while onGround. The first pillar layer is
     * the same: the client clicks it on the ground.
     */
    private boolean pillarApexReady(BlockPos dest, AABB feet) {
        if (player.onGround()) {
            return true;
        }
        if (dest.getX() != Mth.floor(player.getX()) || dest.getZ() != Mth.floor(player.getZ())) {
            return true;
        }
        if (dest.getY() >= Mth.floor(feet.minY)) {
            return true;
        }
        double above = feet.minY - (dest.getY() + 1.0);
        double vy = player.getDeltaMovement().y;
        return above >= 0.20 && above <= 0.30 && vy > -0.02 && vy < 0.05;
    }

    private BlockPos placeDest(BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        if (!level.isLoaded(clicked)) {
            return null;
        }
        BlockState clickedState = level.getBlockState(clicked);
        return clickedState.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
    }

    private void markSentPlace(BlockPos dest) {
        if (dest == null) {
            return;
        }
        if (controller.awaitingPlace() || controller.placeConfirmed(dest)
                || (level.isLoaded(dest) && !level.getBlockState(dest).canBeReplaced())) {
            sentPlaceDest = dest.immutable();
        }
    }

    private void releaseSentPlaceIfLayerAdvanced(Set<BlockPos> want) {
        if (sentPlaceDest == null) {
            return;
        }
        if (controller.placeRejected(sentPlaceDest)) {
            sentPlaceDest = null;
            return;
        }
        if (!want.isEmpty() && !wantHas(want, sentPlaceDest)) {
            sentPlaceDest = null;
        }
    }

    private void logPlaceGate(String reason, BlockPos dest) {
        if (dest == null) {
            return;
        }
        AABB feet = player.getBoundingBox();
        AABB cell = new AABB(dest);
        String last = (movement == null || Double.isNaN(movement.lastSentY()))
                ? "n/a"
                : String.format(Locale.ROOT, "x=%.6f y=%.6f z=%.6f",
                        movement.lastSentX(), movement.lastSentY(), movement.lastSentZ());
        boolean lookOnWire = movement != null
                && movement.lookMatchesLastSent(player.getYRot(), player.getXRot());
        String line = String.format(Locale.ROOT,
                "%s dest=%s y=%.6f aabb=[%.4f,%.4f,%.4f -> %.4f,%.4f,%.4f] cell=[%.1f,%.1f,%.1f -> %.1f,%.1f,%.1f] intersects=%s lastSent=%s lookOnWire=%s",
                reason, dest.toShortString(), player.getY(),
                feet.minX, feet.minY, feet.minZ, feet.maxX, feet.maxY, feet.maxZ,
                cell.minX, cell.minY, cell.minZ, cell.maxX, cell.maxY, cell.maxZ,
                feet.intersects(cell), last, lookOnWire);
        System.out.println("[HeadlessPlace] " + line);
        baritone.utils.PacketLog.note(line);
    }

    void close() {
        cancel();
        host.getPathingBehavior().close();
        movement = null;
        sentPlaceDest = null;
    }
}
