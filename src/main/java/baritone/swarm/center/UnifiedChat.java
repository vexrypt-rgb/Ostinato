package baritone.swarm.center;

import baritone.api.command.ICommand;
import baritone.api.command.manager.ICommandManager;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Ostinato: the installed registry. Local {@code #} chat and local {@code @} chat call {@link #localHash}
 * and {@link #localAt}. The window and {@link #ai} use the same {@link UnifiedRegistry#execute}.
 * When nothing is installed, local chat runs the existing command manager and an AI call does not run.
 */
public final class UnifiedChat {

    private static volatile UnifiedRegistry registry = UnifiedRegistry.of(java.util.List.of(), java.util.List.of());
    private static volatile Path configDir = Path.of(System.getProperty("user.home"), ".ostinato");
    private static volatile boolean installed;

    private UnifiedChat() {}

    public static void install(ICommandManager manager, Path directory) {
        Iterable<ICommand> commands = manager == null || manager.getRegistry() == null
                ? java.util.List.of() : manager.getRegistry().entries;
        String at = baritone.swarm.protocol.AtCatalogBridge.exportInstalled();
        registry = UnifiedRegistry.fromCommands(commands, at);
        if (directory != null) {
            configDir = directory;
        }
        installed = true;
        CenterRuntime.layout(LayoutStore.load(LayoutStore.file(configDir)));
        String theme = ThemeStore.load(configDir); // Ostinato: the saved per-user theme
        CenterRuntime.theme(theme, ThemeStore.theme(configDir, theme));
    }

    public static UnifiedRegistry registry() {
        return registry;
    }

    public static Path configDir() {
        return configDir;
    }

    public static boolean installed() {
        return installed;
    }

    /** Local player {@code #} line. Permission tags are not read. The supplier is the existing command manager. */
    public static boolean localHash(String line, BooleanSupplier run) {
        if (run == null) {
            return false;
        }
        UnifiedRegistry current = registry;
        if (!installed || current == null) {
            return run.getAsBoolean();
        }
        return current.execute(RegistryCall.localChat(line), run).executed();
    }

    /**
     * Local {@code @} line. Returns true when the registry ran {@code body}.
     * Returns false when the registry is not installed, and the caller runs {@code body} itself.
     */
    public static boolean localAt(String line, Runnable body) {
        if (!installed || body == null) {
            return false;
        }
        registry.execute(RegistryCall.localChat(line), () -> {
            body.run();
            return true;
        });
        return true;
    }

    public static RegistryResult ai(RegistryCall call, BooleanSupplier run) {
        if (call == null || call.kind != RegistryCall.Kind.AI) {
            return RegistryResult.denied("unauthorized");
        }
        if (!installed) {
            return RegistryResult.denied("unauthorized");
        }
        return registry.execute(call, run);
    }

    public static RegistryResult window(RegistryCall call, BooleanSupplier run) {
        if (call == null || call.kind != RegistryCall.Kind.WINDOW) {
            return RegistryResult.denied("unauthorized");
        }
        return registry.execute(call, run);
    }

    /** Test isolation. */
    public static void resetForTests() {
        registry = UnifiedRegistry.of(java.util.List.of(), java.util.List.of());
        configDir = Path.of(System.getProperty("user.home"), ".ostinato");
        installed = false;
        CenterRuntime.resetForTests();
    }
}
