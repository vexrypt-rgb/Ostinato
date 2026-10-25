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

import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.TickSim;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

// Ostinato: god bridge. Original. A backplace traverse turns around and walks backward at walk speed,
// and clicks the far face of the block underfoot (the face on the destination side) the tick that face
// is under the crosshair. The look is applied a tick later, so the pitch is chosen for where the next
// tick lands. No per-node state: the search only reads footing().
final class GodBridge {

    static final double EYE = 1.62;
    /** Centre of the far face, below the feet plane. The face itself runs from -1 to 0. */
    static final double FACE_CENTRE = 0.5;
    /** Still standing on the block while the feet centre is this far past the far face. */
    static final double SUPPORT = TickSim.HALF_WIDTH;
    /** Default survival reach. The click is about two blocks; this only rejects a pitch that would be out of reach. */
    static final double REACH = 4.5;
    /** Pitch while still short of the face, steep enough that the first tick past it is already aimed. */
    static final double AIM_PAST = 0.15;
    /** Room, in blocks before the face, needed to finish turning around. Closer than this, sneak instead. */
    static final double TURN_ROOM = 0.35;
    static final float YAW_SLACK = 25f;
    /** Off the centre line by more than this, the crosshair misses the face. The sneak path recentres. */
    static final double LATERAL_LIMIT = 0.35;
    private static final double YAW_BIAS_PER_BLOCK = 40;
    private static final double YAW_BIAS_CAP = 15;
    /** Normal ground friction. Ice, slime and honey are not this, and the walk timing is for this. */
    private static final float NORMAL_FRICTION = 0.6f;

    private GodBridge() {}

    /** A full block we can place against, at normal walk friction, that a click will not open. */
    static boolean footing(BlockState state) {
        Block block = state.getBlock();
        float friction = block.getFriction();
        if (friction < NORMAL_FRICTION - 0.01f || friction > NORMAL_FRICTION + 0.01f) {
            return false;
        }
        if (block == Blocks.MAGMA_BLOCK || block == Blocks.SOUL_SAND || block == Blocks.HONEY_BLOCK || block == Blocks.SLIME_BLOCK) {
            return false;
        }
        if (block instanceof SlabBlock && state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
            return false;
        }
        if (MovementHelper.isClimbable(block) || MovementHelper.isWater(state) || MovementHelper.isLava(state)) {
            return false;
        }
        if (opens(block)) {
            return false;
        }
        return MovementHelper.isBlockNormalCube(state) || block == Blocks.GLASS || block instanceof StainedGlassBlock;
    }

    private static boolean opens(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST
                || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
                || block == Blocks.CRAFTING_TABLE;
    }

    /** Pitch in degrees, positive down, that puts the crosshair on the far-face centre from {@code past} blocks beyond it. */
    static double pitchDegrees(double past) {
        double along = Math.max(past, 1.0e-3);
        return Math.toDegrees(Math.atan2(EYE + FACE_CENTRE, along));
    }

    /** The ray at {@code pitchDeg} hits the far face while the body is still supported by the block. */
    static boolean hitsFace(double past, double pitchDeg) {
        if (!(past > 0) || past > SUPPORT) {
            return false;
        }
        double pitch = Math.toRadians(pitchDeg);
        double cos = Math.cos(pitch);
        if (cos < 1.0e-4) {
            return false;
        }
        double t = past / cos;
        if (t <= 0 || t > REACH) {
            return false;
        }
        double hitY = EYE - t * Math.sin(pitch);
        return hitY < -0.02 && hitY > -0.98;
    }

    /**
     * How far ahead to aim. The crosshair catches up next tick, so this is where that tick will be when
     * {@code predict} is the feet centre then, measured past the face.
     */
    static double aimPast(double past, double predict) {
        if (predict > 0 && predict <= SUPPORT) {
            return predict;
        }
        if (past > 0 && past <= SUPPORT) {
            return past;
        }
        return AIM_PAST;
    }

    /** This tick's displacement from a post-friction along-track speed, holding back on normal ground. */
    static double displacement(double draggedAlong) {
        return draggedAlong + TickSim.WALK_ACCEL;
    }

    /**
     * Already facing back, holding back. {@code before} is how far the feet centre is short of the face.
     * {@code dragged} is the post-friction speed toward the destination. True when a tick lands in the
     * window on the pitch chosen the tick before.
     */
    static boolean places(double before, double dragged) {
        double past = -before;
        double d = dragged;
        double aimed = Double.NaN;
        for (int t = 0; t < 40; t++) {
            if (past > SUPPORT) {
                return false;
            }
            if (!Double.isNaN(aimed) && hitsFace(past, aimed)) {
                return true;
            }
            double upcoming = displacement(d);
            aimed = pitchDegrees(aimPast(past, past + upcoming));
            past += upcoming;
            d = upcoming * TickSim.GROUND_DRAG;
        }
        return false;
    }

    /** Too late, or still turning with the edge too close. That tick uses the sneak backplace. */
    static boolean sneakInstead(boolean facingBack, double past) {
        if (past > SUPPORT) {
            return true;
        }
        return !facingBack && past > -TURN_ROOM;
    }

    /** Yaw facing back along the bridge. A lateral offset (right of travel is positive) biases it back to the centre line. */
    static float backYaw(int dx, int dz, double lateral) {
        double base = Math.toDegrees(Math.atan2(dx, -dz));
        double bias = Math.max(-YAW_BIAS_CAP, Math.min(YAW_BIAS_CAP, lateral * YAW_BIAS_PER_BLOCK));
        return (float) (base - bias);
    }

    /** Metres to the right of the travel direction. Bridging east, south is positive. */
    static double lateral(int dx, int dz, double px, double pz, double cx, double cz) {
        return (px - cx) * (-dz) + (pz - cz) * dx;
    }

    static Direction travel(int dx, int dz) {
        if (dx > 0) {
            return Direction.EAST;
        }
        if (dx < 0) {
            return Direction.WEST;
        }
        if (dz > 0) {
            return Direction.SOUTH;
        }
        return Direction.NORTH;
    }
}
