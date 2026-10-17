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

package baritone.gui.hud;

import baritone.behavior.FreecamBehavior;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * While freecam is on, draws the bot's hotbar (and an open container) above its head so a chest
 * is readable from outside. The container screen itself is not drawn; see the container-screen mixin.
 */
public final class FreecamInventoryHud {

    private static final int CELL = 16;

    private FreecamInventoryHud() {}

    public static void render(GuiGraphicsExtractor g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (FreecamBehavior.activeCamera() == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }
        LocalPlayer player = mc.player;
        String canvas = FreecamBehavior.canvasHud();
        if (canvas != null) {
            GuiDraw.text(g, canvas, 8, 8, 0xFFE7FFF4, true);
        }
        String spar = FreecamBehavior.sparHud();
        float[] at = project(player.getPosition(partial).add(0, player.getBbHeight() + 0.45, 0));
        if (at == null) {
            if (spar != null) {
                GuiDraw.text(g, spar, 8, 8, 0xFFFFFFFF, true);
            }
            return;
        }
        List<ItemStack> chest = chestSlots(player);
        int cols = chest.isEmpty() ? 9 : Math.min(9, chest.size());
        int chestRows = chest.isEmpty() ? 0 : (chest.size() + cols - 1) / cols;
        int rows = chestRows + 2; // armor/offhand, then hotbar
        int panelW = Math.max(cols, 9) * CELL + 4;
        int panelH = rows * CELL + (spar != null ? 12 : 0) + 4;
        float x = at[0] - panelW / 2f;
        float y = at[1] - panelH;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        x = Math.max(2, Math.min(x, sw - panelW - 2));
        y = Math.max(2, Math.min(y, sh - panelH - 2));
        Skin.panel(g, x, y, panelW, panelH, 0xC010141C);
        float cy = y + 2;
        if (spar != null) {
            GuiDraw.text(g, spar, x + 3, cy, 0xFFFFD27A, false);
            cy += 12;
        }
        int i = 0;
        for (ItemStack stack : chest) {
            int col = i % cols;
            int row = i / cols;
            draw(g, stack, x + 2 + col * CELL, cy + row * CELL, false);
            i++;
        }
        cy += chestRows * CELL;
        EquipmentSlot[] armor = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int a = 0; a < armor.length; a++) {
            draw(g, player.getItemBySlot(armor[a]), x + 2 + a * CELL, cy, false);
        }
        draw(g, player.getOffhandItem(), x + 2 + 8 * CELL, cy, false);
        cy += CELL;
        int selected = player.getInventory().getSelectedSlot();
        for (int h = 0; h < 9; h++) {
            draw(g, player.getInventory().getItem(h), x + 2 + h * CELL, cy, h == selected);
        }
    }

    private static void draw(GuiGraphicsExtractor g, ItemStack stack, float x, float y, boolean selected) {
        int ix = (int) x;
        int iy = (int) y;
        g.fill(ix, iy, ix + CELL, iy + CELL, Skin.cell(selected, 0xE03A4A28, 0xA0182028));
        if (stack != null && !stack.isEmpty()) {
            g.item(stack, ix, iy);
        }
    }

    private static List<ItemStack> chestSlots(Player player) {
        List<ItemStack> out = new ArrayList<>();
        if (player.containerMenu == null || player.containerMenu == player.inventoryMenu) {
            return out;
        }
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container != player.getInventory()) {
                out.add(slot.getItem());
            }
        }
        return out;
    }

    /** World position to GUI pixels. Null when the point is behind the camera. */
    private static float[] project(Vec3 world) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            return null;
        }
        Vec3 rel = world.subtract(cam.position());
        Entity body = FreecamBehavior.activeCamera();
        if (body != null && rel.dot(body.getLookAngle()) <= 0.05) {
            return null;
        }
        // Same clip-space point GameRenderer.projectPointToScreen produces: NDC, perspective-divided.
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(world);
        if (ndc.z < 0 || ndc.z > 1) {
            return null;
        }
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        return new float[]{(float) ((ndc.x * 0.5 + 0.5) * w), (float) ((1 - (ndc.y * 0.5 + 0.5)) * h)};
    }
}
