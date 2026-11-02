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

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.legato.LegatoFailures;
import baritone.pathing.legato.LegatoFeasibility;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Ostinato Legato: walk a straight line, at any angle, between two cells at the same height. Made only by Legato's
 * path smoothing and any-angle search, where {@link LegatoFeasibility} found the whole swept player box open over a
 * plain floor. The cost re-runs that check, so the executor's per-tick cost check drops the line (and replans) as
 * soon as the terrain under it changes. The kinematic driver steers it like any walking run; this movement's own
 * steering is the fallback: follow the line a little ahead of the player, turning before walking on.
 */
public class MovementStraight extends Movement {

    /** How far ahead along the line the fallback steering aims. */
    static final double LOOKAHEAD = 1.2;

    public MovementStraight(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[0]);
        if (src.y != dest.y) {
            throw new IllegalArgumentException("a straight line is flat: " + src + " -> " + dest);
        }
    }

    @Override
    public double calculateCost(CalculationContext context) {
        // planning thread and client thread both ask, so each call has its own answer
        return LegatoFeasibility.evaluate(context, src.x, src.y, src.z, dest.x, dest.z, new LegatoFeasibility.Result()).cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        set.add(dest);
        LegatoFeasibility.forEachCell(src.x, src.z, dest.x, dest.z, (x, z) -> set.add(new BetterBlockPos(x, src.y, z)));
        return set;
    }

    /** The line failed with the player at {@code at}: do not plan it (or through that cell) again for a while. */
    public void failed(BetterBlockPos at) {
        LegatoFailures.record(src.x, src.y, src.z, dest.x, dest.z, at.x, at.z);
    }

    @Override
    protected boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || ctx.player().onGround();
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (feet.equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        Vec3 p = ctx.player().position();
        boolean ground = ctx.player().onGround();
        if (p.y < src.y - 0.25 || (ground && (feet.y != src.y || !getValidPositions().contains(feet)))) {
            failed(feet);
            return state.setStatus(MovementStatus.FAILED);
        }
        double ax = src.x + 0.5;
        double az = src.z + 0.5;
        double dx = dest.x - src.x;
        double dz = dest.z - src.z;
        double len2 = dx * dx + dz * dz;
        double t = Math.max(0, Math.min(1, ((p.x - ax) * dx + (p.z - az) * dz) / len2));
        double aim = Math.min(1, t + LOOKAHEAD / Math.sqrt(len2));
        Vec3 head = ctx.playerHead();
        Rotation to = RotationUtils.calcRotationFromVec3d(head, new Vec3(ax + dx * aim, head.y, az + dz * aim),
                ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch());
        state.setTarget(new MovementState.MovementTarget(to, false));
        if (!MovementHelper.turningAway(ctx, to)) {
            state.setInput(Input.MOVE_FORWARD, true);
            state.setInput(Input.SPRINT, true);
        }
        return state;
    }
}
