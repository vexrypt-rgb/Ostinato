/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Scherzo's hold on the game: one per fight process. Each fight tick it feeds the opponent's position to an
 * {@link OpponentModel}, settles the {@link PredictionAudit} against it, and builds the tick's {@link CombatSnapshot}.
 * <p>
 * Mode comes from {@code -Dostinato.scherzo=off|observe|live}, observe by default (Ostinato: 2026-11-02 fixture A/B, Bombard needs Scherzo >= OBSERVE; LIVE melee gain unproven). OFF does nothing at all. OBSERVE
 * models and measures and moves no decision: the fight is exactly what it was without Scherzo. LIVE lets the fight
 * read the snapshot: the spear jab is aimed at, and judged against, where the server will have the opponent
 * ({@link #jab()}).
 */
public final class Scherzo {

    public enum Mode { OFF, OBSERVE, LIVE }

    private final Mode mode;
    private final OpponentModel model = new OpponentModel();
    private final PredictionAudit fight = new PredictionAudit(), session = new PredictionAudit();
    private final Ground ground = new Ground();
    private final JabAudit jabFight = new JabAudit(), jabSession = new JabAudit();
    private final JabAdvice jab = new Jab();
    private Level level;
    private int targetId = Integer.MIN_VALUE;
    private CombatSnapshot snapshot;
    private long ticks, nanos, maxNanos, fightTicks;

    public Scherzo() {
        this(parse(System.getProperty("ostinato.scherzo", "observe"))); // Ostinato: default OBSERVE (telemetry only); LIVE stays opt-in until a melee A/B proves gain
    }

    public Scherzo(Mode mode) {
        this.mode = mode;
    }

    static Mode parse(String s) {
        try {
            return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.OBSERVE;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** The fight may act on what Scherzo says. */
    public boolean live() {
        return mode == Mode.LIVE;
    }

    /**
     * One fight tick, after the target is chosen and before anything is decided.
     *
     * @param baseVel  the velocity the fight already leads the opponent by: the audit's baseline
     * @param lagTicks round trip in ticks, as {@code CombatLag} measures it
     */
    public void tick(Player me, LivingEntity target, Vec3 baseVel, double lagTicks) {
        if (mode == Mode.OFF) return;
        long t0 = System.nanoTime();
        Level lvl = me.level();
        if (lvl != level || target.getId() != targetId) {
            model.forget();
            fight.discardPending();
            session.discardPending();
            jabFight.abandon();
            jabSession.abandon();
            level = lvl;
            targetId = target.getId();
        }
        long now = lvl.getGameTime();
        jabFight.tick(now, target.hurtTime, target.hurtDuration);
        jabSession.tick(now, target.hurtTime, target.hurtDuration);
        Vec3 pos = target.position();
        fight.actual(now, pos.x, pos.y, pos.z);
        session.actual(now, pos.x, pos.y, pos.z);
        long resets = model.resets();
        model.observe(now, pos.x, pos.y, pos.z, target.onGround());
        if (model.resets() != resets) {
            // a teleport: what was predicted before it was of a path that ended
            fight.discardPending();
            session.discardPending();
        }
        ground.level = lvl;
        for (int i = 0; i < PredictionAudit.HORIZONS.length; i++) {
            int h = PredictionAudit.HORIZONS[i];
            Prediction p = model.predict(now, h, ground);
            if (p == null) continue;
            double bx = pos.x + baseVel.x * h, by = pos.y + baseVel.y * h, bz = pos.z + baseVel.z * h;
            fight.expect(now, i, p, bx, by, bz);
            session.expect(now, i, p, bx, by, bz);
        }
        double lead = lagTicks + target.getType().updateInterval();
        Vec3 eye = me.getEyePosition(), v = me.getDeltaMovement();
        snapshot = new CombatSnapshot(now, target.getId(), target instanceof Player,
                eye.x, eye.y, eye.z, v.x, v.y, v.z,
                pos.x, pos.y, pos.z, target.getBbWidth(), target.getBbHeight(), target.onGround(),
                lagTicks, lead, me.getAttackStrengthScale(0.5f), target.hurtTime, model.predict(now, lead, ground));
        long dt = System.nanoTime() - t0;
        ticks++;
        fightTicks++;
        nanos += dt;
        maxNanos = Math.max(maxNanos, dt);
    }

    /** This tick's snapshot; null before the first fight tick or when OFF. */
    public CombatSnapshot snapshot() {
        return snapshot;
    }

    /** The opponent {@code ahead} ticks after the newest sample, or null. */
    public Prediction predict(double ahead) {
        if (mode == Mode.OFF || level == null) return null;
        ground.level = level;
        return model.predict(level.getGameTime(), ahead, ground);
    }

    public OpponentModel model() {
        return model;
    }

    /** The fight ended: its measure to the fight log, and a fresh one for the next. */
    public void fightEnd(Consumer<String> note) {
        if (mode == Mode.OFF || fightTicks == 0) return;
        String f = fight.report("fight");
        if (!f.isEmpty()) note.accept(f);
        String s = session.report("session");
        if (!s.isEmpty()) note.accept(s);
        jabFight.abandon();
        jabSession.abandon();
        String j = jabFight.report("fight");
        if (!j.isEmpty()) note.accept(j);
        String js = jabSession.report("session");
        if (!js.isEmpty()) note.accept(js);
        OpponentModel.Behavior b = model.behavior();
        note.accept(String.format(Locale.ROOT, "scherzo mode=%s foe turn=%.3f jump=%.3f speed=%.3f samples=%d turns=%d jumps=%d resets=%d",
                mode, b.turnRate(), b.jumpRate(), b.speed(), b.samples(), b.turns(), b.jumps(), b.resets()));
        note.accept(String.format(Locale.ROOT, "scherzo cost ticks=%d mean=%.1fus max=%.1fus", ticks, ticks == 0 ? 0 : nanos / 1000.0 / ticks, maxNanos / 1000.0));
        fight.clear();
        jabFight.clear();
        fightTicks = 0;
        snapshot = null;
    }

    /** Leaving the world: nothing kept about it. */
    public void reset() {
        jabFight.abandon();
        jabSession.abandon();
        model.forget();
        fight.discardPending();
        session.discardPending();
        level = null;
        targetId = Integer.MIN_VALUE;
        snapshot = null;
    }

    /** The spear jab's advice: {@link JabAdvice#NONE}'s answers unless LIVE, but every jab is audited unless OFF. */
    public JabAdvice jab() {
        return mode == Mode.OFF ? JabAdvice.NONE : jab;
    }

    /**
     * The server's jab ray from our eye and look as the server has them, over {@code [from, to]} along it, against
     * the opponent where the server will have it. Blocks between the eye and the box stop it as they stop the server's.
     */
    AttackFeasibility.Verdict jabVerdict(Player me, LivingEntity target, double from, double to) {
        CombatSnapshot s = snapshot;
        if (s == null || s.targetId() != target.getId() || s.now() == null) return AttackFeasibility.Verdict.UNKNOWN;
        Vec3 eye = me.getEyePosition(), look = me.getHeadLookAngle();
        net.minecraft.world.item.component.AttackRange r = me.getAttackRangeWith(me.getMainHandItem());
        double margin = r.hitboxMargin();
        AttackFeasibility.Verdict v = AttackFeasibility.ray(eye.x, eye.y, eye.z, look.x, look.y, look.z, from, to, margin,
                s.now(), s.foeWidth(), s.foeHeight());
        if (v == AttackFeasibility.Verdict.MISS || v == AttackFeasibility.Verdict.UNKNOWN) return v;
        double e = AttackFeasibility.entry(eye.x, eye.y, eye.z, look.x, look.y, look.z, from, to, margin, s.now(), s.foeWidth(), s.foeHeight());
        double check = Double.isNaN(e) ? to : e;
        net.minecraft.world.phys.BlockHitResult hit = me.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(check)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS && eye.distanceTo(hit.getLocation()) < check - 1e-3)
            return AttackFeasibility.Verdict.MISS;
        return v;
    }

    /** The server's full jab range for the held weapon: vanilla's segment, lengthened by our motion along the look. */
    private AttackFeasibility.Verdict serverJab(Player me, LivingEntity target) {
        net.minecraft.world.item.component.AttackRange r = me.getAttackRangeWith(me.getMainHandItem());
        double along = Math.max(0, me.getKnownMovement().dot(me.getHeadLookAngle()));
        return jabVerdict(me, target, r.effectiveMinRange(me), r.effectiveMaxRange(me) + along);
    }

    private final class Jab implements JabAdvice {
        private double lastLo = Double.NaN, lastHi = Double.NaN;

        @Override
        public Vec3 aim(Player me, LivingEntity target) {
            if (!live()) return null;
            CombatSnapshot s = snapshot;
            Prediction p = s == null || s.targetId() != target.getId() ? null : s.now();
            if (p == null || p.confidence() == Prediction.Confidence.LOW) return null;
            // the fight's own aim point (CombatGeometry.aimPoint), on the box where the server will have it
            Vec3 eye = me.getEyePosition();
            double half = s.foeWidth() / 2 - 0.05;
            return new Vec3(Mth.clamp(eye.x, p.x() - half, p.x() + half), Mth.clamp(eye.y, p.y() + 0.05 + 0.2, p.y() + s.foeHeight() - 0.05 - 0.1),
                    Mth.clamp(eye.z, p.z() - half, p.z() + half));
        }

        @Override
        public int verdict(Player me, LivingEntity target, double bandLo, double bandHi) {
            lastLo = bandLo;
            lastHi = bandHi;
            if (!live()) return 0;
            AttackFeasibility.Verdict server = serverJab(me, target);
            if (server == AttackFeasibility.Verdict.MISS) {
                jabFight.held(me.level().getGameTime());
                jabSession.held(me.level().getGameTime());
                return -1;
            }
            AttackFeasibility.Verdict band = jabVerdict(me, target, bandLo, bandHi);
            return band == AttackFeasibility.Verdict.SURE || band == AttackFeasibility.Verdict.LIKELY ? 1 : 0;
        }

        @Override
        public void sent(Player me, LivingEntity target, boolean clientRay) {
            long now = me.level().getGameTime();
            AttackFeasibility.Verdict server = serverJab(me, target);
            AttackFeasibility.Verdict band = Double.isNaN(lastLo) ? AttackFeasibility.Verdict.UNKNOWN : jabVerdict(me, target, lastLo, lastHi);
            jabFight.sent(now, server, band, clientRay, target.hurtTime);
            jabSession.sent(now, server, band, clientRay, target.hurtTime);
        }
    }

    /**
     * The floor under a column: the top of the highest collision shape at or below a height, within 8 blocks. Reads
     * only the column under the body's centre, so a body standing on a block's edge over air reads the floor
     * below it; the audit's vertical error is what shows how much that costs.
     */
    private static final class Ground implements OpponentModel.Ground {
        Level level;
        private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        @Override
        public double floor(double x, double y, double z) {
            if (level == null) return Double.NaN;
            int bx = Mth.floor(x), bz = Mth.floor(z), top = Mth.floor(y);
            for (int by = top; by >= top - 8; by--) {
                p.set(bx, by, bz);
                if (!level.hasChunkAt(p)) return Double.NaN;
                BlockState st = level.getBlockState(p);
                VoxelShape sh = st.getCollisionShape(level, p);
                if (sh.isEmpty()) continue;
                double surface = by + sh.max(Direction.Axis.Y);
                if (surface <= y + 1e-6) return surface;
            }
            return Double.NaN;
        }
    }
}
