package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.EntityRayTraceResult;

import java.util.Comparator;
import java.util.function.Predicate;

/**
 * 1.16.1 slim melee fighter with the same surface the bastion code uses ({@code attack}, {@code hold}, {@code driven},
 * {@code clearEnemies}, {@code onTick}). The 26.3 PveProcess depends on its combat suite (gear, shields, maces, tuners),
 * which 1.16.1 does not carry; bastions on 1.16.1 have no brutes, so a plain nearest-target melee is enough.
 */
public final class PveProcess extends BaritoneProcessHelper {
    private Predicate<LivingEntity> filter;
    private String label = "";
    private LivingEntity target;
    public int attacks;
    /** Set by a process that calls {@link #onTick} itself. */
    public boolean driven;
    /** Swing at what is in reach but never walk. */
    public boolean hold;

    public PveProcess(Baritone baritone) {
        super(baritone);
    }

    public void attack(Predicate<LivingEntity> filter, String label) {
        this.filter = filter;
        this.label = label;
        target = null;
        attacks = 0;
    }

    public void clearEnemies() {
        driven = false;
        hold = false;
        filter = null;
        target = null;
    }

    public LivingEntity getTarget() {
        return target;
    }

    @Override
    public boolean isActive() {
        return filter != null && ctx.player() != null;
    }

    private boolean matches(LivingEntity e) {
        return e != ctx.player() && e.isAlive() && !(e instanceof PlayerEntity) && filter != null && filter.test(e);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isActive()) return new PathingCommand(null, PathingCommandType.DEFER);
        PlayerEntity me = ctx.player();
        if (target == null || !matches(target) || me.getDistance(target) > 24) {
            target = ctx.world().getEntitiesWithinAABB(LivingEntity.class, me.getBoundingBox().grow(16), this::matches).stream()
                    .min(Comparator.comparingDouble(me::getDistance)).orElse(null);
        }
        if (target == null) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(me.getEyePosition(1.0F),
                target.getPositionVec().add(0, target.getHeight() * 0.6, 0), ctx.playerRotations()), true);
        double d = me.getDistance(target);
        if (d < 3.6 && me.getCooledAttackStrength(0) >= 0.95F && ctx.minecraft().objectMouseOver instanceof EntityRayTraceResult
                && ((EntityRayTraceResult) ctx.minecraft().objectMouseOver).getEntity() == target) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            attacks++;
        }
        if (hold || d < 2.5) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        return new PathingCommand(new GoalNear(target.getPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        if (driven) return;
        filter = null;
        target = null;
    }

    @Override
    public String displayName0() {
        return "PvE " + label + (target == null ? "" : " -> " + target.getName().getString());
    }

    @Override
    public double priority() {
        return 2;
    }
}
