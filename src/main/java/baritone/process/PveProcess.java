package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;

/**
 * Fighting mobs. A mob is not a player: it has no hit select to read and no strafe to out-guess. It walks straight
 * at us and swings on its own cooldown, so the work is positional. Swing only on a full charge, step back during the
 * recharge so the mob's own swing finds air, never stand beside a lit creeper, shield against arrows, close on
 * shooters crookedly, and leave what is not worth the trade. {@link PvpProcess} stays for duels.
 */
public final class PveProcess extends BaritoneProcessHelper {

    private static final double SCAN = 24, DRIVE_PVE = 10;
    /** Attack strength a swing waits for; below it the hit is a fraction of the damage and resets the charge. */
    private static final float CHARGED = 0.92f;

    private Predicate<LivingEntity> filter;
    /** Mobs marked by hand; fought as well as whatever the filter matches. */
    private final Set<UUID> marked = new HashSet<>();
    private String label = "";
    private LivingEntity target;
    private final Random rng = new Random(11);
    private final CombatAim aimer = new CombatAim(baritone, ctx, rng);
    private final CombatInventory inv = new CombatInventory(ctx);
    private final CombatSwing swing = new CombatSwing();
    private final CombatMovement movement = new CombatMovement(ctx, rng, this::key);

    private int eatTicks, blockTicks, retreatTicks, backoff;
    private boolean shooterClose;
    private int weave;
    /** Mobs we cannot see or reach for a long time (burrowed, walled off, in a hole) are left alone for a while. */
    private final Map<UUID, Long> ignoreUntil = new HashMap<>();
    private final Map<UUID, Integer> blind = new HashMap<>();
    private float lastHealth = -1;
    private final Map<UUID, LivingEntity> engaged = new HashMap<>();
    private String tickDec = "-";

    public int attacks, kills, hurtEvents, blocks, meals;
    public float damageTaken;
    private long ticks;

    public PveProcess(Baritone baritone) {
        super(baritone);
    }

    /** Fight what matches; always ANDed with "alive, not us, not a player". */
    public void attack(Predicate<LivingEntity> filter, String label) {
        this.filter = filter;
        this.label = label;
        target = null;
        lastHealth = -1;
        attacks = kills = hurtEvents = blocks = meals = 0;
        damageTaken = 0;
        ticks = 0;
        marked.clear();
        ignoreUntil.clear();
        blind.clear();
        engaged.clear();
    }

    public void attackHostiles() {
        attack(MobProfile::hostile, "hostiles");
    }

    /** One entity type id, e.g. {@code zombie} or {@code minecraft:creeper}. */
    public void attackType(String id) {
        String want = id.contains(":") ? id : "minecraft:" + id;
        attack(e -> net.minecraft.world.entity.EntityType.getKey(e.getType()).toString().equals(want), id);
    }

    /** Marked by hand (freecam). Players are PvP's; this takes mobs only. */
    public boolean addEnemy(LivingEntity e) {
        if (e == null || e instanceof Player || ctx.player() == null) return false;
        if (!isActive()) attack(x -> false, "marked");
        marked.add(e.getUUID());
        return true;
    }

    public void clearEnemies() {
        filter = null;
        marked.clear();
        target = null;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public String stats() {
        return String.format("attacks=%d kills=%d hurt=%d blocks=%d meals=%d dmgTaken=%.1f ticks=%d", attacks, kills, hurtEvents, blocks, meals, damageTaken, ticks);
    }

    public String lastDecision() {
        return tickDec;
    }

    @Override
    public boolean isActive() {
        return filter != null && ctx.player() != null;
    }

    private boolean matches(LivingEntity e) {
        return e != ctx.player() && e.isAlive() && !e.isRemoved() && !(e instanceof Player) && filter != null
                && (marked.contains(e.getUUID()) || filter.test(e));
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Player me = ctx.player();
        inv.closeAbandoned(me);
        baritone.getInputOverrideHandler().clearAllKeys();
        ticks++;
        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) {
            damageTaken += lastHealth - hp;
            hurtEvents++;
        }
        lastHealth = hp;
        tickDec = "-";

        List<LivingEntity> all = ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(SCAN), this::matches);
        for (LivingEntity e : all) if (me.distanceTo(e) < 8) engaged.put(e.getUUID(), e);
        engaged.values().removeIf(e -> {
            // a mob that unloads or despawns is gone, not killed
            if (e.isDeadOrDying()) {
                kills++;
                return true;
            }
            return e.isRemoved() || me.distanceTo(e) > SCAN + 8;
        });
        if (ticks % 200 == 0) {
            ignoreUntil.values().removeIf(until -> until <= ticks);
            Set<UUID> near = new HashSet<>();
            for (LivingEntity e : all) near.add(e.getUUID());
            blind.keySet().retainAll(near);
        }
        if (all.isEmpty()) {
            target = null;
            use(false);
            // nothing left to fight: top up if hurt
            if (hp < 14 && eatFood(me, null)) return decide("eat");
            eatTicks = 0;
            return decide("idle");
        }

        // who matters most: a creeper at 4 blocks beats a zombie at 3; the weak die first among equals
        LivingEntity pick = null;
        double bestScore = Double.MAX_VALUE;
        int adjacent = 0;
        Vec3 crowd = Vec3.ZERO;
        for (LivingEntity e : all) {
            if (ignoreUntil.getOrDefault(e.getUUID(), 0L) > ticks) continue;
            double d = me.distanceTo(e);
            MobProfile p = MobProfile.of(e);
            if (d < 4.5 && p.kind != MobProfile.Kind.RANGED) {
                adjacent++;
                crowd = crowd.add(e.position());
            }
            double score = d - p.threat * 0.25 + e.getHealth() / 20.0 + (p.kind == MobProfile.Kind.AVOID ? 6 : 0);
            if (score < bestScore) {
                bestScore = score;
                pick = e;
            }
        }
        if (pick == null) {
            target = null;
            use(false);
            return decide("idle");
        }
        target = pick;
        MobProfile prof = MobProfile.of(target);
        double hd = horizontalBoxDist(me, target);
        boolean los = me.hasLineOfSight(target);
        if (los || hd < 2.5) {
            blind.remove(target.getUUID());
        } else if (blind.merge(target.getUUID(), 1, Integer::sum) > 160) {
            blind.remove(target.getUUID());
            ignoreUntil.put(target.getUUID(), ticks + 600);
            return decide("idle");
        }
        boolean canHeal = healItem(me) >= 0 || goldenApple(me) >= 0;

        keepTotem(me);

        // 1. Leave what is not worth it, and leave when hurt with nothing to heal.
        if (prof.kind == MobProfile.Kind.AVOID && hd < 24 || hp <= 6 && !canHeal && hd < 20) {
            use(false);
            return decide("flee", runFrom(all));
        }

        // 2. A bomb in its fuse: nothing matters but distance.
        if (prof.kind == MobProfile.Kind.BOMB && target instanceof net.minecraft.world.entity.monster.Creeper cr
                && (cr.getSwelling(1f) > 0 || cr.isIgnited()) && hd < 7.5) {
            retreatTicks = 6;
        }
        if (retreatTicks > 0) {
            retreatTicks--;
            use(false);
            awayFrom(me, target.position());
            key(Input.MOVE_FORWARD);
            key(Input.SPRINT);
            return decide("clear");
        }

        // 3. Heal when nothing is on top of us.
        // not in the open while something is shooting at us: eating stands still and a drawn bow keeps its aim
        boolean shot = all.stream().anyMatch(e -> MobProfile.of(e).kind == MobProfile.Kind.RANGED && me.hasLineOfSight(e));
        if (hp <= 10 && adjacent == 0 && hd > 6 && !shot && canHeal && eatFood(me, target)) return decide("eat");
        if (eatTicks > 0) {
            use(false);
            eatTicks = 0;
        }

        // 4. A crowd: do not stand in the middle of it.
        if (adjacent >= 3 && hp < 14) {
            use(false);
            awayFrom(me, crowd.scale(1.0 / adjacent));
            key(Input.MOVE_FORWARD);
            key(Input.SPRINT);
            if (me.onGround() && rng.nextInt(8) == 0) key(Input.JUMP);
            return decide("crowd");
        }

        // 5. Shooters: shield against a drawn bow, otherwise close crookedly.
        // Once it is inside sword range we stay on it: a shooter we back away from only gets free shots.
        if (prof.kind == MobProfile.Kind.RANGED && hd > (shooterClose ? 4.5 : 3.0)) {
            shooterClose = false;
            // the draw is not always visible from the client, so a bow in its hand and a clear line is enough
            boolean armed = target.isUsingItem() || target.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                    || target.getMainHandItem().getItem() instanceof net.minecraft.world.item.CrossbowItem
                    || target instanceof net.minecraft.world.entity.monster.Blaze || target instanceof net.minecraft.world.entity.monster.Ghast;
            // sprinting and the shield do not mix, and a shooter that is not closed on just keeps shooting:
            // charge it, weaving across its aim; the shield is only for when we are hurt and cannot get there
            boolean hurt = me.getHealth() <= 8;
            if (armed && los && hd > 5 && hurt && hd <= DRIVE_PVE + 6 && shieldUp(me, target)) {
                movement.dodgeRanged(me);
                return decide("block");
            }
            use(false);
            blockTicks = 0;
            if (target instanceof net.minecraft.world.entity.monster.Ghast) {
                // a fireball in flight is struck back; a mouse click on whatever the crosshair is on
                look(target.getBoundingBox().getCenter());
                if (ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult er && er.getEntity() != target
                        && er.getEntity() instanceof net.minecraft.world.entity.projectile.Fireball) {
                    press(ctx.minecraft().options.keyAttack);
                }
            }
            if (!los || hd > DRIVE_PVE) {
                return decide("path", new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
            }
            look(target.getBoundingBox().getCenter());
            key(Input.MOVE_FORWARD);
            key(Input.SPRINT);
            weave++;
            key((weave / 12) % 2 == 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT);
            if (me.onGround() && rng.nextInt(10) == 0) key(Input.JUMP);
            return decide("dodge");
        }
        blockTicks = 0;
        shooterClose = prof.kind == MobProfile.Kind.RANGED;

        // 6. Far or out of sight: walk there.
        if (hd > DRIVE_PVE || !los && hd > 2.5) {
            use(false);
            return decide("path", new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
        }

        // 7. Melee range.
        int weapon = inv.weapon(me);
        if (weapon >= 0) select(me, weapon);
        use(false);
        boolean low = prof.kind == MobProfile.Kind.BOMB || target instanceof net.minecraft.world.entity.monster.EnderMan;
        look(low ? new Vec3(target.getX(), target.getY() + 0.3, target.getZ()) : swing.swingPoint(me, target, target.getDeltaMovement()));
        double er = exactReach(me, target);
        float charge = me.getAttackStrengthScale(0.5f);

        if (er <= REACH - 0.05 && charge >= CHARGED && ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult hr && hr.getEntity() == target) {
            press(ctx.minecraft().options.keyAttack);
            attacks++;
            backoff = prof.kind == MobProfile.Kind.RANGED ? 0 : prof.kind == MobProfile.Kind.BOMB ? 8 : 4 + (adjacent > 1 ? 3 : 0);
            return decide("hit");
        }
        // recharging: step back so its swing finds air, then close as the charge fills
        boolean recharging = charge < CHARGED;
        // a hard hitter in our face: the recharge is spent behind the shield rather than stepping back into its reach
        if (recharging && hd < 3.5 && prof.kind == MobProfile.Kind.MELEE && prof.threat >= 5 && !(target instanceof net.minecraft.world.entity.monster.EnderMan) && shieldUp(me, target)) {
            return decide("guard");
        }
        if (backoff > 0 && recharging && hd < 3.0) {
            backoff--;
            key(Input.MOVE_BACK);
            return decide("back");
        }
        backoff = 0;
        if (er > REACH - 0.4 || !recharging) {
            key(Input.MOVE_FORWARD);
            if (me.getFoodData().getFoodLevel() > 6) key(Input.SPRINT);
            if (hd > 3.5 && me.onGround() && me.isSprinting() && !me.isInWater() && rng.nextInt(3) == 0) key(Input.JUMP);
            return decide("close");
        }
        return decide("wait");
    }

    private PathingCommand runFrom(List<LivingEntity> all) {
        net.minecraft.core.BlockPos[] from = all.stream().map(LivingEntity::blockPosition).toArray(net.minecraft.core.BlockPos[]::new);
        return new PathingCommand(new GoalRunAway(22, from), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /** Face directly away from the point; the caller holds forward. */
    private void awayFrom(Player me, Vec3 from) {
        Vec3 away = me.position().subtract(from).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
        look(me.getEyePosition().add(away.normalize().scale(6)));
    }

    /** Hold the offhand shield toward the mob, dropping it for a tick every so often. False without a shield. */
    private boolean shieldUp(Player me, LivingEntity from) {
        if (me.getOffhandItem().getItem() != Items.SHIELD) return false;
        look(from.getEyePosition());
        if (blockTicks++ == 0) blocks++;
        use(true);
        if (blockTicks > 60) {
            blockTicks = 0;
            use(false);
        }
        return true;
    }

    /** Inventory index (0-35) of ordinary food that is worth eating, or -1. */
    private int healItem(Player me) {
        for (int i = 0; i < 36; i++) {
            var st = me.getInventory().getItem(i);
            if (st.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
            Item it = st.getItem();
            if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE || it == Items.ROTTEN_FLESH || it == Items.SPIDER_EYE
                    || it == Items.PUFFERFISH || it == Items.POISONOUS_POTATO || it == Items.CHORUS_FRUIT || it == Items.SUSPICIOUS_STEW) continue;
            return i;
        }
        return -1;
    }

    private int goldenApple(Player me) {
        for (int i = 0; i < 36; i++) {
            Item it = me.getInventory().getItem(i).getItem();
            if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE) return i;
        }
        return -1;
    }

    /** Eat: golden apples when low, plain food when hungry. False when there is nothing to eat or no need. */
    private boolean eatFood(Player me, LivingEntity watching) {
        int slot = -1;
        if (me.getHealth() <= 8) {
            int g = goldenApple(me);
            if (g >= 0) slot = g;
        }
        if (slot < 0 && me.getFoodData().needsFood()) {
            int i = healItem(me);
            if (i >= 0) slot = i;
        }
        if (slot < 0) return false;
        if (!select(me, slot)) return true;
        if (me.isUsingItem() && me.getUseItem() != me.getMainHandItem()) {
            use(false);
            return true;
        }
        if (eatTicks++ == 0) meals++;
        use(true);
        if (watching != null) look(watching.getEyePosition());
        key(Input.MOVE_BACK);
        if (eatTicks > 36) {
            use(false);
            eatTicks = 0;
        }
        return true;
    }

    private void keepTotem(Player me) {
        if (me.getHealth() > 8 || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        inv.toOffhand(me, Items.TOTEM_OF_UNDYING);
    }

    private boolean select(Player me, int slot) {
        slot = inv.toHotbar(me, slot); // from the main inventory this takes two ticks: false until it is up
        if (slot < 0) return false;
        if (me.getInventory().selected != slot) {
            me.getInventory().selected = slot;
            press(ctx.minecraft().options.keyHotbarSlots[slot]);
        }
        return me.getInventory().selected == slot;
    }

    private void look(Vec3 at) {
        aimer.look(at, target == null ? 0 : target.getDeltaMovement().horizontalDistance());
    }

    private void key(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    private void use(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down);
    }

    private PathingCommand decide(String d) {
        tickDec = d;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand decide(String d, PathingCommand cmd) {
        tickDec = d;
        return cmd;
    }

    @Override
    public void onLostControl() {
        filter = null;
        marked.clear();
        target = null;
        engaged.clear();
        eatTicks = blockTicks = retreatTicks = backoff = 0;
        inv.closeScreen();
        if (ctx.minecraft().options != null) use(false);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "PvE " + label + (target == null ? "" : " -> " + target.getName().getString());
    }

    @Override
    public double priority() {
        return 2;
    }
}
