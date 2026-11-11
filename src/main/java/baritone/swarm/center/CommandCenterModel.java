package baritone.swarm.center;

import baritone.api.command.FormPlacement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: the mission-control wall without a window. Buttons are the registry forms.
 * Layout changes go through the same file as {@code #center layout}.
 */
public final class CommandCenterModel {

    private final UnifiedRegistry registry;
    private final Path layoutFile;
    private CenterLayout layout;
    private List<BotSnapshot> tiles = List.of();
    private String server = "";

    public CommandCenterModel(UnifiedRegistry registry, Path configDir) {
        this.registry = registry;
        this.layoutFile = LayoutStore.file(configDir);
        this.layout = LayoutStore.load(this.layoutFile);
    }

    public UnifiedRegistry registry() {
        return registry;
    }

    public Path layoutFile() {
        return layoutFile;
    }

    public CenterLayout layout() {
        return layout;
    }

    public List<BotSnapshot> tiles() {
        return tiles;
    }

    public void tiles(List<BotSnapshot> next) {
        tiles = next == null ? List.of() : List.copyOf(next);
    }

    public String server() {
        return server == null || server.isBlank() ? "no server" : server;
    }

    public void server(String next) {
        server = next == null ? "" : next;
    }

    public List<ButtonModel> buttons(FormPlacement placement) {
        return registry.buttons().stream().filter(button -> button.form().placement() == placement).toList();
    }

    public RegistryResult press(RegistryCall call, ButtonModel button, Map<String, String> values, String bot,
                                TileActions.Sink sink) {
        return TileActions.press(registry, call, button, values, bot, sink);
    }

    /** The host switched cards. The file was already written by {@code #center layout} or {@link #remember}. */
    public void show(CenterLayout next) {
        layout = next == null ? CenterLayout.WALL : next;
    }

    public void remember(CenterLayout next) throws IOException {
        CenterLayout use = next == null ? CenterLayout.WALL : next;
        LayoutStore.save(layoutFile, use);
        layout = use;
        CenterRuntime.layout(use);
    }
}
