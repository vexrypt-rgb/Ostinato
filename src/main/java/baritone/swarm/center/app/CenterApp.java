package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.FakeCommandPort;
import baritone.swarm.center.app.fake.DemoSwarm;
import baritone.swarm.center.channel.CenterClient;
import baritone.swarm.center.channel.CommandPort;
import baritone.swarm.center.feed.FeedReader;
import baritone.swarm.center.feed.SwarmSource;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ostinato: Phase 3, the standalone command-center window. Its own JVM with no Minecraft classes: it reads the feed
 * directory and sends commands over the authenticated loopback channel. Closing it ends only this process; every bot,
 * the coordinator and the command host keep running.
 *
 * <pre>
 *   java -cp &lt;jar&gt; baritone.swarm.center.app.CenterApp --dir &lt;swarmCenterDir&gt;
 *        [--theme dark|light|high-contrast|colorblind] [--layout wall|map] [--pos x,y] [--log file]
 *        [--fake N]                               moving demo swarm of N bots (DemoSwarm), no files, no host
 *        [--auto "secs|bot|formId|arg=value,..."] press a tile button programmatically (live checks; no mouse)
 * </pre>
 *
 * Focus rules (design doc): the window is shown without activating, alerts are drawn in it only (no dialog, sound or
 * taskbar flash), and it takes focus only when the user clicks into it.
 */
public final class CenterApp {

    public static final String DEFAULT_POS = "2600,40";
    public static final long REFRESH_MS = 500;

    private CenterApp() {}

    public static void main(String[] args) throws Exception {
        Map<String, List<String>> a = parse(args);
        String log = first(a, "log");
        if (first(a, "fake") == null && first(a, "dir") == null) {
            System.err.println("usage: CenterApp --dir <swarmCenterDir> | --fake N");
            System.exit(2);
        }
        SwarmSource source;
        CommandPort port;
        if (first(a, "fake") != null) {
            DemoSwarm demo = new DemoSwarm(Math.max(1, Integer.parseInt(first(a, "fake"))), System::currentTimeMillis);
            source = demo;
            port = new FakeCommandPort(demo.swarm().hostName());
        } else {
            Path dir = Path.of(first(a, "dir"));
            source = new FeedReader(dir, System::currentTimeMillis);
            port = new CenterClient(dir, System::currentTimeMillis);
        }
        CenterTheme theme = theme(first(a, "theme"));
        CenterLayout layout = first(a, "layout") == null ? CenterLayout.WALL : CenterLayout.parse(first(a, "layout"));
        String pos = first(a, "pos") != null ? first(a, "pos") : System.getProperty("ostinato.windowPos", DEFAULT_POS);
        Logger logger = new Logger(log == null ? null : Path.of(log));
        logger.line("start pid=" + ProcessHandle.current().pid() + " layout=" + layout.wire() + " pos=" + pos);

        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "center-worker");
            t.setDaemon(true);
            return t;
        });
        Commands commands = new Commands(port, worker, SwingUtilities::invokeLater, System::currentTimeMillis);
        commands.onReply(r -> logger.line("reply " + (r.admitted() ? "admitted" : "refused") + " " + r.summary()));
        SwingUtilities.invokeAndWait(() -> {
            CenterShell shell = new CenterShell(new AppStyle(theme), commands, layout);
            JFrame frame = frame(shell, pos);
            worker.scheduleWithFixedDelay(() -> {
                SwarmView view = source.snapshot();
                SwingUtilities.invokeLater(() -> shell.update(view));
                logger.tiles(view);
            }, 0, REFRESH_MS, TimeUnit.MILLISECONDS);
            for (String auto : a.getOrDefault("auto", List.of())) {
                scheduleAuto(worker, shell, auto, logger);
            }
            logger.line("shown at " + frame.getX() + "," + frame.getY());
        });
    }

    /** The frame, shown at {@code pos} (second monitor) without taking focus. */
    static JFrame frame(CenterShell shell, String pos) {
        JFrame f = new JFrame("Ostinato command center");
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); // Ostinato: ends this process only
        f.setContentPane(shell.component());
        f.setSize(1280, 860);
        int[] at = place(screens(), pos);
        f.setLocation(at[0], at[1]);
        f.setAutoRequestFocus(false);
        f.setFocusableWindowState(false);
        f.setVisible(true);
        f.setFocusableWindowState(true); // Ostinato: may take focus later, when clicked into
        return f;
    }

    static List<Rectangle> screens() {
        List<Rectangle> out = new ArrayList<>();
        if (!GraphicsEnvironment.isHeadless()) {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                out.add(d.getDefaultConfiguration().getBounds());
            }
        }
        return out;
    }

    /**
     * Where the window goes: {@code pos} when it is on a screen; else 40,40 into the second screen; else {@code pos}
     * (same rule as the in-game window's {@code WindowPlacement}).
     */
    static int[] place(List<Rectangle> screens, String pos) {
        int[] p = pair(pos);
        if (p == null) {
            p = pair(DEFAULT_POS);
        }
        for (Rectangle s : screens) {
            if (s.contains(p[0], p[1])) {
                return p;
            }
        }
        if (screens.size() >= 2) {
            return new int[]{screens.get(1).x + 40, screens.get(1).y + 40};
        }
        return p;
    }

    static int[] pair(String raw) {
        if (raw == null) {
            return null;
        }
        String[] f = raw.trim().split(",");
        try {
            return f.length == 2 ? new int[]{Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim())} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static CenterTheme theme(String name) {
        if (name == null) {
            return CenterTheme.DEFAULT_DARK;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "light" -> CenterTheme.LIGHT;
            case "high-contrast", "contrast", "hc" -> CenterTheme.HIGH_CONTRAST;
            case "colorblind", "colorblind-safe", "cb" -> CenterTheme.COLORBLIND_SAFE;
            default -> CenterTheme.DEFAULT_DARK;
        };
    }

    /**
     * {@code --auto "secs|bot|formId|arg=value,arg=value"}: after {@code secs}, fill that tile's fields and press its
     * button through Swing ({@code doClick}), the same code path as a mouse click, without moving the mouse.
     */
    static void scheduleAuto(ScheduledExecutorService worker, CenterShell shell, String spec, Logger logger) {
        String[] f = spec.split("\\|", -1);
        if (f.length < 3) {
            logger.line("auto: bad spec " + spec);
            return;
        }
        long delay = (long) (Double.parseDouble(f[0]) * 1000);
        Map<String, String> values = new LinkedHashMap<>();
        if (f.length > 3 && !f[3].isBlank()) {
            for (String kv : f[3].split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    values.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        worker.schedule(() -> SwingUtilities.invokeLater(() -> {
            String bot = f[1], form = f[2];
            for (Map.Entry<String, String> e : values.entrySet()) {
                Component c = find(shell.component(), "tile:" + bot + ":field:" + form + ":" + e.getKey());
                if (c instanceof JTextField t) {
                    t.setText(e.getValue());
                }
            }
            Component b = find(shell.component(), "tile:" + bot + ":button:" + form);
            if (b instanceof AbstractButton button) {
                logger.line("auto: press " + bot + " " + form + " " + values);
                button.doClick();
            } else {
                logger.line("auto: no button tile:" + bot + ":button:" + form);
            }
        }), delay, TimeUnit.MILLISECONDS);
    }

    /** Depth-first search by component name. */
    public static Component find(Component root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                Component hit = find(child, name);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Map<String, List<String>> parse(String[] args) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "";
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        return out;
    }

    private static String first(Map<String, List<String>> a, String k) {
        List<String> v = a.get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** Optional append-only log for live checks: replies, and every 10 s one line per tile. */
    static final class Logger {
        private final Path file;
        private long nextTiles;

        Logger(Path file) {
            this.file = file;
        }

        synchronized void line(String text) {
            if (file == null) {
                return;
            }
            try {
                Files.writeString(file, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + text
                        + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // a log write never stops the window
            }
        }

        void tiles(SwarmView view) {
            if (file == null || view.readAtMs() < nextTiles) {
                return;
            }
            nextTiles = view.readAtMs() + 10_000;
            for (SwarmView.BotState b : view.bots()) {
                line("tile " + b.name() + " " + b.freshness().name().toLowerCase(java.util.Locale.ROOT) + " age="
                        + b.ageMs() + " pos=" + TileText.position(b.feed()) + " task=" + TileText.task(b.feed())
                        + " health=" + b.feed().health() + " alerts=" + view.alertsFor(b.name()).size());
            }
        }
    }
}
