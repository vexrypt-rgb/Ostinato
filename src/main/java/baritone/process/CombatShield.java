package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;
import static baritone.process.CombatInventory.*;

/**
 * The shield in melee and against a dive: when to hold it between swings, when a foe is hidden behind its own, and
 * how to cover or dodge a mace coming down. The block clocks and what was learned of the foe's swings are kept here
 * as package-visible fields, because the process's tick reads and resets them too.
 */
final class CombatShield {
    /** What the process does for us: hotbar, aim, keys, the tick's decision label, the eat clock and the block count. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        PathingCommand decide(String d);
        int eatTicks();
        void blockStarted();
        void dodge(Player me);
    }

    int blockTicks, lastShieldTick = -1000, lastAxeTick = -1000, targetSwingTick;
    boolean counter, axeHeld, flicked;
    /** Learned in the fight: ticks between the opponent's swings, and how long after a swing our hits bounce off it. */
    int swingGap, unseenBlock;

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatDefense defense;
    private final CombatPhase phase;
    private final CombatPolicy.Shield p;
    private final Hands hands;
    private LivingEntity target; // set on each entry point; the routines below read the fight's target from it

    CombatShield(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatDefense defense, CombatPhase phase, CombatPolicy.Shield p, Hands hands) {
        this.p = p;
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.defense = defense;
        this.phase = phase;
        this.hands = hands;
    }

    /** The player respawned and tickCount restarted: every stamp taken against the old clock is void. */
    void respawned() {
        targetSwingTick = 0;
        lastShieldTick = lastAxeTick = -1000;
        axeHeld = flicked = false;
        swingGap = unseenBlock = 0;
    }

    /** Per-tick read of the foe: has it answered our shield with a flick, and when did it last swing. Runs before the block. */
    void observe(Player me, LivingEntity target) {
        // VexBot answers a raised shield with an axe flick inside three ticks: once it has, the shield is only bait
        if (target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && me.getOffhandItem().getItem() == Items.SHIELD
                && me.getCooldowns().isOnCooldown(me.getOffhandItem())) flicked = true;
        if (target.swinging && target.swingTime == 0) { // before the block: a held shield returns early
            int sinceLast = me.tickCount - targetSwingTick;
            if (sinceLast >= 6 && sinceLast <= 40) swingGap = sinceLast;
            targetSwingTick = me.tickCount;
        }
    }

    /**
     * Raise the shield against a melee swing or an arrow in flight, or step out of a ranged aim; otherwise let the
     * shield down if it was up. Null when neither applies. {@code blockMelee} is {@link #meleeBlock}'s verdict.
     */
    PathingCommand guard(Player me, LivingEntity target, double dist, boolean blockMelee) {
        if (phase.pearlStage == 0 && (blockMelee || defense.arrowIncoming(me))) {
            // the use key must not start a bow or food in the main hand: the shield only rises when the main hand has no use action
            // (062608 bow expert: blocked arrows at 10 blocks with the bow in hand, drew it instead, took 4.76 a shot)
            hands.select(me, inv.weapon(me));
            if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
            hands.look(target.getEyePosition());
            if (blockMelee && dist > 2.4) hands.key(Input.MOVE_FORWARD); // stay where the answer to its swing still reaches
            hands.use(true);
            if (blockTicks++ == 0) hands.blockStarted();
            lastShieldTick = me.tickCount;
            return hands.decide("block");
        }
        if (phase.pearlStage == 0 && hands.eatTicks() == 0 && phase.macePhase == 0 && defense.foeAimed(target, dist)) {
            hands.select(me, inv.weapon(me));
            hands.look(target.getEyePosition());
            hands.use(false);
            hands.dodge(me);
            return hands.decide("dodge");
        }
        if (blockTicks > 0) {
            hands.use(false);
            blockTicks = 0;
        }
        return null;
    }

    /**
     * Between our own swings the opponent's sword is the only thing hurting us: hold the shield up while the
     * weapon recharges and drop it as the swing comes back. A raised target shield is the axe's job instead.
     */
    boolean meleeBlock(Player me, LivingEntity target, double dist) {
        this.target = target;
        if (me.getOffhandItem().getItem() != Items.SHIELD && inv.slotOf(me, Items.SHIELD) < 0) return false;
        // 212743 hard aggressive: every 6 and 9 landed in the air after our own jump swing.
        if (dist > 5.5 || me.isInWater() || hands.eatTicks() > 0 || phase.macePhase != 0) return false;
        if (target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD)) return false; // it cannot swing mid-bite

        counter = false;
        // 221131 expert: VexBot's sword put our shield on cooldown with the first blocked swing, and the
        // next 60 ticks were spent standing behind a shield that was not there.
        if (me.getCooldowns().isOnCooldown(me.getOffhandItem())) return false;
        if (target.isBlocking() && dist <= REACH + 0.5 && inv.best(me, AXES) >= 0 && me.tickCount - lastAxeTick > 25) return false;
        if (!(target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) || target.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)
                || isSpear(target.getMainHandItem())
                || target.getMainHandItem().getItem() == Items.MACE)) return false;
        float cd = me.getAttackStrengthScale(0.5f);
        // VexBot's ShieldController drops its shield at 0.95 charge to swing and needs 5 ticks to get it back:
        // against sword and shield, take the swing on ours and answer inside that warm-up.
        // 215619 expert defensive: with its shield axed it still won every trade, its charged sword waiting
        // for ours to come down. The answer after its swing is the only free hit, shield or no shield.
        if (target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)) {
            if (flicked) return false;
            if (!me.isBlocking() && cd >= 0.9f && dist <= REACH && !target.isUsingItem() && !hiddenShield(me, target)) return false; // a free swing beats a shield it will axe
        }
        boolean holding = blockTicks > 0 && me.isUsingItem();
        // 224436 expert adaptive: the shield came down at 0.78 charge, six ticks before each of its swings.
        // Its next swing is due one observed swing gap after the last: be behind a warmed-up shield for it, then answer.
        int since = me.tickCount - targetSwingTick;
        int gap = swingGap > 0 ? swingGap : p.defaultGap; // a sword recharges in 12.5 ticks
        boolean due = target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && since >= gap - p.dueEarly && since < gap + p.dueLate && dist < p.dueDist;
        // raise early enough for the shield's warm-up, hold until the swing is nearly ready
        return holding ? (cd < p.holdCharge || due) && blockTicks < p.holdMaxTicks : (cd < p.raiseCharge || due) && since > 2;
    }

    /** A blockhitting opponent is behind its shield right after its own swing, and the use flag does not always reach the client. */
    boolean hiddenShield(Player me, LivingEntity target) {
        this.target = target;
        int since = me.tickCount - targetSwingTick;
        return since >= 0 && since < unseenBlock && !(axeHeld && me.tickCount - lastAxeTick < 100);
    }

        /**
     * They are well above us and coming down. A shield only covers the half-circle we face and a dive lands where
     * it likes, so standing under it is the one wrong answer. A pearl that meets them puts us at their height,
     * falling after them with the mace; failing that, be somewhere else when they arrive.
     */
    PathingCommand underDive(Player me, LivingEntity target, double dist, boolean los) {
        this.target = target;
        boolean hop = phase.macePhase == 2; // our own smash is in the air: a diver coming down on it is a trade we lose
        // dropping a raised shield in the diver's last two ticks (it is within 4 blocks) is what killed us twice: hold it until the diver is level
        boolean raised = me.isUsingItem() && me.getUseItem().getItem() == Items.SHIELD && target.getY() > me.getY() - 0.5;
        double hzUp = Math.hypot(target.getX() - me.getX(), target.getZ() - me.getZ());
        // a mace overhead can turn from rising to lethal in one tick (a 5 block smash kills): near it the shield goes up regardless of its velocity
        boolean closeAbove = hzUp < 4 && target.getY() - me.getY() < 9 && target.getMainHandItem().getItem() == Items.MACE
                && me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem());
        double vy = Math.min(targeting.velocity(target).y, targeting.rawY());
        if (phase.macePhase != 0 && !hop || phase.pearlStage != 0 && phase.pearlStage != 2 || hands.eatTicks() > 0 || target.onGround() || phase.pearlStage == 0 && vy > (hzUp < 4 ? -0.3 : -0.6) && !closeAbove || target.getY() < me.getY() + (raised ? 0 : 4) || !me.onGround() && !hop && !raised && !(me.getDeltaMovement().y < -0.3 && groundGap(me) < 20)) return null; // a wind charge popping us off the ground just before the smash is not the end of the block
        if (phase.pearlStage == 2) { // a pearl is already out: keep moving until it lands us somewhere
            if (me.position().distanceTo(phase.pearlFrom) > 3.5 || phase.pearlTicks++ > 40) return null;
            phase.pearlFrom = phase.pearlFrom.add(me.getDeltaMovement().multiply(1, 0, 1));
        }
        int pearlSlot = inv.slotOf(me, Items.ENDER_PEARL);
        // off: the 5 HP landing put us in the diver's path (pearlmace 12/25 with it, 16/25 without)
        if (false && !hop && phase.pearlStage == 0 && inv.slotOf(me, Items.MACE) >= 0 && pearlSlot >= 0 && phase.pearlCool == 0 && los && dist < 25 && me.getHealth() + me.getAbsorptionAmount() >= 12) {
            Vec3 eye = me.getEyePosition(), tp = target.getBoundingBox().getCenter(), v = targeting.velocity(target), need = null;
            double sum = 0, drop = 0, u = 0;
            for (int t = 1; t <= 24 && need == null; t++) {
                sum += Math.pow(0.99, t - 1);
                drop += u;
                u = u * 0.99 - 0.03;
                tp = tp.add(v);
                v = new Vec3(v.x, (v.y - 0.08) * 0.98, v.z);
                if (tp.y - 0.9 < me.getY() + 3) break; // it will be down before the pearl is there
                Vec3 n = tp.subtract(eye).subtract(0, drop, 0).scale(1 / sum);
                if (n.length() <= 1.6) need = n; // the first tick the pearl can be where they will be
            }
            if (need != null) {
                hands.use(false);
                if (!hands.select(me, pearlSlot)) return hands.decide("swap");
                Rotation r = RotationUtils.calcRotationFromVec3d(eye, eye.add(need), ctx.playerRotations());
                if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pearl");
                press(ctx.minecraft().options.keyUse);
                phase.pearlStage = 2;
                phase.pearlTicks = 0;
                phase.pearlFrom = me.position();
                return hands.decide("pearl");
            }
        }
        Vec3 away = me.position().subtract(target.position()).multiply(1, 0, 1);
        if (target.getMainHandItem().getItem() != Items.MACE || away.length() > 6) return null;
        if (away.length() < 0.3) away = Vec3.directionFromRotation(0, me.getYRot());
        // Running only buys time while the diver is high. In its last few ticks it has committed to a path
        // that tracks us at our own speed: a raised shield stops a smash, so take the landing behind it.
        double fall = -vy;
        double ticksLeft = fall > 0.05 ? (target.getY() - me.getY() - 1.0) / fall : closeAbove ? 5 : 99;
        if (closeAbove) ticksLeft = Math.min(ticksLeft, 8); // it can commit to a dive in one tick: a drifting diver this close is not a reason to lower the shield
        if (me.getOffhandItem().getItem() != Items.SHIELD && ticksLeft > 6) { // a wind charge or totem left in the offhand: put the shield back while the diver is still high
            for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == Items.SHIELD) {
                inv.toOffhand(me, Items.SHIELD);
                return hands.decide("swap");
            }
        }
        boolean shield = me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem());
        // once it is up, keep it up: the fall speed estimate jumps when the diver is knocked, and dropping it for a step is fatal
        boolean held = me.isUsingItem() && me.getUseItem().getItem() == Items.SHIELD;
        if (hop) {
            if (!shield || ticksLeft > 20 || away.length() > 5) return null;
            phase.macePhase = 0;
            phase.maceCool = 20;
        }
        if (shield && ticksLeft <= 30 && away.length() < 6) { // a shield takes ~5 ticks to count as raised
            int hand = inv.slotOf(me, Items.MACE);
            if (hand >= 0 && !hands.select(me, hand)) return hands.decide("swap");
            // face where it is now: a landing point predicted onto our own spot has no bearing, and a shield
            // only covers the front half, so a diver that ends up behind us gets through
            Vec3 tp = defense.shieldBearing(me, target, targeting.velocity(target), lastShieldTick, ticksLeft);
            // the block test uses the attacker's offset at impact, however small: running away first turned the shield 180 degrees off it
            if (tp != null) hands.look(tp);
            // a diver chases us and lags behind our drift: backing away from the side it is on, facing it, keeps its landing inside the shield's half
            Vec3 side = target.position().subtract(me.position()), view = me.getViewVector(1f);
            double sideH = Math.hypot(side.x, side.z);
            if (me.onGround() && ticksLeft <= 12 && sideH > 0.02 && sideH < 1.5 && view.x * side.x + view.z * side.z > 0) hands.key(Input.MOVE_BACK);
            hands.use(true);
            if (blockTicks++ == 0) hands.blockStarted();
            lastShieldTick = me.tickCount;
            return hands.decide("block");
        }
        hands.use(false);
        aimer.aim(new Rotation((float) Math.toDegrees(Math.atan2(-away.x, away.z)), 0f), true);
        hands.key(Input.MOVE_FORWARD);
        hands.key(Input.SPRINT);
        return hands.decide(shield ? "dodge" : me.getOffhandItem().getItem() == Items.SHIELD ? "dodge-cd" : "dodge-off");
    }

    boolean shieldDive(Player me, LivingEntity target, double dist) {
        this.target = target;
        if (dist > 7 || target.onGround() || target.getY() < me.getY() + 1.0) return false;
        // 05:32 blocked for the whole jump (507 ticks) and still took D9 with the shield up.
        // Only the last part of a real descent. While they are high, keep jabbing.
        if (targeting.velocity(target).y >= -0.08 || target.getY() > me.getY() + 2.6) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD && inv.slotOf(me, Items.SHIELD) < 0) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
        // A spear's right-click uses the spear, so the shield never reaches isBlocking() (logs: flag U, never B, D9).
        int hand = inv.slotOf(me, Items.MACE);
        if (hand < 0) hand = inv.spearSlot(me);
        if (hand >= 0 && !hands.select(me, hand)) return true;
        hands.look(target.getEyePosition());
        hands.use(true);
        // a diver lands where we stand: step out from under it as well as covering up
        if (horizontalBoxDist(me, target) < 2.5) hands.key(Input.MOVE_BACK);
        if (blockTicks++ == 0) hands.blockStarted();
        return true;
    }

    /** A mace diver still coming that we cannot wind-counter: raise the shield and keep it up until the dive lands. */
    PathingCommand diveCover(Player me, LivingEntity target, double dist, boolean hasShield) {
        // a diver still coming (no charge, or too close to counter): block the smash with the shield
        // 1822 fight ticks 505-508: block, jump, block as the diver came inside one block of our height,
        // and a shield needs five unbroken ticks. Once a dive is seen the shield stays up until it lands.
        // 000432 ticks 443-452: the diver topped out nine blocks up at 443, passed -0.3 at 448 and landed its mace
        // at 452, on the shield's fifth tick. From three blocks up the turn at the top is already the dive.
        // the smoothed position velocity still reads the climb for four ticks after the diver has turned over: its own
        // reported velocity is already negative at the top, and a shield needs five ticks before the smash
        double diveVy = Math.min(targeting.velocity(target).y, target.getDeltaMovement().y);
        boolean dive = !target.onGround() && dist < 11 && (diveVy < -0.3 && target.getY() > me.getY() + 1 || diveVy < 0.1 && target.getY() > me.getY() + 3);
        if (dive) phase.diveBlock = true;
        else if (target.onGround() || dist > 11) phase.diveBlock = false;
        if (phase.macePhase == 0 && (dive || phase.diveBlock && hasShield)
                && (me.getOffhandItem().getItem() == Items.SHIELD || inv.slotOf(me, Items.SHIELD) >= 0)) {
            if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
            hands.look(target.getEyePosition());
            hands.use(true);
            return hands.decide("block");
        }
        return null;
    }
}
