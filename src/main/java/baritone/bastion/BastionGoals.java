package baritone.bastion;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure item logic: what is worth taking from chests and barters, and when the run has enough. */
public final class BastionGoals {
    private BastionGoals() {}

    /** Worth taking from a bastion chest (food is decided by the caller from the item's food component). */
    public static final Set<String> CHEST_USEFUL = J8.setOf(
            "gold_ingot", "gold_block", "gold_nugget", "obsidian", "crying_obsidian", "ender_pearl", "string",
            "iron_ingot", "iron_nugget", "iron_block", "arrow", "spectral_arrow", "flint", "flint_and_steel",
            "fire_resistance", "fire_charge", "golden_carrot", "cooked_porkchop", "golden_apple", "enchanted_golden_apple");

    /** Worth walking over after a barter; the rest (quartz, leather, nether bricks, soul speed books...) is skipped. */
    public static final Set<String> BARTER_USEFUL = J8.setOf(
            "ender_pearl", "obsidian", "crying_obsidian", "string", "fire_resistance", "fire_charge", "iron_nugget",
            "gravel", "soul_sand", "spectral_arrow");

    public static boolean chestUseful(String id, boolean food) {
        return CHEST_USEFUL.contains(id) || food && !id.equals("rotten_flesh") && !id.equals("spider_eye") && !id.equals("poisonous_potato");
    }

    public static boolean barterUseful(String id) {
        return BARTER_USEFUL.contains(id);
    }

    /** Target id -> how many are still missing (only ids still short appear). */
    public static Map<String, Integer> missing(Map<String, Integer> have, Map<String, Integer> targets) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Integer> t : targets.entrySet()) {
            int short_ = t.getValue() - have.getOrDefault(t.getKey(), 0);
            if (short_ > 0) out.put(t.getKey(), short_);
        }
        return out;
    }

    public static boolean met(Map<String, Integer> have, Map<String, Integer> targets) {
        return missing(have, targets).isEmpty();
    }

    /**
     * Whether another ingot should be thrown. Stops when the targets are met, when only the reserve is left, and when the piglins
     * already admiring (counted as in flight, each ~1 useful drop at best) would likely cover what is still missing of a single item.
     */
    public static boolean shouldThrow(Map<String, Integer> have, Map<String, Integer> targets, int ingots, int reserve, int inFlight, int maxInFlight) {
        if (ingots <= reserve || inFlight >= maxInFlight) return false;
        Map<String, Integer> miss = missing(have, targets);
        if (miss.isEmpty()) return false;
        // fire resistance alone is a ~2% drop: worth throwing for; but once every other target is met and one throw is out, wait for it
        int total = 0;
        for (int v : miss.values()) total += v;
        return inFlight == 0 || total > inFlight;
    }

    // ---- gold bookkeeping ----

    /** Ingot equivalent of what we carry: a block is nine ingots, nine nuggets one (nuggets need a crafting table, so they are counted, not thrown). */
    public static int ingotEquivalent(int ingots, int nuggets, int blocks) {
        return ingots + 9 * blocks + nuggets / 9;
    }

    /** Ingots usable for bartering right now or after an inventory craft (blocks split in the 2x2 grid; nuggets do not fit it). */
    public static int throwable(int ingots, int blocks) {
        return ingots + 9 * blocks;
    }

    /** Expected count of an item per barter, 1.16.1 table (total weight 423): pearls 20 x 4-8, fire resistance 8 potion + 8 splash. */
    public static final Map<String, Double> PER_THROW = J8.mapOf(
            "ender_pearl", 20 / 423.0 * 6,
            "obsidian", 40 / 423.0,
            "crying_obsidian", 40 / 423.0 * 2,
            "string", 20 / 423.0 * 6,
            "fire_resistance", 16 / 423.0,
            "gravel", 40 / 423.0 * 12,
            "soul_sand", 40 / 423.0 * 5);

    /** Throws expected to cover the slowest missing target (capped: chests and gold are finite). */
    public static int throwsNeeded(Map<String, Integer> missing, int cap) {
        double worst = 0;
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            Double ev = PER_THROW.get(e.getKey());
            if (ev == null || ev <= 0) continue;
            worst = Math.max(worst, e.getValue() / ev);
        }
        return (int) Math.min(cap, Math.ceil(worst));
    }

    /** Whether to go after gold (mine a block, take it from a chest): only when what we carry will not cover the expected throws. */
    public static boolean needGold(Map<String, Integer> missing, int throwableIngots, int reserve, int cap) {
        if (missing.isEmpty()) return false;
        return throwableIngots - reserve < throwsNeeded(missing, cap);
    }
}