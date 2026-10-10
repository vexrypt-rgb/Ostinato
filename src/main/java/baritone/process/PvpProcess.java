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

    private static final double CHASE = 48;

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
    /** The hit being watched. */
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
        // Bench respawn drops the kit before the kit is re-equipped. Do not swing naked.
        if (Integer.getInteger("ostinato.vexbench", 0) > 0 && (respawned
                || me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty())) {
            if (recorder.active()) recorder.end(me, respawned ? "death" : "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        LivingEntity prevTarget = target;
        if (target == null || !target.isAlive() || target.isRemoved() || me.distanceTo(target) > CHASE) target = targeting.pick(me, CHASE);
        if (target != prevTarget) {
            spears.retarget();
            spears.spearReleaseNext = false;
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
            boolean charging = inv.spearSlot(me) >= 0 && spears.spearUseTicks > 0;
            PathingCommand ate = survival.tend(me, target, targetEating, overhead, safe, charging, me.tickCount - shield.targetSwingTick, shield.swingGap);
            if (ate != null) return ate;

            double dist = eyeToBox(me, target);
            boolean los = me.hasLineOfSight(target);

            if (phase.pearlCool > 0) phase.pearlCool--;
            tools.coolFire();
            if (spears.spearCool > 0) spears.spearCool--;
            if (hp <= 6 && !survival.canHeal(me) && target.getHealth() + target.getAbsorptionAmount() > 6 && !targetEating) {
                return decide("flee", survival.flee(me, target, dist));
            }

            // A spear that raises its shield here never steps into the 2-4 jab band.
            // 1654 bench: a mace's 33-tick cooldown kept the shield up for 405 of 1800 ticks, and
            // the hop that makes the damage starts from the ground. A mace with wind charges hops.
            boolean maceHop = inv.slotOf(me, Items.MACE) >= 0 && inv.slotOf(me, Items.WIND_CHARGE) >= 0;
            if (me.tickCount < lastSeenTick) { // respawned between bench rounds: tickCount restarted under the old stamps
                duelOpenUntil = click.probeTick = 0;
                shield.respawned();
            }
            lastSeenTick = me.tickCount;
            shield.observe(me, target);
            // a click that connected and left it unhurt met a shield the client was never shown:
            // remember how long after its swing that was and keep the sword out of that window
            click.settle(me, target);
            boolean blockMelee = inv.spearSlot(me) < 0 && !maceHop && shield.meleeBlock(me, target, dist);
            PathingCommand clutched = winds.clutch(me, target);
            if (clutched != null) return clutched;
            PathingCommand flared = elytra.flare(me);
            if (flared != null) return flared;
            PathingCommand dive = shield.underDive(me, target, dist, los);
            if (dive != null) return dive;
            PathingCommand guarded = shield.guard(me, target, dist, blockMelee);
            if (guarded != null) return guarded;

            // crystals and anchors reach further than a sword, and blowing them is also how we clear a wall of them
            if (explosives.crystal(me, target)) {
                if (dist <= DRIVE) movement.steer(me, target, dist, chase, melee.critArmed);
                return decide("crystal");
            }
            Vec3 bomb = explosives.hazard(me);
            if (bomb != null) { // never stand beside a live explosive: run straight away from it
                Vec3 away = me.position().subtract(bomb).multiply(1, 0, 1);
                if (away.lengthSqr() < 1.0e-4) away = me.position().subtract(target.position()).multiply(1, 0, 1);
                look(me.getEyePosition().add(away.normalize().scale(5)));
                key(Input.MOVE_FORWARD);
                key(Input.SPRINT);
                return decide("clear");
            }
            PathingCommand sp = special(me, dist, los);
            if (sp != null) {
                if ("-".equals(tickDec)) tickDec = "special";
                return sp;
            }
            PathingCommand closing = approach.close(me, target, dist, los, spears.spearUseCool);
            if (closing != null) return closing;
            // A spear charge is the use key. Releasing here every tick reset the 10-tick delay.
            if (me.isUsingItem() && spears.spearUseTicks == 0) use(false);

            int spear = inv.spearSlot(me);
            // Spear jabs from farther than a sword; never jab inside SPEAR_MIN (vanilla spear dead zone).
            double reach = spear >= 0 ? SPEAR_REACH : REACH;
            double er = exactReach(me, target);
            // Movement uses horizontal distance to the hitbox. A mace hop makes the 3D eye distance
            // jump to ~5 while we are already in the jab band, which was the close/back oscillation.
            double hr = horizontalBoxDist(me, target);
            spears.observe(me, spear, hr);
            // Jab only in the middle of the 2-4 band. Outer edge (~4) misses; under SPEAR_MIN cannot connect.
            boolean inReach = spear >= 0
                    ? (hr >= SPEAR_JAB_LO && hr <= SPEAR_JAB_HI)
                    : (er <= reach - 0.05);
            boolean meFalling = !me.onGround() && me.getDeltaMovement().y < -0.05;
            boolean targetFalling = !target.onGround() && tv().y < -0.05;
            PathingCommand jabbed = spears.jab(me, target, los, spear, hr, targetEating, inReach, meFalling, targetFalling);
            if (jabbed != null) return jabbed;

            PathingCommand ready = melee.prepare(me, target, inReach, chase, targetEating);
            if (ready != null) return ready;

            // Plain spear: commit to closing or backing until settled inside 2-4, then hold and jab.
            // No sprint near the band, so one tick cannot cross it. Lunge only with the enchant.
            if (spear >= 0) return spears.pass(me, target, dist, los, spear, hr, er, targetEating);
            return melee.exchange(me, target, dist);
        } finally {
            recorder.tick(me, target, eyeToBox(me, target),
                    targeting.others(me, target) + " m" + phase.macePhase + " p" + phase.pearlStage + " f" + survival.fleeTicks + " e" + survival.eatTicks + " s" + me.getInventory().getSelectedSlot()
                            + (ctx.minecraft().screen != null ? " scr=" + ctx.minecraft().screen.getClass().getSimpleName() : "")
                            + (me.getCooldowns().isOnCooldown(me.getOffhandItem()) ? " offcd" : "")
                            + (ctx.minecraft().options.keyUse.isDown() ? " use" : "") + " k" + click.clickKind,
                    tickDec + brokeNote, attacks);
            click.clickKind = '-';
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
        public void abortCharge() { spears.abortCharge(); }
    });
    private final CombatPolicy policy = new CombatPolicy();
    private final CombatShield shield = new CombatShield(ctx, inv, aimer, targeting, defense, phase, policy.shield, new CombatShield.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int eatTicks() { return survival.eatTicks; }
        public void blockStarted() { blocks++; }
        public void dodge(Player me) { movement.dodgeRanged(me); }
    });
    private final CombatClick click = new CombatClick(ctx.minecraft(), aimer, swing, targeting, shield, new CombatClick.Hands() {
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void attacked() { attacks++; }
    });
    private final CombatMelee melee = new CombatMelee(inv, swing, targeting, shield, movement, new CombatMelee.Hands() {
        public void key(Input in) { PvpProcess.this.key(in); }
        public boolean hit(Player me) { return PvpProcess.this.hit(me); }
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public void axeHit() { axeHits++; }
        public void crit() { crits++; }
        public void sprintHit() { sprintHits++; }
    });
    private final CombatSpear spears = new CombatSpear(inv, aimer, swing, policy.spear, new CombatSpear.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public boolean hit(Player me) { return PvpProcess.this.hit(me); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int eatTicks() { return survival.eatTicks; }
        public void note(String line) { recorder.note(line); }
    });
    private final CombatApproach approach = new CombatApproach(ctx, inv, tools, phase, new CombatApproach.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void use(boolean down) { PvpProcess.this.use(down); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public PathingCommand decide(String d, PathingCommand cmd) { return PvpProcess.this.decide(d, cmd); }
        public int eatTicks() { return survival.eatTicks; }
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
    private final CombatMace maces = new CombatMace(ctx, inv, aimer, targeting, phase, policy.mace, new CombatMace.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public void look(Vec3 at) { PvpProcess.this.look(at); }
        public void key(Input in) { PvpProcess.this.key(in); }
        public boolean hit(Player me) { return PvpProcess.this.hit(me); }
        public boolean shieldDive(Player me, double dist) { return shield.shieldDive(me, target, dist); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
        public int lastAxe() { return shield.lastAxeTick; }
        public void axeHit(int tick) { axeHits++; shield.lastAxeTick = tick; }
        public int spearCool() { return spears.spearCool; }
        public void spearCool(int ticks) { spears.spearCool = ticks; }
    });
    private final CombatWind winds = new CombatWind(ctx, inv, aimer, targeting, phase, new CombatWind.Hands() {
        public boolean select(Player me, int slot) { return PvpProcess.this.select(me, slot); }
        public PathingCommand decide(String d) { return PvpProcess.this.decide(d); }
    });
    private Vec3 tv() {
        return targeting.velocity(target);
    }

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
            if (spears.spearUseTicks == 0 && shield.shieldDive(me, target, dist)) return decide("block");
            // Do not hop in the middle of a charge approach. The hop was the whole fight and use never started.
            boolean foeEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
            PathingCommand tool = spears.spearUseTicks == 0 && !spears.spearReopen && !spears.spearCommit && !foeEating && !(spears.spearUseCool == 0 && horizontalBoxDist(me, target) > 4.6)
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

    private String brokeNote = "";

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
    private boolean hit(Player me, Entity e) { return click.clickOn(me, e); }

    private boolean hit(Player me) { return click.hit(me, target); }

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
        survival.eatTicks = survival.foodTicks = shield.blockTicks = duelOpenUntil = shield.targetSwingTick = 0;
        shield.axeHeld = shield.flicked = false;
        shield.swingGap = shield.unseenBlock = click.probeTick = 0;
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
