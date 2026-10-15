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

package baritone.pathing.viz;

import java.awt.Color;

/**
 * Ostinato: turns what {@link PathViz} recorded into coloured line segments, without touching Minecraft, so every
 * layer can be checked headless. {@code baritone.utils.PathVizRenderer} feeds the segments to Baritone's line renderer.
 * Every list walked here is bounded, so the cost per frame is a few thousand segments at most.
 */
public final class PathVizDraw {

    private PathVizDraw() {}

    /** Receives one segment in world coordinates; {@code w} multiplies the path line width. */
    public interface Sink {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a, float w);
    }

    /** Per-tick views (corridor) disappear this long after the controller last drove. */
    static final long LIVE_MS = 600;
    /** The rollout fan stays up a little longer, so the last fan before a stretch's arrival can be seen. */
    static final long FAN_MS = 1500;
    /** After a hand-back the corridor stays up this long, in the hand-back colour. */
    static final long HANDBACK_MS = 3000;
    static final long MARK_MS = 30_000;
    static final long TREE_MS = 15_000;
    static final int GLYPH_TICKS = 200;
    /** Letters only every few ticks: one per tick would overlap into a smear. */
    static final int LETTER_EVERY = 3;
    private static final double SPEED_MAX = 7.1; // m/s, sprint-jump peak: the red end of the ramp

    private static Sink sink;
    private static final float[] c = new float[4];
    /** Unit vector across the view, for glyphs that face the camera. */
    private static double camSideX = 1, camSideZ = 0;

    public static boolean corridorLive(PathViz v, long now) {
        return v.lineCount > 1 && (now - v.lineStamp < LIVE_MS || now - v.handbackStamp < HANDBACK_MS && v.handbackStamp >= v.lineStamp);
    }

    public static boolean hasKin(PathViz v, long now) {
        return v.corridorOn() && corridorLive(v, now)
                || v.rolloutsOn() && v.rolloutCount > 0 && now - v.rolloutStamp < FAN_MS
                || v.jumpsOn() && (v.jumps.size() > 0 || v.stuck.size() > 0 || v.handbacks.size() > 0);
    }

    public static boolean hasPhys(PathViz v, long now) {
        PathViz.Sample last = v.trail.newest();
        boolean trailLive = last != null && now - last.stamp < MARK_MS;
        return (v.speedOn() || v.inputsOn()) && trailLive
                || v.searchOn() && (v.treeCount > 0 && now - v.treeStamp < TREE_MS || v.plannedCount > 0 && trailLive || v.replans.size() > 0);
    }

    /** P1-P3. {@code camX, camZ}: the camera position, so letters face it. */
    public static void drawPhys(PathViz v, long now, double camX, double camZ, Sink out) {
        sink = out;
        PathViz.Sample last = v.trail.newest();
        boolean trailLive = last != null && now - last.stamp < MARK_MS;
        if (v.searchOn()) {
            search(v, now);
        }
        if (v.speedOn() && trailLive) {
            speedTrail(v);
        }
        if (v.inputsOn() && trailLive) {
            double dx = last.x - camX, dz = last.z - camZ, d = Math.hypot(dx, dz);
            if (d > 1e-3) {
                camSideX = -dz / d;
                camSideZ = dx / d;
            }
            inputs(v, now);
        }
        sink = null;
    }

    /** K1-K3. */
    public static void drawKin(PathViz v, long now, Sink out) {
        sink = out;
        if (v.corridorOn() && corridorLive(v, now)) {
            corridor(v, now);
        }
        if (v.rolloutsOn() && v.rolloutCount > 0 && now - v.rolloutStamp < FAN_MS) {
            rollouts(v);
        }
        if (v.jumpsOn()) {
            jumps(v, now);
        }
        sink = null;
    }

    // ---------------------------------------------------------------- K palette (concept sketches)

    private static final float[] CYAN = {0.0F, 0.85F, 1.0F};
    private static final float[] YELLOW = {1.0F, 0.86F, 0.1F};
    private static final float[] GREEN = {0.15F, 1.0F, 0.45F};
    private static final float[] RED = {1.0F, 0.18F, 0.15F};
    private static final float[] ORANGE = {1.0F, 0.55F, 0.0F};
    private static final float[] PINK = {1.0F, 0.45F, 0.7F};
    private static final float[] GREY = {0.55F, 0.55F, 0.55F};
    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};
    private static final float[] PURPLE = {0.75F, 0.55F, 1.0F};

    /** Pixel width multiplier of K lines over pathRenderLineWidthPixels. */
    private static final float K_WIDTH = 3.0F;

    private static void col(float[] c, float a) {
        col(c[0], c[1], c[2], a);
    }

    /**
     * A bright line with a wide faint halo under it: reads as a thick glowing stroke at a distance, and stays
     * visible when the GL line width is clamped.
     */
    private static void bold(float[] c, double x1, double y1, double z1, double x2, double y2, double z2, float w) {
        col(c, 0.28F);
        line(x1, y1, z1, x2, y2, z2, w * K_WIDTH * 2.6F);
        col(c, 0.95F);
        line(x1, y1, z1, x2, y2, z2, w * K_WIDTH);
    }

    // ---------------------------------------------------------------- K1

    private static void corridor(PathViz v, long now) {
        // after a hand-back the stretch Baritone took over stays up, red when stuck (M01), orange otherwise
        boolean handedBack = now - v.handbackStamp < HANDBACK_MS && v.handbackStamp >= v.lineStamp;
        float[] tubeC = handedBack ? (v.handbackStuck ? RED : ORANGE) : YELLOW;
        float[] pathC = handedBack ? (v.handbackStuck ? RED : ORANGE) : CYAN;
        int n = v.lineCount;
        double[] l = v.line;
        double r = Math.max(0.45, v.lineWidth * 0.65);
        int next = v.nextNode();
        double px = 0, py = 0, pz = 0, pSideX = 0, pSideZ = 0;
        for (int i = 0; i < n; i++) {
            double x = l[i * 4], y = l[i * 4 + 1], z = l[i * 4 + 2], s = l[i * 4 + 3];
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double tx = l[b * 4] - l[a * 4], tz = l[b * 4 + 2] - l[a * 4 + 2];
            double tl = Math.hypot(tx, tz);
            double sideX = tl < 1e-6 ? 1 : -tz / tl, sideZ = tl < 1e-6 ? 0 : tx / tl;
            boolean walked = s < v.lineS;
            float[] tube = walked ? GREY : tubeC;
            double cy = y + 0.9;
            // ring: a wide translucent band (the "filled" tube wall) under a bright core
            col(tube, walked ? 0.12F : 0.22F);
            ring(x, cy, z, sideX, sideZ, r, 12, K_WIDTH * 4F);
            col(tube, walked ? 0.45F : 0.95F);
            ring(x, cy, z, sideX, sideZ, r, 12, K_WIDTH);
            // node tile on the floor
            col(walked ? GREY : pathC, walked ? 0.4F : 0.9F);
            square(x, y + 0.03, z, 0.5, K_WIDTH * 0.8F);
            if (i > 0) {
                if (walked) {
                    col(GREY, 0.6F);
                    line(px, py + 0.05, pz, x, y + 0.05, z, K_WIDTH * 1.5F);
                    col(GREY, 0.45F);
                    line(px + pSideX * r, py + 0.9, pz + pSideZ * r, x + sideX * r, cy, z + sideZ * r, K_WIDTH);
                    line(px - pSideX * r, py + 0.9, pz - pSideZ * r, x - sideX * r, cy, z - sideZ * r, K_WIDTH);
                } else {
                    bold(pathC, px, py + 0.05, pz, x, y + 0.05, z, 1.6F);
                    bold(tubeC, px + pSideX * r, py + 0.9, pz + pSideZ * r, x + sideX * r, cy, z + sideZ * r, 1F);
                    bold(tubeC, px - pSideX * r, py + 0.9, pz - pSideZ * r, x - sideX * r, cy, z - sideZ * r, 1F);
                    // faint top and bottom seams fill the tube in
                    col(tubeC, 0.18F);
                    line(px, py + 0.9 + r, pz, x, cy + r, z, K_WIDTH * 2F);
                    line(px, py + 0.9 - r, pz, x, cy - r, z, K_WIDTH * 2F);
                }
            }
            px = x;
            py = y;
            pz = z;
            pSideX = sideX;
            pSideZ = sideZ;
        }
        if (next >= 0 && !handedBack) {
            double x = l[next * 4], y = l[next * 4 + 1], z = l[next * 4 + 2];
            col(GREEN, 0.3F);
            circle(x, y + 0.05, z, 0.45, 16, K_WIDTH * 4F);
            col(GREEN, 1F);
            circle(x, y + 0.05, z, 0.45, 16, K_WIDTH * 1.4F);
            bold(GREEN, x, y + 0.05, z, x, y + 1.8, z, 1.4F);
            box(x - 0.2, y, z - 0.2, x + 0.2, y + 0.4, z + 0.2);
        }
    }

    // ---------------------------------------------------------------- K2

    private static void rollouts(PathViz v) {
        // The yaw offsets only act for the first ticks, after which every candidate steers back onto the line: drawn
        // at the same height they all lay under the chosen plan and the fan was invisible. The chosen one goes first
        // now, and each loser is lifted a little more than the last so the fan fans out in height too.
        int rank = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < v.rolloutCount; i++) {
                PathViz.Rollout r = v.rollout(i);
                if (r.chosen != (pass == 0) || r.n < 1) {
                    continue;
                }
                double lift = r.chosen ? 0.1 : 0.18 + 0.07 * rank++;
                float[] c = r.chosen ? GREEN : r.rejected() ? RED : ORANGE;
                float w = r.chosen ? 1.8F : 0.9F;
                float[] p = r.pts;
                for (int k = 1; k < r.n; k++) {
                    double x1 = p[k * 3 - 3], y1 = p[k * 3 - 2] + lift, z1 = p[k * 3 - 1], x2 = p[k * 3], y2 = p[k * 3 + 1] + lift, z2 = p[k * 3 + 2];
                    if (r.chosen) {
                        bold(c, x1, y1, z1, x2, y2, z2, w);
                    } else {
                        col(c, 0.8F);
                        line(x1, y1, z1, x2, y2, z2, w * K_WIDTH);
                    }
                }
                int e = (r.n - 1) * 3;
                if (r.chosen) {
                    col(WHITE, 1F);
                    for (int k = 0; k < r.n; k++) {
                        dot(p[k * 3], p[k * 3 + 1] + lift, p[k * 3 + 2], 0.07);
                    }
                } else if (r.rejected()) { // an X where a rejected plan failed; a merely slower one has none
                    col(c, 1F);
                    xMark(p[e], p[e + 1] + lift, p[e + 2], 0.22);
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
            float[] take = m.kind == PathViz.CLIMB ? PINK : YELLOW;
            col(take, 0.3F * f);
            circle(m.tx, m.ty + 0.03, m.tz, 0.45, 16, K_WIDTH * 4F);
            col(take, f);
            circle(m.tx, m.ty + 0.03, m.tz, 0.45, 16, K_WIDTH * 1.4F);
            line(m.tx, m.ty, m.tz, m.tx, m.ty + 0.6, m.tz, K_WIDTH * 1.4F);
            // apex: a dotted drop from the top of the arc to take-off height, and a tick at the top
            col(WHITE, f);
            dotted(m.ax, m.ty, m.az, m.ax, m.ay, m.az, 0.12);
            line(m.ax - 0.2, m.ay, m.az, m.ax + 0.2, m.ay, m.az, K_WIDTH * 1.4F);
            line(m.ax, m.ay, m.az - 0.2, m.ax, m.ay, m.az + 0.2, K_WIDTH * 1.4F);
            // the flown arc, tick by tick (it used to be two straight chords, hard to tell from the corridor)
            float[] a = m.arcN > 1 ? m.arc : null;
            if (a != null) {
                for (int k = 1; k < m.arcN; k++) {
                    bold(PURPLE, a[k * 3 - 3], a[k * 3 - 2] + 0.05, a[k * 3 - 1], a[k * 3], a[k * 3 + 1] + 0.05, a[k * 3 + 2], 1.2F);
                }
            } else {
                col(PURPLE, 0.85F * f);
                line(m.tx, m.ty + 0.05, m.tz, m.ax, m.ay + 0.05, m.az, K_WIDTH);
            }
            if (m.landed) {
                float[] land = m.grabbed ? PINK : CYAN;
                col(land, 0.3F * f);
                circle(m.lx, m.ly + 0.03, m.lz, 0.45, 16, K_WIDTH * 4F);
                col(land, f);
                circle(m.lx, m.ly + 0.03, m.lz, 0.45, 16, K_WIDTH * 1.4F);
            }
        }
        for (int i = 0; i < v.handbacks.size(); i++) {
            PathViz.Mark m = v.handbacks.get(i);
            float f = fade(now - m.stamp);
            if (f <= 0) {
                continue;
            }
            // hand-back to Baritone: an orange post and ring (a stuck one gets the red marker below as well)
            bold(ORANGE, m.x, m.y, m.z, m.x, m.y + 1.6, m.z, 1.2F);
            col(ORANGE, f);
            circle(m.x, m.y + 0.03, m.z, 0.35, 12, K_WIDTH * 1.2F);
        }
        for (int i = 0; i < v.stuck.size(); i++) {
            PathViz.Mark m = v.stuck.get(i);
            float f = fade(now - m.stamp);
            if (f <= 0) {
                continue;
            }
            bold(RED, m.x, m.y, m.z, m.x, m.y + 2.6, m.z, 1.8F);
            double t = m.y + 2.6;
            col(RED, f);
            for (int k = 0; k < 3; k++) { // a filled-looking warning triangle: three nested outlines
                double s = 0.35 - k * 0.1, h = 0.6 - k * 0.15;
                line(m.x - s, t, m.z, m.x + s, t, m.z, K_WIDTH * 1.4F);
                line(m.x - s, t, m.z, m.x, t + h, m.z, K_WIDTH * 1.4F);
                line(m.x + s, t, m.z, m.x, t + h, m.z, K_WIDTH * 1.4F);
            }
            col(RED, 0.9F * f);
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
        int from = Math.max(0, n - GLYPH_TICKS);
        // the executed trajectory the glyphs sit on
        for (int i = from + 1; i < n; i++) {
            PathViz.Sample p = v.trail.get(i - 1), s = v.trail.get(i);
            bold(CYAN, p.x, p.y + 0.15, p.z, s.x, s.y + 0.15, s.z, 0.8F);
        }
        int lastInputs = -1;
        for (int i = from; i < n; i++) {
            PathViz.Sample s = v.trail.get(i);
            boolean jump = (s.inputs & PathViz.IN_JUMP) != 0;
            // letters where the keys change, on jumps, and every few ticks; a short stalk ties them to the trajectory
            if (s.inputs == lastInputs && !jump && i % LETTER_EVERY != 0) {
                continue;
            }
            lastInputs = s.inputs;
            double gy = s.y + 2.0;
            col(WHITE, 0.35F);
            line(s.x, s.y + 0.15, s.z, s.x, gy, s.z, 0.6F);
            double row = gy;
            if ((s.inputs & PathViz.IN_W) != 0) {
                col(WHITE, 0.95F);
                row = glyphRow("W", s, row);
            }
            if ((s.inputs & PathViz.IN_SPRINT) != 0) {
                col(ORANGE, 0.95F);
                row = glyphRow("S+", s, row);
            }
            if (jump) {
                col(YELLOW, 1F);
                row = glyphRow("J", s, row);
            }
            if ((s.inputs & PathViz.IN_LEFT) != 0) {
                col(PURPLE, 0.95F);
                row = glyphRow("A", s, row);
            }
            if ((s.inputs & PathViz.IN_RIGHT) != 0) {
                col(PURPLE, 0.95F);
                glyphRow("D", s, row);
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
        c[0] = r;
        c[1] = g;
        c[2] = b;
        c[3] = a;
    }

    private static void line(double x1, double y1, double z1, double x2, double y2, double z2, float w) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        if (dx * dx + dy * dy + dz * dz < 1e-12) {
            return;
        }
        sink.line(x1, y1, z1, x2, y2, z2, c[0], c[1], c[2], c[3], w);
    }

    private static void cross(double x, double y, double z, double s) {
        line(x - s, y, z, x + s, y, z, 1);
        line(x, y - s, z, x, y + s, z, 1);
        line(x, y, z - s, x, y, z + s, 1);
    }

    private static final double GLYPH = 0.2;

    private static double glyphRow(String t, PathViz.Sample s, double y) {
        text(t, s.x, y, s.z, GLYPH, camSideX, camSideZ);
        return y + GLYPH * 1.5;
    }

    /** Stroke letters, in a unit cell: x1, y1, x2, y2 per stroke. */
    private static double[] strokes(char ch) {
        switch (ch) {
            case 'W': return new double[]{0, 1, .25, 0, .25, 0, .5, .6, .5, .6, .75, 0, .75, 0, 1, 1};
            case 'S': return new double[]{1, 1, 0, 1, 0, 1, 0, .5, 0, .5, 1, .5, 1, .5, 1, 0, 1, 0, 0, 0};
            case '+': return new double[]{.15, .5, .85, .5, .5, .15, .5, .85};
            case 'J': return new double[]{0, 1, 1, 1, .6, 1, .6, 0, .6, 0, 0, 0, 0, 0, 0, .3};
            case 'A': return new double[]{0, 0, .5, 1, .5, 1, 1, 0, .25, .5, .75, .5};
            case 'D': return new double[]{0, 0, 0, 1, 0, 1, .7, 1, .7, 1, 1, .7, 1, .7, 1, .3, 1, .3, .7, 0, .7, 0, 0, 0};
            default: return null;
        }
    }

    private static final double[][] FONT = new double[128][];

    static {
        for (char ch : "WS+JAD".toCharArray()) {
            FONT[ch] = strokes(ch);
        }
    }

    /**
     * Upright stroke text centred over (x, z) with its base at y, written along the horizontal direction (dirX, dirZ).
     * Letters it has no strokes for are skipped (still take their space).
     */
    static void text(String t, double x, double y, double z, double size, double dirX, double dirZ) {
        double d = Math.hypot(dirX, dirZ);
        double ux = d < 1e-6 ? 1 : dirX / d, uz = d < 1e-6 ? 0 : dirZ / d;
        double adv = size * 1.3, x0 = -adv * t.length() / 2;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            double[] st = ch < 128 ? FONT[ch] : null;
            if (st == null) {
                continue;
            }
            double o = x0 + i * adv;
            for (int k = 0; k < st.length; k += 4) {
                double a = o + st[k] * size, b = o + st[k + 2] * size;
                line(x + ux * a, y + st[k + 1] * size, z + uz * a, x + ux * b, y + st[k + 3] * size, z + uz * b, K_WIDTH * 0.8F);
            }
        }
    }

    private static void square(double x, double y, double z, double h, float w) {
        line(x - h, y, z - h, x + h, y, z - h, w);
        line(x + h, y, z - h, x + h, y, z + h, w);
        line(x + h, y, z + h, x - h, y, z + h, w);
        line(x - h, y, z + h, x - h, y, z - h, w);
    }

    /** A tick dot: a short, very wide stroke reads as a round blob. */
    private static void dot(double x, double y, double z, double s) {
        line(x, y - s / 2, z, x, y + s / 2, z, K_WIDTH * 3F);
    }

    private static void xMark(double x, double y, double z, double s) {
        line(x - s, y, z - s, x + s, y, z + s, K_WIDTH * 2F);
        line(x - s, y, z + s, x + s, y, z - s, K_WIDTH * 2F);
        line(x, y - s, z, x, y + s, z, K_WIDTH * 2F);
    }

    /** A vertical ring around (x, y, z), across the direction whose sideways unit vector is (sideX, sideZ). */
    private static void ring(double x, double y, double z, double sideX, double sideZ, double r, int seg, float w) {
        double lx = x + sideX * r, ly = y, lz = z + sideZ * r;
        for (int k = 1; k <= seg; k++) {
            double a = 2 * Math.PI * k / seg, c = Math.cos(a) * r, s = Math.sin(a) * r;
            double nx = x + sideX * c, ny = y + s, nz = z + sideZ * c;
            line(lx, ly, lz, nx, ny, nz, w);
            lx = nx;
            ly = ny;
            lz = nz;
        }
    }

    private static void circle(double x, double y, double z, double r, int seg, float w) {
        double lx = x + r, lz = z;
        for (int k = 1; k <= seg; k++) {
            double a = 2 * Math.PI * k / seg;
            double nx = x + Math.cos(a) * r, nz = z + Math.sin(a) * r;
            line(lx, y, lz, nx, y, nz, w);
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
        line(x1, y1, z1, x2, y1, z1, 1);
        line(x2, y1, z1, x2, y1, z2, 1);
        line(x2, y1, z2, x1, y1, z2, 1);
        line(x1, y1, z2, x1, y1, z1, 1);
        line(x1, y2, z1, x2, y2, z1, 1);
        line(x2, y2, z1, x2, y2, z2, 1);
        line(x2, y2, z2, x1, y2, z2, 1);
        line(x1, y2, z2, x1, y2, z1, 1);
        line(x1, y1, z1, x1, y2, z1, 1);
        line(x2, y1, z1, x2, y2, z1, 1);
        line(x2, y1, z2, x2, y2, z2, 1);
        line(x1, y1, z2, x1, y2, z2, 1);
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
