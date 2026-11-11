package baritone.swarm.center;

/**
 * Ostinato: the {@code #center} hook. Closing the window goes through {@link #close()} and does not despawn bots.
 * The Swing window installs itself as the host. Until then, open is remembered and does nothing visible.
 */
public final class CenterRuntime {

    /** Documented hook. Chat {@code #center} and {@code CenterRuntime.open()} use the same host. */
    public static final String HOOK = "baritone.swarm.center.CenterRuntime#open";

    public interface Host {
        void open();

        void close();

        void layout(CenterLayout layout);

        /** Ostinato: redraw with a new theme. No restart. */
        default void theme(CenterTheme theme) {}

        Host NONE = new Host() {
            @Override public void open() {}
            @Override public void close() {}
            @Override public void layout(CenterLayout layout) {}
        };
    }

    private static volatile Host host = Host.NONE;
    private static volatile boolean open;
    private static volatile CenterLayout layout = CenterLayout.WALL;
    private static volatile String themeName = ThemeStore.DEFAULT;
    private static volatile CenterTheme theme = CenterTheme.DEFAULT_DARK;

    private CenterRuntime() {}

    public static void setHost(Host next) {
        host = next == null ? Host.NONE : next;
    }

    public static Host host() {
        return host;
    }

    public static boolean isOpen() {
        return open;
    }

    public static CenterLayout layout() {
        return layout;
    }

    public static void open() {
        open = true;
        host.open();
    }

    /** Hides the window. Does not stop a bot, a child process, or the swarm link. */
    public static void close() {
        open = false;
        host.close();
    }

    public static void layout(CenterLayout next) {
        layout = next == null ? CenterLayout.WALL : next;
        host.layout(layout);
    }

    public static String themeName() {
        return themeName;
    }

    public static CenterTheme theme() {
        return theme;
    }

    /** Ostinato: sets the theme the window draws with and redraws it live. */
    public static void theme(String name, CenterTheme next) {
        themeName = name == null ? ThemeStore.DEFAULT : name;
        theme = next == null ? CenterTheme.DEFAULT_DARK : next;
        host.theme(theme);
    }

    /** Test isolation. Production code does not call this. */
    public static void resetForTests() {
        host = Host.NONE;
        open = false;
        layout = CenterLayout.WALL;
        themeName = ThemeStore.DEFAULT;
        theme = CenterTheme.DEFAULT_DARK;
    }
}
