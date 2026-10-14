package baritone.utils.hunger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ostinato code: the 26.3 food table, from net.minecraft.world.food.Foods and item.component.Consumables.
 * Eat time is Consumable.consumeSeconds * 20: 1.6 s (32 ticks) for everything but dried kelp (0.8 s, 16 ticks) and
 * honey (a 2 s drink, 40 ticks).
 */
public final class VanillaFoods {
    private VanillaFoods() {}

    public static final Map<String, Food> ALL;

    static {
        Map<String, Food> m = new LinkedHashMap<>();
        p(m, "apple", 4, 0.3F);
        p(m, "baked_potato", 5, 0.6F);
        p(m, "beef", 3, 0.3F);
        p(m, "beetroot", 1, 0.6F);
        p(m, "beetroot_soup", 6, 0.6F);
        p(m, "bread", 5, 0.6F);
        p(m, "carrot", 3, 0.6F);
        risky(m, "chicken", 2, 0.3F);                 // 30% Hunger 30 s
        m.put("chorus_fruit", new Food("chorus_fruit", 4, HungerModel.saturationByModifier(4, 0.3F), 32, true, -1, 0, 0, true, false)); // teleports
        p(m, "cod", 2, 0.1F);
        p(m, "cooked_beef", 8, 0.8F);
        p(m, "cooked_chicken", 6, 0.6F);
        p(m, "cooked_cod", 5, 0.6F);
        p(m, "cooked_mutton", 6, 0.8F);
        p(m, "cooked_porkchop", 8, 0.8F);
        p(m, "cooked_rabbit", 5, 0.6F);
        p(m, "cooked_salmon", 6, 0.8F);
        p(m, "cookie", 2, 0.1F);
        m.put("dried_kelp", new Food("dried_kelp", 1, HungerModel.saturationByModifier(1, 0.3F), 16, false, -1, 0, 0, false, false));
        // Regeneration II 20 s, Resistance, Fire Resistance 5 min, Absorption IV 2 min
        m.put("enchanted_golden_apple", new Food("enchanted_golden_apple", 4, HungerModel.saturationByModifier(4, 1.2F), 32, true, 1, 400, 16, false, true));
        // Regeneration II 5 s, Absorption I 2 min
        m.put("golden_apple", new Food("golden_apple", 4, HungerModel.saturationByModifier(4, 1.2F), 32, true, 1, 100, 4, false, true));
        p(m, "golden_carrot", 6, 1.2F);
        m.put("honey_bottle", new Food("honey_bottle", 6, HungerModel.saturationByModifier(6, 0.1F), 40, true, -1, 0, 0, false, false));
        p(m, "melon_slice", 2, 0.3F);
        p(m, "mushroom_stew", 6, 0.6F);
        p(m, "mutton", 2, 0.3F);
        risky(m, "poisonous_potato", 2, 0.3F);        // 60% Poison 5 s
        p(m, "porkchop", 3, 0.3F);
        p(m, "potato", 1, 0.3F);
        risky(m, "pufferfish", 1, 0.1F);              // Poison II, Hunger III, Nausea
        p(m, "pumpkin_pie", 8, 0.3F);
        p(m, "rabbit", 3, 0.3F);
        p(m, "rabbit_stew", 10, 0.6F);
        risky(m, "rotten_flesh", 4, 0.1F);            // 80% Hunger 30 s
        p(m, "salmon", 2, 0.1F);
        risky(m, "spider_eye", 2, 0.8F);              // Poison 5 s
        m.put("suspicious_stew", new Food("suspicious_stew", 6, HungerModel.saturationByModifier(6, 0.6F), 32, true, -1, 0, 0, true, false));
        p(m, "sweet_berries", 2, 0.1F);
        p(m, "glow_berries", 2, 0.1F);
        p(m, "tropical_fish", 1, 0.1F);
        ALL = Collections.unmodifiableMap(m);
    }

    private static void p(Map<String, Food> m, String id, int n, float mod) {
        m.put(id, Food.plain(id, n, mod));
    }

    private static void risky(Map<String, Food> m, String id, int n, float mod) {
        m.put(id, new Food(id, n, HungerModel.saturationByModifier(n, mod), 32, false, -1, 0, 0, true, false));
    }

    public static Food get(String id) {
        return ALL.get(id);
    }
}
