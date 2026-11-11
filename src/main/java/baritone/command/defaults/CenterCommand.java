package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.Command;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterRuntime;
import baritone.swarm.center.desktop.CenterWindow;
import baritone.swarm.center.LayoutStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: {@code #center} opens the command-center window. {@code #center layout wall|map} remembers the layout.
 * Closing the window does not stop the swarm.
 */
public class CenterCommand extends Command {

    public CenterCommand(IBaritone baritone) {
        super(baritone, "center");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.OWNER;
    }

    public static List<ArgDecl> declaredSchema() {
        return List.of(ArgDecl.rest("args", false));
    }

    public static List<FormDecl> windowForms() {
        return List.of(
                FormDecl.of("layout-wall", "Wall", "center layout wall", FormPlacement.HEADER),
                FormDecl.of("layout-map", "War room", "center layout map", FormPlacement.HEADER)
        );
    }

    @Override
    public List<ArgDecl> argumentSchema() {
        return declaredSchema();
    }

    @Override
    public List<FormDecl> forms() {
        return windowForms();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "open";
        switch (action) {
            case "open" -> {
                args.requireMax(0);
                CenterRuntime.open();
                if (!CenterWindow.mayShow()) {
                    logDirect("center: the window cannot open in this runtime");
                } else {
                    logDirect("center: open (closing the window leaves bots running)");
                }
            }
            case "close" -> {
                args.requireMax(0);
                CenterRuntime.close();
                logDirect("center: closed");
            }
            case "layout" -> {
                Path file = LayoutStore.file(directory());
                if (!args.hasAny()) {
                    logDirect("center: layout " + LayoutStore.load(file).wire());
                    return;
                }
                String which = args.getString();
                args.requireMax(0);
                CenterLayout layout;
                try {
                    layout = CenterLayout.parse(which);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidTypeException(args.consumed(), "wall or map");
                }
                try {
                    LayoutStore.save(file, layout);
                } catch (IOException e) {
                    throw new CommandInvalidStateException("center: could not save layout");
                }
                CenterRuntime.layout(layout);
                logDirect("center: layout " + layout.wire());
            }
            default -> throw new CommandInvalidTypeException(args.consumed(), "open, close or layout");
        }
    }

    private Path directory() {
        if (baritone instanceof Baritone) {
            return ((Baritone) baritone).getDirectory();
        }
        return Path.of(System.getProperty("user.home"), ".ostinato");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.peekString().toLowerCase(Locale.ROOT);
            return Stream.of("open", "close", "layout").filter(s -> s.startsWith(prefix));
        }
        if (args.hasExactly(2) && args.peekString().equalsIgnoreCase("layout")) {
            args.get();
            String prefix = args.peekString().toLowerCase(Locale.ROOT);
            return Stream.of("wall", "map").filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Open the command center, or set its layout";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Opens the command-center window on the second monitor. It is off until you run this.",
                "The window never steals focus. Closing it does not stop the swarm or any bot.",
                "",
                "Usage:",
                "> center - open the window",
                "> center close - hide the window",
                "> center layout - show wall or map",
                "> center layout wall - mission control wall",
                "> center layout map - war room placeholder",
                "The choice is saved per user under the Ostinato config directory.",
                "Hook: " + CenterRuntime.HOOK
        );
    }
}
