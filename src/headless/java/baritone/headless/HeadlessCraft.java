/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Craft, smelt, chest deposit/withdraw, drop, pickup and armor equip over real container
 * menus. Recipe-book placement is used when the server has unlocked a RecipeDisplayId;
 * otherwise ingredients are clicked into the 2x2 or 3x3 grid. The client Baritone has no
 * equivalent process; menu kind checks go through ClientContainers, and clicks go through
 * IPlayerController.windowClick / HeadlessInventory hashed ContainerClick packets.
 */
package baritone.headless;

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.interact.ClientContainers;
import baritone.process.interact.ContainerView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class HeadlessCraft {

    enum Mode { CRAFT, SMELT, DEPOSIT, WITHDRAW, DROP, PICKUP, EQUIP }

    private static final int CLICK_COOLDOWN = 2;
    private static final int RESULT_WAIT_TICKS = 25;
    private static final int OPEN_WAIT_TICKS = 40;
    private static final int SMELT_WAIT_TICKS = 20 * 30;
    private static final int PICKUP_WAIT_TICKS = 20 * 20;

    private final HeadlessBot bot;
    private final HeadlessPlayer player;
    private final HeadlessPlayerController controller;
    private final HeadlessBaritone host;
    private final HeadlessLiveDriver driver;
    private final HeadlessInventory inventory;

    private boolean active;
    private boolean success;
    private Mode mode = Mode.CRAFT;
    private String status = "idle";
    private int cooldown;
    private int waited;
    private long startedTick;
    private int maxTicks = 20 * 90;

    private Item target;
    private int targetCount = 1;
    private Item fuel = Items.COAL;
    private List<HeadlessRecipes.Recipe> plan = List.of();
    private int step;
    private HeadlessRecipes.Recipe current;
    private boolean placedRecipe;
    private boolean filling;
    private int fillIndex;
    private boolean taking;
    private BlockPos station;
    private boolean placingStation;
    private boolean opening;
    private int remainingDrops;
    private int pickupStartCount;
    private int pickupEntityId = -1;
    private Item lastDroppedItem;
    private int lastDropPreCount;
    private int lastDropAmount;
    private long lastDropTick = -1;

    HeadlessCraft(HeadlessBot bot, HeadlessPlayer player, HeadlessPlayerController controller,
                  HeadlessBaritone host, HeadlessLiveDriver driver) {
        this.bot = bot;
        this.player = player;
        this.controller = controller;
        this.host = host;
        this.driver = driver;
        this.inventory = bot.inventory();
    }

    void startCraft(Item item, int count) {
        begin(Mode.CRAFT, item, Math.max(1, count), 20 * 90);
        plan = HeadlessRecipes.plan(item, targetCount, player.getInventory());
        if (plan.isEmpty()) {
            if (have(item) >= targetCount) {
                finish(true, "already had " + HeadlessRecipes.idOf(item));
            } else {
                finish(false, "cannot plan " + HeadlessRecipes.idOf(item));
            }
            return;
        }
        step = 0;
        status = "crafting " + HeadlessRecipes.idOf(item) + " x" + targetCount + " (" + plan.size() + " steps)";
    }

    void startSmelt(Item input, Item fuel, int count) {
        begin(Mode.SMELT, input, Math.max(1, count), SMELT_WAIT_TICKS + 20 * 30);
        this.fuel = fuel == null ? Items.COAL : fuel;
        status = "smelt " + HeadlessRecipes.idOf(input);
    }

    void startChest(boolean deposit, Item item, int count) {
        begin(deposit ? Mode.DEPOSIT : Mode.WITHDRAW, item, Math.max(1, count), 20 * 45);
        status = (deposit ? "deposit " : "withdraw ") + HeadlessRecipes.idOf(item);
    }

    void startDrop(Item item, int count) {
        begin(Mode.DROP, item, Math.max(1, count), 20 * 20);
        remainingDrops = targetCount;
        status = "drop " + HeadlessRecipes.idOf(item);
    }

    void startPickup(Item item) {
        begin(Mode.PICKUP, item, 1, PICKUP_WAIT_TICKS + 20 * 15);
        int haveNow = item == null ? totalItems() : have(item);
        boolean entityVisible = nearestItem() != null;
        // Ostinato: vanilla auto-collects a throw that is already in pickup range
        // once the 40-tick pickup delay expires. If that happens before this
        // command starts, have() already includes the item and no entity remains,
        // which used to fail "no item entity".
        pickupStartCount = pickupStartAfterAutoCollect(
                haveNow, item, lastDroppedItem, lastDropPreCount, lastDropAmount,
                lastDropTick, bot.ticks(), entityVisible);
        pickupEntityId = -1;
        status = item == null ? "pickup" : "pickup " + HeadlessRecipes.idOf(item);
        if (!entityVisible && haveNow > pickupStartCount) {
            finish(true, item == null ? "picked up item" : "picked up " + HeadlessRecipes.idOf(item));
        }
    }

    void startEquip(Item item) {
        begin(Mode.EQUIP, item, 1, 20 * 20);
        status = item == null ? "equip armor" : "equip " + HeadlessRecipes.idOf(item);
    }

    void cancel() {
        if (!active) {
            status = "idle";
            return;
        }
        opening = false;
        placingStation = false;
        active = false;
        status = "cancelled";
    }

    boolean active() {
        return active;
    }

    boolean success() {
        return success;
    }

    String status() {
        return status;
    }

    void tick() {
        if (!active) {
            return;
        }
        if (bot.ticks() - startedTick > maxTicks) {
            finish(false, "timeout");
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        try {
            switch (mode) {
                case CRAFT -> tickCraft();
                case SMELT -> tickSmelt();
                case DEPOSIT -> tickChest(true);
                case WITHDRAW -> tickChest(false);
                case DROP -> tickDrop();
                case PICKUP -> tickPickup();
                case EQUIP -> tickEquip();
            }
        } catch (RuntimeException e) {
            finish(false, "error: " + e.getMessage());
        }
    }

    private void begin(Mode mode, Item item, int count, int maxTicks) {
        this.mode = mode;
        this.target = item;
        this.targetCount = count;
        this.maxTicks = maxTicks;
        this.startedTick = bot.ticks();
        this.cooldown = 0;
        this.waited = 0;
        this.step = 0;
        this.current = null;
        this.placedRecipe = false;
        this.filling = false;
        this.fillIndex = 0;
        this.taking = false;
        this.station = null;
        this.placingStation = false;
        this.opening = false;
        this.success = false;
        this.active = true;
    }

    private void tickCraft() {
        if (have(target) >= targetCount && step >= plan.size()) {
            inventory.closeContainer();
            finish(true, "crafted " + HeadlessRecipes.idOf(target));
            return;
        }
        if (step >= plan.size()) {
            if (have(target) >= targetCount) {
                inventory.closeContainer();
                finish(true, "crafted " + HeadlessRecipes.idOf(target));
            } else {
                inventory.closeContainer();
                finish(false, "finished steps but missing " + HeadlessRecipes.idOf(target));
            }
            return;
        }
        current = plan.get(step);
        if (have(current.result()) >= neededNow(current.result()) && have(target) >= targetCount && step == plan.size() - 1) {
            step = plan.size();
            return;
        }
        boolean table = current.needsTable();
        ContainerView.Kind kind = inventory.kind();
        if (table && kind != ContainerView.Kind.CRAFTING) {
            if (kind != ContainerView.Kind.NONE) {
                inventory.closeContainer();
                pause(CLICK_COOLDOWN);
                return;
            }
            if (!ensureStation(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE)) {
                return;
            }
        }
        if (!table && kind == ContainerView.Kind.CRAFTING) {
            inventory.closeContainer();
            pause(CLICK_COOLDOWN);
            return;
        }
        if (!inventory.carriedEmpty()) {
            parkCarried();
            return;
        }
        if (taking) {
            takeCraftResult();
            return;
        }
        if (placedRecipe || filling) {
            waitForCraftResult();
            return;
        }
        RecipeDisplayId recipeId = inventory.recipes().find(current.result(), player.level());
        if (recipeId != null) {
            inventory.placeRecipe(recipeId, false);
            placedRecipe = true;
            waited = 0;
            status = "place-recipe " + HeadlessRecipes.idOf(current.result());
            pause(CLICK_COOLDOWN);
            return;
        }
        filling = true;
        fillIndex = 0;
        status = "fill-grid " + HeadlessRecipes.idOf(current.result());
        clearCraftGridIfDirty();
    }

    private void waitForCraftResult() {
        int resultSlot = HeadlessInventory.resultSlot(inventory.menu());
        ItemStack result = inventory.slot(resultSlot);
        if (!result.isEmpty() && result.is(current.result())) {
            taking = true;
            placedRecipe = false;
            filling = false;
            takeCraftResult();
            return;
        }
        if (filling && fillIndex < current.grid().length) {
            if (placeNextIngredient()) {
                return;
            }
        }
        waited++;
        if (waited > RESULT_WAIT_TICKS) {
            if (filling && !gridMatches()) {
                filling = false;
                fillIndex = 0;
                waited = 0;
                clearCraftGridIfDirty();
                filling = true;
                if (waited == 0 && placeNextIngredient()) {
                    return;
                }
            }
            finish(false, "no result for " + HeadlessRecipes.idOf(current.result()));
        }
    }

    private boolean placeNextIngredient() {
        Item[] grid = current.grid();
        AbstractContainerMenu menu = inventory.menu();
        int start = HeadlessInventory.craftSlotStart(menu);
        while (fillIndex < grid.length) {
            Item need = grid[fillIndex];
            int dest = start + fillIndex;
            fillIndex++;
            if (need == null) {
                if (!inventory.slot(dest).isEmpty()) {
                    inventory.click(dest, 0, ContainerInput.QUICK_MOVE);
                    pause(CLICK_COOLDOWN);
                    return true;
                }
                continue;
            }
            ItemStack there = inventory.slot(dest);
            if (there.is(need) && there.getCount() >= 1) {
                continue;
            }
            if (!there.isEmpty()) {
                inventory.click(dest, 0, ContainerInput.QUICK_MOVE);
                fillIndex--;
                pause(CLICK_COOLDOWN);
                return true;
            }
            int src = inventory.findSlot(stack -> stack.is(need), true);
            if (src < 0) {
                finish(false, "missing ingredient " + HeadlessRecipes.idOf(need));
                return true;
            }
            // Ostinato: right-click picks one off the stack, then left-click places it in the grid.
            inventory.click(src, 1, ContainerInput.PICKUP);
            inventory.click(dest, 0, ContainerInput.PICKUP);
            if (!inventory.carriedEmpty()) {
                inventory.click(src, 0, ContainerInput.PICKUP);
            }
            pause(CLICK_COOLDOWN);
            waited = 0;
            return true;
        }
        filling = true;
        return false;
    }

    private void takeCraftResult() {
        int resultSlot = HeadlessInventory.resultSlot(inventory.menu());
        ItemStack result = inventory.slot(resultSlot);
        if (result.isEmpty() || (current != null && !result.is(current.result()))) {
            taking = false;
            placedRecipe = false;
            filling = false;
            step++;
            pause(CLICK_COOLDOWN);
            return;
        }
        inventory.click(resultSlot, 0, ContainerInput.QUICK_MOVE);
        if (!inventory.slot(resultSlot).isEmpty()) {
            int empty = inventory.findEmptyPlayerSlot();
            if (empty >= 0 && !inventory.carriedEmpty()) {
                inventory.click(empty, 0, ContainerInput.PICKUP);
            } else if (!inventory.slot(resultSlot).isEmpty()) {
                inventory.click(resultSlot, 0, ContainerInput.PICKUP);
                empty = inventory.findEmptyPlayerSlot();
                if (empty >= 0) {
                    inventory.click(empty, 0, ContainerInput.PICKUP);
                }
            }
        }
        taking = false;
        placedRecipe = false;
        filling = false;
        clearCraftGridIfDirty();
        step++;
        status = "crafted step " + step + "/" + plan.size();
        pause(CLICK_COOLDOWN);
    }

    private void clearCraftGridIfDirty() {
        AbstractContainerMenu menu = inventory.menu();
        if (menu == null) {
            return;
        }
        int start = HeadlessInventory.craftSlotStart(menu);
        int count = HeadlessInventory.craftSlotCount(menu);
        for (int i = 0; i < count; i++) {
            if (!inventory.slot(start + i).isEmpty()) {
                inventory.click(start + i, 0, ContainerInput.QUICK_MOVE);
                pause(CLICK_COOLDOWN);
                return;
            }
        }
    }

    private boolean gridMatches() {
        if (current == null) {
            return false;
        }
        AbstractContainerMenu menu = inventory.menu();
        int start = HeadlessInventory.craftSlotStart(menu);
        Item[] grid = current.grid();
        for (int i = 0; i < grid.length; i++) {
            ItemStack there = inventory.slot(start + i);
            if (grid[i] == null) {
                if (!there.isEmpty()) {
                    return false;
                }
            } else if (!there.is(grid[i])) {
                return false;
            }
        }
        return true;
    }

    private void tickSmelt() {
        Item output = Items.IRON_INGOT;
        if (target != Items.RAW_IRON && target != Items.IRON_INGOT) {
            // Ostinato: the live gate is raw iron; still allow smelting the named input to its default ingot.
            output = target == Items.RAW_IRON ? Items.IRON_INGOT : target;
        }
        if (target == Items.RAW_IRON) {
            output = Items.IRON_INGOT;
        }
        if (have(output) >= targetCount && !inventory.isMenuOpen()) {
            finish(true, "smelted " + HeadlessRecipes.idOf(output));
            return;
        }
        if (inventory.kind() != ContainerView.Kind.FURNACE) {
            if (inventory.isMenuOpen()) {
                inventory.closeContainer();
                pause(CLICK_COOLDOWN);
                return;
            }
            if (!ensureStation(Blocks.FURNACE, Items.FURNACE)) {
                return;
            }
        }
        if (!inventory.carriedEmpty()) {
            parkCarried();
            return;
        }
        ItemStack result = inventory.slot(2);
        if (!result.isEmpty()) {
            inventory.click(2, 0, ContainerInput.QUICK_MOVE);
            if (!inventory.slot(2).isEmpty()) {
                inventory.click(2, 0, ContainerInput.PICKUP);
                int empty = inventory.findEmptyPlayerSlot();
                if (empty >= 0) {
                    inventory.click(empty, 0, ContainerInput.PICKUP);
                }
            }
            status = "took smelt output";
            pause(CLICK_COOLDOWN);
            if (have(output) >= targetCount) {
                inventory.closeContainer();
                finish(true, "smelted " + HeadlessRecipes.idOf(output));
            }
            return;
        }
        ItemStack inputSlot = inventory.slot(0);
        if (inputSlot.isEmpty() && have(target) > 0) {
            int src = inventory.findSlot(stack -> stack.is(target), true);
            if (src >= 0) {
                inventory.click(src, 0, ContainerInput.PICKUP);
                inventory.click(0, 0, ContainerInput.PICKUP);
                if (!inventory.carriedEmpty()) {
                    inventory.click(src, 0, ContainerInput.PICKUP);
                }
                pause(CLICK_COOLDOWN);
                waited = 0;
                status = "loaded input";
                return;
            }
        }
        ItemStack fuelSlot = inventory.slot(1);
        if (fuelSlot.isEmpty()) {
            int src = inventory.findSlot(stack -> stack.is(fuel) || stack.is(Items.CHARCOAL), true);
            if (src >= 0) {
                inventory.click(src, 0, ContainerInput.PICKUP);
                inventory.click(1, 0, ContainerInput.PICKUP);
                if (!inventory.carriedEmpty()) {
                    inventory.click(src, 0, ContainerInput.PICKUP);
                }
                pause(CLICK_COOLDOWN);
                waited = 0;
                status = "loaded fuel";
                return;
            }
            // Ostinato: one coal is consumed into burn time immediately, so the fuel
            // slot goes empty while raw iron is still smelting. A stale SetContent can
            // also make both the fuel slot and the player inventory look empty mid-click.
            // Wait for the next sync (or the output) instead of aborting.
            if (!inputSlot.isEmpty()) {
                waited++;
                status = "smelting " + waited;
                if (waited > SMELT_WAIT_TICKS) {
                    finish(false, "smelt timed out");
                }
                return;
            }
            waited++;
            status = "waiting for fuel sync " + waited;
            if (waited > 40) {
                finish(false, "no fuel");
            }
            return;
        }
        waited++;
        status = "smelting " + waited;
        if (waited > SMELT_WAIT_TICKS) {
            finish(false, "smelt timed out");
        }
    }

    private void tickChest(boolean deposit) {
        if (inventory.kind() != ContainerView.Kind.CHEST) {
            if (inventory.isMenuOpen()) {
                inventory.closeContainer();
                pause(CLICK_COOLDOWN);
                return;
            }
            if (!ensureStation(Blocks.CHEST, Items.CHEST)) {
                return;
            }
        }
        if (!inventory.carriedEmpty()) {
            parkCarried();
            return;
        }
        AbstractContainerMenu menu = inventory.menu();
        int chestSize = HeadlessInventory.chestSize(menu);
        int playerStart = HeadlessInventory.playerInventoryStart(menu);
        if (deposit) {
            int moved = 0;
            for (int i = playerStart; i < menu.slots.size(); i++) {
                ItemStack stack = inventory.slot(i);
                if (!stack.isEmpty() && stack.is(target)) {
                    inventory.click(i, 0, ContainerInput.QUICK_MOVE);
                    moved++;
                    pause(CLICK_COOLDOWN);
                    status = "deposited " + HeadlessRecipes.idOf(target);
                    break;
                }
            }
            if (moved == 0) {
                inventory.closeContainer();
                finish(true, "deposited " + HeadlessRecipes.idOf(target));
            }
        } else {
            int moved = 0;
            for (int i = 0; i < chestSize; i++) {
                ItemStack stack = inventory.slot(i);
                if (!stack.isEmpty() && (target == null || stack.is(target))) {
                    inventory.click(i, 0, ContainerInput.QUICK_MOVE);
                    moved++;
                    pause(CLICK_COOLDOWN);
                    status = "withdrew " + (target == null ? "items" : HeadlessRecipes.idOf(target));
                    break;
                }
            }
            if (moved == 0) {
                inventory.closeContainer();
                finish(true, "withdrew " + (target == null ? "items" : HeadlessRecipes.idOf(target)));
            }
        }
    }

    private void tickDrop() {
        if (inventory.isMenuOpen()) {
            inventory.closeContainer();
            pause(CLICK_COOLDOWN);
            return;
        }
        if (remainingDrops <= 0 || have(target) <= 0) {
            finish(true, "dropped " + HeadlessRecipes.idOf(target));
            return;
        }
        if (!selectInHotbar(target)) {
            finish(false, "cannot select " + HeadlessRecipes.idOf(target));
            return;
        }
        boolean all = remainingDrops >= player.getMainHandItem().getCount();
        int dropped = all ? player.getMainHandItem().getCount() : 1;
        int before = have(target);
        controller.dropHeld(all);
        lastDroppedItem = target;
        lastDropPreCount = before;
        lastDropAmount = dropped;
        lastDropTick = bot.ticks();
        remainingDrops -= dropped;
        status = "dropping " + remainingDrops + " left";
        pause(CLICK_COOLDOWN);
    }

    private void tickPickup() {
        if (target != null && have(target) > pickupStartCount) {
            finish(true, "picked up " + HeadlessRecipes.idOf(target));
            return;
        }
        if (target == null && totalItems() > pickupStartCount) {
            finish(true, "picked up item");
            return;
        }
        HeadlessEntityTracker.Tracked item = nearestItem();
        if (item == null) {
            waited++;
            if (waited > 20 && pickupEntityId != -1) {
                // Entity vanished; check inventory once more.
                if (target == null || have(target) > pickupStartCount) {
                    finish(true, "picked up item");
                    return;
                }
            }
            if (waited > PICKUP_WAIT_TICKS) {
                finish(false, "no item entity");
            }
            return;
        }
        pickupEntityId = item.id;
        // Ostinato: vanilla ItemEntity pickup is a 1-block inflate of the player
        // AABB. Items already in that range must be left for auto-collect; do not
        // path with GoalBlock. Walk onto the item only when it is out of range
        // (GoalGetToBlock is already complete from an adjacent tile).
        if (!inVanillaItemPickupRange(player.getBoundingBox(), item.box())) {
            BlockPos pos = BlockPos.containing(item.x, item.y, item.z);
            driver.requestStandOn(pos);
            status = "approaching drop " + pos.toShortString();
            return;
        }
        waited++;
        status = "waiting to pickup";
        if (waited > PICKUP_WAIT_TICKS) {
            finish(false, "pickup timed out");
        }
    }

    private void tickEquip() {
        if (inventory.isMenuOpen()) {
            inventory.closeContainer();
            pause(CLICK_COOLDOWN);
            return;
        }
        if (armorEquipped()) {
            finish(true, "equipped");
            return;
        }
        InventoryMenu menu = player.inventoryMenu;
        player.containerMenu = menu;
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = inventory.slot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (target != null && !stack.is(target)) {
                continue;
            }
            if (isArmor(stack)) {
                inventory.click(i, 0, ContainerInput.QUICK_MOVE);
                status = "equipping " + HeadlessRecipes.idOf(stack.getItem());
                pause(CLICK_COOLDOWN);
                return;
            }
        }
        if (armorEquipped()) {
            finish(true, "equipped");
        } else {
            finish(false, "no armor to equip");
        }
    }

    /**
     * Place or walk to a station block and right-click it. @return true when that menu is open.
     */
    private boolean ensureStation(Block block, Item item) {
        if (opening) {
            waited++;
            if (inventoryKindOf(block)) {
                opening = false;
                waited = 0;
                return true;
            }
            if (waited == 5 || waited == 15) {
                useStation(station);
            }
            if (waited > OPEN_WAIT_TICKS) {
                finish(false, "did not open " + BuiltInId(block));
            }
            return false;
        }
        if (placingStation) {
            if (station != null && controller.placeConfirmed(station)) {
                placingStation = false;
                opening = true;
                waited = 0;
                useStation(station);
                status = "opening " + station.toShortString();
                pause(CLICK_COOLDOWN);
            } else if (station != null && controller.placeRejected(station)) {
                placingStation = false;
                station = null;
            } else if (driver.isPlacing()) {
                status = "placing " + BuiltInId(block);
            } else {
                waited++;
                if (waited > 40) {
                    finish(false, "place " + BuiltInId(block) + " failed");
                }
            }
            return false;
        }
        BlockPos existing = findNearby(block);
        if (existing != null) {
            station = existing;
            if (!inUseRange(existing)) {
                driver.requestGoto(existing);
                status = "approaching " + existing.toShortString();
                return false;
            }
            opening = true;
            waited = 0;
            useStation(existing);
            status = "opening " + existing.toShortString();
            pause(CLICK_COOLDOWN);
            return false;
        }
        if (have(item) <= 0) {
            finish(false, "no " + HeadlessRecipes.idOf(item) + " to place");
            return false;
        }
        BlockPos dest = findPlacePos();
        if (dest == null) {
            finish(false, "no place for " + HeadlessRecipes.idOf(item));
            return false;
        }
        if (!selectInHotbar(item)) {
            finish(false, "cannot select " + HeadlessRecipes.idOf(item));
            return false;
        }
        station = dest;
        placingStation = true;
        waited = 0;
        driver.requestPlace(dest);
        status = "placing " + dest.toShortString();
        return false;
    }

    private void useStation(BlockPos pos) {
        if (pos == null) {
            return;
        }
        lookAt(Vec3.atCenterOf(pos));
        // Prefer an empty or non-block hand so a held block is not predicted as a place.
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            int empty = findEmptyHotbar();
            if (empty >= 0) {
                player.getInventory().setSelectedSlot(empty);
                controller.syncHeldItem();
            }
        }
        Vec3 hit = Vec3.atCenterOf(pos).add(0, 0.51, 0);
        controller.interactBlock(new BlockHitResult(hit, Direction.UP, pos, false));
    }

    private boolean inventoryKindOf(Block block) {
        ContainerView.Kind expected = ClientContainers.openedBy(block);
        return expected != ContainerView.Kind.NONE && inventory.kind() == expected;
    }

    private BlockPos findNearby(Block block) {
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int x = feet.getX() - 8; x <= feet.getX() + 8; x++) {
            for (int z = feet.getZ() - 8; z <= feet.getZ() + 8; z++) {
                for (int y = feet.getY() - 3; y <= feet.getY() + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!player.level().isLoaded(pos)) {
                        continue;
                    }
                    if (!player.level().getBlockState(pos).is(block)) {
                        continue;
                    }
                    double d = pos.distSqr(feet);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findPlacePos() {
        BlockPos feet = player.blockPosition();
        Direction facing = player.getDirection();
        BlockPos[] candidates = new BlockPos[]{
                feet.relative(facing),
                feet.relative(facing.getClockWise()),
                feet.relative(facing.getCounterClockWise()),
                feet.relative(facing.getOpposite()),
                feet.relative(facing).above(),
        };
        for (BlockPos dest : candidates) {
            if (player.level().isLoaded(dest)
                    && player.level().getBlockState(dest).canBeReplaced()
                    && !player.level().getBlockState(dest.below()).canBeReplaced()) {
                return dest.immutable();
            }
        }
        return null;
    }

    private boolean inUseRange(BlockPos pos) {
        return player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= 20.0;
    }

    private void lookAt(Vec3 target) {
        Rotation rot = RotationUtils.calcRotationFromVec3d(player.getEyePosition(), target,
                new Rotation(player.getYRot(), player.getXRot()));
        player.setYRot(rot.getYaw());
        player.setXRot(rot.getPitch());
    }

    private boolean selectInHotbar(Item item) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                controller.syncHeldItem();
                return true;
            }
        }
        int src = inventory.findSlot(stack -> stack.is(item), true);
        if (src < 0) {
            return false;
        }
        int dest = findEmptyHotbar();
        if (dest < 0) {
            dest = Math.max(0, inv.getSelectedSlot());
        }
        // InventoryBehavior: main-inventory index i maps to menu slot i, hotbar is i+36 when i<9.
        inventory.click(src, dest, ContainerInput.SWAP);
        inv.setSelectedSlot(dest);
        controller.syncHeldItem();
        pause(CLICK_COOLDOWN);
        return inv.getItem(dest).is(item) || inv.getItem(inv.getSelectedSlot()).is(item);
    }

    private int findEmptyHotbar() {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void parkCarried() {
        int empty = inventory.findEmptyPlayerSlot();
        if (empty >= 0) {
            inventory.click(empty, 0, ContainerInput.PICKUP);
        } else {
            inventory.click(-999, 0, ContainerInput.PICKUP);
        }
        pause(CLICK_COOLDOWN);
    }

    private int have(Item item) {
        if (item == null) {
            return 0;
        }
        if (item == Items.OAK_PLANKS) {
            int specific = HeadlessRecipes.count(player.getInventory(), item);
            int any = HeadlessRecipes.countMatching(player.getInventory(), HeadlessRecipes::isPlanks);
            return Math.max(specific, any);
        }
        return HeadlessRecipes.count(player.getInventory(), item);
    }

    private int neededNow(Item result) {
        int need = 0;
        for (int i = step; i < plan.size(); i++) {
            HeadlessRecipes.Recipe recipe = plan.get(i);
            if (recipe.result() == result) {
                need += recipe.resultCount();
            }
        }
        return Math.max(need, 1);
    }

    private int totalItems() {
        int n = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            n += stack.getCount();
        }
        return n;
    }

    private HeadlessEntityTracker.Tracked nearestItem() {
        return bot.entities().nearest(player.position(), "item", 16);
    }

    private boolean isArmor(ItemStack stack) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return false;
        }
        EquipmentSlot slot = equippable.slot();
        return slot.isArmor();
    }

    private boolean armorEquipped() {
        int filled = 0;
        for (int i = InventoryMenu.ARMOR_SLOT_START; i < InventoryMenu.ARMOR_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                if (target == null || stack.is(target) || isArmor(stack)) {
                    filled++;
                }
            }
        }
        return target == null ? filled >= 4 : filled >= 1;
    }

    // Ostinato: if vanilla already scooped our throw, start the pickup baseline
    // at the pre-drop count so have() > baseline still reports success.
    static int pickupStartAfterAutoCollect(int haveNow, Item wanted, Item lastDropped,
                                           int lastDropPreCount, int lastDropAmount,
                                           long lastDropTick, long nowTick, boolean entityVisible) {
        if (entityVisible || lastDropped == null || lastDropTick < 0 || lastDropAmount <= 0) {
            return haveNow;
        }
        if (wanted != null && wanted != lastDropped) {
            return haveNow;
        }
        if (nowTick - lastDropTick > 20L * 6) {
            return haveNow;
        }
        if (haveNow >= lastDropPreCount) {
            return lastDropPreCount - lastDropAmount;
        }
        return haveNow;
    }

    // Ostinato: matches ItemEntity's player-AABB inflate(1.0) pickup volume.
    static boolean inVanillaItemPickupRange(net.minecraft.world.phys.AABB playerBox,
                                            net.minecraft.world.phys.AABB itemBox) {
        return playerBox.inflate(1.0).intersects(itemBox);
    }

    private static String BuiltInId(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private void pause(int ticks) {
        cooldown = ticks;
    }

    private void finish(boolean ok, String why) {
        if (inventory != null && inventory.isMenuOpen() && ok) {
            inventory.closeContainer();
        }
        active = false;
        success = ok;
        status = (ok ? "done: " : "failed: ") + why;
        bot.event("craft " + status);
        System.out.println("[HeadlessCraft] " + status);
    }
}
