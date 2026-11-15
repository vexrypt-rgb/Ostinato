/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Vanilla melee for the protocol bot: track a target, look at it, walk in, swap to the
 * best hotbar weapon, and swing only with a full attack-strength charge inside vanilla
 * reach. Eating starts when hungry and no target is in reach.
 *
 * Scherzo and Bombard stay on the client (keyAttack, TNT spear, world entity getter).
 * 26.3 split the old ServerboundInteractPacket ATTACK action into ServerboundAttackPacket;
 * this sends that packet, which is what a real 26.3 client uses to hit an entity.
 */
package baritone.headless;

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.player.Inventory;

final class HeadlessCombat {

    private static final int APPROACH_COOLDOWN_TICKS = 20;
    private static final int EAT_WHEN_FOOD_AT_MOST = 16;
    private static final int EAT_TIMEOUT_TICKS = 40;
    private static final int MAX_TICKS = 20 * 120;
    private static final double HUNT_RANGE = 48.0;

    private final HeadlessBot bot;
    private final HeadlessPlayer player;
    private final HeadlessPlayerController controller;
    private final HeadlessBaritone host;
    private final HeadlessEntityTracker tracker;

    private boolean active;
    private boolean sawTarget;
    private String filter = "";
    private String status = "idle";
    private int pendingAttackId = -1;
    private int hits;
    private int lastApproachTick = Integer.MIN_VALUE;
    private int lastApproachX, lastApproachY, lastApproachZ;
    private boolean eating;
    private int eatTicks;
    private long startedTick;

    HeadlessCombat(HeadlessBot bot, HeadlessPlayer player, HeadlessPlayerController controller,
                   HeadlessBaritone host, HeadlessEntityTracker tracker) {
        this.bot = bot;
        this.player = player;
        this.controller = controller;
        this.host = host;
        this.tracker = tracker;
    }

    void start(String typeFilter) {
        this.filter = typeFilter == null ? "" : typeFilter;
        this.active = true;
        this.sawTarget = false;
        this.hits = 0;
        this.pendingAttackId = -1;
        this.eating = false;
        this.eatTicks = 0;
        this.lastApproachTick = Integer.MIN_VALUE;
        this.startedTick = bot.ticks();
        this.status = this.filter.isEmpty() ? "hunting" : "hunting " + this.filter;
    }

    void cancel() {
        if (eating) {
            controller.releaseUseItem();
            eating = false;
        }
        active = false;
        pendingAttackId = -1;
        status = "idle";
    }

    boolean active() {
        return active;
    }

    int hits() {
        return hits;
    }

    String status() {
        return status;
    }

    void tick() {
        if (!active) {
            return;
        }
        pendingAttackId = -1;
        if (bot.ticks() - startedTick > MAX_TICKS) {
            status = "timed out hits=" + hits;
            active = false;
            return;
        }
        HeadlessEntityTracker.Tracked target = tracker.nearest(player.position(), filter, HUNT_RANGE);
        if (target == null) {
            if (sawTarget) {
                status = "cleared hits=" + hits;
                active = false;
            } else {
                status = filter.isEmpty() ? "waiting for target" : "waiting for " + filter;
            }
            return;
        }
        sawTarget = true;
        Rotation look = RotationUtils.calcRotationFromVec3d(
                player.getEyePosition(), target.eye(),
                new Rotation(player.getYRot(), player.getXRot()));
        player.setYRot(look.getYaw());
        player.setXRot(look.getPitch());

        double range = player.entityInteractionRange();
        double distSq = target.box().distanceToSqr(player.getEyePosition());
        boolean inReach = distSq <= range * range;
        if (maybeEat(inReach)) {
            status = "eating target=" + describe(target) + " food=" + player.getFoodData().getFoodLevel();
            return;
        }
        selectBestWeapon();
        float charge = player.getAttackStrengthScale(0.0f);
        if (!inReach) {
            maybeApproach(target);
            status = String.format("approaching %s dist=%.2f cd=%.2f", describe(target), Math.sqrt(distSq), charge);
            return;
        }
        if (charge >= 1.0f) {
            pendingAttackId = target.id;
            status = String.format("swing %s dist=%.2f cd=%.2f hits=%d", describe(target), Math.sqrt(distSq), charge, hits);
        } else {
            status = String.format("charging %s dist=%.2f cd=%.2f hits=%d", describe(target), Math.sqrt(distSq), charge, hits);
        }
    }

    /** After the look/position packet, matching a real client's interact-after-move order. */
    void flushAttack() {
        if (!active || pendingAttackId < 0) {
            return;
        }
        bot.send(new ServerboundAttackPacket(pendingAttackId));
        player.resetAttackStrengthTicker();
        hits++;
        status = "hit #" + hits + " entity=" + pendingAttackId;
        pendingAttackId = -1;
    }

    private void maybeApproach(HeadlessEntityTracker.Tracked target) {
        int bx = (int) Math.floor(target.x);
        int by = (int) Math.floor(target.y);
        int bz = (int) Math.floor(target.z);
        int now = (int) bot.ticks();
        if (now - lastApproachTick < APPROACH_COOLDOWN_TICKS
                && Math.abs(bx - lastApproachX) <= 1
                && Math.abs(by - lastApproachY) <= 1
                && Math.abs(bz - lastApproachZ) <= 1) {
            return;
        }
        lastApproachTick = now;
        lastApproachX = bx;
        lastApproachY = by;
        lastApproachZ = bz;
        host.getPathingBehavior().gotoGoal(new GoalGetToBlock(new BlockPos(bx, by, bz)));
    }

    private boolean maybeEat(boolean inReach) {
        int food = player.getFoodData().getFoodLevel();
        if (eating) {
            eatTicks++;
            if (food >= 20 || eatTicks >= EAT_TIMEOUT_TICKS || inReach) {
                if (inReach && food < 20) {
                    controller.releaseUseItem();
                }
                eating = false;
                eatTicks = 0;
                return false;
            }
            return true;
        }
        if (inReach || food > EAT_WHEN_FOOD_AT_MOST) {
            return false;
        }
        int slot = findFoodSlot();
        if (slot < 0) {
            return false;
        }
        player.getInventory().setSelectedSlot(slot);
        controller.syncHeldItem();
        controller.useHeldItem(InteractionHand.MAIN_HAND, player.getYRot(), player.getXRot());
        eating = true;
        eatTicks = 0;
        return true;
    }

    private void selectBestWeapon() {
        Inventory inv = player.getInventory();
        synchronized (inv) {
            int bestSlot = inv.getSelectedSlot();
            double best = weaponScore(inv.getSelectedItem());
            int hotbar = Math.min(Inventory.SELECTION_SIZE, inv.getNonEquipmentItems().size());
            for (int i = 0; i < hotbar; i++) {
                double score = weaponScore(inv.getItem(i));
                if (score > best) {
                    best = score;
                    bestSlot = i;
                }
            }
            if (best > 0 && bestSlot != inv.getSelectedSlot()) {
                inv.setSelectedSlot(bestSlot);
                controller.syncHeldItem();
            }
        }
    }

    static double weaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        Item item = stack.getItem();
        if (item == Items.NETHERITE_SWORD) return 200;
        if (item == Items.NETHERITE_AXE) return 190;
        if (item == Items.DIAMOND_SWORD) return 180;
        if (item == Items.DIAMOND_AXE) return 170;
        if (item == Items.IRON_SWORD) return 160;
        if (item == Items.IRON_AXE) return 150;
        if (item == Items.TRIDENT) return 155;
        if (item == Items.MACE) return 154;
        if (item == Items.STONE_SWORD) return 140;
        if (item == Items.STONE_AXE) return 130;
        if (item == Items.GOLDEN_SWORD) return 120;
        if (item == Items.WOODEN_SWORD) return 110;
        if (item == Items.GOLDEN_AXE) return 105;
        if (item == Items.WOODEN_AXE) return 100;
        return 0.0;
    }

    static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    private int findFoodSlot() {
        Inventory inv = player.getInventory();
        synchronized (inv) {
            int hotbar = Math.min(Inventory.SELECTION_SIZE, inv.getNonEquipmentItems().size());
            for (int i = 0; i < hotbar; i++) {
                if (isFood(inv.getItem(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String describe(HeadlessEntityTracker.Tracked target) {
        return target.typeId() + "#" + target.id;
    }
}
