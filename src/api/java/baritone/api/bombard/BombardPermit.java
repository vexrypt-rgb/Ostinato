/*
 * Ostinato: new file (Bombard TNT combat). Not part of upstream Baritone.
 */
package baritone.api.bombard;

/**
 * What the TNT layer of the fight may spend, granted by the driver (TenorClef decides what the bot's resources are
 * for; Ostinato only does what it is allowed). Immutable. {@link #DENY} until the driver says otherwise: with it the
 * fight places, lights, hooks and pushes no TNT at all.
 *
 * @param placeTnt      may place TNT blocks from the inventory
 * @param maxTnt        at most this many TNT a fight
 * @param ignite        may spend flint and steel or fire charges lighting them
 * @param rod           may hook and reel primed TNT with a fishing rod (5 durability a reel)
 * @param spear         may jab primed TNT with a spear
 * @param chain         may light TNT with another TNT's blast
 * @param breakBlocks   may set off TNT where its blast breaks blocks (it always does unless the server forbids it)
 * @param maxSelfDamage health the bot may expect to lose to its own TNT in one blast; 0 allows none
 * @param grantedBy     who granted it, for the fight log
 */
public record BombardPermit(boolean placeTnt, int maxTnt, boolean ignite, boolean rod, boolean spear, boolean chain,
                            boolean breakBlocks, float maxSelfDamage, String grantedBy) {

    public static final BombardPermit DENY = new BombardPermit(false, 0, false, false, false, false, false, 0, "default");

    public enum Primitive { ROD, SPEAR, CHAIN }

    public BombardPermit {
        if (maxTnt < 0 || !Float.isFinite(maxSelfDamage) || maxSelfDamage < 0) throw new IllegalArgumentException("maxTnt " + maxTnt + " maxSelfDamage " + maxSelfDamage);
        grantedBy = grantedBy == null ? "?" : grantedBy;
    }

    /** May the fight use this primitive on a TNT it lit itself? Any primitive needs TNT placed and lit. */
    public boolean allows(Primitive p) {
        if (!placeTnt || maxTnt == 0 || !ignite || !breakBlocks) return false;
        return switch (p) {
            case ROD -> rod;
            case SPEAR -> spear;
            case CHAIN -> chain;
        };
    }

    public boolean any() {
        for (Primitive p : Primitive.values()) if (allows(p)) return true;
        return false;
    }

    @Override
    public String toString() {
        if (!any()) return "deny(" + grantedBy + ")";
        return String.format(java.util.Locale.ROOT, "tnt<=%d%s%s%s self<=%.1f (%s)", maxTnt, rod ? " rod" : "", spear ? " spear" : "",
                chain ? " chain" : "", maxSelfDamage, grantedBy);
    }
}
