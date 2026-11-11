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
import baritone.swarm.center.CenterRuntime;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: {@code #theme <name>} picks the command-center color theme. It applies live and is saved per user.
 * The window header's theme picker runs this same command.
 */
public class ThemeCommand extends Command {

    public ThemeCommand(IBaritone baritone) {
        super(baritone, "theme");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.OWNER;
    }

    public static List<ArgDecl> declaredSchema() {
        return List.of(ArgDecl.token("name", false));
    }

    public static List<FormDecl> windowForms() {
        return List.of(FormDecl.of("theme-pick", "Theme", "theme", FormPlacement.HEADER, ArgDecl.token("name", true)));
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
        Path dir = directory();
        if (!args.hasAny()) {
            logDirect("theme: " + CenterRuntime.themeName() + " (choices: " + String.join(", ", ThemeStore.names(dir))
                    + "; custom files go in " + ThemeStore.themesDir(dir) + ")");
            return;
        }
        String wanted = args.getString();
        args.requireMax(0);
        String name = ThemeStore.resolve(dir, wanted);
        if (name == null) {
            throw new CommandInvalidTypeException(args.consumed(), String.join(", ", ThemeStore.names(dir)));
        }
        CenterTheme theme = ThemeStore.theme(dir, name);
        try {
            ThemeStore.save(dir, name);
        } catch (IOException e) {
            throw new CommandInvalidStateException("theme: could not save the choice");
        }
        CenterRuntime.theme(name, theme);
        List<String> problems = theme.aaProblems();
        logDirect("theme: " + name + (problems.isEmpty() ? "" : " (below WCAG AA: " + String.join(", ", problems) + ")"));
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
            return new ArrayList<>(ThemeStore.names(directory())).stream().filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Pick the command-center color theme";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Picks the command-center color theme. It applies at once with no restart and is saved per user.",
                "Status always shows a glyph and a word too, so no theme relies on color alone.",
                "",
                "Built-ins: dark (Default dark), light, high-contrast, colorblind (deuteranopia and protanopia safe).",
                "Custom themes: put name.json in the themes folder of the Ostinato config directory, with any of",
                "background, surface, raised, border, text, muted, faint, accent, onAccent, ok, warn, danger as \"#RRGGBB\",",
                "and an optional \"base\" built-in for the rest.",
                "",
                "Usage:",
                "> theme - show the current theme and the choices",
                "> theme <name> - switch theme"
        );
    }
}
