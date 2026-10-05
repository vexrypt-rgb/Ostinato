package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import static baritone.process.CombatAim.press;

/**
 * The wind charge as a defensive and disruptive tool: thrown onto an incoming projectile's path, onto an airborne
 * foe's predicted path, or at the feet of a foe behind its shield or eating. Each returns null when it isn't the moment.
 */
final class CombatWind {
    /** What the process does for us: hotbar and the tick's decision label. */
    interface Hands {
        boolean select(Player me, int slot);
        PathingCommand decide(String d);
    }

    private final IPlayerContext ctx;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatPhase phase;
    private final Hands hands;

    CombatWind(IPlayerContext ctx, CombatAim aimer, CombatTargeting targeting, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.aimer = aimer;
        this.targeting = targeting;
        this.phase = phase;
        this.hands = hands;
    }

    /** Knock an arrow, trident, fireball or potion off its line to us. */
    PathingCommand deflect(Player me, LivingEntity target, int wind) {
        // arrows, tridents, fireballs, potions: a wind charge on the projectile's path deflects it
        // a mace carrier above us is the lethal one: the knock from its wind charge is not, and the shield needs the ticks
        boolean diverAbove = target.getMainHandItem().getItem() == Items.MACE && !target.onGround() && target.getY() > me.getY() + 2
                && me.getOffhandItem().getItem() == Items.SHIELD;
        if (wind >= 0 && phase.macePhase == 0 && phase.windCool == 0 && !diverAbove) {
            for (net.minecraft.world.entity.projectile.Projectile pr : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    me.getBoundingBox().inflate(14), e -> e.getOwner() != me && !e.onGround() && e.getDeltaMovement().lengthSqr() > 0.09)) {
                Vec3 v = pr.getDeltaMovement(), rel = me.getEyePosition().subtract(pr.position());
                double d = rel.length();
                if (d < 3.5 || d > 13 || v.dot(rel) <= 0 || v.normalize().dot(rel.normalize()) < 0.85) continue;
                Vec3 at = pr.position().add(v.scale(d / (v.length() + 1.5)));
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
                if (!hands.select(me, wind)) return hands.decide("swap");
                if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("deflect");
                press(ctx.minecraft().options.keyUse);
                phase.windCool = 8;
                return hands.decide("deflect");
            }
        }
        return null;
    }

    /** Knock an airborne foe off its dive; a shield kit only does so against a high, far one. */
    PathingCommand diver(Player me, LivingEntity target, double dist, int wind, boolean hasShield) {
        // an airborne opponent diving at us: a wind charge on its predicted path knocks it off the smash
        // Only a real dive. Every ordinary jump matched the old test, which was 347 of 1800 ticks.
        // 1726 bench: 235 ticks of this and the 9.1 smashes landed anyway. A shield stops a smash, so a kit
        // with one blocks below and keeps the mace in hand and charged.
        // a shield kit still gets one charge at a diver that is high and far (the shield needs its five ticks only once the diver is close)
        boolean farDiver = hasShield && dist > 5 && target.getY() > me.getY() + 4 && targeting.velocity(target).y < 0.1;
        if (wind >= 0 && (!hasShield || farDiver) && phase.macePhase == 0 && phase.windCool == 0 && !target.onGround() && dist < 12 && dist > 2
                && target.getY() > me.getY() + 2) {
            Vec3 at = target.getBoundingBox().getCenter().add(targeting.velocity(target).scale(dist / 1.5));
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            if (!hands.select(me, wind)) return hands.decide("swap");
            if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("wind");
            press(ctx.minecraft().options.keyUse);
            phase.windCool = 12;
            return hands.decide("wind");
        }
        return null;
    }

    /** Throw the foe off the spot at its feet while it stands behind its shield or eats. */
    PathingCommand feet(Player me, LivingEntity target, double dist, boolean los, int wind) {
        // A wind charge at the feet of an opponent standing behind its shield or eating throws it off the
        // spot and into the air, where the hop that follows finds it.
        if (phase.feetCool > 0) phase.feetCool--;
        if (wind >= 0 && phase.macePhase == 0 && phase.pearlStage == 0 && phase.feetCool == 0 && phase.windCool == 0 && me.onGround() && target.onGround() && los
                && dist > 2.5 && dist < 10 && (target.isBlocking() || target.isUsingItem())) {
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.position().add(0, 0.1, 0), ctx.playerRotations());
            if (!hands.select(me, wind)) return hands.decide("swap");
            if (!aimer.face(r.getYaw(), r.getPitch(), 3f) && ++phase.feetTicks < 15) return hands.decide("windfeet");
            if (phase.feetTicks < 15) press(ctx.minecraft().options.keyUse);
            phase.feetTicks = 0;
            phase.feetCool = 80;
            phase.windCool = 8;
            return hands.decide("windfeet");
        }
        return null;
    }
}
