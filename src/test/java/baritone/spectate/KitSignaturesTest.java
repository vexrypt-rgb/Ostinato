/*
 * Ostinato: new file (spectator kit detection tests). Not part of upstream Baritone.
 */
package baritone.spectate;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the kit table reads, and what each kit looks like from the outside is labelled as that kit. */
public class KitSignaturesTest {
    private static final KitSignatures K = KitSignatures.get();

    private static Map<String, Double> armor(String material) {
        Map<String, Double> m = new HashMap<>();
        m.put("armor:" + material, 1.0);
        return m;
    }

    private static Map<String, Double> with(Map<String, Double> base, Object... kv) {
        Map<String, Double> m = new HashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    /** {@code share} of 400 ticks shown each gear map in turn, then the events. */
    private static KitSignatures.Result watch(Object[][] gear, String... events) {
        KitEvidence ev = new KitEvidence();
        int t = 0;
        for (Object[] g : gear) {
            @SuppressWarnings("unchecked") Map<String, Double> m = (Map<String, Double>) g[1];
            ev.gear(t, m);
            t += (int) Math.round(((Number) g[0]).doubleValue() * 400);
        }
        for (String e : events) ev.event(t, e);
        return K.classify(ev, t);
    }

    private static void is(String kit, KitSignatures.Result r) {
        assertEquals(r.toString(), kit, r.label);
        assertTrue(r.toString(), r.confidence > 0.5);
    }

    @Test
    public void tableReads() {
        assertTrue(K.kits().size() >= 9);
        for (String k : new String[]{"sword", "axe+shield", "mace", "crystal", "pot", "netherite-pot", "smp", "uhc", "ranged"}) {
            boolean found = false;
            for (KitSignatures.Kit x : K.kits()) found |= x.label.equals(k);
            assertTrue(k, found);
        }
    }

    @Test
    public void globs() {
        assertTrue(KitSignatures.glob("*_sword", "diamond_sword"));
        assertTrue(KitSignatures.glob("armor:*", "armor:iron"));
        assertTrue(KitSignatures.glob("a*b*c", "aXbYc"));
        assertTrue(!KitSignatures.glob("*_sword", "diamond_axe"));
    }

    @Test
    public void eachKitLooksLikeItself() {
        Map<String, Double> dia = armor("diamond"), neth = armor("netherite");
        is("sword", watch(new Object[][]{{1, with(dia, "diamond_sword", 1)}}));
        is("axe+shield", watch(new Object[][]{{0.8, with(dia, "diamond_axe", 1, "shield", 1)}, {0.2, with(dia, "diamond_sword", 1, "shield", 1)}}));
        is("mace", watch(new Object[][]{{0.5, with(neth, "mace", 1, "shield", 1)}, {0.5, with(neth, "netherite_sword", 1, "shield", 1)}}, "threw:wind_charge"));
        is("crystal", watch(new Object[][]{{0.4, with(neth, "end_crystal", 1, "totem_of_undying", 1)}, {0.3, with(neth, "obsidian", 1, "totem_of_undying", 1)},
                {0.3, with(neth, "netherite_sword", 1, "totem_of_undying", 1)}}, "crystal", "crystal", "pop:totem"));
        is("pot", watch(new Object[][]{{0.9, with(dia, "diamond_sword", 1, "effect:speed", 1)}, {0.1, with(dia, "splash_potion", 1)}},
                "threw:splash_heal", "threw:splash_heal", "threw:splash_heal"));
        is("netherite-pot", watch(new Object[][]{{0.9, with(neth, "netherite_sword", 1, "effect:strength", 1)}, {0.1, with(neth, "splash_potion", 1)}},
                "threw:splash_heal", "threw:splash_heal"));
        is("smp", watch(new Object[][]{{0.8, with(neth, "netherite_sword", 1, "shield", 1)}, {0.1, with(neth, "golden_apple", 1, "shield", 1)},
                {0.1, with(neth, "ender_pearl", 1, "totem_of_undying", 1)}}, "ate:golden_apple", "threw:ender_pearl", "pop:totem"));
        is("uhc", watch(new Object[][]{{0.7, with(dia, "diamond_sword", 1)}, {0.1, with(dia, "lava_bucket", 1)}, {0.1, with(dia, "bow", 1)},
                {0.1, with(dia, "golden_apple", 1)}}, "ate:golden_apple", "ate:golden_apple", "shot:arrow"));
        is("ranged", watch(new Object[][]{{0.7, with(dia, "bow", 1)}, {0.3, with(dia, "diamond_sword", 1)}}, "shot:arrow", "shot:arrow"));
    }

    @Test
    public void littleSeenIsUnknownOrUnsure() {
        KitSignatures.Result r = watch(new Object[][]{{1, with(armor("leather"), "wooden_shovel", 1)}});
        assertEquals(KitSignatures.UNKNOWN, r.label);
        KitEvidence ev = new KitEvidence();
        ev.gear(0, with(armor("diamond"), "diamond_sword", 1));
        KitSignatures.Result brief = K.classify(ev, 10);
        assertTrue(brief.toString(), brief.confidence < 0.2);
    }

    @Test
    public void newKitIsOneLine() {
        KitSignatures k = KitSignatures.parse("spear spear=5 armor:iron=1\nsword *_sword=3\n");
        Map<String, Double> f = with(armor("iron"), "spear", 1.0);
        assertEquals("spear", k.classify(f, 200).label);
    }

    /** Ostinato: the kit bench's misses (v1.24.0): pot as sword, uhc as axe+shield, smp as netherite-pot. */
    @Test
    public void benchMisses() {
        Map<String, Double> dia = armor("diamond"), neth = armor("netherite");
        // pot: diamond and a sword almost all the time, a splash potion in hand now and then, two heals thrown
        is("pot", watch(new Object[][]{{0.95, with(dia, "diamond_sword", 1)}, {0.05, with(dia, "splash_potion", 1)}},
                "threw:splash_heal", "threw:splash_heal"));
        // uhc: diamond with iron leggings, sword mostly, the axe a fifth of the time, a shield always in the offhand
        Map<String, Double> uhcArmor = with(new HashMap<>(), "armor:diamond", 0.75, "armor:iron", 0.25, "armor:mixed", 1);
        is("uhc", watch(new Object[][]{{0.7, with(uhcArmor, "diamond_sword", 1, "shield", 1)}, {0.2, with(uhcArmor, "diamond_axe", 1, "shield", 1)},
                {0.1, with(uhcArmor, "golden_apple", 1, "shield", 1)}}, "ate:golden_apple"));
        // the axe kit itself stays axe+shield
        is("axe+shield", watch(new Object[][]{{0.8, with(dia, "diamond_axe", 1, "shield", 1)}, {0.2, with(dia, "golden_apple", 1, "shield", 1)}}, "ate:golden_apple"));
        // smp: netherite, sword and shield, and it throws heals too
        is("smp", watch(new Object[][]{{0.9, with(neth, "netherite_sword", 1, "shield", 1)}, {0.1, with(neth, "splash_potion", 1, "shield", 1)}},
                "threw:splash_heal", "threw:splash_heal", "ate:golden_apple"));
        // smp with a totem in the offhand instead of a shield
        is("smp", watch(new Object[][]{{0.9, with(neth, "netherite_sword", 1, "totem_of_undying", 1)}, {0.1, with(neth, "splash_potion", 1, "totem_of_undying", 1)}},
                "threw:splash_heal", "threw:splash_heal"));
    }
}
