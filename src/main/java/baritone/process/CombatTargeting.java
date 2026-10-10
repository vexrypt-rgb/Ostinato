package baritone.process;

import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.function.Predicate;

/** Who we fight and how fast they are moving. The current target itself stays with the process; this only answers about it. */
final class CombatTargeting {
    private final IPlayerContext ctx;
    private final Predicate<LivingEntity> matches;
    private LivingEntity tvTarget;
    private Vec3 tvPos = Vec3.ZERO, tvVel = Vec3.ZERO;
    private double tvRawY; // last tick's actual vertical step: the smoothed velocity lags a dive's start by two ticks

    CombatTargeting(IPlayerContext ctx, Predicate<LivingEntity> matches) {
        this.ctx = ctx;
        this.matches = matches;
    }

    /** Remote players report no velocity client-side, so derive it from their position change per tick. */
    void track(LivingEntity target) {
        if (target != tvTarget || tvTarget == null) {
            tvTarget = target;
            tvVel = Vec3.ZERO;
        } else {
            Vec3 d = target.position().subtract(tvPos);
            tvVel = d.length() > 4 ? Vec3.ZERO : tvVel.scale(0.5).add(d.scale(0.5));
            tvRawY = d.length() > 4 ? 0 : d.y;
        }
        tvPos = target.position();
    }

    Vec3 velocity(LivingEntity target) {
        // Remote players keep a stale getDeltaMovement (Vex0 vy stayed 1.16 for a whole jump).
        Vec3 own = target.getDeltaMovement();
        if (target != ctx.player() && tvVel.lengthSqr() > 1e-4) return tvVel;
        return own.lengthSqr() > 1e-4 ? own : tvVel;
    }

    double rawY() {
        return tvRawY;
    }

    /** With several opponents in reach, finish the weakest one rather than whichever was nearest first. Returns the target to use. */
    LivingEntity retarget(Player me, LivingEntity target) {
        java.util.function.ToDoubleFunction<LivingEntity> score = e -> e.getHealth() + e.getAbsorptionAmount() + 0.6 * me.distanceTo(e);
        LivingEntity best = ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(7),
                        e -> e != me && e.isAlive() && !e.isRemoved() && !e.isSpectator() && matches.test(e))
                .stream().min(Comparator.comparingDouble(score)).orElse(null);
        if (best != null && best != target && score.applyAsDouble(best) < score.applyAsDouble(target) - 3) return best;
        return target;
    }

    /** Recorder tag: opponents within 12 blocks as "n<count>:<nearest-other dist>". */
    String others(Player me, LivingEntity target) {
        double near = 99;
        int n = 0;
        for (LivingEntity e : ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(12), x -> x != me && x != target && x.isAlive() && matches.test(x))) {
            n++;
            near = Math.min(near, me.distanceTo(e));
        }
        return n == 0 ? "n0" : String.format("n%d:%.1f", n, near);
    }

    /** Nearest matching enemy within {@code chase} blocks, or null. */
    LivingEntity pick(Player me, double chase) {
        return ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(chase),
                        e -> e != me && e.isAlive() && !e.isRemoved() && !e.isSpectator() && matches.test(e))
                .stream().filter(e -> me.distanceTo(e) <= chase)
                .min(Comparator.comparingDouble(me::distanceToSqr)).orElse(null);
    }
}
