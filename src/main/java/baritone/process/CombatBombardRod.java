package baritone.process;

import baritone.Baritone;
import baritone.api.bombard.BombardPermit.Primitive;
import baritone.api.physics.*;
import baritone.bombard.*;
import baritone.scherzo.Prediction;
import baritone.scherzo.Scherzo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.Consumer;

/** Rod slice: choose existing TNT, cast, require synced attachment, re-evaluate, reel, observe. */
final class CombatBombardRod {
    private final Baritone baritone;
    private final Bombard observer;
    private final Scherzo scherzo;
    private final CombatInventory inventory;
    private final CombatBombard.Hands hands;
    private final Consumer<String> note;
    private long lastTick = Long.MIN_VALUE, castAt, nextTry;
    private int selected = -1, attempted = -1;
    private Object world, player, foe;
    private ImpulseAttempt effect;
    private int aimed = -1, aimTicks, retries;
    // Ostinato: PPE path and index of the expected reel tick for the cast-time worthwhile check (null when reeling)
    private float[] castPath;
    private int castIndex = -1;
    private final java.util.Set<String> told = new java.util.HashSet<>();

    CombatBombardRod(Baritone b, Bombard observer, Scherzo scherzo, CombatInventory inventory,
                     CombatBombard.Hands hands, Consumer<String> note) {
        this.baritone = b; this.observer = observer; this.scherzo = scherzo;
        this.inventory = inventory; this.hands = hands; this.note = note;
    }

    boolean tick(Player me, LivingEntity target) {
        try { return run(me, target); }
        catch (IllegalArgumentException | IllegalStateException unavailable) {
            drop("UNAVAILABLE_" + unavailable.getClass().getSimpleName());
            nextTry = me.level().getGameTime() + 5;
            return false;
        }
    }

    private boolean run(Player me, LivingEntity target) {
        long now = me.level().getGameTime();
        if (world != me.level() || now < lastTick) { nextTry = now; attempted = -1; }
        // Ostinato: a cast in flight is tracked by entity + castAt timeout (12), so short client stalls must not drop it.
        boolean gap = now < lastTick || now - lastTick > (selected >= 0 ? 12 : 1);
        if (world != me.level() || player != me || foe != target || gap)
            drop(gap ? "TICK_GAP" : "CONTEXT_CHANGED");
        world = me.level(); player = me; foe = target; lastTick = now;
        if (!observer.may(Primitive.ROD)) { drop("NOT_PERMITTED"); return false; }
        if (baritone.getGraceNote().holdsControl()) { drop("GRACE_NOTE"); return false; }
        if (effect != null) {
            var e = me.level().getEntity(attempted);
            Vec3 v = e == null ? Vec3.ZERO : e.getDeltaMovement();
            var result = effect.observe(now, e instanceof PrimedTnt && e.isAlive(), v.x, v.z);
            if (result != ImpulseAttempt.Result.WAITING) {
                note.accept("bombard rod entity=" + attempted + " effect=" + result + " opponent-outcome=UNKNOWN");
                effect = null;
            }
            return false;
        }
        if (!me.onGround()) { drop("AIRBORNE"); return false; }
        if (me.isUsingItem()) { drop("USING_ITEM"); return false; }
        if (me.getDeltaMovement().horizontalDistance() > 0.05) { drop("MOVING"); return false; }
        if (baritone.getPlayerContext().minecraft().gui.screen() != null) { drop("SCREEN"); return false; }
        int slot = inventory.slotOf(me, Items.FISHING_ROD);
        if (slot < 0) { drop("NO_ROD"); return false; }
        var rod = me.getInventory().getItem(slot);
        if (rod.getMaxDamage() - rod.getDamageValue() <= TntMechanics.ROD_PULL_DURABILITY) { drop("ROD_WORN"); return false; }
        PrimedTnt tnt;
        if (selected < 0) {
            if (now < nextTry || me.fishing != null) return false;
            var nearby = me.level().getEntitiesOfClass(PrimedTnt.class, me.getBoundingBox().inflate(8), e -> e.isAlive());
            if (nearby.size() != 1) return false;
            tnt = nearby.get(0);
            if (tnt.getId() == attempted) return false;
            if (tnt.getFuse() < 20 || tnt.getFuse() > 35) return reject(tnt, "FUSE");
            if (me.distanceTo(tnt) > 6) return reject(tnt, "RANGE");
            if (!me.hasLineOfSight(tnt)) return reject(tnt, "LOS");
        } else {
            var entity = me.level().getEntity(selected);
            if (!(entity instanceof PrimedTnt live) || !entity.isAlive() || now - castAt > 12 || live.getFuse() < 8) {
                String why = !(entity instanceof PrimedTnt) || !entity.isAlive() ? "GONE" : now - castAt > 12 ? "TIMEOUT" : "FUSE";
                note.accept("bombard rod attachment=UNKNOWN entity=" + selected + " reason=" + why
                        + " hooked=" + (me.fishing == null ? "NO_HOOK" : String.valueOf(me.fishing.getHookedIn() == entity)));
                cancel(); return false;
            }
            tnt = live;
        }
        if (!hands.select(me, slot)) return true;
        if (!me.getMainHandItem().is(Items.FISHING_ROD)) return false;
        if (selected >= 0) {
            hands.look(tnt.getBoundingBox().getCenter());
            if (me.fishing == null || me.fishing.getHookedIn() == null) return true;
            if (me.fishing.getHookedIn() != tnt) {
                note.accept("bombard rod attachment=WRONG_ENTITY"); cancel(); return false;
            }
        } else {
            var physics = baritone.getPredictivePhysics();
            var trajectory = physics.predict(PredictionRequest.trajectory(physics.capture(tnt), RodCast.HORIZON)
                    .priority(PredictionRequest.Priority.COMBAT).fidelity(PredictionRequest.Fidelity.HIGH)
                    .deadlineIn(2_000_000L).consumer("bombard-cast").build());
            String why = BlastSafety.unusable(trajectory);
            if (why != null) return reject(tnt, "PPE_" + why);
            Vec3 eye = me.getEyePosition();
            var cast = RodCast.solve(eye.x, eye.y, eye.z, trajectory.path, trajectory.pathStride);
            if (cast == null) return reject(tnt, "NO_SOLUTION");
            if (aimed != tnt.getId()) { aimed = tnt.getId(); aimTicks = 0; }
            // The rotation set this tick is the one the next tick's use packet carries: aim for the TNT a tick on.
            var ahead = RodCast.lead(eye.x, eye.y, eye.z, trajectory.path, trajectory.pathStride, 1);
            if (ahead != null) cast = ahead;
            double pitch = Math.toRadians(cast.pitch()), yaw = Math.toRadians(cast.yaw());
            Vec3 desired = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            hands.look(eye.add(desired.scale(6)));
            // The use packet carries the rotation held right now (verified MultiPlayerGameMode/handleUseItem), so
            // fire on that rotation's own swept arc rather than waiting for the view to settle on the solution.
            float actualYaw = me.getYRot(), actualPitch = me.getXRot();
            int at = RodCast.hits(eye.x, eye.y, eye.z, actualYaw, actualPitch, trajectory.path, trajectory.pathStride, RodCast.SHRINK);
            if (at < 0) {
                if (++aimTicks > 10) { reject(tnt, "AIM_TIMEOUT"); attempted = tnt.getId(); }
                return true;
            }
            String blocked = arcBlocked(me, tnt, eye, actualYaw, actualPitch, at);
            if (blocked != null) return reject(tnt, blocked);
            // Ostinato: the pull happens at the reel, not now: hit tick + worst spawn delay + one tick to send the reel
            castPath = trajectory.path; castIndex = Math.min(at + RodCast.LATE_MAX + 1, trajectory.path.length / 3 - 2);
        }
        Prediction future = scherzo.predict(tnt.getFuse());
        if (future == null || future.age() != 0 || future.confidence() == Prediction.Confidence.LOW || future.spread() > 1) {
            String why = future == null ? "OPPONENT_UNPREDICTABLE_NONE" : "OPPONENT_UNPREDICTABLE age=" + future.age()
                    + " confidence=" + future.confidence() + String.format(" spread=%.2f", future.spread());
            if (selected < 0) reject(tnt, why);
            drop(why); return false;
        }
        try {
            var physics = baritone.getPredictivePhysics();
            BodyState body = physics.capture(tnt);
            if (selected < 0 && castPath != null) body = atReel(body, castPath, castIndex); // Ostinato: forecast the pull where it will happen
            castPath = null;
            double[] impulse = TntMechanics.rodPull(body.x, body.y, body.z, me.getX(), me.getY(), me.getZ());
            var victim = physics.capture(target).toBuilder().pos(future.x(), future.y(), future.z()).build();
            var targets = List.of(physics.capture(me), victim);
            var before = CombatBombard.predict(physics, body, targets, me);
            var after = CombatBombard.predict(physics, body.toBuilder().vel(body.vx + impulse[0], body.vy + impulse[1], body.vz + impulse[2]).build(), targets, me);
            if ((BlastSafety.transientFailure(before) || BlastSafety.transientFailure(after)) && ++retries <= 3) return true;
            BlastForecast was = BlastSafety.usable(before) ? BlastForecast.of(before, now, 2) : null;
            BlastForecast will = BlastSafety.usable(after) ? BlastForecast.of(after, now, 2) : null;
            if (!BlastSafety.worthwhile(was, will, me.getHealth() + me.getAbsorptionAmount(), observer.permit().maxSelfDamage())) {
                String reason = was == null || will == null
                        ? "FORECAST_" + java.util.Objects.requireNonNullElse(BlastSafety.unusable(was == null ? before : after), "NO_EXPLOSION")
                            + " horizon=" + (was == null ? before : after).horizonTicks + "/" + body.fuse
                            + " computeUs=" + (was == null ? before : after).computeNanos / 1000
                        : String.format("NOT_WORTHWHILE self=%.1f->%.1f foe=%.1f->%.1f", was.damageTo(0), will.damageTo(0),
                                was.damageTo(1), will.damageTo(1));
                if (selected < 0) reject(tnt, reason);
                else note.accept("bombard rod entity=" + selected + " attachment=OBSERVED reel=DECLINED reason=" + reason);
                nextTry = now + 5; retries = 0; cancel(); return false;
            }
            nextTry = now + 5; retries = 0;
            CombatAim.press(baritone.getPlayerContext().minecraft().options.keyUse);
            if (selected < 0) {
                selected = attempted = tnt.getId(); castAt = now; aimed = -1;
                note.accept("bombard rod entity=" + selected + " command=CAST_SENT attachment=UNKNOWN aimTicks=" + aimTicks
                        + String.format(" yaw=%.1f pitch=%.1f fuse=%d", me.getYRot(), me.getXRot(), tnt.getFuse()));
            } else {
                note.accept("bombard rod entity=" + selected + " attachment=OBSERVED command=REEL_SENT");
                effect = new ImpulseAttempt(now, body.vx, body.vz, impulse[0], impulse[2]);
                selected = -1;
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            drop("UNAVAILABLE_" + unavailable.getClass().getSimpleName()); nextTry = now + 5; return false;
        }
    }

    /** Ostinato: TNT state at PPE path index j (vanilla PrimedTnt: gravity 0.04 before move, so v = displacement + 0.04 up). */
    static BodyState atReel(BodyState now, float[] path, int j) {
        if (j <= 0 || 3 * j + 5 >= path.length) return now;
        double x = path[3 * j], y = path[3 * j + 1], z = path[3 * j + 2];
        double dx = path[3 * j + 3] - x, dy = path[3 * j + 4] - y, dz = path[3 * j + 5] - z;
        boolean ground = Math.abs(dy) < 1e-6 && Math.abs(path[3 * j + 1] - path[3 * j - 2]) < 1e-6;
        return now.toBuilder().pos(x, y, z).vel(dx, ground ? 0 : dy + 0.04, dz).onGround(ground)
                .fuse(Math.max(1, now.fuse - j), now.explosionPower).build();
    }

    /** Mean arc for the rotation actually held must not meet a block or another entity before the TNT. */
    private static String arcBlocked(Player me, PrimedTnt tnt, Vec3 eye, float yaw, float pitch, int ticks) {
        Vec3 previous = null;
        for (int step = 0; step <= ticks; step++) {
            var point = RodCast.point(eye.x, eye.y, eye.z, yaw, pitch, step);
            Vec3 here = new Vec3(point.x(), point.y(), point.z());
            if (previous != null) {
                if (me.level().clip(new net.minecraft.world.level.ClipContext(previous, here,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me))
                        .getType() != net.minecraft.world.phys.HitResult.Type.MISS) return "CLIPPED";
                Vec3 from = previous, to = here;
                if (!me.level().getEntities(me, new net.minecraft.world.phys.AABB(from, to).inflate(1.5),
                        e -> e != tnt && e.isPickable() && e.isAlive()
                                && e.getBoundingBox().inflate(0.3 + RodCast.SHRINK).clip(from, to).isPresent()).isEmpty())
                    return "ENTITY_IN_PATH";
            }
            previous = here;
        }
        return null;
    }

    /** Once per TNT and reason: why an existing TNT was not cast at. Always declines this tick. */
    private boolean reject(PrimedTnt tnt, String reason) {
        if (aimed == tnt.getId() && told.add(tnt.getId() + reason))
            note.accept("bombard rod entity=" + tnt.getId() + " cast=ABANDONED reason=" + reason + " fuse=" + tnt.getFuse());
        else if (aimed != tnt.getId() && told.add(tnt.getId() + reason) && !"FUSE".equals(reason))
            note.accept("bombard rod entity=" + tnt.getId() + " cast=DECLINED reason=" + reason + " fuse=" + tnt.getFuse());
        if (told.size() > 64) told.clear();
        return false;
    }

    /** Cancel, saying why when a sent cast was still waiting on attachment or reel. */
    void drop(String why) {
        if (selected >= 0) note.accept("bombard rod entity=" + selected + " reel=ABANDONED reason=" + why);
        cancel();
    }

    void cancel() {
        if (effect != null) note.accept("bombard rod entity=" + attempted + " effect=" + effect.interrupt());
        effect = null; selected = -1;
    }
}
