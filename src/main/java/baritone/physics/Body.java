/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionResult.Assumption;

import java.util.Arrays;

/**
 * Ostinato: one simulated entity and vanilla 26.3's Entity-level movement applied to it: Entity.move (stuck
 * multiplier, collide with step-up, fall-reset clip, collision flags, supporting block, fall tracking, bounce, block
 * speed factor), fluid contact (EntityFluidInteraction.update) and the motion effects of blocks it touches
 * (applyEffectsFromBlocks). Mutable; one simulation owns it.
 */
final class Body {
    // what it is
    boolean living, player, sneaking, flying, fallFlying, slowFalling, weaving, pushedByFluid = true;
    float halfW, height, eyeHeight, stepHeight, movementEfficiency;
    double gravity, bounciness;
    float airDrag = 0.98f;

    // state
    double x, y, z, vx, vy, vz, fallDistance;
    boolean onGround, hColl, vColl, vCollBelow;
    boolean supported, onGroundNoBlocks;
    int supX, supY, supZ;
    double stuckX, stuckY, stuckZ;
    boolean inWater, inLava, eyeInWater;
    double waterHeight, lavaHeight;
    /** Where the tick started (vanilla's oldPosition). */
    double tickX, tickY, tickZ;

    // what the last move did
    private boolean moved, zFirst;
    private double fromX, fromY, fromZ, toX, toY, toZ;
    boolean xColl, zColl, landed, bounced;
    double landFall;
    BlockInfo landOn;
    int landX, landY, landZ;

    private double cx, cy, cz;
    private float[] candidates = new float[8];
    private long[] visited = new long[8];
    private int visitedCount;

    Body set(BodyState s) {
        living = s.isLiving();
        player = s.type == BodyState.Type.PLAYER;
        sneaking = s.has(BodyState.SNEAKING);
        flying = s.has(BodyState.FLYING);
        fallFlying = s.has(BodyState.FALL_FLYING);
        slowFalling = s.has(BodyState.SLOW_FALLING);
        weaving = s.has(BodyState.WEAVING);
        pushedByFluid = !(player && flying);
        halfW = s.width / 2.0f;
        height = s.height;
        eyeHeight = s.eyeHeight;
        stepHeight = s.stepHeight;
        movementEfficiency = s.movementEfficiency;
        gravity = s.has(BodyState.NO_GRAVITY) ? 0.0 : s.gravity;
        bounciness = s.bounciness;
        airDrag = living ? PhysMath.modifiedFriction(0.98f, s.airDragModifier) : 0.98f; // LivingEntity.getAirDrag
        x = tickX = s.x;
        y = tickY = s.y;
        z = tickZ = s.z;
        vx = s.vx;
        vy = s.vy;
        vz = s.vz;
        fallDistance = s.fallDistance;
        onGround = s.onGround;
        hColl = vColl = vCollBelow = false;
        supported = onGroundNoBlocks = false;
        stuckX = stuckY = stuckZ = 0;
        inWater = inLava = eyeInWater = false;
        waterHeight = lavaHeight = 0;
        moved = landed = bounced = false;
        return this;
    }

    /** Called at the start of each simulated tick. */
    void startTick() {
        tickX = x;
        tickY = y;
        tickZ = z;
        moved = false;
        landed = false;
        bounced = false;
    }

    /** LivingEntity.getEffectiveGravity (Slow Falling caps it while falling). */
    double effectiveGravity() {
        return slowFalling && vy <= 0.0 ? Math.min(gravity, 0.01) : gravity;
    }

    void resetFallDistance() {
        fallDistance = 0;
    }

    void makeStuck(double sx, double sy, double sz) {
        if (player && flying) return;
        fallDistance = 0;
        stuckX = sx;
        stuckY = sy;
        stuckZ = sz;
    }

    // ---- Entity.move ----

    void move(TerrainView v, double dx, double dy, double dz) {
        if (stuckX * stuckX + stuckY * stuckY + stuckZ * stuckZ > 1.0E-7) {
            dx *= stuckX;
            dy *= stuckY;
            dz *= stuckZ;
            stuckX = stuckY = stuckZ = 0;
            vx = vy = vz = 0;
        }
        if (player && sneaking && !flying && onGround && dy <= 0.0) v.assume(Assumption.SNEAK_EDGE); // maybeBackOffFromEdge
        collide(v, dx, dy, dz);
        double mx = cx, my = cy, mz = cz;
        double mLen = mx * mx + my * my + mz * mz;
        if (mLen > 1.0E-7 || dx * dx + dy * dy + dz * dz - mLen < 1.0E-7) {
            if (fallDistance != 0.0 && mLen >= 1.0) {
                double len = Math.sqrt(mLen), check = Math.min(len, 8.0);
                if (v.clip(x, y, z, x + mx / len * check, y + my / len * check, z + mz / len * check,
                        TerrainView.CLIP_FALL_RESET, TerrainView.FLUID_WATER)) resetFallDistance();
            }
            fromX = x;
            fromY = y;
            fromZ = z;
            x += mx;
            y += my;
            z += mz;
            toX = x;
            toY = y;
            toZ = z;
            zFirst = Math.abs(dx) < Math.abs(dz);
            moved = true;
        }
        xColl = !PhysMath.equal(dx, mx);
        zColl = !PhysMath.equal(dz, mz);
        hColl = xColl || zColl;
        vColl = dy != my;
        vCollBelow = vColl && dy < 0.0;
        onGround = vCollBelow;
        checkSupportingBlock(v, mx, mz);
        long effect = onPos(v, 0.2f);
        int ex = PhysMath.unpackX(effect), ey = PhysMath.unpackY(effect), ez = PhysMath.unpackZ(effect);
        BlockInfo effectInfo = v.at(ex, ey, ez);
        checkFallDamage(v, my, effectInfo, ex, ey, ez);
        if (Math.abs(dy) > 0.0 && vColl || hColl) restitute(effectInfo, my);
        float speed = blockSpeedFactor(v);
        vx *= speed;
        vz *= speed;
    }

    private void collide(TerrainView v, double dx, double dy, double dz) {
        double minX = x - halfW, minY = y, minZ = z - halfW, maxX = x + halfW, maxY = y + height, maxZ = z + halfW;
        // entity colliders come from aabb.expandTowards(movement).expandTowards(0, maxUpStep, 0), shared by both passes
        double qMinX = minX + Math.min(dx, 0), qMaxX = maxX + Math.max(dx, 0);
        double qMinY = minY + Math.min(dy, 0), qMaxY = maxY + Math.max(dy, 0);
        double qMinZ = minZ + Math.min(dz, 0), qMaxZ = maxZ + Math.max(dz, 0);
        double eMaxY = qMaxY + Math.max(stepHeight, 0);
        double sx = dx, sy = dy, sz = dz;
        if (dx * dx + dy * dy + dz * dz != 0.0) {
            v.gather(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, true, qMinX, qMinY, qMinZ, qMaxX, eMaxY, qMaxZ);
            collideWithShapes(v, dx, dy, dz, minX, minY, minZ, maxX, maxY, maxZ);
            sx = cx;
            sy = cy;
            sz = cz;
        }
        boolean groundAfter = dy != sy && dy < 0.0;
        if (stepHeight > 0.0f && (groundAfter || onGround) && (dx != sx || dz != sz)) {
            double gMinY = groundAfter ? minY + sy : minY, gMaxY = groundAfter ? maxY + sy : maxY;
            double uMinY = groundAfter ? gMinY : gMinY + (double) -1.0E-5f;
            v.gather(qMinX, uMinY, qMinZ, qMaxX, gMaxY + stepHeight, qMaxZ, true, qMinX, qMinY, qMinZ, qMaxX, eMaxY, qMaxZ);
            int n = stepCandidates(v, gMinY, stepHeight, (float) sy);
            double stepH = sx * sx + sz * sz;
            for (int i = 0; i < n; i++) {
                collideWithShapes(v, dx, candidates[i], dz, minX, gMinY, minZ, maxX, gMaxY, maxZ);
                if (cx * cx + cz * cz > stepH) {
                    cy -= minY - gMinY;
                    return;
                }
            }
        }
        cx = sx;
        cy = sy;
        cz = sz;
    }

    /** Entity.collectCandidateStepUpHeights over the gathered shapes, sorted ascending. */
    private int stepCandidates(TerrainView v, double groundY, float maxStep, float skip) {
        int n = 0;
        for (int s = 0; s < v.shapeCount; s++) {
            BlockInfo info = v.shapeInfo[s];
            int count = info == null ? 2 : info.yCoords.length;
            for (int i = 0; i < count; i++) {
                double coord = info == null ? v.boxes[v.shapeBox[s] * 6 + (i == 0 ? 1 : 4)] : info.yCoords[i] + v.shapeY[s];
                float rel = (float) (coord - groundY);
                if (rel < 0.0f || rel == skip) continue;
                if (rel > maxStep) break;
                boolean dup = false;
                for (int j = 0; j < n; j++) {
                    if (Float.floatToIntBits(candidates[j]) == Float.floatToIntBits(rel)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                if (n == candidates.length) candidates = Arrays.copyOf(candidates, n * 2);
                candidates[n++] = rel;
            }
        }
        Arrays.sort(candidates, 0, n);
        return n;
    }

    /** Entity.collideWithShapes: Y first, then the larger horizontal axis last. Result in cx, cy, cz. */
    private void collideWithShapes(TerrainView v, double dx, double dy, double dz,
                                   double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (v.shapeCount == 0) {
            cx = dx;
            cy = dy;
            cz = dz;
            return;
        }
        double rx = 0, ry = 0, rz = 0;
        if (dy != 0.0) ry = collideAxis(v, 1, minX, minY, minZ, maxX, maxY, maxZ, dy);
        if (Math.abs(dx) < Math.abs(dz)) {
            if (dz != 0.0) rz = collideAxis(v, 2, minX, minY + ry, minZ, maxX, maxY + ry, maxZ, dz);
            if (dx != 0.0) rx = collideAxis(v, 0, minX, minY + ry, minZ + rz, maxX, maxY + ry, maxZ + rz, dx);
        } else {
            if (dx != 0.0) rx = collideAxis(v, 0, minX, minY + ry, minZ, maxX, maxY + ry, maxZ, dx);
            if (dz != 0.0) rz = collideAxis(v, 2, minX + rx, minY + ry, minZ, maxX + rx, maxY + ry, maxZ, dz);
        }
        cx = rx;
        cy = ry;
        cz = rz;
    }

    /**
     * Shapes.collide along one axis (0 x, 1 y, 2 z): each shape in turn shortens the move, with vanilla's 1e-7
     * tolerances. A box the entity already overlaps is ignored and reported (vanilla stops at a voxel boundary inside it).
     */
    private static double collideAxis(TerrainView v, int axis, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ, double d) {
        double eMinA, eMaxA, eMinB, eMaxB, eMinC, eMaxC;
        int a, b, c;
        if (axis == 0) {
            a = 0; b = 1; c = 2;
            eMinA = minX; eMaxA = maxX; eMinB = minY; eMaxB = maxY; eMinC = minZ; eMaxC = maxZ;
        } else if (axis == 1) {
            a = 1; b = 0; c = 2;
            eMinA = minY; eMaxA = maxY; eMinB = minX; eMaxB = maxX; eMinC = minZ; eMaxC = maxZ;
        } else {
            a = 2; b = 0; c = 1;
            eMinA = minZ; eMaxA = maxZ; eMinB = minX; eMaxB = maxX; eMinC = minY; eMaxC = maxY;
        }
        double[] box = v.boxes;
        for (int s = 0; s < v.shapeCount; s++) {
            if (Math.abs(d) < 1.0E-7) return 0.0;
            for (int i = v.shapeBox[s], end = v.shapeEnd(s); i < end; i++) {
                int o = i * 6;
                if (!(box[o + b] <= eMaxB - 1.0E-7 && box[o + 3 + b] > eMinB + 1.0E-7)) continue;
                if (!(box[o + c] <= eMaxC - 1.0E-7 && box[o + 3 + c] > eMinC + 1.0E-7)) continue;
                double bMinA = box[o + a], bMaxA = box[o + 3 + a];
                if (d > 0.0) {
                    if (bMinA > eMaxA - 1.0E-7) d = Math.min(d, bMinA - eMaxA);
                    else if (bMaxA > eMinA + 1.0E-7) v.assume(Assumption.PENETRATING);
                } else {
                    if (bMaxA < eMinA + 1.0E-7) d = Math.max(d, bMaxA - eMinA);
                    else if (bMinA < eMaxA - 1.0E-7) v.assume(Assumption.PENETRATING);
                }
            }
        }
        return d;
    }

    // ---- ground ----

    /** Entity.checkSupportingBlock after a move. */
    private void checkSupportingBlock(TerrainView v, double mx, double mz) {
        if (onGround) {
            double minX = x - halfW, maxX = x + halfW, minZ = z - halfW, maxZ = z + halfW;
            boolean found = findSupport(v, minX, minZ, maxX, maxZ);
            if (!found && !onGroundNoBlocks) found = findSupport(v, minX - mx, minZ - mz, maxX - mx, maxZ - mz);
            supported = found;
            onGroundNoBlocks = !found;
        } else {
            onGroundNoBlocks = false;
            supported = false;
        }
    }

    /** CollisionGetter.findSupportingBlock over the thin box under the feet: the nearest block centre, ties to the greater BlockPos. */
    private boolean findSupport(TerrainView v, double minX, double minZ, double maxX, double maxZ) {
        v.gather(minX, y - 1.0E-6, minZ, maxX, y, maxZ, false, 0, 0, 0, 0, 0, 0);
        boolean found = false;
        double best = Double.MAX_VALUE;
        for (int s = 0; s < v.shapeCount; s++) {
            int bx = v.shapeX[s], by = v.shapeY[s], bz = v.shapeZ[s];
            double ddx = bx + 0.5 - x, ddy = by + 0.5 - y, ddz = bz + 0.5 - z;
            double dist = ddx * ddx + ddy * ddy + ddz * ddz;
            if (dist < best || dist == best && (!found || compare(supX, supY, supZ, bx, by, bz) < 0)) {
                supX = bx;
                supY = by;
                supZ = bz;
                best = dist;
                found = true;
            }
        }
        return found;
    }

    /** Vec3i.compareTo: y, then z, then x. */
    private static int compare(int ax, int ay, int az, int bx, int by, int bz) {
        if (ay != by) return Integer.compare(ay, by);
        if (az != bz) return Integer.compare(az, bz);
        return Integer.compare(ax, bx);
    }

    /** Entity.getOnPos(offset), packed: 0.2f legacy (effects, landing), 0.500001f (friction, speed, jump factor). */
    long onPos(TerrainView v, float offset) {
        if (supported) {
            if (offset > 1.0E-5f) {
                BlockInfo below = v.at(supX, supY, supZ);
                if ((double) offset <= 0.5 && below.is(BlockInfo.FENCE) || below.is(BlockInfo.WALL_OR_GATE)) return PhysMath.pack(supX, supY, supZ);
                return PhysMath.pack(supX, PhysMath.floor(y - (double) offset), supZ);
            }
            return PhysMath.pack(supX, supY, supZ);
        }
        return PhysMath.pack(PhysMath.floor(x), PhysMath.floor(y - (double) offset), PhysMath.floor(z));
    }

    BlockInfo below(TerrainView v) {
        long p = onPos(v, 0.500001f);
        return v.at(PhysMath.unpackX(p), PhysMath.unpackY(p), PhysMath.unpackZ(p));
    }

    /** Entity.checkFallDamage (LivingEntity's first refreshes fluid contact); a landing is recorded for the simulator to price. */
    private void checkFallDamage(TerrainView v, double my, BlockInfo on, int ex, int ey, int ez) {
        if (living && !inWater) updateFluids(v);
        if (!inWater && my < 0.0) fallDistance -= (double) (float) my;
        if (onGround) {
            if (fallDistance > 0.0) {
                landed = true;
                landFall = fallDistance;
                landOn = on;
                landX = ex;
                landY = ey;
                landZ = ez;
            }
            fallDistance = 0;
        }
    }

    /** Entity.restituteMovementAfterCollisions: stop (or bounce) on the axes that collided. */
    private void restitute(BlockInfo effect, double my) {
        double r = sneaking ? 0.0 : bounciness;
        double cvx = vx, cvy = vy, cvz = vz;
        if (xColl) vx = -cvx * r;
        if (zColl) vz = -cvz * r;
        boolean b = r > 0.0 && hColl;
        if (vColl) {
            double g = effectiveGravity();
            if (vCollBelow) {
                float blockBounce = effect.bounce;
                if (!living) blockBounce *= 0.8f;
                r = -cvy <= g || sneaking || effect.is(BlockInfo.SUPPRESSES_BOUNCE) ? 0.0 : Math.max(r, blockBounce);
            }
            double comp, drag;
            if (r > 0.0) {
                double portion = my / cvy;
                comp = portion * g;
                drag = PhysMath.lerp(portion, 1.0, airDrag);
                b = true;
            } else {
                comp = 0.0;
                drag = 1.0;
            }
            vy = (comp - cvy) * drag * r;
        }
        bounced = b;
    }

    /** Entity.getBlockSpeedFactor, with LivingEntity's movement efficiency and Player's flying exemption. */
    private float blockSpeedFactor(TerrainView v) {
        if (player && (flying || fallFlying)) return 1.0f;
        BlockInfo here = v.at(PhysMath.floor(x), PhysMath.floor(y), PhysMath.floor(z));
        float f = here.speedFactor;
        if (!here.is(BlockInfo.WATER_BLOCK) && f == 1.0f) f = below(v).speedFactor;
        return living ? f + movementEfficiency * (1.0f - f) : f;
    }

    /** Entity.getBlockJumpFactor. */
    float blockJumpFactor(TerrainView v) {
        float f = v.at(PhysMath.floor(x), PhysMath.floor(y), PhysMath.floor(z)).jumpFactor;
        float g = below(v).jumpFactor;
        return (double) f == 1.0 ? g : f;
    }

    // ---- fluids ----

    /** Entity.updateFluidInteraction: what fluid the box (deflated by 0.001) is in and how deep; currents are only reported. */
    boolean updateFluids(TerrainView v) {
        double minX = x - halfW + 0.001, minY = y + 0.001, minZ = z - halfW + 0.001;
        double maxX = x + halfW - 0.001, maxY = y + height - 0.001, maxZ = z + halfW - 0.001;
        int x0 = PhysMath.floor(minX), y0 = PhysMath.floor(minY), z0 = PhysMath.floor(minZ);
        int x1 = (int) Math.ceil(maxX) - 1, y1 = (int) Math.ceil(maxY) - 1, z1 = (int) Math.ceil(maxZ) - 1;
        int eyeX = PhysMath.floor(x), eyeZ = PhysMath.floor(z);
        double eyeY = y + eyeHeight;
        boolean water = false, lava = false, eyes = false, current = false;
        double wh = 0, lh = 0;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    BlockInfo b = v.at(bx, by, bz);
                    if (b.fluid == BlockInfo.FLUID_NONE) continue;
                    double top = by + v.fluidHeight(bx, by, bz, b);
                    if (top < minY) continue;
                    if (b.fluid == BlockInfo.FLUID_WATER) {
                        water = true;
                        wh = Math.max(wh, top - y);
                        if (bx == eyeX && bz == eyeZ && eyeY >= by && eyeY <= top) eyes = true;
                    } else {
                        lava = true;
                        lh = Math.max(lh, top - y);
                    }
                    if (pushedByFluid && !current && currentAt(v, bx, by, bz, b)) current = true;
                }
            }
        }
        inWater = water;
        inLava = lava;
        eyeInWater = eyes;
        waterHeight = wh;
        lavaHeight = lh;
        if (water) resetFallDistance();
        if (current) v.assume(Assumption.FLUID_CURRENT);
        return water || lava;
    }

    /** Whether FluidState.getFlow could be non-zero here (conservative: any level difference or open fall beside it). */
    private static boolean currentAt(TerrainView v, int x, int y, int z, BlockInfo b) {
        if (b.flowing()) return true;
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            BlockInfo n = v.at(nx, y, nz);
            if (n.fluid == b.fluid) {
                if (n.fluidOwnHeight != b.fluidOwnHeight) return true;
            } else if (n.fluid == BlockInfo.FLUID_NONE && !n.hasCollision() && v.at(nx, y - 1, nz).fluid == b.fluid) {
                return true;
            }
        }
        return false;
    }

    double fluidHeight() {
        return Math.max(waterHeight, lavaHeight);
    }

    // ---- block effects ----

    /** Entity.applyEffectsFromBlocks: what the block stood on and the blocks passed through this tick do to motion. */
    void applyEffectsFromBlocks(TerrainView v) {
        if (onGround) {
            long p = onPos(v, 0.2f);
            BlockInfo on = v.at(PhysMath.unpackX(p), PhysMath.unpackY(p), PhysMath.unpackZ(p));
            if (on.effect == BlockInfo.EFFECT_SLIME) {
                double ay = Math.abs(vy);
                if (ay < 0.1 && !sneaking) {
                    double s = 0.4 + ay * 0.2;
                    vx *= s;
                    vz *= s;
                }
            }
        }
        visitedCount = 0;
        boolean far = (x - tickX) * (x - tickX) + (y - tickY) * (y - tickY) + (z - tickZ) * (z - tickZ) > 0.9999900000002526 * 0.9999900000002526;
        if (moved && (toX - x) * (toX - x) + (toY - y) * (toY - y) + (toZ - z) * (toZ - z) <= 9.999999439624929E-11
                && fromX == tickX && fromY == tickY && fromZ == tickZ) {
            // one recorded move: vanilla sweeps it axis by axis, Y first
            double px = fromX, py = fromY, pz = fromZ;
            sweep(v, px, py, pz, px, toY, pz, far, false);
            py = toY;
            if (zFirst) {
                sweep(v, px, py, pz, px, py, toZ, far, false);
                pz = toZ;
                sweep(v, px, py, pz, toX, py, pz, far, false);
            } else {
                sweep(v, px, py, pz, toX, py, pz, far, false);
                px = toX;
                sweep(v, px, py, pz, px, py, toZ, far, false);
            }
        } else {
            sweep(v, tickX, tickY, tickZ, x, y, z, far, true);
        }
    }

    /**
     * The effect blocks a box (deflated by 1e-5f) touches moving from one position to another. Straight diagonal
     * sweeps take the bounding box of the path, a superset of vanilla's cells; a cell only in that superset is reported.
     */
    private void sweep(TerrainView v, double ax, double ay, double az, double bx, double by, double bz, boolean far, boolean diagonal) {
        double e = (double) 1.0E-5f;
        double minX = Math.min(ax, bx) - halfW + e, maxX = Math.max(ax, bx) + halfW - e;
        double minY = Math.min(ay, by) + e, maxY = Math.max(ay, by) + height - e;
        double minZ = Math.min(az, bz) - halfW + e, maxZ = Math.max(az, bz) + halfW - e;
        int x0 = PhysMath.floor(minX), x1 = PhysMath.floor(maxX);
        int y0 = PhysMath.floor(minY), y1 = PhysMath.floor(maxY);
        int z0 = PhysMath.floor(minZ), z1 = PhysMath.floor(maxZ);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    BlockInfo b = v.at(cx, cy, cz);
                    if (b.effect == BlockInfo.EFFECT_NONE && !b.is(BlockInfo.UNMODELLED)) continue;
                    if (b.effect == BlockInfo.EFFECT_SLIME && !b.is(BlockInfo.UNMODELLED)) continue;
                    if (!visit(PhysMath.pack(cx, cy, cz))) continue;
                    boolean atEnd = boxHits(x, y, z, e, cx, cy, cz);
                    if (diagonal && !atEnd && !boxHits(ax, ay, az, e, cx, cy, cz)) v.assume(Assumption.INSIDE_EFFECT_APPROX);
                    if (b.is(BlockInfo.UNMODELLED)) v.assume(Assumption.UNMODELLED_BLOCK_EFFECT);
                    inside(v, b, cx, cy, cz, far || atEnd);
                }
            }
        }
    }

    private boolean boxHits(double px, double py, double pz, double e, int cx, int cy, int cz) {
        return px - halfW + e < cx + 1 && px + halfW - e > cx && py + e < cy + 1 && py + height - e > cy
                && pz - halfW + e < cz + 1 && pz + halfW - e > cz;
    }

    private boolean visit(long p) {
        for (int i = 0; i < visitedCount; i++) if (visited[i] == p) return false;
        if (visitedCount == visited.length) visited = Arrays.copyOf(visited, visitedCount * 2);
        visited[visitedCount++] = p;
        return true;
    }

    /** The motion part of Block.entityInside for the modelled blocks. */
    private void inside(TerrainView v, BlockInfo b, int bx, int by, int bz, boolean precise) {
        switch (b.effect) {
            case BlockInfo.EFFECT_COBWEB -> {
                if (living && weaving) makeStuck(0.5, 0.25, 0.5);
                else makeStuck(0.25, (double) 0.05f, 0.25);
            }
            case BlockInfo.EFFECT_POWDER_SNOW -> {
                if (!living || v.at(PhysMath.floor(x), PhysMath.floor(y), PhysMath.floor(z)).effect == BlockInfo.EFFECT_POWDER_SNOW) {
                    makeStuck((double) 0.9f, 1.5, (double) 0.9f);
                }
            }
            case BlockInfo.EFFECT_BERRY -> {
                if (living) makeStuck((double) 0.8f, 0.75, (double) 0.8f);
            }
            case BlockInfo.EFFECT_HONEY -> honeySlide(bx, by, bz);
            case BlockInfo.EFFECT_BUBBLE_UP, BlockInfo.EFFECT_BUBBLE_DOWN -> {
                if (!precise) return;
                boolean down = b.effect == BlockInfo.EFFECT_BUBBLE_DOWN;
                BlockInfo above = v.at(bx, by + 1, bz);
                if (!above.hasCollision() && above.fluid == BlockInfo.FLUID_NONE) {
                    vy = down ? Math.max(-0.9, vy - 0.03) : Math.min(1.8, vy + 0.1);
                } else {
                    vy = down ? Math.max(-0.3, vy - 0.03) : Math.min(0.7, vy + 0.06);
                    resetFallDistance();
                }
            }
            default -> {
            }
        }
    }

    /** HoneyBlock.isSlidingDown and doSlideMovement. */
    private void honeySlide(int bx, int by, int bz) {
        if (onGround || y > by + 0.9375 - 1.0E-7) return;
        double oldDy = vy / (double) 0.98f + 0.08;
        if (oldDy >= -0.08) return;
        double r = 0.4375 + (double) halfW;
        if (!(Math.abs(bx + 0.5 - x) + 1.0E-7 > r || Math.abs(bz + 0.5 - z) + 1.0E-7 > r)) return;
        double newDy = (-0.05 - 0.08) * (double) 0.98f;
        if (oldDy < -0.13) {
            double f = -0.05 / oldDy;
            vx *= f;
            vz *= f;
        }
        vy = newDy;
        resetFallDistance();
    }
}
