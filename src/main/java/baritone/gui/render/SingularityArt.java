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


package baritone.gui.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;

/**
 * Pictures of the singularity theme. The black hole (16 frames of its disk turning, in a 4x4 atlas) and a faint
 * nebula are computed once, off the render thread, the first time anything asks for them, then uploaded as two
 * linear-filtered textures. A frame of the menu then costs two blits and the star quads, not a ray trace.
 * Until the textures are up the black hole is drawn as a plain disc.
 */
public final class SingularityArt {

    /** One frame of the black hole is FRAME pixels square and spans [-SPAN, SPAN] shadow radii. */
    public static final int FRAME = 256, GRID = 4, FRAMES = GRID * GRID;
    public static final float SPAN = 3.4f;
    private static final int NEB_W = 192, NEB_H = 108;
    private static final float RIN = 1.55f, ROUT = 3.0f, INC = 0.17f;
    private static final Identifier HOLE = Identifier.fromNamespaceAndPath("baritone", "ostinato/singularity_hole");
    private static final Identifier NEBULA = Identifier.fromNamespaceAndPath("baritone", "ostinato/singularity_nebula");

    private static CompletableFuture<int[][]> baking;
    private static boolean uploaded, failed;
    /** Star field: x, y in [0,1), brightness, twinkle phase, tint. */
    private static final float[][] STARS = stars(150);

    private SingularityArt() {}

    // ---------------------------------------------------------------- baking (pure, any thread)

    /** Returns {atlas ARGB, nebula ARGB}. */
    static int[][] bake() {
        int aw = FRAME * GRID;
        int[] atlas = new int[aw * aw];
        for (int f = 0; f < FRAMES; f++) {
            int ox = (f % GRID) * FRAME, oy = (f / GRID) * FRAME;
            // the disk pattern repeats every 2pi/8, so 16 frames over that turn loop without a seam
            double turn = f / (double) FRAMES * (Math.PI * 2 / 8);
            for (int j = 0; j < FRAME; j++) {
                for (int i = 0; i < FRAME; i++) {
                    atlas[(oy + j) * aw + ox + i] = holePixel(((i + 0.5) / FRAME * 2 - 1) * SPAN, ((j + 0.5) / FRAME * 2 - 1) * SPAN, turn);
                }
            }
        }
        int[] neb = new int[NEB_W * NEB_H];
        for (int j = 0; j < NEB_H; j++) {
            for (int i = 0; i < NEB_W; i++) {
                double x = i / (double) NEB_W, y = j / (double) NEB_H;
                double n = fbm(x * 5, y * 3) , m = fbm(x * 3 + 7, y * 4 + 2);
                double band = Math.exp(-Math.pow((y - 0.35 - 0.25 * x) / 0.3, 2)); // a faint diagonal arm
                double v = Math.max(0, n - 0.42) * 1.9 * (0.35 + 0.65 * band);
                double r = 0.32 * v + 0.12 * m * v, g = 0.14 * v, b = 0.42 * v + 0.2 * (1 - m) * v;
                double a = Math.min(0.55, Math.max(r, Math.max(g, b)) * 1.3);
                neb[j * NEB_W + i] = argb(a, r / Math.max(1e-4, a * 1.6), g / Math.max(1e-4, a * 1.6), b / Math.max(1e-4, a * 1.6));
            }
        }
        return new int[][]{atlas, neb};
    }

    /** One pixel of the black hole at (x, y) shadow radii from its centre, y down, camera a little above the disk. */
    static int holePixel(double x, double y, double turn) {
        double r = Math.hypot(x, y);
        double cr = 0, cg = 0, cb = 0;
        boolean opaque = r < 1;
        if (!opaque) {
            // background stars bent by a point lens
            double k = 1 - 1.5 / (r * r);
            double st = starAt(x * k + 11, y * k + 7) * Math.min(1, (r - 1) * 2.5);
            cr += st;
            cg += st;
            cb += st * 1.1;
            double glow = Math.exp(-(r - 1) * 2.4) * 0.45;
            double ring = Math.exp(-sq((r - 1.035) / 0.03)) * 1.1;
            cr += glow + ring;
            cg += glow * 0.5 + ring * 0.92;
            cb += glow * 0.18 + ring * 0.75;
            // the far side of the disk, bent over and under the shadow: thicker above and below
            double th = Math.atan2(y, x), s = Math.abs(Math.sin(th));
            double band = (r - 1.07) / (0.32 + 0.3 * s);
            if (band > 0 && band < 1) {
                double e = disk(RIN + band * (ROUT - RIN) * 0.85, -th, turn) * (0.55 + 0.35 * s);
                double[] c = ramp(e * 1.05);
                cr += c[0] * e * 1.4;
                cg += c[1] * e * 1.4;
                cb += c[2] * e * 1.4;
            }
        }
        // the near side of the disk, seen almost edge on; it passes in front of the shadow below the centre
        double z = y / INC, rd = Math.hypot(x, z);
        boolean front = y > 0;
        if (rd >= RIN && rd <= ROUT && (front || !opaque)) {
            double e = disk(rd, Math.atan2(z, x), turn) * (1 + 0.6 * (-x / rd)); // the approaching left side is brighter
            double[] c = ramp(e * 0.95);
            if (opaque) {
                return argb(1, c[0] * e * 1.5, c[1] * e * 1.5, c[2] * e * 1.5);
            }
            cr += c[0] * e * 1.5;
            cg += c[1] * e * 1.5;
            cb += c[2] * e * 1.5;
        }
        if (opaque) {
            return 0xFF000000;
        }
        double m = Math.min(1, Math.max(cr, Math.max(cg, cb)));
        double edge = Math.max(0, Math.min(1, (SPAN - r) / 0.6));
        if (m <= 0) {
            return 0;
        }
        return argb(m * edge, cr / m, cg / m, cb / m);
    }

    private static double disk(double rr, double phi, double turn) {
        if (rr < RIN || rr > ROUT) {
            return 0;
        }
        double u = (rr - RIN) / (ROUT - RIN);
        double prof = Math.pow(1 - u, 1.6) * Math.min(1, (rr - RIN) * 6);
        double p = phi - turn;
        return prof * (0.72 + 0.16 * Math.sin(8 * p + 7 * rr) + 0.12 * Math.sin(24 * p - 5 * rr));
    }

    /** Temperature: deep red, orange, gold, white. */
    static double[] ramp(double t) {
        t = Math.max(0, Math.min(1, t));
        double[][] k = {{0, .02, .01, .02}, {.25, .55, .08, .02}, {.5, 1, .45, .1}, {.75, 1, .78, .42}, {1, 1, .97, .9}};
        for (int i = 1; i < k.length; i++) {
            if (t <= k[i][0]) {
                double[] a = k[i - 1], b = k[i];
                double u = (t - a[0]) / (b[0] - a[0]);
                return new double[]{a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, a[3] + (b[3] - a[3]) * u};
            }
        }
        return new double[]{1, .97, .9};
    }

    private static double hash(double x, double y) {
        double h = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
        return h - Math.floor(h);
    }

    private static double starAt(double x, double y) {
        double gx = Math.floor(x * 9), gy = Math.floor(y * 9);
        double h = hash(gx, gy);
        if (h < 0.93) {
            return 0;
        }
        double sx = gx + hash(gx + 3, gy), sy = gy + hash(gx, gy + 5);
        double d = Math.hypot(x * 9 - sx, y * 9 - sy);
        return Math.max(0, 1 - d * 3.2) * (h - 0.93) / 0.07;
    }

    private static double noise(double x, double y) {
        double ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
        double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
    }

    private static double fbm(double x, double y) {
        double v = 0, amp = 0.5;
        for (int o = 0; o < 4; o++) {
            v += amp * noise(x, y);
            x *= 2.03;
            y *= 2.03;
            amp *= 0.5;
        }
        return v;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int argb(double a, double r, double g, double b) {
        return (c8(a) << 24) | (c8(r) << 16) | (c8(g) << 8) | c8(b);
    }

    private static int c8(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    private static float[][] stars(int n) {
        long seed = 7;
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] s = new float[5];
            for (int k = 0; k < 5; k++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                s[k] = (seed >>> 40) / (float) (1 << 24);
            }
            s[2] = s[2] * s[2] * s[2];
            out[i] = s;
        }
        return out;
    }

    // ---------------------------------------------------------------- upload + draw (render thread)

    /** Starts the bake if needed and uploads it once it is done. True when the textures can be drawn. */
    public static boolean ready() {
        if (uploaded) {
            return true;
        }
        if (failed) {
            return false;
        }
        if (baking == null) {
            baking = CompletableFuture.supplyAsync(SingularityArt::bake);
            return false;
        }
        if (!baking.isDone()) {
            return false;
        }
        try {
            int[][] px = baking.join();
            upload(HOLE, px[0], FRAME * GRID, FRAME * GRID);
            upload(NEBULA, px[1], NEB_W, NEB_H);
            uploaded = true;
        } catch (Throwable t) {
            failed = true; // the theme still works, with plain discs
            t.printStackTrace();
        }
        return uploaded;
    }

    private static void upload(Identifier id, int[] px, int w, int h) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, px[y * w + x]);
            }
        }
        Minecraft.getInstance().getTextureManager().register(id, new LinearTexture(id.toString(), img));
    }

    /** DynamicTexture that samples linearly, so the pictures stay smooth when scaled up. */
    private static final class LinearTexture extends DynamicTexture {
        LinearTexture(String label, NativeImage img) {
            super(() -> label, img);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    /** Full-screen space: dark gradient, nebula, and twinkling stars nudged by the mouse for a little parallax. */
    public static void space(GuiGraphicsExtractor ms, float w, float h, float mx, float my, float a, long now) {
        GuiDraw.gradV(ms, 0, 0, w, h, GuiDraw.alphaOf(0x030206, Math.round(0xD8 * a)), GuiDraw.alphaOf(0x060509, Math.round(0xEC * a)));
        if (ready()) {
            blit(ms, NEBULA, 0, 0, w, h, 0, 0, NEB_W, NEB_H, NEB_W, NEB_H, a * GuiDraw.alpha);
        }
        float px = mx < 0 ? 0 : (mx - w / 2) * -0.006f, py = my < 0 ? 0 : (my - h / 2) * -0.006f;
        double t = now / 1000.0;
        for (float[] s : STARS) {
            float tw = 0.65f + 0.35f * (float) Math.sin(t * 1.3 + s[3] * 6.283);
            int alpha = Math.round((0.25f + 0.75f * s[2]) * tw * a * 255);
            if (alpha < 8) {
                continue;
            }
            int col = s[4] < 0.15f ? 0x9FB8FF : s[4] > 0.9f ? 0xFFD3A1 : 0xFFFFFF;
            float z = s[2] > 0.7f ? 1f : 0.5f;
            float x = s[0] * w + px, y = s[1] * h + py;
            GuiDraw.rect(ms, x, y, x + z, y + z, GuiDraw.alphaOf(col, alpha));
        }
    }

    /** The black hole centred on (cx, cy) with shadow radius rs. */
    public static void hole(GuiGraphicsExtractor ms, float cx, float cy, float rs, float a, long now) {
        float half = rs * SPAN;
        if (!ready()) {
            GuiDraw.round(ms, cx - rs * 1.08f, cy - rs * 1.08f, cx + rs * 1.08f, cy + rs * 1.08f, rs * 1.08f, GuiDraw.alphaOf(Skin.HAWKING, Math.round(0x90 * a)));
            GuiDraw.round(ms, cx - rs, cy - rs, cx + rs, cy + rs, rs, GuiDraw.alphaOf(0x000000, Math.round(0xFF * a)));
            return;
        }
        // 16 frames over 2.4 s; cross-fade the next frame in so the turn is smooth
        double pos = (now % 2400L) / 2400.0 * FRAMES;
        int f0 = (int) pos % FRAMES, f1 = (f0 + 1) % FRAMES;
        float mix = (float) (pos - Math.floor(pos));
        frame(ms, f0, cx - half, cy - half, half * 2, a);
        frame(ms, f1, cx - half, cy - half, half * 2, a * mix);
    }

    /** A tiny black hole for HUD headers. */
    public static void glyph(GuiGraphicsExtractor ms, float cx, float cy, float r, long now) {
        if (!ready()) {
            GuiDraw.round(ms, cx - r, cy - r, cx + r, cy + r, r, Skin.HAWKING);
            GuiDraw.round(ms, cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1, r - 1, 0xFF000000);
            return;
        }
        int f = (int) ((now % 2400L) / 2400.0 * FRAMES) % FRAMES;
        // the glyph shows the hole and the inner disk only: crop to 1.9 shadow radii
        float crop = 1.9f / SPAN;
        int cu = Math.round(FRAME * (1 - crop) / 2), cs = Math.round(FRAME * crop);
        blit(ms, HOLE, cx - r, cy - r, r * 2, r * 2, (f % GRID) * FRAME + cu, (f / GRID) * FRAME + cu, cs, cs, FRAME * GRID, FRAME * GRID, GuiDraw.alpha);
    }

    private static void frame(GuiGraphicsExtractor ms, int f, float x, float y, float size, float a) {
        blit(ms, HOLE, x, y, size, size, (f % GRID) * FRAME, (f / GRID) * FRAME, FRAME, FRAME, FRAME * GRID, FRAME * GRID, a);
    }

    /** Region (u, v, uw, vh) of a texture drawn into a fractional GUI rectangle. */
    private static void blit(GuiGraphicsExtractor ms, Identifier id, float x, float y, float w, float h, int u, int v, int uw, int vh, int tw, int th, float a) {
        int alpha = Math.round(Math.max(0, Math.min(1, a)) * 255);
        if (alpha < 4) {
            return;
        }
        ms.pose().pushMatrix();
        ms.pose().translate(x, y);
        ms.pose().scale(w / uw, h / vh);
        ms.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, uw, vh, tw, th, (alpha << 24) | 0xFFFFFF);
        ms.pose().popMatrix();
    }
}
