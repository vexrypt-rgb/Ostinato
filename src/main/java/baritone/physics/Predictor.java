/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Exposure;
import baritone.api.physics.PredictionResult.Failure;
import baritone.health.DamageMath;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Ostinato: runs one {@link PredictionRequest} over one {@link Terrain}. Picks the simulation by the subject's
 * family (living, projectile, primed TNT / falling block), records the path at the fidelity's stride, and turns
 * what happened into a {@link PredictionResult}: landing and fall damage, projectile hit, TNT explosion. Fidelity
 * changes what is recorded and worked out, never the physics. Reusable, not thread safe: one per thread.
 */
public final class Predictor {
    private static final int LIVING = 0, PROJECTILE = 1, INERT = 2;

    private final TerrainView view = new TerrainView();
    private final LivingSim living = new LivingSim();
    private final Body inert = new Body();
    private final ProjectileSim projectile = new ProjectileSim();
    private final ExplosionSim explosion = new ExplosionSim();
    private double[] eyes = new double[3 * 64];
    private long deadline;
    /** Internal failures seen by this predictor; drives the rate limit on the diagnostic below. */
    private int internalFailures;

    /** Path stride for a fidelity: SCREENING records none, STANDARD every 4 ticks, HIGH and VALIDATION every tick. */
    public static int stride(Fidelity f) {
        return switch (f) {
            case SCREENING -> 0;
            case STANDARD -> 4;
            default -> 1;
        };
    }

    public PredictionResult run(PredictionRequest r, Terrain terrain, Fidelity fidelity, BooleanSupplier cancelled) {
        return run(r, terrain, fidelity, r.deadlineNanos, cancelled);
    }

    /** As {@link #run(PredictionRequest, Terrain, Fidelity, BooleanSupplier)} with a deadline other than the request's (0 none). */
    public PredictionResult run(PredictionRequest r, Terrain terrain, Fidelity fidelity, long deadlineNanos, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        this.deadline = deadlineNanos;
        view.reset(terrain);
        PredictionResult.Builder out = new PredictionResult.Builder(fidelity);
        if (r.subject != null && r.subject.has(BodyState.CLIENT_VIEW)) out.assume(Assumption.CLIENT_STATE);
        boolean ok;
        try {
            ok = r.kind == PredictionRequest.Kind.EXPLOSION
                    ? explode(r, fidelity, out, cancelled, 0, r.ex, r.ey, r.ez, r.power, r.breaksBlocks, -1)
                    : simulate(r, fidelity, out, cancelled);
        } catch (RuntimeException e) {
            // Ostinato: this used to discard the exception entirely, so a modelling bug reached the consumer as a
            // bare FAILED/INTERNAL that is indistinguishable from a legitimate refusal — nothing to debug with.
            // Log the first one with its stack, then every 1000th, so a systematic defect is visible without a
            // predictor that is called hundreds of times per tick flooding the log. The result is unchanged.
            if (internalFailures == 0 || internalFailures % 1000 == 999) {
                System.err.println("[PPE] prediction reported FAILED/INTERNAL (kind=" + r.kind
                        + ", consumer=" + r.consumer
                        + ", subject=" + (r.subject == null ? "none" : r.subject)
                        + ", occurrence " + (internalFailures + 1) + "): " + e);
                e.printStackTrace();
            }
            internalFailures++;
            return PredictionResult.failed(PredictionResult.Status.FAILED, Failure.INTERNAL, fidelity, 0);
        }
        if (!ok) return PredictionResult.failed(PredictionResult.Status.CANCELLED, Failure.CANCELLED, fidelity, 0);
        return out.assume(view.assumptions).computeNanos(System.nanoTime() - start).build();
    }

    private boolean simulate(PredictionRequest r, Fidelity fidelity, PredictionResult.Builder out, BooleanSupplier cancelled) {
        BodyState s = r.subject;
        int mode;
        Body body;
        if (s.isLiving()) {
            living.set(s, r.input);
            body = living.b;
            mode = LIVING;
            out.assume(Assumption.INPUT_HELD);
        } else if (s.isProjectile()) {
            projectile.set(s, r.targets);
            body = projectile.b;
            mode = PROJECTILE;
            for (BodyState t : r.targets) {
                out.assume(Assumption.TARGETS_FROZEN);
                if (t.has(BodyState.CLIENT_VIEW)) out.assume(Assumption.CLIENT_STATE);
            }
        } else {
            inert.set(s);
            body = inert;
            mode = INERT;
        }
        if (r.kind == PredictionRequest.Kind.PROJECTILE && mode != PROJECTILE) {
            out.fail(Failure.UNSUPPORTED);
            return true;
        }
        boolean tnt = s.type == BodyState.Type.PRIMED_TNT, fallingBlock = s.type == BodyState.Type.FALLING_BLOCK;
        // The blast a primed TNT will make rests on a power the client cannot read; say so instead of implying it was read.
        if (tnt && s.has(BodyState.POWER_ASSUMED)) out.assume(Assumption.TNT_POWER_ASSUMED);
        boolean fallKind = r.kind == PredictionRequest.Kind.FALL;
        body.updateFluids(view); // the contact its last tick left it with
        out.end(body.x, body.y, body.z, body.vx, body.vy, body.vz, body.onGround);
        if (view.outside()) {
            view.assumptions &= ~Assumption.OUTSIDE_SNAPSHOT.bit();
            out.partial(Failure.OUT_OF_SNAPSHOT).horizon(0);
            return true;
        }
        int stride = stride(fidelity);
        float[] path = stride == 0 ? null : new float[3 * (r.horizonTicks / stride + 1)];
        int pathN = 0;
        if (path != null) pathN = point(path, pathN, body);
        int horizon = r.horizonTicks;
        if (eyes.length < 3 * (horizon + 1)) eyes = new double[3 * (horizon + 1)];
        eye(0, body);
        int fuse = s.fuse;
        PredictionResult.Landing landing = null;
        int done = 0;
        for (int t = 1; t <= horizon; t++) {
            if ((t & 7) == 0) {
                if (cancelled.getAsBoolean()) return false;
                if (deadline != 0 && System.nanoTime() - deadline > 0) {
                    out.partial(Failure.DEADLINE);
                    break;
                }
            }
            if (mode == PROJECTILE && projectile.inGround) break;
            long before = view.assumptions;
            double prevFall = body.fallDistance;
            boolean explodes = false;
            switch (mode) {
                case LIVING -> living.tick(view);
                case PROJECTILE -> projectile.tick(view);
                default -> {
                    body.startTick();
                    if (body.gravity != 0.0) body.vy -= body.gravity;
                    body.move(view, body.vx, body.vy, body.vz);
                    body.applyEffectsFromBlocks(view);
                    if (tnt) {
                        body.vx *= (double) 0.98f;
                        body.vy *= (double) 0.98f;
                        body.vz *= (double) 0.98f;
                        if (body.onGround) {
                            body.vx *= 0.7;
                            body.vy *= -0.5;
                            body.vz *= 0.7;
                        }
                        if (--fuse <= 0) explodes = true;
                        else body.updateFluids(view);
                    } else {
                        if (body.onGround) {
                            body.vx *= 0.7;
                            body.vy *= -0.5;
                            body.vz *= 0.7;
                        }
                        body.vx *= (double) 0.98f;
                        body.vy *= (double) 0.98f;
                        body.vz *= (double) 0.98f;
                    }
                }
            }
            if (view.outside()) { // this tick read cells that were not copied: keep what came before it
                view.assumptions = before;
                out.partial(Failure.OUT_OF_SNAPSHOT);
                break;
            }
            done = t;
            out.end(body.x, body.y, body.z, body.vx, body.vy, body.vz, body.onGround);
            if (body.hColl || body.vColl || mode == PROJECTILE && projectile.hit) out.firstCollision(t);
            eye(t, body);
            if (path != null && t % stride == 0) pathN = point(path, pathN, body);
            if (explodes) {
                double cy = body.y + (double) body.height * 0.0625;
                if (!explode(r, fidelity, out, cancelled, t, body.x, cy, body.z, s.explosionPower, true, s.entityId)) return false;
                break;
            }
            if (mode == PROJECTILE) {
                if (projectile.hit) {
                    out.hit(new PredictionResult.Hit(t, projectile.hitX, projectile.hitY, projectile.hitZ, projectile.hitTarget,
                            projectile.hitBlockX, projectile.hitBlockY, projectile.hitBlockZ, projectile.hitFace, projectile.hitSpeed));
                    break;
                }
                continue;
            }
            boolean water = body.inWater && prevFall > 0.0;
            if (landing == null && (body.landed || water || fallKind && (body.onGround || body.inWater) || fallingBlock && body.onGround)) {
                landing = landing(r, s, mode, body, t, prevFall, water);
                out.landing(landing);
                if (fallKind || fallingBlock) break;
            }
        }
        out.horizon(done);
        if (path != null) {
            if (pathN * 3 < path.length) path = java.util.Arrays.copyOf(path, pathN * 3);
            out.path(path, stride);
        }
        return true;
    }

    private static int point(float[] path, int n, Body b) {
        path[n * 3] = (float) b.x;
        path[n * 3 + 1] = (float) b.y;
        path[n * 3 + 2] = (float) b.z;
        return n + 1;
    }

    private void eye(int t, Body b) {
        eyes[t * 3] = b.x;
        eyes[t * 3 + 1] = b.y + b.eyeHeight;
        eyes[t * 3 + 2] = b.z;
    }

    private PredictionResult.Landing landing(PredictionRequest r, BodyState s, int mode, Body b, int t, double prevFall, boolean water) {
        int bx, by, bz;
        double fall;
        BlockInfo on;
        if (b.landed) {
            bx = b.landX;
            by = b.landY;
            bz = b.landZ;
            fall = b.landFall;
            on = b.landOn;
        } else if (water) {
            bx = PhysMath.floor(b.x);
            by = PhysMath.floor(b.y);
            bz = PhysMath.floor(b.z);
            fall = prevFall;
            on = null;
        } else {
            long p = b.onPos(view, 0.2f);
            bx = PhysMath.unpackX(p);
            by = PhysMath.unpackY(p);
            bz = PhysMath.unpackZ(p);
            fall = 0;
            on = null;
        }
        float damage = 0;
        boolean lethal = false;
        if (mode == LIVING && on != null) {
            damage = fallDamage(s, on, fall, b.sneaking);
            lethal = damage > 0 && damage >= s.health + s.absorption;
        }
        int reachTick = -1;
        if (mode == LIVING) {
            double reachSq = (double) r.reach * r.reach;
            for (int i = 0; i <= t; i++) {
                double dx = Math.max(Math.max(bx - eyes[i * 3], 0), eyes[i * 3] - (bx + 1));
                double dy = Math.max(Math.max(by - eyes[i * 3 + 1], 0), eyes[i * 3 + 1] - (by + 1));
                double dz = Math.max(Math.max(bz - eyes[i * 3 + 2], 0), eyes[i * 3 + 2] - (bz + 1));
                if (dx * dx + dy * dy + dz * dz <= reachSq) {
                    reachTick = i;
                    break;
                }
            }
        }
        return new PredictionResult.Landing(t, b.x, b.y, b.z, bx, by, bz, fall, damage, lethal, b.bounced, water || b.inWater, reachTick);
    }

    /**
     * What landing does to health: the landed-on block's Block.fallOn, LivingEntity.calculateFallDamage, then
     * Resistance and Feather Falling / Protection (fall damage ignores armour and difficulty). Totems are not considered.
     */
    static float fallDamage(BodyState s, BlockInfo on, double fall, boolean suppressingBounce) {
        if (s.has(BodyState.FALL_IMMUNE)) return 0;
        if (s.type == BodyState.Type.PLAYER && s.has(BodyState.MAY_FLY)) return 0;
        double distance;
        float modifier;
        switch (on.fall) {
            case BlockInfo.FALL_HAY, BlockInfo.FALL_HONEY -> {
                distance = fall;
                modifier = 0.2f;
            }
            case BlockInfo.FALL_SLIME -> {
                if (suppressingBounce) return 0;
                distance = fall;
                modifier = 0.0f;
            }
            case BlockInfo.FALL_NONE -> {
                return 0;
            }
            case BlockInfo.FALL_DRIPSTONE -> {
                distance = fall + 2.5;
                modifier = 2.0f;
            }
            default -> {
                distance = fall * (double) (1.0f - on.fallReduction);
                modifier = 1.0f;
            }
        }
        int raw = PhysMath.floor((distance + 1.0E-6 - (double) s.safeFallDistance) * (double) modifier * (double) s.fallDamageMultiplier);
        if (raw <= 0) return 0;
        return DamageMath.afterMagic((float) raw, s.resistance - 1, s.fallProtection);
    }

    private boolean explode(PredictionRequest r, Fidelity fidelity, PredictionResult.Builder out, BooleanSupplier cancelled, int tick,
                            double x, double y, double z, float power, boolean breaks, int sourceId) {
        List<Exposure> exposures = new ArrayList<>(r.targets.size());
        for (BodyState t : r.targets) {
            if (cancelled.getAsBoolean()) return false;
            if (deadline != 0 && System.nanoTime() - deadline > 0) {
                out.partial(Failure.DEADLINE);
                return true;
            }
            Exposure e = sourceId >= 0 && t.entityId == sourceId ? null : ExplosionSim.expose(view, x, y, z, power, t, r.difficulty);
            exposures.add(e != null ? e : new Exposure(0, 0, false, 0, 0, 0));
        }
        out.assume(ExplosionSim.targetAssumptions(r.targets, tick, sourceId));
        long[] certain = null, possible = null, chain = new long[0];
        if (breaks && fidelity.ordinal() >= Fidelity.HIGH.ordinal()) {
            if (!explosion.blocks(view, x, y, z, power,
                    () -> cancelled.getAsBoolean() || deadline != 0 && System.nanoTime() - deadline > 0)) {
                if (cancelled.getAsBoolean()) return false;
                out.partial(Failure.DEADLINE);
                return true;
            }
            certain = explosion.certain.toLongArray();
            possible = explosion.possible.toLongArray();
            java.util.Arrays.sort(certain);
            java.util.Arrays.sort(possible);
            chain = explosion.chainTnt(view);
            if (certain.length != possible.length) out.assume(Assumption.RANDOM_BLOCK_DAMAGE);
            if (chain.length > 0) out.assume(Assumption.RANDOM_FUSE);
        }
        out.explosion(new PredictionResult.Explosion(tick, x, y, z, power, exposures, certain, possible, chain));
        return true;
    }
}
