package baritone.process;

import baritone.Baritone;
import baritone.api.bombard.BombardPermit.Primitive;
import baritone.api.physics.*;
import baritone.bombard.*;
import baritone.scherzo.Prediction;
import baritone.scherzo.Scherzo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/** TNT hands run only inside PvpProcess's decision chain, never from an independent input listener. */
final class CombatBombard {
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 point);
    }
    private final Baritone baritone;
    private final Bombard observer;
    private final Scherzo scherzo;
    private final CombatInventory inventory;
    private final Hands hands;
    private final Consumer<String> note;
    private final CombatBombardRod rod;
    private final AimWindow aim = new AimWindow();
    private ImpulseAttempt attempt;
    private int watched = -1, lastTnt = -1;
    private long lastTick = Long.MIN_VALUE, nextEvaluation;
    private Object world, player, foe;

    CombatBombard(Baritone baritone, Bombard observer, Scherzo scherzo, CombatInventory inventory,
                  Hands hands, Consumer<String> note) {
        this.baritone = baritone;
        this.observer = observer;
        this.scherzo = scherzo;
        this.inventory = inventory;
        this.hands = hands;
        this.note = note;
        rod = new CombatBombardRod(baritone, observer, scherzo, inventory, hands, note);
    }

    /** Returns true only when these hands own this tick; every rejected candidate falls back to normal combat. */
    boolean tick(Player me, LivingEntity target) {
        baritone.getPredictivePhysics().warmUp(); // Ostinato: once per JVM (background); later calls are one read
        long now = me.level().getGameTime();
        if (world != me.level() || now < lastTick) { nextEvaluation = now; lastTnt = -1; }
        // Ostinato: a one-tick gap (client tick without a combat pass, or a server time resync) used to drop a hooked rod as
        // "EXTERNAL" mid-reel. The rod tracks its cast by entity and castAt timeout, so it rides out short stalls; spear stays strict.
        if (world != me.level() || player != me || foe != target) cancel(world != me.level() ? "WORLD" : player != me ? "PLAYER" : "TARGET");
        else if (lastTick != now - 1) { if (now - lastTick > 12 || now < lastTick) rod.drop("TICK_GAP"); cancelSpear(); } // Ostinato: 3+ tick client stalls seen live; castAt timeout (12) bounds the rod
        world = me.level(); player = me; foe = target; lastTick = now;
        if (rod.tick(me, target)) return true;
        if (baritone.getGraceNote().holdsControl() || !observer.may(Primitive.SPEAR)) {
            cancelSpear();
            return false;
        }
        if (attempt != null) {
            var e = me.level().getEntity(watched);
            Vec3 v = e == null ? Vec3.ZERO : e.getDeltaMovement();
            var result = attempt.observe(now, e instanceof PrimedTnt && e.isAlive(), v.x, v.z);
            if (result != ImpulseAttempt.Result.WAITING) {
                note.accept("bombard spear entity=" + watched + " effect=" + result + " opponent-outcome=UNKNOWN");
                attempt = null;
            }
            return false;
        }
        if (now < nextEvaluation || !me.onGround() || me.isUsingItem() || me.getDeltaMovement().horizontalDistance() > 0.05
                || baritone.getPlayerContext().minecraft().gui.screen() != null) return false;
        int slot = inventory.spearSlot(me);
        if (slot < 0) return false;
        List<PrimedTnt> nearby = me.level().getEntitiesOfClass(PrimedTnt.class, me.getBoundingBox().inflate(8), e -> e.isAlive());
        // Multiple blasts require a combined risk calculation. This first slice never assumes they are independent.
        if (nearby.size() != 1) return false;
        PrimedTnt tnt = nearby.get(0);
        if (tnt.getId() == lastTnt || tnt.getFuse() < 8 || tnt.getFuse() > 30) return false;
        double distance = me.getEyePosition().distanceTo(tnt.getBoundingBox().getCenter());
        if (distance < 2.5 || distance > 4.2 || !me.hasLineOfSight(tnt)) return false;
        Prediction future = scherzo.predict(tnt.getFuse());
        if (future == null || future.age() != 0 || future.confidence() == Prediction.Confidence.LOW || future.spread() > 1) return false;
        if (!hands.select(me, slot)) return true;
        hands.look(tnt.getBoundingBox().getCenter());
        if (!aim.ready(now, tnt.getId(), me.getYRot(), me.getXRot(), me.getYHeadRot())) return true;
        var stack = me.getMainHandItem();
        if (!CombatInventory.isSpear(stack) || me.cannotAttackWithItem(stack, 0)) return true;
        var hit = me.getAttackRangeWith(stack).getClosesetHit(me, 1.0f, e -> e == tnt);
        if (!(hit instanceof EntityHitResult er) || er.getEntity() != tnt) return true;
        int knockback = 0;
        for (var entry : stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getKey().is(Enchantments.KNOCKBACK)) knockback = entry.getIntValue();
        }
        double[] impulse = TntMechanics.spearPush(me.getYRot(), knockback
                + me.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK));
        nextEvaluation = now + 5; // at most two bounded PPE calls per five fight ticks
        try {
            IPredictivePhysics physics = baritone.getPredictivePhysics();
            BodyState body = physics.capture(tnt);
            BodyState victim = physics.capture(target).toBuilder().pos(future.x(), future.y(), future.z()).build();
            List<BodyState> targets = List.of(physics.capture(me), victim);
            var before = predict(physics, body, targets, me);
            var after = predict(physics, body.toBuilder().vel(body.vx + impulse[0], body.vy + impulse[1], body.vz + impulse[2]).build(), targets, me);
            if (!BlastSafety.usable(before) || !BlastSafety.usable(after)) {
                note.accept("bombard spear rejected=PPE before=" + before + " after=" + after);
                return false;
            }
            if (!BlastSafety.worthwhile(BlastForecast.of(before, now, 2), BlastForecast.of(after, now, 2),
                    me.getHealth() + me.getAbsorptionAmount(), observer.permit().maxSelfDamage())) {
                note.accept("bombard spear rejected=RISK_OR_BENEFIT self=" + BlastForecast.of(after, now, 2).damageTo(0)
                        + " target=" + BlastForecast.of(after, now, 2).damageTo(1)
                        + " blast=" + after.explosion.x + "," + after.explosion.y + "," + after.explosion.z);
                return false;
            }
            CombatAim.press(baritone.getPlayerContext().minecraft().options.keyAttack);
            watched = lastTnt = tnt.getId();
            attempt = new ImpulseAttempt(now, body.vx, body.vz, impulse[0], impulse[2]);
            note.accept("bombard spear entity=" + watched + " command=JAB_SENT knockback=" + knockback + " fuse=" + body.fuse);
            // No CombatOutcome attack count: an attempted TNT push is not a melee hit and must not train ACS as one.
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            note.accept("bombard spear rejected=" + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    static PredictionResult predict(IPredictivePhysics physics, BodyState body, List<BodyState> targets, Player me) {
        return physics.predict(PredictionRequest.trajectory(body, body.fuse).targets(targets)
                .difficulty(me.level().getDifficulty().getId()).priority(PredictionRequest.Priority.COMBAT)
                .fidelity(PredictionRequest.Fidelity.STANDARD).deadlineIn(2_000_000L).consumer("bombard-spear").build());
    }

    void cancel() { cancel("EXTERNAL"); }

    void cancel(String why) { // Ostinato: name the reason a hooked rod was dropped
        rod.drop(why);
        cancelSpear();
    }

    private void cancelSpear() {
        if (attempt != null) {
            note.accept("bombard spear entity=" + watched + " effect=" + attempt.interrupt() + " opponent-outcome=UNKNOWN");
            attempt = null;
        }
        // lastTnt remains protected against repeated commands. Observation continues in Bombard's listener.
    }
}
