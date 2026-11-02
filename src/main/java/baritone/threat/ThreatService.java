package baritone.threat;

import baritone.Baritone;
import baritone.behavior.Behavior;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.physics.*;
import baritone.api.threat.*;
import baritone.api.threat.ThreatAssessment.Sight;
import baritone.health.DamageMath;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Client-thread observer; one PPE submission per tick, four outstanding at most, no new worker. */
public final class ThreatService extends Behavior implements IThreatAssessment {
    public static final int SCAN_LIMIT = 512, SOURCE_LIMIT = 32;
    /** Ostinato (Bombard): a primed TNT with this little fuse left is late (a sprint covers about 5.6 blocks in it). */
    public static final int TNT_LATE_FUSE = 20;
    private final ThreatPublication publication = new ThreatPublication();
    private volatile Telemetry telemetry = new Telemetry(0, 0, 0, 0, 0, 0, 0, 0);
    private final Map<UUID, Pending> pending = new HashMap<>();
    private Level world;
    private Player subjectIdentity;
    private long tick, generation, evaluations, scanned, candidates, predictions, discarded, truncatedTicks, total, maximum;
    private int cursor;
    private record Pending(PredictionTicket ticket, long tick, Vec3 source, Vec3 motion, Vec3 subject, BodyState defense) {}

    public ThreatService(Baritone baritone) { super(baritone); }
    @Override public ThreatSnapshot latest() { return publication.latest(); }
    @Override public Telemetry telemetry() { return telemetry; }

    private void invalidate(String reason) {
        for (Pending p : pending.values()) p.ticket.cancel();
        discarded += pending.size();
        pending.clear();
        generation = publication.invalidate(tick, reason);
    }
    @Override public void onWorldEvent(WorldEvent e) { invalidate("WORLD_CHANGED"); world = null; subjectIdentity = null; }
    @Override public void onChunkEvent(ChunkEvent e) { invalidate("CHUNK_CHANGED"); }
    @Override public void onBlockChange(BlockChangeEvent e) { invalidate("BLOCK_CHANGED"); }

    @Override public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) return;
        tick++;
        if (event.getType() != TickEvent.Type.IN || ctx.player() == null || ctx.world() == null) {
            if (latest().subject() != null || !pending.isEmpty()) invalidate("NO_WORLD");
            world = null;
            subjectIdentity = null;
            return;
        }
        if (world != ctx.world()) { invalidate("WORLD_CHANGED"); world = ctx.world(); }
        if (subjectIdentity != ctx.player()) { invalidate("SUBJECT_CHANGED"); subjectIdentity = ctx.player(); }
        long start = System.nanoTime();
        try { observe(); }
        catch (IllegalArgumentException | IllegalStateException unsupported) {
            invalidate("CAPTURE_UNAVAILABLE_" + unsupported.getClass().getSimpleName());
        }
        finally {
            long elapsed = System.nanoTime() - start;
            total += elapsed; maximum = Math.max(maximum, elapsed); evaluations++;
            telemetry = new Telemetry(evaluations, scanned, candidates, predictions, discarded, truncatedTicks, total, maximum);
        }
    }

    private void observe() {
        Player subject = ctx.player();
        if (subject.isSpectator() || !subject.isAlive()) { invalidate("SUBJECT_NOT_ACTIVE"); return; }
        var physics = baritone.getPredictivePhysics();
        // Fixed scan budget; explicit truncation means an empty answer cannot assert safety.
        List<Entity> selected = new ArrayList<>(SOURCE_LIMIT + 1);
        Comparator<Entity> priority = Comparator.comparingInt((Entity e) -> immediate(e) ? 0 : 1)
                .thenComparingDouble(subject::distanceToSqr).thenComparing(e -> e.getUUID().toString());
        int seen = 0, eligible = 0;
        boolean truncated = false;
        for (Entity e : ((net.minecraft.client.multiplayer.ClientLevel)ctx.world()).entitiesForRendering()) {
            if (seen++ >= SCAN_LIMIT) { truncated = true; break; }
            if (e == subject || !e.isAlive() || e.isRemoved() || subject.distanceToSqr(e) > 32 * 32
                    || kind(e) == ThreatRules.Kind.NONE && !(e instanceof Mob mob && mob.getTarget() == subject)) continue;
            eligible++;
            selected.add(e);
            selected.sort(priority);
            if (selected.size() > SOURCE_LIMIT) { selected.removeLast(); truncated = true; }
        }
        scanned += Math.min(seen, SCAN_LIMIT); candidates += eligible;
        if (truncated) truncatedTicks++;
        Set<UUID> present = new HashSet<>();
        for (Entity e : selected) present.add(e.getUUID());
        pending.entrySet().removeIf(entry -> {
            boolean remove = !present.contains(entry.getKey()) || tick - entry.getValue().tick > 2;
            if (remove) { entry.getValue().ticket.cancel(); discarded++; }
            return remove;
        });
        List<ThreatAssessment> results = new ArrayList<>(selected.size());
        List<Entity> predict = new ArrayList<>();
        for (Entity e : selected) {
            ThreatRules.Kind kind = kind(e);
            boolean aggression = e instanceof Mob mob && mob.getTarget() == subject
                    || subject.getLastHurtByMob() == e && subject.tickCount - subject.getLastHurtByMobTimestamp() <= 20;
            if (kind == ThreatRules.Kind.NONE && aggression) kind = ThreatRules.Kind.MELEE;
            boolean active = e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())
                    || e instanceof net.minecraft.world.entity.item.PrimedTnt; // Ostinato (Bombard): a primed TNT is always burning
            boolean late = active && (e instanceof net.minecraft.world.entity.item.PrimedTnt tnt ? tnt.getFuse() <= TNT_LATE_FUSE
                    : ((Creeper)e).getSwelling(1) >= 0.9f);
            Sight sight = subject.hasLineOfSight(e) ? Sight.CLEAR : Sight.BLOCKED;
            boolean reach = e instanceof Mob mob && mob.isWithinMeleeAttackRange(subject);
            Set<String> flags = new TreeSet<>();
            flags.add("CLIENT_OBSERVATION");
            flags.add("PATH_AND_ESCAPE_ROUTES_UNKNOWN");
            if (e instanceof Creeper c && c.isPowered()) flags.add("POWERED_CREEPER");
            if (subject.getAbilities().invulnerable) flags.add("SUBJECT_INVULNERABLE_ORDINARY_DAMAGE_SUPPRESSED");
            Double damage = null;
            // Only ordinary zombie melee is priced; enchants/status payloads remain an explicit upper estimate.
            if (type(e).equals("minecraft:zombie") && e instanceof LivingEntity living
                    && living.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
                float raw = DamageMath.difficulty((float)living.getAttributeValue(Attributes.ATTACK_DAMAGE), world.getDifficulty().getId());
                damage = (double)DamageMath.afterArmor(raw, subject.getArmorValue(), (float)subject.getAttributeValue(Attributes.ARMOR_TOUGHNESS), 0);
                flags.add("MELEE_ATTRIBUTE_ESTIMATE_ENCHANTMENTS_EFFECTS_SHIELD_UNKNOWN");
            }
            PredictionResult prediction = null;
            int age = 0;
            Pending job = pending.get(e.getUUID());
            if (job != null) {
                age = (int)(tick - job.tick);
                boolean compatible = e.position().distanceToSqr(job.source) <= 0.0625
                        || (kind == ThreatRules.Kind.PROJECTILE || kind == ThreatRules.Kind.PRIMED_TNT) && age <= 1
                            && e.position().distanceToSqr(job.source) <= Math.pow(1 + 2 * job.motion.length(), 2)
                            && e.getDeltaMovement().distanceToSqr(job.motion) <= Math.pow(0.2 + job.motion.length() * 0.2, 2);
                BodyState now = physics.capture(subject);
                compatible &= subject.position().distanceToSqr(job.subject) <= 0.0625
                        && now.health == job.defense.health && now.absorption == job.defense.absorption
                        && now.armor == job.defense.armor && now.toughness == job.defense.toughness
                        && now.resistance == job.defense.resistance && now.blastProtection == job.defense.blastProtection;
                if (kind == ThreatRules.Kind.PROJECTILE) flags.add("PROJECTILE_STATE_ADVANCED_SINCE_CAPTURE");
                // A one-tick projectile forecast is conditional on unchanged flight; never call it exact.
                if (!compatible) { job.ticket.cancel(); pending.remove(e.getUUID()); discarded++; flags.add("PPE_STATE_CHANGED"); }
                else if (job.ticket.isDone()) { prediction = job.ticket.result(); pending.remove(e.getUUID()); }
                else flags.add("PPE_PENDING");
            }
            if (kind == ThreatRules.Kind.PROJECTILE || active) predict.add(e);
            results.add(ThreatRules.assess(new ThreatRules.Observation(e.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), subject.getUUID(), kind,
                    Math.sqrt(subject.distanceToSqr(e)), sight, reach, aggression, active, late,
                    (double)(subject.getHealth() + subject.getAbsorptionAmount()), damage, 0, flags), prediction, age));
        }
        if (!predict.isEmpty() && pending.size() < 4) {
            Entity e = predict.get(Math.floorMod(cursor++, predict.size()));
            if (!pending.containsKey(e.getUUID())) {
                BodyState target = physics.capture(subject);
                PredictionRequest.Builder request;
                if (e instanceof Creeper c) request = PredictionRequest.explosion(e.getX(), e.getY(), e.getZ(), c.isPowered() ? 6 : 3, false);
                // Ostinato (Bombard): the engine carries a primed TNT to its fuse's end and prices the blast against us there
                else if (e instanceof net.minecraft.world.entity.item.PrimedTnt tnt)
                    request = PredictionRequest.trajectory(physics.capture(e), Math.min(PredictionRequest.MAX_HORIZON, Math.max(1, tnt.getFuse())));
                else request = PredictionRequest.projectile(physics.capture(e), 20);
                PredictionTicket ticket = physics.submit(request.targets(List.of(target)).difficulty(world.getDifficulty().getId())
                        .priority(PredictionRequest.Priority.PLANNING).fidelity(PredictionRequest.Fidelity.SCREENING)
                        .deadlineIn(40_000_000L).consumer("threat-observer").build());
                pending.put(e.getUUID(), new Pending(ticket, tick, e.position(), e.getDeltaMovement(), subject.position(), target));
                predictions++;
            }
        }
        Set<String> environment = new TreeSet<>();
        if (subject.isInLava()) environment.add("IN_LAVA");
        if (subject.isOnFire()) environment.add("ON_FIRE");
        if (subject.getAirSupply() < 0) environment.add("AIR_DEPLETED");
        if (subject.hurtTime > 0) environment.add("RECENT_DAMAGE");
        if (subject.fallDistance > 3) environment.add("FALLING_DAMAGE_UNKNOWN");
        if (!subject.getAbilities().invulnerable
                && (environment.contains("IN_LAVA") || environment.contains("ON_FIRE") || environment.contains("AIR_DEPLETED")))
            results.add(ThreatRules.environment(subject.getUUID(), subject.getHealth() + subject.getAbsorptionAmount(), environment,
                    subject.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)));
        results.sort(Comparator.comparingInt(ThreatAssessment::level).reversed()
                .thenComparingDouble(ThreatAssessment::distance).thenComparing(r -> r.source().toString()));
        publication.publish(new ThreatSnapshot(generation, tick, subject.getUUID(), world.dimension().toString(), results, environment, truncated, "OBSERVED"));
    }

    private static String type(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(); }

    private static boolean immediate(Entity e) {
        return e instanceof AbstractArrow || e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())
                || e instanceof net.minecraft.world.entity.item.PrimedTnt; // Ostinato (Bombard)
    }
    private static ThreatRules.Kind kind(Entity e) {
        if (e instanceof Player p) return p.isSpectator() ? ThreatRules.Kind.NONE : ThreatRules.Kind.PLAYER;
        if (e instanceof Creeper) return ThreatRules.Kind.CREEPER;
        if (e instanceof AbstractArrow) return ThreatRules.Kind.PROJECTILE;
        if (type(e).equals("minecraft:zombie")) return ThreatRules.Kind.MELEE;
        if (type(e).equals("minecraft:skeleton")) return ThreatRules.Kind.RANGED;
        if (e instanceof net.minecraft.world.entity.item.PrimedTnt) return ThreatRules.Kind.PRIMED_TNT; // Ostinato (Bombard)
        if (e instanceof net.minecraft.world.entity.projectile.Projectile
                || type(e).equals("minecraft:end_crystal") || type(e).equals("minecraft:tnt_minecart"))
            return ThreatRules.Kind.UNSUPPORTED;
        if (e instanceof Monster) return ThreatRules.Kind.UNSUPPORTED;
        return ThreatRules.Kind.NONE;
    }
}
