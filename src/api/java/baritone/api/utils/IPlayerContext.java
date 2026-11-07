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

package baritone.api.utils;

import baritone.api.cache.IWorldData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author Brady
 * @since 11/12/2018
 */
public interface IPlayerContext {

    Minecraft minecraft();

    LocalPlayer player();

    IPlayerController playerController();

    Level world();

    default Iterable<Entity> entities() {
        return ((ClientLevel) world()).entitiesForRendering();
    }

    default Stream<Entity> entitiesStream() {
        return StreamSupport.stream(entities().spliterator(), false);
    }


    IWorldData worldData();

    HitResult objectMouseOver();

    default BetterBlockPos playerFeet() {
        // TODO find a better way to deal with soul sand!!!!!
        BetterBlockPos feet = new BetterBlockPos(player().position().x, player().position().y + 0.1251, player().position().z);

        // sometimes when calling this from another thread or while world is null, it'll throw a NullPointerException
        // that causes the game to immediately crash
        //
        // so of course crashing on 2b is horribly bad due to queue times and logout spot
        // catch the NPE and ignore it if it does happen
        //
        // this does not impact performance at all since we're not null checking constantly
        // if there is an exception, the only overhead is Java generating the exception object... so we can ignore it
        try {
            var state = world().getBlockState(feet);
            if (state.getBlock() instanceof SlabBlock) {
                return feet.above();
            }
            // Ostinato: standing on any low top the planner counts as the node above it (candle, bed, chain, snow
            // layers, lantern: a collision top 0.25 to 1 high), as the slab above; else a candle's node never matched
            var shape = state.getCollisionShape(world(), feet);
            if (!shape.isEmpty()) {
                double top = shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top >= 0.25 && top <= 1.0 && player().position().y >= feet.y + top - 0.01) {
                    return feet.above();
                }
                // Ostinato: run105. Pressed against a fence, wall or closed gate post (mid-block), the player's centre is up to
                // 0.3 inside the post's block, so the node was the post itself. A path then started "inside" a closed gate,
                // the traverse out never opened it (its gate check only looks ahead), and room 4's dig pushed it for 10 min
                // (the provisioning log hunt was pinned on the fence beside it first). When the gap beside the post is
                // narrower than the player, the body stands in the neighbour: that is the node.
                double lx = player().position().x - feet.x, lz = player().position().z - feet.z, w = player().getBbWidth();
                double minX = shape.min(net.minecraft.core.Direction.Axis.X), maxX = shape.max(net.minecraft.core.Direction.Axis.X);
                double minZ = shape.min(net.minecraft.core.Direction.Axis.Z), maxZ = shape.max(net.minecraft.core.Direction.Axis.Z);
                int sx = lx >= maxX ? 1 : lx <= minX ? -1 : 0, sz = lz >= maxZ ? 1 : lz <= minZ ? -1 : 0;
                double gapX = sx > 0 ? 1 - maxX : sx < 0 ? minX : 1, gapZ = sz > 0 ? 1 - maxZ : sz < 0 ? minZ : 1;
                if (Math.min(gapX, gapZ) < w) {
                    BetterBlockPos side = gapX <= gapZ ? new BetterBlockPos(feet.x + sx, feet.y, feet.z) : new BetterBlockPos(feet.x, feet.y, feet.z + sz);
                    if (world().getBlockState(side).getCollisionShape(world(), side).isEmpty()) {
                        return side;
                    }
                }
            } else if (player().onGround() && player().mainSupportingBlockPos.isPresent()
                    && world().getBlockState(feet.below()).getCollisionShape(world(), feet.below()).isEmpty()) {
                // on the edge of a low top with the centre over air (a candle is 1/8 wide): the node is still above it
                var on = player().mainSupportingBlockPos.get();
                var onShape = world().getBlockState(on).getCollisionShape(world(), on);
                if (on.getY() == feet.y && !onShape.isEmpty() && onShape.max(net.minecraft.core.Direction.Axis.Y) >= 0.25) {
                    return new BetterBlockPos(on.getX(), on.getY() + 1, on.getZ());
                }
            }
        } catch (NullPointerException ignored) {}

        return feet;
    }

    default Vec3 playerFeetAsVec() {
        return new Vec3(player().position().x, player().position().y, player().position().z);
    }

    default Vec3 playerHead() {
        return new Vec3(player().position().x, player().position().y + player().getEyeHeight(), player().position().z);
    }

    default Vec3 playerMotion() {
        return player().getDeltaMovement();
    }

    BetterBlockPos viewerPos();

    default Rotation playerRotations() {
        return new Rotation(player().getYRot(), player().getXRot());
    }

    /**
     * Returns the player's eye height, taking into account whether or not the player is sneaking.
     *
     * @param ifSneaking Whether or not the player is sneaking
     * @return The player's eye height
     * @deprecated Use entity.getEyeHeight(Pose.CROUCHING) instead
     */
    @Deprecated
    static double eyeHeight(boolean ifSneaking) {
        return ifSneaking ? 1.27 : 1.62;
    }

    /**
     * Returns the block that the crosshair is currently placed over. Updated once per tick.
     *
     * @return The position of the highlighted block
     */
    default Optional<BlockPos> getSelectedBlock() {
        HitResult result = objectMouseOver();
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            return Optional.of(((BlockHitResult) result).getBlockPos());
        }
        return Optional.empty();
    }

    default boolean isLookingAt(BlockPos pos) {
        return getSelectedBlock().equals(Optional.of(pos));
    }
}
