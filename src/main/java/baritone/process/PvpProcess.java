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

    private static final double REACH = 3.0, SPEAR_REACH = 4.0, SPEAR_MIN = 2.0, SPEAR_JAB_LO = 2.6, SPEAR_JAB_HI = 3.4, DRIVE = 7, BOW_MIN = 10, CHASE = 48;
    private static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};
    /** Plain spears (no enchant required). Order is best-first for best(). */
    private static final Item[] SPEARS = {Items.NETHERITE_SPEAR, Items.DIAMOND_SPEAR, Items.IRON_SPEAR, Items.COPPER_SPEAR, Items.GOLDEN_SPEAR, Items.STONE_SPEAR, Items.WOODEN_SPEAR};

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
    private int strafeDir = 1, strafeLeft, wtap, eatTicks, groundedJumps, blockTicks;
    private boolean crystalFight;
    private int backingOff;
    private int targetSwingTick, lastAxeTick = -1000;
    /** Learned in the fight: ticks between the opponent's swings, how long after a swing our hits bounce off it, and the hit being watched. */
    private int swingGap, unseenBlock, probeTick, probeSince;
    private boolean critArmed, counter, axeHeld, flicked;
    private char clickKind = '-';
    private int duelOpenUntil, lastShieldTick = -1000, lastSeenTick;
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
        if (pearlStage > 0 || me.getMainHandItem().is(Items.ENDER_PEARL)) pearlGrace = 60;
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
        lastHealth = hp;
        // Bench respawn drops the kit before VexBench's item replace lands. Do not swing naked.
        if (Integer.getInteger("ostinato.vexbench", 0) > 0 && (respawned
                || me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty())) {
            if (recorder.active()) recorder.end(me, respawned ? "death" : "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        LivingEntity prevTarget = target;
        if (target == null || !target.isAlive() || target.isRemoved() || me.distanceTo(target) > CHASE) target = pick(me);
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
        if (target != null && me.tickCount % 5 == 0 && macePhase == 0) retarget(me);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (target == null) {
            if (prevTarget != null && prevTarget.isDeadOrDying()) recorder.markWin();
            recorder.end(me, "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        keepTotem(me);
        trackTarget();
        if (!recorder.active()) recorder.begin(me, target, label);
        tickDec = "-";
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
            boolean charging = spearSlot(me) >= 0 && spearUseTicks > 0;
            // 211909 medium balanced: four apples started inside its sword reach, each dropped
            // when its crit jump read as overhead, each bite costing a 6. 212743 hard aggressive: a
            // bite started at 4.9 took two more. It covers 9 blocks in the 32 ticks. Only a real dive (2 up)
            // stops a bite, and with a shield in hand a bite does not start inside its reach.
            boolean pressed = !targetEating && eyeToBox(me, target) < 9 && target.getMainHandItem().getItem() != Items.MACE
                    && (me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem()) || slotOf(me, Items.SHIELD) >= 0);
            // 232733 axe expert safe: 76 ticks at 2.9 HP behind a shield with eight apples, taking 35 damage all fight
            // while it ate its way back to 20 four times. A slow weapon that has just swung cannot swing again
            // before most of a bite is down: that is the opening, shield or no shield.
            int sinceSwing = me.tickCount - targetSwingTick;
            boolean opening = sinceSwing >= 1 && sinceSwing <= 5 && (swingGap > 0 ? swingGap : target instanceof Player tp ? tp.getCurrentItemAttackStrengthDelay() : 20) >= 16;
            pressed &= !opening;
            boolean critical = me.getHealth() <= 5 && (eatTicks > 0 || !pressed);
            if (eatTicks > 0 && overhead && target.getY() > me.getY() + 2.0) {
                use(false);
                eatTicks = 0;
            } else if ((!charging || critical) && (eatTicks > 0 || (critical || me.getHealth() <= 11 && (safe || opening) && !pressed || crystalFight && me.getAbsorptionAmount() == 0 && me.getHealth() <= (slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? 19 : 16)) && !me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                    && (slotOf(me, Items.GOLDEN_APPLE) >= 0 || slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0))) {
                if (charging) {
                    use(false);
                    spearUseTicks = 0;
                    spearReleaseNext = false;
                    spearCommit = false;
                }
                if (eat(me)) return decide("eat");
            }

            double dist = eyeToBox(me, target);
            boolean los = me.hasLineOfSight(target);

            if (pearlCool > 0) pearlCool--;
            if (fireCool > 0) fireCool--;
            if (spearCool > 0) spearCool--;
            if (hp <= 6 && !canHeal(me) && target.getHealth() + target.getAbsorptionAmount() > 6 && !targetEating) {
                return decide("flee", flee(me, dist));
            }

            // A spear that raises its shield here never steps into the 2-4 jab band.
            // 1654 bench: a mace's 33-tick cooldown kept the shield up for 405 of 1800 ticks, and
            // the hop that makes the damage starts from the ground. A mace with wind charges hops.
            boolean maceHop = slotOf(me, Items.MACE) >= 0 && slotOf(me, Items.WIND_CHARGE) >= 0;
            if (me.tickCount < lastSeenTick) { // respawned between bench rounds: tickCount restarted under the old stamps
                duelOpenUntil = targetSwingTick = 0;
                lastShieldTick = lastAxeTick = -1000;
                axeHeld = flicked = false;
                swingGap = unseenBlock = probeTick = 0;
            }
            // VexBot answers a raised shield with an axe flick inside three ticks: once it has, the shield is only bait
            if (target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && me.getOffhandItem().getItem() == Items.SHIELD
                    && me.getCooldowns().isOnCooldown(me.getOffhandItem())) flicked = true;
            lastSeenTick = me.tickCount;
            if (target.swinging && target.swingTime == 0) { // before the block: a held shield returns early
                int sinceLast = me.tickCount - targetSwingTick;
                if (sinceLast >= 6 && sinceLast <= 40) swingGap = sinceLast;
                targetSwingTick = me.tickCount;
            }
            // a click that connected and left it unhurt met a shield the client was never shown:
            // remember how long after its swing that was and keep the sword out of that window
            if (probeTick > 0 && me.tickCount - probeTick >= 3) {
                if (target.hurtTime == 0 && target.getOffhandItem().getItem() == Items.SHIELD && probeSince < 20)
                    unseenBlock = Math.max(unseenBlock, probeSince + 1);
                probeTick = 0;
            }
            boolean blockMelee = spearSlot(me) < 0 && !maceHop && meleeBlock(me, dist);
            if (blockMelee || shouldBlock(me, dist)) {
                if (blockMelee) select(me, weapon(me)); // the use key must not start a bow or food in the main hand
                if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
                look(target.getEyePosition());
                if (blockMelee && dist > 2.4) key(Input.MOVE_FORWARD); // stay where the answer to its swing still reaches
                use(true);
                if (blockTicks++ == 0) blocks++;
                lastShieldTick = me.tickCount;
                return decide("block");
            }
            if (blockTicks > 0) {
                use(false);
                blockTicks = 0;
            }

            // crystals and anchors reach further than a sword, and blowing them is also how we clear a wall of them
            if (crystal(me)) {
                if (dist <= DRIVE) steer(me, dist);
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
                if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    look(wall.getLocation());
                    key(Input.CLICK_LEFT); // hold the attack key on the wall
                    return decide("dig");
                }
            }
            // 1907 bench: they fell 8 blocks off the platform and no path follows a drop that deep, so
            // 945 ticks went to standing at the edge. Walk off after them; with a mace the fall is a dive.
            double drop = me.getY() - target.getY();
            if (drop > 3.5 && drop < 20 && horizontalBoxDist(me, target) < 8 && eatTicks == 0
                    && (slotOf(me, Items.MACE) >= 0 || me.getHealth() > drop + 4)) {
                use(false);
                int mace = slotOf(me, Items.MACE);
                select(me, mace >= 0 ? mace : weapon(me));
                look(target.getEyePosition());
                key(Input.MOVE_FORWARD);
                key(Input.SPRINT);
                if (!me.onGround() && mace >= 0) {
                    macePhase = 2;
                    maceTicks = 5;
                }
                return decide("drop");
            }
            if (dist > DRIVE || !los) {
                // Charge needs a sprint runway. Baritone chase from 7 blocks never reaches 4.6 blocks/s
                // before the pierce window, so a plain spear closes that gap on foot.
                boolean spearRush = spearSlot(me) >= 0 && los && dist < 14 && eatTicks == 0 && spearUseCool == 0
                        && me.getFoodData().getFoodLevel() > 6;
                if (!spearRush) {
                    if (los && dist > BOW_MIN && slotOf(me, Items.BOW) >= 0 && slotOf(me, Items.ARROW) >= 0) return decide("bow", bow(me));
                    use(false);
                    // Spear chase stops in the jab band, not inside the 2-block dead zone.
                    int near = spearSlot(me) >= 0 ? 3 : 2;
                    return decide("chase", new PathingCommand(new GoalNear(target.blockPosition(), near), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
                }
            }
            // A spear charge is the use key. Releasing here every tick reset the 10-tick delay.
            if (me.isUsingItem() && spearUseTicks == 0) use(false);

            int spear = spearSlot(me);
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
            if (spear >= 0 && spearLungeLevel(spearStack) < 1 && spearUseTicks == 0 && !spearCommit && !spearReopen && !targetEating && los && hr < 4.8
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
                    if (aimedAt(me, aim, 25f) || spearFaceTicks > 3 && aimedAt(me, aim, 50f)) {
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
                if (!spearRayHits(me)) return decide("spear_aim");
                use(false); // a held charge would eat the jab click
                hit(me);
                return decide(meFalling ? "spear_fall" : targetFalling ? "spear_air" : "spear");
            }

            // a disabled shield stays "raised" for its 5s cooldown; don't keep throwing uncharged axe swings at it
            // only a shield past its 5-tick warm-up is disabled by the axe; a swing into the raise is a wasted cooldown
            // VexBot's shield blocks from its first tick (no warm-up), so the use flag is the shield
            boolean tgShield = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
            if (tgShield && me.tickCount - lastAxeTick >= 4) axeHeld = false; // it is back up: that swing did not disable it
            // the axe drew blood: there was no shield in its way, so none is on cooldown either
            if (axeHeld && me.tickCount - lastAxeTick <= 3 && target.hurtTime >= 8) axeHeld = false;
            boolean duel = target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && target.getOffhandItem().getItem() == Items.SHIELD;
            // 224436 expert adaptive: swords at t17 and t32 did nothing against a target showing no use flag.
            // A blockhitter's shield goes up with its swing, flag or no flag; the window is learned from bounced hits.
            if (duel && hiddenShield(me)) tgShield = true;
            boolean axeTime = tgShield && inReach && me.tickCount - lastAxeTick > 8 && best(me, AXES) >= 0;
            if (!select(me, axeTime ? best(me, AXES) : weapon(me))) return decide("swap");
            look(swingPoint(me, target));

            boolean targetReady = me.tickCount - targetSwingTick >= 10; // its sword is charged: whoever swings first wins the exchange
            // the tick the use key comes up, an attack click is still swallowed by the item in use
            boolean immune = target.hurtTime > 1 || me.tickCount - lastShieldTick < (counter ? 2 : 3)
                    || tgShield && best(me, AXES) >= 0; // a sword into a raised shield is a wasted cooldown
            boolean falling = !me.onGround() && me.getDeltaMovement().y < -0.05;
            boolean canJump = me.onGround() && !me.isInWater() && !me.isInLava() && !me.onClimbable();
            boolean hurry = me.getCurrentItemAttackStrengthDelay() < 14;
            // 233514 axe expert adaptive: it ate from 4 HP back to 20 under plain 3.84s swung on the run, and an apple
            // is worth 8. A target chewing in reach is not going anywhere: it gets the crit like any other.
            boolean run = chase && (hurry || !targetEating);
            // Same fight, t544: jumped fully charged and hung in the air for the fall while its axe, due, landed first.
            // With its next swing due inside the jump, the charged swing goes now.
            int swingAge = me.tickCount - targetSwingTick;
            boolean rushed = !hurry && !targetEating && cd >= 0.95f
                    && swingAge >= (swingGap > 0 ? swingGap : target instanceof Player tp ? (int) tp.getCurrentItemAttackStrengthDelay() : 20) - 6;

            // Plain spear: commit to closing or backing until settled inside 2-4, then hold and jab.
            // No sprint near the band, so one tick cannot cross it. Lunge only with the enchant.
            if (spear >= 0) {
                int lungeLvl = spearLungeLevel(me.getInventory().getItem(spear));
                double lungeMax = 6.0 + lungeLvl * 5.0; // L1 ~11, L2 ~16, L3 ~21
                // Jab is 4 raw (0.96 through diamond). Charge is KineticWeaponComponent.usageTick
                // while use is held, after a 10-tick delay. Damage needs look.dot(movement)*20 >= 4.6.
                // Hold use on the approach so the delay ends inside the 2-4.5 pierce window, then release.
                if (spearUseCool > 0) spearUseCool--;
                if (lungeLvl < 1 && los && eatTicks == 0 && me.getFoodData().getFoodLevel() > 6) {
                    double along = kineticAlong(me);
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
            if (!inReach) steer(me, dist);
            else if (dist < 1.4) key(Input.MOVE_BACK); // sword: too close, make space
            // its reach is measured centre to centre, a little shorter than ours: recharge just outside it
            else if (duel && cd < 0.75f && dist < 2.9) key(Input.MOVE_BACK);
            if (me.hurtTime == me.hurtDuration - 1 && canJump) key(Input.JUMP); // jump reset

            if (axeTime && inReach && me.tickCount - lastShieldTick >= 2) { // an axe disables a raised shield whatever the charge
                if (hit(me)) { // only a click that connected starts the wait for its shield
                    axeHits++;
                    lastAxeTick = me.tickCount;
                    axeHeld = true;
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
            if ((run || counter) && inReach && cd >= 0.9f && !immune) {
                hit(me);
                return decide("hit");
            }
            if (!me.onGround() && me.getDeltaMovement().y < 0.08 && dist <= REACH + 0.6 && cd >= 0.5f && (inReach || !chase)) {
                wtap = Math.max(wtap, 1); // let go of forward for a tick so the sprint drops: a sprinting hit is never a crit
                critArmed = true;
            }
            if (!me.onGround() && !critArmed && me.getDeltaMovement().y < 0.08 && inReach && cd >= 0.95f && !immune) {
                hit(me); // knocked airborne without a crit set up: don't waste the cooldown
                return decide("hit");
            }
            if (me.onGround()) critArmed = false;
            else groundedJumps = 0;

            boolean breached = axeHeld && me.tickCount - lastAxeTick < 100;
            // 232146 axe expert adaptive: 47 ground hits of 3.65 into an opponent eating apples, two jumps all fight.
            // A slow weapon is charged by the time the jump falls, so the crit costs nothing: only a sword hurries.
            if (breached && hurry && inReach && cd >= 0.95f && !immune) {
                hit(me); // its shield is on cooldown: land the follow-up as soon as the sword is charged
                return decide("hit");
            }
            boolean diving = !target.onGround() && tv().y < -0.2 && target.getY() > me.getY() + 1.5;
            // The fall starts six ticks after the jump. A mace or axe jumped at 0.55 landed before it was charged.
            float jumpCd = Math.max(0.55f, 1 - 6f / me.getCurrentItemAttackStrengthDelay());
            if (!(breached && hurry) && !duel && !diving && !run && !counter && !(rushed && inReach) && canJump && dist <= REACH + 0.8 && cd >= jumpCd && !immune && groundedJumps < 4) {
                key(Input.JUMP);
                groundedJumps++;
                return decide("jump");
            }
            if (me.onGround() && inReach && cd >= (duel ? 0.9f : 0.95f) && !immune && (duel || !canJump || groundedJumps >= 4 || rushed)) {
                boolean sprint = me.isSprinting();
                hit(me);
                if (sprint) {
                    sprintHits++;
                    wtap = 2;
                }
                groundedJumps = 0;
                return decide("hit");
            }
            return decide("strafe");
        } finally {
            recorder.tick(me, target, eyeToBox(me, target),
                    others(me) + " m" + macePhase + " p" + pearlStage + " f" + fleeTicks + " e" + eatTicks + " s" + me.getInventory().getSelectedSlot()
                            + (ctx.minecraft().screen != null ? " scr=" + ctx.minecraft().screen.getClass().getSimpleName() : "")
                            + (me.getCooldowns().isOnCooldown(me.getOffhandItem()) ? " offcd" : "")
                            + (ctx.minecraft().options.keyUse.isDown() ? " use" : "") + " k" + clickKind,
                    tickDec, attacks);
            clickKind = '-';
        }
    }

    private static final boolean HUMANIZE = !"false".equals(System.getProperty("ostinato.humanize"));
    private static final boolean KINEMATIC = !"false".equals(System.getProperty("ostinato.kinematic"));
    private baritone.pathing.kinematic.KinematicController kin;
    private double wanderY, wanderP, wanderVy, wanderVp;
    private int pearlStage, pearlTicks;
    private Vec3 pearlFrom, pearlLast;
    private int fireCool, fireStage, fireTicks, fleeTicks;
    private BlockPos firePos;
    private LivingEntity tvTarget;
    private Vec3 tvPos = Vec3.ZERO, tvVel = Vec3.ZERO;

    /** Remote players report no velocity client-side, so derive it from their position change per tick. */
    private void trackTarget() {
        if (target != tvTarget || tvTarget == null) {
            tvTarget = target;
            tvVel = Vec3.ZERO;
        } else {
            Vec3 d = target.position().subtract(tvPos);
            tvVel = d.length() > 4 ? Vec3.ZERO : tvVel.scale(0.5).add(d.scale(0.5));
        }
        tvPos = target.position();
    }

    private Vec3 tv() {
        // Remote players keep a stale getDeltaMovement (Vex0 vy stayed 1.16 for a whole jump).
        Vec3 own = target.getDeltaMovement();
        if (target != ctx.player() && tvVel.lengthSqr() > 1e-4) return tvVel;
        return own.lengthSqr() > 1e-4 ? own : tvVel;
    }

    private Item chestSaved;
    private boolean boosted;
    private int pearlCool, spearCool, spearBand, webCool, potCool, windCool, macePhase, maceTicks, maceCool, chargeTicks;
    /** Ticks the spear use-key has been held this pass, and ticks to wait before another pass. */
    private int spearUseTicks, spearUseCool;
    private double spearHrPrev = -1, spearClose;
    private boolean spearReleaseNext, spearReopen, spearFacing, spearCommit;
    private int spearReopenTicks, spearFaceTicks, spearCommitTicks, spearJabWait;

    /** Mace, crossbow and trident play; null when the kit has none of them or they don't apply right now. */
    private boolean diveBlock;
    private int hopThrown;
    // ticks from the wind charge leaving the hand to the jump under it
    private static final int HOP_JUMP_DELAY = Integer.getInteger("ostinato.pvp.hopJumpDelay", 1);
    private int flickLeft, flickAge;
    private int feetCool, feetTicks;

    private PathingCommand special(Player me, double dist, boolean los) {
        if (maceCool > 0) maceCool--;
        int mace = slotOf(me, Items.MACE), wind = slotOf(me, Items.WIND_CHARGE);
        if (windCool > 0) windCool--;
        boolean overhead = !target.onGround() && target.getY() > me.getY() + 3;
        // Spear kit still jabs. Wind is only a knock-in just outside the band, or the hop under a mace smash.
        // Far wind-charge spam and shield-holding stay off. A plain spear never lunges.
        if (spearSlot(me) >= 0 && macePhase == 0 && pearlStage == 0) {
            // Their mace dive is the ~9 damage in the spear logs. Shield it; do not hop into it.
            // Melee and the mace smash landed on spear_back, never on spear_charge.
            // Shielding mid-charge swaps off the spear. Dive-block only while use is not held.
            if (spearUseTicks == 0 && shieldDive(me, dist)) return decide("block");
            // Do not hop in the middle of a charge approach. The hop was the whole fight and use never started.
            boolean foeEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
            PathingCommand tool = spearUseTicks == 0 && !spearReopen && !spearCommit && !foeEating && !(spearUseCool == 0 && horizontalBoxDist(me, target) > 4.6)
                    ? spearTools(me, dist, los, wind, mace) : null;
            if (tool != null) return tool;
            return null;
        }
        // arrows, tridents, fireballs, potions: a wind charge on the projectile's path deflects it
        if (wind >= 0 && macePhase == 0 && windCool == 0) {
            for (net.minecraft.world.entity.projectile.Projectile pr : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    me.getBoundingBox().inflate(14), e -> e.getOwner() != me && !e.onGround() && e.getDeltaMovement().lengthSqr() > 0.09)) {
                Vec3 v = pr.getDeltaMovement(), rel = me.getEyePosition().subtract(pr.position());
                double d = rel.length();
                if (d < 3.5 || d > 13 || v.dot(rel) <= 0 || v.normalize().dot(rel.normalize()) < 0.85) continue;
                Vec3 at = pr.position().add(v.scale(d / (v.length() + 1.5)));
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
                if (!select(me, wind)) return decide("swap");
                if (!face(r.getYaw(), r.getPitch(), 2.5f)) return decide("deflect");
                press(ctx.minecraft().options.keyUse);
                windCool = 8;
                return decide("deflect");
            }
        }
        // an airborne opponent diving at us: a wind charge on its predicted path knocks it off the smash
        // Only a real dive. Every ordinary jump matched the old test, which was 347 of 1800 ticks.
        // 1726 bench: 235 ticks of this and the 9.1 smashes landed anyway. A shield stops a smash, so a kit
        // with one blocks below and keeps the mace in hand and charged.
        boolean hasShield = me.getOffhandItem().getItem() == Items.SHIELD || slotOf(me, Items.SHIELD) >= 0;
        if (wind >= 0 && !hasShield && macePhase == 0 && windCool == 0 && !target.onGround() && dist < 12 && dist > 2
                && target.getY() > me.getY() + 2) {
            Vec3 at = target.getBoundingBox().getCenter().add(tv().scale(dist / 1.5));
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            if (!select(me, wind)) return decide("swap");
            if (!face(r.getYaw(), r.getPitch(), 2.5f)) return decide("wind");
            press(ctx.minecraft().options.keyUse);
            windCool = 12;
            return decide("wind");
        }
        // a diver still coming (no charge, or too close to counter): block the smash with the shield
        // 1822 fight ticks 505-508: block, jump, block as the diver came inside one block of our height,
        // and a shield needs five unbroken ticks. Once a dive is seen the shield stays up until it lands.
        // 000432 ticks 443-452: the diver topped out nine blocks up at 443, passed -0.3 at 448 and landed its mace
        // at 452, on the shield's fifth tick. From three blocks up the turn at the top is already the dive.
        boolean dive = !target.onGround() && dist < 11 && (tv().y < -0.3 && target.getY() > me.getY() + 1 || tv().y < 0.1 && target.getY() > me.getY() + 3);
        if (dive) diveBlock = true;
        else if (target.onGround() || dist > 11) diveBlock = false;
        if (macePhase == 0 && (dive || diveBlock && hasShield)
                && (me.getOffhandItem().getItem() == Items.SHIELD || slotOf(me, Items.SHIELD) >= 0)) {
            if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
            look(target.getEyePosition());
            use(true);
            return decide("block");
        }
        // A wind charge at the feet of an opponent standing behind its shield or eating throws it off the
        // spot and into the air, where the hop that follows finds it.
        if (feetCool > 0) feetCool--;
        if (wind >= 0 && macePhase == 0 && pearlStage == 0 && feetCool == 0 && windCool == 0 && me.onGround() && target.onGround() && los
                && dist > 2.5 && dist < 10 && (target.isBlocking() || target.isUsingItem())) {
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.position().add(0, 0.1, 0), ctx.playerRotations());
            if (!select(me, wind)) return decide("swap");
            if (!face(r.getYaw(), r.getPitch(), 3f) && ++feetTicks < 15) return decide("windfeet");
            if (feetTicks < 15) press(ctx.minecraft().options.keyUse);
            feetTicks = 0;
            feetCool = 80;
            windCool = 8;
            return decide("windfeet");
        }
        // pearl strike: lob a pearl so it peaks above the target, pop it mid-air with a wind charge to teleport there, then drop the mace
        if (mace >= 0 && wind >= 0 && macePhase == 0 && (pearlStage > 0 || pearlCool == 0 && maceCool == 0 && me.onGround() && los && dist > 7 && dist < 22
                && slotOf(me, Items.ENDER_PEARL) >= 0 && target.onGround() && !overhead)) {
            if (pearlStage == 0) {
                float bestPitch = 0;
                double bestErr = 1e9;
                Vec3 eye = me.getEyePosition();
                Vec3 flat = new Vec3(target.getX() - me.getX(), 0, target.getZ() - me.getZ()).normalize();
                for (float pitch = -80; pitch <= -25; pitch += 1.5f) {
                    double pr = Math.toRadians(pitch);
                    Vec3 v = new Vec3(flat.x * Math.cos(pr), -Math.sin(pr), flat.z * Math.cos(pr)).scale(1.5);
                    Vec3 p = eye;
                    for (int t = 0; t < 80; t++) {
                        p = p.add(v);
                        v = v.scale(0.99).add(0, -0.03, 0);
                        Vec3 tp = target.position().add(tv().scale(t + 1));
                        double h = Math.hypot(p.x - tp.x, p.z - tp.z);
                        if (p.y > target.getY() + 5 && p.y < target.getY() + 14 && h < bestErr) {
                            bestErr = h;
                            bestPitch = pitch;
                        }
                        if (p.y < eye.y - 2 && v.y < 0) break;
                    }
                }
                if (bestErr > 2.0) {
                    pearlCool = 80;
                    return null;
                }
                if (!select(me, slotOf(me, Items.ENDER_PEARL))) return decide("swap");
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), eye.add(flat.scale(10)), ctx.playerRotations());
                if (!face(r.getYaw(), bestPitch, 2.5f)) return decide("pearl");
                press(ctx.minecraft().options.keyUse);
                pearlStage = 1;
                pearlTicks = 0;
                pearlFrom = me.position();
                return decide("pearl");
            }
            pearlTicks++;
            if (pearlStage == 1) {
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl = null;
                for (net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl e : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class, me.getBoundingBox().inflate(60), x -> x.getOwner() == me)) pearl = e;
                if (pearl != null) pearlLast = pearl.position();
                if (pearl == null || pearlTicks > 90 || dist < 4) {
                   
                    pearlStage = 0;
                    pearlCool = pearl == null && pearlTicks <= 3 ? 0 : 120;
                    return null;
                }
                if (!select(me, wind)) return decide("swap");
                Vec3 pv = pearl.getDeltaMovement();
                Vec3 pp = pearl.position(), vv = pv;
                int n = 1;
                boolean ok = false;
                for (; n < 80; n++) { // first tick the pearl is over the target, high enough
                    pp = pp.add(vv);
                    vv = vv.scale(0.99).add(0, -0.03, 0);
                    Vec3 tpn = target.position().add(tv().scale(n));
                    if (Math.hypot(pp.x - tpn.x, pp.z - tpn.z) < 1.3 && pp.y > tpn.y + 4) {
                        ok = true;
                        break;
                    }
                    if (pp.y < me.getY() - 3) break;
                }
                look(pearl.position());
                if (ok && pp.distanceTo(me.getEyePosition()) / 1.5 >= n - 1) { // the charge needs about as long to arrive as the pearl does
                    Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), pp, ctx.playerRotations());
                    if (!face(r.getYaw(), r.getPitch(), 2.5f)) return decide("pearl");
                    press(ctx.minecraft().options.keyUse);
                    pearlStage = 2;
                    pearlTicks = 0;
                }
                return decide("pearl");
            }
            // stage 2: wait for the teleport, then fall on it
            if (me.position().distanceTo(pearlFrom) > 5) {
                pearlStage = 0;
                pearlCool = 200;
                macePhase = 2;
                maceTicks = 0;
            } else if (pearlTicks > 40) {
                pearlStage = 0;
                pearlCool = 200;
            }
            return decide("pearl");
        }
        int rocket = slotOf(me, Items.FIREWORK_ROCKET);
        net.minecraft.world.entity.EquipmentSlot chestSlot = net.minecraft.world.entity.EquipmentSlot.CHEST;
        Item worn = me.getItemBySlot(chestSlot).getItem();
        if (mace >= 0 && worn != Items.ELYTRA && macePhase == 0 && maceCool == 0 && me.onGround() && los && dist > 6 && dist < 40 && !overhead
                && (rocket >= 0 || wind >= 0) && slotOf(me, Items.ELYTRA) >= 0) {
            // the wings are in the hotbar, not on the chest: put them on (the chestplate goes where they were)
            chestSaved = worn;
            invSwap(me, 6, slotOf(me, Items.ELYTRA));
            return decide("elytra");
        }
        if (worn == Items.ELYTRA && chestSaved != null && chestSaved != Items.AIR && macePhase == 0 && me.onGround() && maceCool > 0) {
            // landed: the chestplate is worth more than the wings in a melee
            if (slotOf(me, chestSaved) >= 0 && invSwap(me, 6, slotOf(me, chestSaved))) chestSaved = null;
            return decide("elytra");
        }
        if (mace >= 0 && (rocket >= 0 || wind >= 0) && worn == Items.ELYTRA
                && (macePhase >= 5 || macePhase == 0 && me.onGround() && maceCool == 0 && !overhead && tv().y > -0.3 && dist > 3 && dist < 40 && los)) {
            // elytra mace: take off, rocket up above the target, dive and smash
            maceTicks++;
            if (macePhase == 0) {
                key(Input.JUMP);
                boosted = false;
                macePhase = 5;
                maceTicks = 0;
                return decide("elytra");
            }
            Vec3 tp = aimPoint(me, target);
            if (macePhase == 5) { // rising: boost with a wind charge, then press jump in the air to open the wings
                if (me.isFallFlying()) {
                    macePhase = 6;
                    maceTicks = 0;
                } else if (rocket < 0 && !boosted && maceTicks >= 2 && wind >= 0) {
                    if (!select(me, wind)) return decide("swap");
                    if (throwStraightDown(me)) boosted = true;
                } else if (me.getDeltaMovement().y < 0 && !me.onGround() && (rocket >= 0 || boosted)) {
                    if (maceTicks % 2 == 0) key(Input.JUMP);
                } else if (maceTicks > 40) {
                    macePhase = 0;
                    maceCool = 40;
                }
                return decide("elytra");
            }
            // macePhase 6: gliding
            boolean climbing = rocket >= 0 && me.getY() < target.getY() + 14 && maceTicks < 70;
            Vec3 aim = climbing ? new Vec3(tp.x, me.getEyeY() + 30, tp.z).add(tp.subtract(me.position()).multiply(0.0, 0, 0)) : tp;
            if (climbing) {
                Vec3 flat = new Vec3(tp.x - me.getX(), 0, tp.z - me.getZ());
                flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
                aim = me.getEyePosition().add(flat.scale(12)).add(0, 14, 0); // ~50 degrees up
            }
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations());
            aim(r, true);
            double speed = me.getDeltaMovement().length();
            if (climbing && speed < 1.2 && maceTicks % 12 == 3) {
                if (!select(me, rocket)) return decide("swap");
                press(ctx.minecraft().options.keyUse);
            } else if (!climbing) {
                if (!select(me, mace)) return decide("swap");
                if (exactReach(me, target) <= REACH - 0.05) {
                    hit(me);
                    macePhase = 0;
                    maceCool = 10;
                }
            } else {
                if (!select(me, mace)) return decide("swap");
            }
            if (me.onGround() || !me.isFallFlying() && maceTicks > 6 || maceTicks > 200) {
                macePhase = 0;
                maceCool = 40;
            }
            return decide("mace");
        }
        if (mace >= 0) {
            boolean spearKit = spearSlot(me) >= 0;
            boolean canJump = me.onGround() && !me.isInWater();
            // Spear kit starts the smash from spearTools, and only from just outside the jab.
            // The bot stands inside 2.5 for half the fight. The hop goes straight up, so it starts there too.
            // 002307 t1527: it stood at 6 health eating 3.6 away and the next 40 ticks went to a hop; two sword hits end it.
            boolean finish = target.getHealth() + target.getAbsorptionAmount() <= 7 && dist < 5;
            if (!spearKit && macePhase == 0 && canJump && maceCool == 0 && !overhead && !finish && dist > 1.0 && dist < 24 && los && wind >= 0) {
                if (!select(me, wind)) return decide("swap");
                aim(new Rotation(me.getYRot(), 90f), true);
                hopThrown = -1;
                flickLeft = 0;
                flickAge = 100;
                macePhase = 1;
                maceTicks = 0;
            }
            if (macePhase == 1) { // hop: charge leaves straight down this tick, under the feet
                maceTicks++;
                // Spear kit: swapping onto the mace zeroes its charge, and the hop lands at cd~0.5
                // (fight 051702 ticks 45-48 had the fall flag and never clicked). Charge first, then
                // throw the wind charge from the offhand so the hotbar slot never changes.
                if (spearSlot(me) >= 0) {
                    if (!select(me, mace)) return decide("swap");
                    if (me.getAttackStrengthScale(0f) < 0.99f) {
                        // 05:32: standing still to charge let the bot walk into us, and the throw at dist 0.5 never fell.
                        double hr = horizontalBoxDist(me, target);
                        look(aimPoint(me, target));
                        if (hr < 3.6) key(Input.MOVE_BACK);
                        else if (hr > 4.2) key(Input.MOVE_FORWARD);
                        if (maceTicks > 50 || hr < 1.2) {
                            macePhase = 0;
                            maceCool = 40;
                            if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
                        }
                        return decide("mace");
                    }
                    if (horizontalBoxDist(me, target) < 3.2) {
                        macePhase = 0;
                        maceCool = 40;
                        if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
                        return decide("mace");
                    }
                    if (me.getOffhandItem().getItem() != Items.WIND_CHARGE) {
                        toOffhand(me, Items.WIND_CHARGE);
                        return decide("mace");
                    }
                    if (me.onGround()) {
                        key(Input.JUMP);
                        return decide("mace");
                    }
                    if (throwStraightDown(me)) {
                        macePhase = 2;
                        maceTicks = 0;
                    }
                    return decide("mace");
                }
                // Do not look at the target or walk in. A 2.5 deg/tick look throws into the ground ahead.
                if (!select(me, wind)) return decide("swap");
                // 1740 fight ticks 249-262: jumped, threw two ticks later, and the burst met the feet a block
                // up for +0.84 and a 7 block hop. The burst is strongest at the feet: tip down on the ground,
                // throw, and jump as it lands so the jump adds to it.
                aim(new Rotation(me.getYRot(), 90f), true);
                if (hopThrown < 0) {
                    if (me.getXRot() >= 78f && me.onGround()) {
                        press(ctx.minecraft().options.keyUse);
                        hopThrown = maceTicks;
                    } else if (maceTicks > 10) {
                        macePhase = 0;
                        maceCool = 20;
                    }
                } else if (maceTicks - hopThrown >= HOP_JUMP_DELAY) {
                    key(Input.JUMP);
                    macePhase = 2;
                    maceTicks = 0;
                }
                return decide("mace");
            }
            if (macePhase == 2) { // flying: steer to the target, smash while falling
                maceTicks++;
                // Abort a hop that is not a smash when their dive is the one that will land.
                if (spearKit && me.fallDistance < 1.2 && shieldDive(me, dist)) {
                    macePhase = 0;
                    maceCool = 8;
                    return decide("block");
                }
                flickAge++;
                if (flickLeft > 0 && select(me, wind)) { // two ticks, so the server sees it
                    flickLeft--;
                    return decide("flick");
                }
                if (!select(me, mace)) return decide("swap");
                // The fall is 0.9 a tick, 15 degrees of pitch at this range, and a look set now is read
                // from the next tick's eye. Aim from there, or hit() finds the aim 10 degrees off and does not click.
                Vec3 lead = spearKit ? aimPoint(me, target) : aimPoint(me, target).subtract(me.getDeltaMovement());
                look(lead);
                // VexBot's SwordPvpController.isIncomingFallAttack raises its shield when a mace is 3 above it,
                // inside 3.5 and falling at 0.3, and the shield blocks 5 ticks later. A dive that drops in
                // slow and unsprinted meets it less often (4/5 at medium against 1/5 sprinting in).
                boolean quiet = !spearKit && me.distanceTo(target) < 7;
                // A shield covers the half-circle its holder faces and nothing behind it. 235605 t104 and 235404
                // t591 came down 0.9 and 0.4 behind a raised shield and killed through it; 000432 t151 came down
                // in front and did nothing. Against a shield the dive is flown to the far side of its back.
                Vec3 facing = target.calculateViewVector(0f, target.getYHeadRot());
                Vec3 offset = me.position().subtract(target.position()).multiply(1, 0, 1);
                boolean theirShield = target.getOffhandItem().getItem() == Items.SHIELD || target.getMainHandItem().getItem() == Items.SHIELD;
                boolean behind = offset.dot(facing) < -0.1;
                boolean around = !spearKit && theirShield && me.getY() > target.getY() + 1.5;
                // 002307 t1533-1566: it walked off eating at 0.2 a tick and the dive came down 2.8 behind where it
                // had been. Fly to where it will be when the fall reaches its height.
                int fallTicks = 0;
                for (double v = me.getDeltaMovement().y, dy = me.getY() - target.getY() - 1.5; dy > 0 && fallTicks < 30; fallTicks++) {
                    v = (v - 0.08) * 0.98;
                    dy += v;
                }
                Vec3 ahead = target.position().add(tv().multiply(fallTicks, 0, fallTicks));
                boolean moving = !spearKit && tv().horizontalDistance() > 0.1;
                if (around || moving) {
                    Vec3 to = (around ? ahead.subtract(facing.scale(1.6)) : ahead).subtract(me.position()).multiply(1, 0, 1);
                    if (to.horizontalDistance() > 0.15) {
                        float rel = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - me.getYRot());
                        if (Math.abs(rel) < 67.5f) key(Input.MOVE_FORWARD);
                        else if (Math.abs(rel) > 112.5f) key(Input.MOVE_BACK);
                        if (rel > 22.5f && rel < 157.5f) key(Input.MOVE_RIGHT);
                        else if (rel < -22.5f && rel > -157.5f) key(Input.MOVE_LEFT);
                        if (around || to.horizontalDistance() > 2.5) key(Input.SPRINT);
                    }
                } else if (!quiet) {
                    key(Input.MOVE_FORWARD);
                    key(Input.SPRINT);
                } else if (!me.isSprinting() && (horizontalBoxDist(me, target) > 2.2 || me.getDeltaMovement().horizontalDistance() < 0.08)) {
                    key(Input.MOVE_FORWARD);
                }
                if (!spearKit && wind >= 0 && !me.onGround() && flickAge > 40) {
                    // The cooldown restarts when the held item changes: flick off the mace so the fall ends near 0.8.
                    int n = 0;
                    double v = me.getDeltaMovement().y, dy = me.getY() - target.getY() - 1.5;
                    while (dy > 0 && n < 60) {
                        v = (v - 0.08) * 0.98;
                        dy += v;
                        n++;
                    }
                    if (n >= 14 && n <= 26 && me.getAttackStrengthScale(0f) + n / 33f >= 0.84f) {
                        flickAge = 0;
                        flickLeft = 1;
                        select(me, wind);
                        return decide("flick");
                    }
                }
                int sp = spearSlot(me), axe = best(me, AXES);
                boolean shielded = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
                if (shielded && axe >= 0 && me.fallDistance > 1.5 && me.tickCount - lastAxeTick > 20 && exactReach(me, target) <= REACH - 0.05) {
                    if (!select(me, axe)) return decide("swap"); // breach slam: the axe drops the shield, the mace lands on the next tick
                    hit(me);
                    axeHits++;
                    lastAxeTick = me.tickCount;
                    return decide("axe");
                }
                // Lunge ONLY if the held spear has minecraft:lunge 1-3. Plain spears never lunge.
                // Vanilla impulse scales ~0.458 per level; we only jab farther out at higher levels.
                int lungeLvl = sp >= 0 ? spearLungeLevel(me.getInventory().getItem(sp)) : 0;
                double lungeMax = 6.0 + lungeLvl * 5.0; // L1~11, L2~16, L3~21
                if (sp >= 0 && lungeLvl >= 1 && spearCool == 0 && !me.onGround()
                        && dist > 4 && dist < lungeMax && me.getFoodData().getFoodLevel() >= 7) {
                    if (!select(me, sp)) return decide("swap");
                    hit(me); // piercing jab; vanilla post_piercing_attack applies Lunge impulse by level
                    spearCool = 45 - lungeLvl * 5;
                    return decide("lunge");
                }
                // 1740 fight ticks 249-277: the swap back to the mace zeroes a 33-tick cooldown and the
                // hop lands 28 ticks later at 0.78, so a 0.99 gate never opened. The fall bonus scales
                // with the charge: swing on the last tick before the ground with whatever is there.
                boolean landing = !ctx.world().noCollision(me, me.getBoundingBox().move(0, -1.3, 0));
                float sc = me.getAttackStrengthScale(0f);
                // 234854 mace easy defensive: it launched after our hop and the dive met it ten blocks up at 0.5 charge,
                // dropped past and landed under its mace. A target in the air that the fall is about to pass is the
                // same last tick as the ground.
                // 235231 ticks 606/952: passing it just under the apex smashed for 5 with nothing fallen yet.
                boolean passing = !spearKit && !target.onGround() && me.fallDistance >= 4 && me.getY() + me.getDeltaMovement().y * 2 < target.getY() + 0.5;
                if (me.fallDistance > 1.5 && me.getDeltaMovement().y < -0.05
                        && (spearKit ? sc >= 0.99f : sc >= 0.6f || (landing || passing) && sc >= 0.4f) && target.hurtTime <= 0
                        && exactReach(me, target) <= REACH - 0.05
                        // 1745 fight tick 91: the aim was off on the landing tick, hit() did not click, and the
                        // hop was written off as spent.
                        && (spearKit || aimedAt(me, aimPoint(me, target), 10f)
                        || ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult on && on.getEntity() == target)
                        && (!around || behind || landing || !shielded)
                        && (me.fallDistance >= 3 || landing || passing)) {
                    // 235231 ticks 238 and 297: hit() refused the click (crosshair off its own swing point) and
                    // the dive was closed anyway, three ticks above a target it then fell onto unarmed.
                    boolean clicked = hit(me);
                    if (!spearKit) look(lead);
                    if (clicked || spearKit) {
                        macePhase = 0;
                        maceCool = 14;
                        if (!spearKit && me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
                    }
                } else if (me.onGround() && (spearKit || maceTicks > 4) || maceTicks > (spearKit ? 40 : 80)) {
                    // A hop that did not smash must not restart. Walk into the jab band first.
                    if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
                    macePhase = 0;
                    maceCool = spearKit ? 300 : 20; // 300 after every missed hop left 8 smashes in a 90s round
                }
                return decide("mace");
            }
            if (wind < 0 || maceCool > 0 || dist <= 3) {
                int alt = weapon(me);
                if (alt < 0) alt = mace;
                if (!select(me, alt)) return decide("swap");
            }
            return null;
        }
        if (webCool > 0) webCool--;
        // a web in the target's feet slows it into our hits
        if (webCool == 0 && los && dist > 2.4 && dist < 5 && target.onGround() && slotOf(me, Items.COBWEB) >= 0
                && ctx.world().getBlockState(target.blockPosition()).isAir()) {
            webCool = 60;
            place(me, Items.COBWEB, target.blockPosition().below());
            return decide("web");
        }
        if (potCool > 0) potCool--;
        if (potCool == 0) {
            int heal = potion(me, MobEffects.INSTANT_HEALTH), harm = potion(me, MobEffects.INSTANT_DAMAGE);
            if (heal >= 0 && me.getHealth() <= 9) {
                if (!select(me, heal)) return decide("swap");
                if (!face(me.getYRot(), 90f, 2.5f)) return decide("pot");
                press(ctx.minecraft().options.keyUse);
                potCool = 12;
                return decide("pot");
            }
            if (harm >= 0 && los && dist > 3 && dist < 12) {
                Vec3 at = target.position().add(tv().scale(dist / 0.5)).add(0, 0.2, 0);
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at.add(0, dist * 0.12, 0), ctx.playerRotations());
                if (!select(me, harm)) return decide("swap");
                if (!face(r.getYaw(), r.getPitch(), 2.5f)) return decide("pot");
                press(ctx.minecraft().options.keyUse);
                potCool = 25;
                return decide("pot");
            }
        }
        // soul sand + flint and steel + any bow: shoot through the fire to set the target alight
        if (slotOf(me, Items.SOUL_SAND) >= 0 && slotOf(me, Items.FLINT_AND_STEEL) >= 0 && slotOf(me, Items.BOW) >= 0
                && slotOf(me, Items.ARROW) >= 0 && los && dist > (fireStage > 0 ? 6 : 11) && dist < 22 && (fireStage > 0 || (fireCool == 0 && target.onGround() && me.onGround() && !target.isOnFire()))) {
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
                    if (place(me, Items.SOUL_SAND, firePos)) fireStage = 2;
                    return decide("fire");
                } else if (fireStage == 2) {
                    if (place(me, Items.FLINT_AND_STEEL, firePos.above())) fireStage = 3;
                    return decide("fire");
                } else {
                    if (!ctx.world().getBlockState(firePos.above(2)).isAir() && ctx.world().getBlockState(firePos.above(2)).getBlock() != net.minecraft.world.level.block.Blocks.FIRE
                            && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.SOUL_FIRE
                            && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.FIRE) fireStage = 0;
                    if (target.isOnFire() || fireTicks > 70) { fireStage = 0; fireCool = 400; }
                    return decide("bow", bow(me));
                }
            }
        } else if (fireStage < 0 && (!target.onGround() || dist < 5)) {
            fireStage = 0;
        } else if (fireStage > 0) {
            fireStage = 0;
        }
        int xb = slotOf(me, Items.CROSSBOW);
        if (xb >= 0 && los && dist > 5 && dist < 70 && (slotOf(me, Items.ARROW) >= 0 || net.minecraft.world.item.CrossbowItem.isCharged(me.getInventory().getItem(xb)))) {
            if (!select(me, xb)) return decide("swap");
            Vec3 at = arcAim(me.getEyePosition(), target.getBoundingBox().getCenter(), tv(), 3.15);
            look(at);
            ItemStack held = me.getMainHandItem();
            if (net.minecraft.world.item.CrossbowItem.isCharged(held)) {
                // loaded: shoot only once the aim has settled on the arc, not on the way there
                if (aimedAt(me, at, 3f) || ++xbWait > 40) {
                    use(false);
                    press(ctx.minecraft().options.keyUse);
                    attacks++;
                    xbWait = 0;
                }
            } else if (me.isUsingItem()) {
                // a crossbow only loads when the use key is let go after the full draw
                if (me.getTicksUsingItem() >= net.minecraft.world.item.CrossbowItem.getChargeDuration(held, me)) use(false);
                else use(true);
            } else {
                use(true);
                press(ctx.minecraft().options.keyUse);
            }
            return decide("crossbow");
        }
        int tr = slotOf(me, Items.TRIDENT);
        if (tr >= 0 && los && dist > 5 && dist < 40) {
            if (!select(me, tr)) return decide("swap");
            look(target.getEyePosition().add(0, dist * 0.04, 0));
            if (++chargeTicks > 14) {
                use(false);
                chargeTicks = -8;
            } else if (chargeTicks > 0) {
                use(true);
            }
            return decide("trident");
        }
        return null;
    }

    /** Hotbar slot of a splash potion carrying the effect, or -1. */
    private int potion(Player me, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SPLASH_POTION) continue;
            net.minecraft.world.item.alchemy.PotionContents pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            for (net.minecraft.world.effect.MobEffectInstance ei : pc.getAllEffects()) if (ei.getEffect().equals(effect)) return i;
        }
        return -1;
    }

    /** Hotbar slot of a plain spear (unenchanted diamond_spear etc.), or -1. */
    private int spearSlot(Player me) {
        // 1626 bench, 17 rounds: the spear routine dealt 0-4 a round and died in 11. A plain jab
        // is 0.96 through diamond and a smash is 4-7, so a kit with a mace and wind charges
        // plays the mace and leaves the spear in the hotbar.
        if (slotOf(me, Items.MACE) >= 0 && (slotOf(me, Items.WIND_CHARGE) >= 0 || me.getOffhandItem().getItem() == Items.WIND_CHARGE)) return -1;
        int byItem = best(me, SPEARS);
        if (byItem >= 0) return byItem;
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            // Tag covers every vanilla spear without requiring enchants or PIERCING_WEAPON
            if (st.is(net.minecraft.tags.ItemTags.SPEARS)) return i;
        }
        return -1;
    }

    private static boolean isSpear(ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        Item it = st.getItem();
        for (Item s : SPEARS) if (it == s) return true;
        return st.is(net.minecraft.tags.ItemTags.SPEARS);
    }

    /**
     * Level of {@code minecraft:lunge} on a spear, else 0.
     * Plain/unenchanted spears return 0 and must never lunge. Caps at 3.
     */
    private int spearLungeLevel(ItemStack st) {
        if (!isSpear(st)) return 0;
        net.minecraft.world.item.enchantment.ItemEnchantments enchants =
                st.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                        net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        if (enchants.isEmpty()) return 0;
        // Plain spears have no lunge entry. Match id string so we do not depend on ResourceLocation APIs.
        for (var entry : enchants.entrySet()) {
            String id = entry.getKey().unwrapKey().map(Object::toString).orElse(entry.getKey().toString());
            if (!id.contains("lunge")) continue;
            int lvl = entry.getIntValue();
            return lvl < 1 ? 0 : Math.min(3, lvl);
        }
        return 0;
    }

    private int blockSlot(Player me) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() != net.minecraft.world.level.block.Blocks.SOUL_SAND
                    && bi.getBlock().defaultBlockState().isSolid() && !(bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                    && bi.getBlock() != net.minecraft.world.level.block.Blocks.TNT) return i;
        }
        return -1;
    }

    private boolean canHeal(Player me) {
        return me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING || slotOf(me, Items.GOLDEN_APPLE) >= 0
                || slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 || potion(me, MobEffects.INSTANT_HEALTH) >= 0;
    }

    /** Low on health with nothing to heal: pearl away from the target, else run. */
    private PathingCommand flee(Player me, double dist) {
        use(false);
        if (dist < 10 && pearlCool == 0 && slotOf(me, Items.ENDER_PEARL) >= 0) {
            Vec3 away = new Vec3(me.getX() - target.getX(), 0, me.getZ() - target.getZ());
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 at = me.getEyePosition().add(away.scale(24)).add(0, 7, 0);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            if (!select(me, slotOf(me, Items.ENDER_PEARL))) return decide("swap");
            if (!face(r.getYaw(), r.getPitch(), 2.5f)) return decide("pearl");
            press(ctx.minecraft().options.keyUse);
            pearlCool = 160;
            return decide("pearl");
        }
        if (dist > 16) {
            fleeTicks = 0;
            return decide("flee"); // clear of it: stand and regenerate
        }
        int blk = blockSlot(me);
        if (++fleeTicks > 40 && dist < 6 && blk >= 0 && me.getY() - target.getY() < 5) {
            // can't shake it: tower up out of melee
            if (!select(me, blk)) return decide("swap");
            aim(new Rotation(me.getYRot(), 90f), true);
            if (me.onGround()) key(Input.JUMP);
            else if (me.getDeltaMovement().y < 0.1 && ctx.world().getBlockState(me.blockPosition().below()).isAir()) click(me, me.blockPosition().below().below());
            return decide("pillar");
        }
        return decide("flee", new PathingCommand(new baritone.api.pathing.goals.GoalRunAway(18, target.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
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

    private void steer(Player me, double dist) {
        if (--strafeLeft <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeLeft = 10 + rng.nextInt(20);
        }
        if (chase) {
            wtap = 0;
            key(Input.MOVE_FORWARD);
            if (me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
            if (dist > 3.5 && me.onGround() && me.isSprinting() && !me.isInWater()) key(Input.JUMP);
            return;
        }
        if (wtap > 0) {
            wtap--;
        } else if (dist > 2.4) {
            key(Input.MOVE_FORWARD);
            if (!critArmed && me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
        } else if (dist < 1.2) {
            key(Input.MOVE_BACK);
        }
        if (dist > 3.5) {
            // it's backing off to heal: run it down in a straight line, sprint-jumping for speed
            if (me.onGround() && me.isSprinting() && !me.isInWater() && (!KINEMATIC || (kin != null ? kin : (kin = new baritone.pathing.kinematic.KinematicController(ctx))).jumpHelps(target.getX(), target.getZ()))) key(Input.JUMP);
            return;
        }
        key(strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT);
    }

    /**
     * Between our own swings the opponent's sword is the only thing hurting us: hold the shield up while the
     * weapon recharges and drop it as the swing comes back. A raised target shield is the axe's job instead.
     */
    private boolean meleeBlock(Player me, double dist) {
        if (me.getOffhandItem().getItem() != Items.SHIELD && slotOf(me, Items.SHIELD) < 0) return false;
        // 212743 hard aggressive: every 6 and 9 landed in the air after our own jump swing.
        if (dist > 5.5 || me.isInWater() || eatTicks > 0 || macePhase != 0) return false;
        if (target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD)) return false; // it cannot swing mid-bite

        counter = false;
        // 221131 expert: VexBot's sword put our shield on cooldown with the first blocked swing, and the
        // next 60 ticks were spent standing behind a shield that was not there.
        if (me.getCooldowns().isOnCooldown(me.getOffhandItem())) return false;
        if (target.isBlocking() && dist <= REACH + 0.5 && best(me, AXES) >= 0 && me.tickCount - lastAxeTick > 25) return false;
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
            if (!me.isBlocking() && cd >= 0.9f && dist <= REACH && !target.isUsingItem() && !hiddenShield(me)) return false; // a free swing beats a shield it will axe
        }
        boolean holding = blockTicks > 0 && me.isUsingItem();
        // 224436 expert adaptive: the shield came down at 0.78 charge, six ticks before each of its swings.
        // Its next swing is due one observed swing gap after the last: be behind a warmed-up shield for it, then answer.
        int since = me.tickCount - targetSwingTick;
        int gap = swingGap > 0 ? swingGap : 13; // a sword recharges in 12.5 ticks
        boolean due = target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && since >= gap - 6 && since < gap + 10 && dist < 4.5;
        // raise early enough for the shield's warm-up, hold until the swing is nearly ready
        return holding ? (cd < 0.78f || due) && blockTicks < 40 : (cd < 0.45f || due) && since > 2;
    }

    /** A blockhitting opponent is behind its shield right after its own swing, and the use flag does not always reach the client. */
    private boolean hiddenShield(Player me) {
        int since = me.tickCount - targetSwingTick;
        return since >= 0 && since < unseenBlock && !(axeHeld && me.tickCount - lastAxeTick < 100);
    }

    /** Shield a mace dive. True when the shield is being raised this tick. */
    private boolean shieldDive(Player me, double dist) {
        if (dist > 7 || target.onGround() || target.getY() < me.getY() + 1.0) return false;
        // 05:32 blocked for the whole jump (507 ticks) and still took D9 with the shield up.
        // Only the last part of a real descent. While they are high, keep jabbing.
        if (tv().y >= -0.08 || target.getY() > me.getY() + 2.6) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD && slotOf(me, Items.SHIELD) < 0) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
        // A spear's right-click uses the spear, so the shield never reaches isBlocking() (logs: flag U, never B, D9).
        int hand = slotOf(me, Items.MACE);
        if (hand < 0) hand = spearSlot(me);
        if (hand >= 0 && !select(me, hand)) return true;
        look(target.getEyePosition());
        use(true);
        if (blockTicks++ == 0) blocks++;
        return true;
    }

    private boolean shouldBlock(Player me, double dist) {
        if (me.getOffhandItem().getItem() != Items.SHIELD && slotOf(me, Items.SHIELD) < 0) return false;
        ItemStack using = target.getUseItem();
        if (target.isUsingItem() && (using.getItem() == Items.BOW || using.getItem() == Items.CROSSBOW) && dist > 4) return true;
        AABB around = me.getBoundingBox().inflate(6);
        for (AbstractArrow a : ctx.world().getEntitiesOfClass(AbstractArrow.class, around, x -> true)) {
            Vec3 v = a.getDeltaMovement();
            if (v.lengthSqr() < 0.25) continue;
            Vec3 to = me.position().add(0, 1, 0).subtract(a.position());
            if (to.normalize().dot(v.normalize()) > 0.9) return true;
        }
        return false;
    }

    private int xbWait;

    /** Whether our view is within {@code deg} degrees of looking at the point. */
    private boolean aimedAt(Player me, Vec3 at, float deg) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        return Math.abs(Mth.wrapDegrees(r.getYaw() - me.getYRot())) <= deg && Math.abs(r.getPitch() - me.getXRot()) <= deg;
    }

    /**
     * Point to look at so an arrow of the given launch speed (blocks/tick) meets a moving target: gravity 0.05 and
     * drag 0.99 per tick are simulated, so a far shot is lobbed over the drop instead of hitting the floor.
     */
    private static Vec3 arcAim(Vec3 from, Vec3 pos, Vec3 vel, double speed) {
        Vec3 aim = pos;
        double flight = 0;
        for (int it = 0; it < 3; it++) {
            Vec3 to = pos.add(vel.x * flight, vel.y * flight * 0.5, vel.z * flight);
            double dx = Math.hypot(to.x - from.x, to.z - from.z), dy = to.y - from.y;
            double lo = -Math.PI / 6, hi = Math.PI / 4;
            double tFlight = dx / speed;
            for (int i = 0; i < 24; i++) {
                double mid = (lo + hi) / 2, vx = Math.cos(mid) * speed, vy = Math.sin(mid) * speed, x = 0, y = 0;
                int t = 0;
                while (x < dx && t < 400) {
                    x += vx;
                    y += vy;
                    vx *= 0.99;
                    vy = vy * 0.99 - 0.05;
                    t++;
                }
                if (y < dy) lo = mid;
                else hi = mid;
                tFlight = t;
            }
            double ang = (lo + hi) / 2;
            flight = tFlight;
            Vec3 h = new Vec3(to.x - from.x, 0, to.z - from.z);
            h = h.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : h.normalize();
            aim = from.add(h.x * Math.cos(ang) * 50, Math.sin(ang) * 50, h.z * Math.cos(ang) * 50);
        }
        return aim;
    }

    private PathingCommand bow(Player me) {
        if (!select(me, slotOf(me, Items.BOW))) return decide("swap");
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

    private boolean eat(Player me) {
        Item apple = me.getHealth() <= 6 && slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        if (slotOf(me, apple) < 0) apple = Items.ENCHANTED_GOLDEN_APPLE;
        if (slotOf(me, apple) < 0) {
            eatTicks = 0;
            return false;
        }
        if (!select(me, slotOf(me, apple))) return true;
        if (me.getMainHandItem().getItem() != apple) return true;
        if (eatTicks++ == 0) gapples++;
        key(Input.MOVE_BACK); // back off while chewing
        use(true);
        look(target.getEyePosition());
        if (eatTicks > 36) {
            use(false);
            eatTicks = 0;
        }
        return true;
    }

    private void keepTotem(Player me) {
        if (me.getHealth() > 8 && !crystalFight || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        toOffhand(me, Items.TOTEM_OF_UNDYING);
    }

    /** Swap an inventory item into the offhand (button 40 = offhand swap). */
    private void toOffhand(Player me, Item item) {
        int slot = -1;
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return;
        int menuSlot = slot < 9 ? 36 + slot : slot;
        invSwap(me, menuSlot, 40);
    }

    /** Hotbar slot of the item, pulling it into the hotbar (slot 8) if it's only in the main inventory. */
    private int slotOf(Player me, Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        for (int i = 9; i < 36; i++) {
            if (me.getInventory().getItem(i).getItem() == item) {
                return invSwap(me, i, 8) ? 8 : -1;
            }
        }
        return -1;
    }

    private int best(Player me, Item[] tiers) {
        for (Item it : tiers) {
            for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == it) return i;
        }
        return -1;
    }

    /** Crit play wants damage per swing: a sword, else an axe. */
    private int weapon(Player me) {
        int s = best(me, SWORDS);
        if (s >= 0) return s;
        int a = best(me, AXES);
        if (a >= 0) return a;
        int sp = spearSlot(me); // spear bench kit has no sword or axe
        if (sp >= 0) return sp;
        return slotOf(me, Items.MACE);
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

    /** A real key press: queued like a keyboard or mouse event and handled at the start of the next client tick. */
    private void press(net.minecraft.client.KeyMapping km) {
        net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(km.saveString()));
    }

    /** Turn the view toward the angles with the smoothed look; true once it already points there within tol degrees. */
    /** Every PvP look goes out as a bounded, mouse-stepped move; see LookBehavior.human(). */
    private void aim(Rotation r, boolean blockInteract) {
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(r, blockInteract);
    }

    private boolean face(float yaw, float pitch, float tol) {
        aim(new Rotation(yaw, pitch), true);
        Player me = ctx.player();
        return Math.abs(Mth.wrapDegrees(yaw - me.getYRot())) <= tol && Math.abs(pitch - me.getXRot()) <= tol;
    }

    /**
     * Foot wind-charge only. Pitch is set to 90 and use is pressed on this tick, before keybinds,
     * so the charge leaves straight down. A smoothed look makes it hit the ground ahead. Other aims stay human.
     */
    private boolean throwStraightDown(Player me) {
        aim(new Rotation(me.getYRot(), 90f), true);
        if (me.getXRot() < 78f) return false; // still tipping down: a 90 degree pitch change inside one tick is a snap
        press(ctx.minecraft().options.keyUse);
        return true;
    }

    /** Left-click only if the crosshair is on the entity, as the mouse button would. */
    private boolean hit(Player me, Entity e) {
        if (!(ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult er) || er.getEntity() != e) return false;
        press(ctx.minecraft().options.keyAttack);
        return true;
    }

    private boolean hit(Player me) {
        boolean spearAim = isSpear(me.getMainHandItem());
        Vec3 aim = spearAim ? aimPoint(me, target) : swingPoint(me, target);
        look(aim);
        double er = exactReach(me, target);
        boolean spear = isSpear(me.getMainHandItem());
        if (spear) {
            // Piercing jab raycasts along the look vector. A click that is merely near the eyes misses
            // and, below full charge, is rejected. Do not press attack unless this ray connects.
            if (!spearRayHits(me)) return false;
            press(ctx.minecraft().options.keyAttack);
            attacks++;
            return true;
        }
        // 003156 mace medium: twelve dives came down on its head and none clicked. Falling 1.2 a tick past a target
        // a block away the bearing swings 40 degrees a tick, and the look is always one behind it. The crosshair
        // being on the entity is what a click needs; the angle only guards a swing on level ground.
        boolean onIt = me.fallDistance > 1.5 && ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult on && on.getEntity() == target;
        if (!onIt && !aimedAt(me, aim, 10f)) { clickKind = 'a'; return false; } // must be looking at the target
        if (hit(me, target)) {
            attacks++;
            clickKind = 'E';
            if (!me.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) {
                probeTick = me.tickCount;
                probeSince = me.tickCount - targetSwingTick;
            }
            return true;
        }
        // 223541 expert adaptive t32: a click with the crosshair beside the hitbox swung at air and spent
        // the full charge. The click is handled this tick against the pick already made, so no entity, no click.
        clickKind = 'r';
        return false;
    }

    private int invTick = -99;

    /** A swap through the inventory screen: opened on one tick, clicked on a later one, then closed. */
    private boolean invSwap(Player me, int menuSlot, int button) {
        net.minecraft.client.Minecraft mc = ctx.minecraft();
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
            if (mc.screen == null) {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(me));
                invTick = me.tickCount;
            }
            return false;
        }
        if (me.tickCount <= invTick) return false;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, button, ClickType.SWAP, me);
        mc.setScreen(null);
        return true;
    }

    private void key(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    private void use(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down);
    }

    private void look(Vec3 at) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        if (HUMANIZE) {
            // a hand on a mouse never tracks perfectly: a slow wander around the aim point, bigger when the target moves fast
            double energy = 0.5 + Math.min(1.0, target == null ? 0 : tv().horizontalDistance() * 3);
            wanderVy = (wanderVy + rng.nextGaussian() * 0.12 * energy) * 0.82;
            wanderVp = (wanderVp + rng.nextGaussian() * 0.07 * energy) * 0.82;
            wanderY = Mth.clamp((wanderY + wanderVy) * 0.96, -1.8, 1.8);
            wanderP = Mth.clamp((wanderP + wanderVp) * 0.96, -1.0, 1.0);
            r = new Rotation(r.getYaw() + (float) wanderY, Mth.clamp(r.getPitch() + (float) wanderP, -90f, 90f));
        }
        aim(r, true);
    }

    /** With several opponents in reach, finish the weakest one rather than whichever was nearest first. */
    private void retarget(Player me) {
        java.util.function.ToDoubleFunction<LivingEntity> score = e -> e.getHealth() + e.getAbsorptionAmount() + 0.6 * me.distanceTo(e);
        LivingEntity best = ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(7),
                        e -> e != me && e.isAlive() && !e.isRemoved() && matches(e))
                .stream().min(Comparator.comparingDouble(score)).orElse(null);
        if (best != null && best != target && score.applyAsDouble(best) < score.applyAsDouble(target) - 3) target = best;
    }

    /** Recorder tag: opponents within 12 blocks as "n<count>:<nearest-other dist>". */
    private String others(Player me) {
        double near = 99;
        int n = 0;
        for (LivingEntity e : ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(12), x -> x != me && x != target && x.isAlive() && matches(x))) {
            n++;
            near = Math.min(near, me.distanceTo(e));
        }
        return n == 0 ? "n0" : String.format("n%d:%.1f", n, near);
    }

    private LivingEntity pick(Player me) {
        return ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(CHASE),
                        e -> e != me && e.isAlive() && !e.isRemoved() && matches(e))
                .stream().filter(e -> me.distanceTo(e) <= CHASE)
                .min(Comparator.comparingDouble(me::distanceToSqr)).orElse(null);
    }

    /**
     * Where a sword or axe looks. The nearest point of the hitbox is its edge whenever we stand off-axis,
     * and the look that arrives is a tick old: 223541 expert sat 6-8 degrees behind the edge and sent no
     * click on 8 charged ticks. Aim at the middle of the box, led by one tick of both players' motion,
     * and only slide toward the near edge when the middle is past reach.
     */
    private Vec3 swingPoint(Player me, LivingEntity t) {
        Vec3 near = aimPoint(me, t);
        Vec3 eye = me.getEyePosition();
        Vec3 mid = t.getBoundingBox().getCenter();
        Vec3 lead = tv().subtract(me.getDeltaMovement()).multiply(1, 0, 1);
        for (double f : new double[]{1.0, 0.6, 0.3}) {
            Vec3 p = new Vec3(Mth.lerp(f, near.x, mid.x), near.y, Mth.lerp(f, near.z, mid.z));
            Vec3 in = t.getBoundingBox().clip(eye, p).orElse(p);
            if (eye.distanceTo(in) <= REACH - 0.04 || eye.distanceTo(near) > REACH) return p.add(lead);
        }
        return near.add(lead);
    }

    private static Vec3 aimPoint(Player me, LivingEntity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox().deflate(0.05);
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY + 0.2, b.maxY - 0.1), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    /**
     * Crystal PvP: break the crystal that hurts the target most, else put a crystal on obsidian where it does,
     * else lay obsidian beside the target's feet. Anything that would hurt us more than it, or pop us, is skipped.
     */
    private boolean crystal(Player me) {
        crystalFight = slotOf(me, Items.END_CRYSTAL) >= 0 || slotOf(me, Items.RESPAWN_ANCHOR) >= 0 || !ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8)).isEmpty();
        if (anchor(me)) return true;
        if (slotOf(me, Items.END_CRYSTAL) < 0 || me.distanceTo(target) > 7) return false;
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        EndCrystal hitIt = null;
        float best = 0;
        for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
            if (exactReach(me, c) > REACH) continue;
            float score = worth(me, c.position(), myHp);
            if (score > best) {
                best = score;
                hitIt = c;
            }
        }
        if (hitIt == null && slotOf(me, Items.OBSIDIAN) >= 0) {
            // a crystal that would hurt us and that we won't pop: wall it off at leg height
            EndCrystal danger = null;
            float worst = 6;
            for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
                float d = blast(me, c.position(), 12);
                if (d >= worst) { worst = d; danger = c; }
            }
            if (danger != null && shield(me, danger.blockPosition())) return true;
        }
        if (hitIt != null) {
            look(hitIt.position());
            if (aimedAt(me, hitIt.getBoundingBox().getCenter(), 6f)) hit(me, hitIt);
            return true;
        }
        Level w = ctx.world();
        BlockPos base = null;
        best = 0;
        BlockPos t = target.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
            if (!w.getBlockState(p).is(Blocks.OBSIDIAN) && !w.getBlockState(p).is(Blocks.BEDROCK)) continue;
            if (!w.isEmptyBlock(p.above()) || !w.getEntities(null, new AABB(p.above())).isEmpty()) continue;
            Vec3 at = Vec3.atBottomCenterOf(p.above());
            if (me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5 || me.getEyePosition().distanceTo(at.add(0, 1, 0)) > REACH + 0.8) continue;
            float score = worth(me, at, myHp);
            if (score > best) {
                best = score;
                base = p.immutable();
            }
        }
        if (base != null) return place(me, Items.END_CRYSTAL, base);
        if (slotOf(me, Items.OBSIDIAN) < 0) return false;
        BlockPos floor = null;
        best = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (BlockPos p : new BlockPos[]{t.relative(d), t.relative(d).below()}) {
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atBottomCenterOf(p.above()), myHp);
                if (score > best) {
                    best = score;
                    floor = p.below();
                }
            }
        }
        return floor != null && place(me, Items.OBSIDIAN, floor);
    }

    /**
     * Anchor PvP (overworld): blow a charged anchor that hurts the target, else charge an anchor near it with
     * glowstone, else put an anchor down beside its feet.
     */
    private boolean anchor(Player me) {
        if (slotOf(me, Items.RESPAWN_ANCHOR) < 0 && slotOf(me, Items.GLOWSTONE) < 0 || me.distanceTo(target) > 7) return false;
        Level w = ctx.world();
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        BlockPos t = target.blockPosition(), boom = null, charge = null, backOff = null;
        float bestBoom = 0, bestCharge = 0, bestBack = 0;
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -1, -3), t.offset(3, 2, 3))) {
            if (!w.getBlockState(p).is(Blocks.RESPAWN_ANCHOR) || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
            Vec3 at = Vec3.atCenterOf(p);
            float score = worth(me, at, myHp, 10), dmg = blast(target, at, 10);
            boolean charged = w.getBlockState(p).getValue(RespawnAnchorBlock.CHARGE) > 0;
            if (!charged && score > 0) {
                // charging hurts nobody, so charge anything that would hurt the target
                if (dmg > bestCharge) { bestCharge = dmg; charge = p.immutable(); }
            } else if (charged && score > bestBoom) {
                bestBoom = score;
                boom = p.immutable();
            } else if (score <= 0 && blast(me, at, 10) >= 8 && blast(me, at, 10) > bestBack) {
                bestBack = blast(me, at, 10); // theirs or ours, it can go off in our face
                backOff = p.immutable();
            }
        }
        if (boom != null) {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                Item it = me.getInventory().getItem(i).getItem();
                if (it != Items.GLOWSTONE && it != Items.RESPAWN_ANCHOR) { slot = i; break; }
            }
            if (slot < 0) return false;
            if (!select(me, slot)) return true;
            return click(me, boom);
        }
        if (charge != null && slotOf(me, Items.GLOWSTONE) >= 0) {
            if (!select(me, slotOf(me, Items.GLOWSTONE))) return true;
            return me.getMainHandItem().getItem() == Items.GLOWSTONE && click(me, charge);
        }
        // a charged anchor that would hurt us: wall it off at leg height, which is where most of the blast lands
        if (backOff != null && shield(me, backOff)) return true;
        backingOff = backOff == null ? 0 : backingOff + 1;
        // a charged anchor that would hurt us too much from here: step away, then blow it (unless a wall keeps us pinned)
        if (backOff != null && backingOff < 40) {
            look(Vec3.atCenterOf(backOff));
            key(Input.MOVE_BACK);
            return true;
        }
        if (slotOf(me, Items.RESPAWN_ANCHOR) < 0 || slotOf(me, Items.GLOWSTONE) < 0) return false;
        BlockPos spot = null;
        float best = 0;
        // not just beside them: a target down a one-wide hole has no free side, only the rim
        for (BlockPos q : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
            {
                BlockPos p = q.immutable();
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atCenterOf(p), myHp, 10);
                if (score > best) { best = score; spot = p; }
            }
        }
        return spot != null && place(me, Items.RESPAWN_ANCHOR, spot.below());
    }

    /** Put a block in the cell between our feet and {@code threat} so the explosion's rays hit it instead of our legs. */
    private boolean shield(Player me, BlockPos threat) {
        Item block = slotOf(me, Items.OBSIDIAN) >= 0 ? Items.OBSIDIAN : slotOf(me, Items.COBBLESTONE) >= 0 ? Items.COBBLESTONE
                : slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? Items.RESPAWN_ANCHOR : null;
        if (block == null) return false;
        BlockPos feet = me.blockPosition();
        int dx = Integer.signum(threat.getX() - feet.getX()), dz = Integer.signum(threat.getZ() - feet.getZ());
        Level w = ctx.world();
        for (BlockPos c : new BlockPos[]{feet.offset(dx, 0, dz), feet.offset(dx, 0, 0), feet.offset(0, 0, dz)}) {
            if (c.equals(feet) || c.equals(threat) || !w.getBlockState(c).canBeReplaced() || w.getBlockState(c.below()).canBeReplaced()) continue;
            if (!w.getEntities(null, new AABB(c)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(c)) > 4.5) continue;
            return place(me, block, c.below());
        }
        return false;
    }

    /** Right-click the top face of a block with whatever is in hand. */
    private boolean click(Player me, BlockPos on) {
        Vec3 face = Vec3.atCenterOf(on).add(0, 0.5, 0);
        look(face);
        if (ctx.minecraft().hitResult instanceof BlockHitResult b && b.getBlockPos().equals(on)) press(ctx.minecraft().options.keyUse);
        return true;
    }

    /** Right-click the top of {@code on} with {@code item}. */
    private boolean place(Player me, Item item, BlockPos on) {
        if (!select(me, slotOf(me, item))) return true;
        if (me.getMainHandItem().getItem() != item) return false;
        return click(me, on);
    }

    /** How good a crystal blowing up at {@code at} is for us: its damage to the target minus ours, 0 if not worth it. */
    private float worth(Player me, Vec3 at, float myHp) {
        return worth(me, at, myHp, 12);
    }

    private float worth(Player me, Vec3 at, float myHp, double size) {
        float dmg = blast(target, at, size), self = blast(me, at, size);
        boolean totem = me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        if (self >= myHp - (totem ? 0 : 2) && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0;
        // once they're low an even trade wins the race
        if (dmg < 3 || dmg < self * (size == 10 ? (target.getHealth() + target.getAbsorptionAmount() > 10 ? 1.5f : 1) : (target.getHealth() + target.getAbsorptionAmount() <= 10 ? 0.8f : 1))) return 0;
        if (size == 10 && self >= myHp - 4 && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0; // don't pop our own totem
        return dmg - self * 0.6f;
    }

    /** Vanilla end crystal (power 6) damage to {@code e} after armour. */
    private static float blast(LivingEntity e, Vec3 at, double size) {
        double d = Math.sqrt(e.distanceToSqr(at)) / size;
        if (d > 1) return 0;
        // an anchor is removed before it blows, but would block its own rays here, so take it as fully exposed
        double impact = (1 - d) * (size == 12 ? ServerExplosion.getSeenPercent(at, e) : 1);
        float raw = (float) ((impact * impact + impact) / 2 * 7 * size + 1);
        return CombatRules.getDamageAfterAbsorb(e, raw, e.damageSources().generic(), e.getArmorValue(), (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    /**
     * Spear kit, and only when a jab is not available.
     * A straight-down wind charge is a hop: a mace smash just outside the jab band, or a shove out of the dead zone.
     * It is not aimed past the target and it is not used to walk in.
     */
    private PathingCommand spearTools(Player me, double dist, boolean los, int wind, int mace) {
        double hr = horizontalBoxDist(me, target);
        // A hop only just outside the jab, and not again for 15s. Repeating it kept the fight
        // out of the band (easy/medium timed out with one swing). Farther than 4: walk in.
        if (mace >= 0 && wind >= 0 && maceCool == 0 && me.onGround() && !me.isInWater() && los
                && target.onGround() && hr > SPEAR_JAB_HI && hr <= 4.0 && target.getY() <= me.getY() + 1.5) {
            // Phase 1 charges the mace on the ground, then throws. Do not swap to the wind charge here.
            macePhase = 1;
            maceTicks = 0;
            maceCool = 300;
            return decide("mace");
        }
        return null;
    }

    private Vec3 kineticPos;

    /**
     * Blocks/second along the look vector, from last tick's position change.
     * getDeltaMovement() stays near 0.15 (about 3 blocks/s) while a sprint actually covers about 0.27.
     * The server charge check uses that position delta, so the 4.6 gate never opened.
     */
    private double kineticAlong(Player me) {
        Vec3 pos = me.position();
        Vec3 delta = kineticPos == null ? Vec3.ZERO : pos.subtract(kineticPos);
        kineticPos = pos;
        if (delta.lengthSqr() > 1.0) delta = me.getDeltaMovement();
        return me.getLookAngle().dot(delta) * 20.0;
    }

    /** Horizontal distance from the eye to the target hitbox. Ignores a mace hop's vertical gap. */
    private static double horizontalBoxDist(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        double cx = Mth.clamp(eye.x, b.minX, b.maxX);
        double cz = Mth.clamp(eye.z, b.minZ, b.maxZ);
        return Math.hypot(eye.x - cx, eye.z - cz);
    }

    /**
     * The held spear's piercing ray hits the target inside the jab band, and the jab is fully charged.
     * Vanilla rejects a spear attack below minimum_attack_charge (1.0) and misses anything the ray misses.
     */
    private boolean spearRayHits(Player me) {
        ItemStack st = me.getMainHandItem();
        if (!isSpear(st) || me.cannotAttackWithItem(st, 0)) return false;
        net.minecraft.world.item.component.AttackRange range = me.entityAttackRange();
        net.minecraft.world.phys.HitResult hit = range.getClosesetHit(me, 1.0f, e -> e == target);
        if (!(hit instanceof net.minecraft.world.phys.EntityHitResult er) || er.getEntity() != target) return false;
        double along = me.getEyePosition().distanceTo(er.getLocation());
        return along >= SPEAR_JAB_LO && along <= SPEAR_JAB_HI;
    }

    private static double exactReach(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ)));
    }

    private static double eyeToBox(Player me, LivingEntity t) {
        return me.getEyePosition().distanceTo(aimPoint(me, t));
    }

    @Override
    public void onLostControl() {
        recorder.end(ctx.player(), "lost");
        filter = null;
        enemies.clear();
        target = null;
        eatTicks = blockTicks = duelOpenUntil = targetSwingTick = 0;
        axeHeld = flicked = false;
        swingGap = unseenBlock = probeTick = 0;
        lastShieldTick = lastAxeTick = -1000; // tickCount restarts with the respawned player
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
