package baritone.utils.hunger;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;

import java.util.ArrayList;
import java.util.List;

/** Ostinato code: the food the player carries, read from the FOOD and CONSUMABLE components. */
public final class InventoryFood {
    private InventoryFood() {}

    public static final class Slot {
        public final int slot;
        public final Food food;
        public final int count;

        Slot(int slot, Food food, int count) {
            this.slot = slot;
            this.food = food;
            this.count = count;
        }
    }

    public static Food food(ItemStack st) {
        FoodProperties fp = st.get(DataComponents.FOOD);
        if (fp == null) {
            return null;
        }
        Consumable c = st.get(DataComponents.CONSUMABLE);
        int ticks = c != null ? c.consumeTicks() : HungerModel.EAT_TICKS;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        Food v = VanillaFoods.get(id);
        if (v != null) {
            return new Food(id, fp.nutrition(), fp.saturation(), ticks, fp.canAlwaysEat(), v.regenAmplifier, v.regenTicks, v.absorption, v.risky, v.reserve);
        }
        return new Food(id, fp.nutrition(), fp.saturation(), ticks, fp.canAlwaysEat(), -1, 0, 0, false, false);
    }

    /** Main inventory and hotbar (0..35), plus the offhand as slot 40. */
    public static List<Slot> carried(Player p) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            Food f = st.isEmpty() ? null : food(st);
            if (f != null) {
                out.add(new Slot(i, f, st.getCount()));
            }
        }
        return out;
    }

    public static List<Food> foods(List<Slot> slots) {
        List<Food> out = new ArrayList<>();
        for (Slot s : slots) {
            out.add(s.food);
        }
        return out;
    }

    /** Hunger points carried, golden apples left out (they are for emergencies). */
    public static float points(List<Slot> slots) {
        float sum = 0;
        for (Slot s : slots) {
            if (!s.food.reserve && !s.food.risky) {
                sum += (s.food.nutrition + s.food.saturation) * s.count;
            }
        }
        return sum;
    }

    public static HungerModel model(Player p) {
        HungerModel m = new HungerModel(p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), 0, p.getHealth());
        m.maxHealth = p.getMaxHealth();
        m.difficulty = HungerModel.Difficulty.values()[p.level().getDifficulty().ordinal()];
        return m;
    }
}
