package baritone.process;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;

/**
 * Staying alive: the totem in the offhand, eating a golden apple, and running (or pearling away) when low with
 * nothing left to heal with. The eat and flee clocks live here because the process's tick reads them.
 */
final class CombatSurvival {
    /** What the process does for us: hotbar, aim, keys, the tick's decision label and the gapple count. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        PathingCommand decide(String d);
        PathingCommand decide(String d, PathingCommand cmd);
        void gappleEaten();
    }

    int eatTicks, fleeTicks;

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatExplosives explosives;
    private final CombatPhase phase;
    private final Hands hands;
    private LivingEntity target; // set on each entry point; the routines below read the fight's target from it

    CombatSurvival(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatExplosives explosives, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.explosives = explosives;
        this.phase = phase;
        this.hands = hands;
    }

    boolean canHeal(Player me) {
        return me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING || inv.slotOf(me, Items.GOLDEN_APPLE) >= 0
                || inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 || inv.potion(me, MobEffects.INSTANT_HEALTH) >= 0;
    }

    /** Low on health with nothing to heal: pearl away from the target, else run. */
    PathingCommand flee(Player me, LivingEntity target, double dist) {
        this.target = target;
        hands.use(false);
        // the landing costs about 3 HP in this kit: a pearl thrown at 2 HP is a suicide (pillar perfect, tick 243)
        if (dist < 10 && phase.pearlCool == 0 && me.getHealth() + me.getAbsorptionAmount() > 3.5f && inv.slotOf(me, Items.ENDER_PEARL) >= 0) {
            Vec3 away = new Vec3(me.getX() - target.getX(), 0, me.getZ() - target.getZ());
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 at = me.getEyePosition().add(away.scale(24)).add(0, 7, 0);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            if (!hands.select(me, inv.slotOf(me, Items.ENDER_PEARL))) return hands.decide("swap");
            if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pearl");
            press(ctx.minecraft().options.keyUse);
            phase.pearlCool = 160;
            return hands.decide("pearl");
        }
        if (dist > 16) {
            fleeTicks = 0;
            return hands.decide("flee"); // clear of it: stand and regenerate
        }
        int blk = inv.blockSlot(me);
        if (++fleeTicks > 40 && dist < 6 && blk >= 0 && me.getY() - target.getY() < 5) {
            // can't shake it: tower up out of melee
            if (!hands.select(me, blk)) return hands.decide("swap");
            aimer.aim(new Rotation(me.getYRot(), 90f), true);
            if (me.onGround()) hands.key(Input.JUMP);
            else if (me.getDeltaMovement().y < 0.1 && ctx.world().getBlockState(me.blockPosition().below()).isAir()) explosives.click(me, me.blockPosition().below().below());
            return hands.decide("pillar");
        }
        return hands.decide("flee", new PathingCommand(new baritone.api.pathing.goals.GoalRunAway(18, target.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
    }

    boolean eat(Player me, LivingEntity target) {
        this.target = target;
        Item apple = me.getHealth() <= 6 && inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        if (inv.slotOf(me, apple) < 0) apple = Items.ENCHANTED_GOLDEN_APPLE;
        if (inv.slotOf(me, apple) < 0) {
            eatTicks = 0;
            return false;
        }
        if (!hands.select(me, inv.slotOf(me, apple))) return true;
        if (me.getMainHandItem().getItem() != apple) return true;
        // a raised offhand shield stays in use across a hotbar switch, so the apple never starts: let go first
        if (me.isUsingItem() && me.getUseItem().getItem() != apple) {
            hands.use(false);
            return true;
        }
        if (eatTicks++ == 0) hands.gappleEaten();
        hands.key(Input.MOVE_BACK); // back off while chewing
        hands.use(true);
        hands.look(target.getEyePosition());
        if (eatTicks > 36) {
            hands.use(false);
            eatTicks = 0;
        }
        return true;
    }

    void keepTotem(Player me) {
        if (me.getHealth() > 8 && !explosives.fighting() || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        inv.toOffhand(me, Items.TOTEM_OF_UNDYING);
    }
}
