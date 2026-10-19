/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.health;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.HealthEstimate;
import baritone.api.utils.IHealthEstimator;
import baritone.behavior.Behavior;
import baritone.spectate.PacketTap;
import baritone.utils.accessor.IBossOverlay;
import baritone.utils.accessor.IEffectParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Ostinato: keeps a {@link HealthTrack} for every living entity the client sees and moves it from what the server
 * sends. Vanilla syncs health, so where that reading is believable it is used as is (confidence 1). Some servers
 * send a constant instead; a reading that stays put through two hurts that should have lowered it is no longer
 * believed, and the track then runs on the hits (vanilla's damage arithmetic on the attacker's weapon and the
 * victim's armour), potions, apples, totems, effects and regeneration it sees. A health scoreboard or a boss bar
 * named for the entity is read where there is one.
 * <p>
 * {@code -Dostinato.health.blind=true} ignores synced health altogether, so local tests can measure the inference;
 * {@code -Dostinato.health.log=<file>} writes estimate against synced health every 10 ticks.
 */
public final class HealthEstimator extends Behavior implements IHealthEstimator, PacketTap.Listener {
    static final boolean BLIND = Boolean.getBoolean("ostinato.health.blind");
    private static final String LOG = System.getProperty("ostinato.health.log");

    private static final class Watch {
        final HealthTrack t = new HealthTrack();
        final LivingEntity e;
        float lastSynced = Float.NaN, lastSyncedAbs = Float.NaN;
        boolean trusted = !BLIND, syncMoved;
        int hurtNoDrop, checkAt = -1;
        float checkHp, checkAbs;
        int useStart = -1;
        ItemStack useItem = ItemStack.EMPTY;
        int lastAttack = -1000; // this entity's last attack (resets its attack strength)
        final int[] swings = {-1000, -1000, -1000, -1000}; // last main-hand swings: 26.3 resets attack strength on every swing
        ItemStack lastWeapon = ItemStack.EMPTY;
        double peakY = Double.NaN, landedPeak = Double.NaN;
        int landedTick = -1000;
        int seenTick;
        boolean strength;
        int strengthAmp;

        Watch(LivingEntity e) {
            this.e = e;
        }
    }

    private static final class Pending {
        final int due;
        final LivingEntity victim;
        final DamageSource src;
        final Vec3 at;
        final int prevAttack; // the attacker's attack before this one
        final int at0; // tick of the hit

        Pending(int due, LivingEntity victim, DamageSource src, Vec3 at, int prevAttack) {
            this.at0 = due - 1;
            this.due = due;
            this.victim = victim;
            this.src = src;
            this.at = at;
            this.prevAttack = prevAttack;
        }
    }

    private static final class Cloud {
        final AreaEffectCloud c;
        final MobEffect effect;
        final Map<Integer, Integer> lastApplied = new HashMap<>();

        Cloud(AreaEffectCloud c, MobEffect effect) {
            this.c = c;
            this.effect = effect;
        }
    }

    private final Map<Integer, Watch> watches = new HashMap<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Map<Integer, Integer> critAt = new HashMap<>();
    private final Map<Integer, ItemStack> potions = new HashMap<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private int tick;
    private ClientLevel lastLevel;
    private BufferedWriter log;
    public long nanos, ticksMeasured; // main-thread cost

    public HealthEstimator(Baritone baritone) {
        super(baritone);
        PacketTap.add(this);
    }

    private boolean on() {
        return BaritoneAPI.getSettings().healthEstimator.value;
    }

    // ---------------------------------------------------------------- API

    @Override
    public HealthEstimate estimate(LivingEntity e) {
        if (!on() || e == null) return synced(e, 0);
        if (e == Minecraft.getInstance().player) return synced(e, 1);
        Watch w = watches.get(e.getId());
        if (w == null || w.e != e) return synced(e, BLIND ? 0 : 0.5);
        HealthTrack t = w.t;
        return new HealthEstimate(t.health(), t.absorption(), t.max(), t.confidence(), t.sd(), t.source().name().toLowerCase(Locale.ROOT));
    }

    private static HealthEstimate synced(LivingEntity e, double conf) {
        if (e == null) return new HealthEstimate(0, 0, 20, 0, 20, "none");
        return new HealthEstimate(e.getHealth(), e.getAbsorptionAmount(), e.getMaxHealth(), conf, conf >= 1 ? 0 : 5, "synced");
    }

    // ---------------------------------------------------------------- bookkeeping

    private Watch watch(Entity ent) {
        if (!(ent instanceof LivingEntity e) || !on()) return null;
        if (e == Minecraft.getInstance().player) return null;
        Watch w = watches.get(e.getId());
        if (w != null && w.e == e) return w;
        w = new Watch(e);
        w.t.kind(e instanceof Player, e.isInvertedHealAndHarm(), e.getType().is(EntityTypeTags.IGNORES_POISON_AND_REGEN));
        w.t.max(e.getMaxHealth());
        w.seenTick = tick;
        if (w.trusted) w.t.exact(e.getHealth(), e.getAbsorptionAmount(), HealthTrack.Source.SYNCED);
        else w.t.unseen(e.getMaxHealth());
        if (!BLIND && e instanceof Player) w.t.exactAbsorption(e.getAbsorptionAmount());
        watches.put(e.getId(), w);
        return w;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            watches.clear();
            pending.clear();
            clouds.clear();
            potions.clear();
            lastLevel = level;
        }
        if (level == null || !on()) return;
        long t0 = System.nanoTime();
        tick++;
        // watch everything living within 64 blocks
        Player me = Minecraft.getInstance().player;
        if (me != null && tick % 10 == 0) {
            for (Entity ent : level.entitiesForRendering()) {
                if (ent instanceof LivingEntity && ent != me && ent.distanceToSqr(me) < 64 * 64) watch(ent);
            }
        }
        while (!pending.isEmpty() && pending.peekFirst().due <= tick) resolve(pending.pollFirst());
        for (Iterator<Watch> it = watches.values().iterator(); it.hasNext(); ) {
            Watch w = it.next();
            LivingEntity e = w.e;
            if (e.isRemoved() || e.level() != level) {
                it.remove();
                continue;
            }
            w.t.max(e.getMaxHealth());
            w.t.tick();
            readSynced(w);
            fall(w);
            use(w);
            if (w.checkAt >= 0 && tick >= w.checkAt) {
                w.checkAt = -1;
                boolean dropped = !Float.isNaN(w.lastSynced) && (e.getHealth() < w.checkHp - 0.01f || e.getAbsorptionAmount() < w.checkAbs - 0.01f);
                if (dropped) w.hurtNoDrop = 0;
                else if (++w.hurtNoDrop >= 2 && w.trusted && !w.syncMoved) w.trusted = false; // a constant: not health
            }
            if ((tick + e.getId()) % 10 == 0) {
                effects(w);
                scoreboard(w, level);
            }
        }
        if (tick % 10 == 0) bossBars(level);
        clouds(level);
        critAt.values().removeIf(t -> t < tick - 3);
        writeLog();
        nanos += System.nanoTime() - t0;
        ticksMeasured++;
    }

    private void readSynced(Watch w) {
        LivingEntity e = w.e;
        float hp = e.getHealth(), abs = e.getAbsorptionAmount();
        if (!Float.isNaN(w.lastSynced) && Math.abs(hp - w.lastSynced) > 0.01f) {
            w.syncMoved = true;
            if (!BLIND) w.trusted = true; // a moving reading is health again
        }
        w.lastSynced = hp;
        w.lastSyncedAbs = abs;
        if (w.trusted && !BLIND) {
            if (hp <= 0) w.t.died();
            else if (w.t.dead) {
                w.t.respawn();
                w.t.exact(hp, abs, HealthTrack.Source.SYNCED);
            } else w.t.exact(hp, abs, HealthTrack.Source.SYNCED);
        } else if (!BLIND && e instanceof Player && w.syncMoved) {
            w.t.exactAbsorption(abs);
        }
    }

    // ---------------------------------------------------------------- packets

    @Override
    public void entityData(Entity e) {
        Watch w = watch(e);
        if (w != null) readSynced(w);
    }

    @Override
    public void animate(Entity e, int action) {
        if (action == 4) critAt.put(e.getId(), tick); // ClientboundAnimatePacket.CRITICAL_HIT, sent for the victim
        if (action == 0) { // SWING_MAIN_HAND: ServerPlayer.swingAndResetAttackStrength
            Watch w = watch(e);
            if (w != null) {
                System.arraycopy(w.swings, 0, w.swings, 1, w.swings.length - 1);
                w.swings[0] = tick;
            }
        }
    }

    @Override
    public void damage(Entity e, ClientboundDamageEventPacket p) {
        Watch w = watch(e);
        if (w == null) return;
        ClientLevel level = Minecraft.getInstance().level;
        DamageSource src = p.getSource(level);
        // the crit particle packet follows the damage event; work it out a tick later
        int prev = -1000;
        if (src.getEntity() instanceof LivingEntity att && (src.is(DamageTypeTags.IS_PLAYER_ATTACK) || src.is(DamageTypes.MOB_ATTACK))) {
            Watch aw = watch(att);
            if (aw != null) {
                prev = aw.lastAttack;
                aw.lastAttack = tick;
            }
        }
        pending.add(new Pending(tick + 1, w.e, src, w.e.position(), prev));
        w.checkAt = tick + 4;
        w.checkHp = w.lastSynced;
        w.checkAbs = w.lastSyncedAbs;
    }

    @Override
    public void entityEvent(Entity e, byte event) {
        Watch w = watch(e);
        if (w == null) return;
        switch (event) {
            case 35 -> w.t.totem(); // EntityEvent.PROTECTED_FROM_DEATH
            case 3 -> w.t.died(); // EntityEvent.DEATH
            default -> {}
        }
    }

    @Override
    public void added(Entity e) {
        if (e instanceof ThrownSplashPotion p) potions.put(p.getId(), p.getItem().copy());
        if (e instanceof AreaEffectCloud c) {
            MobEffect eff = cloudEffect(c);
            if (eff != null) clouds.add(new Cloud(c, eff));
        }
        if (e instanceof LivingEntity) watch(e);
    }

    @Override
    public void removing(Entity e) {
        ItemStack st = potions.remove(e.getId());
        if (st != null) splash(e, st);
    }

    @Override
    public void teleport(Entity e) {
        Watch w = watches.get(e.getId());
        if (w != null) w.peakY = Double.NaN;
    }

    @Override
    public void move(Entity e, Vec3 pos, float yRot, float xRot, boolean hasRot, boolean onGround, boolean full) {
        Watch w = watches.get(e.getId());
        if (w == null || pos == null) return;
        if (onGround) {
            land(w);
        } else if (Double.isNaN(w.peakY) || pos.y > w.peakY) w.peakY = pos.y;
    }

    @Override
    public void equipment(Entity e) {}

    @Override
    public void levelEvent(int type, BlockPos pos, int data) {}

    // ---------------------------------------------------------------- inference

    private void resolve(Pending p) {
        Watch w = watches.get(p.victim.getId());
        if (w == null || w.e != p.victim) return;
        if (w.trusted && !BLIND) return; // the synced value says it all
        double[] d = expected(w, p);
        if (d != null) w.t.damage((float) d[0], d[1]);
    }

    /** What this hit took, after armour, Resistance and protection, and its standard deviation; null if unknown. */
    private double[] expected(Watch w, Pending p) {
        LivingEntity v = w.e;
        DamageSource s = p.src;
        Entity cause = s.getEntity(), direct = s.getDirectEntity();
        double raw, sd;
        ClientLevel level = Minecraft.getInstance().level;
        if (s.is(DamageTypeTags.IS_PLAYER_ATTACK) && cause instanceof Player att) {
            Watch aw = watches.get(att.getId());
            int reset = p.prevAttack;
            if (aw != null) for (int sw : aw.swings) if (sw < p.at0 && sw > reset) reset = sw; // the swing before this hit's own
            double[] m = melee(att, aw, reset, v, critAt.containsKey(v.getId()));
            raw = m[0];
            sd = m[1];
        } else if (s.is(DamageTypes.MOB_ATTACK) || s.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) {
            if (!(cause instanceof LivingEntity mob)) return new double[]{3, 3};
            raw = weaponDamage(mob, mob.getMainHandItem()) + enchantBonus(mob.getMainHandItem(), v);
            sd = 0.2 * raw + 0.3;
        } else if (s.is(DamageTypeTags.IS_PROJECTILE)) {
            if (direct instanceof AbstractArrow a) {
                double speed = a.getDeltaMovement().length();
                double base = 2.0;
                raw = Math.ceil(speed * base);
                sd = 0.25 * raw + 1; // crit arrows add a random part, Power is hidden on the arrow
            } else if (s.is(DamageTypes.TRIDENT)) {
                raw = 8;
                sd = 1;
            } else {
                raw = 1;
                sd = 1;
            }
        } else if (s.is(DamageTypeTags.IS_FALL)) {
            double peak = peak(w, v.getY());
            double safe = v.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
            double mult = v.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);
            raw = DamageMath.fall(peak - v.getY(), safe, 0, mult);
            if (raw <= 0) raw = 1; // it did hurt
            sd = 1;
            w.landedPeak = Double.NaN;
        } else if (s.is(DamageTypeTags.IS_EXPLOSION)) {
            Vec3 c = s.getSourcePosition() != null ? s.getSourcePosition() : direct != null ? direct.position() : p.at;
            double power = direct != null && direct instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal ? 6 : 4;
            raw = DamageMath.explosion(Math.sqrt(v.distanceToSqr(c)), power, 1.0);
            sd = 0.35 * raw + 1; // exposure unknown
        } else if (s.is(DamageTypes.LAVA)) {
            raw = 4;
            sd = 0.1;
        } else if (s.is(DamageTypes.IN_FIRE) || s.is(DamageTypes.ON_FIRE) || s.is(DamageTypes.CAMPFIRE) || s.is(DamageTypes.HOT_FLOOR)
                || s.is(DamageTypes.CACTUS) || s.is(DamageTypes.SWEET_BERRY_BUSH) || s.is(DamageTypes.STARVE) || s.is(DamageTypes.FREEZE)
                || s.is(DamageTypes.IN_WALL) || s.is(DamageTypes.WITHER)) {
            raw = 1;
            sd = 0.1;
        } else if (s.is(DamageTypes.DROWN)) {
            raw = 2;
            sd = 0.1;
        } else if (s.is(DamageTypes.CRAMMING)) {
            raw = 6;
            sd = 0.1;
        } else if (s.is(DamageTypes.LIGHTNING_BOLT)) {
            raw = 5;
            sd = 0.2;
        } else if (s.is(DamageTypes.MAGIC) || s.is(DamageTypes.INDIRECT_MAGIC)) {
            // poison (1, never below 1 health) unless a harming potion or cloud did it (handled where it landed)
            if (w.t.poisonAmp >= 0 || w.t.seenPoisonAmp >= 0) {
                raw = w.t.health > 1 ? 1 : 0;
                sd = 0.1;
            } else return null; // the splash already counted it
        } else if (s.is(DamageTypes.THORNS)) {
            raw = 2.5;
            sd = 1.2;
        } else if (s.is(DamageTypes.SONIC_BOOM)) {
            raw = 10;
            sd = 0.5;
        } else {
            raw = 2;
            sd = 2;
        }
        if (s.scalesWithDifficulty() && v instanceof Player && level != null) raw = DamageMath.difficulty((float) raw, level.getDifficulty().getId());
        return reduce(v, w, s, raw, sd);
    }

    private double[] reduce(LivingEntity v, Watch w, DamageSource s, double raw, double sd) {
        double dmg = raw, scale = 1;
        if (!s.is(DamageTypeTags.BYPASSES_ARMOR)) {
            float armor = v.getArmorValue(), tough = (float) v.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            int breach = 0;
            if (s.getWeaponItem() != null) breach = level(Enchantments.BREACH, s.getWeaponItem());
            double after = DamageMath.afterArmor((float) dmg, armor, tough, breach);
            scale *= dmg > 0 ? after / dmg : 1;
            dmg = after;
        }
        if (!s.is(DamageTypeTags.BYPASSES_EFFECTS) && !s.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
            int res = w.t.resistance();
            double after = DamageMath.afterMagic((float) dmg, res, 0);
            scale *= dmg > 0 ? after / dmg : 1;
            dmg = after;
        }
        if (!s.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            float epf = protection(v, s);
            double after = DamageMath.afterMagic((float) dmg, -1, epf);
            scale *= dmg > 0 ? after / dmg : 1;
            dmg = after;
        }
        return new double[]{dmg, sd * scale};
    }

    /** One melee hit by a player: raw damage and its standard deviation (the attack strength is the unknown). */
    private double[] melee(Player att, Watch aw, int prevAttack, LivingEntity v, boolean crit) {
        ItemStack weapon = att.getMainHandItem();
        double ad = weaponDamage(att, weapon);
        if (aw != null && aw.strength) ad += 3 * (aw.strengthAmp + 1);
        double speed = att.getAttributeValue(Attributes.ATTACK_SPEED);
        float scale;
        double sdScale;
        int since = tick - 1 - prevAttack; // resolved a tick after the hit; prevAttack is the last reset before it
        if (since > 200) {
            scale = 1;
            sdScale = 0.1;
        } else {
            scale = DamageMath.strengthScale(since, speed);
            sdScale = 0.08; // a tick of latency either way
        }
        float bonus = enchantBonus(weapon, v);
        float smash = 0;
        if (weapon.is(Items.MACE)) {
            double peak = aw == null ? att.getY() : peak(aw, att.getY());
            smash = DamageMath.maceSmash(peak - att.getY(), level(Enchantments.DENSITY, weapon));
        }
        double dmg = DamageMath.melee((float) ad, bonus, smash, scale, crit);
        double dHi = DamageMath.melee((float) ad, bonus, smash, (float) Math.min(1, scale + sdScale), crit);
        double sd = Math.abs(dHi - dmg) + 0.25 + (smash > 0 ? 2 : 0);
        return new double[]{dmg, sd};
    }

    private static double weaponDamage(LivingEntity holder, ItemStack weapon) {
        double base = holder instanceof Player ? 1.0 : holder.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        ItemAttributeModifiers mods = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return mods.compute(Attributes.ATTACK_DAMAGE, base, EquipmentSlot.MAINHAND);
    }

    private float enchantBonus(ItemStack weapon, LivingEntity v) {
        float b = DamageMath.sharpness(level(Enchantments.SHARPNESS, weapon));
        if (v.getType().is(EntityTypeTags.SENSITIVE_TO_SMITE)) b += DamageMath.smiteLike(level(Enchantments.SMITE, weapon));
        if (v.getType().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) b += DamageMath.smiteLike(level(Enchantments.BANE_OF_ARTHROPODS, weapon));
        return b;
    }

    private float protection(LivingEntity v, DamageSource s) {
        float epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = v.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            epf += level(Enchantments.PROTECTION, st);
            if (s.is(DamageTypeTags.IS_FIRE)) epf += 2 * level(Enchantments.FIRE_PROTECTION, st);
            if (s.is(DamageTypeTags.IS_EXPLOSION)) epf += 2 * level(Enchantments.BLAST_PROTECTION, st);
            if (s.is(DamageTypeTags.IS_PROJECTILE)) epf += 2 * level(Enchantments.PROJECTILE_PROTECTION, st);
            if (s.is(DamageTypeTags.IS_FALL)) epf += 3 * level(Enchantments.FEATHER_FALLING, st);
        }
        return epf;
    }

    private static int level(ResourceKey<Enchantment> key, ItemStack st) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || st == null || st.isEmpty()) return 0;
        Optional<Holder.Reference<Enchantment>> h = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key);
        return h.map(ench -> EnchantmentHelper.getItemEnchantmentLevel(ench, st)).orElse(0);
    }

    /** ThrownSplashPotion.onHit: everything living within 4 (box to box) of where it broke. */
    private void splash(Entity potion, ItemStack st) {
        PotionContents pc = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        AABB box = potion.getBoundingBox().inflate(4.0, 2.0, 4.0);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            Watch w = watch(e);
            if (w == null || w.trusted && !BLIND) continue;
            double distSq = potion.getBoundingBox().distanceToSqr(e.getBoundingBox());
            double scale = DamageMath.splashScale(distSq);
            if (scale <= 0) continue;
            for (MobEffectInstance mi : pc.getAllEffects()) applyEffect(w, mi.getEffect(), mi.getAmplifier(), (int) (mi.getDuration() * scale + 0.5), scale, 0.5);
        }
    }

    /** One effect landing: instant ones at {@code scale}, the rest started on their clock. */
    private void applyEffect(Watch w, Holder<MobEffect> eff, int amp, int duration, double scale, double sdPos) {
        boolean inv = w.t.undead;
        if (eff.is(MobEffects.INSTANT_HEALTH) && !inv || eff.is(MobEffects.INSTANT_DAMAGE) && inv) {
            w.t.heal(DamageMath.instantHeal(amp, scale), sdPos);
        } else if (eff.is(MobEffects.INSTANT_DAMAGE) || eff.is(MobEffects.INSTANT_HEALTH)) {
            float harm = DamageMath.instantHarm(amp, scale);
            float after = DamageMath.afterMagic(harm, w.t.resistance(), protectionMagic(w.e));
            w.t.damage(after, sdPos + 0.2);
        } else if (eff.is(MobEffects.REGENERATION)) {
            w.t.startRegen(amp, duration);
        } else if (eff.is(MobEffects.POISON)) {
            w.t.startPoison(amp, duration);
        } else if (eff.is(MobEffects.WITHER)) {
            w.t.startWither(amp, duration);
        } else if (eff.is(MobEffects.ABSORPTION)) {
            w.t.startAbsorption(amp, duration);
        } else if (eff.is(MobEffects.RESISTANCE)) {
            w.t.resistanceAmp = Math.max(w.t.resistanceAmp, amp);
        }
    }

    private float protectionMagic(LivingEntity v) {
        float epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            epf += level(Enchantments.PROTECTION, v.getItemBySlot(slot));
        }
        return epf;
    }

    /** AreaEffectCloud: what its colour says it carries (instant health or harming, regeneration, poison). */
    private static MobEffect cloudEffect(AreaEffectCloud c) {
        ParticleOptions po = c.getParticle();
        if (!(po instanceof ColorParticleOption col)) return null;
        int rgb = rgb(col);
        for (Holder<MobEffect> h : List.of(MobEffects.INSTANT_HEALTH, MobEffects.INSTANT_DAMAGE, MobEffects.REGENERATION, MobEffects.POISON)) {
            if ((h.value().getColor() & 0xFFFFFF) == rgb) return h.value();
        }
        return null;
    }

    private void clouds(ClientLevel level) {
        for (Iterator<Cloud> it = clouds.iterator(); it.hasNext(); ) {
            Cloud cl = it.next();
            if (cl.c.isRemoved()) {
                it.remove();
                continue;
            }
            if (cl.c.tickCount < 10 || tick % 5 != 0) continue; // waitTime 10, checked every 5 ticks
            float r = cl.c.getRadius();
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, cl.c.getBoundingBox())) {
                double dx = e.getX() - cl.c.getX(), dz = e.getZ() - cl.c.getZ();
                if (dx * dx + dz * dz > r * r) continue;
                Integer last = cl.lastApplied.get(e.getId());
                if (last != null && tick - last < 20) continue; // reapplicationDelay
                cl.lastApplied.put(e.getId(), tick);
                Watch w = watch(e);
                if (w == null || w.trusted && !BLIND) continue;
                Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(cl.effect);
                applyEffect(w, h, 0, 100, 0.5, 1.0); // lingering: a quarter of the potion's duration, instant at half
            }
        }
    }

    private static int rgb(ColorParticleOption col) {
        return (Math.round(col.getRed() * 255) << 16) | (Math.round(col.getGreen() * 255) << 8) | Math.round(col.getBlue() * 255);
    }

    /** Effects seen by their particle colours: regeneration, poison, wither, resistance, strength, fire resistance. */
    private void effects(Watch w) {
        List<ParticleOptions> parts = ((IEffectParticles) w.e).ostinato$effectParticles();
        boolean regen = false, poison = false, wither = false, res = false, str = false, fire = false;
        for (ParticleOptions po : parts) {
            if (!(po instanceof ColorParticleOption col)) continue;
            int c = rgb(col);
            regen |= c == (MobEffects.REGENERATION.value().getColor() & 0xFFFFFF);
            poison |= c == (MobEffects.POISON.value().getColor() & 0xFFFFFF);
            wither |= c == (MobEffects.WITHER.value().getColor() & 0xFFFFFF);
            res |= c == (MobEffects.RESISTANCE.value().getColor() & 0xFFFFFF);
            str |= c == (MobEffects.STRENGTH.value().getColor() & 0xFFFFFF);
            fire |= c == (MobEffects.FIRE_RESISTANCE.value().getColor() & 0xFFFFFF);
        }
        w.t.seenEffects(regen, poison, wither, res, fire);
        w.strength = str;
    }

    /** An eaten golden apple or a drunk potion: the use flag held long enough with one in hand. */
    private void use(Watch w) {
        LivingEntity e = w.e;
        if (e.isUsingItem()) {
            ItemStack st = e.getItemInHand(e.getUsedItemHand());
            if (w.useStart < 0 || !ItemStack.isSameItem(st, w.useItem)) {
                w.useStart = tick;
                w.useItem = st.copy();
            }
            return;
        }
        if (w.useStart < 0) return;
        int held = tick - w.useStart;
        ItemStack st = w.useItem;
        w.useStart = -1;
        w.useItem = ItemStack.EMPTY;
        if (held < 30) return; // stopped before the 32 ticks eating and drinking take
        if (w.trusted && !BLIND) return;
        if (st.is(Items.GOLDEN_APPLE)) w.t.apple(false);
        else if (st.is(Items.ENCHANTED_GOLDEN_APPLE)) w.t.apple(true);
        else if (st.is(Items.POTION)) {
            PotionContents pc = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            for (MobEffectInstance mi : pc.getAllEffects()) applyEffect(w, mi.getEffect(), mi.getAmplifier(), mi.getDuration(), 1.0, 0.1);
        }
    }

    private void land(Watch w) {
        if (!Double.isNaN(w.peakY)) {
            w.landedPeak = w.peakY;
            w.landedTick = tick;
        }
        w.peakY = Double.NaN;
    }

    /** Highest point of the current fall, or of the one just ended (the landing packet comes before the damage). */
    private double peak(Watch w, double y) {
        double p = w.peakY;
        if (tick - w.landedTick <= 3 && !Double.isNaN(w.landedPeak)) p = Double.isNaN(p) ? w.landedPeak : Math.max(p, w.landedPeak);
        return Double.isNaN(p) ? y : p;
    }

    private void fall(Watch w) {
        if (w.e.onGround()) land(w);
        else if (Double.isNaN(w.peakY) || w.e.getY() > w.peakY) w.peakY = w.e.getY();
    }

    /** A hearts objective in the tab list or under names, or one whose name says health: read it as health. */
    private void scoreboard(Watch w, ClientLevel level) {
        if (!(w.e instanceof Player p) || w.trusted && !BLIND) return;
        Scoreboard sb = level.getScoreboard();
        for (DisplaySlot slot : new DisplaySlot[]{DisplaySlot.BELOW_NAME, DisplaySlot.LIST}) {
            Objective o = sb.getDisplayObjective(slot);
            if (o == null) continue;
            String name = (o.getName() + " " + o.getDisplayName().getString()).toLowerCase(Locale.ROOT);
            boolean hearts = o.getRenderType() == ObjectiveCriteria.RenderType.HEARTS || name.contains("health") || name.contains("\u2764") || name.contains(" hp");
            if (!hearts) continue;
            ReadOnlyScoreInfo info = sb.getPlayerScoreInfo(p, o);
            if (info == null) continue;
            int v = info.value();
            if (v <= 0 || v > 1024) continue;
            w.t.exact(v, -1, HealthTrack.Source.SCOREBOARD);
            w.t.var = 0.25; // the health criterion rounds up
            return;
        }
    }

    /** A boss bar named as this entity: its progress is health over max health. */
    private void bossBars(ClientLevel level) {
        Map<UUID, LerpingBossEvent> bars = ((IBossOverlay) Minecraft.getInstance().gui.getBossOverlay()).ostinato$events();
        if (bars.isEmpty()) return;
        for (LerpingBossEvent b : bars.values()) {
            String bn = b.getName().getString();
            Watch best = null;
            for (Watch w : watches.values()) {
                if (w.trusted && !BLIND || w.e instanceof Player) continue;
                if (w.e.getDisplayName().getString().equals(bn)) {
                    if (best != null) { // two with one name: the bar cannot say which
                        best = null;
                        break;
                    }
                    best = w;
                }
            }
            if (best != null) best.t.exact(b.getProgress() * best.t.max(), -1, HealthTrack.Source.BOSS_BAR);
        }
    }

    private void writeLog() {
        if (LOG == null || tick % 10 != 0) return;
        try {
            if (log == null) {
                log = Files.newBufferedWriter(Paths.get(LOG));
                log.write("tick,id,name,est,abs,sd,conf,source,synced,syncedAbs,trusted\n");
            }
            for (Watch w : watches.values()) {
                log.write(tick + "," + w.e.getId() + "," + w.e.getName().getString().replace(',', '_') + "," + w.t.health() + "," + w.t.absorption() + ","
                        + String.format(Locale.ROOT, "%.3f,%.3f", w.t.sd(), w.t.confidence()) + "," + w.t.source() + "," + w.e.getHealth() + ","
                        + w.e.getAbsorptionAmount() + "," + w.trusted + "\n");
            }
            if (tick % 200 == 0) log.flush();
        } catch (IOException ex) {
            log = null;
        }
    }
}
