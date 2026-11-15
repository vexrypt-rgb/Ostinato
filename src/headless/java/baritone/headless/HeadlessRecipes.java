/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Vanilla 2x2 / 3x3 patterns the live craft gates need, plus aliases so a command like
 * "craft planks" can pick the plank type that matches the logs already in the inventory.
 */
package baritone.headless;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HeadlessRecipes {

    record Ingredient(Item item, int count, boolean anyPlanks, boolean anyLogs) {
        static Ingredient of(Item item, int count) {
            return new Ingredient(item, count, false, false);
        }

        static Ingredient planks(int count) {
            return new Ingredient(null, count, true, false);
        }

        static Ingredient logs(int count) {
            return new Ingredient(null, count, false, true);
        }
    }

    /**
     * @param grid row-major slots for a {@code width} x {@code width} craft; null is empty.
     *             Shapeless recipes still list each required stack in {@link #ingredients}.
     */
    record Recipe(Item result, int resultCount, int width, Item[] grid, List<Ingredient> ingredients, boolean shapeless) {
        boolean needsTable() {
            return width > 2;
        }
    }

    private static final Map<Item, Item> LOG_TO_PLANKS = new LinkedHashMap<>();

    static {
        map(Items.OAK_LOG, Items.OAK_WOOD, Items.STRIPPED_OAK_LOG, Items.STRIPPED_OAK_WOOD, Items.OAK_PLANKS);
        map(Items.SPRUCE_LOG, Items.SPRUCE_WOOD, Items.STRIPPED_SPRUCE_LOG, Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_PLANKS);
        map(Items.BIRCH_LOG, Items.BIRCH_WOOD, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_PLANKS);
        map(Items.JUNGLE_LOG, Items.JUNGLE_WOOD, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_PLANKS);
        map(Items.ACACIA_LOG, Items.ACACIA_WOOD, Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_PLANKS);
        map(Items.DARK_OAK_LOG, Items.DARK_OAK_WOOD, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_PLANKS);
        map(Items.MANGROVE_LOG, Items.MANGROVE_WOOD, Items.STRIPPED_MANGROVE_LOG, Items.STRIPPED_MANGROVE_WOOD, Items.MANGROVE_PLANKS);
        map(Items.CHERRY_LOG, Items.CHERRY_WOOD, Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_CHERRY_WOOD, Items.CHERRY_PLANKS);
        map(Items.PALE_OAK_LOG, Items.PALE_OAK_WOOD, Items.STRIPPED_PALE_OAK_LOG, Items.STRIPPED_PALE_OAK_WOOD, Items.PALE_OAK_PLANKS);
        map(Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK, Items.BAMBOO_PLANKS);
        map(Items.CRIMSON_STEM, Items.CRIMSON_HYPHAE, Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_PLANKS);
        map(Items.WARPED_STEM, Items.WARPED_HYPHAE, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_PLANKS);
    }

    private static void map(Item... items) {
        Item planks = items[items.length - 1];
        for (int i = 0; i < items.length - 1; i++) {
            LOG_TO_PLANKS.put(items[i], planks);
        }
    }

    private HeadlessRecipes() {
    }

    static boolean isLog(Item item) {
        return LOG_TO_PLANKS.containsKey(item);
    }

    static boolean isPlanks(Item item) {
        return LOG_TO_PLANKS.containsValue(item);
    }

    static Item planksForLog(Item log) {
        return LOG_TO_PLANKS.get(log);
    }

    static Item resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase();
        return switch (id) {
            case "planks", "plank", "any_planks" -> Items.OAK_PLANKS;
            case "sticks", "stick" -> Items.STICK;
            case "crafting_table", "workbench", "craft_table", "craftingtable" -> Items.CRAFTING_TABLE;
            case "wooden_pickaxe", "wood_pickaxe", "woodpickaxe" -> Items.WOODEN_PICKAXE;
            case "stone_pickaxe" -> Items.STONE_PICKAXE;
            case "iron_pickaxe" -> Items.IRON_PICKAXE;
            case "furnace" -> Items.FURNACE;
            case "chest" -> Items.CHEST;
            case "iron_ingot", "iron" -> Items.IRON_INGOT;
            case "raw_iron" -> Items.RAW_IRON;
            default -> {
                String full = id.contains(":") ? id : "minecraft:" + id;
                yield BuiltInRegistries.ITEM.getOptional(Identifier.parse(full)).orElse(null);
            }
        };
    }

    static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /**
     * Prefer the plank type already in the inventory, otherwise the type that matches a carried log.
     * {@code wanted} may be the generic oak-planks alias from {@link #resolve("planks")}.
     */
    static Item concreteResult(Item wanted, Inventory inventory) {
        if (wanted == null) {
            return null;
        }
        if (wanted == Items.OAK_PLANKS || isPlanks(wanted)) {
            int haveWanted = count(inventory, wanted);
            if (haveWanted > 0 && wanted != Items.OAK_PLANKS) {
                return wanted;
            }
            Item fromInv = firstMatching(inventory, HeadlessRecipes::isPlanks);
            if (fromInv != null) {
                return fromInv;
            }
            Item log = firstMatching(inventory, HeadlessRecipes::isLog);
            if (log != null) {
                return planksForLog(log);
            }
        }
        return wanted;
    }

    static Recipe recipeFor(Item result, Inventory inventory) {
        Item concrete = concreteResult(result, inventory);
        if (concrete == null) {
            return null;
        }
        if (isPlanks(concrete)) {
            Item log = logForPlanks(concrete, inventory);
            if (log == null) {
                return null;
            }
            return new Recipe(concrete, 4, 2, new Item[]{log, null, null, null},
                    List.of(Ingredient.of(log, 1)), true);
        }
        if (concrete == Items.STICK) {
            Item planks = firstMatching(inventory, HeadlessRecipes::isPlanks);
            if (planks == null) {
                Item log = firstMatching(inventory, HeadlessRecipes::isLog);
                planks = log == null ? Items.OAK_PLANKS : planksForLog(log);
            }
            return new Recipe(Items.STICK, 4, 2, new Item[]{planks, null, planks, null},
                    List.of(Ingredient.of(planks, 2)), false);
        }
        if (concrete == Items.CRAFTING_TABLE) {
            Item planks = firstMatching(inventory, HeadlessRecipes::isPlanks);
            if (planks == null) {
                Item log = firstMatching(inventory, HeadlessRecipes::isLog);
                planks = log == null ? Items.OAK_PLANKS : planksForLog(log);
            }
            return new Recipe(Items.CRAFTING_TABLE, 1, 2, new Item[]{planks, planks, planks, planks},
                    List.of(Ingredient.of(planks, 4)), false);
        }
        if (concrete == Items.WOODEN_PICKAXE) {
            Item planks = firstMatching(inventory, HeadlessRecipes::isPlanks);
            if (planks == null) {
                Item log = firstMatching(inventory, HeadlessRecipes::isLog);
                planks = log == null ? Items.OAK_PLANKS : planksForLog(log);
            }
            return pickaxe(Items.WOODEN_PICKAXE, planks);
        }
        if (concrete == Items.STONE_PICKAXE) {
            return pickaxe(Items.STONE_PICKAXE, Items.COBBLESTONE);
        }
        if (concrete == Items.IRON_PICKAXE) {
            return pickaxe(Items.IRON_PICKAXE, Items.IRON_INGOT);
        }
        if (concrete == Items.FURNACE) {
            Item c = Items.COBBLESTONE;
            return new Recipe(Items.FURNACE, 1, 3, new Item[]{
                    c, c, c,
                    c, null, c,
                    c, c, c
            }, List.of(Ingredient.of(c, 8)), false);
        }
        if (concrete == Items.CHEST) {
            Item planks = firstMatching(inventory, HeadlessRecipes::isPlanks);
            if (planks == null) {
                Item log = firstMatching(inventory, HeadlessRecipes::isLog);
                planks = log == null ? Items.OAK_PLANKS : planksForLog(log);
            }
            Item p = planks;
            return new Recipe(Items.CHEST, 1, 3, new Item[]{
                    p, p, p,
                    p, null, p,
                    p, p, p
            }, List.of(Ingredient.of(p, 8)), false);
        }
        return null;
    }

    private static Recipe pickaxe(Item result, Item head) {
        Item s = Items.STICK;
        return new Recipe(result, 1, 3, new Item[]{
                head, head, head,
                null, s, null,
                null, s, null
        }, List.of(Ingredient.of(head, 3), Ingredient.of(s, 2)), false);
    }

    /**
     * Craft goals in dependency order (ingredients first). Empty when the inventory already has
     * {@code count} of the concrete result. A running inventory simulation reserves ingredients
     * so a later sibling (sticks after a pickaxe head) cannot spend planks already promised.
     */
    static List<Recipe> plan(Item wanted, int count, Inventory inventory) {
        List<Recipe> steps = new ArrayList<>();
        Map<Item, Integer> sim = snapshot(inventory);
        planInto(wanted, count, inventory, steps, sim, 0);
        return steps;
    }

    private static boolean planInto(Item wanted, int count, Inventory inventory, List<Recipe> steps,
                                    Map<Item, Integer> sim, int depth) {
        if (depth > 8) {
            return false;
        }
        Item concrete = concreteResult(wanted, inventory);
        if (available(sim, wanted, inventory) >= count) {
            return true;
        }
        Recipe recipe = recipeFor(concrete, inventory);
        if (recipe == null) {
            return false;
        }
        while (available(sim, wanted, inventory) < count) {
            for (Ingredient ingredient : recipe.ingredients) {
                int need = ingredient.count;
                if (ingredient.anyLogs) {
                    if (countMatchingSim(sim, HeadlessRecipes::isLog) < need) {
                        return false;
                    }
                    Item log = firstMatching(inventory, HeadlessRecipes::isLog);
                    if (log == null || !debit(sim, log, need)) {
                        return false;
                    }
                    continue;
                }
                Item item = ingredient.anyPlanks
                        ? (firstMatching(inventory, HeadlessRecipes::isPlanks) != null
                            ? firstMatching(inventory, HeadlessRecipes::isPlanks)
                            : concreteResult(Items.OAK_PLANKS, inventory))
                        : ingredient.item;
                if (item == null) {
                    return false;
                }
                if (!planInto(item, need, inventory, steps, sim, depth + 1)) {
                    return false;
                }
                if (!debit(sim, item, need)) {
                    return false;
                }
            }
            sim.merge(recipe.result, recipe.resultCount, Integer::sum);
            steps.add(recipe);
        }
        return true;
    }

    private static Map<Item, Integer> snapshot(Inventory inventory) {
        Map<Item, Integer> sim = new LinkedHashMap<>();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                sim.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return sim;
    }

    private static int available(Map<Item, Integer> sim, Item wanted, Inventory inventory) {
        if (wanted == Items.OAK_PLANKS || isPlanks(wanted)) {
            int n = 0;
            for (Map.Entry<Item, Integer> e : sim.entrySet()) {
                if (isPlanks(e.getKey())) {
                    n += e.getValue();
                }
            }
            return n;
        }
        return sim.getOrDefault(wanted, 0);
    }

    private static int countMatchingSim(Map<Item, Integer> sim, java.util.function.Predicate<Item> pred) {
        int n = 0;
        for (Map.Entry<Item, Integer> e : sim.entrySet()) {
            if (pred.test(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    private static boolean debit(Map<Item, Integer> sim, Item item, int need) {
        if (isPlanks(item)) {
            int left = need;
            for (Map.Entry<Item, Integer> e : sim.entrySet()) {
                if (left <= 0) {
                    break;
                }
                if (!isPlanks(e.getKey()) || e.getValue() <= 0) {
                    continue;
                }
                int take = Math.min(left, e.getValue());
                e.setValue(e.getValue() - take);
                left -= take;
            }
            return left == 0;
        }
        int have = sim.getOrDefault(item, 0);
        if (have < need) {
            return false;
        }
        sim.put(item, have - need);
        return true;
    }

    static int count(Inventory inventory, Item item) {
        int n = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    static int countMatching(Inventory inventory, java.util.function.Predicate<Item> pred) {
        int n = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && pred.test(stack.getItem())) {
                n += stack.getCount();
            }
        }
        return n;
    }

    static Item firstMatching(Inventory inventory, java.util.function.Predicate<Item> pred) {
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && pred.test(stack.getItem())) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static Item logForPlanks(Item planks, Inventory inventory) {
        Item owned = firstMatching(inventory, item -> planks.equals(planksForLog(item)));
        if (owned != null) {
            return owned;
        }
        for (Map.Entry<Item, Item> e : LOG_TO_PLANKS.entrySet()) {
            if (e.getValue() == planks) {
                return e.getKey();
            }
        }
        return null;
    }

    private static int countIncludingPlanned(Item item, Inventory inventory, List<Recipe> steps) {
        int n = count(inventory, item);
        for (Recipe step : steps) {
            if (step.result == item) {
                n += step.resultCount;
            }
            for (Ingredient ingredient : step.ingredients) {
                if (ingredient.item == item) {
                    n -= ingredient.count;
                }
            }
        }
        return n;
    }

    private static int countPlannedPlanks(List<Recipe> steps) {
        int n = 0;
        for (Recipe step : steps) {
            if (isPlanks(step.result)) {
                n += step.resultCount;
            }
            for (Ingredient ingredient : step.ingredients) {
                if (ingredient.item != null && isPlanks(ingredient.item)) {
                    n -= ingredient.count;
                }
            }
        }
        return n;
    }
}
