/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.HealthEstimate;
import baritone.behavior.Behavior;
import baritone.pathing.kinematic.ClientWorld;
import baritone.process.PvpRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ostinato: watches other players fight and learns from it ({@code spectateLearn}). The game thread only copies:
 * each position packet of a watched player becomes an {@link InputDecoder.Obs} with a small {@link SnapshotWorld}
 * of the blocks around them, and each swing, hit, apple, pearl, potion, totem and death becomes an event; all of it
 * goes in order down one bounded queue to a single low-priority worker thread, which owns the decoders, the
 * {@link Habits} and the {@link FightBook}. So the render thread pays for copying a few hundred block cells now and
 * then and nothing else, and the worker's time is held to {@code spectateBudgetMs} a tick by watching fewer players
 * (the nearest first) when it runs over.
 * <p>
 * What it learns goes to {@link SpectatePriors}: start points for the Autotune Combat System's search and the styles
 * of players it may meet. Nothing learned here is ever put in force on its own; the bot's own fights decide.
 */
public final class SpectateLearner extends Behavior implements PacketTap.Listener {
    private static final String DECODE_LOG = System.getProperty("ostinato.spectate.decodeLog");
    private static final double MARGIN = 3;

    /** Main-thread state of one watched player. */
    /** -Dostinato.spectate.mainClock=true: count interval ticks from game-thread arrival as before (A/B only). */
    private static final boolean MAIN_CLOCK = Boolean.getBoolean("ostinato.spectate.mainClock");

    private static final class Seen {
        final Player p;
        long lastNanos;
        int lastTick;
        long lastRecv, lastBurst; // network-thread stamp of the previous update (PacketTap.stamp)
        float yaw, pitch;
        double[] kb;
        SnapshotWorld world;
        int worldTick;
        double wx, wy, wz;
        int useStart = -1;
        ItemStack useItem = ItemStack.EMPTY;
        int hits; // for the fight log

        Seen(Player p) {
            this.p = p;
        }
    }

    /** Worker-side state of one watched player. */
    private static final class Mind {
        final InputDecoder dec = new InputDecoder();
        final Habits life = new Habits();
        String name;
    }

    private interface Job {
        void run(SpectateLearner l);
    }

    private final Map<Integer, Seen> seen = new HashMap<>();
    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(512);
    private final Map<Integer, Mind> minds = new HashMap<>(); // worker only
    private final FightBook book; // worker only
    private final Map<Long, PvpRecorder> recorders = new HashMap<>(); // main only
    private final List<Object[]> pendingHits = new ArrayList<>();
    private final Set<Integer> critVictims = new HashSet<>();
    private ClientWorld clientWorld;
    private ClientLevel lastLevel;
    private Thread worker;
    private int tick;
    private int allowed = 8; // players decoded at once, set by the budget
    private volatile boolean running;
    // cost accounting
    private long mainNanos, mainTicks;
    private volatile long workerNanos, workerJobs, decodes, simTicks, dropped;
    private double workerEwma; // ns per game tick
    private long lastWorkerNanos;
    private BufferedWriter decodeLog; // worker only
    private final List<String> lastReport = Collections.synchronizedList(new ArrayList<>());

    public SpectateLearner(Baritone baritone) {
        super(baritone);
        book = new FightBook(f -> {}, this::fightEnded);
        PacketTap.add(this);
        SpectateBench.install(baritone); // -Dostinato.spectbench only
    }

    private static Settings s() {
        return BaritoneAPI.getSettings();
    }

    /** Whether to watch here: the setting, and on a multiplayer server its own setting as well. */
    public boolean active() {
        if (!s().spectateLearn.value) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        if (mc.hasSingleplayerServer()) return true;
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.isLan()) return true;
        return s().spectateLearnOnServers.value;
    }

    // ---------------------------------------------------------------- game thread

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            seen.clear();
            closeRecorders();
            post(l -> l.minds.clear());
            lastLevel = level;
            clientWorld = null;
        }
        boolean on = active();
        SpectatePriors.get().enabled(s().spectateLearn.value);
        if (!on) {
            if (!seen.isEmpty()) seen.clear();
            closeRecorders();
            return;
        }
        ensureWorker();
        long t0 = System.nanoTime();
        tick++;
        if (clientWorld == null) clientWorld = new ClientWorld(ctx);
        Player me = Minecraft.getInstance().player;
        double r = s().spectateRadius.value;
        // whom to watch: the nearest players, as many as the budget allows
        List<Player> near = new ArrayList<>();
        for (Player p : level.players()) {
            if (p != me && !p.isSpectator() && p.distanceToSqr(me) < r * r) near.add(p);
        }
        near.sort(Comparator.comparingDouble(p -> p.distanceToSqr(me)));
        Set<Integer> keep = new HashSet<>();
        for (int i = 0; i < near.size() && i < allowed; i++) keep.add(near.get(i).getId());
        for (Iterator<Map.Entry<Integer, Seen>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Seen> e = it.next();
            if (!keep.contains(e.getKey()) || e.getValue().p.isRemoved()) {
                int id = e.getKey();
                it.remove();
                post(l -> {
                    Mind m = l.minds.get(id);
                    if (m != null) m.dec.reset();
                });
            }
        }
        for (Player p : near) if (keep.contains(p.getId())) seen.computeIfAbsent(p.getId(), k -> new Seen(p));
        for (Seen sn : seen.values()) use(sn);
        // hits wait a tick for the crit packet that follows them
        for (Object[] h : pendingHits) {
            int att = (Integer) h[0], vic = (Integer) h[1];
            boolean crit = critVictims.contains(vic);
            String an = (String) h[2], vn = (String) h[3];
            int t = (Integer) h[4];
            post(l -> l.hitW(t, att, an, vic, vn, crit));
        }
        pendingHits.clear();
        critVictims.clear();
        if (tick % 20 == 0) {
            Map<Integer, Vec3> pos = new HashMap<>();
            Map<Integer, Float> hp = new HashMap<>();
            for (Seen sn : seen.values()) {
                pos.put(sn.p.getId(), sn.p.position());
                hp.put(sn.p.getId(), hp(sn.p));
            }
            int t = tick;
            post(l -> l.book.tick(t, (a, b) -> {
                Vec3 pa = pos.get(a), pb = pos.get(b);
                return pa == null || pb == null ? Double.NaN : pa.distanceTo(pb);
            }, id -> {
                Float h = hp.get(id);
                return h == null ? Double.NaN : h;
            }));
            budget();
        }
        if (s().spectateSaveLogs.value) recordFights(level);
        else closeRecorders();
        mainNanos += System.nanoTime() - t0;
        mainTicks++;
    }

    private float hp(LivingEntity e) {
        HealthEstimate est = baritone.getHealthEstimator().estimate(e);
        return est.confidence() > 0.2 ? est.total() : Float.NaN;
    }

    /** Holds the worker to its budget: fewer players when over, more when well under. */
    private void budget() {
        long w = workerNanos;
        double perTick = (w - lastWorkerNanos) / 20.0;
        lastWorkerNanos = w;
        workerEwma = workerEwma == 0 ? perTick : 0.7 * workerEwma + 0.3 * perTick;
        double budget = s().spectateBudgetMs.value * 1e6;
        if (workerEwma > budget || queue.size() > 256) allowed = Math.max(1, allowed - 1);
        else if (workerEwma < 0.6 * budget && allowed < 16) allowed++;
    }

    @Override
    public void move(Entity e, Vec3 pos, float yRot, float xRot, boolean hasRot, boolean onGround, boolean full) {
        Seen sn = seen.get(e.getId());
        if (sn == null || sn.p != e) return;
        long now = System.nanoTime();
        if (hasRot) {
            sn.yaw = yRot;
            sn.pitch = xRot;
        }
        if (pos == null) return; // rotation only: the next position update carries it
        InputDecoder.Obs o = new InputDecoder.Obs();
        o.x = pos.x;
        o.y = pos.y;
        o.z = pos.z;
        o.yaw = hasRot ? yRot : sn.yaw;
        o.pitch = sn.pitch;
        o.onGround = onGround;
        // Server ticks since the last update: players are sent every second tick, sooner when their metadata changes.
        // Game-thread arrival is only good to a frame or client tick, so the network thread's stamp is used when there
        // is one: its time, and the count of server-tick bursts between the two updates.
        double dt = sn.lastNanos == 0 ? 2 : (now - sn.lastNanos) / 50e6;
        o.dtMain = dt;
        long[] st = PacketTap.stamp();
        if (st != null && sn.lastRecv != 0) {
            o.dtSeen = (st[0] - sn.lastRecv) / 50e6;
            o.burstsSeen = (int) Math.min(64, st[1] - sn.lastBurst);
            if (!MAIN_CLOCK) {
                o.dtRecv = o.dtSeen;
                o.bursts = o.burstsSeen;
            }
        }
        if (st != null) {
            sn.lastRecv = st[0];
            sn.lastBurst = st[1];
        } else {
            sn.lastRecv = 0;
        }
        o.ticks = Math.max(1, InputDecoder.guessTicks(o, MAIN_CLOCK));
        sn.lastNanos = now;
        sn.lastTick = tick;
        Player p = sn.p;
        o.sprintFlag = p.isSprinting();
        o.sneakFlag = p.isShiftKeyDown();
        o.usingFlag = p.isUsingItem();
        o.special = p.isInWater() || p.isInLava() || p.isFallFlying() || p.isPassenger() || p.isSwimming() || p.isSpectator() || p.getAbilities().flying;
        o.kb = sn.kb;
        sn.kb = null;
        double walk = p.getAttributeValue(Attributes.MOVEMENT_SPEED) / (p.isSprinting() ? 1.3 : 1);
        o.speedScale = Math.abs(walk / 0.1 - 1) < 1e-4 ? 1 : walk / 0.1;
        double js = p.getAttributeValue(Attributes.JUMP_STRENGTH);
        o.jumpStrength = Math.abs(js - 0.42) < 1e-4 ? 0.42 : js;
        // the blocks around them, copied here; re-copied when they near its edge or every two seconds
        if (sn.world == null || tick - sn.worldTick > 40 || Math.abs(o.x - sn.wx) > MARGIN - 1.5 || Math.abs(o.z - sn.wz) > MARGIN - 1.5 || Math.abs(o.y - sn.wy) > MARGIN - 1.5) {
            sn.world = SnapshotWorld.around(clientWorld, o.x, o.y, o.z, MARGIN);
            sn.wx = o.x;
            sn.wy = o.y;
            sn.wz = o.z;
            sn.worldTick = tick;
        }
        SnapshotWorld w = sn.world;
        // the foe: whoever they are fighting, else the nearest other player within 8
        Player foe = null;
        double fd = 8;
        for (Seen o2 : seen.values()) {
            if (o2 == sn) continue;
            double d = o2.p.distanceTo(p);
            if (d < fd) {
                fd = d;
                foe = o2.p;
            }
        }
        if (foe == null && Minecraft.getInstance().player != null && Minecraft.getInstance().player.distanceTo(p) < 8 && !Minecraft.getInstance().player.isSpectator()) {
            foe = Minecraft.getInstance().player;
            fd = foe.distanceTo(p);
        }
        double foeDist = foe == null ? -1 : fd;
        double radial = 0;
        if (foe != null) {
            Vec3 to = foe.position().subtract(p.position()).multiply(1, 0, 1).normalize();
            Vec3 v = p.getKnownMovement();
            radial = v.x * to.x + v.z * to.z;
        }
        float hp = hp(p);
        int id = p.getId(), t = tick;
        String name = p.getName().getString();
        double rad = radial;
        if (!post(l -> l.decodeW(id, name, w, o, t, foeDist, rad, hp))) dropped++;
    }

    @Override
    public void teleport(Entity e) {
        int id = e.getId();
        if (seen.containsKey(id)) post(l -> {
            Mind m = l.minds.get(id);
            if (m != null) m.dec.reset();
        });
    }

    @Override
    public void motion(Entity e, Vec3 v) {
        Seen sn = seen.get(e.getId());
        if (sn != null) sn.kb = new double[]{v.x, v.y, v.z};
    }

    @Override
    public void animate(Entity e, int action) {
        if (action == 4) critVictims.add(e.getId());
        Seen sn = seen.get(e.getId());
        if (sn == null || action != 0 && action != 3) return; // SWING_MAIN_HAND, SWING_OFF_HAND
        Player p = sn.p;
        // aim: how far their look is off the nearest other player's chest
        double yawErr = Double.NaN, pitchErr = Double.NaN;
        boolean inReach = false;
        LivingEntity best = null;
        double bd = 6;
        for (Player o : Minecraft.getInstance().level.players()) {
            if (o == p || o.isSpectator()) continue;
            double d = o.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        if (best != null) {
            Vec3 eye = p.getEyePosition();
            Vec3 c = best.getBoundingBox().getCenter();
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            double yaw = Math.toDegrees(Math.atan2(-dx, dz)), pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            yawErr = wrap(sn.yaw - yaw);
            pitchErr = sn.pitch - pitch;
            inReach = Math.sqrt(best.getBoundingBox().distanceToSqr(eye)) <= 3.0;
        }
        int id = p.getId(), t = tick;
        double ye = yawErr, pe = pitchErr;
        boolean ir = inReach;
        String name = p.getName().getString();
        post(l -> l.mind(id, name).life.swing(t, ye, pe, ir));
        post(l -> {
            for (FightBook.Fight f : l.book.fightsOf(id)) f.of(id).swing(t, ye, pe, ir);
        });
    }

    @Override
    public void damage(Entity e, ClientboundDamageEventPacket p) {
        if (!active()) return;
        net.minecraft.world.damagesource.DamageSource src = p.getSource(Minecraft.getInstance().level);
        Entity att = src.getEntity();
        if (!(e instanceof Player victim) || !(att instanceof Player attacker) || att == e) return;
        if (!src.is(DamageTypeTags.IS_PLAYER_ATTACK)) return;
        if (!seen.containsKey(victim.getId()) && !seen.containsKey(attacker.getId())) return;
        Seen as = seen.get(attacker.getId());
        if (as != null) as.hits++;
        pendingHits.add(new Object[]{attacker.getId(), victim.getId(), attacker.getName().getString(), victim.getName().getString(), tick});
    }

    @Override
    public void entityEvent(Entity e, byte event) {
        Seen sn = seen.get(e.getId());
        if (sn == null) return;
        int id = e.getId(), t = tick;
        if (event == 35) post(l -> {
            l.mind(id, null).life.totem(t);
            for (FightBook.Fight f : l.book.fightsOf(id)) f.of(id).totem(t);
        });
        else if (event == 3) post(l -> l.book.died(t, id));
    }

    @Override
    public void added(Entity e) {
        if (!(e instanceof ThrownEnderpearl) && !(e instanceof ThrownSplashPotion)) return;
        Entity owner = ((net.minecraft.world.entity.projectile.Projectile) e).getOwner();
        if (owner == null || !seen.containsKey(owner.getId())) return;
        int id = owner.getId(), t = tick;
        float hp = hp((LivingEntity) owner);
        boolean pearl = e instanceof ThrownEnderpearl;
        if (!pearl) {
            ItemStack st = ((ThrownSplashPotion) e).getItem();
            var pc = st.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents.EMPTY);
            boolean heals = false;
            for (var mi : pc.getAllEffects()) heals |= mi.getEffect().is(net.minecraft.world.effect.MobEffects.INSTANT_HEALTH) || mi.getEffect().is(net.minecraft.world.effect.MobEffects.REGENERATION);
            if (!heals) return;
        }
        post(l -> {
            Habits life = l.mind(id, null).life;
            if (pearl) life.pearl(t, hp);
            else life.pot(t, hp);
            for (FightBook.Fight f : l.book.fightsOf(id)) {
                if (pearl) f.of(id).pearl(t, hp);
                else f.of(id).pot(t, hp);
            }
        });
    }

    /** Eating: the use flag held 30 ticks or more with food in hand. */
    private void use(Seen sn) {
        Player p = sn.p;
        if (p.isUsingItem()) {
            ItemStack st = p.getItemInHand(p.getUsedItemHand());
            if (sn.useStart < 0 || !ItemStack.isSameItem(st, sn.useItem)) {
                sn.useStart = tick;
                sn.useItem = st.copy();
            }
            return;
        }
        if (sn.useStart < 0) return;
        int held = tick - sn.useStart;
        ItemStack st = sn.useItem;
        sn.useStart = -1;
        if (held < 30 || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) return;
        boolean gapple = st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE);
        int id = p.getId(), t = tick;
        // the health they ate at: before the apple's absorption, so take it off again
        float hp = hp(p);
        float at = Float.isNaN(hp) ? hp : Math.max(0, hp - (gapple ? (st.is(Items.ENCHANTED_GOLDEN_APPLE) ? 16 : 4) : 0));
        post(l -> {
            l.mind(id, null).life.ate(t, gapple, at);
            for (FightBook.Fight f : l.book.fightsOf(id)) f.of(id).ate(t, gapple, at);
        });
    }

    /** {@code spectateSaveLogs}: each fight on in the fight recorder's format, side A as "me". */
    private void recordFights(ClientLevel level) {
        Set<Long> live = new HashSet<>();
        synchronized (activeFights) {
            for (long[] f : activeFights) {
                Entity a = level.getEntity((int) f[0]), b = level.getEntity((int) f[1]);
                if (!(a instanceof Player pa) || !(b instanceof LivingEntity pb)) continue;
                live.add(f[2]);
                PvpRecorder r = recorders.get(f[2]);
                if (r == null) {
                    r = new PvpRecorder(Paths.get("pvplogs", "spectate"));
                    r.begin(pa, pb, "spectate");
                    recorders.put(f[2], r);
                }
                Seen sa = seen.get(pa.getId());
                r.tick(pa, pb, pa.distanceTo(pb), "watch", lastKeys.getOrDefault(pa.getId(), "-"), sa == null ? 0 : sa.hits);
            }
        }
        for (Iterator<Map.Entry<Long, PvpRecorder>> it = recorders.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, PvpRecorder> e = it.next();
            if (!live.contains(e.getKey())) {
                e.getValue().end(Minecraft.getInstance().player, "ended");
                it.remove();
            }
        }
    }

    private void closeRecorders() {
        for (PvpRecorder r : recorders.values()) r.end(Minecraft.getInstance().player, "stopped");
        recorders.clear();
    }

    private boolean post(Job j) {
        if (!running) return false;
        return queue.offer(j);
    }

    private synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(this::work, "Ostinato spectate");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    // ---------------------------------------------------------------- worker thread

    private final List<long[]> activeFights = new ArrayList<>(); // {a, b, key}, shared with the game thread
    private final Map<Integer, String> lastKeys = new java.util.concurrent.ConcurrentHashMap<>();

    private void work() {
        while (running) {
            Job j;
            try {
                j = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (j == null) continue;
            long t0 = System.nanoTime();
            try {
                j.run(this);
            } catch (Throwable t) {
                System.out.println("Ostinato spectate: " + t);
            }
            workerNanos += System.nanoTime() - t0;
            workerJobs++;
        }
    }

    private Mind mind(int id, String name) {
        Mind m = minds.computeIfAbsent(id, k -> new Mind());
        if (name != null) m.name = name;
        return m;
    }

    private void decodeW(int id, String name, SnapshotWorld w, InputDecoder.Obs o, int t, double foeDist, double radial, float hp) {
        Mind m = mind(id, name);
        InputDecoder.Decoded d = m.dec.step(w, o);
        decodes++;
        simTicks += d.work;
        m.life.decoded(t, d, foeDist, radial, hp);
        for (FightBook.Fight f : book.fightsOf(id)) f.of(id).decoded(t, d, foeDist, radial, hp);
        if (!d.reset) lastKeys.put(id, d.keys());
        if (m.life.swings() >= 20 && m.life.swings() % 20 == 0) SpectatePriors.get().style(name, m.life);
        logDecode(id, name, o, d, t);
    }

    private void hitW(int t, int att, String an, int vic, String vn, boolean crit) {
        FightBook.Fight f = book.hit(t, att, an, vic, vn);
        synchronized (activeFights) {
            long key = ((long) f.a << 32) | (f.b & 0xffffffffL);
            boolean have = false;
            for (long[] x : activeFights) have |= x[2] == key;
            if (!have) activeFights.add(new long[]{f.a, f.b, key});
        }
        Habits ha = f.of(att), hv = f.of(vic);
        if (ha != null) ha.hit(t, crit);
        if (hv != null) hv.hurt(t);
        mind(att, an).life.hit(t, crit);
        mind(vic, vn).life.hurt(t);
    }

    private void fightEnded(FightBook.Fight f, int winner, String why) {
        synchronized (activeFights) {
            activeFights.removeIf(x -> x[0] == f.a && x[1] == f.b);
        }
        SpectatePriors.get().update(book);
        String w = winner < 0 ? "draw" : winner == f.a ? f.nameA : f.nameB;
        String line = String.format(Locale.ROOT, "spectate: %s vs %s, %s (%s); %s: %s | %s: %s", f.nameA, f.nameB, w, why, f.nameA, f.ha.summary(), f.nameB, f.hb.summary());
        System.out.println("Ostinato " + line);
        lastReport.clear();
        lastReport.addAll(book.report());
    }

    private void logDecode(int id, String name, InputDecoder.Obs o, InputDecoder.Decoded d, int t) {
        if (DECODE_LOG == null) return;
        try {
            if (decodeLog == null) {
                decodeLog = Files.newBufferedWriter(Paths.get(DECODE_LOG));
                decodeLog.write("tick,id,name,x,y,z,ticks,reset,keys,fwd,str,fi,si,jump,jumpObs,sprint,sneak,using,kb,conf,resid,work,guess,dtMain,dtRecv,bursts,pTicks\n");
            }
            decodeLog.write(String.format(Locale.ROOT, "%d,%d,%s,%.5f,%.5f,%.5f,%d,%d,%s,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%.3f,%.5f,%d,%d,%.3f,%.3f,%d,%.3f%n",
                    t, id, name, o.x, o.y, o.z, o.ticks, d.reset ? 1 : 0, d.keys(), d.forward, d.strafe, d.forwardImpulse, d.strafeImpulse,
                    d.jump ? 1 : 0, d.jumpObservable ? 1 : 0, d.sprint ? 1 : 0, d.sneak ? 1 : 0, d.using ? 1 : 0, d.kb ? 1 : 0, d.confidence, d.residual, d.work, d.guess, o.dtMain, o.dtSeen, o.burstsSeen, d.pTicks));
            if (decodes % 200 == 0) decodeLog.flush();
        } catch (IOException e) {
            decodeLog = null;
        }
    }

    // ---------------------------------------------------------------- read-outs

    /** Lines for the {@code #spectate} command and the handoff: cost, decoding and what the fights showed. */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        double mainUs = mainTicks == 0 ? 0 : mainNanos / 1e3 / mainTicks;
        double workUs = mainTicks == 0 ? 0 : workerNanos / 1e3 / mainTicks;
        out.add(String.format(Locale.ROOT, "spectate %s: watching %d (cap %d), main %.1f us/tick, worker %.1f us/tick, %d decodes, %.1f sim ticks each, %d dropped, queue %d",
                active() ? "on" : "off", seen.size(), allowed, mainUs, workUs, decodes, decodes == 0 ? 0 : (double) simTicks / decodes, dropped, queue.size()));
        out.addAll(lastReport);
        return out;
    }

    public long mainNanos() {
        return mainNanos;
    }

    public long mainTicks() {
        return mainTicks;
    }

    public long workerNanos() {
        return workerNanos;
    }

    public long decodes() {
        return decodes;
    }

    public long simTicks() {
        return simTicks;
    }

    /** Flushes the decode log (bench). */
    public void flush() {
        post(l -> {
            try {
                if (l.decodeLog != null) l.decodeLog.flush();
            } catch (IOException ignored) {}
        });
    }

    private static double wrap(double deg) {
        double d = deg % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
