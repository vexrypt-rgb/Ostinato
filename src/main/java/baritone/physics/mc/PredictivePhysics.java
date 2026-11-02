/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics.mc;

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.physics.BodyState;
import baritone.api.physics.IPredictivePhysics;
import baritone.api.physics.PhysicsTelemetry;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionTicket;
import baritone.api.utils.Pair;
import baritone.behavior.Behavior;
import baritone.physics.PhysicsStats;
import baritone.physics.PredictionCache;
import baritone.physics.PredictionScheduler;
import baritone.physics.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import baritone.physics.PredictorWarmUp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ostinato: the engine in the game. Captures the region a request can reach (see {@link #region}) on the game thread,
 * then answers inline or hands it to the scheduler's worker, which starts on the first {@link #submit}. Costs nothing
 * while nobody asks: no thread, no copies, one counter per tick. Main-thread time (capture and inline work) is
 * recorded per tick in the telemetry's tick histogram.
 */
public final class PredictivePhysics extends Behavior implements IPredictivePhysics {
    /** Region half-extents caps, blocks: at most 6 x 6 columns by 10 sections (360, under TerrainCapture.MAX_REGION). */
    static final int REACH_H = 40, REACH_UP = 32, REACH_DOWN = 96;
    static final int QUEUE = 64, RESULTS = 256;

    private final PhysicsStats stats = new PhysicsStats();
    private final PredictionCache cache = new PredictionCache(RESULTS);
    private final PredictionScheduler scheduler = new PredictionScheduler(QUEUE, cache, stats);
    private final TerrainCapture capture = new TerrainCapture(stats);
    private final int[] box = new int[6];
    private long tick, spentThisTick;

    public PredictivePhysics(Baritone baritone) {
        super(baritone);
    }

    @Override
    public PredictionResult predict(PredictionRequest r) {
        PredictionResult refused = refuse(r);
        if (refused != null) return refused;
        long t0 = System.nanoTime();
        try {
            return scheduler.predict(r, snapshot(r));
        } finally {
            spent(t0);
        }
    }

    @Override
    public PredictionTicket submit(PredictionRequest r) {
        PredictionResult refused = refuse(r);
        if (refused != null) return scheduler.done(r, refused);
        long t0 = System.nanoTime();
        try {
            scheduler.start();
            return scheduler.submit(r, snapshot(r));
        } finally {
            spent(t0);
        }
    }

    @Override
    public BodyState capture(Entity entity) {
        if (!ctx.minecraft().isSameThread()) throw new IllegalStateException("capture off the game thread");
        if (!BodyCapture.supported(entity)) throw new IllegalArgumentException("no body type for " + entity.getType());
        return BodyCapture.read(entity, entity == ctx.player(), baritone.getHealthEstimator());
    }

    @Override
    public boolean warmUp() {
        PredictorWarmUp.start();
        return PredictorWarmUp.done();
    }

    @Override
    public PhysicsTelemetry telemetry() {
        return scheduler.telemetry();
    }

    /** Captures that stopped copying at their request's deadline (Presto v4 budget). */
    public long captureCuts() {
        return stats.captureCuts.sum();
    }

    /** Section copies currently kept. */
    public int retainedSections() {
        return capture.retained();
    }

    private PredictionResult refuse(PredictionRequest r) {
        if (!ctx.minecraft().isSameThread()) return PredictionResult.failed(PredictionResult.Status.FAILED, PredictionResult.Failure.NOT_MAIN_THREAD, r.fidelity, 0);
        if (!(ctx.world() instanceof ClientLevel)) return PredictionResult.failed(PredictionResult.Status.FAILED, PredictionResult.Failure.NO_WORLD, r.fidelity, 0);
        return null;
    }

    private void spent(long t0) {
        long dt = System.nanoTime() - t0;
        stats.mainThreadNanos.add(dt);
        spentThisTick += dt;
    }

    private Terrain snapshot(PredictionRequest r) {
        ClientLevel level = (ClientLevel) ctx.world();
        double ox, oy, oz;
        if (r.subject != null) {
            ox = r.subject.x;
            oy = r.subject.y;
            oz = r.subject.z;
        } else {
            ox = r.ex;
            oy = r.ey;
            oz = r.ez;
        }
        region(r, box);
        return capture.capture(level, tick, box[0], box[1], box[2], box[3], box[4], box[5], ox, oy, oz,
                r.subject == null ? -1 : r.subject.entityId, r.deadlineNanos);
    }

    /**
     * The block box {x0, y0, z0, x1, y1, z1} a request can reach within its horizon: a generous bound from the
     * subject's speed, drag and gravity (living: input speed, air-drag decay, terminal fall; projectiles: drag-bounded
     * travel and terminal fall; TNT: its blast radius around anywhere it can drift), or the blast radius around an
     * explosion. Capped by {@link #REACH_H}, {@link #REACH_UP} and {@link #REACH_DOWN}; a path that leaves it stops
     * PARTIAL with OUT_OF_SNAPSHOT rather than guessing.
     */
    static void region(PredictionRequest r, int[] out) {
        double cx, cy, cz, h, up, down;
        if (r.kind == PredictionRequest.Kind.EXPLOSION) {
            cx = r.ex;
            cy = r.ey;
            cz = r.ez;
            h = up = down = 2 * r.power + 1;
        } else {
            BodyState s = r.subject;
            int t = r.horizonTicks;
            double vh = Math.hypot(s.vx, s.vz), g = Math.max(s.gravity, 1e-3);
            cx = s.x;
            cy = s.y;
            cz = s.z;
            if (s.isLiving()) {
                if (s.has(BodyState.FALL_FLYING) || s.has(BodyState.FLYING)) {
                    h = up = down = 3.5 * t;
                } else {
                    boolean moves = r.input.forward != 0 || r.input.strafe != 0;
                    h = vh * Math.min(t, 11) + (moves ? 0.45 * t : 0); // air drag 0.91 keeps at most 11x the speed
                    up = (s.vy > 0 ? s.vy * s.vy / (2 * g) : 0) + (r.input.jump ? 1.3 + 0.6 * s.jumpBoost : 0) + 0.1 * s.levitation * t;
                    down = 3.92 * t; // players' terminal speed
                }
            } else if (s.isProjectile()) {
                double keep = s.drag >= 1 ? t : Math.min(t, 1 / (1 - s.drag));
                h = (vh + (s.drag >= 1 ? s.acceleration * t : s.acceleration / (1 - s.drag))) * keep;
                up = s.vy > 0 ? Math.min(s.vy * t, s.vy * s.vy / (2 * g)) : 0;
                down = t * Math.max(-s.vy, s.drag >= 1 ? 0 : s.gravity / (1 - s.drag)) + (s.acceleration > 0 ? s.acceleration * t : 0);
            } else { // primed TNT, falling block: drag 0.98, gravity 0.04
                h = vh * Math.min(t, 50);
                up = s.vy > 0 ? s.vy * s.vy / (2 * g) : 0;
                down = 2 * t;
                if (s.type == BodyState.Type.PRIMED_TNT) {
                    double blast = 2 * s.explosionPower + 1;
                    h += blast;
                    up += blast;
                    down += blast;
                }
            }
            h += s.width / 2 + 2;
            up += s.height + 2;
            down += 2;
        }
        h = Math.min(h, REACH_H);
        up = Math.min(up, REACH_UP);
        down = Math.min(down, REACH_DOWN);
        out[0] = (int) Math.floor(cx - h);
        out[1] = (int) Math.floor(cy - down);
        out[2] = (int) Math.floor(cz - h);
        out[3] = (int) Math.floor(cx + h);
        out[4] = (int) Math.floor(cy + up);
        out[5] = (int) Math.floor(cz + h);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;
        tick++;
        if (spentThisTick > 0) {
            stats.tick.recordNanos(spentThisTick);
            spentThisTick = 0;
        }
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        for (Pair<BlockPos, BlockState> p : event.getBlocks()) {
            BlockPos pos = p.first();
            capture.blockChanged(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (event.getState() == EventState.POST && ctx.world() instanceof ClientLevel level) capture.chunkChanged(level, event.getX(), event.getZ());
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() != EventState.POST) return;
        capture.clear();
        cache.clear();
        scheduler.stop();
    }
}
