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

package baritone.utils;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Something we can get on with one right-click while falling past it, which cancels the fall: riding resets the
 * fall distance every tick (LivingEntity.rideTick), so a fall that ends in a saddle deals nothing.
 * <p>
 * The click goes out before that tick's move packet, so the server seats us before it hears about the landing.
 * It checks reach from where we were at the end of the previous tick, against the mount's box, with 3 blocks
 * on top of the 3 we normally have. The last tick-end above the ground is at most one tick of terminal speed
 * (3.92 blocks) up, so a mount on the landing cell or beside it is always in reach whatever the height.
 */
public final class MountUtil {

    private MountUtil() {}

    /** Vanilla reach plus the server's allowance (ServerGamePacketListenerImpl.handleInteract), measured from the eye. */
    public static final double SERVER_REACH = 6.0;
    /** What we aim for: the vanilla reach, so the click looks like one a player could make. */
    public static final double REACH = 3.0;

    /** Gets on with an empty hand, nobody aboard. Babies, untamed undead horses and unsaddled pigs/striders don't let us. */
    public static boolean mountable(Entity e) {
        if (!e.isAlive() || !e.getPassengers().isEmpty()) {
            return false;
        }
        if (e instanceof AbstractBoat || e instanceof Minecart) {
            return true;
        }
        if (e instanceof AgeableMob && ((AgeableMob) e).isBaby()) {
            return false;
        }
        if (e instanceof SkeletonHorse || e instanceof ZombieHorse) {
            return ((AbstractHorse) e).isTamed(); // untamed they buck (zombie) or ignore us (skeleton)
        }
        if (e instanceof AbstractHorse) {
            return true; // horses, donkeys, mules, llamas, camels: an untamed one still lets us on for a while
        }
        if (e instanceof Pig || e instanceof Strider) {
            return ((Mob) e).isSaddled();
        }
        return false;
    }

    /** Feet-level cells of everything mountable within r of the player. */
    public static Set<Long> freeMounts(Level w, Player p, double r) {
        Set<Long> out = new HashSet<>();
        if (!(w instanceof ClientLevel) || p == null) return out;
        for (Entity e : ((ClientLevel) w).entitiesForRendering()) {
            if (e != p && e.distanceTo(p) < r && mountable(e)) out.add(BlockPos.containing(e.getX(), e.getY() + 0.1, e.getZ()).asLong());
        }
        return out;
    }

    /** The mountable entity nearest the centre of this landing cell, standing on it or beside it, or null. */
    public static Entity mountAt(Level w, BlockPos feet) {
        if (!(w instanceof ClientLevel)) return null;
        Vec3 c = Vec3.atBottomCenterOf(feet);
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : ((ClientLevel) w).entitiesForRendering()) {
            if (Math.abs(e.getY() - feet.getY()) > 1.0 || Math.abs(e.getX() - c.x) > 1.6 || Math.abs(e.getZ() - c.z) > 1.6 || !mountable(e)) {
                continue;
            }
            double d = e.position().distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Distance from the player's eye to the mount's box, the way the server measures reach. */
    public static double reach(Player p, Entity e) {
        AABB box = e.getBoundingBox();
        return Math.sqrt(box.distanceToSqr(p.getEyePosition()));
    }
}
