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

package baritone.gui.studio;

import baritone.api.event.events.RenderEvent;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/**
 * Draws a client volume with the block atlas. Quads are grown a hair so an edit preview covers the real block
 * instead of flickering against it. Nothing here writes the world.
 */
public final class VolumeWorldRenderer {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
    private static final int MAX_FACES = 12000;
    private static Boolean blockFormat;

    private VolumeWorldRenderer() {}

    public static void render(RenderEvent event, StudioVolume volume, StudioVolume baseline, BlockPos origin,
                              EditOps.Box selection, boolean anchor, int ax, int ay, int az, boolean drawAllSolid, Vec3 eye) {
        if (volume == null || origin == null || event == null) {
            return;
        }
        PoseStack stack = event.getModelViewStack();
        double vx = IRenderer.renderManager.renderPosX();
        double vy = IRenderer.renderManager.renderPosY();
        double vz = IRenderer.renderManager.renderPosZ();
        float ox = (float) (origin.getX() - vx);
        float oy = (float) (origin.getY() - vy);
        float oz = (float) (origin.getZ() - vz);

        if (blockFormat == null) {
            try {
                blockFormat = ChunkSectionLayer.SOLID.vertexFormat() == DefaultVertexFormat.BLOCK;
            } catch (Throwable ignored) {
                blockFormat = false;
            }
        }
        if (blockFormat) {
            drawQuads(stack, volume, baseline, ox, oy, oz, origin, drawAllSolid, eye);
        }

        BufferBuilder lines = IRenderer.startLines(new Color(79, 209, 197), 0.9f);
        IRenderer.emitAABB(lines, stack, new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + volume.sizeX(), origin.getY() + volume.sizeY(), origin.getZ() + volume.sizeZ()), 1.5f);
        if (selection != null) {
            IRenderer.glColor(new Color(255, 196, 64), 0.95f);
            IRenderer.emitAABB(lines, stack, new AABB(
                    origin.getX() + selection.x0, origin.getY() + selection.y0, origin.getZ() + selection.z0,
                    origin.getX() + selection.x1 + 1, origin.getY() + selection.y1 + 1, origin.getZ() + selection.z1 + 1), 2f);
        }
        if (anchor) {
            IRenderer.glColor(new Color(120, 190, 255), 0.95f);
            IRenderer.emitAABB(lines, stack, new AABB(
                    origin.getX() + ax, origin.getY() + ay, origin.getZ() + az,
                    origin.getX() + ax + 1, origin.getY() + ay + 1, origin.getZ() + az + 1), 2f);
        }
        int deletions = 0;
        if (baseline != null) {
            IRenderer.glColor(new Color(220, 72, 72), 0.85f);
            for (int y = 0; y < volume.sizeY() && deletions < 2000; y++) {
                for (int z = 0; z < volume.sizeZ() && deletions < 2000; z++) {
                    for (int x = 0; x < volume.sizeX() && deletions < 2000; x++) {
                        if (volume.get(x, y, z) == 0 && baseline.get(x, y, z) != 0) {
                            IRenderer.emitAABB(lines, stack, new AABB(
                                    origin.getX() + x, origin.getY() + y, origin.getZ() + z,
                                    origin.getX() + x + 1, origin.getY() + y + 1, origin.getZ() + z + 1), 1.25f);
                            deletions++;
                        }
                    }
                }
            }
        }
        IRenderer.endLines(lines, false);
    }

    private static void drawQuads(PoseStack stack, StudioVolume volume, StudioVolume baseline,
                                  float ox, float oy, float oz, BlockPos origin, boolean drawAllSolid, Vec3 eye) {
        // One staged draw at a time. Collect first, then emit each atlas layer and finish it.
        int[] packed = new int[MAX_FACES * 4];
        int n = 0;
        for (int y = 0; y < volume.sizeY() && n < MAX_FACES; y++) {
            for (int z = 0; z < volume.sizeZ() && n < MAX_FACES; z++) {
                for (int x = 0; x < volume.sizeX() && n < MAX_FACES; x++) {
                    int cell = volume.get(x, y, z);
                    if (cell == 0) {
                        continue;
                    }
                    if (!drawAllSolid && baseline != null && baseline.get(x, y, z) == cell) {
                        continue;
                    }
                    for (int face = 0; face < 6 && n < MAX_FACES; face++) {
                        if (volume.get(x + DX[face], y + DY[face], z + DZ[face]) != 0) {
                            continue;
                        }
                        double cx = origin.getX() + x + 0.5 + DX[face] * 0.5;
                        double cy = origin.getY() + y + 0.5 + DY[face] * 0.5;
                        double cz = origin.getZ() + z + 0.5 + DZ[face] * 0.5;
                        if (eye != null && DX[face] * (eye.x - cx) + DY[face] * (eye.y - cy) + DZ[face] * (eye.z - cz) <= 0) {
                            continue;
                        }
                        int at = n * 4;
                        packed[at] = x;
                        packed[at + 1] = y;
                        packed[at + 2] = z;
                        packed[at + 3] = face | (cell << 3);
                        n++;
                    }
                }
            }
        }
        PoseStack.Pose pose = stack.last();
        int alpha = drawAllSolid ? 255 : 235;
        for (int layer = 0; layer < 3; layer++) {
            BufferBuilder buffer = null;
            int count = 0;
            for (int i = 0; i < n; i++) {
                int at = i * 4;
                int face = packed[at + 3] & 7;
                int cell = packed[at + 3] >>> 3;
                BlockTextures.FacePaint paint = BlockTextures.paint(cell, face);
                if (paint == null || !paint.textured() || layerIndex(paint.layer) != layer) {
                    continue;
                }
                if (buffer == null) {
                    buffer = IRenderer.startBlockQuads();
                }
                int tint = paint.tint;
                int color = ((alpha * ((tint >>> 24) & 0xFF) / 255) << 24) | (tint & 0xFFFFFF);
                emitFace(buffer, pose, ox + packed[at], oy + packed[at + 1], oz + packed[at + 2], face, paint, color);
                count++;
            }
            if (buffer != null && count > 0) {
                IRenderer.endBuffer(buffer, renderType(layer));
            }
        }
    }

    private static int layerIndex(ChunkSectionLayer layer) {
        if (layer == ChunkSectionLayer.CUTOUT) {
            return 1;
        }
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            return 2;
        }
        return 0;
    }

    private static RenderType renderType(int layer) {
        if (layer == 1) {
            return RenderTypes.cutoutMovingBlock();
        }
        if (layer == 2) {
            return RenderTypes.translucentMovingBlock();
        }
        return RenderTypes.solidMovingBlock();
    }

    private static void emitFace(BufferBuilder buffer, PoseStack.Pose pose, float x, float y, float z,
                                 int face, BlockTextures.FacePaint paint, int color) {
        float g = 0.02f;
        float u0 = paint.u0;
        float u1 = paint.u1;
        float v0 = paint.v0;
        float v1 = paint.v1;
        float[][] corner = switch (face) {
            case 0 -> new float[][]{{1 + g, -g, -g, u0, v1}, {1 + g, -g, 1 + g, u1, v1}, {1 + g, 1 + g, 1 + g, u1, v0}, {1 + g, 1 + g, -g, u0, v0}};
            case 1 -> new float[][]{{-g, -g, 1 + g, u0, v1}, {-g, -g, -g, u1, v1}, {-g, 1 + g, -g, u1, v0}, {-g, 1 + g, 1 + g, u0, v0}};
            case 2 -> new float[][]{{-g, 1 + g, -g, u0, v0}, {1 + g, 1 + g, -g, u1, v0}, {1 + g, 1 + g, 1 + g, u1, v1}, {-g, 1 + g, 1 + g, u0, v1}};
            case 3 -> new float[][]{{-g, -g, 1 + g, u0, v0}, {1 + g, -g, 1 + g, u1, v0}, {1 + g, -g, -g, u1, v1}, {-g, -g, -g, u0, v1}};
            case 4 -> new float[][]{{-g, -g, 1 + g, u0, v1}, {1 + g, -g, 1 + g, u1, v1}, {1 + g, 1 + g, 1 + g, u1, v0}, {-g, 1 + g, 1 + g, u0, v0}};
            default -> new float[][]{{1 + g, -g, -g, u0, v1}, {-g, -g, -g, u1, v1}, {-g, 1 + g, -g, u1, v0}, {1 + g, 1 + g, -g, u0, v0}};
        };
        float nx = DX[face];
        float ny = DY[face];
        float nz = DZ[face];
        emitQuad(buffer, pose, x, y, z, corner, 0, 1, 2, 3, color, nx, ny, nz);
        emitQuad(buffer, pose, x, y, z, corner, 0, 3, 2, 1, color, -nx, -ny, -nz);
    }

    private static void emitQuad(BufferBuilder buffer, PoseStack.Pose pose, float x, float y, float z,
                                 float[][] corner, int a, int b, int c, int d, int color, float nx, float ny, float nz) {
        vert(buffer, pose, x, y, z, corner[a], color, nx, ny, nz);
        vert(buffer, pose, x, y, z, corner[b], color, nx, ny, nz);
        vert(buffer, pose, x, y, z, corner[c], color, nx, ny, nz);
        vert(buffer, pose, x, y, z, corner[d], color, nx, ny, nz);
    }

    private static void vert(BufferBuilder buffer, PoseStack.Pose pose, float x, float y, float z,
                             float[] corner, int color, float nx, float ny, float nz) {
        IRenderer.emitTexturedVertex(buffer, pose, x + corner[0], y + corner[1], z + corner[2], color, corner[3], corner[4], nx, ny, nz);
    }
}
