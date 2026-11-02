/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.process.ISecondoProcess;
import baritone.secondo.SecondoCommands;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Ostinato: {@code #secondo}. Binds a companion to Primo. Pathing stays idle while {@code secondoEnabled} is off. */
public class SecondoCommand extends Command {

    public SecondoCommand(IBaritone baritone) {
        super(baritone, "secondo");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(2);
        List<String> words = new ArrayList<>();
        while (args.hasAny()) {
            words.add(args.getString());
        }
        SecondoCommands.Call call;
        try {
            call = SecondoCommands.parse(words);
        } catch (IllegalArgumentException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        ISecondoProcess proc = baritone.getSecondoProcess();
        switch (call.verb()) {
            case FOLLOW -> logDirect(call.arg() == null ? proc.followNearest() : proc.followNamed(call.arg()));
            case STOP -> logDirect(proc.stop());
            case STATUS -> logDirect(proc.status());
            case ASSIST -> {
                boolean next = call.arg() == null ? !Baritone.settings().secondoAssist.value : call.arg().equals("on");
                Baritone.settings().secondoAssist.value = next;
                logDirect("secondoAssist " + next);
            }
            default -> throw new CommandInvalidStateException("follow [name], stop, status, or assist [on|off]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("follow", "stop", "status", "assist")
                    .filterPrefix(args.getString())
                    .stream();
        }
        if (!args.hasExactly(2)) {
            return Stream.empty();
        }
        String head = args.peekString(0).toLowerCase(Locale.ROOT);
        String prefix = args.peekString(1);
        if (head.equals("assist")) {
            return new TabCompleteHelper().append("on", "off").filterPrefix(prefix).stream();
        }
        if (head.equals("follow") && ctx.world() instanceof ClientLevel) {
            return new TabCompleteHelper()
                    .append(ctx.entitiesStream().filter(e -> e instanceof Player).map(e -> ((Player) e).getName().getString()))
                    .filterPrefix(prefix)
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Follow and assist another player (Primo)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Secondo is a companion. It follows Primo with GoalNear and, when assist is on, paths to a block",
                "Primo is breaking or onto a nearby drop. It does not take over combat, Presto, or the PPE stack.",
                "secondoEnabled defaults off. A bind while it is off does not path.",
                "",
                "Usage:",
                "> secondo follow - bind the nearest other player",
                "> secondo follow <name> - bind that player",
                "> secondo stop - clear the bind",
                "> secondo status - bind, mode, and which planned features are present",
                "> secondo assist [on|off] - flip secondoAssist",
                "",
                "See docs/SECONDO.md.");
    }
}
