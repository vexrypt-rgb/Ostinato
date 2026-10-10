package baritone.process;

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
import net.minecraft.world.inventory.ClickType;
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
import baritone.api.pathing.goals.GoalBlock;
import baritone.bastion.BastionPlan;
import baritone.bastion.BastionSettings;
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
        query = variant == null || variant.isEmpty() ? "bastion_remnant" : variant;
        active = true;
        // walk around netherrack rather than dig through it: digging is slow and a speedrun has no time for it
        if (savedBreakPenalty == null) savedBreakPenalty = BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value;
        BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = 40D;
        if (savedFall == null) {
            savedFall = BaritoneAPI.getSettings().maxFallHeightNoWater.value;
            savedBucketFall = BaritoneAPI.getSettings().allowWaterBucketFall.value;
        }
        // water evaporates in the nether: a planned bucket fall there is a fall with no bucket
        BaritoneAPI.getSettings().allowWaterBucketFall.value = false;
        BaritoneAPI.getSettings().maxFallHeightNoWater.value = BastionSettings.maxSafeDrop;
        looted.clear();
        badChest.clear();
        plan = List.of();
        planFor = null;
        entry = null;
        exiting = false;
        goldSourceGone = false;
        chestsExhausted = false;
        chestsLooted = 0;
        stuckCount.clear();
        badLoot.clear();
        distractTries.clear();
        startTick = 0;
        doorPos = null;
        descentSteps = 0;
        digging = false;
        preferGold = null;
        dropPlan = null;
        plannedFallUntil = 0;
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
            stuckAvoider = pos -> { Long until = stuckAvoid.get(pos); return until != null && until > ticks; };
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

    public void stop() {
        active = false;
        if (savedBreakPenalty != null) {
            BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = savedBreakPenalty;
            savedBreakPenalty = null;
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
    private long travelSince;
    private String stallKey;
    private long stallSince;

    private static boolean otherLevel(LivingEntity e, Player me) {
        return Math.abs(e.getY() - me.getY()) > 3.5 && !(e instanceof Mob m && m.isAggressive());
    }

    private boolean threat(LivingEntity e, Player me, boolean early) {
        if (!e.isAlive()) return false;
        // a mob on another level (the bastion's lower floors, a pit) cannot be fought and would pin us in place trying to path to it
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
    private long perchCooldown, waitSince;
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
            ctx.playerController().windowClick(me.inventoryMenu.containerId, inInv, 7, ClickType.SWAP, me);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
        if (me.onGround()) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        if (free) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /** The tower needs air over our head: under a ceiling the jump is blocked and the placement pointless. */
    private boolean headroom(Player me, int base) {
        for (int i = 2; i <= 5; i++) {
            if (ctx.world().getBlockState(new BlockPos(me.blockPosition().getX(), base + i, me.blockPosition().getZ())).blocksMotion()) return false;
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
            ((LocalPlayer) me).drop(false);
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
                ((LocalPlayer) me).drop(false);
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
                me.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
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

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (stuckAvoider == null) {
            // onLostControl drops the predicate; put it back or blacklisted moves would be planned again
            stuckAvoider = pos -> { Long until = stuckAvoid.get(pos); return until != null && until > ticks; };
            AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(stuckAvoider);
        }
        if (ticks < nudgeUntil && nudgeDest != null && ctx.player() != null && !ctx.player().isInLava()) {
            // walk straight at the step the planner keeps failing (run 16: 5 minutes at index 0 of a 2-block MovementFall)
            Player p0 = ctx.player();
            double nx = nudgeDest.getX() + 0.5 - p0.getX(), nz = nudgeDest.getZ() + 0.5 - p0.getZ();
            baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-nx, nz)), 20), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            ticks++; // tick0 owns the clock and does not run while nudging (run 22: nudged for 10 minutes)
            status = "nudging toward " + nudgeDest.toShortString();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        PathingCommand cmd = tick0(calcFailed, isSafeToCancel);
        Player me = ctx.player();
        if (cmd == null || cmd.commandType != PathingCommandType.SET_GOAL_AND_PATH || me == null) {
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
        if (calcFailed || ticks - stuckSince > 100) BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = 2D;
        if (ticks - stuckSince < 200) return cmd;
        // blacklist the cells the current movement keeps trying to reach (and where we stand), then plan again around them
        try {
            var cur = baritone.getPathingBehavior().getCurrent();
            if (cur != null) {
                var mv = cur.getPath().movements().get(Math.min(cur.getPosition(), cur.getPath().movements().size() - 1));
                if (mv instanceof baritone.pathing.movement.movements.MovementFall || mv instanceof baritone.pathing.movement.movements.MovementDescend) {
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
            }
        } catch (RuntimeException ignoredEx) { }
        // a drop we cannot reach (run 14: 2 minutes on one crying obsidian): forget that item at once
        if (status != null && status.startsWith("loot") && lootTarget >= 0) badLoot.add(lootTarget);
        String gk = String.valueOf(cmd.goal);
        int times = stuckCount.merge(gk, 1, Integer::sum);
        if (times >= 2) {
            // the same target twice: it is the target, not the step. Give it up for a while and let the plan pick the next one
            if (chestTarget != null) { badChest.put(chestTarget, ticks + 3000); chestTarget = null; }
            if (goldTarget != null) { badGold.put(goldTarget, ticks + 3000); goldTarget = null; }
            if (dropPlan != null) { dropPlan = null; dropScan = ticks + 400; }
            stuckCount.remove(gk);
            status += "; skipping target " + gk;
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
                        && !(ignored.getOrDefault(e.getUUID(), 0L) > ticks && !(e instanceof Mob m && m.isAggressive() && me.distanceTo(e) < 8)));
        boolean heavyComing = heavies.stream().anyMatch(e -> me.distanceTo(e) < 8 || me.distanceTo(e) < 12 && e instanceof Mob m && m.isAggressive());
        PveProcess pveP = baritone.getPveProcess();
        // a brute hits for ~6-13 through gold armour: low on health with one close, a speedrunner leaves with what they have
        // (in-game run 13 fought, perched and retreated at 12 hp and died in the tower)
        if (bastion != null && !exiting && me.getHealth() < BastionSettings.lowHealth && heavies.stream().anyMatch(e -> me.distanceTo(e) < 12)) {
            logDirect("Bastion: brute close at " + (int) me.getHealth() + " hp, leaving");
            pveP.hold = false;
            perching = false;
            if (fighting) pveP.clearEnemies();
            fighting = false;
            return exit(me, counts(me));
        }
        if (exiting && bastion != null) return exit(me, counts(me));
        // kite brutes: an aggressive one within 8, or any within 8 when below 16 hp, means walk away from it now
        LivingEntity brute = null;
        for (LivingEntity e : heavies) if (e instanceof PiglinBrute && me.distanceTo(e) < 9 && (e.hasLineOfSight(me) || me.getHealth() < 16) && (brute == null || me.distanceTo(e) < me.distanceTo(brute))) brute = e;
        if (brute != null) {
            pveP.hold = false;
            perching = false;
            if (fighting) { pveP.clearEnemies(); fighting = false; }
            Vec3 away = me.position().subtract(brute.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
            evadeGoal = BlockPos.containing(me.position().add(away.normalize().scale(14)));
            evadeUntil = ticks + 60;
            status = "kiting brute at " + String.format("%.1f", me.distanceTo(brute));
            return new PathingCommand(new GoalRunAway(14, brute.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
        }        if (bastion != null && startTick > 0 && ticks - startTick > BastionSettings.timeBudget * 20L) {
            // a runner time-boxes the bastion: past the budget, leave with what we have
            logDirect("Bastion: time budget (" + BastionSettings.timeBudget + " s) used, leaving");
            return exit(me, counts(me));
        }
        if (bastion != null && startTick == 0) startTick = Math.max(1, ticks);
        if (!perching && !me.isInLava() && !me.isOnFire() && ticks >= perchCooldown && heavyComing && pillarBlocks(me) >= 4 && me.onGround() && headroom(me, me.blockPosition().getY())) {
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
                    if (gap >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, gap, 6, ClickType.SWAP, me); return pause0(); }
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
            if (away.horizontalDistanceSqr() > 0.01) {
                baritone.getPveProcess().hold = false;
                float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
                baritone.getLookBehavior().updateTarget(new Rotation(yaw, 10), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                status = "step away from lava";
                return pause0();
            }
        }
        if (me.isOnFire() && !me.isInLava() && !perching && lavaNear(me)) {
            Vec3 away = Vec3.ZERO;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                BlockPos lp = me.blockPosition().offset(dx, dy, dz);
                if (lavaAt(lp)) away = away.add(me.position().subtract(Vec3.atCenterOf(lp)));
            }
            if (away.horizontalDistanceSqr() > 0.01) {
                baritone.getLookBehavior().updateTarget(new Rotation((float) Math.toDegrees(Math.atan2(-away.x, away.z)), 10), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                status = "burning, stepping clear of the lava";
                return pause0();
            }
        }
        if (me.isOnFire() && !me.isInLava() && !perching) {
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
            int food = foodSlot(me);
            if (food >= 0) {
                if (food >= 9) {
                    ctx.playerController().windowClick(me.inventoryMenu.containerId, food, 6, ClickType.SWAP, me);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                me.getInventory().setSelectedSlot(food);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                ctx.minecraft().options.keyUse.setDown(true);
                status = "eating";
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        Map<String, Integer> have = counts(me);
        boolean done = BastionGoals.met(have, BastionSettings.TARGETS);
        if (bastion != null && (exiting || done && BastionSettings.exitWhenDone)) return exit(me, have);

        // 2a. Chests in plan order for this layout.
        if (bastion != null) {
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
            if (me.distanceTo(loot) < 1.2 && loot.getId() != lastLootId) { lastLootId = loot.getId(); lootPicked++; }
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
        for (Piglin p : piglins) {
            if (p.getOffhandItem().is(Items.GOLD_INGOT) || p.isAggressive() || !p.isAlive()) continue;
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
            // run 16 (bridge) stood here for minutes: walk the bastion to find calm piglins instead of waiting
            status += ", searching";
            int k = (int) (ticks / 200 % 4);
            BlockPos c = bastion.pos.offset(k == 0 ? 12 : k == 2 ? -12 : 0, 0, k == 1 ? 12 : k == 3 ? -12 : 0);
            return new PathingCommand(new GoalXZ(c.getX(), c.getZ()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (me.distanceTo(target) > THROW_RANGE || !me.hasLineOfSight(target)) {
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
            ((LocalPlayer) me).drop(false);
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
    private long dropScan = -1000, dropSince, plannedFallUntil;
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
            case 0 -> c.windowClick(w, craftFrom, 0, ClickType.PICKUP, me);
            case 1 -> c.windowClick(w, 1, 1, ClickType.PICKUP, me);
            case 2 -> c.windowClick(w, craftFrom, 0, ClickType.PICKUP, me);
            default -> c.windowClick(w, 0, 0, ClickType.QUICK_MOVE, me);
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
        for (int i = 0; i < 36; i++) { ItemStack s = me.getInventory().getItem(i); if (s.is(net.minecraft.tags.ItemTags.DOORS)) doors += s.getCount(); }
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
            if (b >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, b, 7, ClickType.SWAP, me); return pause0(); }
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
            if (pick >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ClickType.SWAP, me); return pause0(); }
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
        for (int i = 0; i < 36 && slot < 0; i++) if (me.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.DOORS)) slot = i;
        if (slot < 0) { descentSteps = -1; return null; }
        if (slot >= 9) { ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ClickType.SWAP, me); return pause0(); }
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
        for (int i = 0; i < 36 && slot < 0; i++) if (me.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.DOORS)) slot = i;
        double shoreDist = shore == null ? -1 : Math.sqrt(feet.distSqr(shore));
        BastionLava.Escape esc = BastionLava.choose(shoreDist, depth, me.getHealth(), slot >= 0, cell != null, pillarBlocks(me) > 0);
        if (esc != BastionLava.Escape.DOOR) return null;
        if (slot >= 9) {
            ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ClickType.SWAP, me);
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
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 0; dy++) if (lavaAt(me.blockPosition().offset(dx, dy, dz))) return true;
        return false;
    }

    private boolean lavaAt(BlockPos p) {
        return ctx.world().getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);
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
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos s = at.offset(dx, dy, dz);
            if (!ctx.world().hasChunkAt(s) || !floor(s.below()) || floor(s) || floor(s.above())) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos o = s.relative(d);
                if (floor(o) || floor(o.above()) || floor(o.below())) continue;
                safe[0] = false;
                int land = landing(o.getX(), o.getY(), o.getZ(), safe);
                if (land == Integer.MIN_VALUE) continue;
                out.add(new BastionDrops.Ledge(s.getX(), s.getY(), s.getZ(), d.getStepX(), d.getStepZ(), land, safe[0]));
            }
        }
        return out;
    }

    /** A verified drop toward a target far below, instead of the long stairs; null to path normally. */
    private PathingCommand drop(Player me, BlockPos target) {
        if (!me.onGround() || ctx.playerFeet().getY() - target.getY() < BastionSettings.maxSafeDrop + 3) { if (dropPlan != null && me.getY() < dropPlan.y() - 1) dropPlan = null; return null; }
        if (!target.equals(dropFor) || ticks - dropScan > 60 && dropPlan == null) {
            dropFor = target;
            dropScan = ticks;
            BlockPos at = ctx.playerFeet();
            dropPlan = BastionDrops.choose(ledges(me), at.getX(), at.getY(), at.getZ(), target.getX(), target.getY(), target.getZ(), me.getHealth(), BastionSettings.maxSafeDrop);
            dropSince = ticks;
        }
        if (dropPlan == null) return null;
        if (ticks - dropSince > 300) { dropPlan = null; dropScan = ticks + 200; return null; }
        BlockPos ledge = new BlockPos(dropPlan.x(), dropPlan.y(), dropPlan.z());
        if (!ctx.playerFeet().equals(ledge)) {
            status = "to verified drop " + ledge.toShortString() + " (" + dropPlan.height() + " down)";
            return new PathingCommand(new GoalBlock(ledge), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // on the ledge: walk off it; the clutch stands down for this fall
        plannedFallUntil = ticks + 60;
        float yaw = (float) Math.toDegrees(Math.atan2(-dropPlan.dx(), dropPlan.dz()));
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 30), true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
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
        BastionDrops.Clutch d = BastionDrops.clutch(me.fallDistance, remaining + (me.getY() - feet.getY()), intoLava, ticks < plannedFallUntil,
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
                    ctx.playerController().windowClick(me.inventoryMenu.containerId, slot, 7, ClickType.SWAP, me);
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
        return new PathingCommand(new GoalXZ(c.getX() + (int) (dx / len * r), c.getZ() + (int) (dz / len * r)), PathingCommandType.SET_GOAL_AND_PATH);
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
        plan = BastionPlan.order(layout(), bastion.pos.getX(), bastion.pos.getY(), bastion.pos.getZ(), at.getX(), at.getY(), at.getZ(), pts);
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
            // a brute guarding it (even one we gave up fighting) kills a speedrun kit: come back when it has moved
            if (!ctx.world().getEntitiesOfClass(PiglinBrute.class, new net.minecraft.world.phys.AABB(b).inflate(9), PiglinBrute::isAlive).isEmpty()) { unloaded = true; continue; } // deferred, not done
            if (pt.kind() == BastionPlan.Kind.GOLD) {
                if (!wantGold || !ctx.world().getBlockState(b).is(Blocks.GOLD_BLOCK)) continue;
            } else if (!ctx.world().getBlockState(b).is(Blocks.CHEST)) continue;
            next = b;
            break;
        }
        // only "no chest left" once we stand in the bastion with its chunks loaded; from outside an empty plan means nothing
        chestsExhausted = next == null && !unloaded && Math.hypot(me.getX() - bastion.pos.getX(), me.getZ() - bastion.pos.getZ()) < 40;
        if (next == null) return null;
        if (ctx.world().getBlockState(next).is(Blocks.GOLD_BLOCK)) {
            // gold block next in the plan: the miner handles the piglin rule (breaking gold angers every piglin in 16, seen or not)
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
        if (piglinWatching(me, near)) {
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
                me.swing(InteractionHand.MAIN_HAND);
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
            if (me.distanceTo(p) > THROW_RANGE + 0.5 || !me.hasLineOfSight(p) || brutesAround(p) >= 2) continue;
            if (target == null || me.distanceTo(p) < me.distanceTo(target)) target = p;
        }
        if (target == null) return null;
        int slot = hotbarGold(me);
        if (slot < 0) return pause0();
        me.getInventory().setSelectedSlot(slot);
        aimer.look(target.position().add(0, 0.4, 0), 0);
        if (Math.abs(Mth.wrapDegrees(me.getYRot() - yawTo(me, target))) < 12) {
            ((LocalPlayer) me).drop(false);
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
            ctx.playerController().windowClick(cm.containerId, i, 0, ClickType.QUICK_MOVE, me);
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
            ctx.playerController().windowClick(me.inventoryMenu.containerId, pick, 7, ClickType.SWAP, me);
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
                lavaPos = null;
                return null;
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
    private long nudgeUntil;
    private int lootTarget = -1;
    private long startTick;

    private ItemEntity lootNear(Player me) {
        if (ticks - throwTick > ADMIRE_TICKS + 600) return null;
        ItemEntity best = null;
        for (ItemEntity i : ctx.world().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(10))) {
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
            ctx.playerController().windowClick(me.inventoryMenu.containerId, i < 9 ? i + 36 : i, 0, ClickType.QUICK_MOVE, me);
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
        ctx.playerController().windowClick(me.inventoryMenu.containerId, s, 8, ClickType.SWAP, me);
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
