package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatInventory.*;
import static baritone.process.CombatGeometry.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Melee PvP. Far away we path to the target; inside {@link #DRIVE} we pause pathing and steer
 * ourselves: crit chaining (jump, drop sprint near the apex, hit falling), sprint hits with a
 * W-tap, hit select (no swings into hurt immunity), jump resets on knockback, random strafing,
 * axe against a raised shield, shield against drawn bows and incoming arrows, a bow at range,
 * golden apples and a totem in the offhand when low.
 */
public final class PvpProcess extends BaritoneProcessHelper {

    private static final double SPEAR_REACH = 4.0, SPEAR_MIN = 2.0, DRIVE = 7, BOW_MIN = 10, CHASE = 48;

    private Predicate<LivingEntity> filter;
    /** Players marked as enemies (freecam middle-click, or attacking us while freecam is on); cleared on death or a non-pearl teleport. */
    private final java.util.Set<java.util.UUID> enemies = new java.util.LinkedHashSet<>();
    private Player enemiesOwner;
    private net.minecraft.world.level.Level enemiesLevel;
    private net.minecraft.world.phys.Vec3 enemiesPos;
    private int pearlGrace;
    private String label;
    private LivingEntity target;
    private final Random rng = new Random(7);
    private boolean chase;
    private int groundedJumps;
    /** The hit being watched. */
    private int probeTick, probeSince;
    private boolean critArmed;
    private char clickKind = '-';
    private int duelOpenUntil, lastSeenTick;
    private float lastHealth = -1;
    private final PvpRecorder recorder = new PvpRecorder();
    /** Short action token written to the PvP log this tick; set by {@link #decide}. */
    private String tickDec = "-";

    public int attacks, crits, sprintHits, axeHits, blocks, gapples;
    public float damageTaken;

    public PvpProcess(Baritone baritone) {
        super(baritone);
    }

    public void attack(Predicate<LivingEntity> filter, String label) {
        this.filter = filter;
        this.label = label;
        target = null;
        lastHealth = -1;
        attacks = crits = sprintHits = axeHits = blocks = gapples = 0;
        damageTaken = 0;
    }

    public void attackPlayer(String name) {
        attack(e -> e instanceof Player && e.getName().getString().equalsIgnoreCase(name), name);
    }

    public void attackPlayers() {
        attack(e -> e instanceof Player, "players");
    }

    public void attackHostiles() {
        attack(e -> e instanceof Enemy, "hostiles");
    }

    private boolean matches(LivingEntity e) {
        return enemies.contains(e.getUUID()) || (filter != null && filter.test(e));
    }

    public boolean addEnemy(LivingEntity p) {
        Player me = ctx.player();
        if (me == null || p == null || p == me) return false;
        boolean added = enemies.add(p.getUUID());
        if (added) {
            enemiesOwner = me;
            enemiesLevel = me.level();
            enemiesPos = me.position();
            if (filter == null) label = "enemies";
        }
        return added;
    }

    public void clearEnemies() {
        enemies.clear();
    }

    public int enemyCount() {
        return enemies.size();
    }

    /** Drops the enemy list when we die/respawn or are teleported (a pearl of ours doesn't count). */
    private void checkEnemyReset(Player me) {
        if (enemies.isEmpty()) return;
        if (phase.pearlStage > 0 || me.getMainHandItem().is(Items.ENDER_PEARL)) pearlGrace = 60;
        else if (pearlGrace > 0) pearlGrace--;
        boolean reset = me != enemiesOwner || !me.isAlive() || me.level() != enemiesLevel
            || (pearlGrace == 0 && enemiesPos != null && me.position().distanceToSqr(enemiesPos) > 100);
        enemiesPos = me.position();
        if (reset) {
            enemies.clear();
            if (filter == null) target = null;
        }
    }

    public LivingEntity getTarget() {
        return target;
    }

    public String stats() {
        return String.format("attacks=%d crits=%d sprintHits=%d axeHits=%d blocks=%d gapples=%d dmgTaken=%.1f",
                attacks, crits, sprintHits, axeHits, blocks, gapples, damageTaken);
    }

    @Override
    public boolean isActive() {
        return (filter != null || !enemies.isEmpty()) && ctx.player() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Player me = ctx.player();
        checkEnemyReset(me);
        if (filter == null && enemies.isEmpty()) {
            recorder.end(me, "lost");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) damageTaken += lastHealth - hp;
        boolean respawned = lastHealth >= 0 && lastHealth < 5 && hp > lastHealth + 8;
        if (respawned) inv.resetBreaks();
        lastHealth = hp;
        // Bench respawn drops the kit before VexBench's item replace lands. Do not swing naked.
        if (Integer.getInteger("ostinato.vexbench", 0) > 0 && (respawned
                || me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty())) {
            if (recorder.active()) recorder.end(me, respawned ? "death" : "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        LivingEntity prevTarget = target;
        if (target == null || !target.isAlive() || target.isRemoved() || me.distanceTo(target) > CHASE) target = targeting.pick(me, CHASE);
        if (target != prevTarget) {
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
        if (target != null && me.tickCount % 5 == 0 && phase.macePhase == 0) target = targeting.retarget(me, target);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (target == null) {
            if (prevTarget != null && prevTarget.isDeadOrDying()) recorder.markWin();
            recorder.end(me, "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        survival.keepTotem(me);
        targeting.track(target);
        if (!recorder.active()) recorder.begin(me, target, label);
        tickDec = "-";
        brokeNote = inv.noteBreaks(me);
        if (!brokeNote.isEmpty()) {
            shield.blockTicks = 0;
            shield.lastShieldTick = -1000;
            use(false);
        }
        try {
            // 230839 axe hard safe: it ate six apples with no use flag on the client while we stood at 6 HP with
            // eight of our own, "pressed" by an opponent holding food. A main hand full of food is not swinging a weapon.
            boolean targetEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD)
                    || target.getMainHandItem().has(net.minecraft.core.component.DataComponents.FOOD);
            // 210415 medium defensive: it backed off at 3.9 HP and ate for 80 ticks while every hop
            // dropped the sprint just outside reach. 211205 safe: at 1 HP it ate for 500 ticks while
            // the strafe ran parallel to its retreat. A target eating or walking away is run down
            // in a straight line and hit on charge, no crit setup until it is in reach.
            Vec3 gap = target.position().subtract(me.position());
            chase = targetEating || gap.horizontalDistance() > 0.5
                    && (tv().x * gap.x + tv().z * gap.z) / gap.horizontalDistance() > 0.1;
            // Horizontal only. A mace hop makes the 3D gap > 4.5 while they are about to land.
            boolean overhead = !target.onGround() && target.getY() > me.getY() + 1.0;
            boolean safe = horizontalBoxDist(me, target) > 4.5 && target.onGround() && !target.swinging || targetEating;
            // Eating through their dive is the D9 in the logs. Drop the apple and shield it.
            // 0650 bench: 720 eat ticks, 282 right after spear_back. Killing blows were on eat
            // or spear_back, never on spear_charge. A charge holds the use key on the spear, so
            // only a charge in progress blocks a bite, and at HP <= 5 the charge is dropped for
            // the apple. Blocking every bite within 14 blocks left a spear kit unable to heal.
            boolean charging = inv.spearSlot(me) >= 0 && spearUseTicks > 0;
            // 211909 medium balanced: four apples started inside its sword reach, each dropped
            // when its crit jump read as overhead, each bite costing a 6. 212743 hard aggressive: a
            // bite started at 4.9 took two more. It covers 9 blocks in the 32 ticks. Only a real dive (2 up)
            // stops a bite, and with a shield in hand a bite does not start inside its reach.
            boolean pressed = !targetEating && eyeToBox(me, target) < 9 && target.getMainHandItem().getItem() != Items.MACE
                    && (me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem()) || inv.slotOf(me, Items.SHIELD) >= 0);
            // 232733 axe expert safe: 76 ticks at 2.9 HP behind a shield with eight apples, taking 35 damage all fight
            // while it ate its way back to 20 four times. A slow weapon that has just swung cannot swing again
            // before most of a bite is down: that is the opening, shield or no shield.
            int sinceSwing = me.tickCount - shield.targetSwingTick;
            boolean opening = sinceSwing >= 1 && sinceSwing <= 5 && (shield.swingGap > 0 ? shield.swingGap : target instanceof Player tp ? tp.getCurrentItemAttackStrengthDelay() : 20) >= 16
                    && !(target instanceof Player hp2 && hp2.getCurrentItemAttackStrengthDelay() < 16); // a foe that swapped back to a sword has no slow swing to wait out
            pressed &= !opening;
            // two critical sword hits (4.52 each) take 9.04: the line to eat at, when the foe gives room, is two hits, not one
            // far from the foe the bite is cheap, so top up earlier: a bite that starts at 9 with the foe in reach is a coin flip
            boolean roomy = eyeToBox(me, target) > 6;
            boolean critical = me.getHealth() <= (roomy ? 12 : 9) && (survival.eatTicks > 0 || !pressed);
            boolean longFall = !me.onGround() && me.fallDistance > 3; // a long fall is the whole problem: no time to eat through it
            if (survival.eatTicks > 0 && (overhead && target.getY() > me.getY() + 2.0 || longFall)) {
                use(false);
                survival.eatTicks = 0;
            } else if (!longFall && !(survival.eatTicks == 0 && overhead && target.getY() > me.getY() + 2.0) // cancelling a bite for a dive and restarting it next tick flickered the shield (it needs ~5 steady ticks)
                && !explosives.blastThreat(me) && (!charging || critical) && (survival.eatTicks > 0 || (critical || me.getHealth() <= 11 && (safe || opening) && !pressed || explosives.fighting() && me.getAbsorptionAmount() == 0 && me.getHealth() <= (inv.slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? 12 : 16)) && (!me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) || me.getHealth() <= 8) // regen is too slow to trust when one hit finishes us
                    && (inv.slotOf(me, Items.GOLDEN_APPLE) >= 0 || inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0))) {
                if (charging) {
                    use(false);
                    spearUseTicks = 0;
                    spearReleaseNext = false;
                    spearCommit = false;
                }
                if (survival.eat(me, target)) return decide("eat");
            }

            double dist = eyeToBox(me, target);
            boolean los = me.hasLineOfSight(target);

            if (phase.pearlCool > 0) phase.pearlCool--;
            tools.coolFire();
            if (spearCool > 0) spearCool--;
            if (hp <= 6 && !survival.canHeal(me) && target.getHealth() + target.getAbsorptionAmount() > 6 && !targetEating) {
                return decide("flee", survival.flee(me, target, dist));
            }

            // A spear that raises its shield here never steps into the 2-4 jab band.
            // 1654 bench: a mace's 33-tick cooldown kept the shield up for 405 of 1800 ticks, and
            // the hop that makes the damage starts from the ground. A mace with wind charges hops.
            boolean maceHop = inv.slotOf(me, Items.MACE) >= 0 && inv.slotOf(me, Items.WIND_CHARGE) >= 0;
            if (me.tickCount < lastSeenTick) { // respawned between bench rounds: tickCount restarted under the old stamps
                duelOpenUntil = shield.targetSwingTick = 0;
                shield.lastShieldTick = shield.lastAxeTick = -1000;
                shield.axeHeld = shield.flicked = false;
                shield.swingGap = shield.unseenBlock = probeTick = 0;
            }
            // VexBot answers a raised shield with an axe flick inside three ticks: once it has, the shield is only bait
            if (target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && me.getOffhandItem().getItem() == Items.SHIELD
                    && me.getCooldowns().isOnCooldown(me.getOffhandItem())) shield.flicked = true;
            lastSeenTick = me.tickCount;
            if (target.swinging && target.swingTime == 0) { // before the block: a held shield returns early
                int sinceLast = me.tickCount - shield.targetSwingTick;
                if (sinceLast >= 6 && sinceLast <= 40) shield.swingGap = sinceLast;
                shield.targetSwingTick = me.tickCount;
            }
            // a click that connected and left it unhurt met a shield the client was never shown:
            // remember how long after its swing that was and keep the sword out of that window
            if (probeTick > 0 && me.tickCount - probeTick >= 3) {
                if (target.hurtTime == 0 && target.getOffhandItem().getItem() == Items.SHIELD && probeSince < 20)
                    shield.unseenBlock = Math.max(shield.unseenBlock, probeSince + 1);
                probeTick = 0;
            }
            boolean blockMelee = inv.spearSlot(me) < 0 && !maceHop && shield.meleeBlock(me, target, dist);
            // a fall that no smash is going to cushion (knocked high, or the target got away below) ends in fall damage:
            // wings and pitch do not reset the fall distance, a wind burst under the feet does
            int clutch = inv.slotOf(me, Items.WIND_CHARGE);
            if (clutch >= 0 && !me.onGround() && me.fallDistance > 12 && me.getDeltaMovement().y < -0.5 && groundGap(me) < 9
                    && exactReach(me, target) > REACH + 1.5 && !me.isInWater()) {
                if (!select(me, clutch)) return decide("swap");
                aimer.throwStraightDown(me);
                return decide("clutch");
            }
            if (phase.macePhase < 5 && me.isFallFlying() && me.getDeltaMovement().y < -0.4
                    && groundGap(me) < 4 + 12 * Math.min(1.0, -me.getDeltaMovement().y / 1.5)) {
                // the dive is over (missed, blocked, or called off) and the wings are still pointed at the ground
                aimer.aim(new Rotation(me.getYRot(), -25f), true);
                return decide("flare");
            }
            PathingCommand dive = shield.underDive(me, target, dist, los);
            if (dive != null) return dive;
            if (phase.pearlStage == 0 && (blockMelee || shouldBlock(me, dist))) {
                // the use key must not start a bow or food in the main hand: the shield only rises when the main hand has no use action
                // (062608 bow expert: blocked arrows at 10 blocks with the bow in hand, drew it instead, took 4.76 a shot)
                select(me, inv.weapon(me));
                if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
                look(target.getEyePosition());
                if (blockMelee && dist > 2.4) key(Input.MOVE_FORWARD); // stay where the answer to its swing still reaches
                use(true);
                if (shield.blockTicks++ == 0) blocks++;
                shield.lastShieldTick = me.tickCount;
                return decide("block");
            }
            if (phase.pearlStage == 0 && survival.eatTicks == 0 && phase.macePhase == 0 && defense.foeAimed(target, dist)) {
                select(me, inv.weapon(me));
                look(target.getEyePosition());
                use(false);
                movement.dodgeRanged(me);
                return decide("dodge");
            }
            if (shield.blockTicks > 0) {
                use(false);
                shield.blockTicks = 0;
            }

            // crystals and anchors reach further than a sword, and blowing them is also how we clear a wall of them
            if (explosives.crystal(me, target)) {
                if (dist <= DRIVE) movement.steer(me, target, dist, chase, critArmed);
                return decide("crystal");
            }
            PathingCommand sp = special(me, dist, los);
            if (sp != null) {
                if ("-".equals(tickDec)) tickDec = "special";
                return sp;
            }
            if (!los && dist <= 3) { // right there but walled off (a crawl gap under our feet, a hole): dig through
                BlockHitResult wall = ctx.world().clip(new net.minecraft.world.level.ClipContext(me.getEyePosition(), target.getEyePosition(),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
                // obsidian and anchors take minutes by hand: the bench sat 1800 ticks left-clicking one
                if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && ctx.world().getBlockState(wall.getBlockPos()).getDestroySpeed(ctx.world(), wall.getBlockPos()) < 10) {
                    look(wall.getLocation());
                    key(Input.CLICK_LEFT); // hold the attack key on the wall
                    return decide("dig");
                }
            }
            // 1907 bench: they fell 8 blocks off the platform and no path follows a drop that deep, so
            // 945 ticks went to standing at the edge. Walk off after them; with a mace the fall is a dive.
            double drop = me.getY() - target.getY();
            if (drop > 3.5 && drop < 20 && horizontalBoxDist(me, target) < 8 && survival.eatTicks == 0
                    && (inv.slotOf(me, Items.MACE) >= 0 || me.getHealth() > drop + 4)) {
                use(false);
                int mace = inv.slotOf(me, Items.MACE);
                select(me, mace >= 0 ? mace : inv.weapon(me));
                look(target.getEyePosition());
                key(Input.MOVE_FORWARD);
                key(Input.SPRINT);
                if (!me.onGround() && mace >= 0) {
                    phase.macePhase = 2;
                    phase.maceTicks = 5;
                }
                return decide("drop");
            }
            if (dist > DRIVE || !los) {
                // Charge needs a sprint runway. Baritone chase from 7 blocks never reaches 4.6 blocks/s
                // before the pierce window, so a plain spear closes that gap on foot.
                boolean spearRush = inv.spearSlot(me) >= 0 && los && dist < 14 && survival.eatTicks == 0 && spearUseCool == 0
                        && me.getFoodData().getFoodLevel() > 6;
                if (!spearRush) {
                    if (los && dist > BOW_MIN && inv.slotOf(me, Items.BOW) >= 0 && inv.slotOf(me, Items.ARROW) >= 0) return decide("bow", bow(me));
                    use(false);
                    // Spear chase stops in the jab band, not inside the 2-block dead zone.
                    int near = inv.spearSlot(me) >= 0 ? 3 : 2;
                    return decide("chase", new PathingCommand(new GoalNear(target.blockPosition(), near), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
                }
            }
            // A spear charge is the use key. Releasing here every tick reset the 10-tick delay.
            if (me.isUsingItem() && spearUseTicks == 0) use(false);

            int spear = inv.spearSlot(me);
            // Spear jabs from farther than a sword; never jab inside SPEAR_MIN (vanilla spear dead zone).
            double reach = spear >= 0 ? SPEAR_REACH : REACH;
            double er = exactReach(me, target);
            // Movement uses horizontal distance to the hitbox. A mace hop makes the 3D eye distance
            // jump to ~5 while we are already in the jab band, which was the close/back oscillation.
            double hr = horizontalBoxDist(me, target);
            if (spearHrPrev >= 0) {
                double inst = spearHrPrev - hr;
                if (inst > -2 && inst < 2) spearClose = spearClose * 0.5 + inst * 0.5;
            }
            spearHrPrev = hr;
            // Jab only in the middle of the 2-4 band. Outer edge (~4) misses; under SPEAR_MIN cannot connect.
            boolean inReach = spear >= 0
                    ? (hr >= SPEAR_JAB_LO && hr <= SPEAR_JAB_HI)
                    : (er <= reach - 0.05);
            // a shield being raised blocks before isBlocking() shows it; only swap in reach, since any swap drains the charge
            boolean shieldUp = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
            float cd = me.getAttackStrengthScale(0.5f);
            boolean meFalling = !me.onGround() && me.getDeltaMovement().y < -0.05;
            boolean targetFalling = !target.onGround() && tv().y < -0.05;
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
                use(false);
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
                    look(aim);
                    spearFaceTicks++;
                    if (aimer.aimedAt(me, aim, 25f) || spearFaceTicks > 3 && aimer.aimedAt(me, aim, 50f)) {
                        spearReopen = false;
                        spearReopenTicks = 0;
                        spearFacing = false;
                        spearFaceTicks = 0;
                        spearCommit = true;
                        spearCommitTicks = 0;
                        key(Input.MOVE_FORWARD);
                        if (me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
                        return decide("spear_run");
                    }
                    return decide("spear_back");
                } else {
                    Vec3 away = me.getEyePosition().scale(2).subtract(target.getEyePosition());
                    look(away);
                    key(Input.MOVE_FORWARD);
                    if (me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
                    return decide("spear_back");
                }
            }
            if (spear >= 0 && !spearCommit && spearUseTicks == 0 && spearBand == 0 && los && spearCharged && inReach && slide < 0.03 && target.hurtTime <= 0
                    && me.getAttackStrengthScale(0f) >= 0.99f) {
                if (!select(me, spear)) return decide("swap");
                Vec3 aim = aimPoint(me, target);
                look(aim);
                // 8 degrees is wider than a player hitbox at 3 blocks, so that gate clicked air.
                if (!swing.spearRayHits(me, target)) return decide("spear_aim");
                use(false); // a held charge would eat the jab click
                hit(me);
                return decide(meFalling ? "spear_fall" : targetFalling ? "spear_air" : "spear");
            }

            // a disabled shield stays "raised" for its 5s cooldown; don't keep throwing uncharged axe swings at it
            // only a shield past its 5-tick warm-up is disabled by the axe; a swing into the raise is a wasted cooldown
            // VexBot's shield blocks from its first tick (no warm-up), so the use flag is the shield
            boolean tgShield = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
            if (tgShield && me.tickCount - shield.lastAxeTick >= 4) shield.axeHeld = false; // it is back up: that swing did not disable it
            // the axe drew blood: there was no shield in its way, so none is on cooldown either
            if (shield.axeHeld && me.tickCount - shield.lastAxeTick <= 3 && target.hurtTime >= 8) shield.axeHeld = false;
            boolean duel = target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && target.getOffhandItem().getItem() == Items.SHIELD;
            // 224436 expert adaptive: swords at t17 and t32 did nothing against a target showing no use flag.
            // A blockhitter's shield goes up with its swing, flag or no flag; the window is learned from bounced hits.
            if (duel && shield.hiddenShield(me, target)) tgShield = true;
            boolean axeTime = tgShield && inReach && me.tickCount - shield.lastAxeTick > 8 && inv.best(me, AXES) >= 0;
            if (!select(me, axeTime ? inv.best(me, AXES) : inv.weapon(me))) return decide("swap");
            look(swing.swingPoint(me, target, tv()));

            boolean targetReady = me.tickCount - shield.targetSwingTick >= 10; // its sword is charged: whoever swings first wins the exchange
            // the tick the use key comes up, an attack click is still swallowed by the item in use
            boolean immune = target.hurtTime > 1 || me.tickCount - shield.lastShieldTick < (shield.counter ? 2 : 3)
                    || tgShield && inv.best(me, AXES) >= 0; // a sword into a raised shield is a wasted cooldown
            boolean falling = !me.onGround() && me.getDeltaMovement().y < -0.05;
            boolean canJump = me.onGround() && !me.isInWater() && !me.isInLava() && !me.onClimbable();
            boolean hurry = me.getCurrentItemAttackStrengthDelay() < 14;
            // 233514 axe expert adaptive: it ate from 4 HP back to 20 under plain 3.84s swung on the run, and an apple
            // is worth 8. A target chewing in reach is not going anywhere: it gets the crit like any other.
            boolean run = chase && (hurry || !targetEating);
            // Same fight, t544: jumped fully charged and hung in the air for the fall while its axe, due, landed first.
            // With its next swing due inside the jump, the charged swing goes now.
            int swingAge = me.tickCount - shield.targetSwingTick;
            boolean rushed = !hurry && !targetEating && cd >= 0.95f
                    && swingAge >= (shield.swingGap > 0 ? shield.swingGap : target instanceof Player tp ? (int) tp.getCurrentItemAttackStrengthDelay() : 20) - 6;

            // Plain spear: commit to closing or backing until settled inside 2-4, then hold and jab.
            // No sprint near the band, so one tick cannot cross it. Lunge only with the enchant.
            if (spear >= 0) {
                int lungeLvl = inv.spearLungeLevel(me.getInventory().getItem(spear));
                double lungeMax = 6.0 + lungeLvl * 5.0; // L1 ~11, L2 ~16, L3 ~21
                // Jab is 4 raw (0.96 through diamond). Charge is KineticWeaponComponent.usageTick
                // while use is held, after a 10-tick delay. Damage needs look.dot(movement)*20 >= 4.6.
                // Hold use on the approach so the delay ends inside the 2-4.5 pierce window, then release.
                if (spearUseCool > 0) spearUseCool--;
                if (lungeLvl < 1 && los && survival.eatTicks == 0 && me.getFoodData().getFoodLevel() > 6) {
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
                            use(false);
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
                            if (!select(me, spear)) return decide("swap");
                            look(aimPoint(me, target));
                            key(Input.MOVE_FORWARD);
                            key(Input.SPRINT);
                            use(true);
                            spearUseTicks++;
                            if (pierce) spearReleaseNext = true;
                            return decide("spear_charge");
                        }
                    } else if (spearUseCool == 0 && along >= 4.6 && hr > 4.55 && hr < 14 && at10 <= 4.4 && at10 >= 2.4) {
                        if (!select(me, spear)) return decide("swap");
                        look(aimPoint(me, target));
                        key(Input.MOVE_FORWARD);
                        key(Input.SPRINT);
                        use(true);
                        spearUseTicks = 1;
                        spearReleaseNext = false;
                        return decide("spear_charge");
                    } else if (spearUseCool == 0 && hr > 4.6 && hr < 14 && (along < 4.6 || at10 > 4.4)) {
                        if (!select(me, spear)) return decide("swap");
                        look(aimPoint(me, target));
                        key(Input.MOVE_FORWARD);
                        key(Input.SPRINT);
                        use(false);
                        return decide("spear_run");
                    }
                }
                if (spearUseTicks == 0) use(false);
                if (lungeLvl >= 1 && spearCool == 0 && !me.onGround() && los
                        && dist > SPEAR_REACH && dist < lungeMax && me.getFoodData().getFoodLevel() >= 7) {
                    if (!select(me, spear)) return decide("swap");
                    look(aimPoint(me, target));
                    hit(me);
                    spearCool = 45 - lungeLvl * 5;
                    return decide("lunge");
                }
                if (spearBand < 0) {
                    look(aimPoint(me, target));
                    key(Input.MOVE_BACK);
                    return decide("spear_back");
                }
                if (spearBand > 0) {
                    if (!select(me, spear)) return decide("swap");
                    look(aimPoint(me, target));
                    key(Input.MOVE_FORWARD);
                    if (er > 5.2 && me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
                    return decide("spear_close");
                }
                return decide("spear_hold"); // inside the band: no step in or out this tick
            }
            if (!inReach) movement.steer(me, target, dist, chase, critArmed);
            else if (dist < 1.4) key(Input.MOVE_BACK); // sword: too close, make space
            // its reach is measured centre to centre, a little shorter than ours: recharge just outside it
            else if (duel && cd < 0.75f && dist < 2.9) key(Input.MOVE_BACK);
            if (me.hurtTime == me.hurtDuration - 1 && canJump) key(Input.JUMP); // jump reset

            if (axeTime && inReach && me.tickCount - shield.lastShieldTick >= 2) { // an axe disables a raised shield whatever the charge
                if (hit(me)) { // only a click that connected starts the wait for its shield
                    axeHits++;
                    shield.lastAxeTick = me.tickCount;
                    shield.axeHeld = true;
                }
                return decide("axe");
            }
            // Only in the first two ticks of a jump. A sword jumped at 0.55 is charged five ticks
            // later, still rising, and this swing took the crit that was two ticks away.
            if (!me.onGround() && (me.getDeltaMovement().y > 0.25 && targetReady || !falling && rushed) && inReach && cd >= 0.95f && !immune) {
                hit(me); // don't hang in the air waiting for a crit while it swings first
                critArmed = false;
                return decide("hit");
            }
            // 1740 fight tick 334: swung on the first tick with y speed below zero, before the move
            // that makes fallDistance positive. Vanilla wants fallDistance > 0, so it was a plain 1.56.
            if (critArmed && falling && me.fallDistance > 0 && inReach && cd >= 0.9f && !immune) {
                hit(me);
                crits++;
                critArmed = false;
                return decide("hit");
            }
            if ((run || shield.counter) && inReach && cd >= 0.9f && !immune) {
                hit(me);
                return decide("hit");
            }
            if (!me.onGround() && me.getDeltaMovement().y < 0.08 && dist <= REACH + 0.6 && cd >= 0.5f && (inReach || !chase)) {
                movement.wtap = Math.max(movement.wtap, 1); // let go of forward for a tick so the sprint drops: a sprinting hit is never a crit
                critArmed = true;
            }
            if (!me.onGround() && !critArmed && me.getDeltaMovement().y < 0.08 && inReach && cd >= 0.95f && !immune) {
                hit(me); // knocked airborne without a crit set up: don't waste the cooldown
                return decide("hit");
            }
            if (me.onGround()) critArmed = false;
            else groundedJumps = 0;

            boolean breached = shield.axeHeld && me.tickCount - shield.lastAxeTick < 100;
            // 232146 axe expert adaptive: 47 ground hits of 3.65 into an opponent eating apples, two jumps all fight.
            // A slow weapon is charged by the time the jump falls, so the crit costs nothing: only a sword hurries.
            if (breached && hurry && inReach && cd >= 0.95f && !immune) {
                hit(me); // its shield is on cooldown: land the follow-up as soon as the sword is charged
                return decide("hit");
            }
            boolean diving = !target.onGround() && tv().y < -0.2 && target.getY() > me.getY() + 1.5;
            // The fall starts six ticks after the jump. A mace or axe jumped at 0.55 landed before it was charged.
            float jumpCd = Math.max(0.55f, 1 - 6f / me.getCurrentItemAttackStrengthDelay());
            if (!(breached && hurry) && !duel && !diving && !run && !shield.counter && !(rushed && inReach) && canJump && dist <= REACH + 0.8 && cd >= jumpCd && !immune && groundedJumps < 4) {
                key(Input.JUMP);
                groundedJumps++;
                return decide("jump");
            }
            if (me.onGround() && inReach && cd >= (duel ? 0.9f : 0.95f) && !immune && (duel || !canJump || groundedJumps >= 4 || rushed)) {
                boolean sprint = me.isSprinting();
                hit(me);
                if (sprint) {
                    sprintHits++;
                    movement.wtap = 2;
                }
                groundedJumps = 0;
                return decide("hit");
            }
            return decide("strafe");
        } finally {
            recorder.tick(me, target, eyeToBox(me, target),
                    targeting.others(me, target) + " m" + phase.macePhase + " p" + phase.pearlStage + " f" + survival.fleeTicks + " e" + survival.eatTicks + " s" + me.getInventory().getSelectedSlot()
                            + (ctx.minecraft().screen != null ? " scr=" + ctx.minecraft().screen.getClass().getSimpleName() : "")
                            + (me.getCooldowns().isOnCooldown(me.getOffhandItem()) ? " offcd" : "")
                            + (ctx.minecraft().options.keyUse.isDown() ? " use" : "") + " k" + clickKind,
                    tickDec + brokeNote, attacks);
            clickKind = '-';
        }
    }

    private final CombatTargeting targeting = new CombatTargeting(ctx, this::matches);
    private final CombatInventory inv = new CombatInventory(ctx);
    private final CombatDefense defense = new CombatDefense(ctx, inv);
    private final CombatAim aimer = new CombatAim(baritone, ctx, rng);
    private final CombatMovement movement = new CombatMovement(ctx, rng, PvpProcess.this::key);
    private final CombatExplosives explosives = new CombatExplosives(ctx, inv, aimer, new CombatExplosives.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public boolean hit(Player me, Entity e) { return PvpProcess.this.hit(me, e); }
        public void key(Input in) { PvpProcess.this.key(in); }
    });
    private final CombatPhase phase = new CombatPhase();
    private final CombatSwing swing = new CombatSwing();
    private final CombatTools tools = new CombatTools(ctx, inv, aimer, targeting, explosives, new CombatTools.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public PathingCommand decide(String d, PathingCommand cmd) { return PvpProcess.this.decide(d, cmd); }
        public PathingCommand bow(Player me) { return PvpProcess.this.bow(me); }
        public void attacked() { attacks++; }
    });
    private final CombatSurvival survival = new CombatSurvival(ctx, inv, aimer, explosives, phase, new CombatSurvival.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public PathingCommand decide(String d, PathingCommand cmd) { return PvpProcess.this.decide(d, cmd); }
        public void gappleEaten() { gapples++; }
    });
    private final CombatShield shield = new CombatShield(ctx, inv, aimer, targeting, defense, phase, new CombatShield.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int eatTicks() { return survival.eatTicks; }
        public void blockStarted() { blocks++; }
    });
    private final CombatPearl pearls = new CombatPearl(ctx, inv, aimer, targeting, phase, new CombatPearl.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
    });
    private final CombatElytra elytra = new CombatElytra(ctx, inv, aimer, targeting, defense, phase, new CombatElytra.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public boolean hit(Player me) { return PvpProcess.this.hit(me); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int lastShield() { return shield.lastShieldTick; }
        public void shielded(int tick) { shield.lastShieldTick = tick; }
    });
    private final CombatMace maces = new CombatMace(ctx, inv, aimer, targeting, phase, new CombatMace.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public boolean hit(Player me) { return PvpProcess.this.hit(me); }
        public boolean shieldDive(Player me, double dist) { return shield.shieldDive(me, target, dist); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int lastAxe() { return shield.lastAxeTick; }
        public void axeHit(int tick) { axeHits++; shield.lastAxeTick = tick; }
        public int spearCool() { return spearCool; }
        public void spearCool(int ticks) { spearCool = ticks; }
    });
    private final CombatWind winds = new CombatWind(ctx, aimer, targeting, phase, new CombatWind.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
    });
    private Vec3 tv() {
        return targeting.velocity(target);
    }

    private int spearCool, spearBand;
    /** Ticks the spear use-key has been held this pass, and ticks to wait before another pass. */
    private int spearUseTicks, spearUseCool;
    private double spearHrPrev = -1, spearClose;
    private boolean spearReleaseNext, spearReopen, spearFacing, spearCommit;
    private int spearReopenTicks, spearFaceTicks, spearCommitTicks, spearJabWait;

    /** Mace, crossbow and trident play; null when the kit has none of them or they don't apply right now. */

    private PathingCommand special(Player me, double dist, boolean los) {
        if (phase.maceCool > 0) phase.maceCool--;
        int mace = inv.slotOf(me, Items.MACE), wind = inv.slotOf(me, Items.WIND_CHARGE);
        if (phase.windCool > 0) phase.windCool--;
        boolean overhead = !target.onGround() && target.getY() > me.getY() + 3;
        // Spear kit still jabs. Wind is only a knock-in just outside the band, or the hop under a mace smash.
        // Far wind-charge spam and shield-holding stay off. A plain spear never lunges.
        if (inv.spearSlot(me) >= 0 && phase.macePhase == 0 && phase.pearlStage == 0) {
            // Their mace dive is the ~9 damage in the spear logs. Shield it; do not hop into it.
            // Melee and the mace smash landed on spear_back, never on spear_charge.
            // Shielding mid-charge swaps off the spear. Dive-block only while use is not held.
            if (spearUseTicks == 0 && shield.shieldDive(me, target, dist)) return decide("block");
            // Do not hop in the middle of a charge approach. The hop was the whole fight and use never started.
            boolean foeEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
            PathingCommand tool = spearUseTicks == 0 && !spearReopen && !spearCommit && !foeEating && !(spearUseCool == 0 && horizontalBoxDist(me, target) > 4.6)
                    ? maces.spearTools(me, target, los, wind, mace) : null;
            if (tool != null) return tool;
            return null;
        }
        PathingCommand deflected = winds.deflect(me, target, wind);
        if (deflected != null) return deflected;
        boolean hasShield = me.getOffhandItem().getItem() == Items.SHIELD || inv.slotOf(me, Items.SHIELD) >= 0;
        PathingCommand dived = winds.diver(me, target, dist, wind, hasShield);
        if (dived != null) return dived;
        PathingCommand covered = shield.diveCover(me, target, dist, hasShield);
        if (covered != null) return covered;
        PathingCommand feet = winds.feet(me, target, dist, los, wind);
        if (feet != null) return feet;
        int pearlSlot = inv.slotOf(me, Items.ENDER_PEARL);
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        PathingCommand dropped = maces.drop(me, target, los, mace, wind);
        if (dropped != null) return dropped;
        PathingCommand stranded = pearls.strand(me, target, pearlSlot, myHp, survival.eatTicks);
        if (stranded != null) return stranded;
        PathingCommand lifted = pearls.lift(me, target, dist, los, overhead, mace, wind, pearlSlot, myHp);
        if (pearls.handled) return lifted;
        PathingCommand struck = pearls.strike(me, target, dist, los, overhead, mace, wind);
        if (pearls.handled) return struck;
        PathingCommand winged = elytra.run(me, target, dist, los, overhead, mace, wind);
        if (winged != null) return winged;
        if (mace >= 0) return maces.run(me, target, dist, los, overhead, mace, wind);
        return tools.run(me, target, dist, los);
    }

    private double groundY = Double.NaN;

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /** Record the action chosen this tick for {@link PvpRecorder}, then pause pathing. */
    private PathingCommand decide(String d) {
        tickDec = d;
        ledgeGuard();
        return pause();
    }

    /** True when the column at {@code at} has something to land on within a survivable fall of the last floor we stood on. */
    private boolean floorUnder(Player me, Vec3 at) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = Mth.floor(at.x), z = Mth.floor(at.z);
        for (int y = Mth.floor(me.getY()); y >= Mth.floor(groundY) - 5; y--) {
            p.set(x, y, z);
            if (!ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty()) return true;
        }
        return false;
    }

    /**
     * A strafe and a hop off the rim of a raised floor was a 29 block fall and the whole health bar.
     * When the keys and the momentum of this tick carry us over a drop the target is not down, steer back in.
     */
    private void ledgeGuard() {
        Player me = ctx.player();
        if (me == null || target == null) return;
        if (me.onGround()) groundY = me.getY();
        if (Double.isNaN(groundY)) return;
        if (me.isFallFlying() || me.isInWater() || target.getY() < groundY - 3) return;
        baritone.api.utils.IInputOverrideHandler in = baritone.getInputOverrideHandler();
        double f = (in.isInputForcedDown(Input.MOVE_FORWARD) ? 1 : 0) - (in.isInputForcedDown(Input.MOVE_BACK) ? 1 : 0);
        double s = (in.isInputForcedDown(Input.MOVE_LEFT) ? 1 : 0) - (in.isInputForcedDown(Input.MOVE_RIGHT) ? 1 : 0);
        Vec3 want = new Vec3(s, 0, f).yRot(-me.getYRot() * Mth.DEG_TO_RAD);
        if (want.lengthSqr() > 0) want = want.normalize();
        Vec3 h = me.getDeltaMovement().multiply(4, 0, 4).add(want.scale(1.1));
        if (h.lengthSqr() < 0.01) return;
        if (floorUnder(me, me.position().add(h.scale(0.5))) && floorUnder(me, me.position().add(h))) return;
        Vec3 to = target.position().subtract(me.position()).multiply(1, 0, 1);
        if (to.lengthSqr() < 0.01 || !floorUnder(me, me.position().add(to.normalize().scale(1.5))) || to.dot(h) > 0 && !floorUnder(me, me.position())) {
            to = h.scale(-1);
        }
        in.setInputForceState(Input.MOVE_FORWARD, false);
        in.setInputForceState(Input.MOVE_BACK, false);
        in.setInputForceState(Input.MOVE_LEFT, false);
        in.setInputForceState(Input.MOVE_RIGHT, false);
        float rel = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - me.getYRot());
        if (Math.abs(rel) < 67.5f) key(Input.MOVE_FORWARD);
        else if (Math.abs(rel) > 112.5f) key(Input.MOVE_BACK);
        if (rel > 22.5f && rel < 157.5f) key(Input.MOVE_RIGHT);
        else if (rel < -22.5f && rel > -157.5f) key(Input.MOVE_LEFT);
        tickDec = tickDec + "+ledge";
    }

    /** Record the action chosen this tick for {@link PvpRecorder}, then return {@code cmd}. */
    private PathingCommand decide(String d, PathingCommand cmd) {
        tickDec = d;
        return cmd;
    }

    private boolean shouldBlock(Player me, double dist) {
        return defense.arrowIncoming(me) && blockWhy(3);
    }

    private String brokeNote = "";

    private int blockWhy;
    private boolean blockWhy(int w) { blockWhy = w; return true; }

    private PathingCommand bow(Player me) {
        if (!select(me, inv.slotOf(me, Items.BOW))) return decide("swap");
        if (me.getMainHandItem().getItem() != Items.BOW) return decide("swap");
        // lead: arrow ~3 b/t at full draw, gravity 0.05
        Vec3 at = arcAim(me.getEyePosition(), target.getBoundingBox().getCenter(), tv(), 3.0);
        look(at);
        if (me.isUsingItem() && me.getTicksUsingItem() >= 21) {
            use(false);
            attacks++;
        } else {
            use(true);
        }
        return decide("bow");
    }

    /**
     * Hard 06:45 held mace and returned swap for 1800 ticks: the hotbar key never landed,
     * so the round dealt 0. Set the slot as well as clicking the key.
     */
    private boolean select(Player me, int slot) {
        if (slot < 0) return false;
        if (me.getInventory().getSelectedSlot() != slot) {
            me.getInventory().setSelectedSlot(slot);
            press(ctx.minecraft().options.keyHotbarSlots[slot]);
        }
        return me.getInventory().getSelectedSlot() == slot;
    }

    /** Turn the view toward the angles with the smoothed look; true once it already points there within tol degrees. */
    /** Every PvP look goes out as a bounded, mouse-stepped move; see LookBehavior.human(). */
    /** Left-click only if the crosshair is on the entity, as the mouse button would. */
    private boolean hit(Player me, Entity e) {
        if (!(ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult er) || er.getEntity() != e) return false;
        press(ctx.minecraft().options.keyAttack);
        return true;
    }

    private boolean hit(Player me) {
        boolean spearAim = isSpear(me.getMainHandItem());
        Vec3 aim = spearAim ? aimPoint(me, target) : swing.swingPoint(me, target, tv());
        look(aim);
        double er = exactReach(me, target);
        boolean spear = isSpear(me.getMainHandItem());
        if (spear) {
            // Piercing jab raycasts along the look vector. A click that is merely near the eyes misses
            // and, below full charge, is rejected. Do not press attack unless this ray connects.
            if (!swing.spearRayHits(me, target)) return false;
            press(ctx.minecraft().options.keyAttack);
            attacks++;
            return true;
        }
        // 003156 mace medium: twelve dives came down on its head and none clicked. Falling 1.2 a tick past a target
        // a block away the bearing swings 40 degrees a tick, and the look is always one behind it. The crosshair
        // being on the entity is what a click needs; the angle only guards a swing on level ground.
        boolean onIt = me.fallDistance > 1.5 && ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult on && on.getEntity() == target;
        if (!onIt && !aimer.aimedAt(me, aim, 10f)) { clickKind = 'a'; return false; } // must be looking at the target
        if (hit(me, target)) {
            attacks++;
            clickKind = 'E';
            if (!me.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) {
                probeTick = me.tickCount;
                probeSince = me.tickCount - shield.targetSwingTick;
            }
            return true;
        }
        // 223541 expert adaptive t32: a click with the crosshair beside the hitbox swung at air and spent
        // the full charge. The click is handled this tick against the pick already made, so no entity, no click.
        clickKind = 'r';
        return false;
    }

    private void key(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    private void use(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down);
    }

    private void look(Vec3 at) {
        aimer.look(at, target == null ? 0 : tv().horizontalDistance());
    }

    @Override
    public void onLostControl() {
        recorder.end(ctx.player(), "lost");
        filter = null;
        enemies.clear();
        target = null;
        survival.eatTicks = shield.blockTicks = duelOpenUntil = shield.targetSwingTick = 0;
        shield.axeHeld = shield.flicked = false;
        shield.swingGap = shield.unseenBlock = probeTick = 0;
        tools.reset();
        inv.resetBreaks();
        shield.lastShieldTick = shield.lastAxeTick = -1000; // tickCount restarts with the respawned player
        if (ctx.minecraft().options != null) use(false);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "PvP " + label + (target == null ? "" : " -> " + target.getName().getString());
    }

    @Override
    public double priority() {
        return 2;
    }
}
