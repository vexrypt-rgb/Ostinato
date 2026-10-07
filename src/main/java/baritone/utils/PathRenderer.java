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

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.*;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

    private PathRenderer() {}

    private static final float GOAL_BEACON_INNER_RADIUS = 0.2F;
    private static final float GOAL_BEACON_GLOW_RADIUS = 0.25F;
    private static final int GOAL_BEACON_GLOW_ALPHA = 32;

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().screen instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            // this is a path for a bot in a different dimension, don't render it
            return;
        }

        if (goal != null && settings.renderGoal.value) {
            drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
        }

        if (!settings.renderPath.value) {
            return;
        }

        PathExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
        PathExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
        if (current != null && settings.renderSelectionBoxes.value) {
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
        }

        //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);

        // Render the current path, if there is one
        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            if (settings.fancyPath.value) {
                drawFancyPath(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.colorCurrentPath.value);
            } else {
                drawPath(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.colorCurrentPath.value, settings.fadePath.value, 10, 20);
            }
        }

        if (next != null && next.getPath() != null) {
            if (settings.fancyPath.value) {
                drawFancyPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value);
            } else {
                drawPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value, settings.fadePath.value, 10, 20);
            }
        }

        // If there is a path calculation currently running, render the path calculation process
        behavior.getInProgress().ifPresent(currentlyRunning -> {
            currentlyRunning.bestPathSoFar().ifPresent(p -> {
                if (settings.fancyRender.value) {
                    drawFancyPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value);
                } else {
                    drawPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20);
                }
            });

            currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
                if (settings.fancyRender.value) {
                    drawFancyPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value);
                } else {
                    drawPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
                }
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
            });
        });
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        for (int i = startIndex, next; i < positions.size() - 1; i = next) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(next = i + 1);

            int dirX = end.x - start.x;
            int dirY = end.y - start.y;
            int dirZ = end.z - start.z;

            while (next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart) &&
                    (dirX == positions.get(next + 1).x - end.x &&
                            dirY == positions.get(next + 1).y - end.y &&
                            dirZ == positions.get(next + 1).z - end.z)) {
                end = positions.get(++next);
            }

            if (fadeOut) {
                float alpha;

                if (i <= fadeStart) {
                    alpha = 0.4F;
                } else {
                    if (i > fadeEnd) {
                        break;
                    }
                    alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
                IRenderer.glColor(color, alpha);
            }

            emitPathLine(bufferBuilder, stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
        }

        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    private static final int FX_MAX_NODES = 110;
    private static final int FX_STEPS = 6;
    private static final double FX_LIFT = 0.14D;
    private static final double FX_FADE_START = 45.0D;
    private static final double FX_FADE_END = 85.0D;
    private static final double FX_CHEVRON_SPACING = 1.6D;

    /**
     * The path as a light trail: a Catmull-Rom curve through the nodes, drawn as a wide soft glow, a mid band and a
     * thin hot core. Hue drifts along the trail and bright pulses run toward the goal; the trail tapers and fades with
     * distance, and chevrons slide along it in the direction of travel.
     */
    public static void drawFancyPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color) {
        final int n = Math.min(positions.size() - startIndex, FX_MAX_NODES);
        if (n < 2) {
            return;
        }
        final double[] cx = new double[n], cy = new double[n], cz = new double[n];
        for (int i = 0; i < n; i++) {
            BetterBlockPos p = positions.get(startIndex + i);
            cx[i] = p.x + 0.5D;
            cy[i] = p.y + FX_LIFT;
            cz[i] = p.z + 0.5D;
        }

        final int total = (n - 1) * FX_STEPS + 1;
        final double[] sx = new double[total], sy = new double[total], sz = new double[total], arc = new double[total];
        for (int i = 0, k = 0; i < n - 1; i++) {
            int a = Math.max(i - 1, 0), d = Math.min(i + 2, n - 1);
            for (int j = 0; j < FX_STEPS; j++, k++) {
                double t = (double) j / FX_STEPS;
                sx[k] = catmullRom(cx[a], cx[i], cx[i + 1], cx[d], t);
                sz[k] = catmullRom(cz[a], cz[i], cz[i + 1], cz[d], t);
                // heights ease between nodes: a spline would overshoot on stairs
                sy[k] = cy[i] + (cy[i + 1] - cy[i]) * (t * t * (3 - 2 * t));
            }
        }
        sx[total - 1] = cx[n - 1];
        sy[total - 1] = cy[n - 1];
        sz[total - 1] = cz[n - 1];
        for (int k = 1; k < total; k++) {
            arc[k] = arc[k - 1] + Math.sqrt(sq(sx[k] - sx[k - 1]) + sq(sy[k] - sy[k - 1]) + sq(sz[k] - sz[k - 1]));
        }

        final double time = (System.nanoTime() / 1.0E9D) % 10000.0D;
        final float baseHue = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)[0];
        final float width = settings.pathRenderLineWidthPixels.value;
        final double vpX = posX(), vpY = posY(), vpZ = posZ();
        final double[] bandWidth = {7.0D, 3.6D, 1.4D};
        final float[] bandAlpha = {0.22F, 0.50F, 1.0F};

        final BufferBuilder bb = IRenderer.startLines(color);
        final PoseStack.Pose pose = stack.last();
        final float[] c0 = new float[4], c1 = new float[4];
        for (int band = 0; band < 3; band++) {
            for (int k = 0; k < total - 1; k++) {
                double dx = sx[k + 1] - sx[k], dy = sy[k + 1] - sy[k], dz = sz[k + 1] - sz[k];
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1.0E-6D) {
                    continue;
                }
                float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
                fxColor(c0, baseHue, arc[k], time, band, bandAlpha[band]);
                fxColor(c1, baseHue, arc[k + 1], time, band, bandAlpha[band]);
                float w0 = (float) (width * bandWidth[band] * fxTaper(arc[k]) * (band == 2 ? 1.0D + 0.8D * fxPulse(arc[k], time) : 1.0D));
                float w1 = (float) (width * bandWidth[band] * fxTaper(arc[k + 1]) * (band == 2 ? 1.0D + 0.8D * fxPulse(arc[k + 1], time) : 1.0D));
                bb.addVertex(pose, (float) (sx[k] - vpX), (float) (sy[k] - vpY), (float) (sz[k] - vpZ)).setColor(c0[0], c0[1], c0[2], c0[3]).setNormal(pose, nx, ny, nz).setLineWidth(w0);
                bb.addVertex(pose, (float) (sx[k + 1] - vpX), (float) (sy[k + 1] - vpY), (float) (sz[k + 1] - vpZ)).setColor(c1[0], c1[1], c1[2], c1[3]).setNormal(pose, nx, ny, nz).setLineWidth(w1);
            }
        }

        // chevrons, scrolling toward the goal
        double nextChevron = (time * 1.3D) % FX_CHEVRON_SPACING;
        for (int k = 0; k < total - 1 && nextChevron < arc[total - 1]; k++) {
            while (nextChevron <= arc[k + 1] && nextChevron >= arc[k]) {
                double seg = arc[k + 1] - arc[k];
                double f = seg < 1.0E-6D ? 0.0D : (nextChevron - arc[k]) / seg;
                double dx = sx[k + 1] - sx[k], dz = sz[k + 1] - sz[k];
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat > 1.0E-4D) {
                    dx /= flat;
                    dz /= flat;
                    double px = sx[k] + (sx[k + 1] - sx[k]) * f + dx * 0.16D, py = sy[k] + (sy[k + 1] - sy[k]) * f + 0.02D, pz = sz[k] + (sz[k + 1] - sz[k]) * f + dz * 0.16D;
                    double bx = px - dx * 0.34D, bz = pz - dz * 0.34D;
                    float[] c = new float[4];
                    fxColor(c, baseHue, nextChevron, time, 2, 0.9F);
                    float w = (float) (width * 0.55D * fxTaper(nextChevron));
                    emitChevronArm(bb, pose, bx - dz * 0.24D - vpX, py - vpY, bz + dx * 0.24D - vpZ, px - vpX, py - vpY, pz - vpZ, c, w);
                    emitChevronArm(bb, pose, px - vpX, py - vpY, pz - vpZ, bx + dz * 0.24D - vpX, py - vpY, bz - dx * 0.24D - vpZ, c, w);
                }
                nextChevron += FX_CHEVRON_SPACING;
            }
        }
        IRenderer.endLines(bb, settings.renderPathIgnoreDepth.value);
    }

    private static void emitChevronArm(BufferBuilder bb, PoseStack.Pose pose, double x1, double y1, double z1, double x2, double y2, double z2, float[] c, float w) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double inv = 1.0D / Math.max(1.0E-6D, Math.sqrt(dx * dx + dy * dy + dz * dz));
        float nx = (float) (dx * inv), ny = (float) (dy * inv), nz = (float) (dz * inv);
        bb.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(c[0], c[1], c[2], c[3] * 0.35F).setNormal(pose, nx, ny, nz).setLineWidth(w);
        bb.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(c[0], c[1], c[2], c[3]).setNormal(pose, nx, ny, nz).setLineWidth(w);
    }

    /** Colour of the trail at {@code s} blocks along it: hue drifts with a slow wave, the core goes white-hot on pulses. */
    private static void fxColor(float[] out, float baseHue, double s, double time, int band, float alpha) {
        float hue = baseHue + 0.17F * (0.5F + 0.5F * (float) Math.sin(s * 0.21D - time * 1.5D));
        float pulse = (float) fxPulse(s, time);
        int rgb = Color.HSBtoRGB(hue, 0.82F - 0.35F * pulse * (band + 1) / 3.0F, 1.0F);
        float white = band == 2 ? 0.30F + 0.55F * pulse : 0.10F * pulse;
        out[0] = ((rgb >> 16 & 255) / 255.0F) * (1.0F - white) + white;
        out[1] = ((rgb >> 8 & 255) / 255.0F) * (1.0F - white) + white;
        out[2] = ((rgb & 255) / 255.0F) * (1.0F - white) + white;
        float fade = (float) (smooth(s / 2.5D) * (1.0D - smooth((s - FX_FADE_START) / (FX_FADE_END - FX_FADE_START))));
        out[3] = Math.min(1.0F, alpha * fade * (band < 2 ? 1.0F + 0.8F * pulse : 1.0F));
    }

    /** 0..1 travelling pulse that moves toward larger arc length (the goal). */
    private static double fxPulse(double s, double time) {
        double w = 0.5D + 0.5D * Math.sin(s * 0.75D - time * 5.0D);
        w *= w;
        return w * w * w;
    }

    private static double fxTaper(double s) {
        return 1.0D - 0.55D * smooth(s / FX_FADE_END);
    }

    private static double smooth(double x) {
        x = Math.max(0.0D, Math.min(1.0D, x));
        return x * x * (3.0D - 2.0D * x);
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return 0.5D * ((2.0D * p1) + (-p0 + p2) * t + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t * t + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t * t * t);
    }

    private static void emitPathLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;

        IRenderer.emitLine(bufferBuilder, stack,
                x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                settings.pathRenderLineWidthPixels.value
        );
        if (renderPathAsFrickinThingy) {
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
        }
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);

        //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

        final List<AABB> boxes = new ArrayList<>();
        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            boxes.add(toDraw.move(pos));
        });
        IRenderer.glow(settings.pathRenderLineWidthPixels.value, width -> {
            for (AABB box : boxes) {
                IRenderer.emitAABB(bufferBuilder, stack, box, .002D, width);
            }
        });

        IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        drawGoal(null, stack, ctx, goal, partialTicks, color, true);
    }

    private static void drawGoal(@Nullable BufferBuilder bufferBuilder, PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color, boolean setupRender) {
        if (!setupRender && bufferBuilder == null) {
            throw new RuntimeException("BufferBuilder must not be null if setupRender is false");
        }
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX, maxX;
        double minZ, maxZ;
        double minY, maxY;
        double y, y1, y2;
        if (!settings.renderGoalAnimated.value) {
            // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
            y = 0.999F;
        } else {
            y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalXZ) {
            GoalXZ goalPos = (GoalXZ) goal;
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;

            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
            drawGoalXZBeacon(stack, ctx, (GoalXZ) goal, minY, maxY, partialTicks, color);
        } else if (goal instanceof GoalComposite) {
            // Simple way to determine if goals can be batched, without having some sort of GoalRenderer
            boolean batch = Arrays.stream(((GoalComposite) goal).goals()).allMatch(IGoalRenderPos.class::isInstance);
            BufferBuilder buf = bufferBuilder;
            if (batch) {
                buf = IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value);
            }
            for (Goal g : ((GoalComposite) goal).goals()) {
                drawGoal(buf, stack, ctx, g, partialTicks, color, !batch);
            }
            if (batch) {
                IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
            }
        } else if (goal instanceof GoalInverted) {
            drawGoal(stack, ctx, ((GoalInverted) goal).origin, partialTicks, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel) {
            GoalYLevel goalpos = (GoalYLevel) goal;
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = ((GoalYLevel) goal).level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalpos.level - renderPosY;
            y2 = 1 - y + goalpos.level - renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        }
    }

    private static void drawDankLitGoalBox(BufferBuilder bufferBuilder, PoseStack stack, Color colorIn, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, boolean setupRender) {
        if (setupRender) {
            bufferBuilder = IRenderer.startLines(colorIn);
        }

        final BufferBuilder bb = bufferBuilder;
        IRenderer.glow(settings.goalRenderLineWidthPixels.value, width -> {
            renderHorizontalQuad(bb, stack, minX, maxX, minZ, maxZ, y1, width);
            renderHorizontalQuad(bb, stack, minX, maxX, minZ, maxZ, y2, width);

            for (double y = minY; y < maxY; y += 16) {
                double max = Math.min(maxY, y + 16);
                IRenderer.emitLine(bb, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0, width);
                IRenderer.emitLine(bb, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0, width);
                IRenderer.emitLine(bb, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0, width);
                IRenderer.emitLine(bb, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0, width);
            }
        });

        if (setupRender) {
            IRenderer.endLines(bufferBuilder, settings.renderGoalIgnoreDepth.value);
        }
    }

    private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y, float lineWidth) {
        if (y != 0) {
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0, lineWidth);
        }
    }

    private static void drawGoalXZBeacon(PoseStack stack, IPlayerContext ctx, GoalXZ goal, double minY, double maxY, float partialTicks, Color color) {
        float time = settings.renderGoalAnimated.value ? (float) ctx.world().getGameTime() + partialTicks : 0.0F;
        int glowColor = (color.getRGB() & 0x00FFFFFF) | GOAL_BEACON_GLOW_ALPHA << 24;
        double height = maxY - minY;

        stack.pushPose();
        stack.translate(goal.getX() - posX(), minY - posY(), goal.getZ() - posZ());
        renderGoalXZBeaconLayer(stack, height, time, color.getRGB(), GOAL_BEACON_INNER_RADIUS, false);
        renderGoalXZBeaconLayer(stack, height, time, glowColor, GOAL_BEACON_GLOW_RADIUS, true);
        stack.popPose();
    }

    private static void renderGoalXZBeaconLayer(PoseStack stack, double height, float time, int color, float radius, boolean translucent) {
        BufferBuilder bufferBuilder = IRenderer.startBlockQuads();
        float scroll = Mth.frac(-time * 0.2F - Mth.floor(-time * 0.1F));

        stack.pushPose();
        stack.translate(0.5D, 0.0D, 0.5D);
        if (!translucent) {
            stack.pushPose();
            stack.mulPose(Axis.YP.rotationDegrees(time * 2.25F - 45.0F));
        }

        float v0 = -1.0F + scroll;
        float v1 = (float) (translucent ? height + v0 : height * (0.5F / radius) + v0);
        PoseStack.Pose pose = stack.last();
        if (translucent) {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, -radius, -radius, radius, -radius, -radius, radius, radius, radius, v0, v1);
        } else {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, 0.0F, radius, radius, 0.0F, -radius, 0.0F, 0.0F, -radius, v0, v1);
        }

        if (!translucent) {
            stack.popPose();
        }
        stack.popPose();

        IRenderer.endBuffer(bufferBuilder, IRenderer.beaconBeam(BeaconRenderer.BEAM_LOCATION, translucent, settings.renderGoalIgnoreDepth.value));
    }

    private static void emitBeaconShell(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                        float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4,
                                        float v0, float v1) {
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x1, z1, x2, z2, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x4, z4, x3, z3, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x2, z2, x4, z4, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x3, z3, x1, z1, 0.0F, 1.0F, v0, v1);
    }

    private static void emitBeaconFace(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                       float x1, float z1, float x2, float z2, float u0, float u1, float v0, float v1) {
        float nx = z2 - z1;
        float nz = x1 - x2;
        float length = Mth.sqrt(nx * nx + nz * nz);
        if (length != 0.0F) {
            nx /= length;
            nz /= length;
        }

        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, maxY, z1, color, u1, v0, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, minY, z1, color, u1, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, minY, z2, color, u0, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, maxY, z2, color, u0, v0, nx, 0.0F, nz);
    }
}
