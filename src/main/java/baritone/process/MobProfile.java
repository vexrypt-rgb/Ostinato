package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;

/**
 * What a mob does to us, read from its vanilla behaviour class rather than a table of names: how it reaches us
 * (touch, shot, blast), how much a hit hurts, and whether it is worth fighting at all.
 */
final class MobProfile {
    enum Kind {
        /** Walks up and hits: zombies, spiders, husks, piglins, silverfish... */
        MELEE,
        /** Shoots from range: skeletons, strays, pillagers, blazes, ghasts, witches. */
        RANGED,
        /** Walks up and blows: creepers. */
        BOMB,
        /** Too strong or too punishing to trade blows with: warden, guardians, ravagers, the bosses. Leave. */
        AVOID
    }

    final Kind kind;
    /** Rough damage per hit, used to rank who to kill first. */
    final double threat;

    private static final MobProfile BOMB = new MobProfile(Kind.BOMB, 12);
    private static final MobProfile AVOID = new MobProfile(Kind.AVOID, 30);
    private static final MobProfile SHOOTER = new MobProfile(Kind.RANGED, 5);
    private static final MobProfile GHAST = new MobProfile(Kind.RANGED, 10);

    private MobProfile(Kind kind, double threat) {
        this.kind = kind;
        this.threat = threat;
    }

    static boolean hostile(LivingEntity e) {
        return e instanceof Enemy && e.isAlive() && !e.isRemoved();
    }

    static MobProfile of(LivingEntity e) {
        if (e instanceof net.minecraft.world.entity.monster.Creeper) return BOMB;
        if (e instanceof net.minecraft.world.entity.monster.warden.Warden
                || e instanceof net.minecraft.world.entity.monster.Guardian
                || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.monster.illager.Evoker
                || e instanceof net.minecraft.world.entity.monster.Ravager) {
            return AVOID;
        }
        if (e instanceof net.minecraft.world.entity.monster.RangedAttackMob
                || e instanceof net.minecraft.world.entity.monster.Ghast
                || e instanceof net.minecraft.world.entity.monster.Blaze
                || e instanceof net.minecraft.world.entity.monster.Shulker) {
            return e instanceof net.minecraft.world.entity.monster.Ghast ? GHAST : SHOOTER;
        }
        double dmg = e.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? e.getAttributeValue(Attributes.ATTACK_DAMAGE) : 3;
        return new MobProfile(Kind.MELEE, dmg);
    }
}
