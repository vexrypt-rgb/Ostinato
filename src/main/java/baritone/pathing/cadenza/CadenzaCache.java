/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-process memo of candidate sets. Processes rebuild their goal every tick; the candidates for a target only change
 * when the world near it does, so a set is reused for {@link #ttlMs} and dropped at once when the target block itself
 * changed. The executor re-checks every movement's cost each tick and the process re-checks reach before clicking,
 * so a stale set can at worst send the bot to a cell it then replans from.
 * <p>
 * A change elsewhere can still make a set stale: a block the process itself placed or broke can hide or open the
 * faces of other targets near it, or make cells standable or not. The process says so ({@link #invalidateNear}), and
 * the sets of targets that block could matter to are dropped (v2.1: Builder kept walking to cells whose faces the
 * block it just placed had covered, for up to the TTL).
 */
public final class CadenzaCache {

    private record Key(Cadenza.Kind kind, int x, int y, int z, double reach, double eye, boolean drop) {}

    private record Entry(Cadenza.Result result, BlockState state, long at) {}

    private final Map<Key, Entry> map = new HashMap<>();
    private final long ttlMs;
    LongSupplier clock = System::currentTimeMillis;

    public CadenzaCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public Cadenza.Result resolve(CalculationContext ctx, Cadenza.Target t, Cadenza.Forbidden forbidden) {
        Key k = new Key(t.kind, t.pos.x, t.pos.y, t.pos.z, t.reach, t.eye, t.collectDrop);
        long now = clock.getAsLong();
        BlockState state = ctx.bsi.get0(t.pos.x, t.pos.y, t.pos.z);
        Entry e = map.get(k);
        if (e != null && now - e.at < ttlMs && Objects.equals(e.state, state)) {
            CadenzaStats.cacheHit();
            return e.result;
        }
        long t0 = System.nanoTime();
        Cadenza.Result r = Cadenza.candidates(ctx, t, forbidden);
        CadenzaStats.result(r, System.nanoTime() - t0);
        map.put(k, new Entry(r, state, now));
        if (map.size() > 256) {
            map.entrySet().removeIf(en -> now - en.getValue().at >= ttlMs);
        }
        return r;
    }

    public List<Cadenza.Result> resolveAll(CalculationContext ctx, List<Cadenza.Target> targets, Cadenza.Forbidden forbidden) {
        List<Cadenza.Result> out = new ArrayList<>(targets.size());
        for (Cadenza.Target t : targets) {
            out.add(resolve(ctx, t, forbidden));
        }
        return out;
    }

    /** Extra blocks beyond a target's reach a changed block can still matter to (cell extent, standing on it). */
    static final double NEAR_MARGIN = 2.0;

    /**
     * The block at {@code changed} was just changed by the process: drop the sets of targets it is within reach
     * (+ {@link #NEAR_MARGIN}) of, since it may now block or open their rays or cells. Returns how many were dropped.
     */
    public int invalidateNear(BlockPos changed) {
        int before = map.size();
        map.keySet().removeIf(k -> {
            double dx = k.x - changed.getX(), dy = k.y - changed.getY(), dz = k.z - changed.getZ(), r = k.reach + NEAR_MARGIN;
            return dx * dx + dy * dy + dz * dz <= r * r;
        });
        return before - map.size();
    }

    int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }
}
