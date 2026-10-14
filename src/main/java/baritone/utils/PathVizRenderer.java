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

import baritone.pathing.viz.PathViz;
import baritone.pathing.viz.PathVizDraw;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import java.awt.Color;

/**
 * Ostinato: feeds the segments {@link PathVizDraw} builds from {@link PathViz} to Baritone's line renderer. Physics
 * views (P1-P3) honour renderPathIgnoreDepth; the kinematic ones (K1-K3) are drawn on top of the world.
 */
public final class PathVizRenderer implements IRenderer {

    private PathVizRenderer() {}

    private static double vx, vy, vz;
    private static BufferBuilder bb;
    private static PoseStack stack;
    private static float width;

    private static final PathVizDraw.Sink SINK = (x1, y1, z1, x2, y2, z2, r, g, b, a, w) -> {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        IRenderer.color[0] = r;
        IRenderer.color[1] = g;
        IRenderer.color[2] = b;
        IRenderer.color[3] = a;
        IRenderer.emitLine(bb, stack, x1 - vx, y1 - vy, z1 - vz, x2 - vx, y2 - vy, z2 - vz, dx / len, dy / len, dz / len, width * w * 0.5F);
    };

    public static void render(PoseStack poseStack) {
        PathViz v = PathViz.get();
        if (!(v.corridorOn() || v.rolloutsOn() || v.jumpsOn() || v.physOn())) {
            return;
        }
        long now = v.now();
        boolean phys = PathVizDraw.hasPhys(v, now), kin = PathVizDraw.hasKin(v, now);
        if (!phys && !kin) {
            return;
        }
        vx = renderManager.renderPosX();
        vy = renderManager.renderPosY();
        vz = renderManager.renderPosZ();
        stack = poseStack;
        width = settings.pathRenderLineWidthPixels.value;
        if (phys) {
            bb = IRenderer.startLines(Color.WHITE, 1F);
            PathVizDraw.drawPhys(v, now, vx, vz, SINK);
            IRenderer.endLines(bb, settings.renderPathIgnoreDepth.value);
        }
        if (kin) {
            bb = IRenderer.startLines(Color.WHITE, 1F);
            PathVizDraw.drawKin(v, now, SINK);
            IRenderer.endLines(bb, true);
        }
        bb = null;
        stack = null;
    }
}
