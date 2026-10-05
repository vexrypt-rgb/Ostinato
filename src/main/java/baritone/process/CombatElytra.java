package baritone.process;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;

/**
 * The mace carried on wings: put the elytra on, rise on a rocket or a wind charge, glide above the foe, dive and
 * smash, then swap the chestplate back once down. The mace phase it flies is kept in {@link CombatPhase}.
 */
final class CombatElytra {
    /** What the process does for us: hotbar, aim, keys, the click, the tick's decision label and the shield clock. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        boolean hit(Player me);
        PathingCommand decide(String d);
        int lastShield();
        void shielded(int tick);
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatDefense defense;
    private final CombatPhase phase;
    private final Hands hands;

    CombatElytra(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatDefense defense, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.defense = defense;
        this.phase = phase;
        this.hands = hands;
    }

    /** One tick of the wings play; null when the kit or the moment doesn't call for it. */
    PathingCommand run(Player me, LivingEntity target, double dist, boolean los, boolean overhead, int mace, int wind) {
        int rocket = inv.slotOf(me, Items.FIREWORK_ROCKET);
        net.minecraft.world.entity.EquipmentSlot chestSlot = net.minecraft.world.entity.EquipmentSlot.CHEST;
        Item worn = me.getItemBySlot(chestSlot).getItem();
        if (mace >= 0 && worn != Items.ELYTRA && phase.macePhase == 0 && phase.maceCool == 0 && me.onGround() && los && dist > 6 && dist < 40 && !overhead
                && (rocket >= 0 || wind >= 0) && inv.slotOf(me, Items.ELYTRA) >= 0) {
            // the wings are in the hotbar, not on the chest: put them on (the chestplate goes where they were)
            phase.chestSaved = worn;
            inv.invSwap(me, 6, inv.slotOf(me, Items.ELYTRA));
            return hands.decide("elytra");
        }
        if (worn == Items.ELYTRA && phase.chestSaved != null && phase.chestSaved != Items.AIR && phase.macePhase == 0 && me.onGround() && phase.maceCool > 0) {
            // landed: the chestplate is worth more than the wings in a melee
            if (inv.slotOf(me, phase.chestSaved) >= 0 && inv.invSwap(me, 6, inv.slotOf(me, phase.chestSaved))) phase.chestSaved = null;
            return hands.decide("elytra");
        }
        if (mace >= 0 && (rocket >= 0 || wind >= 0) && worn == Items.ELYTRA
                && (phase.macePhase >= 5 || phase.macePhase == 0 && me.onGround() && phase.maceCool == 0 && !overhead && targeting.velocity(target).y > -0.3 && dist > 3 && dist < 40 && los)) {
            // elytra mace: take off, rocket up above the target, dive and smash
            phase.maceTicks++;
            if (phase.macePhase == 0) {
                hands.key(Input.JUMP);
                phase.boosted = false;
                phase.macePhase = 5;
                phase.maceTicks = 0;
                return hands.decide("elytra");
            }
            Vec3 tp = aimPoint(me, target);
            if (phase.macePhase == 5) { // rising: boost with a wind charge, then press jump in the air to open the wings
                if (me.isFallFlying()) {
                    phase.macePhase = 6;
                    phase.maceTicks = 0;
                } else if (rocket < 0 && !phase.boosted && phase.maceTicks >= 2 && wind >= 0) {
                    if (!hands.select(me, wind)) return hands.decide("swap");
                    if (aimer.throwStraightDown(me)) phase.boosted = true;
                } else if (me.getDeltaMovement().y < 0 && !me.onGround() && (rocket >= 0 || phase.boosted)) {
                    if (phase.maceTicks % 2 == 0) hands.key(Input.JUMP);
                } else if (phase.maceTicks > 40) {
                    phase.macePhase = 0;
                    phase.maceCool = 40;
                }
                return hands.decide("elytra");
            }
            // phase.macePhase 6: gliding
            // a diver coming down on us from above wins any trade in the air, and it needs only ~6 ticks from a hover to land:
            // under a mace carrier, take the shield up before it commits. The shield checks the facing with its height
            // included, so face the bearing level, not the carrier overhead.
            double aboveBy = target.getY() - me.getY();
            double hzGap = Math.hypot(target.getX() - me.getX(), target.getZ() - me.getZ());
            // the last two ticks of the dive are the ones that land: a raised shield stays up until the diver is level with us
            boolean raised = me.isUsingItem() && me.getUseItem().getItem() == Items.SHIELD && targeting.velocity(target).y < -0.3;
            if (target.getMainHandItem().getItem() == Items.MACE && aboveBy > (raised ? -0.5 : 3) && aboveBy < 16 && hzGap < 8 && groundGap(me) > 6
                    && me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem())) {
                if (!hands.select(me, mace)) return hands.decide("swap");
                Vec3 face = defense.shieldBearing(me, target, targeting.velocity(target), hands.lastShield(), -targeting.velocity(target).y > 0.05 ? (aboveBy - 1.0) / -targeting.velocity(target).y : 99);
                if (face != null) hands.look(face);
                else aimer.aim(new Rotation(me.getYRot(), 0f), true);
                hands.use(true);
                hands.shielded(me.tickCount);
                return hands.decide("block");
            }
            boolean climbing = rocket >= 0 && me.getY() < target.getY() + 14 && phase.maceTicks < 70;
            Vec3 aim = climbing ? new Vec3(tp.x, me.getEyeY() + 30, tp.z).add(tp.subtract(me.position()).multiply(0.0, 0, 0)) : tp;
            if (climbing) {
                Vec3 flat = new Vec3(tp.x - me.getX(), 0, tp.z - me.getZ());
                flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
                aim = me.getEyePosition().add(flat.scale(12)).add(0, 14, 0); // ~50 degrees up
            }
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations());
            // a dive that is not going to connect must not end in the ground: flare out while there is still room
            boolean flare = !climbing && me.isFallFlying() && me.getDeltaMovement().y < -0.5
                    && groundGap(me) < 6 + 14 * Math.min(1.0, -me.getDeltaMovement().y / 2.0)
                    && (exactReach(me, target) > REACH + 1.5 || target.getY() > me.getY() + 1.0);
            double speed = me.getDeltaMovement().length();
            // pulling up at a target overhead with no speed is a stall: wings drop out of the sky. Trade height for speed first.
            if (!climbing && !flare && r.getPitch() < -35f && speed < 1.3) r = new Rotation(r.getYaw(), groundGap(me) > 25 ? 25f : -5f);
            if (flare) r = new Rotation(r.getYaw(), -20f);
            aimer.aim(r, true);
            if (climbing && speed < 1.2 && phase.maceTicks % 12 == 3) {
                if (!hands.select(me, rocket)) return hands.decide("swap");
                press(ctx.minecraft().options.keyUse);
            } else if (!climbing) {
                if (!hands.select(me, mace)) return hands.decide("swap");
                if (exactReach(me, target) <= REACH - 0.05) {
                    hands.hit(me);
                    phase.macePhase = 0;
                    phase.maceCool = 10;
                }
            } else {
                if (!hands.select(me, mace)) return hands.decide("swap");
            }
            if (me.onGround() || !me.isFallFlying() && phase.maceTicks > 6 || phase.maceTicks > 200) {
                phase.macePhase = 0;
                phase.maceCool = 40;
            }
            return hands.decide("mace");
        }
        return null;
    }
}
