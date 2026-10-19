/*
 * Ostinato: new file (freecam ghost sparring). Not part of upstream Baritone.
 */
package baritone.behavior;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: the spar kits, the same names as the spectator kit table (sword, axe+shield, mace, crystal, pot, uhc,
 * smp, netherite-pot). Both sides of a spar get the same kit. Pure numbers (item ids are only for the ghost's model):
 * armour points, toughness, Protection levels summed over the pieces, knockback resistance, weapons and consumables.
 */
public final class SparKit {
    /** One weapon: attack damage attribute (1 + the item's modifier), attacks a second, and its enchantments. */
    public static final class Weapon {
        public final String item;
        public final float damage, speed;
        public final int sharpness, density, breach;
        public final boolean sword, axe, mace;

        Weapon(String item, float damage, float speed, int sharpness, int density, int breach) {
            this.item = item;
            this.damage = damage;
            this.speed = speed;
            this.sharpness = sharpness;
            this.density = density;
            this.breach = breach;
            this.sword = item.endsWith("_sword");
            this.axe = item.endsWith("_axe");
            this.mace = item.equals("mace");
        }
    }

    // vanilla 26.3 item attributes: damage includes the player's base 1
    static Weapon diamondSword(int sharp) { return new Weapon("diamond_sword", 7, 1.6f, sharp, 0, 0); }
    static Weapon netheriteSword(int sharp) { return new Weapon("netherite_sword", 8, 1.6f, sharp, 0, 0); }
    static Weapon diamondAxe() { return new Weapon("diamond_axe", 9, 1.0f, 0, 0, 0); }
    static Weapon netheriteAxe() { return new Weapon("netherite_axe", 10, 1.0f, 0, 0, 0); }
    static Weapon mace(int density, int breach) { return new Weapon("mace", 6, 0.6f, 0, density, breach); }

    public final String name;
    public final String armorMaterial; // diamond, netherite, mixed (uhc)
    public final float armor, toughness, knockbackResist;
    public final int protection; // Protection levels over the four pieces (enchantment protection points)
    public final List<Weapon> weapons; // first is the main one
    public final boolean shield;
    public final int gapples, egapples, totems, heals, speedPots;
    /** Simple crystal play (the ghost only): a blast at the foe every so often in reach. */
    public final int crystals;

    SparKit(String name, String mat, float armor, float tough, float kb, int prot, List<Weapon> weapons, boolean shield,
            int gapples, int egapples, int totems, int heals, int speedPots, int crystals) {
        this.name = name;
        this.armorMaterial = mat;
        this.armor = armor;
        this.toughness = tough;
        this.knockbackResist = kb;
        this.protection = prot;
        this.weapons = weapons;
        this.shield = shield;
        this.gapples = gapples;
        this.egapples = egapples;
        this.totems = totems;
        this.heals = heals;
        this.speedPots = speedPots;
        this.crystals = crystals;
    }

    public static final String[] NAMES = {"sword", "axe+shield", "mace", "crystal", "pot", "uhc", "smp", "netherite-pot"};

    /** Diamond armour: 20 points, 8 toughness. Netherite: 20, 12, and 0.1 knockback resistance a piece. */
    public static SparKit of(String name) {
        String n = name == null ? "sword" : name.toLowerCase(Locale.ROOT);
        if (n.equals("axe") || n.equals("shield")) n = "axe+shield";
        if (n.equals("nethpot") || n.equals("neth-pot")) n = "netherite-pot";
        return switch (n) {
            case "sword" -> new SparKit("sword", "diamond", 20, 8, 0, 8, Arrays.asList(diamondSword(0)), false, 0, 0, 0, 0, 0, 0);
            case "axe+shield" -> new SparKit("axe+shield", "diamond", 20, 8, 0, 16, Arrays.asList(diamondAxe(), diamondSword(0)), true, 6, 0, 0, 0, 0, 0);
            case "mace" -> new SparKit("mace", "netherite", 20, 12, 0.4f, 16, Arrays.asList(mace(4, 0), netheriteSword(5)), false, 8, 0, 2, 0, 0, 0);
            case "crystal" -> new SparKit("crystal", "netherite", 20, 12, 0.4f, 16, Arrays.asList(netheriteSword(5)), false, 16, 0, 4, 0, 0, 16);
            case "pot" -> new SparKit("pot", "diamond", 20, 8, 0, 16, Arrays.asList(diamondSword(3)), false, 0, 0, 0, 6, 2, 0);
            case "uhc" -> new SparKit("uhc", "mixed", 19, 6, 0, 0, Arrays.asList(diamondSword(0), diamondAxe()), true, 6, 0, 0, 0, 0, 0);
            case "smp" -> new SparKit("smp", "netherite", 20, 12, 0.4f, 16, Arrays.asList(netheriteSword(5), netheriteAxe()), true, 16, 0, 3, 0, 0, 0);
            case "netherite-pot" -> new SparKit("netherite-pot", "netherite", 20, 12, 0.4f, 16, Arrays.asList(netheriteSword(5)), false, 0, 0, 0, 6, 2, 0);
            default -> null;
        };
    }
}
