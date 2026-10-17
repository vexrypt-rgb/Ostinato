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

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real face textures for a block-state id. The GUI samples {@link FacePaint#pixels}.
 * The world preview uses the sprite's atlas UVs. A missing model falls back to the map colour.
 */
public final class BlockTextures {

    private static final Direction[] FACES = {
            Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH
    };
    private static final int PLAINS_GRASS = 0xFF91BD59;
    private static final Map<Long, FacePaint> CACHE = new HashMap<>();
    private static Field imageField;

    /** One face. {@code pixels} is 16x16 ARGB, already tinted, or null when only the map colour is known. */
    public static final class FacePaint {
        public final int[] pixels;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;
        public final ChunkSectionLayer layer;
        public final int tint;
        public final int flat;

        FacePaint(int[] pixels, float u0, float v0, float u1, float v1, ChunkSectionLayer layer, int tint, int flat) {
            this.pixels = pixels;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.layer = layer == null ? ChunkSectionLayer.SOLID : layer;
            this.tint = tint;
            this.flat = flat;
        }

        public boolean textured() {
            return u1 > u0 && v1 > v0;
        }
    }

    private BlockTextures() {}

    public static FacePaint paint(int cell, int face) {
        if (cell <= 0) {
            return null;
        }
        int f = Math.max(0, Math.min(FACES.length - 1, face));
        long key = ((long) cell << 3) | f;
        FacePaint cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        FacePaint painted = load(cell, f);
        if (painted != null) {
            CACHE.put(key, painted);
        }
        return painted;
    }

    private static FacePaint load(int cell, int face) {
        int flat = BlockPalette.argb(cell);
        BlockState state = BlockPalette.stateOf(cell);
        if (state == null) {
            return new FacePaint(null, 0, 0, 0, 0, ChunkSectionLayer.SOLID, 0xFFFFFFFF, flat);
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) {
                return null;
            }
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
            if (model == null) {
                return new FacePaint(null, 0, 0, 0, 0, ChunkSectionLayer.SOLID, 0xFFFFFFFF, flat);
            }
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(cell), parts);
            TextureAtlasSprite sprite = null;
            ChunkSectionLayer layer = ChunkSectionLayer.SOLID;
            boolean tinted = false;
            Direction direction = FACES[face];
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(direction);
                if (quads.isEmpty()) {
                    quads = part.getQuads(null);
                }
                if (!quads.isEmpty()) {
                    BakedQuad quad = quads.get(0);
                    sprite = quad.materialInfo().sprite();
                    layer = quad.materialInfo().layer();
                    tinted = quad.materialInfo().isTinted();
                    break;
                }
            }
            if (sprite == null && model.particleMaterial() != null) {
                sprite = model.particleMaterial().sprite();
            }
            if (sprite == null) {
                return new FacePaint(null, 0, 0, 0, 0, layer, 0xFFFFFFFF, flat);
            }
            int tint = tinted ? PLAINS_GRASS : 0xFFFFFFFF;
            int[] pixels = read(sprite, tint);
            return new FacePaint(pixels, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), layer, tint, flat);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] read(TextureAtlasSprite sprite, int tint) {
        try {
            SpriteContents contents = sprite.contents();
            if (contents == null) {
                return null;
            }
            if (imageField == null) {
                imageField = SpriteContents.class.getDeclaredField("originalImage");
                imageField.setAccessible(true);
            }
            Object image = imageField.get(contents);
            if (!(image instanceof NativeImage nativeImage)) {
                return null;
            }
            int w = nativeImage.getWidth();
            int h = nativeImage.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            int[] pixels = new int[256];
            int opaque = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int argb = nativeImage.getPixel(x * w / 16, y * h / 16);
                    argb = multiply(argb, tint);
                    pixels[y * 16 + x] = argb;
                    if ((argb >>> 24) != 0) {
                        opaque++;
                    }
                }
            }
            return opaque == 0 ? null : pixels;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int multiply(int argb, int tint) {
        if (tint == 0xFFFFFFFF) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int r = ((argb >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255;
        int g = ((argb >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
