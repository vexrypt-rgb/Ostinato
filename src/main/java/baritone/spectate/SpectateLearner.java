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
    // Ostinato: kit detection and fight types watch every player in range, not only those decoded (cheap reads)
    private static final class Look {
        final Player p;
        Map<String, Double> gear;
        int gearTick = -1000, clueTick = -1000;
        long fp;
        FightGroup.Clue clue;
        int useStart = -1;
        ItemStack useItem = ItemStack.EMPTY;

        Look(Player p) {
            this.p = p;
        }
    }

    private final Map<Integer, Look> looks = new HashMap<>(); // main only
    private long kitNanos, kitTicks;
    private final Map<Integer, List<String>> meta = new java.util.concurrent.ConcurrentHashMap<>(); // fight serial -> lines, worker to main
    private final List<String> kitReport = Collections.synchronizedList(new ArrayList<>());
    /** What the closed groups were: one entry per group, for the bench and {@link #results()}. */
    private final List<GroupResult> results = Collections.synchronizedList(new ArrayList<>());

    /** Ostinato: one closed fight group as the spectator saw it. */
    public static final class GroupResult {
        public final String type, shape, teams;
        public final Map<String, KitSignatures.Result> kits;
        public final int hits;

        GroupResult(FightGroup g) {
            type = g.type;
            shape = g.shape;
            teams = g.teamsLine();
            kits = new java.util.LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : g.members.entrySet()) kits.put(e.getValue(), g.kits.get(e.getKey()));
            hits = g.hits.size();
        }

        @Override
        public String toString() {
            return type + " " + shape + " " + teams + " kits " + kits;
        }
    }
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
        // Ostinato: kit detection and fight types
        book.onPairEnd(this::pairEnded);
        book.onSettled(this::settled);
        book.onGroup(this::groupEnded);
        PacketTap.add(this);
        SpectateBench.install(baritone); // -Dostinato.spectbench only
        KitBench.install(baritone); // Ostinato: -Dostinato.kitbench only
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
            looks.clear(); // Ostinato
            pendingSplash.clear(); // Ostinato
            firstSeenTick.clear(); // Ostinato: joined late; arriving in a world starts every clock over
            lastHurtTick.clear();
            hpBefore.clear();
            closeRecorders();
            post(l -> {
                l.minds.clear();
                l.book.resetPlayers(); // Ostinato: entity ids start over
            });
            lastLevel = level;
            clientWorld = null;
        }
        boolean on = active();
        SpectatePriors.get().enabled(s().spectateLearn.value);
        if (!on) {
            if (!seen.isEmpty()) seen.clear();
            looks.clear(); // Ostinato
            pendingSplash.clear(); // Ostinato
            firstSeenTick.clear(); // Ostinato: joined late: turning watching on is an arrival
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
            // Ostinato: negative ids are client-made (the freecam spar ghost), never a real player to learn from
            if (p != me && p.getId() >= 0 && !p.isSpectator() && p.distanceToSqr(me) < r * r) near.add(p);
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
        // Ostinato: joined late: who came into range when (leaving range starts it over), and their health this tick
        Set<Integer> nearIds = new HashSet<>();
        for (Player p : near) {
            nearIds.add(p.getId());
            firstSeenTick.putIfAbsent(p.getId(), tick);
        }
        firstSeenTick.keySet().retainAll(nearIds);
        hpBefore.keySet().retainAll(nearIds);
        for (Player p : near) hpBefore.put(p.getId(), p.getHealth());
        for (Seen sn : seen.values()) use(sn);
        lookAll(near); // Ostinato: kits and team clues of everyone in range
        if (!pendingSplash.isEmpty()) resolveSplashes(); // Ostinato
        // hits wait a tick for the crit packet that follows them
        for (Object[] h : pendingHits) {
            int att = (Integer) h[0], vic = (Integer) h[1];
            boolean ranged = (Boolean) h[5]; // Ostinato
            boolean crit = !ranged && critVictims.contains(vic);
            String late = (String) h[6]; // Ostinato: joined late
            String an = (String) h[2], vn = (String) h[3];
            int t = (Integer) h[4];
            post(l -> l.hitW(t, att, an, vic, vn, crit, ranged, late));
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
            // Ostinato: everyone in range counts for fight ends and groups, decoded or not
            Map<Integer, double[]> where = new HashMap<>();
            for (Look lk : looks.values()) {
                int id = lk.p.getId();
                if (!pos.containsKey(id)) {
                    pos.put(id, lk.p.position());
                    hp.put(id, hp(lk.p));
                }
                Vec3 v = pos.get(id);
                Float h = hp.get(id);
                where.put(id, new double[]{v.x, v.y, v.z, h == null ? Double.NaN : h});
            }
            int t = tick;
            boolean apart = s().spectateFightTypes.value;
            post(l -> l.book.multiApart(apart));
            boolean late = s().spectateLearnLateFights.value; // Ostinato
            post(l -> l.book.includeLate(late));
            boolean learnKits = s().spectateLearnKits.value;
            post(l -> l.book.learnKits(learnKits));
            post(l -> l.book.track(t, where));
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
            if (o == p || o.isSpectator() || o.getId() < 0) continue; // Ostinato: not the spar ghost
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
        try {
            damage0(e, p);
        } finally {
            if (e instanceof Player) lastHurtTick.put(e.getId(), tick); // Ostinato: joined late (mid-combo), after this hit's own check
        }
    }

    private void damage0(Entity e, ClientboundDamageEventPacket p) {
        net.minecraft.world.damagesource.DamageSource src = p.getSource(Minecraft.getInstance().level);
        Entity att = src.getEntity();
        if (!(e instanceof Player victim) || !(att instanceof Player attacker) || att == e) return;
        boolean ranged = src.is(DamageTypeTags.IS_PROJECTILE); // Ostinato: bow, crossbow and trident hits count too
        if (!src.is(DamageTypeTags.IS_PLAYER_ATTACK) && !ranged) return;
        if (!seen.containsKey(victim.getId()) && !seen.containsKey(attacker.getId()) && !looks.containsKey(victim.getId()) && !looks.containsKey(attacker.getId())) return; // Ostinato: or looked at
        Seen as = seen.get(attacker.getId());
        if (as != null) as.hits++;
        pendingHits.add(new Object[]{attacker.getId(), victim.getId(), attacker.getName().getString(), victim.getName().getString(), tick, ranged,
                lateSigns(attacker, true) != null ? lateSigns(attacker, true) : lateSigns(victim, false)}); // Ostinato: joined late
    }

    // Ostinato: joined-late detection. Main thread: when each player came in sight, their health the tick before, their last hurt.
    static final int LATE_ARRIVAL = 100, LATE_COMBO = 10;
    private final Map<Integer, Integer> firstSeenTick = new HashMap<>(), lastHurtTick = new HashMap<>();
    private final Map<Integer, Float> hpBefore = new HashMap<>();

    /**
     * Why a fight whose first seen hit involves {@code p} would have begun before we saw it, or null. The victim's own
     * health and hurt time already carry this hit, so for it the health of the tick before and earlier hurts are used.
     */
    private String lateSigns(Player p, boolean attacker) {
        int id = p.getId();
        Integer first = firstSeenTick.get(id);
        float hp = attacker ? p.getHealth() : hpBefore.getOrDefault(id, p.getHealth());
        return lateReason(p.getName().getString(), hp, p.getMaxHealth(), p.getAbsorptionAmount(),
                attacker ? p.hurtTime > 0 : false, lastHurtTick.containsKey(id) ? tick - lastHurtTick.get(id) : Integer.MAX_VALUE,
                p.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION) || p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION),
                first == null ? 0 : tick - first);
    }

    /** Ostinato: the joined-late rule on its own (unit tested). Null when nothing says the fight was under way. */
    static String lateReason(String name, float hp, float maxHp, float absorption, boolean hurtNow, int sinceHurt, boolean gapple, int sinceSeen) {
        if (sinceSeen < LATE_ARRIVAL) return "arrived:" + name;
        if (hp < maxHp - 0.5f) return "hurt:" + name;
        if (absorption > 0) return "absorption:" + name;
        if (hurtNow || sinceHurt <= LATE_COMBO) return "combo:" + name;
        if (gapple) return "gapple:" + name;
        return null;
    }

    @Override
    public void entityEvent(Entity e, byte event) {
        if (event == 35 && looks.containsKey(e.getId())) kitEvent(e.getId(), "pop:totem"); // Ostinato
        if (event == 3 && looks.containsKey(e.getId()) && !seen.containsKey(e.getId())) { // Ostinato: deaths of those only looked at
            int id = e.getId(), t = tick;
            post(l -> l.book.died(t, id));
            return;
        }
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
        addedKit(e); // Ostinato
        if (!(e instanceof ThrownEnderpearl) && !(e instanceof ThrownSplashPotion)) return;
        if (e instanceof ThrownSplashPotion sp0) { // Ostinato: its potion is only known a tick or two later
            Entity o = sp0.getOwner();
            if (o != null && seen.containsKey(o.getId()) && !looks.containsKey(o.getId())) pendingSplash.add(new Object[]{sp0, o.getId(), tick});
            return;
        }
        Entity owner = ((net.minecraft.world.entity.projectile.Projectile) e).getOwner();
        if (owner == null || !seen.containsKey(owner.getId())) return;
        int id = owner.getId(), t = tick;
        float hp = hp((LivingEntity) owner);
        post(l -> { // Ostinato: pearls only; splash potions are read later, in resolveSplashes
            l.mind(id, null).life.pearl(t, hp);
            for (FightBook.Fight f : l.book.fightsOf(id)) f.of(id).pearl(t, hp);
        });
    }

    /**
     * Ostinato: kit detection and team clues for everyone in range. Each player's gear is read every fourth tick (a
     * different tick for different players) and sent to the worker only when it changed or every five seconds; the
     * team clue once a second, when it changed. Eating and drinking are watched here for all of them too.
     */
    private void lookAll(List<Player> near) {
        boolean kits = s().spectateKitDetect.value;
        if (!kits && !s().spectateFightTypes.value) {
            looks.clear();
            return;
        }
        long k0 = System.nanoTime();
        Set<Integer> here = new HashSet<>();
        for (Player p : near) {
            here.add(p.getId());
            Look lk = looks.get(p.getId());
            if (lk == null || lk.p != p) looks.put(p.getId(), lk = new Look(p));
            int id = p.getId();
            // the full read only when the fingerprint moved, and every five seconds regardless
            if (kits && (tick + id) % 4 == 0) {
                long fp = GearReader.fingerprint(p);
                if (fp != lk.fp || tick - lk.gearTick >= 100) {
                    lk.fp = fp;
                    Map<String, Double> g = GearReader.tokens(p);
                    if (!g.equals(lk.gear) || tick - lk.gearTick >= 100) {
                        lk.gear = g;
                        lk.gearTick = tick;
                        int t = tick;
                        post(l -> l.book.gear(t, id, g));
                    }
                }
            }
            if ((tick + id) % 20 == 0) {
                FightGroup.Clue c = GearReader.clue(p);
                if (lk.clue == null || !same(c, lk.clue)) {
                    lk.clue = c;
                    post(l -> l.book.clue(id, c));
                }
            }
            if (kits) useKit(lk);
        }
        looks.keySet().retainAll(here);
        kitNanos += System.nanoTime() - k0;
        kitTicks++;
    }

    private static boolean same(FightGroup.Clue a, FightGroup.Clue b) {
        return a.team.equals(b.team) && a.teamColor == b.teamColor && a.nameColor == b.nameColor && a.dye == b.dye;
    }

    /** Ostinato: what they ate or drank, for the kit: food held in use 30 ticks or more, a potion 25. */
    private void useKit(Look lk) {
        Player p = lk.p;
        if (p.isUsingItem()) {
            ItemStack st = p.getItemInHand(p.getUsedItemHand());
            if (lk.useStart < 0 || !ItemStack.isSameItem(st, lk.useItem)) {
                lk.useStart = tick;
                lk.useItem = st.copy();
            }
            return;
        }
        if (lk.useStart < 0) return;
        int held = tick - lk.useStart;
        ItemStack st = lk.useItem;
        lk.useStart = -1;
        String tok = null;
        if (st.is(Items.POTION) && held >= 25) tok = "drank:potion";
        else if (held >= 30 && st.get(net.minecraft.core.component.DataComponents.FOOD) != null)
            tok = st.is(Items.GOLDEN_APPLE) ? "ate:golden_apple" : st.is(Items.ENCHANTED_GOLDEN_APPLE) ? "ate:enchanted_golden_apple" : "ate:food";
        if (tok == null) return;
        kitEvent(p.getId(), tok);
    }

    private void kitEvent(int id, String token) {
        if (!s().spectateKitDetect.value) return;
        int t = tick;
        post(l -> l.book.kitEvent(t, id, token));
    }

    /**
     * Ostinato: what a new entity says of its thrower's kit: pearls, splash potions (heal, harm or other), wind charges,
     * arrows, tridents, fireworks; an end crystal counts for the nearest fighter within five blocks.
     */
    private void addedKit(Entity e) {
        if (looks.isEmpty()) return; // Ostinato: also for fight types (arrows shot at someone); kitEvent checks kit detection
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
            Look best = null;
            double bd = 25;
            for (Look lk : looks.values()) {
                double d = lk.p.distanceToSqr(e);
                if (d < bd) {
                    bd = d;
                    best = lk;
                }
            }
            if (best != null) kitEvent(best.p.getId(), "crystal");
            return;
        }
        if (!(e instanceof net.minecraft.world.entity.projectile.Projectile pr)) return;
        Entity owner = pr.getOwner();
        if (owner == null || !looks.containsKey(owner.getId())) return;
        String tok = null;
        if (e instanceof ThrownEnderpearl) tok = "threw:ender_pearl";
        else if (e instanceof ThrownSplashPotion sp) {
            // Ostinato: the potion's item comes in an entity-data packet after the spawn, so read it a few ticks on
            pendingSplash.add(new Object[]{sp, owner.getId(), tick});
            return;
        }
        else if (e instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge) tok = "threw:wind_charge";
        else if (e instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident) tok = "shot:trident";
        else if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) tok = "shot:arrow";
        if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) shotAt(owner, e); // Ostinato: engagement
        else if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity) tok = "firework";
        if (tok != null) kitEvent(owner.getId(), tok);
    }

    // Ostinato: thrown splash potions waiting for their item: {potion, thrower id, tick thrown}
    private final List<Object[]> pendingSplash = new ArrayList<>();

    /** Ostinato: reads each pending splash once it shows its potion (or at the latest when it breaks, or 5 ticks on). */
    private void resolveSplashes() {
        for (Iterator<Object[]> it = pendingSplash.iterator(); it.hasNext(); ) {
            Object[] ps = it.next();
            ThrownSplashPotion sp = (ThrownSplashPotion) ps[0];
            String tok = GearReader.splash(sp.getItem());
            boolean known = !tok.equals("threw:splash_other") || sp.isRemoved() || tick - (Integer) ps[2] >= 5;
            if (!known) continue;
            it.remove();
            int id = (Integer) ps[1], t = tick;
            if (s().spectateKitDetect.value && looks.containsKey(id)) kitEvent(id, tok);
            if (tok.equals("threw:splash_heal") && seen.get(id) != null) { // Ostinato: the pot habit, moved here from added()
                float hp = hp(seen.get(id).p);
                post(l -> {
                    l.mind(id, null).life.pot(t, hp);
                    for (FightBook.Fight f : l.book.fightsOf(id)) f.of(id).pot(t, hp);
                });
            }
        }
    }

    /**
     * Ostinato: whom an arrow or trident was shot at: the player within 40 blocks closest to its line of flight (within
     * 20 degrees). Keeps their fight on, see {@link FightBook#shotAt}.
     */
    private void shotAt(Entity shooter, Entity arrow) {
        Vec3 v = arrow.getDeltaMovement();
        if (v.lengthSqr() < 1e-4) return;
        Vec3 dir = v.normalize();
        Player best = null;
        double bestCos = Math.cos(Math.toRadians(20));
        for (Look lk : looks.values()) {
            Player p = lk.p;
            if (p == shooter) continue;
            Vec3 to = p.position().add(0, 1, 0).subtract(arrow.position());
            double d = to.length();
            if (d > 40 || d < 1e-3) continue;
            // a dropping arc: compare on the level plane, so a lob still points at its target
            Vec3 flat = new Vec3(to.x, 0, to.z), fdir = new Vec3(dir.x, 0, dir.z);
            if (flat.lengthSqr() < 1e-6 || fdir.lengthSqr() < 1e-6) continue;
            double cos = flat.normalize().dot(fdir.normalize());
            if (cos > bestCos) {
                bestCos = cos;
                best = p;
            }
        }
        if (best == null) return;
        int a = shooter.getId(), b = best.getId(), t = tick;
        post(l -> l.book.shotAt(t, a, b));
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
                // Ostinato: the fight is over; the log waits (no more ticks) for its type and kits, which come once the
                // fight's group has closed, or gives up after five minutes
                int serial = (int) (long) e.getKey();
                List<String> lines = meta.remove(serial);
                int since = waiting.computeIfAbsent(e.getKey(), k -> tick);
                if (lines == null && tick - since < 6000) continue;
                if (lines == null) lines = List.of("#type unknown");
                for (String l : lines) e.getValue().meta(l);
                e.getValue().end(Minecraft.getInstance().player, "ended");
                waiting.remove(e.getKey());
                it.remove();
            }
        }
    }

    private final Map<Long, Integer> waiting = new HashMap<>(); // Ostinato: fight serial -> tick its log began to wait

    private void closeRecorders() {
        for (Map.Entry<Long, PvpRecorder> e : recorders.entrySet()) {
            List<String> lines = meta.remove((int) (long) e.getKey()); // Ostinato: what is known of it
            if (lines != null) for (String l : lines) e.getValue().meta(l);
            e.getValue().end(Minecraft.getInstance().player, "stopped");
        }
        recorders.clear();
        waiting.clear();
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

    private void hitW(int t, int att, String an, int vic, String vn, boolean crit, boolean ranged, String late) {
        // Ostinato: crit kept on the group's hit list; ranged hits let the fight drift further; late only marks a new fight
        FightBook.Fight f = ranged ? book.rangedHit(t, att, an, vic, vn, late) : book.hit(t, att, an, vic, vn, crit, late);
        synchronized (activeFights) {
            long key = f.serial; // Ostinato: the fight's serial, as the same pair may fight again while a log waits
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

    /**
     * Ostinato: one freecam spar round as a fight between two made-up players (negative ids), when
     * {@code sparSeedPriors} is on. {@code hits} are {tick, 1 ghost hit / 0 you hit, crit}; the loser dies at the end.
     */
    public void sparRound(String kit, List<int[]> hits, boolean ghostWon) {
        if (hits.isEmpty()) return;
        ensureWorker();
        final int ghost = -9001, you = -9002, base = tick + 100000;
        int t0 = hits.get(0)[0];
        for (int[] h : hits) {
            int t = base + h[0] - t0;
            boolean g = h[1] == 1;
            post(l -> l.hitW(t, g ? ghost : you, g ? "spar-ghost" : "spar-you", g ? you : ghost, g ? "spar-you" : "spar-ghost", h[2] == 1, false, null));
        }
        int end = base + hits.get(hits.size() - 1)[0] - t0 + 1;
        post(l -> l.book.died(end, ghostWon ? you : ghost));
    }

    private void fightEnded(FightBook.Fight f, int winner, String why) {
        synchronized (activeFights) {
            activeFights.removeIf(x -> x[2] == f.serial); // Ostinato: by serial
        }
        SpectatePriors.get().update(book);
        String w = winner < 0 ? "draw" : winner == f.a ? f.nameA : f.nameB;
        // Ostinato: with the fight's type and each side's kit
        String line = String.format(Locale.ROOT, "spectate: %s vs %s, %s (%s) [%s, kits %s / %s]; %s: %s | %s: %s", f.nameA, f.nameB, w, why, f.type(), f.kitA, f.kitB,
                f.nameA, f.ha.summary(), f.nameB, f.hb.summary());
        System.out.println("Ostinato " + line);
        lastReport.clear();
        lastReport.addAll(book.report());
    }

    // ---------------------------------------------------------------- Ostinato: kit detection and fight types (worker)

    /** A fight between two is over: its log stops taking ticks and waits for the group to say what kind it was. */
    private void pairEnded(FightBook.Fight f) {
        synchronized (activeFights) {
            activeFights.removeIf(x -> x[2] == f.serial);
        }
    }

    /** The fight's group has closed: its log gets the type and both kits, then is closed. */
    private void settled(FightBook.Fight f, boolean counts) {
        FightGroup g = f.group;
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "#type %s shape=%s players=%d group=%d%s%s", f.type(), g == null ? "1v1" : g.shape, g == null ? 2 : g.members.size(),
                g == null ? 0 : g.id, counts ? "" : " short", f.type().equals("1v1") || !s().spectateFightTypes.value ? "" : " multi")
                + (f.late == null ? "" : " late=" + f.late)); // Ostinato: joined late
        if (g != null && !g.type.equals("1v1")) lines.add("#teams " + g.teamsLine());
        lines.add(kitLine("me", f.nameA, f.kitA, f.a));
        lines.add(kitLine("tg", f.nameB, f.kitB, f.b));
        meta.put(f.serial, lines);
    }

    private String kitLine(String side, String name, KitSignatures.Result r, int id) {
        KitEvidence ev = book.evidence(id);
        return String.format(Locale.ROOT, "#kit %s name=%s label=%s conf=%.2f best=%s score=%.2f evidence=%s", side, name, r == null ? KitSignatures.UNKNOWN : r.label,
                r == null ? 0 : r.confidence, r == null ? "-" : r.best, r == null ? 0 : r.score, ev == null ? "-" : ev.summary(tick));
    }

    /** A group closed: kept for the bench, and for FFA and team fights its own log of every hit. */
    private void groupEnded(FightGroup g) {
        GroupResult r = new GroupResult(g);
        results.add(r);
        if (results.size() > 500) results.remove(0);
        String line = "spectate group: " + g.describe() + "; kits " + r.kits + (g.type.equals("1v1") ? "" : "; moves " + g.skillsLine());
        System.out.println("Ostinato " + line);
        kitReport.add(line);
        while (kitReport.size() > 8) kitReport.remove(0);
        if (!g.type.equals("1v1") && s().spectateSaveLogs.value) writeGroup(g);
    }

    /** {@code pvplogs/spectate/group-<time>-<shape>.log.gz}: teams, kits, every hit (attacker, victim), positions each second. */
    private void writeGroup(FightGroup g) {
        try {
            Path dir = Paths.get("pvplogs", "spectate");
            Files.createDirectories(dir);
            Path file = dir.resolve("group-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + g.id + "-" + g.shape + ".log.gz");
            try (BufferedWriter w = new BufferedWriter(new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file)), java.nio.charset.StandardCharsets.UTF_8))) {
                w.write(String.format(Locale.ROOT, "#group v1 type=%s shape=%s players=%d hits=%d start=%d end=%d%n", g.type, g.shape, g.members.size(), g.hits.size(), g.start, g.last));
                w.write("#teams " + g.teamsLine() + "\n");
                for (Map.Entry<Integer, String> e : g.members.entrySet()) w.write(kitLine("-", e.getValue(), g.kits.get(e.getKey()), e.getKey()) + "\n");
                // hits and positions in tick order
                int ti = 0;
                for (int[] h : g.hits) {
                    while (ti < g.track.size() && (Integer) g.track.get(ti)[0] <= h[0]) writeTrack(w, g, g.track.get(ti++));
                    w.write(String.format(Locale.ROOT, "@%d hit %s>%s%s%n", h[0] - g.start, g.members.get(h[1]), g.members.get(h[2]), h[3] != 0 ? " crit" : ""));
                }
                while (ti < g.track.size()) writeTrack(w, g, g.track.get(ti++));
                w.write("#moves " + g.skillsLine() + "\n");
                w.write("#end\n");
            }
            System.out.println("Ostinato spectate: " + file);
        } catch (IOException e) {
            System.out.println("Ostinato spectate: group log: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeTrack(BufferedWriter w, FightGroup g, Object[] row) throws IOException {
        StringBuilder sb = new StringBuilder().append((Integer) row[0] - g.start);
        for (double[] m : (List<double[]>) row[1])
            sb.append('|').append(g.members.get((int) m[0])).append(':').append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.1f", m[1], m[2], m[3], m[4]));
        w.write(sb.append('\n').toString());
    }

    /** Forgets everyone's kit evidence (the bench, after it changes their kits). */
    public void forgetKits() {
        for (Look lk : looks.values()) {
            lk.gearTick = -1000;
            lk.clue = null;
        }
        post(l -> l.book.resetPlayers());
    }

    /** Groups closed so far, oldest first (at most 500). */
    public List<GroupResult> results() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
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
        // Ostinato: kit detection cost and what the last groups were
        out.add(String.format(Locale.ROOT, "kits %s, fight types %s: looking at %d, %.1f us/tick", s().spectateKitDetect.value ? "on" : "off",
                s().spectateFightTypes.value ? "on" : "off", looks.size(), kitTicks == 0 ? 0 : kitNanos / 1e3 / kitTicks));
        synchronized (kitReport) {
            out.addAll(kitReport);
        }
        out.addAll(lastReport);
        return out;
    }

    /** Ostinato: game-thread time spent on kit detection and team clues. */
    public long kitNanos() {
        return kitNanos;
    }

    public long kitTicks() {
        return kitTicks;
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
