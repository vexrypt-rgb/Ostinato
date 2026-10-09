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

    private MobProfile(Kind kind, double threat) {
        this.kind = kind;
        this.threat = threat;
    }

    static boolean hostile(LivingEntity e) {
        return e instanceof Enemy && e.isAlive() && !e.isRemoved();
    }

    static MobProfile of(LivingEntity e) {
        if (e instanceof net.minecraft.world.entity.monster.Creeper) return new MobProfile(Kind.BOMB, 12);
        if (e instanceof net.minecraft.world.entity.monster.warden.Warden
                || e instanceof net.minecraft.world.entity.monster.Guardian
                || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.monster.Evoker
                || e instanceof net.minecraft.world.entity.monster.Ravager) {
            return new MobProfile(Kind.AVOID, 30);
        }
        if (e instanceof net.minecraft.world.entity.monster.RangedAttackMob
                || e instanceof net.minecraft.world.entity.monster.Ghast
                || e instanceof net.minecraft.world.entity.monster.Blaze
                || e instanceof net.minecraft.world.entity.monster.Shulker) {
            return new MobProfile(Kind.RANGED, e instanceof net.minecraft.world.entity.monster.Ghast ? 10 : 5);
        }
        double dmg = e.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? e.getAttributeValue(Attributes.ATTACK_DAMAGE) : 3;
        return new MobProfile(Kind.MELEE, dmg);
    }
}
