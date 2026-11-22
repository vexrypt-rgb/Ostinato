package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1. How a bot turns its main inventory into {@link BotFeed.Inventory} (the owner's tile
 * choice): free slots, food items, the {@link BotFeed#MAX_ITEMS} biggest totals by item, and the lowest durability
 * percent among damageable items. Pure: the bot passes one {@link Slot} per main-inventory slot.
 */
public final class InventorySummary {

    /**
     * One slot. {@code id} without the {@code minecraft:} namespace; empty slot = null id or count 0.
     * {@code maxDamage} 0 = not damageable.
     */
    public record Slot(String id, int count, boolean food, int damage, int maxDamage) {}

    private InventorySummary() {}

    public static BotFeed.Inventory of(List<Slot> slots) {
        int free = 0, food = 0, lowest = -1;
        String lowestId = "";
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Slot s : slots) {
            if (s == null || s.id() == null || s.count() <= 0) {
                free++;
                continue;
            }
            String id = s.id().startsWith("minecraft:") ? s.id().substring(10) : s.id();
            totals.merge(id, s.count(), Integer::sum);
            if (s.food()) {
                food += s.count();
            }
            if (s.maxDamage() > 0) {
                int pct = (int) Math.floor(100.0 * Math.max(0, s.maxDamage() - s.damage()) / s.maxDamage());
                if (lowest < 0 || pct < lowest) {
                    lowest = pct;
                    lowestId = id;
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<BotFeed.Item> top = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < BotFeed.MAX_ITEMS; i++) {
            top.add(new BotFeed.Item(sorted.get(i).getKey(), sorted.get(i).getValue()));
        }
        return new BotFeed.Inventory(free, slots.size(), food, top, lowest, lowestId);
    }
}
