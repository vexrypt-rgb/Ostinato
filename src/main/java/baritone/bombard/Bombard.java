/*
 * Ostinato: new file (Bombard TNT combat). Not part of upstream Baritone.
 */
package baritone.bombard;

import baritone.Baritone;
import baritone.api.bombard.BombardPermit;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.physics.BodyState;
import baritone.api.physics.IPredictivePhysics;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionTicket;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bombard's hold on the game: one per fight process, ticked as a game listener so it keeps watching TNT whether or
 * not the fight runs (cancelling the fight does not put a fuse out). Each tick it feeds every primed TNT within
 * {@link #SCAN} to the {@link TntTracker}, matches explode packets to them, and asks the engine once per TNT where
 * and when it goes off ({@link BlastForecast}), refreshing expired observations within a bounded submission budget.
 * <p>
 * Mode from {@code -Dostinato.bombard=off|observe|live}, <b>off by default</b>. OFF does nothing. OBSERVE tracks,
 * forecasts and measures, and acts on nothing. LIVE would let the fight use TNT, within the {@link BombardPermit} the
 * driver granted ({@link BombardPermit#DENY} by default) and only for primitives {@link TntMechanics#support} marks
 * verified for this version. PvpProcess owns the optional rod/spear input adapters.
 * <p>
 * The hazard of a burning TNT reaches the Threat Assessment by its own scan of the world, not through Bombard, so it
 * holds whatever Bombard's state.
 */
public final class Bombard implements AbstractGameEventListener {

    public enum Mode { OFF, OBSERVE, LIVE }

    /** Within the explode packet's 64 blocks, so a TNT that goes off in view is heard. */
    public static final double SCAN = 48;
    static final int SUBMITS_PER_TICK = 2, OUTSTANDING = 8, QUEUE = 64;

    private record Boom(double x, double y, double z, float radius, int blocks, boolean hitUs) {}

    private final Baritone baritone;
    private final Mode mode;
    private TntMechanics.Support support;
    private volatile BombardPermit permit = BombardPermit.DENY;
    private final TntTracker tracker = new TntTracker();
    // explode packets arrive on the network thread: queued there, drained on the game thread
    private final ConcurrentLinkedQueue<Boom> booms = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<Integer, PredictionTicket> pending = new HashMap<>();
    private final Map<Integer, Long> submittedAt = new HashMap<>();
    private final Map<Integer, BlastForecast> forecasts = new HashMap<>();
    private final Map<Integer, Long> forecastAt = new HashMap<>();
    static final int FORECAST_TTL = 2;
    private LivingEntity foe;
    private Level level;
    private long tick, ticks, nanos, maxNanos, submitted, answered, failed;
    /** Written only on the network thread. */
    private volatile long droppedBooms;

    public Bombard(Baritone baritone) {
        this(baritone, parse(System.getProperty("ostinato.bombard", "off")));
    }

    public Bombard(Baritone baritone, Mode mode) {
        this.baritone = baritone;
        this.mode = mode;
    }

    static Mode parse(String s) {
        try {
            return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }

    public Mode mode() {
        return mode;
    }

    public BombardPermit permit() {
        return permit;
    }

    public void permit(BombardPermit p) {
        permit = p == null ? BombardPermit.DENY : p;
    }

    /** The version gate; read once, from the running game. */
    public TntMechanics.Support support() {
        if (support == null) support = TntMechanics.support(SharedConstants.getCurrentVersion().name());
        return support;
    }

    /** May the fight use this primitive now? LIVE, verified for this version, and granted. */
    public boolean may(BombardPermit.Primitive p) {
        if (mode != Mode.LIVE || !permit.allows(p)) return false;
        TntMechanics.Support s = support();
        return switch (p) {
            case ROD -> s.rod();
            case SPEAR -> s.spear();
            case CHAIN -> s.chain();
        };
    }

    public TntTracker tracker() {
        return tracker;
    }

    /** Recent observational forecast, or null. Never authorizes an action: live hands re-capture and predict inline. */
    public BlastForecast forecast(int id) {
        TntTracker.Tnt t = tracker.get(id);
        Long at = forecastAt.get(id);
        return t != null && t.live() && at != null && tick - at <= FORECAST_TTL ? forecasts.get(id) : null;
    }

    /** Who the fight is against: the forecasts price each blast against it as well as us. Null between fights. */
    public void foe(LivingEntity e) {
        if (foe != e) invalidateForecasts();
        foe = e;
    }

    /** Terrain and target changes invalidate queued answers as well as delivered ones. */
    @Override public void onBlockChange(BlockChangeEvent event) { invalidateForecasts(); }
    @Override public void onChunkEvent(ChunkEvent event) { invalidateForecasts(); }

    private void invalidateForecasts() {
        for (PredictionTicket p : pending.values()) p.cancel();
        pending.clear();
        submittedAt.clear();
        forecasts.clear();
        forecastAt.clear();
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (mode == Mode.OFF || event.getState() != EventState.PRE) return;
        if (!(event.getPacket() instanceof ClientboundExplodePacket p)) return;
        if (queued.incrementAndGet() > QUEUE) {
            queued.decrementAndGet();
            droppedBooms = droppedBooms + 1;
            return;
        }
        booms.add(new Boom(p.center().x, p.center().y, p.center().z, p.radius(), p.blockCount(), p.playerKnockback().isPresent()));
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (mode == Mode.OFF) return;
        leave();
    }

    @Override
    public void onTick(TickEvent event) {
        if (mode == Mode.OFF || event.getState() != EventState.PRE) return;
        Player me = baritone.getPlayerContext().player();
        if (event.getType() != TickEvent.Type.IN || me == null || baritone.getPlayerContext().world() == null) {
            if (level != null) leave();
            return;
        }
        long t0 = System.nanoTime();
        Level lvl = me.level();
        if (lvl != level) {
            leave();
            level = lvl;
        }
        tick++;
        tracker.begin(tick, me.getX(), me.getY(), me.getZ(), SCAN);
        for (Boom b; (b = booms.poll()) != null; ) {
            queued.decrementAndGet();
            tracker.explosion(b.x, b.y, b.z, b.radius, b.blocks, b.hitUs);
        }
        List<PrimedTnt> seen = lvl.getEntitiesOfClass(PrimedTnt.class, me.getBoundingBox().inflate(SCAN), e -> e.isAlive());
        List<PrimedTnt> fresh = new ArrayList<>();
        for (PrimedTnt e : seen) {
            TntTracker.Tnt t = tracker.see(e.getId(), e.getX(), e.getY(), e.getZ(), e.getDeltaMovement().x, e.getDeltaMovement().y,
                    e.getDeltaMovement().z, e.getFuse(), e.onGround());
            if (t != null && t.live() && forecast(t.id) == null && !pending.containsKey(t.id)) fresh.add(e);
        }
        tracker.end();
        settle();
        // Oldest request first: a crowded scan must not starve later entities behind repeated refreshes.
        fresh.sort(java.util.Comparator.comparingLong(e -> forecastAt.getOrDefault(e.getId(), Long.MIN_VALUE)));
        submit(me, fresh);
        long dt = System.nanoTime() - t0;
        ticks++;
        nanos += dt;
        maxNanos = Math.max(maxNanos, dt);
    }

    /** Collect answered forecasts; drop questions about TNT that have ended. */
    private void settle() {
        Iterator<Map.Entry<Integer, PredictionTicket>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PredictionTicket> en = it.next();
            int id = en.getKey();
            TntTracker.Tnt t = tracker.get(id);
            if (t == null || !t.live() || tick - submittedAt.get(id) > FORECAST_TTL) {
                en.getValue().cancel();
                it.remove();
                submittedAt.remove(id);
                continue;
            }
            if (!en.getValue().isDone()) continue;
            long at = submittedAt.remove(id);
            int targets = en.getValue().request().targets.size();
            BlastForecast f = BlastForecast.of(en.getValue().result(), at, targets);
            it.remove();
            forecasts.put(id, f);
            forecastAt.put(id, at);
            if (f.ok()) {
                answered++;
                tracker.forecast(id, f.explodeTick(), f.x(), f.y(), f.z(), f.confidence());
            } else failed++;
        }
        forecasts.keySet().removeIf(id -> tracker.get(id) == null);
        forecastAt.keySet().removeIf(id -> tracker.get(id) == null);
    }

    /** Ask about TNT first seen this tick: a few a tick, a few outstanding, on the engine's worker. */
    private void submit(Player me, List<PrimedTnt> fresh) {
        if (fresh.isEmpty()) return;
        IPredictivePhysics physics = baritone.getPredictivePhysics();
        if (physics == null) return;
        int n = 0;
        for (PrimedTnt e : fresh) {
            if (n >= SUBMITS_PER_TICK || pending.size() >= OUTSTANDING) break;
            try {
                List<BodyState> targets = new ArrayList<>(2);
                targets.add(physics.capture(me));
                if (foe != null && foe.isAlive() && foe.level() == me.level()) targets.add(physics.capture(foe));
                PredictionRequest r = BlastForecast.request(physics.capture(e), targets, me.level().getDifficulty().getId(),
                        PredictionRequest.Fidelity.SCREENING, PredictionRequest.Priority.PLANNING);
                pending.put(e.getId(), physics.submit(r));
                submittedAt.put(e.getId(), tick);
                submitted++;
                n++;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                failed++;
                forecasts.put(e.getId(), BlastForecast.of(null, tick, 0));
                forecastAt.put(e.getId(), tick); // bounded retry after TTL, never cache failure forever
            }
        }
    }

    private void leave() {
        invalidateForecasts();
        tracker.forget();
        for (Boom b; (b = booms.poll()) != null; ) queued.decrementAndGet();
        level = null;
        foe = null;
    }

    /** A fight starts: its counters start from nothing. Tracked TNT are kept: they are still burning. */
    public void fightStart() {
        if (mode == Mode.OFF) return;
        tracker.clearCounters();
        submitted = answered = failed = 0;
    }

    /** The fight ended: Bombard's measure of it to the fight log. */
    public void fightEnd(Consumer<String> note) {
        if (mode == Mode.OFF) return;
        String r = tracker.report("fight");
        if (!r.isEmpty()) note.accept(r);
        TntMechanics.Support s = support();
        note.accept(String.format(Locale.ROOT, "bombard mode=%s version=%s rod=%b spear=%b chain=%b permit=%s forecasts asked=%d answered=%d failed=%d dropped-booms=%d",
                mode, s.version(), s.rod(), s.spear(), s.chain(), permit, submitted, answered, failed, droppedBooms));
        note.accept(String.format(Locale.ROOT, "bombard cost ticks=%d mean=%.1fus max=%.1fus", ticks, ticks == 0 ? 0 : nanos / 1000.0 / ticks, maxNanos / 1000.0));
        tracker.clearCounters();
        submitted = answered = failed = 0;
    }
}
