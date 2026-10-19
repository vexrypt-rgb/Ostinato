/*
 * Ostinato: new file (freecam ghost sparring). Not part of upstream Baritone.
 */
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Ostinato: the bot's own melee decisions (CombatMelee, CombatMovement, CombatShield with the tuned CombatPolicy) run
 * for a client-side ghost instead of the bot. Every "hand" the modules use writes into {@link Keys} for the ghost:
 * nothing touches the real player's keys, look, hotbar or connection. The freecam spar reads the keys, moves the ghost
 * on a PlayerSim and applies the hits itself.
 */
public final class SparBrain {

    /** What the ghost does this tick. */
    public static final class Keys {
        public boolean forward, back, left, right, jump, sprint, use, attack;
        public int select = -1;
        public Vec3 look;
        public String dec = "-";

        void clear() {
            forward = back = left = right = jump = sprint = use = attack = false;
            look = null;
            dec = "-";
        }
    }

    private final Keys k = new Keys();
    private final CombatInventory inv;
    private final CombatTargeting targeting;
    private final CombatShield shield;
    private final CombatMelee melee;
    private final CombatPhase phase = new CombatPhase();
    private final CombatSwing swing = new CombatSwing();
    /** Set by the spar: ticks left of the ghost's bite (the shield never rises mid-bite). */
    public int eatTicks;
    public int hits, crits, sprintHits, axeHits;

    public SparBrain(Baritone baritone) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Random rng = new Random();
        CombatPolicy policy = baritone.getPvpProcess().policy(); // the bot's tuned numbers
        inv = new CombatInventory(ctx);
        targeting = new CombatTargeting(ctx, e -> true);
        CombatDefense defense = new CombatDefense(ctx, inv);
        CombatAim aimer = new CombatAim(baritone, ctx, rng); // held for the constructors; the ghost never aims through it
        CombatMovement movement = new CombatMovement(ctx, rng, this::key);
        shield = new CombatShield(ctx, inv, aimer, targeting, defense, phase, policy.shield, new CombatShield.Hands() {
            public boolean select(Player me, int slot) { return SparBrain.this.select(slot); }
            public void look(Vec3 at) { k.look = at; }
            public void use(boolean down) { k.use = down; }
            public void key(Input in) { SparBrain.this.key(in); }
            public PathingCommand decide(String d) { return SparBrain.this.decide(d); }
            public int eatTicks() { return eatTicks; }
            public void blockStarted() {}
            public void dodge(Player me) { SparBrain.this.key(Input.MOVE_LEFT); }
        });
        melee = new CombatMelee(inv, swing, targeting, shield, movement, policy.melee, new CombatMelee.Hands() {
            public void key(Input in) { SparBrain.this.key(in); }
            public boolean hit(Player me) {
                k.attack = true;
                hits++;
                return true; // the spar decides whether it connects
            }
            public boolean select(Player me, int slot) { return SparBrain.this.select(slot); }
            public void look(Vec3 at) { k.look = at; }
            public PathingCommand decide(String d) { return SparBrain.this.decide(d); }
            public void axeHit() { axeHits++; }
            public void crit() { crits++; }
            public void sprintHit() { sprintHits++; }
        });
    }

    private boolean select(int slot) {
        if (slot < 0) return true;
        k.select = slot;
        return true; // the ghost's hotbar changes at once (no server to wait for)
    }

    private void key(Input in) {
        switch (in) {
            case MOVE_FORWARD -> k.forward = true;
            case MOVE_BACK -> k.back = true;
            case MOVE_LEFT -> k.left = true;
            case MOVE_RIGHT -> k.right = true;
            case JUMP -> k.jump = true;
            case SPRINT -> k.sprint = true;
            default -> {
            }
        }
    }

    private PathingCommand decide(String d) {
        if ("-".equals(k.dec)) k.dec = d;
        return new PathingCommand((Goal) null, baritone.api.process.PathingCommandType.REQUEST_PAUSE);
    }

    /** One tick of the bot's melee for {@code me} (the ghost) against {@code target} (the freecam camera). */
    public Keys tick(Player me, LivingEntity target) {
        k.clear();
        targeting.track(target);
        shield.observe(me, target);
        double dist = CombatGeometry.horizontalBoxDist(me, target);
        boolean inReach = CombatGeometry.exactReach(me, target) <= CombatGeometry.REACH - 0.05;
        Vec3 gap = target.position().subtract(me.position());
        Vec3 tv = targeting.velocity(target);
        boolean chase = gap.horizontalDistance() > 0.5 && (tv.x * gap.x + tv.z * gap.z) / gap.horizontalDistance() > 0.1;
        boolean blockMelee = eatTicks == 0 && shield.meleeBlock(me, target, dist);
        if (shield.guard(me, target, dist, blockMelee) != null) return k;
        if (melee.prepare(me, target, inReach, chase, false) != null) return k;
        melee.exchange(me, target, dist);
        return k;
    }
}
