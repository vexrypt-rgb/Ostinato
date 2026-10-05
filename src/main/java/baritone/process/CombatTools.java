package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;

/**
 * The kit's side tools, tried in order once nothing more pressing has the tick: a cobweb at the foe's feet, a
 * healing or harming potion, the soul-sand fire the bow shoots through, a crossbow bolt, a thrown trident. Each
 * keeps its own cooldown; the process only asks {@link #run} and acts on the command it returns.
 */
final class CombatTools {
    /** What the process does for us: hotbar, aim, use key, the tick's decision label, and the attack count. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        PathingCommand decide(String d);
        PathingCommand decide(String d, PathingCommand cmd);
        void attacked();
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatExplosives explosives;
    private final Hands hands;
    private LivingEntity target; // set on each run(); the routines below read the fight's target from it
    private int webCool, potCool, chargeTicks;
    private int fireCool, fireStage, fireTicks;
    private BlockPos firePos;
    private int xbAdvance, xbWait;

    CombatTools(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatExplosives explosives, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.explosives = explosives;
        this.hands = hands;
    }

    /** One tick of the fire cooldown; the process ticks it with the other cooldowns, ahead of the decision chain. */
    void coolFire() {
        if (fireCool > 0) fireCool--;
    }

    /** Forget a pending crossbow advance when the fight is lost. */
    void reset() {
        xbAdvance = 0;
    }

    private Vec3 tv() {
        return targeting.velocity(target);
    }

    PathingCommand run(Player me, LivingEntity target, double dist, boolean los) {
        this.target = target;
            if (webCool > 0) webCool--;
            // a web in the target's feet slows it into our hits
            if (webCool == 0 && los && dist > 2.4 && dist < 5 && target.onGround() && inv.slotOf(me, Items.COBWEB) >= 0
                    && ctx.world().getBlockState(target.blockPosition()).isAir()) {
                webCool = 60;
                explosives.place(me, Items.COBWEB, target.blockPosition().below());
                return hands.decide("web");
            }
            if (potCool > 0) potCool--;
            if (potCool == 0) {
                int heal = inv.potion(me, MobEffects.INSTANT_HEALTH), harm = inv.potion(me, MobEffects.INSTANT_DAMAGE);
                if (heal >= 0 && me.getHealth() <= 9) {
                    if (!hands.select(me, heal)) return hands.decide("swap");
                    if (!aimer.face(me.getYRot(), 90f, 2.5f)) return hands.decide("pot");
                    press(ctx.minecraft().options.keyUse);
                    potCool = 12;
                    return hands.decide("pot");
                }
                if (harm >= 0 && los && dist > 3 && dist < 12) {
                    Vec3 at = target.position().add(tv().scale(dist / 0.5)).add(0, 0.2, 0);
                    Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at.add(0, dist * 0.12, 0), ctx.playerRotations());
                    if (!hands.select(me, harm)) return hands.decide("swap");
                    if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pot");
                    press(ctx.minecraft().options.keyUse);
                    potCool = 25;
                    return hands.decide("pot");
                }
            }
            // soul sand + flint and steel + any bow: shoot through the fire to set the target alight
            if (inv.slotOf(me, Items.SOUL_SAND) >= 0 && inv.slotOf(me, Items.FLINT_AND_STEEL) >= 0 && inv.slotOf(me, Items.BOW) >= 0
                    && inv.slotOf(me, Items.ARROW) >= 0 && los && dist > (fireStage > 0 ? 6 : 11) && dist < 22 && (fireStage > 0 || (fireCool == 0 && target.onGround() && me.onGround() && !target.isOnFire()))) {
                if (fireStage == 0) {
                    Vec3 dir = new Vec3(target.getX() - me.getX(), 0, target.getZ() - me.getZ()).normalize();
                    BlockPos g = BlockPos.containing(me.getX() + dir.x * 2, me.getY() - 1, me.getZ() + dir.z * 2);
                    if (!ctx.world().getBlockState(g).isSolid() || !ctx.world().getBlockState(g.above()).isAir() || !ctx.world().getBlockState(g.above(2)).isAir()) {
                        fireStage = -1;
                    } else {
                        firePos = g;
                        fireStage = 1;
                        fireTicks = 0;
                    }
                }
                if (fireStage > 0) {
                    if (++fireTicks > 80) {
                        fireStage = 0;
                        fireCool = 400;
                    } else if (fireStage == 1) {
                        if (explosives.place(me, Items.SOUL_SAND, firePos)) fireStage = 2;
                        return hands.decide("fire");
                    } else if (fireStage == 2) {
                        if (explosives.place(me, Items.FLINT_AND_STEEL, firePos.above())) fireStage = 3;
                        return hands.decide("fire");
                    } else {
                        if (!ctx.world().getBlockState(firePos.above(2)).isAir() && ctx.world().getBlockState(firePos.above(2)).getBlock() != net.minecraft.world.level.block.Blocks.FIRE
                                && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.SOUL_FIRE
                                && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.FIRE) fireStage = 0;
                        if (target.isOnFire() || fireTicks > 70) { fireStage = 0; fireCool = 400; }
                        return hands.decide("bow", bow(me, target));
                    }
                }
            } else if (fireStage < 0 && (!target.onGround() || dist < 5)) {
                fireStage = 0;
            } else if (fireStage > 0) {
                fireStage = 0;
            }
            int xb = inv.slotOf(me, Items.CROSSBOW);
            if (xbAdvance > 0) xbAdvance--;
            // 135907 crossbow easy: shooting from 15 blocks for 1800 ticks never closed the gap. One bolt, then walk in for a while.
            if (xb >= 0 && xbAdvance == 0 && los && dist > 5 && dist < 70 && (inv.slotOf(me, Items.ARROW) >= 0 || net.minecraft.world.item.CrossbowItem.isCharged(me.getInventory().getItem(xb)))) {
                if (!hands.select(me, xb)) return hands.decide("swap");
                Vec3 at = arcAim(me.getEyePosition(), target.getBoundingBox().getCenter(), tv(), 3.15);
                hands.look(at);
                ItemStack held = me.getMainHandItem();
                if (net.minecraft.world.item.CrossbowItem.isCharged(held)) {
                    // loaded: shoot only once the aim has settled on the arc, not on the way there
                    if (aimer.aimedAt(me, at, 3f) || ++xbWait > 40) {
                        hands.use(false);
                        press(ctx.minecraft().options.keyUse);
                        hands.attacked();
                        xbWait = 0;
                        xbAdvance = 70;
                    }
                } else if (me.isUsingItem()) {
                    // a crossbow only loads when the use key is let go after the full draw
                    if (me.getTicksUsingItem() >= net.minecraft.world.item.CrossbowItem.getChargeDuration(held, me)) hands.use(false);
                    else hands.use(true);
                } else {
                    hands.use(true);
                    press(ctx.minecraft().options.keyUse);
                }
                return hands.decide("crossbow");
            }
            int tr = inv.slotOf(me, Items.TRIDENT);
            if (tr >= 0 && los && dist > 5 && dist < 40) {
                if (!hands.select(me, tr)) return hands.decide("swap");
                hands.look(target.getEyePosition().add(0, dist * 0.04, 0));
                if (++chargeTicks > 14) {
                    hands.use(false);
                    chargeTicks = -8;
                } else if (chargeTicks > 0) {
                    hands.use(true);
                }
                return hands.decide("trident");
            }
            return null;
    }

    PathingCommand bow(Player me, LivingEntity target) {
        this.target = target;
        if (!hands.select(me, inv.slotOf(me, Items.BOW))) return hands.decide("swap");
        if (me.getMainHandItem().getItem() != Items.BOW) return hands.decide("swap");
        // lead: arrow ~3 b/t at full draw, gravity 0.05
        Vec3 at = arcAim(me.getEyePosition(), target.getBoundingBox().getCenter(), tv(), 3.0);
        hands.look(at);
        if (me.isUsingItem() && me.getTicksUsingItem() >= 21) {
            hands.use(false);
            hands.attacked();
        } else {
            hands.use(true);
        }
        return hands.decide("bow");
    }
}
