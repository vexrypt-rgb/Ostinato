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
    }

    int spearCool, spearBand;
    /** Ticks the spear use-key has been held this pass, and ticks to wait before another pass. */
    int spearUseTicks, spearUseCool;
    double spearHrPrev = -1, spearClose;
    boolean spearReleaseNext, spearReopen, spearFacing, spearCommit;
    int spearReopenTicks, spearFaceTicks, spearCommitTicks, spearJabWait;

    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatSwing swing;
    private final Hands hands;

    CombatSpear(CombatInventory inv, CombatAim aimer, CombatSwing swing, Hands hands) {
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
        // 05:32 easy: jabs with slide 0.04-0.06 missed; the one at slide 0.02 landed. Stay stopped.
        // 062727: one pierce, then jabs at 3 blocks and a mace hop. The 4 damage was healed.
        // Back to the charge runway before the next pass. Do not jab during that back-out.
        // 070413 never left the jab band (spear_back only to horiz 3.06, then a jab and a mace hop
        // at along 4.20). 070505's only charge started use at box-distance ~9.9, after along had
        // been >= 4.6 since ~12. The reopen handoff at hr 4.55 was still facing away: t52 along
        // 5.60 was the wrong direction (at10 under 2.4) and spear_run died by t56, along 3.29.
        // Sprint out to where that window can see a real approach, face them, then run. Do not
        // stop at 4.55 and do not dump into jabs at 8. Do not eat.
        // 074115: H4.29 stuck (14.00 to 9.71). They held a golden apple t61-t99 while we
        // sprinted away, and t100 finished it at 10.71 plus 4 absorption. Later hits ate the
        // absorption, not the kill. Do not back off while that eat is in progress.
        if (targetEating && spear >= 0 && spearUseTicks == 0) {
            spearReopen = false;
            spearFacing = false;
            spearFaceTicks = 0;
        }
        // Backing out whenever they were inside 4.8 returned before the jab below on every tick.
        // Take a ready jab first; its cooldown is then spent on the back-out for the next charge.
        // If the jab has not connected in 30 ticks, stop waiting and back out anyway.
        boolean jabReady = spearCharged && me.getAttackStrengthScale(0f) >= 0.99f;
        if (spear >= 0 && inv.spearLungeLevel(spearStack) < 1 && spearUseTicks == 0 && !spearCommit && !spearReopen && !targetEating && los && hr < 4.8
                && (!jabReady || ++spearJabWait > 30)) {
            spearReopen = true;
            spearJabWait = 0;
        }
        if (spearCommit && spearUseTicks == 0 && (++spearCommitTicks > 36 || hr < 3.2)) {
            spearCommit = false;
            spearCommitTicks = 0;
        }
        if (spear >= 0 && spearReopen) {
            if (spearUseCool > 0) spearUseCool--;
            hands.use(false);
            // 072935 full charge: use at hr 6.18 along 4.69, pierce tick t120 hr 2.98 along 5.64.
            // The run that made it started at hr 7.52. 8.3 is above the chase plateau, so the
            // reset kept going and the next charge was 92 ticks later. Face them at 7.4.
            if (hr >= 7.4) spearFacing = true;
            if (!los || ++spearReopenTicks > 70) {
                spearReopen = false;
                spearReopenTicks = 0;
                spearFacing = false;
                spearFaceTicks = 0;
            } else if (spearFacing) {
                Vec3 aim = aimPoint(me, target);
                hands.look(aim);
                spearFaceTicks++;
                if (aimer.aimedAt(me, aim, 25f) || spearFaceTicks > 3 && aimer.aimedAt(me, aim, 50f)) {
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
        if (spear >= 0 && !spearCommit && spearUseTicks == 0 && spearBand == 0 && los && spearCharged && inReach && slide < 0.03 && target.hurtTime <= 0
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
            // 062018 started use at 6.8 using only our 5.5 blocks/s. They were also closing
            // on us, so tick 10 was at 2.14, leaving the 2-4.5 window. Lead with the
            // observed distance drop. Do not release at tick 10 if still outside.
            double step = Math.max(along / 20.0, spearClose);
            double at10 = hr - step * 10.0;
            if (spearUseTicks > 0) {
                boolean pierce = spearUseTicks >= 11 && along >= 4.6 && hr > 2.05 && hr <= 4.5;
                boolean failed = along < 4.2 || hr <= 2.0 || hr > 16 || spearUseTicks >= 200;
                if (spearReleaseNext || failed) {
                    hands.use(false);
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
                    spearUseCool = 8;
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
            } else if (spearUseCool == 0 && along >= 4.6 && hr > 4.55 && hr < 14 && at10 <= 4.4 && at10 >= 2.4) {
                if (!hands.select(me, spear)) return hands.decide("swap");
                hands.look(aimPoint(me, target));
                hands.key(Input.MOVE_FORWARD);
                hands.key(Input.SPRINT);
                hands.use(true);
                spearUseTicks = 1;
                spearReleaseNext = false;
                return hands.decide("spear_charge");
            } else if (spearUseCool == 0 && hr > 4.6 && hr < 14 && (along < 4.6 || at10 > 4.4)) {
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
