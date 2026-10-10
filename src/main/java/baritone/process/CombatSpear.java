package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.input.Input;
import static baritone.process.CombatGeometry.*;

/**
 * The spear fight: hold the jab band, take a charged jab, back out and face for the next pass, run up and hold the
 * use key so the charge pierces, lunge with the enchant. All of its clocks live here; the process only asks it
 * for a move and reads whether a charge is in progress.
 */
final class CombatSpear {
    /** What the process does for us: hotbar, aim, keys, a swing, the tick's decision label and the eat clock. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        boolean hit(Player me);
        PathingCommand decide(String d);
        int eatTicks();
        /** A derived-feature line for the fight log. */
        void note(String line);
    }

    int spearCool, spearBand;
    /** Ticks the spear use-key has been held this pass, and ticks to wait before another pass. */
    int spearUseTicks, spearUseCool;
    double spearHrPrev = -1, spearClose;
    boolean spearReleaseNext, spearReopen, spearFacing, spearCommit;
    /** What the pass looked like when its charge began, for the release line. */
    private double passHr, passAlong, passClose, passAt10;
    int spearReopenTicks, spearFaceTicks, spearCommitTicks, spearJabWait;

    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatSwing swing;
    private final Hands hands;
    private final CombatPolicy.Spear p;

    CombatSpear(CombatInventory inv, CombatAim aimer, CombatSwing swing, CombatPolicy.Spear p, Hands hands) {
        this.p = p;
        this.inv = inv;
        this.aimer = aimer;
        this.swing = swing;
        this.hands = hands;
    }

    /** A new target: forget the pass in progress. */
    void retarget() {
        spearBand = 0;
        spearHrPrev = -1;
        spearReopen = false;
        spearReopenTicks = 0;
        spearJabWait = 0;
        spearFacing = false;
        spearFaceTicks = 0;
        spearCommit = false;
        spearCommitTicks = 0;
        spearUseTicks = 0;
        spearReleaseNext = false;
    }

    /** The charge is abandoned (eating): let go of the pass without starting a new one. */
    void abortCharge() {
        spearUseTicks = 0;
        spearReleaseNext = false;
        spearCommit = false;
    }

    /** Track how fast the gap is closing, and the hysteresis on which side of the jab band we are. */
    void observe(Player me, int spear, double hr) {
        if (spearHrPrev >= 0) {
            double inst = spearHrPrev - hr;
            if (inst > -2 && inst < 2) spearClose = spearClose * 0.5 + inst * 0.5;
        }
        spearHrPrev = hr;
        // Spear jab: only the connectable middle of the band (~2.6-3.4). Aim first, then swing.
        // Hysteresis is on horizontal distance so a jump does not flip close/back. SPEAR_MIN stays 2.
        // Plain spears never lunge. A jab is impossible below full charge (minimum_attack_charge = 1).
        if (spear >= 0) {
            if (spearBand > 0) {
                if (hr <= SPEAR_JAB_HI - 0.15) spearBand = 0; // walk in until ~3.25
            } else if (spearBand < 0) {
                if (hr >= SPEAR_JAB_LO + 0.15) spearBand = 0; // back out until ~2.75
            } else if (hr < SPEAR_JAB_LO - 0.2 || hr < SPEAR_MIN) {
                spearBand = -1; // too close for a connectable jab
            } else if (hr > SPEAR_JAB_HI + 0.2) {
                spearBand = 1; // outside the connectable band, not a 3.4-edge flicker
            }
        }
    }

    /** Back-out, reopen and the jab itself; null when none applies this tick. */
    PathingCommand jab(Player me, LivingEntity target, boolean los, int spear, double hr, boolean targetEating, boolean inReach, boolean meFalling, boolean targetFalling) {
        ItemStack spearStack = spear >= 0 ? me.getInventory().getItem(spear) : ItemStack.EMPTY;
        boolean spearCharged = spear >= 0 && !me.cannotAttackWithItem(spearStack, 0);
        double slide = Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z);
        // Jabs only land when nearly stopped (slide 0.04-0.06 missed, 0.02 landed).
        // After a jab, back out to the charge runway before the next pass; no jabbing during the back-out.
        // The reopen must carry on to where the charge window sees a real approach (past hr 4.55: handing
        // off there left us facing away), face them, then run. Do not stop early or dump into jabs at 8.
        // While they eat, stay in: backing off let a golden apple finish and absorb the later hits.
        if (targetEating && spear >= 0 && spearUseTicks == 0) {
            spearReopen = false;
            spearFacing = false;
            spearFaceTicks = 0;
        }
        // Backing out whenever they were inside 4.8 returned before the jab below on every tick.
        // Take a ready jab first; its cooldown is then spent on the back-out for the next charge.
        // If the jab has not connected in 30 ticks, stop waiting and back out anyway.
        boolean jabReady = spearCharged && me.getAttackStrengthScale(0f) >= 0.99f;
        if (spear >= 0 && inv.spearLungeLevel(spearStack) < 1 && spearUseTicks == 0 && !spearCommit && !spearReopen && !targetEating && los && hr < p.jabBand
                && (!jabReady || ++spearJabWait > p.jabWaitTicks)) {
            spearReopen = true;
            spearJabWait = 0;
        }
        if (spearCommit && spearUseTicks == 0 && (++spearCommitTicks > p.commitTicks || hr < p.commitHr)) {
            spearCommit = false;
            spearCommitTicks = 0;
        }
        if (spear >= 0 && spearReopen) {
            if (spearUseCool > 0) spearUseCool--;
            hands.use(false);
            // Face them at hr 7.4: a full charge started from about 7.5, and 8.3 is above the chase
            // plateau, so waiting for it kept the reset going and delayed the next charge by ~90 ticks.
            if (hr >= p.faceHr) spearFacing = true;
            if (!los || ++spearReopenTicks > p.reopenTicks) {
                spearReopen = false;
                spearReopenTicks = 0;
                spearFacing = false;
                spearFaceTicks = 0;
            } else if (spearFacing) {
                Vec3 aim = aimPoint(me, target);
                hands.look(aim);
                spearFaceTicks++;
                if (aimer.aimedAt(me, aim, p.faceTol) || spearFaceTicks > 3 && aimer.aimedAt(me, aim, p.faceTolLate)) {
                    spearReopen = false;
                    spearReopenTicks = 0;
                    spearFacing = false;
                    spearFaceTicks = 0;
                    spearCommit = true;
                    spearCommitTicks = 0;
                    hands.key(Input.MOVE_FORWARD);
                    if (me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
                    return hands.decide("spear_run");
                }
                return hands.decide("spear_back");
            } else {
                Vec3 away = me.getEyePosition().scale(2).subtract(target.getEyePosition());
                hands.look(away);
                hands.key(Input.MOVE_FORWARD);
                if (me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
                return hands.decide("spear_back");
            }
        }
        if (spear >= 0 && !spearCommit && spearUseTicks == 0 && spearBand == 0 && los && spearCharged && inReach && slide < p.jabSlide && target.hurtTime <= 0
                && me.getAttackStrengthScale(0f) >= 0.99f) {
            if (!hands.select(me, spear)) return hands.decide("swap");
            Vec3 aim = aimPoint(me, target);
            hands.look(aim);
            // 8 degrees is wider than a player hitbox at 3 blocks, so that gate clicked air.
            if (!swing.spearRayHits(me, target)) return hands.decide("spear_aim");
            hands.use(false); // a held charge would eat the jab click
            hands.hit(me);
            return hands.decide(meFalling ? "spear_fall" : targetFalling ? "spear_air" : "spear");
        }
        return null;
    }

    /** Plain-spear play once no jab or back-out applied: charge, run up, lunge, close, back or hold. Always decides. */
    PathingCommand pass(Player me, LivingEntity target, double dist, boolean los, int spear, double hr, double er, boolean targetEating) {
        int lungeLvl = inv.spearLungeLevel(me.getInventory().getItem(spear));
        double lungeMax = 6.0 + lungeLvl * 5.0; // L1 ~11, L2 ~16, L3 ~21
        // Jab is 4 raw (0.96 through diamond). Charge is KineticWeaponComponent.usageTick
        // while use is held, after a 10-tick delay. Damage needs look.dot(movement)*20 >= 4.6.
        // Hold use on the approach so the delay ends inside the 2-4.5 pierce window, then release.
        if (spearUseCool > 0) spearUseCool--;
        if (lungeLvl < 1 && los && hands.eatTicks() == 0 && me.getFoodData().getFoodLevel() > 6) {
            double along = swing.kineticAlong(me);
            // Lead with the observed distance drop, not just our own speed: a closing foe put tick 10
            // inside 2.1, outside the 2-4.5 window. Do not release at tick 10 if still outside.
            double step = Math.max(along / 20.0, spearClose);
            double at10 = hr - step * 10.0;
            if (spearUseTicks > 0) {
                boolean pierce = spearUseTicks >= p.minUseTicks && along >= p.chargeAlong && hr > p.pierceLo && hr <= p.pierceHi;
                boolean failed = along < p.failAlong || hr <= 2.0 || hr > p.failHr || spearUseTicks >= p.maxUseTicks;
                if (spearReleaseNext || failed) {
                    hands.use(false);
                    hands.note(String.format("spear rel n=%d hr=%.2f along=%.2f win=%s %s | start hr=%.2f along=%.2f close=%.3f at10=%.2f | tv=%.3f me=%.3f",
                            spearUseTicks, hr, along, hr > p.pierceLo && hr <= p.pierceHi ? "in" : "out", spearReleaseNext ? "pierce" : "fail",
                            passHr, passAlong, passClose, passAt10,
                            Math.hypot(target.getDeltaMovement().x, target.getDeltaMovement().z), Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z)));
                    if (spearReleaseNext) {
                        spearCommit = false;
                        spearFacing = false;
                        spearFaceTicks = 0;
                        if (!targetEating) {
                            spearReopen = true;
                            spearReopenTicks = 0;
                        }
                    }
                    spearUseTicks = 0;
                    spearReleaseNext = false;
                    spearUseCool = p.useCool;
                } else {
                    if (!hands.select(me, spear)) return hands.decide("swap");
                    hands.look(aimPoint(me, target));
                    hands.key(Input.MOVE_FORWARD);
                    hands.key(Input.SPRINT);
                    hands.use(true);
                    spearUseTicks++;
                    if (pierce) spearReleaseNext = true;
                    return hands.decide("spear_charge");
                }
            } else if (spearUseCool == 0 && along >= p.chargeAlong && hr > p.startHrMin && hr < p.startHrMax && at10 <= p.at10Hi && at10 >= p.at10Lo) {
                if (!hands.select(me, spear)) return hands.decide("swap");
                hands.look(aimPoint(me, target));
                hands.key(Input.MOVE_FORWARD);
                hands.key(Input.SPRINT);
                hands.use(true);
                spearUseTicks = 1;
                spearReleaseNext = false;
                passHr = hr;
                passAlong = along;
                passClose = spearClose;
                passAt10 = at10;
                return hands.decide("spear_charge");
            } else if (spearUseCool == 0 && hr > 4.6 && hr < p.startHrMax && (along < p.chargeAlong || at10 > p.at10Hi)) {
                if (!hands.select(me, spear)) return hands.decide("swap");
                hands.look(aimPoint(me, target));
                hands.key(Input.MOVE_FORWARD);
                hands.key(Input.SPRINT);
                hands.use(false);
                return hands.decide("spear_run");
            }
        }
        if (spearUseTicks == 0) hands.use(false);
        if (lungeLvl >= 1 && spearCool == 0 && !me.onGround() && los
                && dist > SPEAR_REACH && dist < lungeMax && me.getFoodData().getFoodLevel() >= 7) {
            if (!hands.select(me, spear)) return hands.decide("swap");
            hands.look(aimPoint(me, target));
            hands.hit(me);
            spearCool = 45 - lungeLvl * 5;
            return hands.decide("lunge");
        }
        if (spearBand < 0) {
            hands.look(aimPoint(me, target));
            hands.key(Input.MOVE_BACK);
            return hands.decide("spear_back");
        }
        if (spearBand > 0) {
            if (!hands.select(me, spear)) return hands.decide("swap");
            hands.look(aimPoint(me, target));
            hands.key(Input.MOVE_FORWARD);
            if (er > 5.2 && me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
            return hands.decide("spear_close");
        }
        return hands.decide("spear_hold"); // inside the band: no step in or out this tick
    }
}
