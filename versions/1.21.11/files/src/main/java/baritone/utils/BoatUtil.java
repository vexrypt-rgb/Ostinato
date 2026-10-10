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

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/** Finds a boat anywhere in the inventory, borrowing a hotbar slot for it while it's placed. */
public final class BoatUtil {

    private static int[] borrowed; // {inventory slot, hotbar slot}

    private BoatUtil() {}

    public static boolean hasBoat(NonNullList<ItemStack> inv) {
        for (int i = 0; i < 36; i++) {
            if (inv.get(i).getItem() instanceof BoatItem) return true;
        }
        return false;
    }

    /** Hotbar slot holding a boat, swapping one in from the main inventory if needed; -1 if none. */
    public static int hotbarBoat(IPlayerContext ctx) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).getItem() instanceof BoatItem) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (!(inv.get(i).getItem() instanceof BoatItem)) continue;
            int hb = 8;
            for (int j = 0; j < 9; j++) {
                if (inv.get(j).isEmpty()) { hb = j; break; }
            }
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, hb, ClickType.SWAP, ctx.player());
            borrowed = new int[]{i, hb};
            return hb;
        }
        return -1;
    }

    /** Put back whatever the borrowed hotbar slot held, once the boat has been placed. */
    public static void restore(IPlayerContext ctx) {
        if (borrowed == null || ctx.player() == null) return;
        if (!(ctx.player().getInventory().getNonEquipmentItems().get(borrowed[1]).getItem() instanceof BoatItem)) {
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, borrowed[0], borrowed[1], ClickType.SWAP, ctx.player());
        }
        borrowed = null;
    }

    /** A boat nobody sits in: whoever boards first drives it, so only these are ours to take. */
    public static boolean free(Entity e) {
        return e instanceof AbstractBoat && e.isAlive() && e.getPassengers().isEmpty();
    }

    /** True if we sit in a boat and are its first passenger, the one that steers. */
    public static boolean isDriver(Player p) {
        Entity v = p.getVehicle();
        return v instanceof AbstractBoat && v.getControllingPassenger() == p;
    }

    /**
     * A mob shares our boat. Vanilla seats a boarding player in front of a mob, so we'd steer it, but
     * the mob came along uninvited (it hopped in first, or climbed into our seat's spare place):
     * get out and break the boat, which throws the mob out, rather than ferry it around.
     */
    public static boolean mobAboard(Player p) {
        Entity v = p.getVehicle();
        if (!(v instanceof AbstractBoat)) return false;
        for (Entity e : v.getPassengers()) {
            if (e != p && !(e instanceof Player)) return true;
        }
        return false;
    }

    /** Block positions (as longs) of free boats within r of the player, snapshot for path costs. */
    public static Set<Long> freeBoats(Level w, Player p, double r) {
        Set<Long> out = new HashSet<>();
        if (!(w instanceof ClientLevel) || p == null) return out;
        for (Entity e : ((ClientLevel) w).entitiesForRendering()) {
            if (free(e) && e.distanceTo(p) < r) out.add(BlockPos.containing(e.getX(), e.getY() + 0.1, e.getZ()).asLong());
        }
        return out;
    }
}
