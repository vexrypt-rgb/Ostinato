package baritone.bastion;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tunables for the bastion router. Plain static values (no Minecraft types) so the pure logic can be unit tested; edit with "#bastion set". */
public final class BastionSettings {
    private BastionSettings() {}

    /** Item id -> count at which the run has enough and leaves. fire_resistance counts potions of any kind. */
    public static final Map<String, Integer> TARGETS = new LinkedHashMap<>();
    /** Piglins admiring gold at the same time; each admires ~6 s so more in parallel is the main barter speed-up. */
    public static int maxConcurrentBarters = 4;
    /** Ingots never thrown (kept for a later distraction). */
    public static int keepIngots = 0;
    /** Longest drop the planner may take; the nether has no water to cushion a fall. Halved when hurt. */
    public static int maxSafeDrop = 3;
    /** Health below which drops are capped at 2 and fights avoided where possible. */
    public static int lowHealth = 12;
    /** Leave once the targets are met (otherwise keep bartering until out of gold). */
    public static boolean exitWhenDone = true;
    /** Distance from the bastion centre at which the exit counts as done. */
    public static int exitDistance = 72;
    /** Seconds in the bastion before leaving with whatever we have. */
    public static int timeBudget = 300;
    /** Ticks to wait for piglins to look away before opening a chest anyway. */
    public static int chestWaitTicks = 200;
    /** Most ingots worth fetching for one bastion (gold blocks are finite and slow to mine). */
    public static int goldCap = 64;

    static { resetTargets(); }

    public static void resetTargets() {
        TARGETS.clear();
        TARGETS.put("ender_pearl", 12);
        TARGETS.put("obsidian", 10);
        TARGETS.put("string", 6);
        TARGETS.put("fire_resistance", 1);
    }

    /** Returns null on success, otherwise an error message. */
    public static String set(String key, String value) {
        try {
            switch (key) {
                case "barters": maxConcurrentBarters = Math.max(1, Integer.parseInt(value)); return null;
                case "keepingots": keepIngots = Math.max(0, Integer.parseInt(value)); return null;
                case "maxdrop": maxSafeDrop = Math.max(1, Integer.parseInt(value)); return null;
                case "lowhealth": lowHealth = Integer.parseInt(value); return null;
                case "exit": exitWhenDone = Boolean.parseBoolean(value); return null;
                case "exitdistance": exitDistance = Integer.parseInt(value); return null;
            case "timebudget": timeBudget = Math.max(30, Integer.parseInt(value)); return null;
                case "chestwait": chestWaitTicks = Integer.parseInt(value); return null;
                case "goldcap": goldCap = Math.max(1, Integer.parseInt(value)); return null;
                default:
                    int n = Integer.parseInt(value);
                    if (n <= 0) TARGETS.remove(key); else TARGETS.put(key, n);
                    return null;
            }
        } catch (NumberFormatException e) {
            return "not a number: " + value;
        }
    }

    public static String describe() {
        return "targets=" + TARGETS + " barters=" + maxConcurrentBarters + " keepIngots=" + keepIngots + " maxDrop=" + maxSafeDrop
                + " lowHealth=" + lowHealth + " exit=" + exitWhenDone + " exitDistance=" + exitDistance + " timeBudget=" + timeBudget + " chestWait=" + chestWaitTicks + " goldCap=" + goldCap;
    }
}
