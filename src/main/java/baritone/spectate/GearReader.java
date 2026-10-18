/*
 * Ostinato: new file (spectator kit detection). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.api.BaritoneAPI;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.scores.PlayerTeam;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: turns what the client can see of a player (the six equipment slots, armor material, glint, trims and
 * dye, the potion particles over them, their scoreboard team and name colour) into {@link KitEvidence} tokens and a
 * {@link FightGroup.Clue}. Game thread; a handful of map writes a call, so it can run every few ticks per player.
 */
public final class GearReader {
    private GearReader() {}

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] MATERIALS = {"leather", "chainmail", "iron", "golden", "diamond", "netherite", "turtle", "copper"};
    private static final Map<Item, String> PATHS = new IdentityHashMap<>();
    private static final Map<Integer, String> EFFECT_BY_COLOR = new HashMap<>();
    private static EntityDataAccessor<List<ParticleOptions>> particles;
    private static boolean particlesTried;

    static String path(Item item) {
        return PATHS.computeIfAbsent(item, i -> BuiltInRegistries.ITEM.getKey(i).getPath());
    }

    /** The armor material of an armor item's id ({@code diamond_chestplate} gives diamond), or null. */
    static String material(String path) {
        for (String m : MATERIALS) if (path.startsWith(m + "_")) return m;
        return null;
    }

    /**
     * A cheap fingerprint of what {@link #tokens} reads: the six stacks' items, counts aside, and the particle list
     * (the synced list is replaced, not changed, when effects change). Equal fingerprints mean equal tokens nearly
     * always; the caller still re-reads now and then.
     */
    public static long fingerprint(Player p) {
        long h = 17;
        h = h * 31 + System.identityHashCode(p.getMainHandItem().getItem());
        h = h * 31 + System.identityHashCode(p.getOffhandItem().getItem());
        for (EquipmentSlot s : ARMOR) h = h * 31 + System.identityHashCode(p.getItemBySlot(s).getItem());
        EntityDataAccessor<List<ParticleOptions>> acc = particleAccessor();
        if (acc != null) {
            try {
                h = h * 31 + System.identityHashCode(p.getEntityData().get(acc));
            } catch (RuntimeException ignored) {}
        }
        return h;
    }

    /** Everything the player wears and holds, as weighted tokens. */
    public static Map<String, Double> tokens(Player p) {
        Map<String, Double> t = new HashMap<>();
        hand(t, p.getMainHandItem(), true);
        hand(t, p.getOffhandItem(), false);
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = p.getItemBySlot(s);
            if (st.isEmpty()) continue;
            String path = path(st.getItem());
            if (st.is(Items.ELYTRA)) t.merge("elytra", 1.0, Double::sum);
            String m = material(path);
            if (m != null) t.merge("armor:" + m, 0.25, Double::sum);
            if (st.hasFoil()) t.merge("glint:armor", 0.25, Double::sum);
            if (st.has(DataComponents.TRIM)) t.merge("trim", 0.25, Double::sum);
            if (st.has(DataComponents.DYED_COLOR)) t.merge("dyed", 0.25, Double::sum);
        }
        effects(t, p);
        return t;
    }

    private static void hand(Map<String, Double> t, ItemStack st, boolean main) {
        if (st.isEmpty()) return;
        t.put(path(st.getItem()), 1.0);
        if (main && st.hasFoil()) t.put("glint:weapon", 1.0);
    }

    /** Potion effects shown by their particles: the particle colour is the effect's own (vanilla effects only). */
    private static void effects(Map<String, Double> t, LivingEntity e) {
        EntityDataAccessor<List<ParticleOptions>> acc = particleAccessor();
        if (acc == null) return;
        List<ParticleOptions> list;
        try {
            list = e.getEntityData().get(acc);
        } catch (RuntimeException ex) {
            return;
        }
        if (list.isEmpty()) return;
        if (EFFECT_BY_COLOR.isEmpty()) {
            for (MobEffect m : BuiltInRegistries.MOB_EFFECT) {
                var key = BuiltInRegistries.MOB_EFFECT.getKey(m);
                if (key != null) EFFECT_BY_COLOR.putIfAbsent(m.getColor() & 0xFFFFFF, key.getPath());
            }
        }
        for (ParticleOptions o : list) {
            if (!(o instanceof ColorParticleOption c)) continue;
            int rgb = ((int) (c.getRed() * 255 + 0.5) << 16) | ((int) (c.getGreen() * 255 + 0.5) << 8) | (int) (c.getBlue() * 255 + 0.5);
            String id = EFFECT_BY_COLOR.get(rgb);
            if (id != null) t.put("effect:" + id, 1.0);
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<List<ParticleOptions>> particleAccessor() {
        if (particlesTried) return particles;
        particlesTried = true;
        try {
            Field f = LivingEntity.class.getDeclaredField("DATA_EFFECT_PARTICLES");
            f.setAccessible(true);
            particles = (EntityDataAccessor<List<ParticleOptions>>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.out.println("Ostinato spectate: no effect particles (" + ex + ")");
        }
        return particles;
    }

    /** What the client shows of the player's team. */
    public static FightGroup.Clue clue(Player p) {
        PlayerTeam team = p.getTeam();
        String name = team == null ? "" : team.getName();
        int teamColor = team == null ? -1 : team.getColor().map(Enum::ordinal).orElse(-1);
        int nameColor = color(p.getDisplayName());
        int dye = -1;
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = p.getItemBySlot(s);
            var d = st.get(DataComponents.DYED_COLOR);
            if (d != null) {
                dye = d.rgb() & 0xFFFFFF;
                break;
            }
        }
        return new FightGroup.Clue(name, teamColor, nameColor, dye, null);
    }

    /** The first colour set in a name (its own or a prefix's), or -1; white counts as none. */
    static int color(Component c) {
        TextColor tc = c.getStyle().getColor();
        if (tc != null && (tc.getValue() & 0xFFFFFF) != 0xFFFFFF) return tc.getValue() & 0xFFFFFF;
        for (Component s : c.getSiblings()) {
            int v = color(s);
            if (v >= 0) return v;
        }
        return -1;
    }

    /** What a thrown splash potion says of the kit: heal, harm or other. */
    public static String splash(ItemStack st) {
        PotionContents pc = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        boolean heal = false, harm = false;
        for (var mi : pc.getAllEffects()) {
            heal |= mi.getEffect().is(MobEffects.INSTANT_HEALTH) || mi.getEffect().is(MobEffects.REGENERATION);
            harm |= mi.getEffect().is(MobEffects.INSTANT_DAMAGE) || mi.getEffect().is(MobEffects.POISON);
        }
        return heal ? "threw:splash_heal" : harm ? "threw:splash_harm" : "threw:splash_other";
    }

    /**
     * Our own kit, read from the whole inventory the way a spectator would see it fought: every hotbar and offhand
     * item counts as held, and what we carry to drink, throw or eat counts as if used.
     */
    public static KitEvidence own(Player p) {
        Map<String, Double> t = tokens(p);
        KitEvidence ev = new KitEvidence();
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            t.put(path(st.getItem()), 1.0);
        }
        ev.gear(0, t);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String ev0 = null;
            if (st.is(Items.SPLASH_POTION)) ev0 = splash(st);
            else if (st.is(Items.GOLDEN_APPLE)) ev0 = "ate:golden_apple";
            else if (st.is(Items.ENCHANTED_GOLDEN_APPLE)) ev0 = "ate:enchanted_golden_apple";
            else if (st.is(Items.ENDER_PEARL)) ev0 = "threw:ender_pearl";
            else if (st.is(Items.TOTEM_OF_UNDYING)) ev0 = "pop:totem";
            else if (st.is(Items.WIND_CHARGE)) ev0 = "threw:wind_charge";
            else if (st.is(Items.END_CRYSTAL)) ev0 = "crystal";
            else if (st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW)) ev0 = "shot:arrow";
            if (ev0 != null && ev.events(ev0) < 2) {
                ev.event(0, ev0);
                ev.event(0, ev0);
            }
        }
        return ev;
    }

    /** Our own kit's label (null when unsure), for the priors to match. */
    public static String ownLabel(Player p) {
        Map<String, Double> f = own(p).features(1000);
        KitSignatures.Result r = KitSignatures.get().classify(f, 1000);
        if (r.label.equals(KitSignatures.UNKNOWN) && BaritoneAPI.getSettings().spectateLearnKits.value) {
            Object[] m = LearnedKits.get().match(f); // a kit learned from watching others
            if (m != null) return ((LearnedKits.Kit) m[0]).label;
        }
        return r.confidence >= FightBook.KIT_SURE ? r.label : null;
    }
}
