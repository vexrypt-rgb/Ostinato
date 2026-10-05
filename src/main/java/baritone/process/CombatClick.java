package baritone.process;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;
import static baritone.process.CombatInventory.*;

/**
 * The left click: aim at the swing point, press attack only when the crosshair (or, for a spear, the piercing ray)
 * is on the target, and remember what the click was for the recorder. A click that connects but leaves the target
 * unhurt is probed three ticks later to learn about a shield the client was never shown.
 */
final class CombatClick {
    /** What the process does for us: the smoothed look, and the attack counter the recorder reads. */
    interface Hands {
        void look(Vec3 at);
        void attacked();
    }

    int probeTick, probeSince;
    /** Why the last click did not go out (a: off aim, r: no entity on the crosshair) or that it did (E). */
    char clickKind = '-';

    private final CombatAim aimer;
    private final CombatSwing swing;
    private final CombatTargeting targeting;
    private final CombatShield shield;
    private final Hands hands;
    private final net.minecraft.client.Minecraft mc;

    CombatClick(net.minecraft.client.Minecraft mc, CombatAim aimer, CombatSwing swing, CombatTargeting targeting, CombatShield shield, Hands hands) {
        this.mc = mc;
        this.aimer = aimer;
        this.swing = swing;
        this.targeting = targeting;
        this.shield = shield;
        this.hands = hands;
    }

    /** Left-click only if the crosshair is on the entity, as the mouse button would. */
    boolean hit(Player me, Entity e) {
        if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult er) || er.getEntity() != e) return false;
        press(mc.options.keyAttack);
        return true;
    }

    boolean hit(Player me, LivingEntity target) {
        boolean spearAim = isSpear(me.getMainHandItem());
        Vec3 aim = spearAim ? aimPoint(me, target) : swing.swingPoint(me, target, targeting.velocity(target));
        hands.look(aim);
        double er = exactReach(me, target);
        boolean spear = isSpear(me.getMainHandItem());
        if (spear) {
            // Piercing jab raycasts along the look vector. A click that is merely near the eyes misses
            // and, below full charge, is rejected. Do not press attack unless this ray connects.
            if (!swing.spearRayHits(me, target)) return false;
            press(mc.options.keyAttack);
            hands.attacked();
            return true;
        }
        // 003156 mace medium: twelve dives came down on its head and none clicked. Falling 1.2 a tick past a target
        // a block away the bearing swings 40 degrees a tick, and the look is always one behind it. The crosshair
        // being on the entity is what a click needs; the angle only guards a swing on level ground.
        boolean onIt = me.fallDistance > 1.5 && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult on && on.getEntity() == target;
        if (!onIt && !aimer.aimedAt(me, aim, 10f)) { clickKind = 'a'; return false; } // must be looking at the target
        if (hit(me, target)) {
            hands.attacked();
            clickKind = 'E';
            if (!me.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) {
                probeTick = me.tickCount;
                probeSince = me.tickCount - shield.targetSwingTick;
            }
            return true;
        }
        // 223541 expert adaptive t32: a click with the crosshair beside the hitbox swung at air and spent
        // the full charge. The click is handled this tick against the pick already made, so no entity, no click.
        clickKind = 'r';
        return false;
    }

    /** Three ticks after a click: did it land on a shield the client was never shown? */
    void settle(Player me, LivingEntity target) {
    if (probeTick > 0 && me.tickCount - probeTick >= 3) {
            if (target.hurtTime == 0 && target.getOffhandItem().getItem() == Items.SHIELD && probeSince < 20)
                shield.unseenBlock = Math.max(shield.unseenBlock, probeSince + 1);
            probeTick = 0;
        }
    }
}
