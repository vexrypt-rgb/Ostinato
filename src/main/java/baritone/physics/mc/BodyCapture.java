/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics.mc;

import baritone.api.physics.BodyState;
import baritone.api.utils.IHealthEstimator;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * Ostinato: reads a live entity into a {@link BodyState}. Game thread only. Projectile drag constants are the 26.3
 * classes' own (they are protected there): arrows 0.99 in air, 0.6 in water (tridents 0.99); thrown items 0.99 / 0.8;
 * hurting projectiles 0.95 / 0.8 (a dangerous wither skull 0.73 in air; wind charges 1 / 1). A primed TNT's power is
 * not synced to clients: vanilla TNT's 4 is assumed and the body is flagged {@link BodyState#POWER_ASSUMED}, which the
 * predictor turns into {@code Assumption.TNT_POWER_ASSUMED}.
 */
final class BodyCapture {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private BodyCapture() {}

    /** Whether {@link #read} has a body type for this entity. */
    static boolean supported(Entity e) {
        return e instanceof LivingEntity || e instanceof PrimedTnt || e instanceof FallingBlockEntity || e instanceof AbstractArrow
                || e instanceof ThrowableProjectile || e instanceof AbstractHurtingProjectile;
    }

    /**
     * @param local whether {@code e} is the local player (its state is the client's own, not a synced view)
     * @param health health estimates for other living entities (their synced health can be hidden or stale)
     */
    static BodyState read(Entity e, boolean local, IHealthEstimator health) {
        BodyState.Type type;
        if (e instanceof Player) type = BodyState.Type.PLAYER;
        else if (e instanceof LivingEntity) type = BodyState.Type.LIVING;
        else if (e instanceof PrimedTnt) type = BodyState.Type.PRIMED_TNT;
        else if (e instanceof FallingBlockEntity) type = BodyState.Type.FALLING_BLOCK;
        else if (e instanceof ThrownTrident) type = BodyState.Type.TRIDENT;
        else if (e instanceof AbstractArrow) type = BodyState.Type.ARROW;
        else if (e instanceof ThrowableProjectile) type = BodyState.Type.THROWABLE;
        else if (e instanceof AbstractHurtingProjectile) type = BodyState.Type.HURTING_PROJECTILE;
        else throw new IllegalArgumentException("no body type for " + e.getType());
        Vec3 v = e.getDeltaMovement();
        BodyState.Builder b = BodyState.builder(type)
                .entityId(e.getId())
                .pos(e.getX(), e.getY(), e.getZ())
                .vel(v.x, v.y, v.z)
                .size(e.getBbWidth(), e.getBbHeight(), e.getEyeHeight())
                .rot(e.getYRot(), e.getXRot())
                .onGround(e.onGround())
                .fallDistance(e.fallDistance)
                .gravity(e.getGravity())
                .stepHeight(e.maxUpStep())
                .age(e.tickCount)
                .flag(BodyState.NO_GRAVITY, e.isNoGravity())
                .flag(BodyState.CLIENT_VIEW, !local)
                .flag(BodyState.FALL_IMMUNE, e.typeHolder().is(EntityTypeTags.FALL_DAMAGE_IMMUNE));
        if (e instanceof LivingEntity l) living(b, l, local, health);
        else if (e instanceof PrimedTnt t) b.fuse(t.getFuse(), 4).flag(BodyState.POWER_ASSUMED, true);
        else if (e instanceof ThrownTrident) b.drag(0.99f, 0.99f, 0);
        else if (e instanceof AbstractArrow) b.drag(0.99f, 0.6f, 0);
        else if (e instanceof ThrowableProjectile) b.drag(0.99f, 0.8f, 0);
        else if (e instanceof AbstractWindCharge w) b.drag(1, 1, w.accelerationPower);
        else if (e instanceof WitherSkull w) b.drag(w.isDangerous() ? 0.73f : 0.95f, 0.8f, w.accelerationPower);
        else if (e instanceof AbstractHurtingProjectile h) b.drag(0.95f, 0.8f, h.accelerationPower);
        return b.build();
    }

    private static void living(BodyState.Builder b, LivingEntity l, boolean local, IHealthEstimator health) {
        b.speed((float) attr(l, Attributes.MOVEMENT_SPEED, 0.1))
                .jumpStrength((float) attr(l, Attributes.JUMP_STRENGTH, 0.42))
                .fall((float) attr(l, Attributes.SAFE_FALL_DISTANCE, 3), (float) attr(l, Attributes.FALL_DAMAGE_MULTIPLIER, 1))
                .knockbackResistance((float) attr(l, Attributes.KNOCKBACK_RESISTANCE, 0), (float) attr(l, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 0))
                .bounciness((float) attr(l, Attributes.BOUNCINESS, 0))
                .dragModifiers((float) attr(l, Attributes.AIR_DRAG_MODIFIER, 1), (float) attr(l, Attributes.FRICTION_MODIFIER, 1))
                .efficiency((float) attr(l, Attributes.MOVEMENT_EFFICIENCY, 0), (float) attr(l, Attributes.WATER_MOVEMENT_EFFICIENCY, 0))
                .sneakingSpeed((float) attr(l, Attributes.SNEAKING_SPEED, 0.3))
                .effects(level(l, MobEffects.JUMP_BOOST), level(l, MobEffects.LEVITATION), level(l, MobEffects.RESISTANCE))
                .flag(BodyState.SPRINTING, l.isSprinting())
                .flag(BodyState.SNEAKING, l.isShiftKeyDown())
                .flag(BodyState.SWIMMING, l.isSwimming())
                .flag(BodyState.FALL_FLYING, l.isFallFlying())
                .flag(BodyState.SLOW_FALLING, l.hasEffect(MobEffects.SLOW_FALLING))
                .flag(BodyState.DOLPHINS_GRACE, l.hasEffect(MobEffects.DOLPHINS_GRACE))
                .flag(BodyState.WEAVING, l.hasEffect(MobEffects.WEAVING));
        if (l instanceof Player p) {
            b.flag(BodyState.FLYING, p.getAbilities().flying).flag(BodyState.MAY_FLY, p.getAbilities().mayfly);
        }
        float hp = local ? l.getHealth() : health.getEstimatedHealth(l);
        b.health(hp, l.getAbsorptionAmount());
        float prot = 0, fall = 0, blast = 0;
        HolderLookup.RegistryLookup<Enchantment> reg = l.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (EquipmentSlot slot : ARMOR) {
            ItemStack st = l.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            prot += ench(reg, Enchantments.PROTECTION, st);
            fall += 3 * ench(reg, Enchantments.FEATHER_FALLING, st);
            blast += 2 * ench(reg, Enchantments.BLAST_PROTECTION, st);
        }
        b.armor(l.getArmorValue(), (float) attr(l, Attributes.ARMOR_TOUGHNESS, 0), prot + fall, prot + blast);
    }

    private static double attr(LivingEntity l, Holder<Attribute> a, double fallback) {
        return l.getAttributes().hasAttribute(a) ? l.getAttributeValue(a) : fallback;
    }

    /** Amplifier + 1, 0 without the effect. */
    private static int level(LivingEntity l, Holder<MobEffect> effect) {
        MobEffectInstance i = l.getEffect(effect);
        return i == null ? 0 : i.getAmplifier() + 1;
    }

    private static int ench(HolderLookup.RegistryLookup<Enchantment> reg, ResourceKey<Enchantment> key, ItemStack st) {
        return reg.get(key).map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, st)).orElse(0);
    }
}
