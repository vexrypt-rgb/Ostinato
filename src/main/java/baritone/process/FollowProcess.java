/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.api.process.ProcessOutcome;
import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings

    public FollowProcess(Baritone baritone) {
        super(baritone);
    }

    // ---- Ostinato: the outcome of each follow request (a swarm command reports success only from this) ------------
    /**
     * A follow has no goal of its own to reach, so it never succeeds: it is cancelled when stopped, and failed once it
     * has had nothing to follow for {@link #LOST_TICKS} game ticks (none in range from the start, or the target gone).
     * Stock behaviour is kept: the filter stays set, so a target that comes back is followed again, under that same
     * (already failed) request. Pickup requests are not recorded (no outcome, as before).
     */
    static final long LOST_TICKS = 100;
    private final RequestOutcome requestOutcome = new RequestOutcome();
    private long lastTargetTick = Long.MIN_VALUE;

    @Override
    public ProcessOutcome outcome() {
        return requestOutcome.outcome();
    }

    /** Whether a follow that last had a target at game tick {@code last} (MIN_VALUE: never) has lost it by {@code now}. */
    static boolean lost(long last, long started, long now) {
        return now - Math.max(last, started) >= LOST_TICKS;
    }

    private long startedTick;

    private long gameTime() {
        return ctx.world() == null ? 0 : ctx.world().getGameTime();
    }

    private void noteTargets() {
        if (into || filter == null || cache == null || !requestOutcome.open()) {
            return;
        }
        long now = gameTime();
        if (!cache.isEmpty()) {
            lastTargetTick = now;
            requestOutcome.running();
        } else if (lost(lastTargetTick, startedTick, now)) {
            requestOutcome.endNow(ProcessOutcome.Reason.INVALIDATED_GOAL);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        if (into) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, Baritone.settings().followRadius.value);
    }


    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        return ctx.entitiesStream().anyMatch(entity::equals);
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        noteTargets(); // Ostinato:
        return !cache.isEmpty();
    }

    @Override
    public void onLostControl() {
        requestOutcome.finish(); // Ostinato: stopped (or replaced) before it lost its target: cancelled
        filter = null;
        cache = null;
    }

    @Override
    public String displayName0() {
        return "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
        requestOutcome.start(); // Ostinato:
        startedTick = gameTime();
        lastTargetTick = Long.MIN_VALUE;
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
        requestOutcome.finish(); // Ostinato: a pickup replaces any follow request (cancelled); pickups are not recorded
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
