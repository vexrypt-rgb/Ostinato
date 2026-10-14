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
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;

import java.awt.Color;

/**
 * Ostinato: draws what {@link PathViz} recorded from the kinematic and physics movers, with Baritone's line renderer.
 * Each view is behind its own render setting; everything goes into one line buffer per frame (colours are per vertex),
 * and every list it walks is bounded, so the cost per frame is a few thousand line vertices at most.
 */
public final class PathVizRenderer implements IRenderer {

    private PathVizRenderer() {}

    /** Per-tick views (corridor, rollouts) disappear this long after the controller last drove. */
    private static final long LIVE_MS = 600;
    private static final long MARK_MS = 30_000;
    private static final long TREE_MS = 15_000;
    private static final int GLYPH_TICKS = 200;
    private static final double SPEED_MAX = 7.1; // m/s, sprint-jump peak: the red end of the ramp

    private static double vx, vy, vz;
    private static BufferBuilder bb;
    private static PoseStack stack;
    private static float width;

    public static void render(PoseStack poseStack) {
        PathViz v = PathViz.get();
        boolean corridor = v.corridorOn(), rollouts = v.rolloutsOn(), jumps = v.jumpsOn();
        boolean speed = v.speedOn(), inputs = v.inputsOn(), search = v.searchOn();
        if (!(corridor || rollouts || jumps || speed || inputs || search)) {
            return;
        }
        long now = v.now();
        boolean drawCorridor = corridor && v.lineCount > 1 && now - v.lineStamp < LIVE_MS;
        boolean drawRollouts = rollouts && v.rolloutCount > 0 && now - v.rolloutStamp < LIVE_MS;
        boolean drawJumps = jumps && (v.jumps.size() > 0 || v.stuck.size() > 0);
        PathViz.Sample last = v.trail.newest();
        boolean trailLive = last != null && now - last.stamp < MARK_MS;
        boolean drawSpeed = speed && trailLive;
        boolean drawInputs = inputs && trailLive;
        boolean drawSearch = search && (v.treeCount > 0 && now - v.treeStamp < TREE_MS || v.plannedCount > 0 && trailLive || v.replans.size() > 0);
        if (!(drawCorridor || drawRollouts || drawJumps || drawSpeed || drawInputs || drawSearch)) {
            return;
        }
        vx = renderManager.renderPosX();
        vy = renderManager.renderPosY();
        vz = renderManager.renderPosZ();
        stack = poseStack;
        width = settings.pathRenderLineWidthPixels.value;
        bb = IRenderer.startLines(Color.WHITE, 1F);
        if (drawSearch) {
            search(v, now);
        }
        if (drawCorridor) {
            corridor(v);
        }
        if (drawRollouts) {
            rollouts(v);
        }
        if (drawJumps) {
            jumps(v, now);
        }
        if (drawSpeed) {
            speedTrail(v);
        }
        if (drawInputs) {
            inputs(v, now);
        }
        IRenderer.endLines(bb, settings.renderPathIgnoreDepth.value);
        bb = null;
        stack = null;
    }

    // ---------------------------------------------------------------- K1

    private static void corridor(PathViz v) {
        int n = v.lineCount;
        double[] l = v.line;
        double r = Math.max(0.4, v.lineWidth * 0.65);
        int next = v.nextNode();
        double px = 0, py = 0, pz = 0, pSideX = 0, pSideZ = 0;
        for (int i = 0; i < n; i++) {
            double x = l[i * 4], y = l[i * 4 + 1], z = l[i * 4 + 2], s = l[i * 4 + 3];
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double tx = l[b * 4] - l[a * 4], tz = l[b * 4 + 2] - l[a * 4 + 2];
            double tl = Math.hypot(tx, tz);
            double sideX = tl < 1e-6 ? 1 : -tz / tl, sideZ = tl < 1e-6 ? 0 : tx / tl;
            boolean walked = s < v.lineS;
            double cy = y + 0.9;
            if (walked) {
                col(0.35F, 0.35F, 0.35F, 0.35F);
            } else {
                col(1F, 0.84F, 0.31F, 0.45F);
            }
            ring(x, cy, z, sideX, sideZ, r, 12);
            if (i > 0) {
                // the block path on the floor and the two rails of the tube
                if (walked) {
                    col(0.33F, 0.33F, 0.33F, 0.6F);
                } else {
                    col(0.31F, 0.76F, 0.97F, 0.7F);
                }
                line(px, py + 0.05, pz, x, y + 0.05, z, 2);
                if (walked) {
                    col(0.35F, 0.35F, 0.35F, 0.3F);
                } else {
                    col(1F, 0.84F, 0.31F, 0.45F);
                }
                line(px + pSideX * r, py + 0.9, pz + pSideZ * r, x + sideX * r, cy, z + sideZ * r, 1);
                line(px - pSideX * r, py + 0.9, pz - pSideZ * r, x - sideX * r, cy, z - sideZ * r, 1);
            }
            px = x;
            py = y;
            pz = z;
            pSideX = sideX;
            pSideZ = sideZ;
        }
        if (next >= 0) {
            double x = l[next * 4], y = l[next * 4 + 1], z = l[next * 4 + 2];
            col(0.41F, 0.94F, 0.68F, 0.95F);
            box(x - 0.2, y, z - 0.2, x + 0.2, y + 0.4, z + 0.2);
            line(x, y + 0.4, z, x, y + 1.6, z, 2);
        }
    }

    // ---------------------------------------------------------------- K2

    private static void rollouts(PathViz v) {
        // losers first so the chosen one is drawn on top
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < v.rolloutCount; i++) {
                PathViz.Rollout r = v.rollout(i);
                if (r.chosen != (pass == 1) || r.n < 1) {
                    continue;
                }
                float w;
                if (r.chosen) {
                    col(0.41F, 0.94F, 0.68F, 0.95F);
                    w = 2.5F;
                } else if (r.rejected()) {
                    col(0.94F, 0.33F, 0.31F, 0.55F);
                    w = 1;
                } else {
                    col(1F, 0.6F, 0F, 0.55F);
                    w = 1;
                }
                float[] p = r.pts;
                for (int k = 1; k < r.n; k++) {
                    line(p[k * 3 - 3], p[k * 3 - 2] + 0.1, p[k * 3 - 1], p[k * 3], p[k * 3 + 1] + 0.1, p[k * 3 + 2], w);
                }
                int e = (r.n - 1) * 3;
                if (r.chosen) {
                    for (int k = 0; k < r.n; k++) {
                        cross(p[k * 3], p[k * 3 + 1] + 0.1, p[k * 3 + 2], 0.06);
                    }
                } else if (r.rejected()) {
                    xMark(p[e], p[e + 1] + 0.1, p[e + 2], 0.18);
                }
            }
        }
    }

    // ---------------------------------------------------------------- K3

    private static void jumps(PathViz v, long now) {
        for (int i = 0; i < v.jumps.size(); i++) {
            PathViz.JumpMark m = v.jumps.get(i);
            float f = fade(now - m.stamp);
            if (f <= 0) {
                continue;
            }
            boolean climb = m.kind == PathViz.CLIMB;
            if (climb) {
                col(0.96F, 0.56F, 0.69F, f);
            } else {
                col(1F, 0.93F, 0.35F, f);
            }
            circle(m.tx, m.ty + 0.03, m.tz, 0.4, 14);
            line(m.tx, m.ty, m.tz, m.tx, m.ty + 0.5, m.tz, 2);
            // apex: a dotted drop from the top of the arc to take-off height, and a tick at the top
            col(1F, 1F, 1F, f * 0.8F);
            dotted(m.ax, m.ty, m.az, m.ax, m.ay, m.az, 0.12);
            cross(m.ax, m.ay, m.az, 0.12);
            if (m.landed) {
                if (m.grabbed) {
                    col(0.96F, 0.56F, 0.69F, f);
                } else {
                    col(0.15F, 0.78F, 0.85F, f);
                }
                circle(m.lx, m.ly + 0.03, m.lz, 0.4, 14);
                col(0.7F, 0.62F, 0.86F, f * 0.6F);
                line(m.tx, m.ty + 0.05, m.tz, m.ax, m.ay + 0.05, m.az, 1);
                line(m.ax, m.ay + 0.05, m.az, m.lx, m.ly + 0.05, m.lz, 1);
            } else {
                col(0.7F, 0.62F, 0.86F, f * 0.6F);
                line(m.tx, m.ty + 0.05, m.tz, m.ax, m.ay + 0.05, m.az, 1);
            }
        }
        for (int i = 0; i < v.stuck.size(); i++) {
            PathViz.Mark m = v.stuck.get(i);
            float f = fade(now - m.stamp);
            if (f <= 0) {
                continue;
            }
            col(1F, 0.32F, 0.32F, f);
            line(m.x, m.y, m.z, m.x, m.y + 2.6, m.z, 3);
            double t = m.y + 2.6;
            line(m.x - 0.3, t, m.z, m.x + 0.3, t, m.z, 2);
            line(m.x - 0.3, t, m.z, m.x, t + 0.5, m.z, 2);
            line(m.x + 0.3, t, m.z, m.x, t + 0.5, m.z, 2);
            dashedBox(m.x - 0.3, m.y, m.z - 0.3, m.x + 0.3, m.y + 1.8, m.z + 0.3);
        }
    }

    // ---------------------------------------------------------------- P1

    private static void speedTrail(PathViz v) {
        int n = v.trail.size();
        for (int i = 0; i < n; i++) {
            PathViz.Sample s = v.trail.get(i);
            speedColor(s.hspeed * 20, 0.9F);
            if (i > 0) {
                PathViz.Sample p = v.trail.get(i - 1);
                line(p.x, p.y + 0.1, p.z, s.x, s.y + 0.1, s.z, 2.5F);
            }
            double h = i % 20 == 0 ? 0.35 : 0.12;
            line(s.x, s.y + 0.1 - h / 2, s.z, s.x, s.y + 0.1 + h / 2, s.z, i % 20 == 0 ? 2 : 1);
        }
    }

    static void speedColor(double metresPerSecond, float alpha) {
        float t = (float) Math.max(0, Math.min(1, metresPerSecond / SPEED_MAX));
        int rgb = Color.HSBtoRGB(0.66F * (1 - t), 1F, 1F);
        col(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, alpha);
    }

    // ---------------------------------------------------------------- P2

    private static void inputs(PathViz v, long now) {
        int n = v.trail.size();
        for (int i = Math.max(0, n - GLYPH_TICKS); i < n; i++) {
            PathViz.Sample s = v.trail.get(i);
            double fx = -Math.sin(Math.toRadians(s.yaw)), fz = Math.cos(Math.toRadians(s.yaw));
            double gy = s.y + 2.1;
            if ((s.inputs & PathViz.IN_W) != 0) {
                col(0.88F, 0.88F, 0.88F, 0.8F);
                line(s.x, gy, s.z, s.x + fx * 0.25, gy, s.z + fz * 0.25, 1.5F);
            }
            if ((s.inputs & PathViz.IN_SPRINT) != 0) {
                col(1F, 0.6F, 0F, 0.8F);
                line(s.x, gy + 0.06, s.z, s.x + fx * 0.25, gy + 0.06, s.z + fz * 0.25, 1.5F);
            }
            if ((s.inputs & PathViz.IN_JUMP) != 0) {
                col(1F, 0.93F, 0.35F, 0.95F);
                line(s.x, gy, s.z, s.x, gy + 0.35, s.z, 2);
            }
            if ((s.inputs & (PathViz.IN_LEFT | PathViz.IN_RIGHT)) != 0) {
                double sx = (s.inputs & PathViz.IN_LEFT) != 0 ? fz : -fz, sz = (s.inputs & PathViz.IN_LEFT) != 0 ? -fx : fx;
                col(0.81F, 0.58F, 0.85F, 0.9F);
                line(s.x, gy - 0.06, s.z, s.x + sx * 0.25, gy - 0.06, s.z + sz * 0.25, 1.5F);
            }
        }
        for (int i = 0; i < v.ghosts.size(); i++) {
            PathViz.Ghost g = v.ghosts.get(i);
            float f = fade(now - g.stamp) * 0.85F;
            if (f <= 0) {
                continue;
            }
            switch (g.kind) {
                case PathViz.GHOST_JUMP: col(1F, 0.93F, 0.35F, f); break;
                case PathViz.GHOST_APEX: col(1F, 1F, 1F, f); break;
                case PathViz.GHOST_LAND: col(0.15F, 0.78F, 0.85F, f); break;
                default: col(0.96F, 0.56F, 0.69F, f); break;
            }
            box(g.x - 0.3, g.y, g.z - 0.3, g.x + 0.3, g.y + 1.8, g.z + 0.3);
        }
    }

    // ---------------------------------------------------------------- P3

    private static void search(PathViz v, long now) {
        if (now - v.treeStamp < TREE_MS) {
            float[] t = v.tree;
            float max = v.treeMaxDepth + 1;
            for (int i = 0; i < v.treeCount; i++) {
                int o = i * 7;
                float a = 0.03F + 0.22F * (1F - t[o + 6] / max);
                col(0.6F, 0.7F, 0.85F, a);
                line(t[o], t[o + 1] + 0.05, t[o + 2], t[o + 3], t[o + 4] + 0.05, t[o + 5], 1);
            }
        }
        double[] p = v.planned, q = v.actual;
        col(0.31F, 0.86F, 0.92F, 0.9F);
        for (int i = 1; i < v.plannedCount; i++) {
            line(p[i * 3 - 3], p[i * 3 - 2] + 0.15, p[i * 3 - 1], p[i * 3], p[i * 3 + 1] + 0.15, p[i * 3 + 2], 2);
        }
        col(1F, 1F, 1F, 0.9F);
        for (int i = 1; i < v.actualCount; i++) {
            line(q[i * 3 - 3], q[i * 3 - 2] + 0.15, q[i * 3 - 1], q[i * 3], q[i * 3 + 1] + 0.15, q[i * 3 + 2], 2);
        }
        for (int i = 0; i < Math.min(v.actualCount, v.plannedCount); i++) {
            double e = v.error(i);
            if (e > PathViz.ERROR_RED) {
                col(1F, 0.25F, 0.25F, 0.95F);
            } else {
                float k = (float) (e / PathViz.ERROR_RED);
                col(0.4F + 0.6F * k, 0.9F - 0.4F * k, 0.4F, 0.6F);
            }
            line(p[i * 3], p[i * 3 + 1] + 0.15, p[i * 3 + 2], q[i * 3], q[i * 3 + 1] + 0.15, q[i * 3 + 2], e > PathViz.ERROR_RED ? 2 : 1);
        }
        for (int i = 0; i < v.replans.size(); i++) {
            PathViz.Mark m = v.replans.get(i);
            float f = fade(now - m.stamp);
            if (f <= 0) {
                continue;
            }
            col(0.88F, 0.25F, 0.98F, f);
            double y = m.y + 1.0;
            // a diamond on a stalk
            line(m.x, m.y, m.z, m.x, y, m.z, 1);
            line(m.x, y, m.z, m.x + 0.25, y + 0.25, m.z, 2);
            line(m.x + 0.25, y + 0.25, m.z, m.x, y + 0.5, m.z, 2);
            line(m.x, y + 0.5, m.z, m.x - 0.25, y + 0.25, m.z, 2);
            line(m.x - 0.25, y + 0.25, m.z, m.x, y, m.z, 2);
        }
    }

    // ---------------------------------------------------------------- primitives

    private static float fade(long age) {
        if (age >= MARK_MS) {
            return 0;
        }
        return age < MARK_MS / 2 ? 0.95F : 0.95F * (1F - (age - MARK_MS / 2F) / (MARK_MS / 2F));
    }

    private static void col(float r, float g, float b, float a) {
        IRenderer.color[0] = r;
        IRenderer.color[1] = g;
        IRenderer.color[2] = b;
        IRenderer.color[3] = a;
    }

    private static void line(double x1, double y1, double z1, double x2, double y2, double z2, float w) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return;
        }
        IRenderer.emitLine(bb, stack, x1 - vx, y1 - vy, z1 - vz, x2 - vx, y2 - vy, z2 - vz, dx / len, dy / len, dz / len, width * w * 0.5F);
    }

    private static void cross(double x, double y, double z, double s) {
        line(x - s, y, z, x + s, y, z, 1);
        line(x, y - s, z, x, y + s, z, 1);
        line(x, y, z - s, x, y, z + s, 1);
    }

    private static void xMark(double x, double y, double z, double s) {
        line(x - s, y - s, z - s, x + s, y + s, z + s, 2);
        line(x - s, y + s, z - s, x + s, y - s, z + s, 2);
        line(x - s, y - s, z + s, x + s, y + s, z - s, 2);
    }

    /** A vertical ring around (x, y, z), across the direction whose sideways unit vector is (sideX, sideZ). */
    private static void ring(double x, double y, double z, double sideX, double sideZ, double r, int seg) {
        double lx = x + sideX * r, ly = y, lz = z + sideZ * r;
        for (int k = 1; k <= seg; k++) {
            double a = 2 * Math.PI * k / seg, c = Math.cos(a) * r, s = Math.sin(a) * r;
            double nx = x + sideX * c, ny = y + s, nz = z + sideZ * c;
            line(lx, ly, lz, nx, ny, nz, 1);
            lx = nx;
            ly = ny;
            lz = nz;
        }
    }

    private static void circle(double x, double y, double z, double r, int seg) {
        double lx = x + r, lz = z;
        for (int k = 1; k <= seg; k++) {
            double a = 2 * Math.PI * k / seg;
            double nx = x + Math.cos(a) * r, nz = z + Math.sin(a) * r;
            line(lx, y, lz, nx, y, nz, 2);
            lx = nx;
            lz = nz;
        }
    }

    private static void dotted(double x1, double y1, double z1, double x2, double y2, double z2, double dash) {
        double len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int n = (int) Math.min(40, len / dash);
        for (int k = 0; k < n; k += 2) {
            double a = (double) k / n, b = (double) (k + 1) / n;
            line(x1 + (x2 - x1) * a, y1 + (y2 - y1) * a, z1 + (z2 - z1) * a, x1 + (x2 - x1) * b, y1 + (y2 - y1) * b, z1 + (z2 - z1) * b, 1);
        }
    }

    private static void box(double x1, double y1, double z1, double x2, double y2, double z2) {
        IRenderer.emitAABB(bb, stack, new AABB(x1, y1, z1, x2, y2, z2), width * 0.5F);
    }

    private static void dashedBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        double[][] e = {
                {x1, y1, z1, x2, y1, z1}, {x2, y1, z1, x2, y1, z2}, {x2, y1, z2, x1, y1, z2}, {x1, y1, z2, x1, y1, z1},
                {x1, y2, z1, x2, y2, z1}, {x2, y2, z1, x2, y2, z2}, {x2, y2, z2, x1, y2, z2}, {x1, y2, z2, x1, y2, z1},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1}, {x2, y1, z2, x2, y2, z2}, {x1, y1, z2, x1, y2, z2}};
        for (double[] d : e) {
            dotted(d[0], d[1], d[2], d[3], d[4], d[5], 0.15);
        }
    }
}
