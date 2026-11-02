/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.PredictionResult.Assumption;

import java.util.Arrays;

/**
 * Ostinato: one simulation's reader over a {@link Terrain}: block lookups with a one-section memo, vanilla's
 * collider gathering (BlockCollisions) and block ray clip (BlockGetter.clip), and the assumptions those hit. Not
 * thread safe and reused between simulations on one thread ({@link #reset}); nothing on the hot path allocates once
 * its buffers have grown.
 */
public final class TerrainView {
    private Terrain terrain;
    private int memoSx = Integer.MIN_VALUE, memoSy, memoSz;
    private Terrain.Section memo;
    /** Assumption bits hit so far. */
    public long assumptions;

    // gathered colliders: boxes (6 doubles each) and the shapes they came from (for step-up candidates)
    double[] boxes = new double[6 * 32];
    int boxCount;
    BlockInfo[] shapeInfo = new BlockInfo[16];
    int[] shapeX = new int[16], shapeY = new int[16], shapeZ = new int[16];
    int[] shapeBox = new int[16]; // first box index of an entity shape (shapeInfo null)
    int shapeCount;

    public TerrainView reset(Terrain terrain) {
        this.terrain = terrain;
        this.memoSx = Integer.MIN_VALUE;
        this.memo = null;
        this.assumptions = 0;
        return this;
    }

    public Terrain terrain() {
        return terrain;
    }

    public void assume(Assumption a) {
        assumptions |= a.bit();
    }

    public boolean outside() {
        return (assumptions & Assumption.OUTSIDE_SNAPSHOT.bit()) != 0;
    }

    public BlockInfo at(int x, int y, int z) {
        if (y < terrain.minY || y >= terrain.maxY) return BlockInfo.AIR_INFO;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (sx != memoSx || sy != memoSy || sz != memoSz) {
            memoSx = sx;
            memoSy = sy;
            memoSz = sz;
            memo = terrain.section(sx, sy, sz);
            if (memo == null) assume(Assumption.OUTSIDE_SNAPSHOT);
            else if ((memo.flags & Terrain.Section.UNLOADED) != 0) assume(Assumption.UNLOADED_CHUNK);
        }
        return memo == null ? BlockInfo.AIR_INFO : memo.get(x & 15, y & 15, z & 15);
    }

    /** Whether the cell's section was copied from a chunk the client had not loaded (Level.hasChunkAt false). */
    public boolean unloadedAt(int x, int y, int z) {
        Terrain.Section s = terrain.section(x >> 4, y >> 4, z >> 4);
        return s != null && (s.flags & Terrain.Section.UNLOADED) != 0;
    }

    /** Fluid height at a cell as FluidState.getHeight: a full block when the same fluid is above, else its own height. */
    public float fluidHeight(int x, int y, int z, BlockInfo info) {
        if (info.fluid == BlockInfo.FLUID_NONE) return 0;
        return at(x, y + 1, z).fluid == info.fluid ? 1.0f : info.fluidOwnHeight;
    }

    // ---- colliders ----

    /**
     * Gathers what an entity box query collides with, as Entity.collectCollidersIgnoringWorldBorder: the frozen
     * entity boxes intersecting {@code entityQuery} (pass NaN to skip them) followed by BlockCollisions over the block query box.
     */
    void gather(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean withEntities,
                double eMinX, double eMinY, double eMinZ, double eMaxX, double eMaxY, double eMaxZ) {
        boxCount = 0;
        shapeCount = 0;
        if (withEntities) addEntities(eMinX, eMinY, eMinZ, eMaxX, eMaxY, eMaxZ);
        addBlocks(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The entity boxes intersecting a box inflated by 1e-7; Level.getEntityCollisions. */
    private void addEntities(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double[] e = terrain.entityBoxes;
        for (int i = 0; i < e.length; i += 6) {
            if (e[i] < maxX + 1.0E-7 && e[i + 3] > minX - 1.0E-7 && e[i + 1] < maxY + 1.0E-7 && e[i + 4] > minY - 1.0E-7
                    && e[i + 2] < maxZ + 1.0E-7 && e[i + 5] > minZ - 1.0E-7) {
                assume(Assumption.ENTITY_COLLIDERS_FROZEN);
                addShape(null, 0, 0, 0, boxCount);
                addBox(e[i], e[i + 1], e[i + 2], e[i + 3], e[i + 4], e[i + 5]);
            }
        }
    }

    /** BlockCollisions over one box, block shapes in its cursor order. */
    private void addBlocks(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int x0 = PhysMath.floor(minX - 1.0E-7) - 1, x1 = PhysMath.floor(maxX + 1.0E-7) + 1;
        int y0 = PhysMath.floor(minY - 1.0E-7) - 1, y1 = PhysMath.floor(maxY + 1.0E-7) + 1;
        int z0 = PhysMath.floor(minZ - 1.0E-7) - 1, z1 = PhysMath.floor(maxZ + 1.0E-7) + 1;
        // Cursor3D order: x fastest, then y, then z
        for (int z = z0; z <= z1; z++) {
            int fz = z == z0 || z == z1 ? 1 : 0;
            for (int y = y0; y <= y1; y++) {
                int fy = y == y0 || y == y1 ? 1 : 0;
                for (int x = x0; x <= x1; x++) {
                    int face = fz + fy + (x == x0 || x == x1 ? 1 : 0);
                    if (face == 3) continue;
                    BlockInfo info = at(x, y, z);
                    if (info.boxes.length == 0 && !info.is(BlockInfo.MOVING_PISTON)) continue;
                    if (face == 1 && !info.is(BlockInfo.LARGE)) continue;
                    if (face == 2 && !info.is(BlockInfo.MOVING_PISTON)) continue;
                    if (info.is(BlockInfo.MOVING_PISTON) || info.is(BlockInfo.CONTEXT)) assume(Assumption.CONTEXT_SHAPE);
                    double[] b = info.boxes;
                    if (b.length == 0) continue;
                    if (info.is(BlockInfo.FULL_CUBE)) {
                        if (!(minX < x + 1 && maxX > x && minY < y + 1 && maxY > y && minZ < z + 1 && maxZ > z)) continue;
                    } else {
                        boolean any = false;
                        for (int i = 0; i < b.length; i += 6) {
                            if (b[i] + x < maxX && b[i + 3] + x > minX && b[i + 1] + y < maxY && b[i + 4] + y > minY
                                    && b[i + 2] + z < maxZ && b[i + 5] + z > minZ) {
                                any = true;
                                break;
                            }
                        }
                        if (!any) continue;
                    }
                    addShape(info, x, y, z, boxCount);
                    for (int i = 0; i < b.length; i += 6) addBox(b[i] + x, b[i + 1] + y, b[i + 2] + z, b[i + 3] + x, b[i + 4] + y, b[i + 5] + z);
                }
            }
        }
    }

    private void addShape(BlockInfo info, int x, int y, int z, int firstBox) {
        if (shapeCount == shapeInfo.length) {
            shapeInfo = Arrays.copyOf(shapeInfo, shapeCount * 2);
            shapeX = Arrays.copyOf(shapeX, shapeCount * 2);
            shapeY = Arrays.copyOf(shapeY, shapeCount * 2);
            shapeZ = Arrays.copyOf(shapeZ, shapeCount * 2);
            shapeBox = Arrays.copyOf(shapeBox, shapeCount * 2);
        }
        shapeInfo[shapeCount] = info;
        shapeX[shapeCount] = x;
        shapeY[shapeCount] = y;
        shapeZ[shapeCount] = z;
        shapeBox[shapeCount] = firstBox;
        shapeCount++;
    }

    /** End (exclusive) of a gathered shape's boxes. */
    int shapeEnd(int s) {
        return s + 1 < shapeCount ? shapeBox[s + 1] : boxCount;
    }

    private void addBox(double a, double b, double c, double d, double e, double f) {
        int o = boxCount * 6;
        if (o + 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        boxes[o] = a;
        boxes[o + 1] = b;
        boxes[o + 2] = c;
        boxes[o + 3] = d;
        boxes[o + 4] = e;
        boxes[o + 5] = f;
        boxCount++;
    }

    /** Whether any block collision box or fluid lies in a box: !Level.noCollision || containsAnyLiquid (Entity.isFree). */
    boolean blocked(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        gather(minX, minY, minZ, maxX, maxY, maxZ, true, minX, minY, minZ, maxX, maxY, maxZ);
        if (boxCount > 0) return true;
        int x0 = PhysMath.floor(minX), x1 = (int) Math.ceil(maxX) - 1;
        int y0 = PhysMath.floor(minY), y1 = (int) Math.ceil(maxY) - 1;
        int z0 = PhysMath.floor(minZ), z1 = (int) Math.ceil(maxZ) - 1;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (at(x, y, z).fluid != BlockInfo.FLUID_NONE) return true;
        return false;
    }

    // ---- ray clip ----

    /** Which shapes a clip uses: ClipContext.Block.COLLIDER, or FALLDAMAGE_RESETTING (a full cube where tagged). */
    public static final int CLIP_COLLIDER = 0, CLIP_FALL_RESET = 1;
    /** ClipContext.Fluid.NONE, WATER, ANY. */
    public static final int FLUID_NONE = 0, FLUID_WATER = 1, FLUID_ANY = 2;

    /** Hit of the last {@link #clip}: location, block and face (Direction order: down up north south west east). */
    public double hitX, hitY, hitZ;
    public int hitBlockX, hitBlockY, hitBlockZ, hitFace;
    private static final double[] UNIT = {0, 0, 0, 1, 1, 1};
    private final double[] fluidBox = new double[6];
    private final double[] scale = new double[1];

    /** BlockGetter.clip from one point to another: true when it hits (the hit fields are then set). */
    public boolean clip(double fx, double fy, double fz, double tx, double ty, double tz, int blockMode, int fluidMode) {
        if (fx == tx && fy == ty && fz == tz) return false;
        double toX = PhysMath.lerp(-1.0E-7, tx, fx), toY = PhysMath.lerp(-1.0E-7, ty, fy), toZ = PhysMath.lerp(-1.0E-7, tz, fz);
        double fromX = PhysMath.lerp(-1.0E-7, fx, tx), fromY = PhysMath.lerp(-1.0E-7, fy, ty), fromZ = PhysMath.lerp(-1.0E-7, fz, tz);
        int cx = PhysMath.floor(fromX), cy = PhysMath.floor(fromY), cz = PhysMath.floor(fromZ);
        if (clipCell(cx, cy, cz, fx, fy, fz, tx, ty, tz, blockMode, fluidMode)) return true;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        int sx = PhysMath.sign(dx), sy = PhysMath.sign(dy), sz = PhysMath.sign(dz);
        double tdx = sx == 0 ? Double.MAX_VALUE : sx / dx;
        double tdy = sy == 0 ? Double.MAX_VALUE : sy / dy;
        double tdz = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double tX = tdx * (sx > 0 ? 1.0 - PhysMath.frac(fromX) : PhysMath.frac(fromX));
        double tY = tdy * (sy > 0 ? 1.0 - PhysMath.frac(fromY) : PhysMath.frac(fromY));
        double tZ = tdz * (sz > 0 ? 1.0 - PhysMath.frac(fromZ) : PhysMath.frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    cx += sx;
                    tX += tdx;
                } else {
                    cz += sz;
                    tZ += tdz;
                }
            } else if (tY < tZ) {
                cy += sy;
                tY += tdy;
            } else {
                cz += sz;
                tZ += tdz;
            }
            if (clipCell(cx, cy, cz, fx, fy, fz, tx, ty, tz, blockMode, fluidMode)) return true;
        }
        return false;
    }

    private boolean clipCell(int x, int y, int z, double fx, double fy, double fz, double tx, double ty, double tz, int blockMode, int fluidMode) {
        BlockInfo info = at(x, y, z);
        double[] shape;
        if (blockMode == CLIP_FALL_RESET) shape = info.is(BlockInfo.FALL_RESET) ? UNIT : null;
        else {
            shape = info.boxes.length == 0 ? null : info.boxes;
            if (shape != null && info.is(BlockInfo.CONTEXT)) assume(Assumption.CONTEXT_SHAPE);
        }
        double bestSq = Double.MAX_VALUE;
        double bx = 0, by = 0, bz = 0;
        int bface = -1;
        if (shape != null && shapeClip(shape, x, y, z, fx, fy, fz, tx, ty, tz)) {
            bx = hitX;
            by = hitY;
            bz = hitZ;
            bface = hitFace;
            bestSq = sq(bx - fx, by - fy, bz - fz);
        }
        if (fluidMode != FLUID_NONE && info.fluid != BlockInfo.FLUID_NONE && (fluidMode == FLUID_ANY || info.fluid == BlockInfo.FLUID_WATER)) {
            float h = fluidHeight(x, y, z, info);
            fluidBox[0] = 0;
            fluidBox[1] = 0;
            fluidBox[2] = 0;
            fluidBox[3] = 1;
            fluidBox[4] = h;
            fluidBox[5] = 1;
            if (h > 0 && shapeClip(fluidBox, x, y, z, fx, fy, fz, tx, ty, tz)) {
                double d = sq(hitX - fx, hitY - fy, hitZ - fz);
                if (d < bestSq) { // vanilla: block wins ties
                    bestSq = d;
                    bx = hitX;
                    by = hitY;
                    bz = hitZ;
                    bface = hitFace;
                }
            }
        }
        if (bface < 0) return false;
        hitX = bx;
        hitY = by;
        hitZ = bz;
        hitFace = bface;
        hitBlockX = x;
        hitBlockY = y;
        hitBlockZ = z;
        return true;
    }

    private static double sq(double a, double b, double c) {
        return a * a + b * b + c * c;
    }

    /** VoxelShape.clip for a shape of boxes at a cell: inside test at 0.001 along, then AABB.clip over the boxes. */
    private boolean shapeClip(double[] b, int x, int y, int z, double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        if (dx * dx + dy * dy + dz * dz < 1.0E-7) return false;
        double px = fx + dx * 0.001 - x, py = fy + dy * 0.001 - y, pz = fz + dz * 0.001 - z;
        for (int i = 0; i < b.length; i += 6) {
            if (px >= b[i] && px < b[i + 3] && py >= b[i + 1] && py < b[i + 4] && pz >= b[i + 2] && pz < b[i + 5]) {
                hitX = px + x;
                hitY = py + y;
                hitZ = pz + z;
                hitFace = opposite(nearest(dx, dy, dz));
                return true;
            }
        }
        scale[0] = 1.0;
        int face = -1;
        for (int i = 0; i < b.length; i += 6) {
            double minX = b[i] + x, minY = b[i + 1] + y, minZ = b[i + 2] + z, maxX = b[i + 3] + x, maxY = b[i + 4] + y, maxZ = b[i + 5] + z;
            if (dx > 1.0E-7) face = clipPoint(face, dx, dy, dz, minX, minY, maxY, minZ, maxZ, 4, fx, fy, fz);
            else if (dx < -1.0E-7) face = clipPoint(face, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, 5, fx, fy, fz);
            if (dy > 1.0E-7) face = clipPoint(face, dy, dz, dx, minY, minZ, maxZ, minX, maxX, 0, fy, fz, fx);
            else if (dy < -1.0E-7) face = clipPoint(face, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, 1, fy, fz, fx);
            if (dz > 1.0E-7) face = clipPoint(face, dz, dx, dy, minZ, minX, maxX, minY, maxY, 2, fz, fx, fy);
            else if (dz < -1.0E-7) face = clipPoint(face, dz, dx, dy, maxZ, minX, maxX, minY, maxY, 3, fz, fx, fy);
        }
        if (face < 0) return false;
        double s = scale[0];
        hitX = fx + s * dx;
        hitY = fy + s * dy;
        hitZ = fz + s * dz;
        hitFace = face;
        return true;
    }

    /** AABB.clip of one box: true when the segment enters it (hit fields set; hitFace is the face entered). */
    public boolean boxClip(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        scale[0] = 1.0;
        int face = -1;
        if (dx > 1.0E-7) face = clipPoint(face, dx, dy, dz, minX, minY, maxY, minZ, maxZ, 4, fx, fy, fz);
        else if (dx < -1.0E-7) face = clipPoint(face, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, 5, fx, fy, fz);
        if (dy > 1.0E-7) face = clipPoint(face, dy, dz, dx, minY, minZ, maxZ, minX, maxX, 0, fy, fz, fx);
        else if (dy < -1.0E-7) face = clipPoint(face, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, 1, fy, fz, fx);
        if (dz > 1.0E-7) face = clipPoint(face, dz, dx, dy, minZ, minX, maxX, minY, maxY, 2, fz, fx, fy);
        else if (dz < -1.0E-7) face = clipPoint(face, dz, dx, dy, maxZ, minX, maxX, minY, maxY, 3, fz, fx, fy);
        if (face < 0) return false;
        double s = scale[0];
        hitX = fx + s * dx;
        hitY = fy + s * dy;
        hitZ = fz + s * dz;
        hitFace = face;
        return true;
    }

    private int clipPoint(int face, double da, double db, double dc, double point, double minB, double maxB, double minC, double maxC,
                          int newFace, double fromA, double fromB, double fromC) {
        double s = (point - fromA) / da;
        double pb = fromB + s * db;
        double pc = fromC + s * dc;
        if (0.0 < s && s < scale[0] && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
            scale[0] = s;
            return newFace;
        }
        return face;
    }

    /** Direction.getApproximateNearest's index (down up north south west east). */
    static int nearest(double dx, double dy, double dz) {
        // Direction.values() order with normals: DOWN(0,-1,0) UP(0,1,0) NORTH(0,0,-1) SOUTH(0,0,1) WEST(-1,0,0) EAST(1,0,0)
        float x = (float) dx, y = (float) dy, z = (float) dz;
        int best = 2; // vanilla starts from NORTH
        float bestDot = Float.MIN_VALUE;
        for (int i = 0; i < 6; i++) {
            float dot = switch (i) {
                case 0 -> -y;
                case 1 -> y;
                case 2 -> -z;
                case 3 -> z;
                case 4 -> -x;
                default -> x;
            };
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }

    static int opposite(int face) {
        return face ^ 1;
    }
}
