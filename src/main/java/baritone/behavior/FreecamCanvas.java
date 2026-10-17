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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.selection.ISelection;
import baritone.gui.studio.BlockPalette;
import baritone.gui.studio.EditOps;
import baritone.gui.studio.EditSchematic;
import baritone.gui.studio.EditSession;
import baritone.gui.studio.StudioPick;
import baritone.gui.studio.StudioVolume;
import baritone.gui.studio.VolumeWorldRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A client-only build volume drawn in the world. Flat is a superflat pad the bot does not know about.
 * Edit copies a region so it can be restyled without writing blocks. The builder runs only on apply,
 * or a short time after each edit when live is on.
 */
public final class FreecamCanvas {

    public enum Mode { OFF, FLAT, EDIT }

    private final EditSession session = new EditSession();
    private Mode mode = Mode.OFF;
    private StudioVolume original;
    private BlockPos origin;
    private boolean live;
    private boolean brushLocked;
    private int handSlot = -2;
    private int liveDelay;
    private String status = "";

    public Mode mode() {
        return mode;
    }

    public boolean active() {
        return mode != Mode.OFF && session.volume != null && origin != null;
    }

    public String hud() {
        if (!active()) {
            return null;
        }
        String liveText = live ? "live" : "later";
        String block = BlockPalette.label(session.brush);
        return (mode == Mode.FLAT ? "Flat" : "Edit") + "  " + session.tool.wire + "  " + block + "  " + liveText;
    }

    public String status() {
        return status;
    }

    public void discard() {
        mode = Mode.OFF;
        original = null;
        origin = null;
        session.bind(null);
        liveDelay = 0;
        status = "";
    }

    public String startFlat(Entity camera, LocalPlayer player) {
        int stone = BlockPalette.cell("stone");
        int dirt = BlockPalette.cell("dirt");
        int grass = BlockPalette.cell("grass_block");
        if (stone == 0 || dirt == 0 || grass == 0) {
            status = "Block registry is not ready";
            return status;
        }
        int ground = Mth.floor(camera.getY()) - 1;
        origin = new BlockPos(Mth.floor(camera.getX()) - 16, ground - 3, Mth.floor(camera.getZ()) - 16);
        StudioVolume volume = new StudioVolume(32, 16, 32);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                volume.set(x, 0, z, stone);
                volume.set(x, 1, z, dirt);
                volume.set(x, 2, z, dirt);
                volume.set(x, 3, z, grass);
            }
        }
        original = volume.copy();
        session.bind(volume);
        session.brush = stone;
        adoptHand(player);
        mode = Mode.FLAT;
        liveDelay = 0;
        status = "Flat canvas at " + origin.getX() + " " + (origin.getY() + 3) + " " + origin.getZ()
                + ". It is not in the world. #freecam apply places only blocks you add. The bot keeps its task until then.";
        return status;
    }

    public String startEdit(Entity camera, Level level, ISelection selection) {
        if (level == null || camera == null) {
            status = "Stand in a world first";
            return status;
        }
        int sx = 16;
        int sy = 12;
        int sz = 16;
        int ox;
        int oy;
        int oz;
        String clipped = "";
        if (selection != null) {
            BlockPos min = selection.min();
            BlockPos max = selection.max();
            int fullX = max.getX() - min.getX() + 1;
            int fullY = max.getY() - min.getY() + 1;
            int fullZ = max.getZ() - min.getZ() + 1;
            sx = Math.min(StudioVolume.MAX, fullX);
            sy = Math.min(StudioVolume.MAX, fullY);
            sz = Math.min(StudioVolume.MAX, fullZ);
            ox = min.getX();
            oy = min.getY();
            oz = min.getZ();
            if (fullX > sx || fullY > sy || fullZ > sz) {
                clipped = " Clipped to " + sx + "x" + sy + "x" + sz + ".";
            }
        } else {
            BlockPos center = lookBlock(camera, level);
            ox = center.getX() - sx / 2;
            oy = center.getY() - sy / 2;
            oz = center.getZ() - sz / 2;
        }
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        if (oy < minY) {
            oy = minY;
        }
        if (oy + sy > maxY) {
            oy = Math.max(minY, maxY - sy);
        }
        StudioVolume volume = new StudioVolume(sx, sy, sz);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState state = level.getBlockState(new BlockPos(ox + x, oy + y, oz + z));
                    volume.set(x, y, z, BlockPalette.idOf(state));
                }
            }
        }
        original = volume.copy();
        origin = new BlockPos(ox, oy, oz);
        session.bind(volume);
        if (session.brush == 0) {
            session.brush = BlockPalette.cell("stone");
        }
        mode = Mode.EDIT;
        liveDelay = 0;
        status = "Editing " + sx + "x" + sy + "x" + sz + " at " + ox + " " + oy + " " + oz + "."
                + clipped + " The world is unchanged until #freecam apply. #freecam live builds as you edit.";
        return status;
    }

    /** True when a debounced live apply should run. */
    public boolean tickLive(LocalPlayer player) {
        if (active()) {
            adoptHand(player);
        }
        if (!live || !active() || liveDelay <= 0) {
            return false;
        }
        liveDelay--;
        return liveDelay == 0;
    }

    public int apply(Baritone baritone) {
        if (!active() || original == null) {
            status = "No canvas to build";
            return 0;
        }
        EditSchematic schematic = EditSchematic.diff(session.volume, original, mode == Mode.EDIT);
        if (schematic == null || schematic.changes() == 0) {
            status = "No changes to build";
            return 0;
        }
        baritone.getBuilderProcess().build(mode == Mode.FLAT ? "flat" : "edit", schematic, origin);
        status = "Building " + schematic.changes() + " blocks. This takes the builder.";
        return schematic.changes();
    }

    public void toggleLive() {
        live = !live;
        status = live
                ? "Live on. Each edit is handed to the builder, which takes over from the current task."
                : "Live off. #freecam apply or #freecam done builds when you say so.";
        if (live && active()) {
            liveDelay = 8;
        }
    }

    public boolean live() {
        return live;
    }

    public void tool(String name) {
        EditOps.Tool tool = EditOps.Tool.byName(name);
        if (tool == null) {
            status = "Unknown tool. brush, select, fill, replace, line, sphere, hollow, wall, copy, paste, rotate, flip.";
            return;
        }
        session.tool = tool;
        status = "Tool: " + tool.wire;
    }

    public void brush(String name) {
        if (name != null && name.equalsIgnoreCase("hand")) {
            brushLocked = false;
            handSlot = -2;
            status = "Brush follows the held block";
            return;
        }
        int id = BlockPalette.cell(name);
        if (id <= 0) {
            status = "Unknown block " + name;
            return;
        }
        session.brush = id;
        brushLocked = true;
        status = "Brush: " + BlockPalette.label(id);
    }

    public void radius(int radius) {
        session.radius = Math.max(1, Math.min(8, radius));
        status = "Radius " + session.radius;
    }

    public boolean undo() {
        boolean changed = session.undo();
        status = session.note == null ? "Undone" : session.note;
        if (changed) {
            touch();
        }
        return changed;
    }

    /**
     * Left 0, right 1, middle 2. The caller consumes the click whenever the canvas is up, so the bot does not path.
     * Returns a line to print, or null when the edit speaks for itself.
     */
    public String click(Entity camera, Level level, int button) {
        if (!active()) {
            return null;
        }
        if (button == 2) {
            String picked = pickBrush(camera, level);
            if (picked != null) {
                status = picked;
            }
            return picked;
        }
        Vec3 eye = camera.getEyePosition();
        Vec3 look = camera.getLookAngle();
        StudioPick hit = StudioPick.trace(eye.x - origin.getX(), eye.y - origin.getY(), eye.z - origin.getZ(),
                look.x, look.y, look.z, session.volume);
        if (session.click(hit, button)) {
            touch();
        }
        if (session.note != null) {
            status = session.note;
        }
        return session.note;
    }

    public void render(RenderEvent event, Vec3 eye) {
        if (!active()) {
            return;
        }
        VolumeWorldRenderer.render(event, session.volume, original, origin, session.selection(),
                session.hasLine, session.lx, session.ly, session.lz, mode == Mode.FLAT, eye);
    }

    private void touch() {
        if (live) {
            liveDelay = 8;
        }
    }

    private void adoptHand(LocalPlayer player) {
        if (brushLocked || player == null || !active()) {
            return;
        }
        int slot = player.getInventory().getSelectedSlot();
        if (slot == handSlot) {
            return;
        }
        handSlot = slot;
        if (player.getMainHandItem().getItem() instanceof BlockItem block) {
            int id = BlockPalette.idOf(block.getBlock().defaultBlockState());
            if (id > 0) {
                session.brush = id;
            }
        }
    }

    private String pickBrush(Entity camera, Level level) {
        Vec3 eye = camera.getEyePosition();
        Vec3 look = camera.getLookAngle();
        Vec3 end = eye.add(look.scale(64));
        if (active()) {
            StudioPick hit = StudioPick.trace(eye.x - origin.getX(), eye.y - origin.getY(), eye.z - origin.getZ(),
                    look.x, look.y, look.z, session.volume);
            if (hit.solid) {
                int cell = session.volume.get(hit.x, hit.y, hit.z);
                if (cell > 0) {
                    session.brush = cell;
                    brushLocked = true;
                    return "Brush: " + BlockPalette.label(cell);
                }
            }
        }
        if (level == null) {
            return null;
        }
        HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockState state = level.getBlockState(((BlockHitResult) hit).getBlockPos());
        int id = BlockPalette.idOf(state);
        if (id <= 0) {
            return null;
        }
        session.brush = id;
        brushLocked = true;
        return "Brush: " + BlockPalette.label(id);
    }

    private static BlockPos lookBlock(Entity camera, Level level) {
        Vec3 eye = camera.getEyePosition();
        Vec3 end = eye.add(camera.getLookAngle().scale(128));
        HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos();
        }
        return BlockPos.containing(camera.getX(), camera.getY(), camera.getZ());
    }
}
