package baritone.process;

import baritone.bastion.OutsideTrade;
import baritone.bastion.EndermanHunt;
import net.minecraft.world.entity.monster.Enderman;
import baritone.bastion.DamageBudget;
import baritone.bastion.BruteTactics;
import baritone.bastion.LavaFlow;
import baritone.bastion.BastionScript;
import baritone.bastion.BastionPriors;
import baritone.bastion.BastionRouteResolver;
import baritone.bastion.BastionRoutes;
import baritone.bastion.ThrowAdapt;
import baritone.Baritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.PathingCommand;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import baritone.structure.DetectedStructure;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.bastion.BastionDrops;
import baritone.bastion.BastionGoals;
import baritone.bastion.BastionLava;
import baritone.bastion.EdgeCost;
import baritone.bastion.ItemThrow;
import baritone.bastion.FluidPocket;
import baritone.bastion.LavaFlow;
import baritone.bastion.BastionTrap;
import baritone.api.pathing.goals.GoalBlock;
import baritone.bastion.BastionPlan;
import baritone.bastion.BastionSettings;
import baritone.bastion.Shelter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Walks to a bastion and barters with its piglins. Piglins ignore a player in gold armour but brutes, hoglins and
 * zoglins never do, so those are handed to {@link PveProcess} the moment they are near (stables clear hoglins early,
 * the other layouts only once something is hunting us). Trading throws one ingot at a calm piglin from close range,
 * waits out its admiring time and walks over the loot.
 */
public final class BastionProcess extends BaritoneProcessHelper {
    private static final double THREAT_RANGE = 14, SEEN_RANGE = 30, THROW_RANGE = 3.2;
    private static final int ADMIRE_TICKS = 170;

    private boolean active;
    private String query = "bastion_remnant";
    private DetectedStructure bastion;
    private final CombatAim aimer;
    private final Random rng = new Random(5);
    private boolean fighting;
    private final List<UUID> traded = new ArrayList<>();
    private UUID throwAt;
    private long throwTick = -1000, ticks;
    public int throwsDone, lootPicked;
    private boolean throwJump;
    private int jumpTries;
    private BlockPos throwTarget;
    private final ThrowAdapt throwAdapt = new ThrowAdapt();
    private int lastLootId = -1;
    private String status = "-";
    /** Breaking a gold block angers every idle piglin within 16 blocks of the miner, seen or not; stay well clear. */
    private static final double ANGER_RANGE = 22;
    private long lastScan = -1000;
    private List<BlockPos> goldBlocks = new ArrayList<>();
    private int craftStep, craftFrom = -1;
    public int goldMined;

    public BastionProcess(Baritone baritone) {
        super(baritone);
        aimer = new CombatAim(baritone, ctx, rng);
    }

    /** {@code variant} is a layout (housing, stables, treasure, bridge) or empty for any bastion. */
    public void start(String variant) {
        EdgeCost.enabled = true;
        // breaking one sets every piglin within sixteen blocks on us, seen or not, and a bastion's walls and floors
        // are salted with it
        List<net.minecraft.world.level.block.Block> keep = BaritoneAPI.getSettings().blocksToDisallowBreaking.value;
        if (!keep.contains(Blocks.GILDED_BLACKSTONE)) { keep.add(Blocks.GILDED_BLACKSTONE); guardedAdded = true; }
        relaxUntil = 0;
        towerReleaseUntil = 0;
        wallFailed.clear();
        wallCell = null;
        query = variant == null || variant.isEmpty() ? "bastion_remnant" : variant;
        active = true;
        // walk around netherrack rather than dig through it: digging is slow and a speedrun has no time for it
        if (savedBreakPenalty == null) savedBreakPenalty = BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value;
        BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = 40D;
        if (savedFall == null) {
            savedFall = BaritoneAPI.getSettings().maxFallHeightNoWater.value;
            savedBucketFall = BaritoneAPI.getSettings().allowWaterBucketFall.value;
        }
        // runs 119, 120: the kinematic driver sprinted (and jumped) through Descend/Fall steps of a winding way down and
        // came off two blocks wide of the path, 20 -> 13 hp each time. Over lava that is the run; Baritone's own driver
        // walks those steps. (experimentalMovement turns the driver on whatever this says.)
        if (savedKinematic == null) savedKinematic = BaritoneAPI.getSettings().kinematicTravel.value;
        BaritoneAPI.getSettings().kinematicTravel.value = savedKinematic && BastionSettings.kinematic;
        // water evaporates in the nether: a planned bucket fall there is a fall with no bucket
        BaritoneAPI.getSettings().allowWaterBucketFall.value = false;
        BaritoneAPI.getSettings().maxFallHeightNoWater.value = BastionSettings.maxSafeDrop;
        looted.clear();
        badChest.clear();
        plan = List.of();
        planFor = null;
        entry = null;
        exiting = false;
        sheltering = false;
        shelterRounds = 0;
        shelterSlit = null;
        shelterTearing = false;
        shelterNoLure = false; shelterSlitCycles = 0;
        shelterKills = 0;
        shelterShy.clear();
        lastHunter = null;
        goldSourceGone = false;
        chestsExhausted = false;
        chestsLooted = 0;
        stuckCount.clear();
        badLoot.clear();
        holes.clear(); pickHole = null; digPickup = false; inPickLogged = false; pickRetry = 0;
        digHole = null;
        holeRetry = 0;
        camping = false;
        unreachable.clear();
        usefulSeen = -1;
        distractTries.clear();
        startTick = 0;
        doorPos = null;
        descentSteps = 0;
        digging = false;
        preferGold = null;
        dropPlan = null;
        plannedFallUntil = 0;
        fallPlan = null;
        craftStep = 0;
        admiring.clear();
        bastion = null;
        admiring.clear();
        throwsDone = lootPicked = 0;
        traded.clear();
        badGold.clear();
        evadeUntil = 0;
        stuckAvoid.clear();
        if (stuckAvoider == null) {
            stuckAvoider = this::avoidCell;
            AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(stuckAvoider);
        }
        goldKey = null;
        ignored.clear();
        stallKey = null;
        fighting = false;
        perching = false;
        trapTries = 0;
        trapMode = "";
        craftStep = 0;
    }

    private boolean guardedAdded;
    private float hpSeen;
    private long hurtAt = -100000, shelterShoveAt = -100000;
    private int shelterShoves;
    private final Map<java.util.UUID, Long> shelterShy = new HashMap<>();
    private PiglinBrute lastHunter;
    private long lastHunterAt = -100000;

    /** Walking our way this tick: a brute runs at a quarter of a block a tick, and strolls at half that. */
    private static boolean coming(LivingEntity e, Player me) {
        double dx = me.getX() - e.getX(), dz = me.getZ() - e.getZ(), d = Math.hypot(dx, dz);
        return d > 0.01 && ((e.getX() - e.xo) * dx + (e.getZ() - e.zo) * dz) / d > 0.08;
    }

    public void stop() {
        EdgeCost.enabled = false; EdgeCost.lavaSoon = java.util.Set.of(); EdgeCost.waterSoon = java.util.Set.of();
        if (guardedAdded) { BaritoneAPI.getSettings().blocksToDisallowBreaking.value.remove(Blocks.GILDED_BLACKSTONE); guardedAdded = false; }
        active = false;
        if (savedBreakPenalty != null) {
            BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = savedBreakPenalty;
            savedBreakPenalty = null;
        }
        if (savedKinematic != null) {
            BaritoneAPI.getSettings().kinematicTravel.value = savedKinematic;
            savedKinematic = null;
        }
        if (savedFall != null) {
            BaritoneAPI.getSettings().maxFallHeightNoWater.value = savedFall;
            BaritoneAPI.getSettings().allowWaterBucketFall.value = savedBucketFall;
            savedFall = null;
        }
        if (fighting) baritone.getPveProcess().clearEnemies();
        fighting = false;
        perching = false;
        luring = false;
        trapMode = "";
        craftStep = 0;
        baritone.getPveProcess().hold = false;
        baritone.getPveProcess().driven = false;
        onLostControl(); // also drop the walk-through predicate and forced keys, not only when another process takes over
    }

    public String status() {
        return status + String.format(" throws=%d loot=%d", throwsDone, lootPicked);
    }

    @Override
    public boolean isActive() {
        return active && ctx.player() != null && ctx.world() != null;
    }

    private String layout() {
        return bastion == null || bastion.variant == null ? "" : bastion.variant;
    }

    private final java.util.Map<java.util.UUID, Long> ignored = new java.util.HashMap<>();
    private final java.util.Map<BlockPos, Long> badGold = new java.util.HashMap<>();
    private String goldKey;
    private BlockPos goldTarget;
    private long calmSince, lastGoldTick;
    private double goldBestDist;
    private long goldBestTick;
    private long goldSince;
    private BlockPos lastDig;
    private long healSince, approachSince;
    private String approachKey;
    private BlockPos safeSpot;
    private BlockPos evadeGoal;
    private long evadeUntil;
    private Vec3 travelAnchor;
    /** Cells the planner keeps routing through although the bot cannot get past them (checked by canWalkThrough). */
    private final java.util.Map<BlockPos, Long> stuckAvoid = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.Predicate<BlockPos> stuckAvoider;

    /** Asked by the path search, off the game thread, of every cell it would walk or fall through. */
    private boolean avoidCell(BlockPos pos) {
        Long until = stuckAvoid.get(pos);
        if (until != null && until > ticks) return true;
        // a hole dug for piglins is theirs: run 135 planned a way to the pickup hole down through it, and stood three
        // minutes at the bottom among seven of them with no room to lay a block and climb out
        for (BlockPos h : holes) if (pos.getX() == h.getX() && pos.getZ() == h.getZ() && pos.getY() <= h.getY() + 1 && pos.getY() >= h.getY() - 2) return true;
        return false;
    }
    private long travelSince;
    private String stallKey;
    private long stallSince;

    private static boolean otherLevel(LivingEntity e, Player me) {
        return Math.abs(e.getY() - me.getY()) > 3.5 && !(e instanceof Mob m && m.isAggressive());
    }

    private boolean threat(LivingEntity e, Player me, boolean early) {
        if (!e.isAlive()) return false;
        // a mob on another level (the bastion's lower floors, a pit) cannot be fought and would pin us in place trying to path to it
        // run 48: a hoglin in melee reach was skipped (other level / lava rule) and killed us mid-camp: always hit back
        if (e instanceof Hoglin && me.distanceTo(e) < 3.5 && Math.abs(e.getY() - me.getY()) < 1.5) return true;
        // run 88: a piglin hit us 5 times while we stood still "recovering" (it was not on the threat list): hit back whatever just hit us
        if (!(e instanceof PiglinBrute) && e == me.getLastHurtByMob() && me.tickCount - me.getLastHurtByMobTimestamp() < 60 && me.distanceTo(e) < 4 && Math.abs(e.getY() - me.getY()) < 1.5) return true;
        if (otherLevel(e, me)) return false;
        Long until = ignored.get(e.getUUID());
        if (until != null && until > ticks && !(e instanceof Mob m && m.isAggressive() && me.distanceTo(e) < 8)) return false;
        // never melee a brute (runs 13, 21: 20 -> 10 hp in seconds); they are kited by the flee rule instead
        if (e instanceof PiglinBrute) return false;
        if (e instanceof Zoglin) return me.distanceTo(e) < THREAT_RANGE;
        // do not chase anything standing by a drop over lava (run 19: knocked off the stables into the lava sea mid-fight)
        if (lavaDropNear(e.blockPosition()) || lavaDropNear(me.blockPosition())) return false;
        boolean hunting = e instanceof Mob m && (m.getTarget() == me || (m.isAggressive() && me.distanceTo(e) < 10));
        if (e instanceof Hoglin) return me.distanceTo(e) < (early ? THREAT_RANGE : 8) || hunting;
        // an angry adult piglin that is handed gold forgets us (see distract); only fight it when we have none to give
        // run 15: dropped gold did not calm piglins angered by a mined gold block, and it died distracting at 14 hp.
        // Two tries per piglin, then fight it.
        if (e instanceof Piglin p) return hunting && !(luring && me.getHealth() >= 10) && !(haveGold && !p.isBaby() && distractTries.getOrDefault(p.getUUID(), 0) < 2 && me.getHealth() >= 14);
        return false;
    }

    // ---- piglin trap: get every piglin into a 3-deep pit so they cannot reach us while we mine ----
    private boolean perching;
    private int perchBase, perchX, perchZ;
    private boolean perchClimbing, perchUp, perchCapped;
    private Double savedBreakPenalty;
    private Boolean savedKinematic;
    private long perchCooldown, waitSince, walledSince, relaxUntil, towerWaitSince, towerWaitTick = -100, towerReleaseUntil;
    private final Map<BlockPos, Long> wallFailed = new HashMap<>();
    private BlockPos wallCell;
    private int wallTries;
    private long perchTick, perchStallSince;
    private String perchStallKey;

    /**
     * One tick of a manual tower: look straight down, jump from the ground, and place under our feet as soon as the cell is free.
     * Gravel and soul sand alternate; the block we place against is always solid, so the gravel never falls.
     */
    private PathingCommand tower(Player me) {
        // the normal throwaway swap waits for us to stand still, which a tower never does: put a block on the hotbar ourselves
        boolean onHotbar = false;
        int inInv = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            boolean ok = s.is(Items.GRAVEL) || s.is(Items.SOUL_SAND)
                    || s.getItem() instanceof net.minecraft.world.item.BlockItem && BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(s.getItem())
                    && !AltoClefSettings.getInstance().isItemProtected(s.getItem());
            if (!ok) continue;
            if (i < 9) { onHotbar = true; break; }
            if (inInv < 0) inInv = i;
        }
        if (!onHotbar && inInv >= 0) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, inInv, 7, ContainerInput.SWAP, me);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // run 122: we landed on the rim of our own one-block pillar, our middle over the open air beside it. Looking
        // straight down from there the click reaches nothing, and we jumped on the spot for a minute. Get over the block
        // that holds us up first
        // run 129: our own column was open to the sky but a shoulder was under the block over the next one. Every jump
        // stopped a fifth of a block up, sixty ticks of it, until the brute arrived. Get the whole body under open air
        BlockPos foot = BlockPos.containing(me.getX(), me.getY() - 0.01, me.getZ());
        if (me.onGround()) {
            BlockPos hold = null;
            if (!ctx.world().getBlockState(foot).getCollisionShape(ctx.world(), foot).isEmpty()) {
                if (jumpBlocked(me) && Math.hypot(foot.getX() + 0.5 - me.getX(), foot.getZ() + 0.5 - me.getZ()) > 0.15) hold = foot;
            } else {
                double best = 1.3;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = foot.offset(dx, 0, dz);
                        if (ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty()) continue;
                        double d = Math.hypot(p.getX() + 0.5 - me.getX(), p.getZ() + 0.5 - me.getZ());
                        if (d < best) { best = d; hold = p; }
                    }
                }
            }
            if (hold != null) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(hold.getX() + 0.5 - me.getX()), hold.getZ() + 0.5 - me.getZ()));
                baritone.getLookBehavior().updateTarget(new Rotation(yaw, 90), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        BlockPos below = me.blockPosition().below();
        boolean free = ctx.world().getBlockState(below).isAir();
        BlockPos support = free ? below.below() : below;
        boolean gravel = !ctx.world().getBlockState(support).is(Blocks.GRAVEL);
        net.minecraft.world.item.Item first = gravel ? Items.GRAVEL : Items.SOUL_SAND, second = gravel ? Items.SOUL_SAND : Items.GRAVEL;
        boolean picked = baritone.getInventoryBehavior().throwaway(true, s -> s.is(Items.NETHERRACK))
                || baritone.getInventoryBehavior().throwaway(true, s -> s.is(first))
                || baritone.getInventoryBehavior().throwaway(true, s -> s.is(second));
        if (!picked) {
            // no gravel or soul sand: any block we are happy to lose will do
            for (net.minecraft.world.item.Item it : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
                if (AltoClefSettings.getInstance().isItemProtected(it)) continue;
                if (baritone.getInventoryBehavior().throwaway(true, s -> s.is(it))) break;
            }
        }
        baritone.getLookBehavior().updateTarget(new Rotation(me.getYRot(), 90), true);
        // run 114: towering from the top of a chest, every click opened the chest instead of placing and we jumped in
        // place until the brutes came. Sneak the whole time the block under the crosshair is one a click opens
        BlockPos under = BlockPos.containing(me.getX(), me.getY() - 0.01, me.getZ());
        for (int i = 0; i < 3 && ctx.world().getBlockState(under).isAir(); i++) under = under.below();
        if (clickOpens(under)) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        // run 124: the first jump, taken at a sprint, came down in the next column under a ceiling and the tower was given
        // up with the brute six blocks off. A tower goes straight up: come to a stand before jumping
        if (me.onGround() && Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z) < 0.08) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        if (free) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---- walling in ----
    private boolean sheltering;
    private long shelterSince, shelterWalledAt, shelterAngryAt, shelterCooldown;
    private Vec3 shelterAt;
    private BlockPos shelterTry;
    private int shelterTries, shelterRounds, shelterIdle;
    private float shelterHp;
    private final Map<BlockPos, Long> shelterFailed = new HashMap<>();
    // the slit: one block of the ring taken out at eye level, to be seen through and to strike through
    private BlockPos shelterSlit, shelterDig, shelterLastCell;
    private long shelterSlitAt, shelterSlitHold, shelterStruckAt, shelterSeenAt;
    private int shelterSlitOff, shelterSlitHurts, shelterSlitBad, shelterStrikes, shelterKills;
    private boolean shelterNoLure, shelterTearing;
    private int shelterSlitCycles;
    private LivingEntity shelterMark;
    private final java.util.LinkedHashSet<BlockPos> shelterPlaced = new java.util.LinkedHashSet<>();
    private long shelterTearAt, shelterTearDone;

    /** A brute that is after someone, or can see us: the ones that have not noticed us are not counted. */
    /** Where it stands from us, for the log: east, up, south. */
    private static String where(LivingEntity e, Player me) {
        return String.format(" (%+.1f %+.1f %+.1f%s)", e.getX() - me.getX(), e.getY() - me.getY(), e.getZ() - me.getZ(), e instanceof Mob m && m.isAggressive() ? " after us" : "");
    }

    private static boolean hunting(LivingEntity e, Player me) {
        return e instanceof Mob m && (m.isAggressive() || e.hasLineOfSight(me));
    }

    /** The cell to wall in: the one under our body that holds us up, nearest our middle. Null in the air. */
    private BlockPos shelterCell(Player me) {
        if (!me.onGround()) return null;
        BlockPos best = null;
        double bd = 9;
        for (int x = Mth.floor(me.getX() - 0.3); x <= Mth.floor(me.getX() + 0.3); x++) {
            for (int z = Mth.floor(me.getZ() - 0.3); z <= Mth.floor(me.getZ() + 0.3); z++) {
                if (!solid(BlockPos.containing(x + 0.5, me.getY() - 0.05, z + 0.5))) continue;
                double d = Math.hypot(x + 0.5 - me.getX(), z + 0.5 - me.getZ());
                if (d < bd) { bd = d; best = new BlockPos(x, Shelter.low(me.getY()), z); }
            }
        }
        return best;
    }

    /** A block for a wall, or for the roof: netherrack first, gravel last and never overhead (it comes down on us). */
    private int shelterBlock(Player me, boolean roof) {
        int other = -1, soul = -1, gravel = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.NETHERRACK)) return i;
            if (s.is(Items.GRAVEL)) { if (gravel < 0) gravel = i; }
            else if (s.is(Items.SOUL_SAND)) { if (soul < 0) soul = i; }
            else if (other < 0 && s.getItem() instanceof net.minecraft.world.item.BlockItem bi && !(bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                    && BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(s.getItem()) && !AltoClefSettings.getInstance().isItemProtected(s.getItem())) other = i;
        }
        return other >= 0 ? other : soul >= 0 ? soul : roof ? -1 : gravel;
    }

    /**
     * Walling in against brutes: see {@link Shelter}. Starts with a brute within twelve blocks that is after us or sees
     * us, or when we are hurt with one about; shuts the cell we stand in; waits in there, eating, until they have gone
     * quiet. Null when there is no call for it or no way to do it, and the older answers (tower, kite, leave) follow.
     */
    private PathingCommand shelter(Player me) {
        List<PiglinBrute> around = ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(16), PiglinBrute::isAlive);
        PiglinBrute hunter = null, nearest = null, mark = null;
        for (PiglinBrute b : around) {
            if (nearest == null || me.distanceTo(b) < me.distanceTo(nearest)) nearest = b;
            // one to draw to the slit: near enough to notice us through it, and on a level it can be struck from
            boolean shy = shelterShy.getOrDefault(b.getUUID(), 0L) > ticks;
            if (me.distanceTo(b) < 12 && Math.abs(b.getY() - me.getY()) < 2.5 && (!shy || me.distanceTo(b) < 5) && (mark == null || me.distanceTo(b) < me.distanceTo(mark))) mark = b;
            // run 134: one stood twelve blocks off for two minutes, in plain view and never a step nearer, and four
            // times the walls went up for it. Twelve is as far as a brute notices anything, and from there it is
            // forty ticks away; the walls take twelve. So: one within eight, or one further off that is on its way.
            // And one that was given a slit and never came is left to stand until it does come
            if (me.distanceTo(b) < 12 && hunting(b, me) && (me.distanceTo(b) < (shy ? 4 : 8) || coming(b, me)) && (hunter == null || me.distanceTo(b) < me.distanceTo(hunter))) hunter = b;
        }
        // sight comes and goes on a stair or in a doorway: one that was on us a second ago still is (run 135 walked
        // on down a stair for six ticks between two glimpses, and the walls were two blocks short when it arrived)
        if (hunter != null) { lastHunter = hunter; lastHunterAt = ticks; }
        else if (lastHunter != null && lastHunter.isAlive() && ticks - lastHunterAt < 20 && me.distanceTo(lastHunter) < 12) hunter = lastHunter;
        if (sheltering && (me.isInLava() || shelterAt != null && me.position().distanceTo(shelterAt) > 3)) sheltering = false;
        if (me.getHealth() < hpSeen - 0.9f) hurtAt = ticks;
        hpSeen = me.getHealth();
        // a grown piglin with its arms up at our side and a blow just taken: it is us it is after. Walls stop a sword
        // and a bolt alike, and its anger is gone in half a minute (run 133 fought two of them from 16 hp to 5, and died)
        Piglin pig = null;
        if (ticks - hurtAt < 60) {
            for (Piglin q : ctx.world().getEntitiesOfClass(Piglin.class, me.getBoundingBox().inflate(5), q -> q.isAlive() && !q.isBaby() && q.isAggressive())) {
                if (pig == null || me.distanceTo(q) < me.distanceTo(pig)) pig = q;
            }
        }
        if (!sheltering) {
            // hurt with one about: the walls are where to eat and mend, if there is anything to mend with
            boolean hurt = me.getHealth() < 14 && nearest != null && (foodSlot(me) >= 0 || me.getFoodData().getFoodLevel() >= 18);
            if (hunter == null && !hurt && pig == null || me.isInLava() || ticks < shelterCooldown || pillarBlocks(me) < 12
                    // already up a tower: that is the older answer at work
                    || perching && me.getY() >= perchBase + 1.9) return null;
            if (shelterCell(me) == null) {
                // in the air, half way through a hop or a drop: the walls start where we come down, and nothing else is
                // begun in the meantime (a tower was, twice in arena trial 4, and in run 133 two blocks into a fall)
                if (me.onGround() || me.isInWater()) return null;
                status = "brute about, coming down to wall in";
                return pause0();
            }
            sheltering = true;
            shelterSince = ticks;
            shelterWalledAt = 0;
            shelterAngryAt = ticks;
            shelterAt = me.position();
            shelterHp = me.getHealth();
            shelterTry = null;
            shelterIdle = 0;
            shelterFailed.clear();
            shelterRounds++;
            shelterSlit = null;
            shelterTearing = false;
            shelterPlaced.clear();
            shelterSlitHold = 0;
            shelterSlitHurts = 0;
            shelterSlitBad = 0;
            shelterStruckAt = 0;
            shelterMark = null;
            // what one brute could not see from its post says nothing of the next (run 133: seven brutes stood at the
            // walls after the first, half a minute each, and no slit was opened for any of them)
            shelterNoLure = false; shelterSlitCycles = 0;
            shelterShoves = 0;
            perching = false;
            baritone.getPveProcess().hold = false;
            if (fighting) { baritone.getPveProcess().clearEnemies(); fighting = false; }
            logDirect("Bastion: " + (hunter != null ? "brute at " + String.format("%.1f", me.distanceTo(hunter)) + where(hunter, me)
                    : pig != null ? "piglin on us at " + String.format("%.1f", me.distanceTo(pig)) + where(pig, me) + ", " + (int) me.getHealth() + " hp"
                    : "hurt (" + (int) me.getHealth() + " hp) with a brute about") + ", walling in");
        }
        boolean hurtNow = me.getHealth() < shelterHp - 1.5f;
        if (hurtNow && shelterWalledAt != 0) logDirect("Bastion: hurt behind our own walls, " + (int) me.getHealth() + " hp" + (shelterSlit != null ? ", slit open" : ""));
        shelterHp = me.getHealth();
        if (shelterMark != null && !shelterMark.isAlive()) {
            shelterKills++;
            logDirect("Bastion: brute killed from behind the walls, " + shelterStrikes + " blows (" + shelterKills + " so far)");
            shelterMark = null;
            shelterStrikes = 0;
        }
        if (shelterTearing) {
            // one that comes while the walls are half down: up they go again. Hurt by anything else, the walls are no help
            if (hurtNow || ticks - shelterTearAt > 600) { shelterTearing = false; sheltering = false; return null; }
            if (hunter != null) {
                logDirect("Bastion: brute at " + String.format("%.1f", me.distanceTo(hunter)) + ", the walls go back up");
                shelterTearing = false;
            } else {
                Vec3 eye = me.getEyePosition(1.0F);
                BlockPos b = null;
                // the last ones placed first: the tops and the roof, so that what they drop lands within our reach
                for (BlockPos q : shelterPlaced) if (!ctx.world().getBlockState(q).canBeReplaced() && eye.distanceTo(Vec3.atCenterOf(q)) < 4.2) b = q;
                if (b == null) {
                    // what the last block dropped can be picked up half a second after it fell
                    if (shelterTearDone == 0) shelterTearDone = ticks;
                    if (ticks - shelterTearDone > 12) {
                        logDirect("Bastion: walls down, " + pillarBlocks(me) + " blocks in hand");
                        shelterTearing = false;
                        sheltering = false;
                        return null;
                    }
                    status = "walls down, picking up the blocks";
                    return pause0();
                }
                int pk = pickaxeSlot(me);
                if (pk >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pk, 6, ContainerInput.SWAP, me); return pause0(); }
                if (pk >= 0) me.getInventory().setSelectedSlot(pk);
                Vec3 c = Vec3.atCenterOf(b);
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, c, ctx.playerRotations()), true);
                Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
                if (!b.equals(shelterDig)) { shelterDig = b; ctx.playerController().clickBlock(b, face); }
                else ctx.playerController().onPlayerDamageBlock(b, face);
                me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                status = "taking the walls down";
                return pause0();
            }
        }
        BlockPos cell = shelterCell(me);
        if (cell == null) {
            // in the air: knocked off our feet
            if (shelterWalledAt == 0 && ticks - shelterSince > 40) { sheltering = false; return null; }
            cell = BlockPos.containing(shelterAt.x, shelterAt.y + 1e-4, shelterAt.z);
        } else if (shelterWalledAt == 0) shelterAt = me.position();
        // still being carried along the path we were on: a block laid now is a block left behind
        boolean steady = cell.equals(shelterLastCell) && Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z) < 0.2 || shelterWalledAt != 0;
        shelterLastCell = cell;
        if (!steady && me.onGround() && ticks - shelterSince > 8 && baritone.getPathingBehavior().getCurrent() != null) baritone.getPathingBehavior().secretInternalSegmentCancel();
        final double feetY = me.onGround() ? me.getY() : shelterAt.y;
        // one of them standing where a wall goes: no block is laid in a cell with somebody in it, and run 133 gave the
        // walls up twice over that with a piglin at its elbow. A blow of any kind throws it back a block or two, and
        // the side it stood on is the first to be shut
        LivingEntity intruder = null;
        for (int[] sd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            net.minecraft.world.phys.AABB side = new net.minecraft.world.phys.AABB(cell.getX() + sd[0], feetY, cell.getZ() + sd[1], cell.getX() + sd[0] + 1, feetY + 1.8, cell.getZ() + sd[1] + 1);
            if (!ctx.world().getBlockState(BlockPos.containing(cell.getX() + sd[0] + 0.5, feetY + 0.5, cell.getZ() + sd[1] + 0.5)).canBeReplaced()
                    && !ctx.world().getBlockState(BlockPos.containing(cell.getX() + sd[0] + 0.5, feetY + 1.5, cell.getZ() + sd[1] + 0.5)).canBeReplaced()) continue;
            // run 134 ended on a piglin we had been trading with, stood in our own cell with a brute six blocks off:
            // until the walls have once been shut, whoever is in the way goes, friend or not. Its anger is half a
            // minute behind walls; the brute is two blows
            boolean anyone = shelterWalledAt == 0;
            for (LivingEntity e : ctx.world().getEntitiesOfClass(LivingEntity.class, side, e -> e != me && e.isAlive()
                    && (anyone || e instanceof PiglinBrute || e instanceof Mob m && m.isAggressive() && !(e instanceof Piglin q && q.isBaby())))) {
                if (intruder == null || me.distanceTo(e) < me.distanceTo(intruder)) intruder = e;
            }
        }
        double tx = toward0(intruder != null ? intruder : hunter, nearest != null ? nearest : pig, me, true), tz = toward0(intruder != null ? intruder : hunter, nearest != null ? nearest : pig, me, false);
        int weapon = weaponSlot(me);
        // a piglin with a crossbow that is after us and sees us: through the slit is the only way it can, and a bolt comes
        // the same way. One that is not after us is no more than a face at the window (run 132: fourteen times the slit
        // was shut on one, with the brute outside at 1 hp)
        boolean watched = shelterSlit != null && !ctx.world().getEntitiesOfClass(Piglin.class, me.getBoundingBox().inflate(16), q -> q.isAlive() && q.isHolding(Items.CROSSBOW) && q.isAggressive() && q.hasLineOfSight(me)).isEmpty();
        if (shelterSlit != null) {
            int[] want = mark == null ? null : Shelter.slit(cell.getX(), Shelter.slitLevel(feetY, mark.getEyeY()), cell.getZ(), mark.getX() - cell.getX() - 0.5, mark.getZ() - cell.getZ() - 0.5);
            shelterSlitOff = want != null && shelterSlit.equals(new BlockPos(want[0], want[1], want[2])) ? 0 : shelterSlitOff + 1;
            String shut = null;
            if (hurtNow || me.getHealth() < 10) {
                shut = "hurt at the slit";
                shelterSlitHold = ticks + 200;
                if (++shelterSlitHurts >= 2) shelterNoLure = true;
            } else if (watched && (me.getHealth() < 12 || around.stream().noneMatch(b -> clearAim(me, b) != null))) {
                // with a brute in reach the blow comes first: a bolt is three hearts at worst, and the brute is the one that kills
                shut = "a crossbow is aimed at the slit";
                shelterSlitHold = ticks + 200;
            } else if (weapon < 0 || shelterNoLure) {
                shut = "nothing to strike with";
            } else if (shelterSlitOff > 40) {
                shut = mark == null ? "nobody left at the slit" : "the brute is round another side";
                // r142: a brute circling the ring had the slit shut and reopened every few seconds for 13 minutes
                if (mark != null && ++shelterSlitCycles >= 6) {
                    shut = "the brute keeps circling (" + shelterSlitCycles + " slits)";
                    shelterNoLure = true;
                    shelterShy.put(mark.getUUID(), ticks + 6000L);
                }
            } else if (ticks - shelterSlitAt > 300 && ticks - shelterSeenAt > 300 && shelterStruckAt < shelterSlitAt) {
                // fifteen seconds and it has not once looked our way: it is over us or under us, or round a corner
                shut = "the brute" + (mark != null ? where(mark, me) : "") + " cannot see the slit";
                shelterNoLure = true;
                if (mark != null) shelterShy.put(mark.getUUID(), ticks + 6000L);
            } else if (ticks - shelterSlitAt > 1200 && ticks - shelterStruckAt > 600) {
                // a minute of it and half of that without a blow: it sees us and will not come, or cannot
                shut = "the brute does not come to the slit";
                shelterNoLure = true;
                if (mark != null) shelterShy.put(mark.getUUID(), ticks + 6000L);
            }
            if (shut != null) {
                logDirect("Bastion: " + shut + ", shutting it");
                shelterSlit = null;
            }
        }
        Shelter.Grid g = new Shelter.Grid() {
            // the slit counts as wall: it is meant to be open
            public boolean open(int x, int y, int z) { BlockPos p = new BlockPos(x, y, z); return !p.equals(shelterSlit) && ctx.world().getBlockState(p).canBeReplaced(); }
            public boolean solid(int x, int y, int z) { BlockPos p = new BlockPos(x, y, z); BlockState s = ctx.world().getBlockState(p); return !p.equals(shelterSlit) && !s.canBeReplaced() && !s.getCollisionShape(ctx.world(), p).isEmpty(); }
            public boolean blocked(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return shelterFailed.getOrDefault(p, 0L) > ticks || !ctx.world().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(p), LivingEntity::isAlive).isEmpty();
            }
        };
        boolean walled = Shelter.walled(g, cell.getX(), feetY, cell.getZ());
        Shelter.Place p = Shelter.next(g, cell.getX(), feetY, cell.getZ(), tx, tz);
        // with a brute that near and something to hit it with, the walls become a ring and the ring gets a slit: it
        // comes for what it sees, it cannot reach us, and we can reach it. Left alone it stays at its post, and we are
        // back behind walls the moment we step out (arena trial 2: thirty seconds a round, ten blocks a round)
        boolean lure = walled && weapon >= 0 && !shelterNoLure && mark != null;
        if (p == null && lure) p = Shelter.fort(g, cell.getX(), feetY, cell.getZ(), tx, tz);
        boolean ring = lure && p == null && Shelter.roofed(g, cell.getX(), feetY, cell.getZ()) && Shelter.fortified(g, cell.getX(), feetY, cell.getZ());
        double ox = cell.getX() + 0.5 - me.getX(), oz = cell.getZ() + 0.5 - me.getZ(), off = Math.hypot(ox, oz);
        // sneaking: we do not walk off an edge, and a click on a chest places against it instead of opening it
        boolean sneak = off < 0.25;
        float yaw = me.getYRot();
        int slot = p == null ? -1 : shelterBlock(me, p.roof());
        boolean shoving = intruder != null && !walled && shelterShoves < 6;
        if (!steady) {
            // nothing placed yet
        } else if (shoving && ticks - shelterShoveAt >= 11) {
            // eleven ticks apart: for ten after a blow nothing more is felt
            Rotation rot = RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), intruder.getBoundingBox().getCenter(), ctx.playerRotations());
            yaw = rot.getYaw();
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.minecraft().gameMode.attack((LocalPlayer) me, intruder);
            me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            shelterShoveAt = ticks;
            if (shelterShoves++ == 0) logDirect("Bastion: " + intruder.getType().toShortString() + " in the way of the wall, knocking it back");
        } else if (slot >= 9) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ContainerInput.SWAP, me);
        } else if (slot >= 0) {
            BlockPos c = new BlockPos(p.x(), p.y(), p.z()), against = new BlockPos(p.ax(), p.ay(), p.az());
            Vec3 hit = Vec3.atCenterOf(against).add(p.fx() * 0.5, p.fy() * 0.5, p.fz() * 0.5);
            Rotation rot = RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), hit, ctx.playerRotations());
            yaw = rot.getYaw();
            baritone.getLookBehavior().updateTarget(rot, true);
            if (clickOpens(against) && !me.isShiftKeyDown()) {
                sneak = true;
            } else {
                me.getInventory().setSelectedSlot(slot);
                if (!c.equals(shelterTry)) { shelterTry = c; shelterTries = 0; }
                if (++shelterTries > 6) {
                    // six clicks and still open: whatever it is, the other cells come first for a while
                    shelterFailed.put(c, ticks + 60L);
                    shelterTry = null;
                    logDirect("Bastion: block refused at " + c.toShortString() + " against " + ctx.world().getBlockState(against).getBlock().getName().getString() + ", in the cell: "
                            + ctx.world().getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(c)).stream().map(e -> e.getType().toShortString()).toList());
                } else {
                    ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.getApproximateNearest(p.fx(), p.fy(), p.fz()), against, false));
                    shelterPlaced.add(c);
                    me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                }
            }
        }
        double slack = 0.1;
        if (shelterSlit != null && p == null && mark != null && me.onGround() && ctx.world().getBlockState(shelterSlit).canBeReplaced()) {
            // a slit shows us to a brute within some eighteen degrees of square on, from the middle of the cell; from
            // against the far side wall it is twenty-eight on the one hand. The brute's eye and ours are all it goes
            // by, so the place to stand is found the way it looks: the nearest of nine from which its eye is in plain
            // view. Against a wall we are still a block and a half from anything outside it
            Vec3 to = mark.getEyePosition(1.0F);
            double best = Double.MAX_VALUE, sx = cell.getX() + 0.5, sz = cell.getZ() + 0.5;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Vec3 from = new Vec3(cell.getX() + 0.5 + 0.2 * i, me.getEyeY(), cell.getZ() + 0.5 + 0.2 * j);
                    if (ctx.world().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me)).getType() != HitResult.Type.MISS) continue;
                    double d = Math.hypot(from.x - me.getX(), from.z - me.getZ());
                    if (d < best) { best = d; sx = from.x; sz = from.z; }
                }
            }
            ox = sx - me.getX();
            oz = sz - me.getZ();
            off = Math.hypot(ox, oz);
            if (sx != cell.getX() + 0.5 || sz != cell.getZ() + 0.5) slack = 0.04;
        }
        if (off > slack && me.onGround()) {
            // to the middle of the cell, or our own body is in the way of the walls. The look belongs to the click, so
            // the step is taken with whichever keys point that way
            double rad = Math.toRadians(yaw), fwd = -Math.sin(rad) * ox + Math.cos(rad) * oz, left = Math.cos(rad) * ox + Math.sin(rad) * oz;
            double dead = slack * 0.7;
            if (fwd > dead) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            else if (fwd < -dead) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            if (left > dead) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
            else if (left < -dead) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        }
        // at the slit we stand up, a sneaking player being noticed from four fifths as far, unless the slit was put
        // at the level of a sneaking eye
        if (shelterSlit != null && p == null) sneak = shelterSlit.getY() != Shelter.eye(feetY);
        if (sneak) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        // shut once and now a hole we cannot fill: the slit that will not take its block back, as like as not. What
        // stands of the walls is still the best place to be: run 132 gave them up over that with a brute at arm's
        // length, walked out, and was dead in a minute
        boolean holed = !walled && shelterWalledAt != 0 && (p == null || slot < 0);
        if (holed && ticks % 100 == 0) logDirect("Bastion: a hole in the walls we cannot fill, staying behind what stands");
        if (!walled && !holed) {
            // blocks gone, or every open cell refuses them (a brute already stands in it): this is no shelter, and
            // standing still in it is the worst of both. The older answers get five seconds
            shelterIdle = p == null && !shoving ? shelterIdle + 1 : 0;
            if (p != null && slot < 0 || shelterIdle > 10) {
                logDirect("Bastion: cannot close the walls" + (p != null ? " (out of blocks)" : "") + ", giving it up");
                sheltering = false;
                shelterCooldown = ticks + (p != null ? 600 : 100);
                return null;
            }
            status = "walling in at " + cell.toShortString() + (hunter != null ? ", brute at " + String.format("%.1f", me.distanceTo(hunter)) : "");
            return pause0();
        }
        if (shelterWalledAt == 0) shelterWalledAt = ticks;
        // the ring wanted and no block for it: wait it out the old way
        if (lure && p != null && slot < 0) shelterNoLure = true;
        boolean angry = false;
        for (PiglinBrute b : around) angry |= b.isAggressive();
        // a blow at a brute sets every grown piglin that saw it on us for half a minute, and so does whatever got us
        // hurt. Past that, one with its arms up is after a hoglin
        angry |= (ticks - hurtAt < 700 || shelterStruckAt != 0 && ticks - shelterStruckAt < 700 || ticks - shelterShoveAt < 700)
                && !ctx.world().getEntitiesOfClass(Piglin.class, me.getBoundingBox().inflate(16), q -> q.isAlive() && !q.isBaby() && q.isAggressive()).isEmpty();
        if (angry) shelterAngryAt = ticks;
        if (ring && shelterSlit == null && ticks >= shelterSlitHold && me.onGround() && me.getHealth() >= 14) {
            int[] w = Shelter.slit(cell.getX(), Shelter.slitLevel(feetY, mark.getEyeY()), cell.getZ(), mark.getX() - cell.getX() - 0.5, mark.getZ() - cell.getZ() - 0.5);
            BlockPos s = new BlockPos(w[0], w[1], w[2]);
            BlockState st = ctx.world().getBlockState(s);
            if (clickOpens(s) || st.hasBlockEntity() || st.is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS) || st.getDestroySpeed(ctx.world(), s) < 0 || st.getDestroySpeed(ctx.world(), s) > 3) {
                // not a block to knock a hole in: perhaps it walks round to another side
                shelterSlitHold = ticks + 100;
                if (++shelterSlitBad >= 3) shelterNoLure = true;
            } else {
                shelterSlit = s;
                shelterSlitAt = ticks;
                shelterSeenAt = ticks;
                shelterSlitOff = 0;
                shelterDig = null;
                logDirect("Bastion: ring shut, opening a slit toward the brute at " + String.format("%.1f", me.distanceTo(mark)));
            }
        }
        if (shelterSlit != null && p == null) {
            Vec3 eye = me.getEyePosition(1.0F);
            if (!ctx.world().getBlockState(shelterSlit).canBeReplaced()) {
                int pk = pickaxeSlot(me);
                if (pk >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pk, 6, ContainerInput.SWAP, me); return pause0(); }
                if (pk >= 0) me.getInventory().setSelectedSlot(pk);
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, Vec3.atCenterOf(shelterSlit), ctx.playerRotations()), true);
                Direction face = Direction.getApproximateNearest(cell.getX() - shelterSlit.getX(), 0, cell.getZ() - shelterSlit.getZ());
                if (!shelterSlit.equals(shelterDig)) { shelterDig = shelterSlit; ctx.playerController().clickBlock(shelterSlit, face); }
                else ctx.playerController().onPlayerDamageBlock(shelterSlit, face);
                me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                status = "walled in, opening a slit";
                return pause0();
            }
            if (mark != null && (mark.isAggressive() || mark.hasLineOfSight(me))) shelterSeenAt = ticks;
            PiglinBrute t = null;
            Vec3 aim = null;
            for (PiglinBrute b : around) {
                Vec3 a = clearAim(me, b);
                if (a != null && (aim == null || eye.distanceTo(a) < eye.distanceTo(aim))) { t = b; aim = a; }
            }
            if (me.getInventory().getSelectedSlot() != weapon) me.getInventory().setSelectedSlot(weapon);
            else if (t != null) {
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, aim, ctx.playerRotations()), true);
                if (me.getAttackStrengthScale(0.5f) >= 0.95f) {
                    ctx.minecraft().gameMode.attack((LocalPlayer) me, t);
                    me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    shelterStruckAt = ticks;
                    if (t != shelterMark) shelterStrikes = 0;
                    shelterMark = t;
                    shelterStrikes++;
                }
            } else if (mark != null) {
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, mark.getEyePosition(1.0F), ctx.playerRotations()), true);
            }
            status = "at the slit, " + (t != null ? "striking the brute at " + String.format("%.1f", me.distanceTo(t)) + " (" + (int) t.getHealth() + " hp)"
                    : mark != null ? "brute at " + String.format("%.1f", me.distanceTo(mark)) + (mark.isAggressive() ? ", after us" : ", quiet") : "nobody about");
            return pause0();
        }
        long calm = ticks - shelterAngryAt;
        double nd = nearest == null ? 99 : me.distanceTo(nearest);
        boolean canEat = foodSlot(me) >= 0;
        boolean healed = me.getHealth() >= 17 || !canEat && me.getFoodData().getFoodLevel() < 18;
        // out when they have gone quiet: after three seconds with none of them near enough to notice us, after thirty
        // with one that has not gone back to its post, after a minute with one at the wall, and after two minutes
        // whatever they are doing
        // none of it while the ring is going up or the slit is due to open again: that brute is being dealt with
        boolean dealing = lure && (p != null || ring);
        if (!dealing && healed && (calm >= 60 && nd >= 12 || calm >= 600 && nd >= 5 || calm >= 1200) || ticks - Math.max(shelterWalledAt, shelterStruckAt) > 2400) {
            logDirect("Bastion: walled in for " + (ticks - shelterSince) / 20 + " s (round " + shelterRounds + "), " + (calm >= 60 ? "the brutes have gone quiet" : "they have not gone quiet") + ", moving on (" + around.size() + " within 16"
                    + (nearest != null ? ", nearest " + String.format("%.1f", me.distanceTo(nearest)) : "") + ")");
            shelterSlit = null;
            if ((nearest == null || nd >= 10 && hunter == null) && !angry && !shelterPlaced.isEmpty()) {
                // nobody near: the blocks come with us. Twenty a time, and a bastion has ten brutes
                shelterTearing = true;
                shelterTearAt = ticks;
                shelterTearDone = 0;
                shelterDig = null;
                return pause0();
            }
            sheltering = false;
            return null;
        }
        if (p == null && canEat && me.getHealth() < 20 && me.getFoodData().getFoodLevel() < 18) {
            PathingCommand eat = eat(me);
            if (eat != null) return eat;
        }
        status = "walled in, " + around.size() + " brutes within 16" + (nearest != null ? ", nearest " + String.format("%.1f", me.distanceTo(nearest)) : "") + (angry ? ", after us" : ", quiet " + calm / 20 + " s");
        return pause0();
    }

    private static double toward0(LivingEntity a, LivingEntity b, Player me, boolean x) {
        LivingEntity e = a != null ? a : b;
        return e == null ? 0 : x ? e.getX() - me.getX() : e.getZ() - me.getZ();
    }

    /** A sword on the hotbar, or failing that an axe; -1 with neither. */
    private int weaponSlot(Player me) {
        int slot = -1, rank = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = me.getInventory().getItem(i);
            int r = s.is(net.minecraft.tags.ItemTags.SWORDS) ? 2 : s.is(net.minecraft.tags.ItemTags.AXES) ? 1 : 0;
            if (r > rank) { rank = r; slot = i; }
        }
        return slot;
    }

    /** A point of its body we can strike from where we stand: within our reach, with nothing between it and our eye. */
    private Vec3 clearAim(Player me, LivingEntity e) {
        Vec3 eye = me.getEyePosition(1.0F), best = null;
        net.minecraft.world.phys.AABB bb = e.getBoundingBox();
        double[] xs = {Mth.clamp(eye.x, bb.minX, bb.maxX), (bb.minX + bb.maxX) / 2}, zs = {Mth.clamp(eye.z, bb.minZ, bb.maxZ), (bb.minZ + bb.maxZ) / 2};
        for (double y = bb.maxY - 0.1; y >= bb.minY; y -= 0.25) {
            for (double x : xs) {
                for (double z : zs) {
                    Vec3 a = new Vec3(x, y, z);
                    if (eye.distanceTo(a) >= 2.9 || best != null && eye.distanceTo(a) >= eye.distanceTo(best)) continue;
                    if (ctx.world().clip(new net.minecraft.world.level.ClipContext(eye, a, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me)).getType() == HitResult.Type.MISS) best = a;
                }
            }
        }
        return best;
    }

    /** Whether we are shut in behind our own walls right now (the harness asks). */
    public boolean walledIn() {
        return sheltering && shelterWalledAt != 0;
    }

    /** Whether a block over any column our body overlaps would stop a jump short: the body is wider than half a block. */
    private boolean jumpBlocked(Player me) {
        net.minecraft.world.phys.AABB bb = me.getBoundingBox().deflate(1e-4, 0, 1e-4);
        int y = BlockPos.containing(me.getX(), me.getY() + 0.01, me.getZ()).getY();
        for (int x = (int) Math.floor(bb.minX); x <= (int) Math.floor(bb.maxX); x++) {
            for (int z = (int) Math.floor(bb.minZ); z <= (int) Math.floor(bb.maxZ); z++) {
                for (int dy = 2; dy <= 3; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /** Whether a plain right click on this block opens or uses it (a chest, a furnace, a crafting table) rather than placing against it. */
    private boolean clickOpens(BlockPos p) {
        BlockState s = ctx.world().getBlockState(p);
        return s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock || s.getMenuProvider(ctx.world(), p) != null;
    }

    /** The tower needs air over our head: under a ceiling the jump is blocked and the placement pointless. */
    private boolean headroom(Player me, int base) {
        for (int i = 2; i <= 5; i++) {
            if (ctx.world().getBlockState(new BlockPos(me.blockPosition().getX(), base + i, me.blockPosition().getZ())).isSolid()) return false;
        }
        return true;
    }

    private int brutesAround(LivingEntity p) {
        return ctx.world().getEntitiesOfClass(PiglinBrute.class, p.getBoundingBox().inflate(10), e -> e.isAlive()).size();
    }

    private int pillarBlocks(Player me) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.GRAVEL) || s.is(Items.SOUL_SAND)
                    || BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(s.getItem())
                    && !AltoClefSettings.getInstance().isItemProtected(s.getItem())) n += s.getCount();
        }
        return n;
    }

    private boolean haveGold;
    private long lastDistract = -1000;
    public int distractions;

    private final Map<java.util.UUID, Integer> distractTries = new HashMap<>();

    private PathingCommand distract(Player me, Piglin angry) {
        PathingCommand pause = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (ticks - lastDistract < 30) return pause;
        if (me.distanceTo(angry) > THROW_RANGE || !me.hasLineOfSight(angry)) {
            status = "distract: close in";
            return new PathingCommand(new GoalNear(angry.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        int slot = hotbarGold(me);
        if (slot < 0) {
            status = "hotbar gold";
            return pause;
        }
        me.getInventory().setSelectedSlot(slot);
        aimer.look(angry.position().add(0, 0.3, 0), 0);
        if (Math.abs(Mth.wrapDegrees(me.getYRot() - yawTo(me, angry))) < 12) {
            if (!safeDrop(me)) return pause0();
            lastDistract = ticks;
            distractions++; distractTries.merge(angry.getUUID(), 1, Integer::sum);
            traded.add(angry.getUUID());
            status = "distract: dropped gold";
        }
        return pause;
    }

    private boolean luring;
    private BlockPos pit;
    private long pitScan = -1000, trapSince;
    private String trapMode = "";
    private int trapTries, aimTicks;
    private boolean angered;

    private int ingotCount(Player me) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.GOLD_INGOT)) n += s.getCount();
        }
        return n;
    }

    private boolean solid(BlockPos p) {
        return !ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty();
    }

    /** Bottom cell of the nearest 1x1 pit that is 3 deep, walled on all sides and floored: piglins fall in but cannot climb out. */
    private BlockPos findPit(Player me) {
        BlockPos base = me.blockPosition();
        int r = base.getY();
        BlockPos best = null;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                BlockPos b = new BlockPos(base.getX() + dx, r - 3, base.getZ() + dz);
                if (!solid(b.below())) continue;
                boolean ok = true;
                for (int y = 0; y < 3 && ok; y++) {
                    BlockPos c = b.above(y);
                    ok = ctx.world().getBlockState(c).isAir() && solid(c.north()) && solid(c.south()) && solid(c.east()) && solid(c.west());
                }
                if (ok && ctx.world().getBlockState(b.above(3)).isAir() && (best == null || base.distSqr(b) < base.distSqr(best))) best = b;
            }
        }
        return best;
    }

    /** Null when no trap is possible. Chooses bait (throw an ingot into the pit) when we have gold, lure (anger one and stand behind the pit) otherwise. */
    private PathingCommand trap(Player me, List<LivingEntity> near) {
        PathingCommand pause = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (trapTries >= 3) return null;
        if (ticks - pitScan > 80) {
            pitScan = ticks;
            pit = findPit(me);
        }
        if (pit == null) return null;
        List<Piglin> blockers = new ArrayList<>();
        for (LivingEntity e : near) if (e instanceof Piglin p && !p.isBaby() && p.isAlive() && me.distanceTo(p) < ANGER_RANGE && !stuck(me, p)) blockers.add(p);
        if (blockers.isEmpty()) return null;
        blockers.sort((a, b) -> Double.compare(me.distanceToSqr(a), me.distanceToSqr(b)));
        if (trapMode.isEmpty()) {
            if (ingotCount(me) >= 1) trapMode = "bait";
            else if (me.getHealth() >= 14 && blockers.size() <= 4) trapMode = "lure";
            else return null;
            trapSince = ticks;
            angered = false;
            aimTicks = 0;
        }
        if (ticks - trapSince > 400) {
            trapMode = "";
            luring = false;
            pit = null;
            trapTries++;
            return null;
        }
        BlockPos rim = pit.above(3);
        Piglin victim = blockers.get(0);
        if (trapMode.equals("bait")) {
            double horiz = Math.hypot(me.getX() - (rim.getX() + 0.5), me.getZ() - (rim.getZ() + 0.5));
            if (horiz > 2.6 || Math.abs(me.getY() - rim.getY()) > 1.2) {
                status = "bait: to pit";
                return new PathingCommand(new GoalNear(rim, 2), PathingCommandType.SET_GOAL_AND_PATH);
            }
            if (angered) {
                status = "bait: waiting for piglins";
                return pause;
            }
            int slot = hotbarGold(me);
            if (slot < 0) {
                status = "hotbar gold";
                return pause;
            }
            me.getInventory().setSelectedSlot(slot);
            aimer.look(Vec3.atBottomCenterOf(pit).add(0, 0.6, 0), 0);
            if (++aimTicks >= 6) {
                if (!safeDrop(me)) return pause0();
                angered = true; // reused as "bait thrown"
                status = "bait: threw ingot";
            }
            return pause;
        }
        // lure: hit one piglin, then stand on the far side of the pit so the chase crosses it
        luring = true;
        if (!angered) {
            if (me.distanceTo(victim) > 2.8) {
                status = "lure: close in";
                return new PathingCommand(new GoalNear(victim.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
            }
            aimer.look(victim.getEyePosition(), 0);
            if (++aimTicks >= 4) {
                net.minecraft.client.Minecraft.getInstance().gameMode.attack((LocalPlayer) me, victim);
                me.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                angered = true;
            }
            return pause;
        }
        int dx = Integer.signum(pit.getX() - victim.blockPosition().getX()), dz = Integer.signum(pit.getZ() - victim.blockPosition().getZ());
        if (Math.abs(pit.getX() - victim.getX()) >= Math.abs(pit.getZ() - victim.getZ())) dz = 0; else dx = 0;
        BlockPos stand = rim.offset(dx * 2, 0, dz * 2);
        status = "lure: behind pit";
        if (me.blockPosition().distSqr(stand) > 2) return new PathingCommand(new GoalNear(stand, 1), PathingCommandType.SET_GOAL_AND_PATH);
        return pause;
    }

    // ---- stuck detector: the same few blocks for 10 s while we asked to path means a move that never completes ----
    private Vec3 stuckAnchor;
    private final Map<String, Integer> stuckCount = new HashMap<>();
    private long stuckSince;
    public int unstucks;

    private long lastCalcFail = -1000;
    private double[] throwPred;
    private BlockPos lastStuckDest;
    private boolean lavaWithin(Player me, int r) {
        for (BlockPos q : BlockPos.betweenClosed(me.blockPosition().offset(-r, -2, -r), me.blockPosition().offset(r, 1, r))) if (realLava(q)) return true;
        return false;
    }
    private int sameStuck;
    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (stuckAvoider == null) {
            // onLostControl drops the predicate; put it back or blacklisted moves would be planned again
            stuckAvoider = this::avoidCell;
            AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(stuckAvoider);
        }
        if (ticks < nudgeUntil && nudgeDest != null && ctx.player() != null && !ctx.player().isInLava()) {
            // walk straight at the step the planner keeps failing (run 16: 5 minutes at index 0 of a 2-block MovementFall)
            Player p0 = ctx.player();
            // run 96: a MovementFall straight down through netherrack stalled with the netherrack held (no dig); walking
            // toward our own column does nothing, so dig the block under our feet ourselves (never into lava)
            BlockPos under = ctx.player().blockPosition().below();
            if (nudgeDest.getX() == ctx.player().blockPosition().getX() && nudgeDest.getZ() == ctx.player().blockPosition().getZ() && solid(under) && !lavaNearPos(under.below())) {
                int pk = pickaxeSlot(ctx.player());
                if (pk >= 0 && pk < 9) ctx.player().getInventory().setSelectedSlot(pk);
                else if (pk >= 9) ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, pk, 7, ContainerInput.SWAP, ctx.player());
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), 90), true);
                if (!under.equals(holeTarget)) { holeTarget = under; ctx.playerController().clickBlock(under, Direction.UP); }
                else ctx.playerController().onPlayerDamageBlock(under, Direction.UP);
                ticks++;
                status = "digging down to " + nudgeDest.toShortString();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            double nx = nudgeDest.getX() + 0.5 - p0.getX(), nz = nudgeDest.getZ() + 0.5 - p0.getZ();
            baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-nx, nz)), 20), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            ticks++; // tick0 owns the clock and does not run while nudging (run 22: nudged for 10 minutes)
            status = "nudging toward " + nudgeDest.toShortString();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ctx.player() != null) {
            selAtTickStart = ctx.player().getInventory().getSelectedSlot(); updateFlow(ctx.player(), brokeNextToFluid()); if (ticks - lastBlockTick == 3) { net.minecraft.client.Minecraft.getInstance().options.keyUse.setDown(false); if (ctx.player().isUsingItem() && ctx.player().getUseItem().is(Items.SHIELD)) net.minecraft.client.Minecraft.getInstance().gameMode.releaseUsingItem(ctx.player()); }
            // picked-up counter from the inventory (counting the item we walked to missed most pickups, run 32: 2)
            int u = 0;
            for (int i = 0; i < 36; i++) { ItemStack st = ctx.player().getInventory().getItem(i); if (!st.isEmpty() && BastionGoals.barterUseful(itemId(st)) && !st.is(Items.GOLD_INGOT)) u += st.getCount(); }
            if (usefulSeen >= 0 && u > usefulSeen && ticks - throwTick < ADMIRE_TICKS + 600) lootPicked += u - usefulSeen;
            usefulSeen = u;
        }        EdgeCost.enabled = ticks >= relaxUntil;
        PathingCommand cmd = tick0(calcFailed, isSafeToCancel);
        Player me = ctx.player();
        boolean walking = cmd != null && (cmd.commandType == PathingCommandType.SET_GOAL_AND_PATH
                || cmd.commandType == PathingCommandType.REVALIDATE_GOAL_AND_PATH || cmd.commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        if (!walking || me == null) {
            stuckAnchor = null;
            return cmd;
        }
        if (stuckAnchor == null || me.position().distanceTo(stuckAnchor) > 2.5) {
            stuckAnchor = me.position();
            stuckSince = ticks;
            BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = 40D;
            return cmd;
        }
        // no path at all (in-game: a bridge bastion whose only way in is through netherrack, 10 minutes standing still):
        // the walk-around penalty only suits short detours, so allow digging once we are going nowhere
        if (calcFailed) lastCalcFail = ticks;
        boolean noPath = ticks - lastCalcFail < 100 && baritone.getPathingBehavior().getCurrent() == null;
        if (calcFailed || ticks - stuckSince > 100) BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = 2D;
        // run 104: path calc kept failing, ~20 s per target x 30 targets; a target with no path at all is dropped after 3 s
        // runs 116, 121: an Ascend that needs a block placed where no face can be reached presses nothing, gives up after
        // 4 s, and the replan picks it again. Three seconds without a step on a move that places is a stall already
        boolean placeStall = false;
        if (!noPath && ticks - stuckSince >= 60) {
            try {
                var cur0 = baritone.getPathingBehavior().getCurrent();
                if (cur0 != null) {
                    var mv0 = (baritone.pathing.movement.Movement) cur0.getPath().movements().get(Math.min(cur0.getPosition(), cur0.getPath().movements().size() - 1));
                    mv0.resetBlockCache();
                    placeStall = !mv0.toPlace(new baritone.utils.BlockStateInterface(ctx)).isEmpty();
                }
            } catch (RuntimeException ignoredEx) { }
        }
        if (ticks - stuckSince < (noPath || placeStall ? 60 : 200)) return cmd;
        if ((ticks - stuckSince) % 600 == 599) {
            // the same stale path for 30 s without a single step (run 33: 5 minutes on one movement): drop it outright
            baritone.getPathingBehavior().forceCancel();
            logDirect("Bastion: no progress for " + (ticks - stuckSince) / 20 + " s, force-cancelled the path");
            return cmd;
        }
        // blacklist the cells the current movement keeps trying to reach (and where we stand), then plan again around them
        try {
            var cur = baritone.getPathingBehavior().getCurrent();
            if (cur != null) {
                var mv = cur.getPath().movements().get(Math.min(cur.getPosition(), cur.getPath().movements().size() - 1));
                if ((mv instanceof baritone.pathing.movement.movements.MovementFall || mv instanceof baritone.pathing.movement.movements.MovementDescend)
                        && mv.getSrc().y - mv.getDest().y <= 3 && !lavaNearPos(mv.getDest())) { // run 65: a nudge overshot a ledge, fell 40 into lava
                    nudgeDest = new BlockPos(mv.getDest().x, mv.getDest().y, mv.getDest().z);
                    nudgeUntil = ticks + 20;
                }
                for (var d : new baritone.api.utils.BetterBlockPos[]{mv.getDest(), mv.getSrc()}) {
                    if (d.equals(me.blockPosition())) continue;
                    BlockPos dp = new BlockPos(d.x, d.y, d.z);
                    stuckAvoid.put(dp, ticks + 1500L);
                    stuckAvoid.put(dp.above(), ticks + 1500L);
                }
                status = "stuck on " + mv.getClass().getSimpleName() + " to " + mv.getDest() + ", avoiding it";
                // run 107: 3 min on a Traverse at y=104 while standing at y=100; we were off the path and it never replanned
                if (me.position().distanceTo(Vec3.atBottomCenterOf(mv.getSrc())) > 2.5) { baritone.getPathingBehavior().forceCancel(); status += " (off path, replanned)"; }
                // run 108: 5+ min on the same MovementFall of a "verified drop" (nudge refused: lava near the landing); after the
                // same step stalls 3 times, drop the drop plan and replan from scratch
                BlockPos sd = new BlockPos(mv.getDest().x, mv.getDest().y, mv.getDest().z);
                sameStuck = sd.equals(lastStuckDest) ? sameStuck + 1 : 1;
                lastStuckDest = sd;
                if (sameStuck >= 3) {
                    dropPlan = null; dropScan = ticks + 1200;
                    stuckAvoid.put(sd, ticks + 6000L); stuckAvoid.put(sd.above(), ticks + 6000L);
                    baritone.getPathingBehavior().forceCancel();
                    sameStuck = 0;
                    status += " (same step 3x: dropped the drop plan, replanned)";
                }
            }
        } catch (RuntimeException ignoredEx) { }
        // a drop we cannot reach (run 14: 2 minutes on one crying obsidian): forget that item at once
        if (status != null && status.startsWith("loot") && lootTarget >= 0) badLoot.add(lootTarget);
        String gk = String.valueOf(cmd.goal);
        int times = stuckCount.merge(gk, 1, Integer::sum);
        if (times >= (noPath ? 1 : 2)) {
            // the same target twice: it is the target, not the step. Give it up for a while and let the plan pick the next one
            if (chestTarget != null) { badChest.put(chestTarget, ticks + 3000); chestTarget = null; }
            if (goldTarget != null) { badGold.put(goldTarget, ticks + 3000); goldTarget = null; }
            if (dropPlan != null) { dropPlan = null; dropScan = ticks + 400; }
            stuckCount.remove(gk);
            status += "; skipping target " + gk;
            // run 119: the search explored 226k nodes and still found no path, because every way on cost more than it will
            // pay per block gained. Our edge costs are a preference; with no path at all they stand down for a while
            if (noPath && ticks >= relaxUntil) { relaxUntil = ticks + 600; status += " (no path: edge costs off for 30 s)"; }
        }
        logDirect("Bastion: " + status);
        unstucks++;
        stuckAnchor = null;
        // a cancel from inside our own tick trips the control manager; cancelling through the command drops the old path; next tick plans fresh around the avoid set
        return new PathingCommand(cmd.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tick0(boolean calcFailed, boolean isSafeToCancel) {
        Player me = ctx.player();
        ticks++;
        baritone.getInputOverrideHandler().clearAllKeys();
        // the use key (shield, food) is only held by whoever needs it this tick; PveProcess leaves it down when we stop calling it
        ctx.minecraft().options.keyUse.setDown(false);

        PathingCommand wp = waterPocket(me);
        if (wp != null) return wp;
        // r112: burned 12 -> 0 while "exiting" from brutes, apples never eaten (the exit ran first); cure before everything
        if (me.isAlive() && me.level().dimension().identifier().getPath().equals("the_nether")) {
            PathingCommand cure = fireCure(me);
            if (cure != null) return cure;
        }
        // Bastions only exist in the nether; after a death the respawn lands in the overworld, so never walk there.
        if (!me.isAlive() || !me.level().dimension().identifier().getPath().equals("the_nether")) {
            if (fighting) baritone.getPveProcess().clearEnemies();
            fighting = false;
            bastion = null;
            status = "not in the nether";
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        haveGold = ingotCount(me) >= 1;
        PathingCommand clutch = clutch(me);
        if (clutch != null) return clutch;        // hurt: never take even a 3-block drop into a bastion pit
        BaritoneAPI.getSettings().maxFallHeightNoWater.value = me.getHealth() < BastionSettings.lowHealth ? Math.min(2, BastionSettings.maxSafeDrop) : BastionSettings.maxSafeDrop;
        // 0. A brute swings for ~9, so by the time we are hurt it is too late to run. As soon as one is coming for us, pillar up
        // two blocks (gravel/soul sand alternate): its attack box ends at its head, ours still reaches it, so we fight it from above.
        List<LivingEntity> heavies = ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(THREAT_RANGE),
                e -> e != me && (e instanceof PiglinBrute || e instanceof Zoglin) && e.isAlive() && (perching || !otherLevel(e, me))
                        // ignored means out of reach from where we stood: one within 8 on our own level is in reach again (run 118)
                        && !(ignored.getOrDefault(e.getUUID(), 0L) > ticks && !(e instanceof Mob m && me.distanceTo(e) < 8 && (m.isAggressive() || Math.abs(e.getY() - me.getY()) <= 3.5))));
        boolean heavyComing = heavies.stream().anyMatch(e -> me.distanceTo(e) < 8 || me.distanceTo(e) < 12 && e instanceof Mob m && m.isAggressive());
        PveProcess pveP = baritone.getPveProcess();
        // runs 100-131: of fourteen first hits from a brute, eleven landed while we stood at a tower half built, on one
        // it could reach from a ledge, or clicking at a tunnel wall; and in nine towers the brute at the foot never once
        // gave up. Out of its sight it does. With the blocks for it, wall in before anything else
        // user-chosen brute tactics (lava bucket / sealed pit) come before walls and towers; null when they do not apply
        PathingCommand btc = bruteTactic(me, heavies);
        if (btc != null) return btc;
        PathingCommand sh = shelter(me);
        if (sh != null) return sh;
        // run 133: between two ticks of waiting to land, a tower was begun and a tunnel wall clicked at; the tower was
        // climbed, the walls were then ruled out for being up a tower, and the brute hit us twice on top of it
        // (20 -> 10 -> 0.4). With the blocks for walls in hand, towers and tunnel walls are not started
        boolean wallsToHand = !me.isInLava() && ticks >= shelterCooldown && pillarBlocks(me) >= 12;
        // a brute hits for ~6-13 through gold armour: low on health with one close, a speedrunner leaves with what they have
        // (in-game run 13 fought, perched and retreated at 12 hp and died in the tower)
        // up a tower with a brute below: stay up there; walking down to leave is what killed run 47
        if (bastion != null && me.onGround() && me.getY() >= perchBase + 1.9 && Math.abs(me.getX() - perchX - 0.5) < 1 && Math.abs(me.getZ() - perchZ - 0.5) < 1
                && ticks >= towerReleaseUntil
                // run 130: this caught the climb two blocks up, where the tower is only half built, and held us there
                // "waiting" until the brute walked up and hit us (18 -> 8). The climb finishes first
                && !(perching && perchClimbing)
                // run 122: one brute idled 5-9 blocks off, out of sight, and we stood on the tower at full health for five
                // minutes. Only a brute that is after us or can see us keeps us up there, and for half a minute at a stretch:
                // one that has not come to the foot of the tower by then cannot get to us
                && heavies.stream().anyMatch(e -> e instanceof PiglinBrute b && me.distanceTo(e) < 10 && (b.isAggressive() || b.hasLineOfSight(me)))) {
            if (ticks - towerWaitTick > 20) towerWaitSince = ticks;
            towerWaitTick = ticks;
            // run 127: two brutes stood two and three blocks off for thirteen seconds, on a ledge level with the tower top
            // and a gap between. The wait ran out, we stepped down between them and were hit. One that has stood within
            // our reach for five seconds without landing a blow cannot reach us; we can reach it, which is what the tower
            // is for. And with one that close, coming down is not the way out: wait longer
            PathingCommand strike = ticks - towerWaitSince >= 100 ? towerStrike(me, heavies) : null;
            if (strike != null) return strike;
            boolean close = heavies.stream().anyMatch(e -> e instanceof PiglinBrute && me.distanceTo(e) < 5);
            if (ticks - towerWaitSince < (close ? 1200 : 600)) {
                pveP.hold = false;
                // run 130: a minute up here at 10 hp, the food bar a point short of regenerating
                if (me.getHealth() < 17 && me.getFoodData().getFoodLevel() < 18) {
                    PathingCommand eat = eat(me);
                    if (eat != null) return eat;
                }
                status = "on tower, waiting out the brute";
                return pause0();
            }
            towerReleaseUntil = ticks + 600;
            perchCooldown = Math.max(perchCooldown, towerReleaseUntil);
        }
        // brute axes disable shields (runs 98, 106): no duel unless asked for
        PathingCommand dl = BastionSettings.duel ? duel(me, heavies) : null;
        if (dl != null) return dl;
        // kite brutes: an aggressive one within 8, or any within 8 when below 16 hp, means walk away from it now
        LivingEntity brute = null;
        // one behind a wall that has not noticed us is left alone: running from it is what gets us seen
        for (LivingEntity e : heavies) if (e instanceof PiglinBrute && me.distanceTo(e) < 12 && (hunting(e, me) || me.getHealth() < 16 && me.distanceTo(e) < 5) && (brute == null || me.distanceTo(e) < me.distanceTo(brute))) brute = e;
        // a tower beats running (run 26, and runs 116, 117 where leaving on foot from a pack ended in two axe hits)
        boolean canTower = brute != null && !perching && !wallsToHand && me.distanceTo(brute) >= 4.5 && !me.isInLava() && !lavaWithin(me, 3) && headroom(me, (int) Math.floor(me.getY() + 0.2)) && pillarBlocks(me) >= 3 && ticks > perchCooldown;
        long pack = heavies.stream().filter(e -> e instanceof PiglinBrute && me.distanceTo(e) < 16).count();
        // run 106: 9 brutes in a stables, hit down to 9 hp before leaving; leave on a pack, or hurt with one coming
        // runs 116, 117: a tower begun a moment earlier was dropped to leave on foot with a brute 5 blocks behind, and both
        // times the brutes won the race (two hits kill). A tower is out of reach in under two seconds: let it get up, and
        // only leave from the ground when it has had three seconds and is still low
        boolean towerFailed = perching && me.getY() < perchBase + 1.9 && ticks - perchTick > 60;
        if (bastion != null && !exiting && !canTower && (!perching || towerFailed) && (pack >= 3 && me.onGround() && me.getHealth() < 16 /* runs 117, 119, 128: left for good at full health, with nothing */ || me.getHealth() < BastionSettings.lowHealth && heavies.stream().anyMatch(e -> me.distanceTo(e) < 12) || me.getHealth() < 16 && heavyComing)) {
            logDirect("Bastion: brute close at " + (int) me.getHealth() + " hp (" + pack + " within 16), leaving");
            pveP.hold = false;
            perching = false;
            if (fighting) pveP.clearEnemies();
            fighting = false;
            return exit(me, counts(me));
        }
        // run 121: on the way out a brute closed from 11 blocks to 1 over four seconds while the exit path stood stalled,
        // and nothing but the exit was allowed to act. Leaving does not outrank the tower
        // r147: on the way out the exit path dug into lava at y87 and this early return skipped the lava walk-out; burned 20 -> 0 in 4 s
        if (exiting && bastion != null && !canTower && !perching && !me.isInLava() && !me.isOnFire()) return exit(me, counts(me));
        if (canTower) {
            // kiting on foot loses (run 26: a brute is as fast as us, 19 -> 9 hp in 4 s); three blocks up it cannot reach us
            if (fighting) { pveP.clearEnemies(); fighting = false; }
            perching = true;
            perchUp = false;
            perchCapped = false;
            perchBase = me.blockPosition().getY();
            perchX = me.blockPosition().getX();
            perchZ = me.blockPosition().getZ();
            perchTick = ticks;
            status = "brute at " + String.format("%.1f", me.distanceTo(brute)) + ", towering";
            return pause0();
        }
        if (brute != null && !perching && !wallsToHand && !headroom(me, (int) Math.floor(me.getY() + 0.2)) && pillarBlocks(me) >= 2) {
            // a tunnel (run 29: dug into the netherrack, no room to tower): wall it off, brutes do not dig
            // run 118: the side facing the brute was solid, we called that walled off and stood still while it walked round
            // and hit us. Brutes do not dig but they do walk round: close the open side nearest the brute first, and
            // only a cell shut on all four sides is walled off
            // runs 124, 125: clicked at one cell for a second and more, it never closed, and we stood there until the brute
            // arrived. A cell that will not take a block (nothing under it to place against, someone standing in it, clicks
            // that do nothing) is passed over, and with only such cells left open we do not stand and wait
            BlockPos feet = me.blockPosition(), cell = null;
            double facing = -Double.MAX_VALUE;
            boolean open = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c0 = feet.relative(d), c1 = c0.above();
                BlockPos c = ctx.world().getBlockState(c0).canBeReplaced() ? c0 : ctx.world().getBlockState(c1).canBeReplaced() ? c1 : null;
                if (c == null) continue;
                open = true;
                if (ctx.world().getBlockState(c.below()).canBeReplaced() || wallFailed.getOrDefault(c, 0L) > ticks
                        || !ctx.world().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(c)).isEmpty()) continue;
                double f = d.getStepX() * (brute.getX() - me.getX()) + d.getStepZ() * (brute.getZ() - me.getZ());
                if (f > facing) { facing = f; cell = c; }
            }
            if (open) walledSince = 0;
            if (cell != null) {
                int b = clutchBlock(me);
                if (b >= 0 && b < 9) {
                    me.getInventory().setSelectedSlot(b);
                    BlockPos against = cell.below();
                    if (!cell.equals(wallCell)) { wallCell = cell.immutable(); wallTries = 0; }
                    if (++wallTries > 8) wallFailed.put(wallCell, ticks + 100L);
                    Vec3 hit = Vec3.atCenterOf(against).add(0, 0.5, 0);
                    baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), hit, ctx.playerRotations()), true);
                    boolean opens = clickOpens(against);
                    if (opens) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    if (!opens || me.isShiftKeyDown()) ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, against, false));
                    status = "walling off brute in tunnel (" + cell.toShortString() + " try " + wallTries + ")";
                    return pause0();
                } else if (b >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, b, 6, ContainerInput.SWAP, me); return pause0(); }
            } else if (!open) {
                // a brute that cannot reach us forgets us in ten seconds; past that, sitting in the cell is only lost time
                if (walledSince == 0) walledSince = ticks;
                if (ticks - walledSince < 300) {
                    status = "walled off from brute";
                    return pause0();
                }
            }
        }
        // with walls to hand, one that looks and does not come is left to look: the walls go up when it moves
        if (brute != null && !perching && !wallsToHand) {
            pveP.hold = false;
            if (fighting) { pveP.clearEnemies(); fighting = false; }
            Vec3 away = me.position().subtract(brute.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
            evadeUntil = 0; // the evade step fought this one tick for tick in run 28
            status = "kiting brute at " + String.format("%.1f", me.distanceTo(brute));
            // drops a path that ends near the brute instead of finishing it first; one that already runs clear is kept
            return new PathingCommand(new GoalRunAway(14, brute.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }        if (bastion != null && startTick > 0 && ticks - startTick > BastionSettings.timeBudget * 20L) {
            // a runner time-boxes the bastion: past the budget, leave with what we have
            logDirect("Bastion: time budget (" + BastionSettings.timeBudget + " s) used, leaving");
            return exit(me, counts(me));
        }
        // the clock starts on arrival, not detection (run 45 spent 3 of its 5 minutes on the approach)
        if (bastion != null && startTick == 0 && Math.hypot(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ()) < 40) startTick = Math.max(1, ticks);
        if (!perching && !wallsToHand && !me.isInLava() && !me.isOnFire() && !lavaWithin(me, 3) && ticks >= perchCooldown && heavyComing && pillarBlocks(me) >= 4 && me.onGround() && headroom(me, me.blockPosition().getY())) {
            perching = true;
            perchUp = false;
            perchCapped = false;
            perchBase = me.blockPosition().getY();
            perchX = me.blockPosition().getX();
            perchZ = me.blockPosition().getZ();
            perchTick = ticks;
        }
        if (perching && (me.isInLava() || me.isOnFire())) perchCooldown = ticks + 100;
        if (perching && (me.isInLava() || me.isOnFire() || heavies.isEmpty() && lavaPos == null || ticks - perchTick > 1500 || me.getY() < perchBase + 1.7 && pillarBlocks(me) < 1)) {
            perching = false;
            pveP.hold = false;
            if (fighting) pveP.clearEnemies();
            fighting = false;
        }
        if (perching) {
            // hysteresis: climb to base+2.3, then only climb again if we drop below base+1.2 (soul sand is 0.875 high)
            // judged only when settled: mid-jump height says nothing about where we land (a brute's attack box ends ~1.95 up)
            if (!perchUp) perchClimbing = true;
            else if (me.onGround() && !perchCapped) perchClimbing = me.getY() < perchBase + 3.7;
            perchUp = true;
            // run 123: the path carried us nine blocks on and two down before we stopped. The tower went up there, measured
            // from here: too short, and the lava trap looked for its column in the wrong place. The tower is where we stand
            BlockPos stand = BlockPos.containing(me.getX(), me.getY() - 0.01, me.getZ());
            if (perchClimbing && me.onGround() && (me.blockPosition().getX() != perchX || me.blockPosition().getZ() != perchZ)
                    && !ctx.world().getBlockState(stand).getCollisionShape(ctx.world(), stand).isEmpty()) {
                perchBase = me.blockPosition().getY();
                perchX = me.blockPosition().getX();
                perchZ = me.blockPosition().getZ();
            }
            if (perchClimbing && !headroom(me, perchBase) && me.getY() < perchBase + 2.2) {
                // a ceiling stops the tower short: standing still on the floor is the worst place to meet a brute, so give up
                perching = false;
                perchCooldown = ticks + 200;
                pveP.hold = false;
                if (fighting) pveP.clearEnemies();
                fighting = false;
                return pause0();
            }
            if (perchClimbing && me.onGround() && me.getY() >= perchBase + 1.9 && !ctx.world().getBlockState(me.blockPosition().above(2)).getCollisionShape(ctx.world(), me.blockPosition().above(2)).isEmpty()) {
                // a ceiling stopped the tower above the floor: this is as high as it goes, fight from here instead of pressing on into it
                perchClimbing = false;
                perchCapped = true;
            }
            if (perchClimbing) {
                status = "perch: climbing";
                return tower(me);
            }
            status = "perch: fighting from above";
            if (!fighting) {
                fighting = true;
                stallKey = null;
                pveP.attack(e -> e instanceof PiglinBrute || e instanceof Zoglin, "brutes from the perch");
                pveP.driven = true;
            }
            // Brutes that stay out of reach (across a gap, behind a wall) are no threat: when nothing changes for a while, get on with the job
            float perchTotal = me.getHealth();
            for (LivingEntity t : heavies) perchTotal += t.getHealth();
            String perchKey = perchTotal + "/" + lavaPos;
            if (!perchKey.equals(perchStallKey)) {
                perchStallKey = perchKey;
                perchStallSince = ticks;
            } else if (ticks - perchStallSince > 200 && lavaPos == null) {
                for (LivingEntity t : heavies) ignored.put(t.getUUID(), ticks + 600);
                perchCooldown = ticks + 600;
                perchStallKey = null;
                perching = false;
                pveP.hold = false;
                pveP.clearEnemies();
                fighting = false;
                status = "ignoring brutes that cannot reach us";
                return pause0();
            }
            PathingCommand lava = lavaTrap(me, heavies);
            if (lava != null) return lava;
            // run 123: ten seconds up here at 8 hp with the food bar at 16, so nothing came back, then down to the brute.
            // Out of its reach is the time to eat
            if (me.getHealth() < 17 && me.getFoodData().getFoodLevel() < 18 && lavaPos == null) {
                PathingCommand eat = eat(me);
                if (eat != null) return eat;
            }
            pveP.hold = true;
            // sneaking at the edge of a one-block column: a brute's knockback cannot push us off
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            return pveP.onTick(calcFailed, isSafeToCancel);
        }

        // standing in our door pocket: the lava cannot reach us, so wait out the fire here instead of walking back into it
        if (doorPos != null) {
            boolean inDoor = ctx.world().getBlockState(doorPos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                    && me.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(doorPos).expandTowards(0, 1, 0));
            if (inDoor && descentSteps == 0) planDescent(me);
            // a 0.6-wide body off the cell centre still touches the lava next to the door (in-game: isInLava the whole time)
            Vec3 mid = Vec3.atBottomCenterOf(doorPos);
            double ox = mid.x - me.getX(), oz = mid.z - me.getZ();
            if (Math.hypot(ox, oz) > 0.12) {
                baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-ox, oz)), me.getXRot()), true);
                me.setDeltaMovement(ox * 0.4, me.getDeltaMovement().y, oz * 0.4);
            }
            // mid-descent the old door breaks with the block under it, so this runs whether or not we are in a door right now
            if (descentSteps > 0 && ticks - doorSince > 300) { descentSteps = -1; logDirect("Bastion: door descent stalled, normal escape"); }
            if (descentSteps > 0) {
                PathingCommand d = descend(me);
                if (d != null) return d;
            }
            if (inDoor && (me.isOnFire() || ticks < doorSince + 40)) {
                // the fire lasts ~15 s after the lava (about 1 hp a second): a golden apple's regeneration outlasts it
                int gap = -1;
                for (int i = 0; i < 36 && gap < 0; i++) if (me.getInventory().getItem(i).is(Items.GOLDEN_APPLE) || me.getInventory().getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) gap = i;
                if (gap >= 0 && me.getHealth() < 12 && !me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) {
                    if (gap >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, gap, 6, ContainerInput.SWAP, me); return pause0(); }
                    me.getInventory().setSelectedSlot(gap);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    ctx.minecraft().options.keyUse.setDown(true);
                    status = "in door pocket, eating a golden apple against the fire";
                    return pause0();
                }
                status = "in door pocket, waiting for the fire to go out";
                return pause0();
            }
            if ((!inDoor || !me.isOnFire()) && descentSteps <= 0) { doorPos = null; descentSteps = 0; }
        }
        if (throwPred != null && ticks == throwTick + 25) {
            final double[] tp = throwPred; throwPred = null;
            if (throwTarget != null) {
                // ingots that come back into our own column hit the shared pit corner: lob higher next time (ThrowAdapt)
                final BlockPos tgt = throwTarget; throwTarget = null; final BlockPos ours = me.blockPosition();
                ctx.world().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, me.getBoundingBox().inflate(6), it -> it.getItem().is(Items.GOLD_INGOT))
                        .stream().min(java.util.Comparator.comparingDouble(it -> it.distanceToSqr(tp[0], tp[1], tp[2])))
                        .ifPresent(it -> throwAdapt.landed(it.getBlockX(), it.getBlockZ(), ours.getX(), ours.getZ(), tgt.getX(), tgt.getZ()));
            }
            ctx.world().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, me.getBoundingBox().inflate(6), it -> it.getItem().is(Items.GOLD_INGOT))
                .stream().min(java.util.Comparator.comparingDouble(it -> it.distanceToSqr(tp[0], tp[1], tp[2])))
                .ifPresentOrElse(it -> logDirect(String.format("Bastion: throw landing predicted %.2f %.2f %.2f, actual %.2f %.2f %.2f (err %.2f)", tp[0], tp[1], tp[2], it.getX(), it.getY(), it.getZ(), Math.sqrt(it.distanceToSqr(tp[0], tp[1], tp[2])))),
                    () -> logDirect(String.format("Bastion: throw landing predicted %.2f %.2f %.2f, ingot already taken", tp[0], tp[1], tp[2])));
        }

        // run 102: lava flowed over the spawn floor (1 deep, on the ground) and the bot stood still for 3 s; walk straight out to the nearest dry cell
        // run 111: 1-deep lava on netherrack, onGround flickered while jumping and pillar-out took over and failed: shallow = solid under the feet cell
        if (me.isInLava() && (me.onGround() || floor(me.blockPosition().below()) && !realLava(me.blockPosition().above()))) {
            BlockPos dry = null;
            for (BlockPos q : BlockPos.betweenClosed(me.blockPosition().offset(-3, -1, -3), me.blockPosition().offset(3, 1, 3))) {
                if (realLava(q) || realLava(q.above()) || !floor(q.below()) || floor(q) || floor(q.above())) continue;
                if (dry == null || me.position().distanceToSqr(Vec3.atBottomCenterOf(q)) < me.position().distanceToSqr(Vec3.atBottomCenterOf(dry))) dry = q.immutable();
            }
            if (dry != null) {
                Vec3 d = Vec3.atBottomCenterOf(dry).subtract(me.position());
                baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-d.x, d.z)), 0), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                if (dry.getY() > me.getY() + 0.5 || me.horizontalCollision) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                status = "shallow lava: walking out to " + dry.toShortString();
                return pause0();
            }
        }
        if (me.isInLava()) {
            // in lava: no planning, just step directly away from the nearest lava and jump (a path from here may never come)
            Vec3 away = Vec3.ZERO;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                BlockPos lp = me.blockPosition().offset(dx, dy, dz);
                if (ctx.world().getFluidState(lp).is(net.minecraft.tags.FluidTags.LAVA)) away = away.add(me.position().subtract(Vec3.atCenterOf(lp)));
            }
            // better than "away from the lava we see": head for the nearest cell we can actually stand on (in-game, the
            // away vector pointed back into a pool and we burned to death 1 block from the shore)
            BlockPos shore = null;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
                BlockPos c = me.blockPosition().offset(dx, dy, dz);
                if (!floor(c.below()) || floor(c) || floor(c.above()) || lavaAt(c) || lavaAt(c.above()) || lavaAt(c.below())) continue;
                if (shore == null || me.blockPosition().distSqr(c) < me.blockPosition().distSqr(shore)) shore = c;
            }
            if (shore != null) away = Vec3.atBottomCenterOf(shore).subtract(me.position());
            PathingCommand door = doorPocket(me, shore);
            if (door != null) return door;
            // run 76: bobbed 3 s at a shore it could not climb; after 1.5 s in lava, pillar up out of it instead
            if (lavaSince >= 0 && ticks - lavaSince > 30 && pillarBlocks(me) > 0 && solid(me.blockPosition().below()) /* run 79: nothing to place against in deep lava, it sank */ && headroom(me, (int) Math.floor(me.getY() + 0.2))) {
                status = "lava: pillar out";
                return tower(me);
            }
            if (away.horizontalDistanceSqr() > 0.01) {
                baritone.getPveProcess().hold = false;
                float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
                baritone.getLookBehavior().updateTarget(new Rotation(yaw, 10), true);
                // run 69: pinned against a wall under a ceiling (head=blackstone, vc=true) and burned 17 -> 0; dig through the way out
                if (me.horizontalCollision) {
                    Direction fd = Direction.getApproximateNearest(away.x, 0, away.z);
                    BlockPos fr = me.blockPosition().relative(fd);
                    for (BlockPos w : new BlockPos[]{fr.above(), fr, me.blockPosition().above()}) {
                        if (!solid(w) || lavaNearPos(w) && lavaAt(w.above())) continue;
                        int pk = pickaxeSlot(me);
                        if (pk >= 0 && pk < 9) me.getInventory().setSelectedSlot(pk);
                        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(w), ctx.playerRotations()), true);
                        if (!w.equals(holeTarget)) { holeTarget = w; ctx.playerController().clickBlock(w, fd.getOpposite()); }
                        else ctx.playerController().onPlayerDamageBlock(w, fd.getOpposite());
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                        status = "lava: digging out";
                        return pause0();
                    }
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                // holding jump keeps us bobbing at the surface where no door cell is found (lava8-10): with a door, sink instead
                boolean haveDoor = false;
                for (int i = 0; i < 36 && !haveDoor; i++) haveDoor = me.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.WOODEN_DOORS);
                if (!haveDoor || shore != null && me.position().distanceTo(Vec3.atBottomCenterOf(shore)) < 1.6) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                status = "step away from lava";
                return pause0();
            }
        }
        if (me.isOnFire() && !me.isInLava() && !perching && lavaNear(me)) {
            Vec3 away = Vec3.ZERO;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                BlockPos lp = me.blockPosition().offset(dx, dy, dz);
                if (realLava(lp)) away = away.add(me.position().subtract(Vec3.atCenterOf(lp)));
            }
            // run 100: pressed into a tunnel wall for 4 s while burning (vc=true) and died; path to a clear cell instead
            // run 112: lava on two sides, the summed "away" pointed at a lava fall a block on and it walked in: the ground
            // ahead must be dry and there, or we path to a clear cell as well
            boolean wetAhead = false;
            if (away.horizontalDistanceSqr() > 0.01) {
                Vec3 n = new Vec3(away.x, 0, away.z).normalize();
                for (double step : new double[]{0.6, 1.2}) {
                    BlockPos a = BlockPos.containing(me.getX() + n.x * step, me.getY() + 0.2, me.getZ() + n.z * step);
                    wetAhead |= lavaAt(a) || lavaAt(a.above()) || lavaAt(a.below()) || !floor(a.below());
                }
            }
            if (me.horizontalCollision || wetAhead) {
                BlockPos safe = null;
                for (BlockPos q : BlockPos.betweenClosed(me.blockPosition().offset(-4, -1, -4), me.blockPosition().offset(4, 1, 4))) {
                    if (!floor(q.below()) || floor(q) || floor(q.above()) || lavaNearPos(q)) continue;
                    if (safe == null || me.blockPosition().distSqr(q) < me.blockPosition().distSqr(safe)) safe = q.immutable();
                }
                if (safe != null) { status = "burning, pathing clear to " + safe.toShortString(); return new PathingCommand(new GoalBlock(safe), PathingCommandType.SET_GOAL_AND_PATH); }
                away = Vec3.ZERO;
            }
            if (away.horizontalDistanceSqr() > 0.01) {
                baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-away.x, away.z)), 10), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                status = "burning, stepping clear of the lava";
                return pause0();
            }
        }
        if (!me.isOnFire()) burnSince = -1; else if (burnSince < 0) burnSince = ticks;
        // run 37 stood still for 10 s and burned 20 -> 0 (something kept relighting it): stand at most 2 s, then carry on
        // run 53 stood in a fire block and burned 20 -> 0: punch out fire under or next to us first
        if (me.isOnFire() && !me.isInLava()) {
            BlockPos f0 = me.blockPosition();
            for (BlockPos f : new BlockPos[]{f0, f0.north(), f0.south(), f0.east(), f0.west()}) {
                if (ctx.world().getBlockState(f).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                    // run 67: punching the block under the fire did nothing and it burned to death in place; punch the fire itself, then walk out
                    if (burnSince >= 0 && ticks - burnSince > 10) {
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            BlockPos q = f0.relative(d);
                            if (ctx.world().getBlockState(q).isAir() && ctx.world().getBlockState(q.above()).isAir() && solid(q.below()) && !lavaNearPos(q)) {
                                status = "walking out of fire";
                                return new PathingCommand(new GoalBlock(q), PathingCommandType.SET_GOAL_AND_PATH);
                            }
                        }
                    }
                    baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(f), ctx.playerRotations()), true);
                    ctx.playerController().clickBlock(f, net.minecraft.core.Direction.UP);
                    me.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    status = "putting out fire";
                    return pause0();
                }
            }
        }
        if (me.isOnFire() && !me.isInLava() && !perching && ticks - burnSince < 40) {
            // burning but clear of the lava: running a long path (with drops) at half health kills more than the flames do
            baritone.getPveProcess().hold = false;
            status = "burning, standing still until it goes out";
            return pause0();
        }
        if ((me.isInLava() || me.isOnFire()) && safeSpot != null) {
            baritone.getPveProcess().hold = false;
            status = "escape fire to safe spot";
            return new PathingCommand(new GoalNear(safeSpot, 1), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Remember where a tower could be built and no brute was near; when hurt with a brute on us and no tower possible, run back there.
        if (me.onGround() && headroom(me, me.blockPosition().getY()) && heavies.stream().noneMatch(e -> me.distanceTo(e) < 16)) safeSpot = me.blockPosition().immutable();
        long close = heavies.stream().filter(e -> me.distanceTo(e) < 9).count();
        if (!perching && safeSpot != null && me.blockPosition().distSqr(safeSpot) > 9 && (close >= 2 || me.getHealth() < 16 && close >= 1)) {
            baritone.getPveProcess().hold = false;
            status = "retreat to safe spot";
            return new PathingCommand(new GoalNear(safeSpot, 1), PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (ticks < evadeUntil && evadeGoal != null && me.blockPosition().distSqr(evadeGoal) > 16) {
            status = "move out of range of unreachable attackers";
            return new PathingCommand(new GoalNear(evadeGoal, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // 1. Anything that will not leave us alone goes to the fighter.
        boolean early = layout().equals("stables");
        List<LivingEntity> near = ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(SEEN_RANGE), e -> e != me);
        if (near.stream().anyMatch(e -> threat(e, me, early))) {
            List<LivingEntity> th0 = near.stream().filter(e -> threat(e, me, early)).toList();
            if (th0.stream().allMatch(e -> e instanceof Piglin)) {
                PathingCommand sk = skirmish(me, th0);
                if (sk != null) return sk;
            }
            PveProcess pve = baritone.getPveProcess();
            if (!fighting) {
                fighting = true;
                stallKey = null;
                pve.attack(e -> threat(e, me, early), "bastion threats");
                pve.driven = true;
            }
            // Watchdog: if neither we, nor our position, nor the threats' health change for 100 ticks the fight is going
            // nowhere (mob behind a wall, in a pit, unreachable). Ignore those mobs for a while and get on with the job.
            // (our own health is not part of the key: shooters we cannot reach hurt us while nothing else changes)
            float total = 0;
            List<LivingEntity> threats = near.stream().filter(e -> threat(e, me, early)).toList();
            for (LivingEntity t : threats) total += t.getHealth();
            String key = me.blockPosition().toShortString() + "/" + total;
            if (!key.equals(stallKey)) {
                stallKey = key;
                stallSince = ticks;
            } else if (ticks - stallSince > 100) {
                Vec3 away = Vec3.ZERO;
                for (LivingEntity t : threats) {
                    ignored.put(t.getUUID(), ticks + 600);
                    away = away.add(me.position().subtract(t.position()));
                }
                pve.clearEnemies();
                fighting = false;
                stallKey = null;
                if (away.horizontalDistanceSqr() > 0.01) {
                    Vec3 d = away.multiply(1, 0, 1).normalize().scale(16);
                    evadeGoal = BlockPos.containing(me.position().add(d));
                    evadeUntil = ticks + 100;
                }
                status = "ignoring unreachable threats";
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            status = "fight";
            return pve.onTick(calcFailed, isSafeToCancel);
        }
        if (fighting) {
            fighting = false;
            baritone.getPveProcess().clearEnemies();
        }

        // 1b. An angry piglin forgets us when it is given gold: drop an ingot at its feet.
        if (haveGold) {
            Piglin angry = null;
            for (LivingEntity e : near) {
                if (e instanceof Piglin p && !p.isBaby() && p.isAlive() && p.isAggressive() && me.distanceTo(p) < 12 && distractTries.getOrDefault(p.getUUID(), 0) < 2 && me.getHealth() >= 14 && (angry == null || me.distanceTo(p) < me.distanceTo(angry))) angry = p;
            }
            if (angry != null) {
                PathingCommand d = distract(me, angry);
                if (d != null) return d;
            }
        }

        // 2. Find the bastion we are heading for.
        List<DetectedStructure> known = baritone.getStructureBehavior().find(query);
        known.removeIf(d -> !d.dimension.equals("the_nether"));
        if (!known.isEmpty()) bastion = known.get(0);
        List<Piglin> piglins = new ArrayList<>();
        for (LivingEntity e : near) if (e instanceof Piglin p && !p.isBaby() && !admiring.containsKey(p.getUUID())) piglins.add(p); // babies never barter
        piglins.sort((a, b) -> Double.compare(me.distanceToSqr(a), me.distanceToSqr(b)));
        ItemEntity loot = lootNear(me);

        // 2b. No gold to trade: mine a gold block (never with piglins about) and craft it into ingots.
        // never walk into the bastion hurt: regenerate first (natural regeneration with a full food bar)
        if (me.getHealth() >= 17) healSince = ticks;
        if (me.getHealth() < 17 && (me.getFoodData().getFoodLevel() >= 18 || ticks - healSince < 1200)) {
            if (me.getFoodData().getFoodLevel() >= 18) {
                status = "recover";
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // regeneration needs a full food bar: eat until it is
            PathingCommand eat = eat(me);
            if (eat != null) return eat;
        }
        Map<String, Integer> have = counts(me);
        boolean done = BastionGoals.met(have, BastionSettings.TARGETS);
        if (bastion != null && (exiting || done && BastionSettings.exitWhenDone)) return exit(me, have);

        // 2a. Chests in plan order for this layout.
        if (bastion != null) {
            // run 86: ran out of ingots with 3 gold blocks left; the camp returns every tick, so split blocks before it
                if (craftStep > 0 || countOf(me, Items.GOLD_BLOCK) > 0 && ingotCount(me) < 2) { PathingCommand cr = craftGold(me); if (cr != null) return cr; }
                // the route loots and builds first and trades at its trade step (r143: the camp took the whole run, no route step ran);
                // after 4 minutes on the route the camp is allowed anyway
                boolean routeTrades = !BastionSettings.scriptedRoutes || route.isEmpty() || routeIdx >= BastionRoutes.firstTradeIndex(route) || ticks - routeStart > 4800;
                PathingCommand camp = routeTrades ? tradeCamp(me, piglins, have, done) : null;
            if (camp != null) return camp;
            // trades first: a free piglin in reach gets an ingot before the next chest (run 36: 3 min on chests, 4 throws)
            PathingCommand bt0 = barterOnTheWay(me, near);
            if (bt0 != null) return bt0;
            PathingCommand rs = runRoute(me, near);
            if (rs != null) return rs;
            PathingCommand c = lootChests(me, near);
            if (c != null) return c;
        }
        // gold bookkeeping: blocks count nine ingots (split in the 2x2 grid when the ingots run low), nuggets only count
        int blocks = countOf(me, Items.GOLD_BLOCK), throwable = BastionGoals.throwable(ingotCount(me), blocks);
        if (craftStep > 0 || blocks > 0 && ingotCount(me) < 2) {
            PathingCommand craft = craftGold(me);
            if (craft != null) return craft;
        }
        // nearly out: mine the nearest safe gold block (the plan above already fetches planned gold blocks when more is needed)
        if (!done && throwable < 2) {
            PathingCommand acquire = acquireGold(me, near);
            if (acquire != null) { goldSourceGone = false; return acquire; }
            goldSourceGone = true; // no pickaxe or no gold block left in range
        }

        if (piglins.isEmpty() && loot == null && admiring.isEmpty()) {
            if (bastion == null) {
                status = "searching: no " + query + " known";
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (outOfEverything(me)) return exit(me, have);
            // no way through without breaking rock: the high penalty only suits short hops, so drop it when we are going nowhere
            String ak = me.blockPosition().toShortString();
            if (!ak.equals(approachKey)) { approachKey = ak; approachSince = ticks; }
            BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = ticks - approachSince > 300 ? 2D : 40D;
            if (Math.hypot(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ()) > 48) entry = me.blockPosition().immutable();
            status = "approach " + layout();
            return new PathingCommand(new GoalXZ(bastion.pos.getX(), bastion.pos.getZ()), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // 3. Gold armour first, so calm piglins stay calm.
        if (!wearingGold(me) && wearGold(me)) {
            status = "equip gold";
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 4. Pick up useful barter drops (junk is left lying).
        if (loot != null) {
            status = "loot " + itemId(loot.getItem());
            lootTarget = loot.getId();
            return new PathingCommand(new GoalNear(loot.blockPosition(), 0), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // 5. Several piglins admire at once: keep throwing to the best-placed calm one until enough are busy or the targets are covered.
        admiring.values().removeIf(t -> ticks - t > ADMIRE_TICKS);
        int ingots = ingotCount(me);
        if (!BastionGoals.shouldThrow(have, BastionSettings.TARGETS, ingots, BastionSettings.keepIngots, admiring.size(), BastionSettings.maxConcurrentBarters)) {
            if (admiring.isEmpty() && (done || outOfEverything(me))) return exit(me, have);
            status = admiring.isEmpty() ? "out of gold" : "admiring x" + admiring.size();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Piglin target = null;
        double bestScore = Double.MAX_VALUE;
        // Park and spam (how runners do it): any free piglin already in throw range with line of sight gets an ingot
        // without us moving. Only when none is, walk to one on our level, and give up on one we cannot reach.
        Piglin inRange = null;
        for (Piglin p : piglins) {
            if (p.getOffhandItem().is(Items.GOLD_INGOT) || p.isAggressive() || !p.isAlive() || p.isBaby() || admiring.containsKey(p.getUUID())) continue;
            if (me.distanceTo(p) <= THROW_RANGE && me.hasLineOfSight(p) && brutesAround(p) == 0 && (inRange == null || me.distanceTo(p) < me.distanceTo(inRange))) inRange = p;
        }
        if (inRange != null) piglins = List.of(inRange);
        for (Piglin p : piglins) {
            if (p.getOffhandItem().is(Items.GOLD_INGOT) || p.isAggressive() || !p.isAlive()) continue;
            if (unreachable.getOrDefault(p.getUUID(), 0L) > ticks) continue;
            // a piglin by the lava sea is not worth walking to (run 35: walked into the lava closing in on one at y=34)
            if (lavaDropNear(p.blockPosition()) || lavaNearPos(p.blockPosition())) continue;
            // another level of the bastion (run 30: 1347 ticks closing in on piglins under the bridge) is not worth the walk
            if (Math.abs(p.getY() - me.getY()) > 2.5 || me.distanceTo(p) > 24) continue;
            int brutes = brutesAround(p);
            // brutes are always hostile and as fast as us: never walk up to a piglin with one beside it (run 24: 8 brutes, dead)
            if (brutes >= 1) continue;
            // reachability: height difference means stairs or a drop, no line of sight means a wall in between
            double score = me.distanceTo(p) + 4 * Math.abs(p.getY() - me.getY()) + (me.hasLineOfSight(p) ? 0 : 8) + 10 * brutes;
            if (score < bestScore) { bestScore = score; target = p; }
        }
        if (target == null) {
            status = admiring.isEmpty() ? "no free piglin n=" + piglins.size() : "admiring x" + admiring.size() + ", no other free piglin";
            if (!admiring.isEmpty() || bastion == null) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            // after the route, with every piglin guarded by a brute: lure them out, go to the wastes, or hunt endermen
            PathingCommand of = outsideFallback(me, piglins, counts(me), BastionGoals.met(counts(me), BastionSettings.TARGETS));
            if (of != null) return of;
            // run 16 (bridge) stood here for minutes: walk the bastion to find calm piglins instead of waiting
            status += ", searching";
            int k = (int) (ticks / 200 % 4);
            BlockPos c = bastion.pos.offset(k == 0 ? 12 : k == 2 ? -12 : 0, 0, k == 1 ? 12 : k == 3 ? -12 : 0);
            return new PathingCommand(new GoalXZ(c.getX(), c.getZ()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (me.distanceTo(target) > THROW_RANGE || !me.hasLineOfSight(target)) {
            if (!target.getUUID().equals(closeInId)) { closeInId = target.getUUID(); closeInSince = ticks; }
            else if (ticks - closeInSince > 200) { unreachable.put(target.getUUID(), ticks + 1200); closeInId = null; }
            status = "close in";
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        int slot = hotbarGold(me);
        if (slot < 0) {
            status = "hotbar gold";
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        me.getInventory().setSelectedSlot(slot);
        aimer.look(target.position().add(0, 0.4, 0), 0);
        if (Math.abs(Mth.wrapDegrees(me.getYRot() - yawTo(me, target))) < 12) {
            if (!safeDrop(me)) return pause0();
            admiring.put(target.getUUID(), ticks);
            throwTick = ticks;
            throwsDone++;
            status = "threw gold (" + admiring.size() + " admiring)";
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---- gold crafting, verified drops, fall clutch ----
    private BlockPos preferGold;
    private BastionDrops.Ledge dropPlan;
    private long dropScan = -1000, dropSince, plannedFallUntil, lipSince, lipTick = -10;
    private BastionDrops.Ledge fallPlan;
    private BlockPos dropFor;

    private int countOf(Player me, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = me.getInventory().getItem(i); if (s.is(item)) n += s.getCount(); }
        return n;
    }

    /** One gold block becomes nine ingots in the 2x2 grid: pick up, right-click one in, put the rest back, take the result. */
    private PathingCommand craftGold(Player me) {
        if (me.containerMenu != me.inventoryMenu) return null; // a chest is open: its slot ids are not the inventory's
        int held = slotOf(me, Items.GOLD_BLOCK);
        if (held < 0 && craftStep == 0) return null;
        var c = ctx.playerController();
        int w = me.inventoryMenu.containerId;
        if (craftStep == 0) craftFrom = held < 9 ? held + 36 : held;
        switch (craftStep) {
            case 0 -> c.windowClick(w, craftFrom, 0, ContainerInput.PICKUP, me);
            case 1 -> c.windowClick(w, 1, 1, ContainerInput.PICKUP, me);
            case 2 -> c.windowClick(w, craftFrom, 0, ContainerInput.PICKUP, me);
            default -> c.windowClick(w, 0, 0, ContainerInput.QUICK_MOVE, me);
        }
        craftStep = (craftStep + 1) % 4;
        status = "craft ingots";
        return pause0();
    }

    private boolean floor(BlockPos p) {
        return !ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty();
    }

    private BlockPos doorPos;
    /** Door descent: breaks planned (0 = not planned, -1 = not possible from this pocket) and done. */
    private int descentSteps, descentDone;

    private boolean lavaBeside(BlockPos c) {
        if (lavaAt(c)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) if (lavaAt(c.relative(d))) return true;
        return false;
    }

    private void planDescent(Player me) {
        boolean[] clean = new boolean[8], solid = new boolean[8];
        for (int i = 0; i < 8; i++) {
            BlockPos c = doorPos.below(i);
            BlockState st = ctx.world().getBlockState(c);
            clean[i] = i > 0 && !lavaBeside(c); // our own pocket (0) sits in the lake by definition
            solid[i] = i == 0 || floor(c) && st.getDestroySpeed(ctx.world(), c) >= 0 && !st.is(Blocks.CHEST) && !(st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
        }
        int steps = BastionLava.descentSteps(clean, solid);
        int doors = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = me.getInventory().getItem(i); if (s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) doors += s.getCount(); }
        // the pocket we stand in already used its door: the rest of the descent needs one per break
        descentSteps = BastionLava.canDescend(steps, doors + 1, pickaxeSlot(me) >= 0) ? steps : -1;
        descentDone = 0;
        if (descentSteps > 0) logDirect("Bastion: door descent, " + steps + " breaks, " + doors + " doors left");
    }

    /** One tick of the descent: mine the block under us, put a door in the cell we drop into, seal over our head at the bottom. */
    private PathingCommand descend(Player me) {
        BlockPos cur = doorPos, below = cur.below();
        if (descentDone >= descentSteps) {
            BlockPos seal = cur.above(2);
            if (floor(seal)) { descentSteps = -1; logDirect("Bastion: sealed under the lava, digging out"); return null; }
            int b = clutchBlock(me);
            if (b < 0) { descentSteps = -1; return null; }
            if (b >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, b, 7, ContainerInput.SWAP, me); return pause0(); }
            me.getInventory().setSelectedSlot(b);
            // sneaking, so the click on the door top places the block instead of opening the door
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (me.isShiftKeyDown()) {
                Vec3 hit = Vec3.atCenterOf(cur.above()).add(0, 0.5, 0);
                ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, cur.above(), false));
            }
            status = "door descent: sealing above our head";
            return pause0();
        }
        if (floor(below) && !(ctx.world().getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
            int pick = pickaxeSlot(me);
            if (pick < 0) { descentSteps = -1; return null; }
            if (pick >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ContainerInput.SWAP, me); return pause0(); }
            me.getInventory().setSelectedSlot(pick);
            baritone.getLookBehavior().updateTarget(new Rotation(me.getYRot(), 90), true);
            // mine that exact block: the crosshair would hit the door we stand in first
            if (!digging) { ctx.playerController().clickBlock(below, Direction.UP); digging = true; }
            else ctx.playerController().onPlayerDamageBlock(below, Direction.UP);
            status = "door descent: mining " + (descentDone + 1) + "/" + descentSteps;
            return pause0();
        }
        digging = false;
        if (ctx.world().getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            // our new door showed up a tick after the click (in-game we then mined it again, thinking it was the floor)
            doorPos = below.immutable();
            doorSince = ticks;
            descentDone++;
            return pause0();
        }
        // the block is gone (our old door went with it): next tick, a door in the cell we drop into
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) if (me.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) slot = i;
        if (slot < 0) { descentSteps = -1; return null; }
        if (slot >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ContainerInput.SWAP, me); return pause0(); }
        me.getInventory().setSelectedSlot(slot);
        placeDoor(me, below);
        if (ctx.world().getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            // placed (the client sees it next tick at the latest): this is our pocket now
            doorPos = below.immutable();
            doorSince = ticks;
            descentDone++;
        }
        status = "door descent: door " + descentDone + "/" + descentSteps;
        return pause0();
    }

    private boolean digging;
    private int doorTries;

    /**
     * Door into the cell on the block under it. A closed door is a 3/16 slab on one side of its cell and is refused when that
     * slab would cut into us, which depends on which way we face; in-game the first facing failed every time. Turn a quarter
     * every few tries so one of the four sides is clear of our body. True once the door is there.
     */
    private boolean placeDoor(Player me, BlockPos cell) {
        if (ctx.world().getBlockState(cell).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) { doorTries = 0; return true; }
        doorTries++;
        float yaw = Mth.wrapDegrees(Math.round(me.getYRot() / 90f) * 90f + (doorTries % 3 == 0 ? 90 : 0));
        // a closed door's slab sits on the side opposite its facing: off-centre, face the side we lean toward so the slab
        // lands on the far edge and does not overlap our hitbox (w6-w9: refused against the tank wall, drowned)
        double offX = me.getX() - (cell.getX() + 0.5), offZ = me.getZ() - (cell.getZ() + 0.5);
        if (Math.max(Math.abs(offX), Math.abs(offZ)) > 0.05) yaw = Math.abs(offX) > Math.abs(offZ) ? (offX > 0 ? -90 : 90) : (offZ > 0 ? 0 : 180);
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 89), true);
        if (doorTries % 3 == 2) {
            BlockPos floorPos = cell.below();
            Vec3 hit = Vec3.atCenterOf(floorPos).add(0, 0.5, 0);
            ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floorPos, false));
        }
        return false;
    }
    private long doorSince;

    /** Deep lava or a far shore: put a door in the lava around us and stand in the pocket it makes (see BastionLava). */

    // ---- underwater door pocket (overworld): breathe in a door, then swim up or tunnel out (shares FluidPocket with lava) ----
    private BlockPos wDoor, wExit, wTunnel;
    private long wLog = -1000;
    private boolean wSeekWall, wLeaving;
    private int centreTicks, wWalkTicks;
    private final java.util.Set<BlockPos> wBadExit = new java.util.HashSet<>();
    private long wOpenTick = -100;
    private boolean water(BlockPos p) { return ctx.world().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER); }
    private int surfaceDepth(Player me) {
        BlockPos e = BlockPos.containing(me.getEyePosition());
        for (int i = 0; i < 40; i++) {
            BlockPos q = e.above(i);
            if (water(q)) continue;
            if (ctx.world().getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) continue;
            return solid(q) ? -1 : i;
        }
        return -1;
    }
    private BlockPos findExit(BlockPos from) {
        BlockPos best = null;
        for (BlockPos q : BlockPos.betweenClosed(from.offset(-10, -10, -10), from.offset(10, 1, 10))) { // w12: also below us, so we head there while sinking
            if (solid(q) || solid(q.above()) || water(q) || water(q.above()) || !solid(q.below())) continue;
            if (ctx.world().getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) continue;
            if (wBadExit.contains(q)) continue;
            // w16: a cave 8 below was "nearer" than the room at our level; tunnelling sideways is cheaper than down
            java.util.function.ToDoubleFunction<BlockPos> cost = r -> Math.hypot(r.getX() - from.getX(), r.getZ() - from.getZ()) + 3 * Math.abs(r.getY() - from.getY());
            if (best == null || cost.applyAsDouble(q) < cost.applyAsDouble(best)) best = q.immutable();
        }
        return best;
    }
    private boolean fluidNextTo(BlockPos c, BlockPos... ok) {
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            boolean skip = false; for (BlockPos o : ok) if (n.equals(o)) skip = true;
            if (!skip && (water(n) || realLava(n))) return true;
        }
        return false;
    }
    private PathingCommand waterPocket(Player me) {
        var in = baritone.getInputOverrideHandler();
        boolean under = me.isUnderWater();
        if (ticks % 20 == 0 && (under || wSeekWall || wDoor != null)) System.out.println("WATERDBG t=" + ticks + " air=" + me.getAirSupply() + " seek=" + wSeekWall + " door=" + wDoor + " exit=" + wExit + " held=" + me.getMainHandItem().getItem() + " tries=" + doorTries + " st=" + status);
        BlockState fdA = ctx.world().getBlockState(me.blockPosition());
        // w23: a wet tick right after placing dropped the pocket, then this guard returned before it could be re-adopted
        if (!under && wTunnel == null && wDoor == null && !wSeekWall && !(fdA.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && water(me.blockPosition().above(2)))) return null;
        if (me.level().dimension().identifier().getPath().equals("the_nether")) return null;
        BlockPos feet = me.blockPosition();
        // the door shows up a tick after the click (waterpocket w1: placed, never adopted, process walked off): adopt it
        BlockState fd0 = ctx.world().getBlockState(feet);
        // w20: stood in a door's upper half, adopted it as the pocket and drowned waiting with the head in the water above
        boolean lowerDoor = fd0.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && fd0.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
        if (wDoor == null && !wLeaving && lowerDoor && water(feet.above(2))) { wDoor = feet.immutable(); wSeekWall = false; logDirect("Bastion: water door pocket at " + feet.toShortString() + " air=" + me.getAirSupply()); }
        if (wLeaving && !(ctx.world().getBlockState(feet).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && !(ctx.world().getBlockState(feet.below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && !(ctx.world().getBlockState(feet.below(2)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) wLeaving = false;
        boolean inPocket = wDoor != null && ctx.world().getBlockState(wDoor).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                && me.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(wDoor).expandTowards(0, 1, 0));
        if (wTunnel != null && !under) inPocket = true; // in our own dry tunnel
        if (!under && !inPocket && !wSeekWall) { wDoor = null; wTunnel = null; in.setInputForceState(Input.SNEAK, false); return null; }
        int doors = 0, slot = -1;
        for (int i = 0; i < 36; i++) { ItemStack s = me.getInventory().getItem(i); if (s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) { doors += s.getCount(); if (slot < 0) slot = i; } }
        // w13: re-scanning mid-tunnel took our own dug cell for the exit; keep the exit once pocketed
        if (wExit == null || ticks % 40 == 0 && wDoor == null && wTunnel == null) wExit = findExit(feet);
        int surf = wTunnel != null ? -1 : surfaceDepth(me); // w17: the dry tunnel read as "surface 0" and it tried to swim up
        boolean floorOk = water(feet) && ctx.world().getBlockState(feet.below()).isFaceSturdy(ctx.world(), feet.below(), Direction.UP) && !(ctx.world().getBlockState(feet.below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock);
        FluidPocket.Action a; a = FluidPocket.decide(me.getAirSupply(), me.getMaxAirSupply(), surf, inPocket, doors, floorOk, wExit != null, pickaxeSlot(me) >= 0);
        // the pocket is out in open water (w2): walk the bottom toward the exit and re-pocket against the wall there
        // w4/w5: a first door mid-tank had to be left again through itself; go to the wall toward the exit before the first door
        if (under && !inPocket && wDoor == null && wTunnel == null && wExit != null && doors > 0 && pickaxeSlot(me) >= 0 && me.getAirSupply() > 120 && !(ctx.world().getBlockState(feet).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) wSeekWall = true;
        if (wSeekWall && me.getAirSupply() <= 120 && !wLeaving) wSeekWall = false;
        if (wSeekWall && !inPocket && wExit != null && (me.getAirSupply() > 120 || wLeaving) && doors > 0) {
            BlockState fs = ctx.world().getBlockState(feet);
            if (fs.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                Direction toward = Math.abs(wExit.getX() - feet.getX()) >= Math.abs(wExit.getZ() - feet.getZ()) ? (wExit.getX() > feet.getX() ? Direction.EAST : Direction.WEST) : (wExit.getZ() > feet.getZ() ? Direction.SOUTH : Direction.NORTH);
                if (solid(feet.relative(toward))) { wDoor = feet.immutable(); wSeekWall = false; logDirect("Bastion: water door pocket against the wall at " + feet.toShortString()); return pause0(); }
                // w4/w5: walking out through our own door stalls; swim straight up out of it (2 tall) instead
                in.setInputForceState(Input.JUMP, true);
                status = "water: rising out of the old door";
                return pause0();
            }
            boolean overDoor = ctx.world().getBlockState(feet.below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock || ctx.world().getBlockState(feet.below(2)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
            Direction dir = Math.abs(wExit.getX() - feet.getX()) >= Math.abs(wExit.getZ() - feet.getZ()) ? (wExit.getX() > feet.getX() ? Direction.EAST : Direction.WEST) : (wExit.getZ() > feet.getZ() ? Direction.SOUTH : Direction.NORTH);
            if (solid(feet.relative(dir)) && solid(feet.below()) && water(feet)) { wSeekWall = false; a = FluidPocket.Action.DOOR; }
            else {
                Vec3 d = Vec3.atBottomCenterOf(feet.relative(dir)).subtract(me.position());
                baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-d.x, d.z)), solid(feet.below()) ? 0 : 60), true);
                in.setInputForceState(Input.MOVE_FORWARD, true);
                in.setInputForceState(Input.SNEAK, !overDoor && !solid(feet.below())); // walking the floor crouched crawls (w22: 1 block in 9 s)
                if (overDoor) in.setInputForceState(Input.JUMP, true);
                status = "water: to the wall toward " + wExit.toShortString();
                return pause0();
            }
        }
        if (ticks - wLog > 40) { wLog = ticks; logDirect("Bastion: water " + a + " air=" + me.getAirSupply() + " surface=" + surf + " doors=" + doors + " exit=" + (wExit == null ? "none" : wExit.toShortString()) + String.format(" at %.2f %.2f %.2f", me.getX(), me.getY(), me.getZ())); }
        status = "water: " + a;
        in.setInputForceState(Input.SNEAK, false);
        switch (a) {
            case SWIM_UP -> { in.setInputForceState(Input.JUMP, true); return pause0(); }
            case SINK -> { in.setInputForceState(Input.SNEAK, true); return pause0(); } // crouch sinks faster
            case DOOR -> {
                if (slot >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ContainerInput.SWAP, me); return pause0(); }
                // w6: pressed against the wall the door's slab overlaps our hitbox and placement is refused; centre first
                double ox = feet.getX() + 0.5 - me.getX(), oz = feet.getZ() + 0.5 - me.getZ();
                if (false && (Math.abs(ox) > 0.12 || Math.abs(oz) > 0.12) && centreTicks++ < 30) {
                    baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-ox, oz)), 0), true);
                    in.setInputForceState(Input.MOVE_FORWARD, true);
                    in.setInputForceState(Input.SNEAK, true);
                    return pause0();
                }
                me.getInventory().setSelectedSlot(slot);
                if (placeDoor(me, feet)) { centreTicks = 0; wDoor = feet.immutable(); logDirect("Bastion: water door pocket at " + feet.toShortString() + " air=" + me.getAirSupply()); }
                in.setInputForceState(Input.SNEAK, true);
                return pause0();
            }
            case WAIT -> {
                if (under && me.getAirSupply() < 200) { wDoor = null; return pause0(); } // head stays wet: this is no pocket
                in.setInputForceState(Input.SNEAK, true); return pause0();
            }
            case TUNNEL -> {
                BlockPos cur = wTunnel != null ? wTunnel : wDoor;
                if (cur == null) cur = feet;
                // stand in the current tunnel cell first
                if (!feet.equals(cur)) {
                    // w14: stuck in the door, the dug cell ahead unreachable (door slab in the way): toggle the door every second
                    status = "water: stepping into tunnel " + cur.toShortString();
                    if (ctx.world().getBlockState(feet).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && ++wWalkTicks % 20 == 0)
                        ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(feet), Direction.UP, feet, false));
                    Vec3 d = Vec3.atBottomCenterOf(cur).subtract(me.position());
                    baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-d.x, d.z)), 0), true);
                    in.setInputForceState(Input.MOVE_FORWARD, true);
                    return pause0();
                }
                int dx = Integer.signum(wExit.getX() - cur.getX()), dz = Integer.signum(wExit.getZ() - cur.getZ());
                Direction dir = Math.abs(wExit.getX() - cur.getX()) >= Math.abs(wExit.getZ() - cur.getZ()) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                BlockPos n = cur.relative(dir);
                if (water(n) && wTunnel == null) { wSeekWall = true; wLeaving = true; wDoor = null; status = "water: leaving the pocket for the wall"; logDirect("Bastion: water pocket is in open water, moving to the wall toward " + wExit.toShortString()); return pause0(); }
if (!solid(n) && !solid(n.above()) && !water(n) && !water(n.above())) {
                    wTunnel = n.immutable();
                    if (feet.equals(wExit)) { logDirect("Bastion: water tunnel reached the exit at " + n.toShortString()); wDoor = null; wTunnel = null; return null; }
                    return pause0();
                }
                for (BlockPos t : new BlockPos[]{n.above(), n}) {
                    if (!solid(t)) continue;
                    if (fluidNextTo(t, cur, cur.above(), n, n.above())) { if (wExit != null) wBadExit.add(wExit); wExit = null; status = "water: tunnel blocked by fluid"; return pause0(); }
                    int pk = pickaxeSlot(me);
                    if (pk >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pk, 7, ContainerInput.SWAP, me); return pause0(); }
                    me.getInventory().setSelectedSlot(pk);
                    baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(t), ctx.playerRotations()), true);
                    if (!t.equals(holeTarget)) { holeTarget = t; ctx.playerController().clickBlock(t, dir.getOpposite()); }
                    else ctx.playerController().onPlayerDamageBlock(t, dir.getOpposite());
                    status = "water: tunnelling " + t.toShortString();
                    return pause0();
                }
                return pause0();
            }
            default -> { return null; }
        }
    }
    private PathingCommand doorPocket(Player me, BlockPos shore) {
        BlockPos feet = me.blockPosition();
        // a door we placed shows up a tick after the click: adopt it at once (in-game we swam up out of it, taking it for lava)
        for (BlockPos c : new BlockPos[]{feet.below(), feet}) {
            BlockState ds = ctx.world().getBlockState(c);
            if (ds.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && ds.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                doorPos = c.immutable();
                doorSince = ticks;
                doorTries = 0;
                status = "lava: in door pocket at " + c.toShortString();
                logDirect("Bastion: " + status);
                return pause0();
            }
        }
        int depth = 0;
        for (int i = -1; i < 4 && lavaAt(feet.above(1).below(i + 1)); i++) depth++;
        // the door's lower half needs a full block under it: our feet cell, or the one below while we bob above it
        BlockPos cell = null;
        for (BlockPos c : new BlockPos[]{feet, feet.below()}) {
            BlockState under = ctx.world().getBlockState(c.below());
            if (lavaAt(c) && under.isFaceSturdy(ctx.world(), c.below(), Direction.UP) && (lavaAt(c.above()) || ctx.world().getBlockState(c.above()).canBeReplaced())) { cell = c; break; }
        }
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) if (me.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) slot = i;
        double shoreDist = shore == null ? -1 : Math.sqrt(feet.distSqr(shore));
        BastionLava.Escape esc = BastionLava.choose(shoreDist, depth, me.getHealth(), slot >= 0, cell != null, pillarBlocks(me) > 0);
        if (!me.isInLava()) lavaSince = -1; else if (lavaSince < 0) lavaSince = ticks;
        // swimming for a shore that never comes (lava8: 4 s in the lava 2 blocks from the edge, dead): door after 1 s
        if (esc == BastionLava.Escape.SWIM && slot >= 0 && cell != null && lavaSince >= 0 && ticks - lavaSince > 20) esc = BastionLava.Escape.DOOR;
        if (esc != BastionLava.Escape.DOOR) return null;
        if (slot >= 9) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ContainerInput.SWAP, me);
            return pause0();
        }
        me.getInventory().setSelectedSlot(slot);
        if (placeDoor(me, cell)) {
            doorPos = cell.immutable();
            doorSince = ticks;
            status = "lava: door pocket at " + cell.toShortString() + " (depth " + depth + ", shore " + (shore == null ? "none" : String.format("%.1f", shoreDist)) + ")";
            logDirect("Bastion: " + status);
        } else status = "lava: placing a door";
        return pause0();
    }

    private boolean lavaNear(Player me) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 0; dy++) if (realLava(me.blockPosition().offset(dx, dy, dz))) return true;
        return false;
    }

    private boolean realLava(BlockPos p) { return ctx.world().getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA); }

    private boolean lavaAt(BlockPos p) {
        return ctx.world().getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA) || EdgeCost.lavaSoon.contains(LavaFlow.key(p.getX(), p.getY(), p.getZ()));
    }

    private BlockPos lastSel;
    private boolean lastSelSolid;
    /** The block we were aiming at last tick is gone and touches a fluid: a flow may have been released, re-forecast now. */
    private boolean brokeNextToFluid() {
        BlockPos was = lastSel; boolean wasSolid = lastSelSolid;
        lastSel = ctx.getSelectedBlock().orElse(null);
        lastSelSolid = lastSel != null && !ctx.world().getBlockState(lastSel).isAir();
        if (was == null || !wasSolid || !ctx.world().getBlockState(was).isAir()) return false;
        for (Direction d : Direction.values()) if (!ctx.world().getFluidState(was.relative(d)).isEmpty()) return true;
        return false;
    }

    private long flowAt = -100;
    private BlockPos flowCenter;
    /** Re-forecast lava spread around us (every 10 ticks, or right after a block next to lava was broken). */
    private void updateFlow(Player me, boolean force) {
        if (!force && ticks - flowAt < 10) return;
        flowAt = ticks;
        BlockPos c = me.blockPosition();
        var w = ctx.world();
        LavaFlow.Grid g = new LavaFlow.Grid() {
            public int lava(int x, int y, int z) {
                var fs = w.getFluidState(new BlockPos(x, y, z));
                if (!fs.is(net.minecraft.tags.FluidTags.LAVA)) return -1;
                if (fs.isSource() || fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return 0;
                return Math.max(0, 8 - fs.getAmount());
            }
            public boolean open(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                if (!w.isLoaded(q)) return false;
                BlockState s = w.getBlockState(q);
                return s.getFluidState().isEmpty() && (s.isAir() || s.canBeReplaced());
            }
        };
        // horizon 100 ticks: the cells we could walk into within ~5 s
        var f = LavaFlow.forecast(g, c.getX(), c.getY(), c.getZ(), 10, 100);
        EdgeCost.lavaSoon = java.util.Set.copyOf(f.keySet());
        // water (overworld / the port): 7 sideways, 5 ticks a step; current water cells count too
        LavaFlow.Grid wg = new LavaFlow.Grid() {
            public int lava(int x, int y, int z) {
                var fs = w.getFluidState(new BlockPos(x, y, z));
                if (!fs.is(net.minecraft.tags.FluidTags.WATER)) return -1;
                if (fs.isSource() || fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return 0;
                return Math.max(0, 8 - fs.getAmount());
            }
            public boolean open(int x, int y, int z) { return g.open(x, y, z); }
        };
        java.util.Set<Long> wet = new java.util.HashSet<>(LavaFlow.forecast(wg, c.getX(), c.getY(), c.getZ(), 10, 60, LavaFlow.WATER_RUN, LavaFlow.WATER_STEP_TICKS).keySet());
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-10, -4, -10), c.offset(10, 4, 10))) if (w.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) wet.add(LavaFlow.key(q.getX(), q.getY(), q.getZ()));
        EdgeCost.waterSoon = java.util.Set.copyOf(wet);
    }

    /** Feet height of the landing below (x, y, z) feet cell; Integer.MIN_VALUE when the column is unloaded, ends in lava or is too deep. */
    private int landing(int x, int y, int z, boolean[] safe) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int yy = y; yy > y - 40; yy--) {
            m.set(x, yy - 1, z);
            if (!ctx.world().hasChunkAt(m)) return Integer.MIN_VALUE;
            if (lavaAt(m) || lavaAt(m.above())) return Integer.MIN_VALUE;
            if (floor(m)) {
                BlockState st = ctx.world().getBlockState(m);
                BlockPos feet = m.above();
                boolean ok = !st.is(Blocks.MAGMA_BLOCK) && !st.is(Blocks.FIRE) && !st.is(Blocks.SOUL_FIRE) && !floor(feet.above()) && ctx.world().getFluidState(feet).isEmpty();
                for (Direction d : Direction.Plane.HORIZONTAL) ok &= !lavaAt(feet.relative(d)) && !lavaAt(m.relative(d));
                safe[0] = ok;
                return yy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Ledges within 10 blocks at our height whose drop lands on a checked floor. */
    private List<BastionDrops.Ledge> ledges(Player me) {
        List<BastionDrops.Ledge> out = new ArrayList<>();
        BlockPos at = ctx.playerFeet();
        boolean[] safe = new boolean[1];
        List<PiglinBrute> brutes = ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(32), PiglinBrute::isAlive);
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos s = at.offset(dx, dy, dz);
            if (!ctx.world().hasChunkAt(s) || !floor(s.below()) || floor(s) || floor(s.above())) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos o = s.relative(d);
                if (floor(o) || floor(o.above()) || floor(o.below())) continue;
                safe[0] = false;
                int land = landing(o.getX(), o.getY(), o.getZ(), safe);
                if (land == Integer.MIN_VALUE) continue;
                BastionDrops.Ledge l = new BastionDrops.Ledge(s.getX(), s.getY(), s.getZ(), d.getStepX(), d.getStepZ(), land, safe[0]);
                if (!nearBrute(l, brutes)) out.add(l);
            }
        }
        return out;
    }

    private static boolean nearBrute(BastionDrops.Ledge l, List<PiglinBrute> brutes) {
        for (PiglinBrute b : brutes) if (BastionDrops.landsNear(l, b.getX(), b.getY(), b.getZ(), BastionDrops.THREAT_CLEAR)) return true;
        return false;
    }

    /**
     * Brutes cost extra to walk near while a run is on, so routes go round them where there is a way round. Not the
     * avoidance setting: that one counts every mob, and piglins are who we came to see.
     */
    public List<baritone.utils.pathing.Avoidance> avoid() {
        if (!active || ctx.player() == null || ctx.world() == null || BastionSettings.bruteAvoid == 1) return List.of();
        List<baritone.utils.pathing.Avoidance> out = new ArrayList<>();
        for (PiglinBrute b : ctx.world().getEntitiesOfClass(PiglinBrute.class, ctx.player().getBoundingBox().inflate(48), PiglinBrute::isAlive))
            out.add(new baritone.utils.pathing.Avoidance(b.blockPosition(), BastionSettings.bruteAvoid, BastionSettings.bruteAvoidRadius));
        return out;
    }

    /** A verified drop toward a target far below, instead of the long stairs; null to path normally. */
    private PathingCommand drop(Player me, BlockPos target) {
        if (fallPlan != null && me.onGround() && me.getY() < fallPlan.y() - 0.5) fallPlan = null;
        // run 120: we walked off the ledge at full pace and the carry took us two blocks out, past the one-block shelf we
        // had verified and down a 22-block shaft. In the air the pace is braked, so we come down in the column we checked
        if (fallPlan != null && !me.onGround() && ticks < plannedFallUntil && me.getY() > fallPlan.landY()) {
            double out = me.getDeltaMovement().x * fallPlan.dx() + me.getDeltaMovement().z * fallPlan.dz();
            baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-fallPlan.dx(), fallPlan.dz())), 30), true);
            if (out > 0.02) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            status = "taking verified drop (" + fallPlan.height() + ")";
            return pause0();
        }
        if (!me.onGround() || ctx.playerFeet().getY() - target.getY() < BastionSettings.maxSafeDrop + 3) { if (dropPlan != null && me.getY() < dropPlan.y() - 1) dropPlan = null; return null; }
        if (!target.equals(dropFor) || ticks - dropScan > 60 && dropPlan == null) {
            dropFor = target;
            dropScan = ticks;
            BlockPos at = ctx.playerFeet();
            dropPlan = BastionDrops.choose(ledges(me), at.getX(), at.getY(), at.getZ(), target.getX(), target.getY(), target.getZ(), me.getHealth(), BastionSettings.maxSafeDrop, BastionSettings.lowHealth);
            dropSince = ticks;
        }
        if (dropPlan == null) return null;
        if (ticks - dropSince > 300) { dropPlan = null; dropScan = ticks + 200; return null; }
        BlockPos ledge = new BlockPos(dropPlan.x(), dropPlan.y(), dropPlan.z());
        // brutes move while we walk to the ledge: the landing is checked again before we commit to it
        if (ticks >= plannedFallUntil && nearBrute(dropPlan, ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(32), PiglinBrute::isAlive))) {
            dropPlan = null;
            dropScan = ticks;
            return null;
        }
        // run 115: one step out we still stood on the rim, in the cell beyond the ledge, and were sent back to the ledge;
        // the walk back and the walk off fought and we left the edge sideways, into a 10-block fall. Once the step
        // off has begun it is finished
        boolean stepping = ticks < plannedFallUntil && ctx.playerFeet().equals(ledge.offset(dropPlan.dx(), 0, dropPlan.dz()));
        if (!ctx.playerFeet().equals(ledge) && !stepping) {
            status = "to verified drop " + ledge.toShortString() + " (" + dropPlan.height() + " down)";
            return new PathingCommand(new GoalBlock(ledge), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // on the ledge: walk off it; the clutch stands down for this fall
        if (!stepping) plannedFallUntil = ticks + 60;
        fallPlan = dropPlan;
        if (ticks - lipTick > 1) lipSince = ticks;
        lipTick = ticks;
        float yaw = (float) Math.toDegrees(Math.atan2(-dropPlan.dx(), dropPlan.dz()));
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 30), true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        // creep to the lip sneaking (a sneak stops at the edge), then step off from there: we leave at a crawl, not a walk
        double along = (me.getX() - ledge.getX() - 0.5) * dropPlan.dx() + (me.getZ() - ledge.getZ() - 0.5) * dropPlan.dz();
        if (along < 0.7 && ticks - lipSince < 60) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        status = "taking verified drop (" + dropPlan.height() + ")";
        return pause0();
    }

    /** Falling further than is safe: put a block under (or beside and under) us now, while the damage so far is still small. */
    private PathingCommand clutch(Player me) {
        if (me.onGround() || me.isInLava() || me.getDeltaMovement().y > -0.2 || me.isFallFlying()) return null;
        BlockPos feet = me.blockPosition();
        int remaining = 0;
        boolean intoLava = false;
        for (int i = 1; i <= 64; i++) {
            BlockPos b = feet.below(i);
            if (!ctx.world().hasChunkAt(b)) return null;
            if (lavaAt(b)) { intoLava = true; remaining = i - 1; break; }
            if (floor(b)) { remaining = i - 1; break; }
            remaining = i;
        }
        int slot = clutchBlock(me);
        BastionDrops.Clutch d = BastionDrops.clutch(me.fallDistance, remaining + (me.getY() - feet.getY()), intoLava,
                BastionDrops.planned(ticks < plannedFallUntil && fallPlan != null, me.fallDistance, remaining + (me.getY() - feet.getY()), fallPlan == null ? 0 : fallPlan.height()),
                me.getHealth(), BastionSettings.maxSafeDrop, slot >= 0);
        if (d != BastionDrops.Clutch.PLACE) return null;
        // the cell just below our feet, placed against any solid neighbour (the ledge we came off is usually one)
        for (int down = 1; down <= 2; down++) {
            BlockPos cell = feet.below(down);
            if (floor(cell)) return null;
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos against = cell.relative(dir);
                if (!floor(against)) continue;
                Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(dir.getOpposite().getUnitVec3i()).scale(0.5));
                if (me.getEyePosition(1.0F).distanceTo(hit) > ctx.playerController().getBlockReachDistance()) continue;
                if (slot >= 9) {
                    ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ContainerInput.SWAP, me);
                    return pause0();
                }
                me.getInventory().setSelectedSlot(slot);
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), hit, ctx.playerRotations()), true);
                ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, dir.getOpposite(), against, false));
                status = "clutch: block under us (" + (int) me.fallDistance + " fallen, " + remaining + " to go" + (intoLava ? ", lava" : "") + ")";
                return pause0();
            }
        }
        return null;
    }

    /** Netherrack, soul sand or another throwaway; gravel last since it falls through unless the cell under it is solid. */
    private int clutchBlock(Player me) {
        int gravel = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.NETHERRACK)) return i;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.GRAVEL)) { if (gravel < 0) gravel = i; continue; }
            if (s.is(Items.SOUL_SAND) || s.is(Items.COBBLESTONE) || s.is(Items.BLACKSTONE) || s.is(Items.COBBLED_DEEPSLATE)) return i;
        }
        return gravel;
    }

    // ---- speedrun router: goals, chest plan, exit ----
    private final Map<UUID, Long> admiring = new HashMap<>();
    private final Set<BlockPos> looted = new HashSet<>();
    private final Map<BlockPos, Long> badChest = new HashMap<>();
    private List<BastionPlan.Point> plan = List.of();
    /** Objective-level route for the layout (BastionRoutes); the block plan above executes its chest/gold steps. */
    private List<BastionRoutes.Step> route = List.of(), loggedRoute = List.of();
    private final Map<BlockPos, BastionRoutes.Step> planStep = new HashMap<>();
    private BastionRoutes.Step currentStep;
    private int[] floorCells = {0, 0};

    /** Adult piglin within 16 that is not admiring and not holding our gold. */
    private Piglin nearestUndistracted(Player me, List<LivingEntity> near) {
        Piglin best = null;
        for (LivingEntity e : near) if (e instanceof Piglin p && !p.isBaby() && p.isAlive() && !admiring.containsKey(p.getUUID()) && !p.getOffhandItem().is(Items.GOLD_INGOT)
                && me.distanceTo(p) <= BastionPriors.AGGRO_RANGE && (best == null || me.distanceTo(p) < me.distanceTo(best))) best = p;
        return best;
    }

    /** Housing lava variant: sample netherrack floor cells around the bastion and count those with lava on top. */
    private int[] lavaFloorSample() {
        int floor = 0, lava = 0;
        BlockPos c = bastion.pos;
        for (int dx = -24; dx <= 24; dx += 3) for (int dz = -24; dz <= 24; dz += 3) for (int dy = -12; dy <= 4; dy++) {
            BlockPos b = c.offset(dx, dy, dz);
            if (!ctx.world().hasChunkAt(b) || !ctx.world().getBlockState(b).is(Blocks.NETHERRACK)) continue;
            net.minecraft.world.level.block.state.BlockState up = ctx.world().getBlockState(b.above());
            if (up.isAir()) { floor++; break; }
            if (up.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) { floor++; lava++; break; }
        }
        return new int[]{floor, lava};
    }
    private BlockPos planFor, chestTarget, openedChest, entry;
    private long chestScan = -1000, chestSince, chestWaitSince, chestClickTick = -1000, lastTake;
    private boolean exiting, goldSourceGone, chestsExhausted;
    public int chestsLooted;
    private Integer savedFall;
    private Boolean savedBucketFall;

    /** Out of ingots with no chest and no gold block left: nothing more to get here. */
    private boolean outOfEverything(Player me) {
        return BastionSettings.exitWhenDone && ingotCount(me) <= BastionSettings.keepIngots && chestsExhausted && goldSourceGone && admiring.isEmpty();
    }

    public static String itemIdPublic(ItemStack s) { return itemId(s); }

    static String itemId(ItemStack s) {
        if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) {
            PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
            if (pc != null && (pc.is(Potions.FIRE_RESISTANCE) || pc.is(Potions.LONG_FIRE_RESISTANCE))) return "fire_resistance";
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private Map<String, Integer> counts(Player me) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (!s.isEmpty()) m.merge(itemId(s), s.getCount(), Integer::sum);
        }
        ItemStack off = me.getOffhandItem();
        if (!off.isEmpty()) m.merge(itemId(off), off.getCount(), Integer::sum);
        return m;
    }

    private String summary(Map<String, Integer> have) {
        StringBuilder b = new StringBuilder();
        for (String k : BastionSettings.TARGETS.keySet()) b.append(k).append('=').append(have.getOrDefault(k, 0)).append('/').append(BastionSettings.TARGETS.get(k)).append(' ');
        return b + "gold=" + have.getOrDefault("gold_ingot", 0) + " chests=" + chestsLooted + " throws=" + throwsDone + " layout=" + layout();
    }

    /** Leave back the way we came (the entry side is the side we know is walkable) and hand off once clear of the bastion. */
    private PathingCommand exit(Player me, Map<String, Integer> have) {
        if (!exiting) {
            exiting = true;
            logDirect("Bastion done: " + summary(have));
        }
        if (me.containerMenu instanceof ChestMenu) ((LocalPlayer) me).closeContainer();
        BlockPos c = bastion.pos;
        double dx = (entry == null ? me.getX() : entry.getX()) - c.getX(), dz = (entry == null ? me.getZ() : entry.getZ()) - c.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1) { dx = 1; dz = 0; len = 1; }
        if (Math.hypot(me.getX() - c.getX(), me.getZ() - c.getZ()) >= BastionSettings.exitDistance) {
            logDirect("Bastion: left, handing off. " + summary(have));
            stop();
            return pause0();
        }
        int r = BastionSettings.exitDistance + 8;
        status = "exiting";
        // a plain goal change lets the old path run to its end first (run 116: a second further toward the brutes, one axe hit)
        return new PathingCommand(new GoalXZ(c.getX() + (int) (dx / len * r), c.getZ() + (int) (dz / len * r)), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    /** Opening a chest angers every piglin that can see us (gold armour does not help); brutes are hostile anyway. */
    private boolean piglinWatching(Player me, List<LivingEntity> near) {
        for (LivingEntity e : near) if ((e instanceof Piglin p && !p.isBaby() || e instanceof PiglinBrute) && e.isAlive() && me.distanceTo(e) < 16 && e.hasLineOfSight(me)) return true;
        return false;
    }

    private void replan(Player me) {
        chestScan = ticks;
        planFor = bastion.pos;
        List<BlockPos> chests = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, new BlockOptionalMetaLookup(Blocks.CHEST), 64, 64, 64);
        floorCells = lavaFloorSample();
        List<BastionPlan.Point> pts = new ArrayList<>();
        for (BlockPos b : chests) {
            if (Math.hypot(b.getX() - bastion.pos.getX(), b.getZ() - bastion.pos.getZ()) > 56 || looted.contains(b)) continue;
            pts.add(new BastionPlan.Point(b.getX(), b.getY(), b.getZ(), BastionPlan.Kind.CHEST));
        }
        List<BlockPos> gold = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, new BlockOptionalMetaLookup(Blocks.GOLD_BLOCK), 64, 64, 64);
        for (BlockPos b : gold) {
            if (Math.hypot(b.getX() - bastion.pos.getX(), b.getZ() - bastion.pos.getZ()) > 56) continue;
            pts.add(new BastionPlan.Point(b.getX(), b.getY(), b.getZ(), BastionPlan.Kind.GOLD));
        }
        BlockPos at = me.blockPosition();
        // the route (BastionRoutes) decides the order of objectives; the resolver maps each to the scanned blocks
        if ("housing".equals(layout())) {
            BastionRoutes.HousingVariant hv = BastionRoutes.housingVariant(floorCells[0], floorCells[1], BastionSettings.intermediateRoutes);
            route = BastionRoutes.housing(hv);
        } else route = BastionRoutes.forLayout(layout(), false, BastionSettings.intermediateRoutes);
        List<BastionRouteResolver.Target> targets = BastionRouteResolver.resolve(layout(), route, bastion.pos.getX(), bastion.pos.getY(), bastion.pos.getZ(), at.getX(), at.getY(), at.getZ(), pts);
        List<BastionPlan.Point> ordered = new ArrayList<>();
        planStep.clear();
        for (BastionRouteResolver.Target t : targets) { ordered.add(t.point()); if (t.step() != null) planStep.put(new BlockPos(t.point().x(), t.point().y(), t.point().z()), t.step()); }
        plan = ordered;
        if (!route.equals(loggedRoute)) { loggedRoute = route; routeIdx = 0; scriptStep = null; routeStart = ticks; logDirect("Bastion: route " + layout() + " (" + targets.size() + " targets) " + route); }
    }

    // ---- route scripts (BastionScript): the movement steps of Couriway's treasure / housing routes
    private int routeIdx, opLeft = -1, opThrowMark, aimMiss;
    private long routeStart, blockStepIdle;
    private BastionRoutes.Step blockStep;
    private BastionScript.Runner scriptRun;
    private BastionRoutes.Step scriptStep;
    private BlockPos scriptAt, opLava;
    private int sfx = 1, sfz = 0;
    private PathingCommand opCmd;
    private BlockPos opTarget;
    private int opTargetIdx = -1;
    private BastionRoutes.Step opTargetStep;

    /** True while the plan still holds an unlooted chest or unmined gold block claimed by this step. */
    private boolean stepHasTargets(BastionRoutes.Step s) {
        for (Map.Entry<BlockPos, BastionRoutes.Step> e : planStep.entrySet()) {
            if (e.getValue() != s || looted.contains(e.getKey()) || badChest.getOrDefault(e.getKey(), 0L) > ticks || badGold.getOrDefault(e.getKey(), 0L) > ticks) continue;
            if (!ctx.world().hasChunkAt(e.getKey())) return true;
            net.minecraft.world.level.block.state.BlockState st = ctx.world().getBlockState(e.getKey());
            if (st.is(Blocks.CHEST) || st.is(Blocks.GOLD_BLOCK)) return true;
        }
        return false;
    }

    /** Walk the route: block steps are left to lootChests while they have targets; scripted steps run their ops here. */
    private PathingCommand runRoute(Player me, List<LivingEntity> near) {
        if (route.isEmpty() || !BastionSettings.scriptedRoutes) return null;
        while (routeIdx < route.size()) {
            BastionRoutes.Step s = route.get(routeIdx);
            List<BastionScript.Op> ops = BastionScript.ops(s);
            if (ops.isEmpty()) {
                // a block step is over when its targets are gone, or when the loot loop has been working on another
                // step's targets for 20 s (r144: top chests deferred behind a brute, the route never moved past them)
                if (blockStep != s) { blockStep = s; blockStepIdle = ticks; }
                if (currentStep == s) blockStepIdle = ticks;
                if (stepHasTargets(s) && ticks - blockStepIdle < 400) return null;
                if (stepHasTargets(s)) logDirect("Bastion: step " + s + " left for later, the loot loop is elsewhere");
                routeIdx++;
                continue;
            }
            if (scriptStep != s) {
                scriptStep = s;
                scriptRun = new BastionScript.Runner(ops);
                scriptAt = me.blockPosition();
                int dx = bastion.pos.getX() - scriptAt.getX(), dz = bastion.pos.getZ() - scriptAt.getZ();
                if (Math.abs(dx) >= Math.abs(dz)) { sfx = dx >= 0 ? 1 : -1; sfz = 0; } else { sfx = 0; sfz = dz >= 0 ? 1 : -1; }
                opLeft = -1; opLava = null;
                logDirect("Bastion: step " + s + " (" + ops.size() + " ops, facing " + sfx + "," + sfz + ")");
            }
            if (scriptRun.done()) {
                logDirect("Bastion: step " + s + " done" + (scriptRun.skipped() > 0 ? ", " + scriptRun.skipped() + " ops skipped" : ""));
                routeIdx++;
                scriptStep = null;
                continue;
            }
            BastionScript.Op op = scriptRun.current();
            int idx = scriptRun.index();
            opCmd = null;
            BastionScript.Result r = execOp(me, near, s, op);
            scriptRun.report(r);
            if (scriptRun.index() != idx) opLeft = -1;
            status = "route " + s + " op " + (idx + 1) + "/" + ops.size() + " " + op.kind() + (r == BastionScript.Result.FAILED ? " (failed, skipping)" : "");
            return opCmd != null ? opCmd : pause0();
        }
        return null;
    }

    private BlockPos opCell(BastionScript.Op op) {
        int[] w = BastionScript.toWorld(sfx, sfz, op.f(), op.r(), op.u());
        return scriptAt.offset(w[0], w[1], w[2]);
    }

    private BastionScript.Result execOp(Player me, List<LivingEntity> near, BastionRoutes.Step s, BastionScript.Op op) {
        switch (op.kind()) {
            case MOVE: {
                BlockPos c = opCell(op);
                if (me.blockPosition().equals(c)) return BastionScript.Result.DONE;
                opCmd = new PathingCommand(new GoalBlock(c), PathingCommandType.SET_GOAL_AND_PATH);
                return BastionScript.Result.WORKING;
            }
            case MOVE_TARGET: {
                // scanned once per op (a 65x17x65 sweep), not every tick
                if (opTarget == null || opTargetIdx != scriptRun.index() || opTargetStep != s) { opTarget = routeTarget(me, s); opTargetIdx = scriptRun.index(); opTargetStep = s; }
                BlockPos t = opTarget;
                if (t == null) return BastionScript.Result.FAILED;
                if (Math.hypot(me.getX() - (t.getX() + 0.5), me.getZ() - (t.getZ() + 0.5)) < 1.5 && Math.abs(me.getY() - t.getY()) < 2) return BastionScript.Result.DONE;
                opCmd = new PathingCommand(new GoalNear(t, 1), PathingCommandType.SET_GOAL_AND_PATH);
                return BastionScript.Result.WORKING;
            }
            case PLACE: return placeCell(me, opCell(op));
            case MINE: return mineCell(me, opCell(op));
            case PILLAR: return pillarStep(me, op.n());
            case THROW: {
                int ingots = ingotCount(me);
                if (opLeft < 0) { opLeft = op.n() == 0 ? ingots / 2 : op.n(); opThrowMark = distractions; }
                opLeft -= distractions - opThrowMark;
                opThrowMark = distractions;
                if (opLeft <= 0 || ingots <= 0) return BastionScript.Result.DONE;
                Piglin p = nearestUndistracted(me, near);
                if (p == null) for (LivingEntity e : near) if (e instanceof Piglin q && !q.isBaby() && q.isAlive() && me.distanceTo(q) <= 16 && (p == null || me.distanceTo(q) < me.distanceTo(p))) p = q;
                if (p == null) return BastionScript.Result.DONE;
                opCmd = distract(me, p);
                return BastionScript.Result.WORKING;
            }
            case PUSH_ITEMS: {
                for (ItemEntity i : ctx.world().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(4), i -> i.getItem().is(Items.GOLD_BLOCK))) {
                    BlockPos c = i.blockPosition();
                    if (c.equals(me.blockPosition()) || c.equals(me.blockPosition().above()) || !ctx.world().getBlockState(c).canBeReplaced()) continue;
                    BastionScript.Result r = placeCell(me, c);
                    return r == BastionScript.Result.DONE ? BastionScript.Result.WORKING : r;
                }
                return BastionScript.Result.DONE;
            }
            case BLOCK_LAVA: {
                if (opLeft < 0) opLeft = op.n();
                if (opLeft <= 0) return BastionScript.Result.DONE;
                BlockPos best = null;
                BlockPos at = me.blockPosition();
                for (BlockPos b : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 1, 5))) {
                    if (!ctx.world().getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA) || !ctx.world().getFluidState(b).isSource()) continue;
                    if (best == null || b.distSqr(at) < best.distSqr(at)) best = b.immutable();
                }
                if (best == null) return BastionScript.Result.DONE;
                BastionScript.Result r = placeCell(me, best);
                if (r == BastionScript.Result.DONE) { opLeft--; return opLeft <= 0 ? BastionScript.Result.DONE : BastionScript.Result.WORKING; }
                return r;
            }
            case LAVA_ON_PIGLIN: return lavaOnStraggler(me, near);
            case WEAR_GOLD: {
                if (wearingGold(me)) return BastionScript.Result.DONE;
                return wearGold(me) ? BastionScript.Result.WORKING : BastionScript.Result.FAILED;
            }
            case UNSTICK: {
                for (LivingEntity e : near) {
                    if (!(e instanceof Piglin p) || !p.isAlive() || me.distanceTo(p) > 6 || !stuck(me, p)) continue;
                    BlockPos pb = p.blockPosition();
                    Direction d = Direction.getApproximateNearest(me.getX() - p.getX(), 0, me.getZ() - p.getZ());
                    BlockPos c = pb.relative(d);
                    if (ctx.world().getBlockState(c).canBeReplaced()) c = pb.below();
                    return mineCell(me, c) == BastionScript.Result.DONE ? BastionScript.Result.DONE : BastionScript.Result.WORKING;
                }
                return BastionScript.Result.DONE;
            }
            default: return BastionScript.Result.FAILED;
        }
    }

    /** World target of a MOVE_TARGET step. */
    private BlockPos routeTarget(Player me, BastionRoutes.Step s) {
        BlockPos at = me.blockPosition();
        if (s == BastionRoutes.Step.TAKE_THIRD_BASALT_BRIDGE) {
            // basalt bridge columns between us and the centre; lateral offset along our right-hand axis
            List<BlockPos> tops = new ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(at.offset(-32, -10, -32), at.offset(32, 6, 32))) {
                if (!ctx.world().getBlockState(b).is(Blocks.BASALT) && !ctx.world().getBlockState(b).is(Blocks.POLISHED_BASALT)) continue;
                if (!ctx.world().getBlockState(b.above()).isAir()) continue;
                tops.add(b.immutable());
            }
            if (tops.isEmpty()) return null;
            List<Integer> lat = new ArrayList<>();
            for (BlockPos b : tops) { int l = -sfz * (b.getX() - at.getX()) + sfx * (b.getZ() - at.getZ()); if (!lat.contains(l)) lat.add(l); }
            int want = BastionScript.thirdFromLeft(lat);
            BlockPos best = null;
            for (BlockPos b : tops) if (-sfz * (b.getX() - at.getX()) + sfx * (b.getZ() - at.getZ()) == want && (best == null || b.distSqr(at) < best.distSqr(at))) best = b;
            return best == null ? null : best.above();
        }
        if (s == BastionRoutes.Step.CROSS_BRICK_BRIDGE) {
            BlockPos best = null;
            for (BlockPos b : BlockPos.betweenClosed(at.offset(-24, -6, -24), at.offset(24, 8, 24))) {
                if (!(ctx.world().getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.StairBlock) || !ctx.world().getBlockState(b.above()).isAir()) continue;
                if (b.distSqr(bastion.pos) >= at.distSqr(bastion.pos)) continue; // stairs on the far side of the bridge
                if (best == null || b.distSqr(at) < best.distSqr(at)) best = b.immutable();
            }
            return best == null ? bastion.pos : best.above();
        }
        return bastion.pos;
    }

    /** Place a throwaway block into the cell from any solid neighbour face in reach. */
    private BastionScript.Result placeCell(Player me, BlockPos cell) {
        if (!ctx.world().getBlockState(cell).canBeReplaced()) return BastionScript.Result.DONE;
        if (me.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cell))) return BastionScript.Result.FAILED;
        int slot = shelterBlock(me, false);
        if (slot < 0) return BastionScript.Result.FAILED;
        Vec3 eye = me.getEyePosition(1.0F);
        BlockPos sup = null; Direction face = null; Vec3 aim = null;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!solid(n)) continue;
            Vec3 a = Vec3.atCenterOf(n).add(d.getOpposite().getStepX() * 0.5, d.getOpposite().getStepY() * 0.5, d.getOpposite().getStepZ() * 0.5);
            if (eye.distanceTo(a) > 4.4) continue;
            if (aim == null || eye.distanceTo(a) < eye.distanceTo(aim)) { sup = n; face = d.getOpposite(); aim = a; }
        }
        if (aim == null) {
            if (eye.distanceTo(Vec3.atCenterOf(cell)) > 4.4) { opCmd = new PathingCommand(new GoalNear(cell, 2), PathingCommandType.SET_GOAL_AND_PATH); return BastionScript.Result.WORKING; }
            return BastionScript.Result.FAILED;
        }
        if (slot >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 8, ContainerInput.SWAP, me); return BastionScript.Result.WORKING; }
        me.getInventory().setSelectedSlot(slot);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true); // do not open a chest or door we click on
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, aim, ctx.playerRotations()), true);
        if (ctx.minecraft().hitResult instanceof BlockHitResult h && h.getBlockPos().equals(sup) && h.getDirection() == face) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            aimMiss = 0;
        } else if (++aimMiss > 30) {
            // r146: a pillar side face is hidden from on top of the pillar; 30 ticks without sight of the face is a miss, not 10 s
            aimMiss = 0;
            return BastionScript.Result.FAILED;
        }
        return BastionScript.Result.WORKING;
    }

    /** Mine the cell with the pickaxe; never one that opens onto lava. */
    private BastionScript.Result mineCell(Player me, BlockPos cell) {
        net.minecraft.world.level.block.state.BlockState st = ctx.world().getBlockState(cell);
        if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) return BastionScript.Result.DONE;
        if (!st.getFluidState().isEmpty() || lavaBeside(cell)) return BastionScript.Result.FAILED;
        Vec3 eye = me.getEyePosition(1.0F);
        if (eye.distanceTo(Vec3.atCenterOf(cell)) > 4.4) { opCmd = new PathingCommand(new GoalNear(cell, 2), PathingCommandType.SET_GOAL_AND_PATH); return BastionScript.Result.WORKING; }
        int pick = pickaxeSlot(me);
        if (pick < 0) return BastionScript.Result.FAILED;
        if (pick >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ContainerInput.SWAP, me); return BastionScript.Result.WORKING; }
        me.getInventory().setSelectedSlot(pick);
        Rotation rot = RotationUtils.calcRotationFromVec3d(eye, Vec3.atCenterOf(cell), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true);
        if (ctx.isLookingAt(cell)) { baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true); aimMiss = 0; }
        else if (++aimMiss > 40) { aimMiss = 0; return BastionScript.Result.FAILED; } // something in the way of the cell
        return BastionScript.Result.WORKING;
    }

    /** Jump-place under ourselves until we stand n above where the step began. */
    private BastionScript.Result pillarStep(Player me, int n) {
        if (me.blockPosition().getY() >= scriptAt.getY() + n && me.onGround()) return BastionScript.Result.DONE;
        if (!headroom(me, me.blockPosition().getY())) return BastionScript.Result.FAILED;
        int slot = shelterBlock(me, false);
        if (slot < 0) return BastionScript.Result.FAILED;
        if (slot >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 8, ContainerInput.SWAP, me); return BastionScript.Result.WORKING; }
        me.getInventory().setSelectedSlot(slot);
        baritone.getLookBehavior().updateTarget(new Rotation(me.getYRot(), 90), true);
        if (me.onGround()) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        else if (me.getY() - Math.floor(me.getY()) > 0.15 || me.getDeltaMovement().y < 0) {
            BlockPos under = BlockPos.containing(me.getX(), me.getY() - 0.2, me.getZ());
            if (ctx.world().getBlockState(under).canBeReplaced() && solid(under.below())) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        }
        return BastionScript.Result.WORKING;
    }

    /** Pour lava under the nearest hostile piglin within 6, then scoop it back up. */
    private BastionScript.Result lavaOnStraggler(Player me, List<LivingEntity> near) {
        Vec3 eye = me.getEyePosition(1.0F);
        if (opLava != null) {
            if (!realLava(opLava)) { opLava = null; return BastionScript.Result.DONE; }
            int b = slotOf(me, Items.BUCKET);
            if (b < 0 || b >= 9) { opLava = null; return BastionScript.Result.DONE; }
            me.getInventory().setSelectedSlot(b);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, Vec3.atCenterOf(opLava), ctx.playerRotations()), true);
            if (ticks % 4 == 0) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            return BastionScript.Result.WORKING;
        }
        LivingEntity t = null;
        for (LivingEntity e : near) if ((e instanceof Piglin p && p.isAggressive() || e instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && e.isAlive()
                && me.distanceTo(e) > 2 && me.distanceTo(e) < 6 && (t == null || me.distanceTo(e) < me.distanceTo(t))) t = e;
        if (t == null) return BastionScript.Result.DONE;
        int lb = slotOf(me, Items.LAVA_BUCKET);
        if (lb < 0 || lb >= 9) return BastionScript.Result.FAILED;
        BlockPos fl = t.blockPosition().below();
        if (!solid(fl)) return BastionScript.Result.WORKING;
        me.getInventory().setSelectedSlot(lb);
        Vec3 a = Vec3.atCenterOf(fl).add(0, 0.5, 0);
        if (eye.distanceTo(a) > 4.4) return BastionScript.Result.WORKING;
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, a, ctx.playerRotations()), true);
        if (ctx.minecraft().hitResult instanceof BlockHitResult h && h.getBlockPos().equals(fl) && h.getDirection() == Direction.UP) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            opLava = fl.above();
        }
        return BastionScript.Result.WORKING;
    }

    // ---- brute tactics (BruteTactics): lava bucket, or a sealed brute pit; never a direct hit
    private BlockPos btLava, btPit, btPitCover;
    private long btPourTick, btPitSince, btDbg = -1000;
    private int btPitPhase; // 0 none, 1 dig, 2 lure, 3 cover, 4 holding
    private java.util.UUID btBrute;

    private LavaFlow.Grid liveGrid() {
        var w = ctx.world();
        return new LavaFlow.Grid() {
            public int lava(int x, int y, int z) {
                var fs = w.getFluidState(new BlockPos(x, y, z));
                if (!fs.is(net.minecraft.tags.FluidTags.LAVA)) return -1;
                if (fs.isSource() || fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return 0;
                return Math.max(0, 8 - fs.getAmount());
            }
            public boolean open(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                if (!w.isLoaded(q)) return false;
                net.minecraft.world.level.block.state.BlockState s = w.getBlockState(q);
                return s.getFluidState().isEmpty() && (s.isAir() || s.canBeReplaced());
            }
        };
    }

    private PathingCommand bruteTactic(Player me, List<LivingEntity> heavies) {
        String setting = BastionSettings.bruteTactic;
        if ("off".equals(setting)) return null;
        Vec3 eye = me.getEyePosition(1.0F);
        // 1. lava already poured: wait while it burns, then scoop it back
        if (btLava != null) {
            LivingEntity b = null;
            for (LivingEntity e : heavies) if (e.getUUID().equals(btBrute)) b = e;
            boolean drops = !ctx.world().getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(btLava).inflate(3), i -> !i.getItem().is(Items.GOLD_INGOT)).isEmpty();
            if (!realLava(btLava)) { logDirect("Bastion: brute lava: lava gone" + (b == null ? ", brute dead or away" : ", brute at " + String.format("%.1f", me.distanceTo(b)))); btLava = null; return null; }
            if (b != null && me.distanceTo(b) < 2.5) return null; // too close: the shelter takes it
            if (BruteTactics.scoopNow(ticks - btPourTick, b != null && b.isAlive(), b != null && b.isInLava(), drops)) {
                int s = slotOf(me, Items.BUCKET);
                if (s < 0 || s >= 9) { logDirect("Bastion: brute lava: no empty bucket in the hotbar to scoop"); btLava = null; return null; }
                me.getInventory().setSelectedSlot(s);
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, Vec3.atCenterOf(btLava), ctx.playerRotations()), true);
                if (eye.distanceTo(Vec3.atCenterOf(btLava)) > 4.4) return new PathingCommand(new GoalNear(btLava, 3), PathingCommandType.SET_GOAL_AND_PATH);
                if (ticks % 4 == 0) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                status = "brute lava: scooping";
                if (ticks - btPourTick > BruteTactics.BURN_TICKS + 200) { logDirect("Bastion: brute lava: could not scoop, leaving it"); btLava = null; }
                return pause0();
            }
            status = "brute lava: burning (" + (b == null ? "gone" : String.format("%.0f hp", b.getHealth())) + ")";
            return pause0();
        }
        // 2. brute pit in progress
        if (btPitPhase > 0) return brutePit(me, heavies);
        // 3. a brute to deal with?
        LivingEntity b = null;
        // own query: heavies drops brutes on other levels or out of reach, but a brute standing at the piglins still blocks trading (r150/r151)
        for (PiglinBrute e : ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(24), PiglinBrute::isAlive))
            if (b == null || me.distanceTo(e) < me.distanceTo(b)) b = e;
        if (b == null) return null;
        final LivingEntity fb = b;
        boolean blocksTrading = !ctx.world().getEntitiesOfClass(Piglin.class, b.getBoundingBox().inflate(BruteTactics.BLOCKS_TRADE), p -> p.isAlive() && !p.isBaby()).isEmpty() && me.distanceTo(b) < 20;
        int lb = slotOf(me, Items.LAVA_BUCKET);
        boolean hasLava = lb >= 0;
        BlockPos target = null;
        if (hasLava) {
            LavaFlow.Grid g = liveGrid();
            BlockPos m = me.blockPosition();
            for (int[] c : BruteTactics.pourCandidates(b.getBlockX(), b.getBlockY(), b.getBlockZ(), m.getX(), m.getZ())) {
                BlockPos t = new BlockPos(c[0], c[1], c[2]);
                if (!solid(t.below()) || !ctx.world().getBlockState(t).canBeReplaced()) continue;
                if (eye.distanceTo(Vec3.atCenterOf(t.below()).add(0, 0.5, 0)) > 4.4) continue;
                if (BruteTactics.pourSafe(g, t.getX(), t.getY(), t.getZ(), m.getX(), m.getY(), m.getZ())) { target = t; break; }
            }
        }
        BlockPos hole = holes.isEmpty() ? me.blockPosition() : holes.get(0);
        int[] site = null;
        for (int[] s : BruteTactics.pitSites(hole.getX(), hole.getY(), hole.getZ(), me.getBlockX(), me.getBlockZ())) {
            BlockPos p = new BlockPos(s[0], s[1], s[2]);
            if (!solid(p.below(2)) || lavaBeside(p) || lavaBeside(p.below())) continue;
            if (!solid(p) && !ctx.world().getBlockState(p).canBeReplaced()) continue;
            site = s; break;
        }
        BruteTactics.Plan plan = BruteTactics.choose(setting, me.distanceTo(b), blocksTrading, hasLava, target != null, site != null);
        if (plan == BruteTactics.Plan.NONE && ticks - btDbg >= 200 && (btDbg = ticks) >= 0) logDirect("BAST dbg brute tactic: none (dist " + String.format("%.1f", me.distanceTo(b)) + ", blocksTrade=" + blocksTrading + ", lava=" + hasLava + ", safePour=" + (target != null) + ", pitSite=" + (site != null) + ", holes=" + holes.size() + ")");
        if (plan == BruteTactics.Plan.NONE && hasLava && blocksTrading && !((Mob) b).isAggressive() && me.distanceTo(b) > 6 && me.getHealth() >= 14) {
            // a calm brute guarding the piglins: walk up to pouring range (it ignores us until hit or close)
            status = "brute lava: closing on the brute guarding the piglins";
            return new PathingCommand(new GoalNear(b.blockPosition(), 5), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (plan == BruteTactics.Plan.NONE) return null;
        if (plan == BruteTactics.Plan.PIT) {
            btPit = new BlockPos(site[0], site[1], site[2]);
            btPitPhase = 1; btPitSince = ticks; btBrute = b.getUUID();
            logDirect("Bastion: brute pit: digging at " + btPit.toShortString() + " (brute at " + String.format("%.1f", me.distanceTo(b)) + ")");
            return brutePit(me, heavies);
        }
        if (lb >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, lb, 6, ContainerInput.SWAP, me); return pause0(); }
        me.getInventory().setSelectedSlot(lb);
        BlockPos fl = target.below();
        Vec3 a = Vec3.atCenterOf(fl).add(0, 0.5, 0);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, a, ctx.playerRotations()), true);
        status = "brute lava: aiming at " + target.toShortString();
        if (ctx.minecraft().hitResult instanceof BlockHitResult h && h.getBlockPos().equals(fl) && h.getDirection() == Direction.UP) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            btLava = target; btPourTick = ticks; btBrute = b.getUUID();
            logDirect("Bastion: brute lava: poured at " + target.toShortString() + " (brute at " + String.format("%.1f", me.distanceTo(b)) + ", " + (int) b.getHealth() + " hp" + (blocksTrading ? ", by the piglins" : "") + ")");
        }
        return pause0();
    }

    private PathingCommand brutePit(Player me, List<LivingEntity> heavies) {
        LivingEntity b = null;
        for (LivingEntity e : heavies) if (e.getUUID().equals(btBrute)) b = e;
        if (b == null) for (LivingEntity e : ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(32), PiglinBrute::isAlive)) if (e.getUUID().equals(btBrute)) b = e;
        boolean in = b != null && BruteTactics.inPit(btPit.getX(), btPit.getY(), btPit.getZ(), b.getBlockX(), b.getY(), b.getBlockZ());
        if (btPitPhase == 4) {
            // holding: watch it, log an escape or a broken wall once
            if (b == null || !b.isAlive()) { logDirect("Bastion: brute pit: brute gone"); btPitPhase = 0; return null; }
            if (!in) { logDirect("Bastion: brute pit: escaped (" + where(b, me) + ", cover " + (solid(btPitCover) ? "intact" : "broken") + ", " + (ticks - btPitSince) / 20 + " s)"); btPitPhase = 0; return null; }
            for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(btPit.relative(d)) || !solid(btPit.below().relative(d))) { logDirect("Bastion: brute pit: wall open at " + d + " (aggressive=" + ((Mob) b).isAggressive() + ")"); btPitPhase = 0; return null; }
            return null; // trapped: carry on trading
        }
        if (b == null || !b.isAlive()) { logDirect("Bastion: brute pit: brute gone before it was trapped"); btPitPhase = 0; return null; }
        if (ticks - btPitSince > 1200) { logDirect("Bastion: brute pit: gave up in phase " + btPitPhase); btPitPhase = 0; return null; }
        if (btPitPhase == 1) {
            BastionScript.Result r = mineCell(me, btPit);
            if (r == BastionScript.Result.DONE) r = mineCell(me, btPit.below());
            if (r == BastionScript.Result.FAILED) { logDirect("Bastion: brute pit: could not dig"); btPitPhase = 0; return null; }
            if (ctx.world().getBlockState(btPit).canBeReplaced() && ctx.world().getBlockState(btPit.below()).canBeReplaced()) { btPitPhase = 2; btPitSince = ticks; logDirect("Bastion: brute pit: dug, luring"); }
            status = "brute pit: digging";
            return opCmd != null ? opCmd : pause0();
        }
        if (btPitPhase == 2) {
            if (DamageBudget.abort(me.getHealth(), DamageBudget.BRUTE_HIT) && me.getHealth() < me.getMaxHealth()) { logDirect("Bastion: brute pit: hp " + (int) me.getHealth() + " under the damage budget, retreating"); btPitPhase = 0; return null; }
            if (in) { btPitPhase = 3; btPitSince = ticks; btPitCover = btPit.above(); logDirect("Bastion: brute pit: trapped, covering"); return pause0(); }
            if (ticks - btPitSince > BruteTactics.LURE_TICKS) { logDirect("Bastion: brute pit: lure failed (brute " + where(b, me) + ")"); btPitPhase = 0; return null; }
            int[] st = BruteTactics.lureStand(btPit.getX(), btPit.getY(), btPit.getZ(), b.getX(), b.getZ());
            BlockPos s = new BlockPos(st[0], st[1], st[2]);
            if (solid(s)) s = s.above(); // the stand is on the surface beside the pit, not in its wall
            status = "brute pit: luring from " + s.toShortString();
            if (!me.blockPosition().equals(s)) return new PathingCommand(new GoalBlock(s), PathingCommandType.SET_GOAL_AND_PATH);
            return pause0();
        }
        if (btPitPhase == 3) {
            opCmd = null;
            // r152: placeCell walked 15 blocks off to build a footing; the cover sits on the pit walls, so place it from here
            if (ticks - btPitSince > 200) { logDirect("Bastion: brute pit: could not cover in 10 s"); btPitPhase = 0; return null; }
            if (me.getEyePosition(1.0F).distanceTo(Vec3.atCenterOf(btPitCover)) > 4.4) return new PathingCommand(new GoalNear(btPitCover, 2), PathingCommandType.SET_GOAL_AND_PATH);
            BastionScript.Result r = placeCell(me, btPitCover);
            if (r == BastionScript.Result.DONE) { btPitPhase = 4; btPitSince = ticks; logDirect("Bastion: brute pit: covered, brute trapped"); return null; }
            if (r == BastionScript.Result.FAILED) { logDirect("Bastion: brute pit: could not cover"); btPitPhase = 0; return null; }
            status = "brute pit: covering";
            return opCmd != null ? opCmd : pause0();
        }
        return null;
    }

    // ---- post-route fallbacks: trade outside the bastion, then warped-forest endermen (OutsideTrade, EndermanHunt, DamageBudget)
    private OutsideTrade.Mode otMode = OutsideTrade.Mode.INSIDE;
    private BlockPos otGoal, ehStand;
    private long otSince, otLastDrop, ehSince;
    private boolean ehActive;

    private boolean routeFinished() { return !BastionSettings.scriptedRoutes || route.isEmpty() || routeIdx >= route.size(); }

    /** Nearest sampled cell (16-block grid, radius r) whose biome id path is one of {@code ids}, or null. */
    private BlockPos nearestBiome(Player me, int r, String... ids) {
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (int x = -r; x <= r; x += 16) for (int z = -r; z <= r; z += 16) {
            BlockPos q = new BlockPos(me.getBlockX() + x, me.getBlockY(), me.getBlockZ() + z);
            if (!ctx.world().isLoaded(q)) continue;
            String id = ctx.world().getBiome(q).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            for (String s : ids) if (s.equals(id) && Math.hypot(x, z) < bd) { bd = Math.hypot(x, z); best = q; }
        }
        return best;
    }

    private PathingCommand outsideFallback(Player me, List<Piglin> piglins, Map<String, Integer> have, boolean done) {
        int free = 0, guarded = 0;
        for (Piglin p : piglins) { if (!p.isAlive() || p.isBaby()) continue; if (brutesAround(p) > 0) guarded++; else free++; }
        if (otMode == OutsideTrade.Mode.INSIDE || ticks - otSince > 2400) {
            BlockPos w = nearestBiome(me, 160, "nether_wastes", "crimson_forest");
            OutsideTrade.Mode m = OutsideTrade.choose(OutsideTrade.setting, routeFinished(), done, free, guarded, w == null ? -1 : Math.sqrt(me.blockPosition().distSqr(w)));
            if (m != otMode) {
                logDirect("Bastion: outside trade: " + m + " (free " + free + ", guarded " + guarded + (w == null ? ", no wastes in 160" : ", wastes at " + w.toShortString()) + ")");
                otMode = m; otSince = ticks;
                if (m == OutsideTrade.Mode.LURE_OUT) {
                    BlockPos c = bastion.pos; double dx = me.getX() - c.getX(), dz = me.getZ() - c.getZ(), l = Math.max(1, Math.hypot(dx, dz));
                    int r = BastionSettings.exitDistance + 8;
                    otGoal = new BlockPos(c.getX() + (int) (dx / l * r), me.getBlockY(), c.getZ() + (int) (dz / l * r));
                } else if (m == OutsideTrade.Mode.WASTES) otGoal = w;
            }
        }
        if (otMode == OutsideTrade.Mode.LURE_OUT) {
            if (OutsideTrade.outside(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ(), BastionSettings.exitDistance)) {
                long followers = piglins.stream().filter(p -> p.isAlive() && !p.isBaby() && me.distanceTo(p) < 10 && brutesAround(p) == 0).count();
                logDirect("Bastion: outside trade: outside the walls, " + followers + " piglins followed; trading here");
                otMode = OutsideTrade.Mode.DONE; return null; // the normal trade hole/pit logic takes the free piglins from here
            }
            // drop an ingot every 5 s as we walk out: piglins go for it and follow the trail
            if (ticks - otLastDrop > 100 && ingotCount(me) > 6) { int s = slotOf(me, Items.GOLD_INGOT); if (s >= 0 && s < 9) { me.getInventory().setSelectedSlot(s); if (safeDrop(me)) otLastDrop = ticks; } }
            status = "outside trade: luring piglins out";
            return new PathingCommand(new GoalXZ(otGoal.getX(), otGoal.getZ()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (otMode == OutsideTrade.Mode.WASTES) {
            if (me.blockPosition().distSqr(otGoal) < 64) { logDirect("Bastion: outside trade: in the wastes, trading here"); otMode = OutsideTrade.Mode.DONE; return null; }
            status = "outside trade: to the wastes at " + otGoal.toShortString();
            return new PathingCommand(new GoalXZ(otGoal.getX(), otGoal.getZ()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (otMode == OutsideTrade.Mode.INSIDE && routeFinished()) return endermanHunt(me, free > 0, have);
        return null;
    }

    private PathingCommand endermanHunt(Player me, boolean freePiglins, Map<String, Integer> have) {
        int pearls = have.getOrDefault("ender_pearl", 0), target = BastionSettings.TARGETS.getOrDefault("ender_pearl", 12);
        BlockPos warped = ehStand != null ? ehStand : nearestBiome(me, EndermanHunt.maxWarpedDist, "warped_forest");
        if (!EndermanHunt.go(EndermanHunt.setting, otMode, freePiglins, warped == null ? -1 : Math.sqrt(me.blockPosition().distSqr(warped)), pearls, target)) {
            if (ehActive) { logDirect("Bastion: enderman hunt: stop, pearls " + pearls + "/" + target); ehActive = false; }
            return null;
        }
        if (!ehActive) { ehActive = true; ehSince = ticks; logDirect("Bastion: enderman hunt: warped forest at " + warped.toShortString() + ", pearls " + pearls + "/" + target); }
        // 1. reach the forest and find a 2-high gap (endermen are 3 tall and cannot follow us in)
        if (ehStand == null) {
            if (me.blockPosition().distSqr(warped) > 400) { status = "enderman hunt: to the warped forest"; return new PathingCommand(new GoalNear(warped, 4), PathingCommandType.SET_GOAL_AND_PATH); }
            BlockPos m = me.blockPosition();
            for (int dx = -8; dx <= 8 && ehStand == null; dx++) for (int dz = -8; dz <= 8 && ehStand == null; dz++) for (int dy = -3; dy <= 3 && ehStand == null; dy++) {
                BlockPos q = m.offset(dx, dy, dz);
                if (EndermanHunt.safeGap(solid(q.below()), ctx.world().getBlockState(q).isAir(), ctx.world().getBlockState(q.above()).isAir(), solid(q.above(2)), lavaBeside(q))) ehStand = q;
            }
            if (ehStand == null) {
                // none here: dig one: stand cell plus head cell into a solid face, leaving the ceiling
                for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos q = m.relative(d); if (solid(q) && solid(q.above()) && solid(q.above(2)) && solid(q.below()) && !lavaBeside(q)) { ehStand = q; logDirect("Bastion: enderman hunt: digging a 2-high gap at " + q.toShortString()); break; } }
                if (ehStand == null) { status = "enderman hunt: looking for a gap"; return new PathingCommand(new GoalNear(warped.offset((int) (ticks / 200 % 3 - 1) * 8, 0, 8), 2), PathingCommandType.SET_GOAL_AND_PATH); }
            }
        }
        if (solid(ehStand) || solid(ehStand.above())) {
            BastionScript.Result r = mineCell(me, solid(ehStand) ? ehStand : ehStand.above());
            if (r == BastionScript.Result.FAILED) { ehStand = null; return pause0(); }
            status = "enderman hunt: digging the gap"; return opCmd != null ? opCmd : pause0();
        }
        if (!me.blockPosition().equals(ehStand)) { status = "enderman hunt: into the gap"; return new PathingCommand(new GoalBlock(ehStand), PathingCommandType.SET_GOAL_AND_PATH); }
        // 2. in the gap: fight an angry one in reach, else stare at the nearest calm one to provoke it
        List<Enderman> ems = ctx.world().getEntitiesOfClass(Enderman.class, me.getBoundingBox().inflate(32), Enderman::isAlive);
        int angry = (int) ems.stream().filter(Enderman::isCreepy).count();
        if (DamageBudget.abort(me.getHealth(), 7) && angry > 0) status = "enderman hunt: low hp, holding the gap";
        Vec3 eye = me.getEyePosition(1.0F);
        Enderman best = null;
        for (Enderman e : ems) if (best == null || me.distanceTo(e) < me.distanceTo(best)) best = e;
        if (best == null) { status = "enderman hunt: waiting for endermen"; return pause0(); }
        int sw = slotOf(me, Items.DIAMOND_SWORD); if (sw >= 0 && sw < 9) me.getInventory().setSelectedSlot(sw);
        if (best.isCreepy() || EndermanHunt.provoke(me.distanceTo(best), angry)) {
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, best.getEyePosition(1.0F), ctx.playerRotations()), true);
            if (best.isCreepy() && me.distanceTo(best) < 3.2 && me.getAttackStrengthScale(0) >= 0.95F && ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult h && h.getEntity() == best)
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            status = best.isCreepy() ? "enderman hunt: fighting from the gap" : "enderman hunt: provoking at " + String.format("%.0f", me.distanceTo(best));
        } else status = "enderman hunt: waiting (" + angry + " angry)";
        return pause0();
    }

    private PathingCommand lootChests(Player me, List<LivingEntity> near) {
        if (me.containerMenu instanceof ChestMenu cm) return takeFromChest(me, cm);
        if (Math.hypot(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ()) > 64) return null;
        if (ticks - chestScan > 100 || !bastion.pos.equals(planFor)) replan(me);
        BlockPos next = null;
        boolean unloaded = false;
        Map<String, Integer> have = counts(me);
        boolean wantGold = BastionGoals.needGold(BastionGoals.missing(have, BastionSettings.TARGETS),
                BastionGoals.throwable(ingotCount(me), countOf(me, Items.GOLD_BLOCK)), BastionSettings.keepIngots, BastionSettings.goldCap);
        for (BastionPlan.Point pt : plan) {
            BlockPos b = new BlockPos(pt.x(), pt.y(), pt.z());
            if (!ctx.world().hasChunkAt(b)) { unloaded = true; continue; }
            if (looted.contains(b) || badChest.getOrDefault(b, 0L) > ticks || badGold.getOrDefault(b, 0L) > ticks) continue;
                // run 84: skipped one chest, then looped 9 min on its neighbour; a skipped target rules out its whole cluster
                boolean nearBad = false;
                for (Map.Entry<BlockPos, Long> e : badChest.entrySet()) if (e.getValue() > ticks && e.getKey().distManhattan(b) <= 8) { nearBad = true; break; }
                if (nearBad) continue;
            // a brute guarding it (even one we gave up fighting) kills a speedrun kit: come back when it has moved
            if (!ctx.world().getEntitiesOfClass(PiglinBrute.class, new net.minecraft.world.phys.AABB(b).inflate(9), PiglinBrute::isAlive).isEmpty()) { unloaded = true; continue; } // deferred, not done
            if (pt.kind() == BastionPlan.Kind.GOLD) {
                boolean rg = BastionSettings.scriptedRoutes && planStep.containsKey(b) && BastionRouteResolver.breaksGold(planStep.get(b));
                if (!(wantGold || rg) || !ctx.world().getBlockState(b).is(Blocks.GOLD_BLOCK)) continue;
            } else if (!ctx.world().getBlockState(b).is(Blocks.CHEST)) continue;
            next = b;
            BastionRoutes.Step ps = planStep.get(b);
            if (ps != null && ps != currentStep && pt.kind() == BastionPlan.Kind.CHEST) { currentStep = ps; logDirect("Bastion: step " + ps); }
            break;
        }
        // only "no chest left" once we stand in the bastion with its chunks loaded; from outside an empty plan means nothing
        chestsExhausted = next == null && !unloaded && Math.hypot(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ()) < 40;
        if (next == null) return null;
        // a route gold step (bridge chalice, treasure pit gold, housing wall gold) mines regardless of the ingots we hold
        boolean routeGold = BastionSettings.scriptedRoutes && planStep.containsKey(next) && BastionRouteResolver.breaksGold(planStep.get(next));
        if (ctx.world().getBlockState(next).is(Blocks.GOLD_BLOCK) && ingotCount(me) >= 4 && !routeGold) {
            // runs 20/27 walked to gold blocks holding 20+ ingots: trade those first, mine only when nearly out
            badGold.put(next, ticks + 600);
            return null;
        }
        if (ctx.world().getBlockState(next).is(Blocks.GOLD_BLOCK)) {
            // gold block next in the plan: the miner handles the piglin rule (breaking gold angers every piglin in 16, seen or not)
            // Couriway ch. 3: distract undistracted piglins within 16 with an ingot before breaking gold
            BastionRoutes.Step st = planStep.getOrDefault(next, BastionRoutes.Step.TRADE);
            Piglin undistracted = nearestUndistracted(me, near);
            double ud = undistracted == null ? Double.MAX_VALUE : me.distanceTo(undistracted);
            if (undistracted != null && ingotCount(me) > 0 && (planStep.containsKey(next) ? BastionRoutes.needsDistractFirst(st, ud) : !BastionPriors.mayBreakGold(ud))) {
                status = "distract first (" + st + ", piglin at " + String.format("%.1f", ud) + ")";
                return distract(me, undistracted);
            }
            if (currentStep != st) { currentStep = st; logDirect("Bastion: step " + st); }
            preferGold = next;
            PathingCommand g = drop(me, next);
            if (g != null) return g;
            PathingCommand m = acquireGold(me, near);
            if (m != null) return m;
            badGold.put(next, ticks + 1200);
            return null;
        }
        if (!next.equals(chestTarget)) {
            chestTarget = next;
            chestSince = ticks;
            chestWaitSince = 0;
        } else if (ticks - chestSince > 900) {
            badChest.put(next, ticks + 2400);
            chestTarget = null;
            status = "chest unreachable, skipping";
            return pause0();
        }
        double d = me.getEyePosition(1.0F).distanceTo(Vec3.atCenterOf(next));
        Rotation rot = d <= 4.3 ? aimAt(me, next) : null;
        if (rot == null) {
            PathingCommand bt = barterOnTheWay(me, near);
            if (bt != null) return bt;
            PathingCommand dc = drop(me, next);
            if (dc != null) return dc;
            status = "to chest " + next.toShortString() + " (" + layout() + ", " + plan.size() + " planned)";
            return new PathingCommand(new GoalGetToBlock(next), PathingCommandType.SET_GOAL_AND_PATH);
        }
        boolean watched = piglinWatching(me, near);
        if (watched) lastWatched = ticks;
        // line of sight flickers: a piglin seen in the last second still counts (run 31 opened in a gap and was mobbed)
        if (watched || ticks - lastWatched < 30) {
            if (chestWaitSince == 0) chestWaitSince = ticks;
            if (ticks - chestWaitSince < BastionSettings.chestWaitTicks) {
                status = "chest: piglins watching, waiting";
                return pause0();
            }
            // opening a chest angers every piglin that sees it, gold armour or not (run 13: 17 -> 6 hp after opening anyway):
            // never open in view, come back later
            badChest.put(next, ticks + 600);
            chestTarget = null;
            return null;
        }
        baritone.getLookBehavior().updateTarget(rot, true);
        // aimAt already proved this rotation hits the chest; the smoothed aim may never settle exactly, so do not wait for it
        if (ticks - chestClickTick > 10) {
            HitResult h = RayTraceUtils.rayTraceTowards(me, rot, ctx.playerController().getBlockReachDistance(), false);
            if (h instanceof BlockHitResult bh && bh.getBlockPos().equals(next)) {
                ctx.playerController().processRightClickBlock((LocalPlayer) me, ctx.world(), InteractionHand.MAIN_HAND, bh);
                me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                chestClickTick = ticks;
                openedChest = next;
            }
        }
        status = "opening chest (hit " + hitDesc(me, next) + ", menu " + me.containerMenu.getClass().getSimpleName() + ")";
        return pause0();
    }

    /** A calm piglin within throwing range while we walk the chest route: throw it an ingot without leaving the route. */
    private PathingCommand barterOnTheWay(Player me, List<LivingEntity> near) {
        admiring.values().removeIf(t -> ticks - t > ADMIRE_TICKS);
        if (!BastionGoals.shouldThrow(counts(me), BastionSettings.TARGETS, ingotCount(me), BastionSettings.keepIngots, admiring.size(), BastionSettings.maxConcurrentBarters)) return null;
        Piglin target = null;
        for (LivingEntity e : near) {
            if (!(e instanceof Piglin p) || p.isBaby() || !p.isAlive() || p.isAggressive() || admiring.containsKey(p.getUUID()) || p.getOffhandItem().is(Items.GOLD_INGOT)) continue;
            // calm piglins within 14 that we can see are worth a short detour (runs 20/27: 6 throws in 300 s with 20+ ingots)
            if (me.distanceTo(p) > 14 || !me.hasLineOfSight(p) || brutesAround(p) >= 1 || Math.abs(p.getY() - me.getY()) > 3) continue;
            if (me.distanceTo(p) > THROW_RANGE && (lavaDropNear(p.blockPosition()) || lavaNearPos(p.blockPosition()))) continue;
            if (target == null || me.distanceTo(p) < me.distanceTo(target)) target = p;
        }
        if (target == null) return null;
        if (me.distanceTo(target) > THROW_RANGE) {
            status = "detour to trade with piglin at " + String.format("%.1f", me.distanceTo(target));
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        int slot = hotbarGold(me);
        if (slot < 0) return pause0();
        me.getInventory().setSelectedSlot(slot);
        aimer.look(target.position().add(0, 0.4, 0), 0);
        if (Math.abs(Mth.wrapDegrees(me.getYRot() - yawTo(me, target))) < 12) {
            if (!safeDrop(me)) return pause0();
            admiring.put(target.getUUID(), ticks);
            throwTick = ticks;
            throwsDone++;
        }
        status = "barter on the way (" + admiring.size() + " admiring)";
        return pause0();
    }

    private boolean roomFor(Player me, ItemStack s) {
        if (me.getInventory().getFreeSlot() >= 0) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack h = me.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(h, s) && h.getCount() < h.getMaxStackSize()) return true;
        }
        return false;
    }

    private PathingCommand takeFromChest(Player me, ChestMenu cm) {
        if (ticks - lastTake < 2) return pause0();
        int n = cm.getRowCount() * 9;
        for (int i = 0; i < n; i++) {
            ItemStack s = cm.getSlot(i).getItem();
            if (s.isEmpty() || !BastionGoals.chestUseful(itemId(s), s.has(DataComponents.FOOD)) || !roomFor(me, s)) continue;
            ctx.playerController().windowClick(cm.containerId, i, 0, ContainerInput.QUICK_MOVE, me);
            lastTake = ticks;
            status = "looting chest: " + itemId(s);
            return pause0();
        }
        if (openedChest != null) {
            looted.add(openedChest);
            // a double chest is one inventory: its other half is done too
            for (Direction dir : Direction.Plane.HORIZONTAL) if (ctx.world().getBlockState(openedChest.relative(dir)).is(Blocks.CHEST)) looted.add(openedChest.relative(dir).immutable());
            chestsLooted++;
            openedChest = null;
        }
        ((LocalPlayer) me).closeContainer();
        status = "chest done";
        return pause0();
    }
    private boolean piglinsNear(Player me, List<LivingEntity> near) {
        boolean blocked = false;
        for (LivingEntity e : near) {
            // babies flee and never attack, so they are no reason to hold off
            if (!(e instanceof Piglin pg) || pg.isBaby() || !e.isAlive() || me.distanceTo(e) >= ANGER_RANGE) continue;
            if (!stuck(me, pg)) blocked = true;
        }
        return blocked;
    }

    /** A piglin that cannot reach us (far above/below, or hunting us but not moving) is no reason to wait. */
    private boolean stuck(Player me, Piglin pg) {
        // in a pit (3+ below us) or on a ledge far above: it cannot walk up to us
        if (pg.getY() <= me.getY() - 3 || pg.getY() >= me.getY() + 5) return true;
        var last = piglinSeen.get(pg.getId());
        Vec3 pos = pg.position();
        if (last == null || last.pos.distanceToSqr(pos) > 0.04) {
            piglinSeen.put(pg.getId(), new Seen(pos, ticks));
            return false;
        }
        return pg.isAggressive() && ticks - last.since >= 100 && me.distanceTo(pg) > 3;
    }

    private record Seen(Vec3 pos, long since) {}

    private final java.util.Map<Integer, Seen> piglinSeen = new java.util.HashMap<>();

    /** Best plain food in the inventory (golden apples are kept for emergencies); hotbar slots first. */
    private int foodSlot(Player me) {
        int best = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isEmpty() || !st.has(net.minecraft.core.component.DataComponents.FOOD)) continue;
            if (st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE) || st.is(Items.ROTTEN_FLESH) || st.is(Items.SPIDER_EYE)) continue;
            if (i < 9) return i;
            if (best < 0) best = i;
        }
        return best;
    }

    private int pickaxeSlot(Player me) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (s.is(Items.IRON_PICKAXE) || s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE)) return i;
        }
        return -1;
    }

    private int slotOf(Player me, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    /** Null when there is nothing to do about gold (then the caller reports it). */
    private PathingCommand acquireGold(Player me, List<LivingEntity> near) {
        PathingCommand pause = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (ticks - lastGoldTick > 5) {
            // we were busy with something else (recovering, fighting): that time says nothing about the gold
            goldSince = ticks;
            goldBestTick = ticks;
            calmSince = 0;
            travelAnchor = null;
        }
        lastGoldTick = ticks;
        piglinsNear(me, near); // keeps the stuck-piglin tracking current while we walk over
        // pick up a dropped block first
        for (ItemEntity i : ctx.world().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(24))) {
            if (i.getItem().is(Items.GOLD_BLOCK)) {
                status = "pick up gold block";
                return new PathingCommand(new GoalNear(i.blockPosition(), 0), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }
        int pick = pickaxeSlot(me);
        if (pick < 0) {
            status = "need an iron pickaxe";
            return null;
        }
        if (ticks - lastScan > 60) {
            lastScan = ticks;
            goldBlocks = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, new BlockOptionalMetaLookup(Blocks.GOLD_BLOCK), 32, 10, 64);
        }
        // a gold block in the middle of a brute hoard costs far more than the walk to a quieter one
        List<PiglinBrute> brutes = ctx.world().getEntitiesOfClass(PiglinBrute.class, me.getBoundingBox().inflate(80), PiglinBrute::isAlive);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (BlockPos p : goldBlocks) {
                if (!ctx.world().getBlockState(p).is(Blocks.GOLD_BLOCK)) continue;
                Long bad = badGold.get(p);
                if (bad != null && bad > ticks) continue;
                int guards = 0;
                for (PiglinBrute b : brutes) if (b.blockPosition().distSqr(p) <= 144) guards++;
                if (pass == 0 && guards >= 3) continue;
                double score = Math.sqrt(me.blockPosition().distSqr(p)) + 10.0 * guards - (p.equals(preferGold) ? 1000 : 0);
                if (score < bestScore) { bestScore = score; best = p; }
            }
        }
        if (best == null) {
            status = "no gold block in range";
            return null;
        }
        // Watchdog: a gold block behind a wall (or any other reason we make no progress on it) is skipped for a while.
        String fk = best.asLong() + "/" + me.blockPosition().asLong();
        // waiting for piglins to leave only counts once we are at the block; far away it is just a path that goes nowhere
        if (!fk.equals(goldKey) || piglinsNear(me, near) && me.position().distanceTo(Vec3.atCenterOf(best)) <= 6) {
            goldKey = fk;
            goldSince = ticks;
        } else if (ticks - goldSince > 80 && me.position().distanceTo(Vec3.atCenterOf(best)) <= 12) {
            // (far away a standing bot is just waiting on a long path calculation; the progress watchdog below covers that)
            // its neighbours sit in the same unreachable chamber: skip them too instead of failing on each in turn
            for (BlockPos g : goldBlocks) if (g.distSqr(best) <= 36) badGold.put(g.immutable(), ticks + 1200);
            goldKey = null;
            status = "gold block unreachable, skipping";
            return pause;
        }
        // a hoard of brutes between us and the gold is not worth it: give this cluster up while we can still walk away
        if (me.position().distanceTo(Vec3.atCenterOf(best)) > 10) {
            int closeBrutes = 0;
            for (PiglinBrute b : brutes) if (me.distanceTo(b) <= 24) closeBrutes++;
            if (closeBrutes >= 3) {
                for (BlockPos g : goldBlocks) if (g.distSqr(best) <= 100) badGold.put(g.immutable(), ticks + 300);
                status = "too many brutes (" + closeBrutes + ") near this gold, trying another";
                return pause;
            }
        }
        // Wiggling in place (an ascend that never completes next to vines, say) trips neither watchdog below: anchor check
        if (travelAnchor == null || me.position().distanceTo(travelAnchor) > 3) {
            travelAnchor = me.position();
            travelSince = ticks;
        } else if (ticks - travelSince > 200 && me.position().distanceTo(Vec3.atCenterOf(best)) > 10) {
            for (BlockPos g : goldBlocks) if (g.distSqr(best) <= 100) badGold.put(g.immutable(), ticks + 1200);
            travelAnchor = null;
            try {
                var cur = baritone.getPathingBehavior().getCurrent();
                if (cur != null) {
                    var d = cur.getPath().movements().get(cur.getPosition()).getDest();
                    BlockPos dp = new BlockPos(d.x, d.y, d.z);
                    stuckAvoid.put(dp, ticks + 1500L);
                    stuckAvoid.put(dp.above(), ticks + 1500L);
                }
            } catch (RuntimeException ignoredEx) { }
            status = "stuck in place, trying another gold block";
            return pause;
        }
        // Walking back and forth never trips the stationary watchdog: also give up when we stop getting closer
        double dNow = me.position().distanceTo(Vec3.atCenterOf(best));
        if (!best.equals(goldTarget) || dNow < goldBestDist - 2) {
            goldTarget = best.immutable();
            goldBestDist = dNow;
            goldBestTick = ticks;
        } else if (ticks - goldBestTick > 500 && dNow > 6) {
            for (BlockPos g : goldBlocks) if (g.distSqr(best) <= 36) badGold.put(g.immutable(), ticks + 1200);
            goldTarget = null;
            status = "gold block not getting closer, skipping";
            return pause;
        }
        Rotation rot = aimAt(me, best);
        BlockPos dig = best;
        double gd = me.position().distanceTo(Vec3.atCenterOf(best));
        if (rot == null && gd <= 3.5) {
            // something in the way (a ceiling lip, a wall): one swing at it is quicker than finding another angle
            BlockPos ob = obstruction(me, best);
            if (ob == null) status = "no safe obstruction to dig: " + hitDesc(me, best);
            if (ob != null) {
                dig = ob;
                if (!ob.equals(lastDig)) { lastDig = ob.immutable(); goldSince = ticks; } // each new block dug is progress
                // aim along the line to the gold and dig whatever the crosshair really lands on first
                rot = RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(best), ctx.playerRotations());
                BlockPos sel = ctx.getSelectedBlock().orElse(null);
                if (sel != null && !sel.equals(best) && obstruction(me, sel) != null) { dig = sel; }
            }
        }
        if (gd > 4.2 || rot == null) {
            status = "to gold block " + best.toShortString() + " d=" + (int) gd
                    + " los=" + (rot != null) + " hit=" + hitDesc(me, best) + " bad=" + badGold.size() + " found=" + goldBlocks.size();
            return new PathingCommand(new GoalNear(best, 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // Breaking a gold block angers every piglin around: only do it when no brute is close and the crowd is small
        long crowd = near.stream().filter(e -> e instanceof net.minecraft.world.entity.monster.piglin.Piglin && e.isAlive() && me.distanceTo(e) < 12).count();
        boolean brutesClose = near.stream().anyMatch(e -> (e instanceof PiglinBrute || e instanceof Zoglin) && e.isAlive() && me.distanceTo(e) < 16);
        if ((brutesClose || crowd >= 4) && !luring) {
            if (calmSince == 0) calmSince = ticks;
            if (ticks - calmSince > 600) {
                for (BlockPos g : goldBlocks) if (g.distSqr(best) <= 36) badGold.put(g.immutable(), ticks + 1200);
                calmSince = 0;
                status = "never calm here, trying another gold block";
                return pause;
            }
            status = "waiting for a calm moment to mine (brutes=" + brutesClose + " crowd=" + crowd + ")";
            return pause;
        }
        calmSince = 0;
        if (piglinsNear(me, near)) {
            PathingCommand t = trap(me, near);
            if (t != null) return t;
            // no trap and no ingot to bait with: waiting never ends, and an angry piglin is only a sword fight, so mine anyway
            if (waitSince == 0) waitSince = ticks;
            if (ticks - waitSince < 100 || me.getHealth() < 14) {
                status = "piglins near, waiting before mining";
                return pause;
            }
            status = "piglins near, mining anyway";
        } else {
            waitSince = 0;
        }
        luring = false;
        int hot = pick < 9 ? pick : -1;
        if (hot < 0) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ContainerInput.SWAP, me);
            return pause;
        }
        me.getInventory().setSelectedSlot(hot);
        baritone.getLookBehavior().updateTarget(rot, true);
        if (ctx.isLookingAt(dig) || ctx.playerRotations().isReallyCloseTo(rot)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            if (ctx.world().getBlockState(best).isAir()) goldMined++;
        }
        status = dig.equals(best) ? "mining gold block" : "digging " + ctx.world().getBlockState(dig).getBlock() + dig.toShortString() + " toward gold held=" + me.getMainHandItem().getItem() + " look=" + ctx.isLookingAt(dig);
        return pause;
    }

    /**
     * A rotation that puts the crosshair on this block, or null if no side is in line of sight. RotationUtils.reachable traces
     * from the aim-smoothed rotation, so it is false until we already look that way and we would never start turning.
     */
    /** The block the straight line to p runs into first, if it is safe to dig (not liquid, not a valuable). */
    private String clipDesc(Player me) {
        Vec3 e = me.getEyePosition(1.0F);
        BlockHitResult r = ctx.world().clip(new net.minecraft.world.level.ClipContext(e, e.add(me.getViewVector(1.0F).scale(4.5)), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, me));
        return r.getType() + "@" + r.getBlockPos() + " " + ctx.world().getBlockState(r.getBlockPos()).getBlock();
    }

    private BlockPos lavaPos;
    private long lavaTick;

    private int hotbarItem(Player me, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    /** Speedrunner trick: from the tower, pour lava against its foot where the brute stands, then take it back. */
    private PathingCommand lavaTrap(Player me, List<LivingEntity> heavies) {
        Vec3 eye = me.getEyePosition(1.0F);
        if (lavaPos != null) {
            boolean still = ctx.world().getBlockState(lavaPos).is(Blocks.LAVA);
            if (!still) {
                if (ticks - lavaTick < 10) return pause0();
                // run 128: the click lands a tick after the aim is checked and the lava went a cell off. Nobody took it
                // back, and the way out led through it. Look for where it did go
                BlockPos found = null;
                for (BlockPos p : BlockPos.betweenClosed(lavaPos.offset(-2, -2, -2), lavaPos.offset(2, 2, 2))) {
                    if (ctx.world().getBlockState(p).is(Blocks.LAVA) && ctx.world().getFluidState(p).isSource()
                            && (found == null || p.distSqr(lavaPos) < found.distSqr(lavaPos))) found = p.immutable();
                }
                lavaPos = found;
                if (found == null) return null;
            }
            if (!heavies.isEmpty() && ticks - lavaTick < 120) return null;
            int b = hotbarItem(me, Items.BUCKET);
            if (b < 0) { lavaPos = null; return null; }
            me.getInventory().setSelectedSlot(b);
            Vec3 c = Vec3.atCenterOf(lavaPos).add(0, 0.4, 0);
            if (eye.distanceTo(c) > 4.4) { lavaPos = null; return null; }
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, c, ctx.playerRotations()), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (ticks % 6 == 0 && clipDesc(me).contains("lava")) {
                // use the item directly: the bucket takes the fluid its own ray finds, the crosshair block is beside the point
                ctx.playerController().processRightClick(ctx.player(), ctx.world(), net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            status = "perch: lava back";
            return pause0();
        }
        if (ticks - lavaTick < 100) return null;
        int slot = hotbarItem(me, Items.LAVA_BUCKET);
        if (slot < 0) return null;
        LivingEntity brute = null;
        for (LivingEntity e : heavies) {
            double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= 5.5 && e.getY() < me.getY() - 1.5 && (brute == null || me.distanceTo(e) < me.distanceTo(brute))) brute = e;
        }
        if (brute == null) return null;
        Direction d = Direction.getApproximateNearest(brute.getX() - me.getX(), 0, brute.getZ() - me.getZ());
        // sneak out to the edge of the column so the side faces are in view; the sneak edge keeps us from falling
        double off = (me.getX() - (perchX + 0.5)) * d.getStepX() + (me.getZ() - (perchZ + 0.5)) * d.getStepZ();
        if (off < 0.72) { // sneaking stops us at 0.8, so this leaves the face enough slack to be hit at a steep angle
            baritone.getLookBehavior().updateTarget(new Rotation(d.toYRot(), 40), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            status = "perch: lava trap edge";
            return pause0();
        }
        // the lowest column block within reach: lava poured there runs down the side to where the brute stands
        BlockPos col = null, spot = null;
        Vec3 aim = null;
        for (int y = perchBase; y < me.blockPosition().getY(); y++) {
            BlockPos c = new BlockPos(perchX, y, perchZ), sp = c.relative(d);
            if (ctx.world().getBlockState(c).getCollisionShape(ctx.world(), c).isEmpty() || !ctx.world().getBlockState(sp).canBeReplaced()) continue;
            Vec3 a = Vec3.atCenterOf(c).add(d.getStepX() * 0.5, 0, d.getStepZ() * 0.5);
            if (eye.distanceTo(a) <= 4.4) { col = c; spot = sp; aim = a; break; }
        }
        if (col == null) return null;
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        me.getInventory().setSelectedSlot(slot);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, aim, ctx.playerRotations()), true);
        status = "perch: lava trap";
        if (ctx.minecraft().hitResult instanceof BlockHitResult h && h.getBlockPos().equals(col) && h.getDirection() == d) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            lavaPos = spot.immutable();
            lavaTick = ticks;
        }
        return pause0();
    }

    /**
     * From the tower: hit the brute that stands within our reach. Null with none there, and null while a piglin with a
     * crossbow looks on: hitting a brute sets every piglin that sees it on us, and a crossbow reaches the tower.
     */
    private PathingCommand towerStrike(Player me, List<LivingEntity> heavies) {
        Vec3 eye = me.getEyePosition(1.0F), aim = null;
        LivingEntity t = null;
        double best = 2.9;
        for (LivingEntity e : heavies) {
            if (!(e instanceof PiglinBrute) || !me.hasLineOfSight(e)) continue;
            net.minecraft.world.phys.AABB bb = e.getBoundingBox();
            Vec3 near = new Vec3(net.minecraft.util.Mth.clamp(eye.x, bb.minX, bb.maxX), net.minecraft.util.Mth.clamp(eye.y, bb.minY, bb.maxY), net.minecraft.util.Mth.clamp(eye.z, bb.minZ, bb.maxZ));
            if (eye.distanceTo(near) < best) { best = eye.distanceTo(near); t = e; aim = near; }
        }
        if (t == null) return null;
        if (!ctx.world().getEntitiesOfClass(net.minecraft.world.entity.monster.piglin.Piglin.class, me.getBoundingBox().inflate(16),
                p -> p.isAlive() && p.isHolding(Items.CROSSBOW) && p.hasLineOfSight(me)).isEmpty()) return null;
        int slot = -1, rank = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = me.getInventory().getItem(i);
            int r = s.is(net.minecraft.tags.ItemTags.SWORDS) ? 2 : s.is(net.minecraft.tags.ItemTags.AXES) ? 1 : 0;
            if (r > rank) { rank = r; slot = i; }
        }
        status = "on tower, striking brute at " + String.format("%.1f", best);
        // sneaking at the edge of a one-block column: we do not step off it turning to aim
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        if (slot >= 0 && me.getInventory().getSelectedSlot() != slot) { me.getInventory().setSelectedSlot(slot); return pause0(); }
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, aim.lerp(t.getBoundingBox().getCenter(), 0.2), ctx.playerRotations()), true);
        if (me.getAttackStrengthScale(0.5f) >= 0.95f) {
            ctx.minecraft().gameMode.attack((LocalPlayer) me, t);
            me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        }
        return pause0();
    }

    /** One tick of eating whatever food we carry, or null with none. */
    private PathingCommand eat(Player me) {
        int food = foodSlot(me);
        if (food < 0) return null;
        if (food >= 9) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, food, 6, ContainerInput.SWAP, me);
            return pause0();
        }
        me.getInventory().setSelectedSlot(food);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        ctx.minecraft().options.keyUse.setDown(true);
        status = "eating";
        return pause0();
    }

    private PathingCommand pause0() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private BlockPos obstruction(Player me, BlockPos p) {
        Rotation r = RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(p), ctx.playerRotations());
        HitResult h = RayTraceUtils.rayTraceTowards(me, r, ctx.playerController().getBlockReachDistance(), false);
        if (h == null || h.getType() != HitResult.Type.BLOCK) return null;
        BlockPos hp = ((BlockHitResult) h).getBlockPos();
        BlockState st = ctx.world().getBlockState(hp);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.getDestroySpeed(ctx.world(), hp) < 0 || st.is(Blocks.CHEST) || st.is(Blocks.GILDED_BLACKSTONE)) return null;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!ctx.world().getBlockState(hp.relative(d)).getFluidState().isEmpty()) return null; // never open a wall onto lava
        }
        return hp;
    }

    private String hitDesc(Player me, BlockPos p) {
        Rotation r = RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(p), ctx.playerRotations());
        HitResult h = RayTraceUtils.rayTraceTowards(me, r, ctx.playerController().getBlockReachDistance(), false);
        return h == null ? "null" : h.getType() == HitResult.Type.BLOCK ? ctx.world().getBlockState(((BlockHitResult) h).getBlockPos()).getBlock() + "@" + ((BlockHitResult) h).getBlockPos().toShortString() : h.getType().toString();
    }

    private Rotation aimAt(Player me, BlockPos p) {
        Vec3 eyes = me.getEyePosition(1.0F);
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 c = Vec3.atCenterOf(p);
        Vec3[] spots = {c, c.add(0, 0.5, 0), c.add(0, -0.5, 0), c.add(0.5, 0, 0), c.add(-0.5, 0, 0), c.add(0, 0, 0.5), c.add(0, 0, -0.5)};
        for (Vec3 v : spots) {
            Rotation r = RotationUtils.calcRotationFromVec3d(eyes, v, ctx.playerRotations());
            HitResult h = RayTraceUtils.rayTraceTowards(me, r, reach, false);
            if (h != null && h.getType() == HitResult.Type.BLOCK && ((BlockHitResult) h).getBlockPos().equals(p)) return r;
        }
        return null;
    }

    // timed brute duel: one isolated brute, full-cooldown sprint hits, step out of reach between swings
    private LivingEntity duelBrute;
    private int duelHits;
    private float duelHp;
    private long duelBackUntil, duelStart;

    private void duelEnd(Player me, String why) {
        if (duelBrute != null) logDirect("Bastion: duel " + why + " hits=" + duelHits + " dmgTaken=" + String.format("%.1f", duelHp - me.getHealth()) + " t=" + (ticks - duelStart) / 20 + "s");
        if (duelBrute != null) net.minecraft.client.Minecraft.getInstance().options.keyUse.setDown(false);
        duelBrute = null;
    }

    private boolean lavaDropNear(BlockPos c, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            for (int dy = 0; dy < 16; dy++) {
                BlockState s = ctx.world().getBlockState(c.offset(dx, -dy, dz));
                if (!s.getFluidState().isEmpty() && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
                if (dy > 0 && !s.isAir() && !s.getCollisionShape(ctx.world(), c.offset(dx, -dy, dz)).isEmpty()) break;
            }
        }
        return false;
    }

    private PathingCommand duel(Player me, List<LivingEntity> heavies) {
        List<LivingEntity> brutes = heavies.stream().filter(e -> e instanceof PiglinBrute && e.isAlive()).toList();
        if (duelBrute != null && !duelBrute.isAlive()) { duelEnd(me, "won"); return null; }
        // run 64: turning to eat/climb/exit with a brute in reach got hit 7, 3 and 10; with a shield, keep blocking until it is out of reach
        // shield duel only (runs 58/59: no shield lost 11 hp in 3-4 hits)
        if (!me.getOffhandItem().is(Items.SHIELD)) { duelEnd(me, "aborted (no shield)"); return null; }
        // run 98: "blocking" took 10 twice: a brute's axe disables the shield for 5 s; then it is no defence at all
        if (me.getCooldowns().isOnCooldown(me.getOffhandItem())) { duelEnd(me, "aborted (shield disabled)"); return null; }
        LivingEntity b = brutes.stream().filter(e -> me.distanceTo(e) < 12).min(java.util.Comparator.comparingDouble(me::distanceTo)).orElse(null);
        if (b == null) { duelEnd(me, "lost sight"); return null; }
        LivingEntity fb = b;
        boolean isolated = brutes.stream().noneMatch(e -> e != fb && e.distanceTo(fb) < 10);
        // run 71: outnumbered, it walked its exit path past a brute at 1.4 blocks and took 10 in one hit; one in reach gets blocked anyway
        boolean inReach = me.distanceTo(b) < 5 && !otherLevel(b, me); // run 95: exit path met a brute at 1.3, hit for 10 before blocking
        String why = !isolated && !inReach ? "outnumbered" : me.getHealth() < 14 && me.distanceTo(b) > 4 ? "low hp" : perching ? "perched" : me.isInLava() ? "lava" : !inReach && lavaDropNear(me.blockPosition(), 2) ? "lava edge" : otherLevel(b, me) ? "other level" : null;
        if (why != null) { duelEnd(me, "aborted (" + why + ")"); return null; }
        if (duelBrute != b) {
            duelBrute = b; duelHits = 0; duelHp = me.getHealth(); duelStart = ticks;
            // axe swap once, before engaging (swapping resets the attack cooldown)
            int best = -1, rank = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack s = me.getInventory().getItem(i);
                int r = s.is(net.minecraft.tags.ItemTags.AXES) ? 2 : s.is(net.minecraft.tags.ItemTags.SWORDS) ? 1 : 0;
                if (r > rank) { rank = r; best = i; }
            }
            if (best >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, best, 0, ContainerInput.SWAP, me); best = 0; }
            if (best >= 0) me.getInventory().setSelectedSlot(best);
            logDirect("Bastion: duel start vs brute at " + b.blockPosition().toShortString());
        }
        if (fighting) { baritone.getPveProcess().clearEnemies(); fighting = false; }
        baritone.getPveProcess().hold = false;
        double d = me.distanceTo(b);
        float cd = me.getAttackStrengthScale(0.5f);
        aimer.look(b.getEyePosition(), 0);
        // run 97: at a lava edge it kited (and was knocked into the lava); there we only block, never swing or close in
        boolean edge = lavaDropNear(me.blockPosition(), 2);
        if (!edge && cd >= 0.95f && d <= 3.3 /* center distance; run 92 blocked for 8 s at ~3.1 and never swung */ && me.hasLineOfSight(b)) {
            // lower the shield and swing this tick, then block again
            net.minecraft.client.Minecraft.getInstance().options.keyUse.setDown(false);
            if (me.isUsingItem()) net.minecraft.client.Minecraft.getInstance().gameMode.releaseUsingItem(me);
            me.setSprinting(true);
            net.minecraft.client.Minecraft.getInstance().gameMode.attack((LocalPlayer) me, b);
            me.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            duelHits++;
            status = "duel: hit " + duelHits;
            return pause0();
        }
        if (!edge && d > 6 && cd >= 0.95f) {
            status = "duel: closing in";
            return new PathingCommand(new GoalNear(b.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // run 61: the CLICK_RIGHT override never raised the shield (using=false); hold the real use key and start the use
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.options.keyUse.setDown(true);
        if (!me.isUsingItem()) mc.gameMode.useItem(me, net.minecraft.world.InteractionHand.OFF_HAND);
        status = "duel: blocking"; lastBlockTick = ticks;
        return pause0();
    }
    /**
     * Regular piglins (runs 88, 91, 92, 94 lost melee 3 hits of ~5.4): three or more angry means leave; otherwise our own
     * full-cooldown sword fight, blocking with a shield while the cooldown refills.
     */
    private long skirmishLog, lastBlockTick = -100;
    private long skirmishSeen = -1000;
    private PathingCommand skirmish(Player me, List<LivingEntity> th) {
        LivingEntity t = th.stream().min(java.util.Comparator.comparingDouble(me::distanceTo)).orElse(null);
        if (t == null) return null;
        long close = th.stream().filter(e -> me.distanceTo(e) < 8).count();
        if (ticks - skirmishSeen > 100) { skirmishSeen = ticks; logDirect(String.format("Bastion: skirmish engaged %d piglin(s), nearest %.1f, hp %.0f, target hp %.0f", th.size(), me.distanceTo(t), me.getHealth(), t.getHealth())); }
        if (fighting) { baritone.getPveProcess().clearEnemies(); fighting = false; }
        baritone.getPveProcess().hold = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (close >= 3) {
            mc.options.keyUse.setDown(false);
            if (ticks - skirmishLog > 100) { skirmishLog = ticks; logDirect("Bastion: " + close + " angry piglins close, retreating"); }
            status = "retreat from " + close + " piglins";
            Vec3 away = Vec3.ZERO;
            for (LivingEntity e : th) away = away.add(me.position().subtract(e.position()));
            return new PathingCommand(new baritone.api.pathing.goals.GoalRunAway(16, th.stream().map(LivingEntity::blockPosition).toArray(BlockPos[]::new)), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // sword for these: the axe cooldown is too slow against a fast piglin
        int best = -1, rank = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = me.getInventory().getItem(i);
            int r = s.is(net.minecraft.tags.ItemTags.SWORDS) ? 2 : s.is(net.minecraft.tags.ItemTags.AXES) ? 1 : 0;
            if (r > rank) { rank = r; best = i; }
        }
        if (best >= 0 && me.getInventory().getSelectedSlot() != best) { me.getInventory().setSelectedSlot(best); status = "skirmish: draw"; return pause0(); }
        double d = me.distanceTo(t);
        float cd = me.getAttackStrengthScale(0.5f);
        boolean shield = me.getOffhandItem().is(Items.SHIELD);
        aimer.look(t.getEyePosition(), 0);
        if (cd >= 0.95f && d <= 3.3 && me.hasLineOfSight(t)) {
            mc.options.keyUse.setDown(false);
            if (me.isUsingItem()) mc.gameMode.releaseUsingItem(me);
            mc.gameMode.attack((LocalPlayer) me, t);
            me.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            status = "skirmish: hit";
            return pause0();
        }
        if (d > 3.3 && cd >= 0.95f) {
            mc.options.keyUse.setDown(false);
            status = "skirmish: close in";
            return new PathingCommand(new GoalNear(t.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (shield) {
            mc.options.keyUse.setDown(true);
            if (!me.isUsingItem()) mc.gameMode.useItem(me, net.minecraft.world.InteractionHand.OFF_HAND);
            status = "skirmish: blocking"; lastBlockTick = ticks;
        } else status = "skirmish: cooldown";
        return pause0();
    }

    /** Burning or in lava without fire resistance: drink a fire resistance potion, or eat a golden apple when hurt. */
    private long cureUntil;
    private PathingCommand fireCure(Player me) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (ticks < cureUntil && me.isUsingItem() && !(me.isInLava() && me.getUseItem().is(Items.GOLDEN_APPLE))) { mc.options.keyUse.setDown(true); status = "drinking/eating for fire"; return pause0(); }
        if (cureUntil > 0 && ticks >= cureUntil) { mc.options.keyUse.setDown(false); cureUntil = 0; }
        if (!(me.isOnFire() || me.isInLava()) || me.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) return null;
        int slot = -1;
        for (int i = 0; i < 9 && slot < 0; i++) if ("fire_resistance".equals(itemId(me.getInventory().getItem(i)))) slot = i;
        // run 102: ate an apple standing in lava and burned 12 -> 0 while chewing; apples only once out of the lava
        if (slot < 0 && me.getHealth() < 14 && !me.isInLava()) for (int i = 0; i < 9 && slot < 0; i++) if (me.getInventory().getItem(i).is(Items.GOLDEN_APPLE) || me.getInventory().getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) slot = i;
        if (slot < 0) {
            // pull one from the main inventory into hotbar slot 8
            for (int i = 9; i < 36; i++) {
                ItemStack s = me.getInventory().getItem(i);
                if ("fire_resistance".equals(itemId(s)) || me.getHealth() < 14 && !me.isInLava() && s.is(Items.GOLDEN_APPLE)) { ctx.playerController().windowClick(me.inventoryMenu.containerId, i, 8, ContainerInput.SWAP, me); return pause0(); }
            }
            return null;
        }
        me.getInventory().setSelectedSlot(slot);
        if (me.getInventory().getSelectedSlot() != selAtTickStart) return pause0();
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(me, net.minecraft.world.InteractionHand.MAIN_HAND);
        cureUntil = ticks + 40;
        logDirect("Bastion: " + (me.isInLava() ? "in lava" : "burning") + " at " + (int) me.getHealth() + " hp, using " + itemId(me.getInventory().getItem(slot)));
        status = "drinking/eating for fire";
        return pause0();
    }

    private static float yawTo(Player me, LivingEntity e) {
        double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    /** A dropped item near us that is not our own ingot: barter loot. */
    private final Set<Integer> badLoot = new HashSet<>();

    /** A cell within 2 blocks whose column falls onto lava (an edge over the lava sea or a lava pit). */
    private boolean lavaDropNear(BlockPos at) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos c = at.offset(dx, 0, dz);
            if (!ctx.world().getBlockState(c.below()).getCollisionShape(ctx.world(), c.below()).isEmpty()) continue;
            for (int y = 1; y <= 12; y++) {
                BlockPos d = c.below(y);
                if (lavaAt(d)) return true;
                if (!ctx.world().getBlockState(d).getCollisionShape(ctx.world(), d).isEmpty()) break;
            }
        }
        return false;
    }
    private BlockPos nudgeDest;
    private int selAtTickStart = -1;
    /** A piglin walks to an ingot it sees land a few blocks off: no need to stand on top of it. */
    private static final double CAMP_THROW = 3.0; // run 75: 81 throws spent 5 ingots; a Q-throw carries ~2-3 blocks and we re-picked the rest
    private boolean camping;
    /** Surface cells of dug piglin holes (the hole is the two blocks below) and the one being dug. */
    private final List<BlockPos> holes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private BlockPos digHole;
    private boolean holeDigging;
    private long holeRetry, lastBait;

    private final java.util.Set<java.util.UUID> trapLogged = new java.util.HashSet<>();
    private boolean trapped(Piglin p) {
        for (BlockPos h : holes) if (p.blockPosition().getX() == h.getX() && p.blockPosition().getZ() == h.getZ() && p.getY() < h.getY() - 0.5) { if (trapLogged.add(p.getUUID())) logDirect("Bastion: piglin trapped in hole at " + h.toShortString()); return true; }
        return false;
    }

    private BastionTrap.Site holeSite(Player me, List<Piglin> piglins, BlockPos c, int dx, int dz) {
        BlockPos a = c.below(), b = c.below(2);
        boolean open = ctx.world().getBlockState(c).isAir() && ctx.world().getBlockState(c.above()).isAir();
        BlockState sa = ctx.world().getBlockState(a), sb = ctx.world().getBlockState(b);
        // thin floors: the lower cell may already be open (a gap under the floor), as long as it is walled and floored
        boolean bOk = sb.isAir() || solid(b) && sb.getDestroySpeed(ctx.world(), b) >= 0 && sb.getDestroySpeed(ctx.world(), b) < 20;
        boolean mineable = solid(a) && bOk && sa.getDestroySpeed(ctx.world(), a) >= 0 && sa.getDestroySpeed(ctx.world(), a) < 20
                && !sa.is(Blocks.CHEST) && !sb.is(Blocks.CHEST) && !sa.is(Blocks.GOLD_BLOCK) && !sb.is(Blocks.GOLD_BLOCK) && !(sa.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                // run 133: a hole dug beside two piglins we were trading with, and both were on us before it was done
                && !sa.is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS) && !sb.is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS);
        boolean walled = true;
        for (BlockPos h : new BlockPos[]{a, b}) for (Direction d : Direction.Plane.HORIZONTAL) walled &= solid(h.relative(d));
        boolean lava = false;
        for (BlockPos h : new BlockPos[]{a, b, b.below()}) for (Direction d : Direction.values()) lava |= lavaAt(h.relative(d));
        double pd = 99;
        for (Piglin p : piglins) pd = Math.min(pd, p.position().distanceTo(Vec3.atCenterOf(c)));
        return new BastionTrap.Site(dx, dz, open, mineable, walled, solid(b.below()), lava, onEdge(c), pd);
    }

    /** Dig up to two separate 1x2 holes beside the camp, bait them with an ingot, and let the camp trade with whoever falls in. */
    private PathingCommand holeStep(Player me, List<Piglin> piglins, int ingots) {
        BlockPos feet = me.blockPosition();
        if (digHole == null && holes.size() < 2 && ingots >= 4 && ticks > holeRetry && me.onGround()) {
            List<BastionTrap.Site> sites = new ArrayList<>();
            // ring of 1 and 2 blocks (incl. diagonals): run 60/62 camped 20+ s and never found a site next to the feet
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if ((dx != 0 || dz != 0) && Math.abs(dx) + Math.abs(dz) <= 3) sites.add(holeSite(me, piglins, feet.offset(dx, 0, dz), dx, dz));
            List<int[]> taken = new ArrayList<>();
            for (BlockPos h : holes) taken.add(new int[]{h.getX() - feet.getX(), h.getZ() - feet.getZ()});
            BastionTrap.Site s = BastionTrap.choose(sites, taken);
            if (s == null) {
                holeRetry = ticks + 600;
                int[] r = new int[6];
                for (BastionTrap.Site x : sites) { if (!x.open()) r[0]++; if (!x.mineable()) r[1]++; if (!x.walled()) r[2]++; if (!x.floored()) r[3]++; if (x.lavaNear()) r[4]++; if (x.edge()) r[5]++; }
                logDirect("Bastion: no hole site of " + sites.size() + " (notOpen=" + r[0] + " notMineable=" + r[1] + " notWalled=" + r[2] + " noFloor=" + r[3] + " lava=" + r[4] + " edge=" + r[5] + ")");
            }
            else { digHole = feet.offset(s.dx(), 0, s.dz()); digPickup = false; holeDigging = false; digSince = ticks; logDirect("Bastion: digging piglin hole at " + digHole.toShortString()); }
        }
        if (digHole != null) {
            BlockPos target = solid(digHole.below()) ? digHole.below() : solid(digHole.below(2)) ? digHole.below(2) : null;
            if (target == null && digPickup) {
                logDirect("Bastion: pickup hole ready at " + digHole.toShortString());
                pickHole = digHole; digPickup = false; digHole = null; holeDigging = false;
                return pause0();
            }
            if (target == null) {
                logDirect("Bastion: piglin hole ready at " + digHole.toShortString());
                holes.add(digHole);
                digHole = null;
                holeDigging = false;
                return pause0();
            }
            if (ticks - digSince > 300) { logDirect("Bastion: hole dig stalled, skipping"); digHole = null; holeRetry = ticks + 1200; return null; }
            if (feet.getX() == digHole.getX() && feet.getZ() == digHole.getZ()) { logDirect("Bastion: hole dig aborted (standing on it)"); digHole = null; holeRetry = ticks + 600; return null; }
            int pick = pickaxeSlot(me);
            if (pick < 0) { logDirect("Bastion: hole dig aborted (no pickaxe)"); digHole = null; holeRetry = ticks + 6000; return null; }
            if (pick >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ContainerInput.SWAP, me); return pause0(); }
            me.getInventory().setSelectedSlot(pick);
            // sneak: never step into the hole we are digging
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F), Vec3.atCenterOf(target).add(0, 0.5, 0), ctx.playerRotations()), true);
            if (!target.equals(holeTarget)) { holeTarget = target; ctx.playerController().clickBlock(target, Direction.UP); }
            else ctx.playerController().onPlayerDamageBlock(target, Direction.UP);
            status = "camp: digging piglin hole";
            return pause0();
        }
        // a piglin is trapped: dig a pickup hole diagonal to its hole and trade from inside it (drops land at our feet, the
        // diagonal gap keeps the piglin in)
        Piglin caught = null;
        BlockPos caughtHole = null;
        for (Piglin p : piglins) if (trapped(p)) { caught = p; for (BlockPos h : holes) if (p.blockPosition().getX() == h.getX() && p.blockPosition().getZ() == h.getZ()) caughtHole = h; break; }
        if (caught != null && caughtHole != null) {
            if (pickHole == null && ticks > pickRetry) {
                BastionTrap.Site best = null; BlockPos bestPos = null;
                for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
                    BlockPos q = caughtHole.offset(dx, 0, dz);
                    if (q.getX() == feet.getX() && q.getZ() == feet.getZ()) continue;
                    BastionTrap.Site s = holeSite(me, piglins, q, dx, dz);
                    // we stand in it ourselves, so it needs no walls (run 83 rejected all four on walled=false)
                    if (s.open() && s.mineable() && s.floored() && !s.lavaNear() && !s.edge() && (bestPos == null || me.blockPosition().distSqr(q) < me.blockPosition().distSqr(bestPos))) { best = s; bestPos = q; }
                }
                if (bestPos == null) {
                    pickRetry = ticks + 600;
                    StringBuilder why = new StringBuilder();
                    for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) why.append(" ").append(holeSite(me, piglins, caughtHole.offset(dx, 0, dz), dx, dz));
                    logDirect("Bastion: no pickup hole site next to " + caughtHole.toShortString() + why);
                }
                else { digHole = bestPos; digPickup = true; holeDigging = false; digSince = ticks; logDirect("Bastion: digging pickup hole at " + bestPos.toShortString()); return pause0(); }
            }
            if (pickHole != null) {
                BlockPos stand = pickHole.below(2);
            // a jump to throw over the rim leaves the stand block for a moment; that is still "in the hole"
            boolean inStandColumn = me.blockPosition().getX() == stand.getX() && me.blockPosition().getZ() == stand.getZ() && me.getY() >= stand.getY() && me.getY() < stand.getY() + 1.6;
                if (!me.blockPosition().equals(stand) && !(inStandColumn && throwJump)) {
                    if (me.blockPosition().getX() == pickHole.getX() && me.blockPosition().getZ() == pickHole.getZ() && !me.onGround()) return pause0();
                    status = "camp: into pickup hole";
                    return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
                }
                if (!inPickLogged) { inPickLogged = true; logDirect("Bastion: standing in pickup hole, trading with trapped piglin"); }
                if (admiring.containsKey(caught.getUUID()) || caught.getOffhandItem().is(Items.GOLD_INGOT) || ingots < 1) { status = "camp: pickup hole, waiting"; return pause0(); }
                int slot = hotbarGold(me);
                if (slot < 0) return pause0();
                me.getInventory().setSelectedSlot(slot);
                // aim at the far corner of the trap hole so the ingot lands on the piglin's side, not back at our feet
                Vec3 far = new Vec3(caughtHole.getX() + 0.5 + 0.35 * (caughtHole.getX() - pickHole.getX()), caughtHole.getY() - 2, caughtHole.getZ() + 0.5 + 0.35 * (caughtHole.getZ() - pickHole.getZ()));
                // simulate the ingot's flight (ItemThrow) and pick the pitch that lands it in that corner
                ItemThrow.Floor fl = (fx, fz) -> {
                    int bx = Mth.floor(fx), bz = Mth.floor(fz);
                    for (int by = Mth.floor(me.getEyeY()); by > me.getBlockY() - 5; by--) if (floor(new BlockPos(bx, by, bz))) return by + 1;
                    return me.getBlockY() - 5;
                };
                // our eyes are below the rim in the pit: the ingot hit the shared corner and fell back to us (r138: ingots 152 -> 151
            // over 65 throws). Throw at the top of a jump, about 1.25 above the standing eye height.
            // a roof over the pit stops the jump at +0.12 (r145: 167 s of jumping, no throw): throw from the ground then
            boolean canJump = jumpTries < 3 && ctx.world().getBlockState(stand.above(2)).getCollisionShape(ctx.world(), stand.above(2)).isEmpty();
            double throwEye = me.onGround() && canJump ? me.getEyeY() + 1.25 : me.getEyeY();
            float[] aim = ItemThrow.aim(me.getX(), throwEye, me.getZ(), far.x, far.z, fl);
            aim[1] = throwAdapt.pitch(aim[1]);
                baritone.getLookBehavior().updateTarget(new Rotation(aim[0], aim[1]), true);
                float farYaw = aim[0];
                boolean aimed = Math.abs(Mth.wrapDegrees(me.getYRot() - farYaw)) < 6 && Math.abs(me.getXRot() - aim[1]) < 4;
            if (aimed && me.onGround() && canJump) { if (throwJump) jumpTries++; throwJump = true; baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true); return pause0(); }
            boolean apex = !canJump ? me.onGround() : !me.onGround() && Math.abs(me.getDeltaMovement().y) < 0.12 && me.getY() > stand.getY() + 0.9;
            if (aimed && apex && safeDrop(me)) { throwJump = false; jumpTries = 0; throwTarget = caughtHole;
                    throwPred = ItemThrow.land(me.getX(), me.getEyeY(), me.getZ(), me.getYRot(), me.getXRot(), fl); admiring.put(caught.getUUID(), ticks); throwTick = ticks; throwsDone++; logDirect("Bastion: pickup-hole throw " + throwsDone + " ingots=" + (ingots - 1)); }
                status = "camp: pickup hole, throwing (throws=" + throwsDone + ")";
                return pause0();
            }
        }
        // bait an empty hole: an ingot dropped in brings a piglin down after it
        for (BlockPos h : holes) {
            boolean occupied = piglins.stream().anyMatch(this::trapped);
            if (occupied || ticks - lastBait < 140 || ingots < 2) continue;
            int slot = hotbarGold(me);
            if (slot < 0) return null;
            me.getInventory().setSelectedSlot(slot);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            aimer.look(Vec3.atCenterOf(h.below(2)), 0);
            if (Math.abs(Mth.wrapDegrees(me.getYRot() - (float) Math.toDegrees(Math.atan2(-(h.getX() + 0.5 - me.getX()), h.getZ() + 0.5 - me.getZ())))) < 15 && safeDrop(me)) { lastBait = ticks; status = "camp: baiting hole"; logDirect("Bastion: baited hole at " + h.toShortString()); }
            return pause0();
        }
        return null;
    }
    private BlockPos holeTarget, pickHole;
    private boolean digPickup, inPickLogged;
    private long pickRetry;
    private long digSince;
    private long campIdleSince;


    /** Standing next to an open drop over lava/void (same rule as the path cost). */
    private boolean onEdge(BlockPos at) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            boolean deadly = true;
            for (int d = 1; d <= EdgeCost.SCAN; d++) {
                BlockPos c = at.offset(dx, -d, dz);
                if (lavaAt(c)) { deadly = d > EdgeCost.MIN_DROP; break; }
                if (!ctx.world().getBlockState(c).getCollisionShape(ctx.world(), c).isEmpty()) { deadly = false; break; }
            }
            if (deadly) return true;
        }
        return false;
    }

    private boolean calmNear(Player me, Piglin p, double r) {
        return p.isAlive() && !p.isBaby() && !p.isAggressive() && me.distanceTo(p) <= r && Math.abs(p.getY() - me.getY()) <= 2
                && me.hasLineOfSight(p) && brutesAround(p) == 0;
    }

    /**
     * Trade camp, the way runners barter: with 2+ calm piglins within 6 and 6+ ingots, stand still and throw an ingot each
     * time one is free, until out of ingots or the targets are met. Only short hops (3) for drops; chests wait until after.
     */
    private PathingCommand tradeCamp(Player me, List<Piglin> piglins, Map<String, Integer> have, boolean done) {
        // a throw the piglin never picked up (not holding gold 2 s later) frees it again
        for (Piglin p : piglins) { Long at = admiring.get(p.getUUID()); if (at != null && ticks - at > 40 && !p.getOffhandItem().is(Items.GOLD_INGOT)) admiring.remove(p.getUUID()); }
        int ingots = ingotCount(me);
        long calm = piglins.stream().filter(p -> calmNear(me, p, camping ? 8 : 6)).count();
        if (!camping && !done && ingots >= 6 && calm >= 2 && !onEdge(me.blockPosition())) { camping = true; campIdleSince = ticks; logDirect("Bastion: trade camp, " + calm + " piglins, " + ingots + " ingots"); }
        if (!camping) return null;
        if (done || ingots < 1 || onEdge(me.blockPosition())) { camping = false; return null; }
        admiring.values().removeIf(t -> ticks - t > ADMIRE_TICKS);
        ItemEntity drop = null;
        for (ItemEntity i : ctx.world().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(3))) {
            if (BastionGoals.barterUseful(itemId(i.getItem())) && !i.getItem().is(Items.GOLD_INGOT) && Math.abs(i.getY() - me.getY()) < 1.5 && (drop == null || me.distanceToSqr(i) < me.distanceToSqr(drop))) drop = i;
        }
        if (drop != null && me.distanceTo(drop) > 0.8) {
            status = "camp: pick up " + itemId(drop.getItem());
            return new PathingCommand(new GoalBlock(drop.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        PathingCommand hole = holeStep(me, piglins, ingots);
        if (hole != null) return hole;
        Piglin t = null;
        for (Piglin p : piglins) {
            if (me.distanceTo(p) < 1.2 /* run 83: throws at 0.3-0.6 landed at our feet and came back */ || !(calmNear(me, p, CAMP_THROW) || trapped(p) && me.distanceTo(p) <= CAMP_THROW) || admiring.containsKey(p.getUUID()) || p.getOffhandItem().is(Items.GOLD_INGOT)) continue;
            // a trapped piglin first: it cannot wander off mid-trade
            if (t == null || trapped(p) && !trapped(t) || trapped(p) == trapped(t) && me.distanceTo(p) < me.distanceTo(t)) t = p;
        }
        if (t == null) {
            if (calm > 0 || !admiring.isEmpty()) campIdleSince = ticks;
            if (holes.isEmpty() && admiring.size() < 2) {
                // calm piglins near but out of throw range (run 48: 71 waits to 4 throws): shuffle up to them
                Piglin c = null;
                for (Piglin p : piglins) if (calmNear(me, p, 8) && !admiring.containsKey(p.getUUID()) && me.distanceTo(p) > CAMP_THROW && !lavaNearPos(p.blockPosition()) && !lavaDropNear(p.blockPosition()) && (c == null || me.distanceTo(p) < me.distanceTo(c))) c = p;
                if (c != null) { status = "camp: stepping closer"; return new PathingCommand(new GoalNear(c.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH); }
            }
            if (holes.isEmpty() && ticks - campIdleSince > 40) {
                // the group wandered off: follow it a short way instead of ending the camp
                Piglin f = null;
                for (Piglin p : piglins) if (p.isAlive() && !p.isBaby() && !p.isAggressive() && Math.abs(p.getY() - me.getY()) <= 2 && me.distanceTo(p) < 16 && brutesAround(p) == 0
                        && !lavaNearPos(p.blockPosition()) && !lavaDropNear(p.blockPosition()) && (f == null || me.distanceTo(p) < me.distanceTo(f))) f = p;
                if (f != null && ticks - campIdleSince < 400) {
                    status = "camp: following piglins (" + String.format("%.1f", me.distanceTo(f)) + ")";
                    return new PathingCommand(new GoalNear(f.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
                }
            }
            if (ticks - campIdleSince > (holes.isEmpty() ? 400 : 600)) { camping = false; return null; }
            status = "camp: waiting (" + admiring.size() + " admiring, " + calm + " near)";
            return pause0();
        }
        campIdleSince = ticks;
        int slot = hotbarGold(me);
        if (slot < 0) { camping = false; return null; }
        me.getInventory().setSelectedSlot(slot);
        aimer.look(t.position().add(0, 0.4, 0), 0);
        if (Math.abs(Mth.wrapDegrees(me.getYRot() - yawTo(me, t))) < 12 && safeDrop(me)) {
            admiring.put(t.getUUID(), ticks);
            throwTick = ticks;
            throwsDone++;
            logDirect("Bastion: camp throw " + throwsDone + " at " + String.format("%.1f", me.distanceTo(t)) + " ingots(before)=" + me.getInventory().countItem(Items.GOLD_INGOT));
        }
        status = "camp: throwing (" + admiring.size() + " admiring, throws=" + throwsDone + ")";
        return pause0();
    }
    private long burnSince = -1, lavaSince = -1;

    private boolean lavaNearPos(BlockPos at) {
        for (BlockPos c : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 1, 2))) if (lavaAt(c)) return true;
        return false;
    }
    private final Map<java.util.UUID, Long> unreachable = new HashMap<>();
    private java.util.UUID closeInId;
    private long closeInSince;
    private int usefulSeen = -1;

    /**
     * Drop one ingot. A slot picked this tick has not reached the server yet (the drop packet does not flush it), so the
     * server would drop whatever was held before: run 32 threw its iron pickaxe to a piglin this way. Wait a tick.
     */
    private boolean safeDrop(Player me) {
        if (me.getInventory().getSelectedSlot() != selAtTickStart || !me.getMainHandItem().is(Items.GOLD_INGOT)) return false;
        ctx.minecraft().gameMode.dropItem((LocalPlayer) me, false);
        return true;
    }
    private long lastWatched = -1000;
    private long nudgeUntil;
    private int lootTarget = -1;
    private long startTick;

    private ItemEntity lootNear(Player me) {
        if (ticks - throwTick > ADMIRE_TICKS + 600) return null;
        ItemEntity best = null;
        for (ItemEntity i : ctx.world().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(admiring.isEmpty() ? 10 : 5))) {
            if (!BastionGoals.barterUseful(itemId(i.getItem())) || badLoot.contains(i.getId())) continue;
            if (best == null || me.distanceToSqr(i) < me.distanceToSqr(best)) best = i;
        }
        return best;
    }

    private static boolean isGoldArmor(ItemStack s) {
        return s.is(Items.GOLDEN_HELMET) || s.is(Items.GOLDEN_CHESTPLATE) || s.is(Items.GOLDEN_LEGGINGS) || s.is(Items.GOLDEN_BOOTS);
    }

    private boolean wearingGold(Player me) {
        return isGoldArmor(me.getItemBySlot(EquipmentSlot.HEAD)) || isGoldArmor(me.getItemBySlot(EquipmentSlot.CHEST))
                || isGoldArmor(me.getItemBySlot(EquipmentSlot.LEGS)) || isGoldArmor(me.getItemBySlot(EquipmentSlot.FEET));
    }

    /** Shift-click a gold piece so it equips itself. True when a click was made. */
    private boolean wearGold(Player me) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (!isGoldArmor(st)) continue;
            // shift-click only equips into an empty armour slot; otherwise it never lands and we loop on "equip gold"
            var eq = st.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if (eq == null || !me.getItemBySlot(eq.slot()).isEmpty()) continue;
            ctx.playerController().windowClick(me.inventoryMenu.containerId, i < 9 ? i + 36 : i, 0, ContainerInput.QUICK_MOVE, me);
            return true;
        }
        return false;
    }

    private int goldSlot(Player me) {
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).is(Items.GOLD_INGOT)) return i;
        return -1;
    }

    /** Hotbar slot holding gold; moves a stack there from the inventory first (returns -1 for this tick). */
    private int hotbarGold(Player me) {
        int s = goldSlot(me);
        if (s < 0) return -1;
        if (s < 9) return s;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, s, 8, ContainerInput.SWAP, me);
        return -1;
    }

    @Override
    public void onLostControl() {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (stuckAvoider != null) {
            AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().remove(stuckAvoider);
            stuckAvoider = null;
        }
        stuckAvoid.clear();
    }

    @Override
    public String displayName0() {
        return "Bastion " + query + " (" + status + ")";
    }

    @Override
    public double priority() {
        return 3;
    }
}
