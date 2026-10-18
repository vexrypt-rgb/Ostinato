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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.utils.ExperimentalMovement;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.BoatUtil;
import baritone.utils.MountUtil;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.SearchCache;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    // Ostinato: was a static ItemStack field on this class; held lazily so a headless context (no bound item components)
    // can load this class. Same stack, built on first use by the normal constructor.
    private static final class BucketHolder {
        static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    }

    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasBoat;
    /** Free boats already out in the world (block longs); boarding one skips placing our own. */
    public final java.util.Set<Long> freeBoats;
    /** Feet-level cells of things we can ride, for {@link #mountAt}. */
    public final java.util.Set<Long> freeMounts;
    /** Fall through offset holes by steering in the air, see MovementDescend.steerThrough. */
    public final boolean allowSteeredFalls;
    public final int maxFallHeightBoat;
    public final boolean aquaAffinity;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    /** Ostinato: jump and momentum-hop costing searches run in the native library ({@code nativeAcceleration}). */
    public final boolean nativeSearch;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean allowParkourFourGap;
    public final boolean allowNeos;
    public final boolean allowClimbJumps;
    public final boolean allowTasJumps;
    public final boolean allowMomentumJumps;
    /** A ladder or vine on the hotbar and allowLadderClutch on, see MovementDescend.dynamicFallCost. */
    public final boolean hasClutchItem;
    /** The clutch item is a ladder (vines win if both are there) and pickupLadders is on, so a clutch also costs the pickup. */
    public final boolean clutchPicksUp;
    /** The clutch item is a ladder (a vine can go on any wall, a ladder needs us to be about 0.6 off it). */
    public final boolean clutchIsLadder;
    public final float blockReach;
    public final boolean experimental;
    public final double experimentalMinHealth;
    public final double fallDamageCost;
    /** 1 unless experimentalMovement wants jumps to look a bit cheaper. */
    public final double jumpBias;
    /** Health plus absorption when the context was made, for falls that hurt. */
    public final double health;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    /** world.dimensionType() goes through a Holder; read once (from Soprano's CalculationContext). */
    public final int minY;
    public final int maxY;

    // Per-search memo for MovementHelper.getMiningDurationTicks, keyed by position: the answer cannot change mid
    // search and the same block is costed by many neighbouring movements. Two tables because includeFalling is part
    // of the question. Present only while a search has claimed it (claimSearchCaches). Ported from Soprano
    // (https://github.com/AverWasTaken/soprano, LGPL-3.0, by AverWasTaken),
    // src/main/java/baritone/pathing/movement/CalculationContext.java.
    private SearchCache.MiningBuffers miningBuffers;
    long[] miningKeys;
    long[] miningKeysFalling;
    double[] miningVals;
    double[] miningValsFalling;
    int miningCacheBits;
    Thread miningOwner;

    public final PrecomputedData precomputedData;
    /** The walk hooks of {@link AltoClefSettings} as they stood when planning began; the lists are null while no hook is registered, which is nearly always. */
    public final boolean swimThroughLava;
    private final List<java.util.function.Predicate<BlockPos>> forceWalkOn;
    private final List<java.util.function.Predicate<BlockPos>> avoidWalkThrough;

    // Ostinato: vertical world bounds cached once, so the pathfinder and movement costs no longer read world.dimensionType()
    // / world.getMinY() / world.getMaxY() per call, and so a headless context (no Level) works. Same values as before.
    public final int worldMinY;
    /** Ostinato: per-context scratch caches of MovementJump / MovementSwim (were ThreadLocals). Only touched by the planning thread. */
    public volatile Object jumpCache;
    public Object swimCache;
    /** Ostinato: offer simulated jumps unchecked (MutableMoveResult.check); set by the A* that runs the checks. */
    public boolean lazyJumps;
    /** dimensionType().height(), the number of block rows. */
    public final int worldHeight;
    /** Level.getMaxY(): the top block row, worldMinY + worldHeight - 1. */
    public final int worldMaxY;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    /**
     * Ostinato: a headless context over a synthetic or snapshot {@link BlockStateInterface}, with no player, inventory or
     * client world, for the pathfinding benchmark and cost-equality tests. Player-derived inputs take the values of a
     * fresh full-health player with an empty hotbar (bare hands via {@code toolSet}); everything else is read from
     * settings exactly as the normal constructor does. New, replaces nothing.
     */
    public CalculationContext(BlockStateInterface bsi, ToolSet toolSet, boolean hasThrowaway, int minY, int height) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = true;
        this.baritone = null;
        this.world = null;
        this.worldData = null;
        this.bsi = bsi;
        this.toolSet = toolSet;
        this.aquaAffinity = false;
        this.hasThrowaway = hasThrowaway;
        this.hasWaterBucket = false;
        this.hasBoat = false;
        this.freeBoats = java.util.Collections.emptySet();
        this.freeMounts = java.util.Collections.emptySet();
        this.allowSteeredFalls = Baritone.settings().allowSteeredFalls.value;
        this.maxFallHeightBoat = Baritone.settings().maxFallHeightBoat.value;
        this.canSprint = Baritone.settings().allowSprint.value;
        this.placeBlockCost = ExperimentalMovement.blockPlacementPenalty();
        this.allowBreak = Baritone.settings().allowBreak.value;
        this.swimThroughLava = false;
        this.forceWalkOn = null;
        this.avoidWalkThrough = null;
        this.allowBreakAnyway = new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = ExperimentalMovement.allowParkour();
        this.allowParkourPlace = Baritone.settings().allowParkourPlace.value;
        this.nativeSearch = Baritone.settings().nativeAcceleration.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = ExperimentalMovement.allowParkourAscend();
        this.allowParkourFourGap = ExperimentalMovement.kinematicTravel();
        this.allowNeos = ExperimentalMovement.allowNeos();
        this.allowClimbJumps = ExperimentalMovement.allowClimbJumps();
        this.allowTasJumps = ExperimentalMovement.allowTasJumps();
        this.allowMomentumJumps = ExperimentalMovement.allowMomentumJumps();
        this.hasClutchItem = false;
        this.clutchIsLadder = false;
        this.clutchPicksUp = false;
        this.blockReach = Baritone.settings().blockReachDistance.value;
        this.experimental = ExperimentalMovement.on();
        this.experimentalMinHealth = Baritone.settings().experimentalMinHealth.value;
        this.fallDamageCost = Baritone.settings().fallDamageCost.value;
        this.jumpBias = ExperimentalMovement.jumpBias();
        this.health = 20;
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false;
        this.frostWalker = 0;
        this.allowDiagonalDescend = ExperimentalMovement.allowDiagonalDescend();
        this.allowDiagonalAscend = ExperimentalMovement.allowDiagonalAscend();
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3;
        this.maxFallHeightNoWater = Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - 1.0f) + ActionCosts.WALK_ONE_BLOCK_COST * 1.0f;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        this.worldBorder = bsi.worldBorder;
        this.minY = minY;
        this.maxY = height;
        this.worldMinY = minY;
        this.worldHeight = height;
        this.worldMaxY = minY + height - 1;
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        LocalPlayer player = baritone.getPlayerContext().player();
        this.world = baritone.getPlayerContext().world();
        this.worldData = (WorldData) baritone.getPlayerContext().worldData();
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.toolSet = new ToolSet(player);
        this.aquaAffinity = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED) > 0.5;
        this.hasThrowaway = !AltoClefSettings.getInstance().isInteractionPaused() && Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = Baritone.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(BucketHolder.STACK_BUCKET_WATER)) && world.dimension() != Level.NETHER;
        this.hasBoat = Baritone.settings().allowBoats.value && Baritone.settings().allowBoatFall.value && !AltoClefSettings.getInstance().isInteractionPaused()
                && BoatUtil.hasBoat(player.getInventory().getNonEquipmentItems());
        this.freeBoats = Baritone.settings().allowBoats.value && Baritone.settings().allowBoatFall.value && !AltoClefSettings.getInstance().isInteractionPaused()
                ? BoatUtil.freeBoats(world, player, 64) : java.util.Collections.emptySet();
        this.allowSteeredFalls = Baritone.settings().allowSteeredFalls.value;
        this.freeMounts = Baritone.settings().allowMountClutch.value && !AltoClefSettings.getInstance().isInteractionPaused()
                ? MountUtil.freeMounts(world, player, 128) : java.util.Collections.emptySet();
        this.maxFallHeightBoat = Baritone.settings().maxFallHeightBoat.value;
        this.canSprint = Baritone.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = ExperimentalMovement.blockPlacementPenalty();
        this.allowBreak = !AltoClefSettings.getInstance().isInteractionPaused() && Baritone.settings().allowBreak.value;
        this.swimThroughLava = AltoClefSettings.getInstance().canSwimThroughLava();
        this.forceWalkOn = AltoClefSettings.getInstance().forceWalkOnSnapshot();
        this.avoidWalkThrough = AltoClefSettings.getInstance().forceAvoidWalkThroughSnapshot();
        this.allowBreakAnyway = new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        // every jump over a gap is planned for a plain fall: under Levitation or Slow Falling none lands where planned
        this.allowParkour = ExperimentalMovement.allowParkour()
                && ClientWorld.plainGravity(baritone.getPlayerContext());
        this.allowParkourPlace = Baritone.settings().allowParkourPlace.value;
        this.nativeSearch = Baritone.settings().nativeAcceleration.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = ExperimentalMovement.allowParkourAscend();
        // the longer jumps are rolled out at the plain walk, and under Slowness they fall short
        boolean fullPace = ClientWorld.fullPace(baritone.getPlayerContext());
        // only the kinematic controller can build the hop speed a 4 block gap needs
        this.allowParkourFourGap = ExperimentalMovement.kinematicTravel() && fullPace;
        this.allowNeos = ExperimentalMovement.allowNeos() && fullPace;
        this.allowClimbJumps = ExperimentalMovement.allowClimbJumps() && fullPace;
        this.allowTasJumps = ExperimentalMovement.allowTasJumps() && fullPace;
        this.allowMomentumJumps = ExperimentalMovement.allowMomentumJumps() && fullPace;
        net.minecraft.world.item.Item clutchItem = Baritone.settings().allowLadderClutch.value ? ((Baritone) baritone).getInventoryBehavior().pickClutchItem(false) : null;
        this.hasClutchItem = clutchItem != null;
        this.clutchIsLadder = clutchItem == net.minecraft.world.item.Items.LADDER;
        this.clutchPicksUp = clutchItem == net.minecraft.world.item.Items.LADDER && Baritone.settings().pickupLadders.value;
        this.blockReach = Baritone.settings().blockReachDistance.value;
        this.experimental = ExperimentalMovement.on();
        this.experimentalMinHealth = Baritone.settings().experimentalMinHealth.value;
        this.fallDamageCost = Baritone.settings().fallDamageCost.value;
        this.jumpBias = ExperimentalMovement.jumpBias();
        this.health = player.getHealth() + player.getAbsorptionAmount();
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        this.frostWalker = frostWalkerLevel;
        this.allowDiagonalDescend = ExperimentalMovement.allowDiagonalDescend();
        this.allowDiagonalAscend = ExperimentalMovement.allowDiagonalAscend();
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        double swim = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        // Dolphin's Grace is about 9 blocks/s, faster than a boat. Depth Strider already lowered swim.
        if (player.hasEffect(net.minecraft.world.effect.MobEffects.DOLPHINS_GRACE)) {
            swim = Math.min(swim, ActionCosts.DOLPHIN_ONE_BLOCK_COST);
        }
        this.waterWalkSpeed = swim;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
        this.minY = world.dimensionType().minY();
        this.maxY = world.dimensionType().height();
        this.worldMinY = this.minY; // Ostinato
        this.worldHeight = this.maxY; // Ostinato
        this.worldMaxY = world.getMaxY(); // Ostinato
    }

    /** Called by the search thread before A* starts; see the mining memo fields. */
    public synchronized void claimSearchCaches() {
        if (miningOwner != null) {
            return; // someone else is searching with this context, they keep it
        }
        miningBuffers = SearchCache.acquireMining();
        miningKeys = miningBuffers.keys;
        miningKeysFalling = miningBuffers.fallingKeys;
        miningVals = miningBuffers.values;
        miningValsFalling = miningBuffers.fallingValues;
        miningCacheBits = miningBuffers.bits;
        miningOwner = Thread.currentThread();
    }

    public synchronized void releaseSearchCaches() {
        if (miningOwner != Thread.currentThread()) {
            return;
        }
        miningOwner = null;
        miningKeys = null;
        miningKeysFalling = null;
        miningVals = null;
        miningValsFalling = null;
        SearchCache.release(miningBuffers);
        miningBuffers = null;
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(x, y, z) || current.getBlock() instanceof EndPortalFrameBlock) {
            return COST_INF;
        }
        return 1;
    }

    /** Whether a hook asks us to keep out of this cell. The executor asks the same of {@link AltoClefSettings}, so the two agree. */
    public boolean avoidsWalkThrough(int x, int y, int z) {
        return avoidWalkThrough != null && anyMatch(avoidWalkThrough, x, y, z);
    }

    /** Whether a hook vouches for this block as footing. */
    public boolean forcesWalkOn(int x, int y, int z) {
        return forceWalkOn != null && anyMatch(forceWalkOn, x, y, z);
    }

    private static boolean anyMatch(List<java.util.function.Predicate<BlockPos>> preds, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        for (int i = 0; i < preds.size(); i++) {
            if (preds.get(i).test(pos)) return true;
        }
        return false;
    }

    /** A free boat at feet level on (x,y,z) or right beside it, ready to board at a cliff top. */
    public boolean freeBoatAt(int x, int y, int z) {
        if (freeBoats.isEmpty()) return false;
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (freeBoats.contains(BlockPos.asLong(x + d[0], y, z + d[1]))) return true;
        }
        return false;
    }

    /** Something to ride standing on this landing cell or one of the eight around it (MountUtil.mountAt looks there too). */
    public boolean mountAt(int x, int y, int z) {
        if (freeMounts.isEmpty()) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (freeMounts.contains(BlockPos.asLong(x + dx, y, z + dz))) return true;
            }
        }
        return false;
    }

    /** Get on, then off again. Mobs wander, so it is priced above a bucket we carry. */
    public double mountClutchCost() {
        return 40;
    }

    /** Board a boat that's already there and drive off: no placing, and we still pick it up after. */
    public double boardBoatFallCost() {
        return 60;
    }

    /** Place, board, drive off, then break and pick the boat back up. */
    public double boatFallCost() {
        return 100 + placeBlockCost;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        // TODO more protection logic here; see #220
        return false;
    }
}
