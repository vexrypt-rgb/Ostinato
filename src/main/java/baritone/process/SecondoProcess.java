/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.ISecondoProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.secondo.SecondoFeatures;
import baritone.secondo.SecondoGoals;
import baritone.secondo.SecondoLook;
import baritone.secondo.SecondoPolicy;
import baritone.secondo.SecondoPolicy.Config;
import baritone.secondo.SecondoPolicy.Decision;
import baritone.secondo.SecondoPolicy.Observation;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: companion process. While {@code secondoEnabled} is off, or no follow is bound, this stays inactive
 * and does not set a goal. One process owns pathing, so the goal is issued here with {@link SecondoGoals}
 * (GoalNear / GoalBlock, the FollowProcess shapes) instead of leaving {@code FollowProcess} running beside it.
 * <p>
 * Assist break paths next to the block and records {@code Request.BREAK}. v1 does not swing and does not call
 * {@code MineProcess} (that searches by block type, not by this one cell).
 * A drop paths onto the item; walking into it is the pickup.
 * Combat paths next to Primo and records the threat. It does not call {@code IPvpProcess}, because that process
 * would take pathing and cancel this session. Bombard and Scherzo stay behind {@link SecondoFeatures#invoke}.
 */
public final class SecondoProcess extends BaritoneProcessHelper implements ISecondoProcess {

    private boolean running;
    /** Null means the nearest other player. */
    private String primoName;
    private Decision last = Decision.off();

    public SecondoProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public String followNearest() {
        primoName = null;
        running = true;
        return boundLine("the nearest other player");
    }

    @Override
    public String followNamed(String name) {
        if (name == null || name.isBlank()) {
            return "Secondo needs a player name";
        }
        primoName = name;
        running = true;
        return boundLine(name);
    }

    @Override
    public String stop() {
        onLostControl();
        return "Secondo stopped";
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public String primoName() {
        return primoName;
    }

    @Override
    public String status() {
        Config c = config();
        String who = !running ? "none" : (primoName == null ? "nearest" : primoName);
        String mode = last.mode().name().toLowerCase(Locale.ROOT);
        String request = last.request().name().toLowerCase(Locale.ROOT);
        String detail = last.detail() == null || last.detail().isEmpty() ? "" : " detail=" + last.detail();
        Map<SecondoFeatures.Id, SecondoFeatures.State> gates = SecondoFeatures.probeHere();
        return "secondo enabled=" + c.enabled()
                + " running=" + running
                + " primo=" + who
                + " mode=" + mode
                + " request=" + request
                + detail
                + " assist=" + c.assist()
                + " " + SecondoFeatures.report(gates);
    }

    @Override
    public boolean isActive() {
        if (!Baritone.settings().secondoEnabled.value || !running) {
            return false;
        }
        return findPrimo() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Player self = ctx.player();
        Player primo = findPrimo();
        if (self == null || primo == null) {
            last = Decision.off();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        Iterable<net.minecraft.world.entity.Entity> entities = ctx.world() instanceof ClientLevel ? ctx.entities() : List.of();
        Observation obs = SecondoLook.observe(self, primo, entities);
        last = SecondoPolicy.decide(config(), obs);
        Goal goal = SecondoGoals.toGoal(last);
        if (goal == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        // Revalidate so a moving Primo retargets the same way FollowProcess does.
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        running = false;
        primoName = null;
        last = Decision.off();
    }

    @Override
    public double priority() {
        // Above stock follow/mine (-1) so a bound companion keeps the goal. Below PvpProcess (2).
        return DEFAULT_PRIORITY + 0.5;
    }

    @Override
    public String displayName0() {
        String who = primoName == null ? "nearest" : primoName;
        return "Secondo " + last.mode().name().toLowerCase(Locale.ROOT) + " " + who;
    }

    private String boundLine(String who) {
        boolean on = Baritone.settings().secondoEnabled.value;
        if (on) {
            return "Secondo bound to " + who;
        }
        return "Secondo bound to " + who + " (secondoEnabled is off, pathing unchanged)";
    }

    private static Config config() {
        var s = Baritone.settings();
        return new Config(
                s.secondoEnabled.value,
                s.secondoFollowDistance.value,
                s.secondoAssist.value,
                s.secondoAssistRadius.value,
                s.secondoAssistStand.value,
                s.secondoPickup.value,
                s.secondoPickupRadius.value,
                s.secondoCombatAssist.value
        ).clamped();
    }

    private Player findPrimo() {
        if (ctx.player() == null || !(ctx.world() instanceof ClientLevel)) {
            return null;
        }
        String want = primoName;
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : ctx.entities()) {
            if (!(e instanceof Player p) || p == ctx.player() || !p.isAlive()) {
                continue;
            }
            if (want != null && !p.getName().getString().equalsIgnoreCase(want)) {
                continue;
            }
            double d = p.distanceToSqr(ctx.player());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
