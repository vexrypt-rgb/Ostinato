package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.LayoutStore;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, everything inside the frame: header (title, server, command host, alert count, theme
 * picker, layout toggle), one card per {@link LayoutView}, and a footer with the last command reply and any refused
 * feed files. A plain panel, so tests build and click it without a display; {@link CenterApp} puts it in a frame.
 *
 * <p>Names: {@code header.server}, {@code header.host}, {@code header.alerts}, {@code header.theme.dark},
 * {@code header.theme.light}, {@code header.theme.high-contrast}, {@code header.theme.colorblind},
 * {@code header.layout.wall}, {@code header.layout.map}, {@code footer.reply}, {@code footer.problems}.
 */
public final class CenterShell {

    private static final List<String> THEMES = List.of("dark", "light", "high-contrast", "colorblind");

    private AppStyle style;
    private final JPanel root;
    private JLabel server, host, alerts, reply, problems;
    private CardLayout cards = new CardLayout();
    private JPanel body;
    private final List<LayoutView> views = new ArrayList<>();
    private final Map<CenterLayout, JToggleButton> toggles = new EnumMap<>(CenterLayout.class);
    private final Map<String, JToggleButton> themeToggles = new LinkedHashMap<>();
    private MapView map;
    private final Commands commands;
    private CenterLayout shown;
    private Consumer<CenterLayout> onPick = l -> {};
    private Path prefsDir;
    private String layoutSeen;
    private String themeName;
    private String themeSeen;
    private SwarmView last = SwarmView.EMPTY;
    private boolean applying;
    private Hold hold;

    public CenterShell(AppStyle style, Commands commands, CenterLayout initial) {
        this.style = style == null ? new AppStyle(CenterTheme.DEFAULT_DARK) : style;
        this.commands = commands;
        this.themeName = nameOf(this.style.theme);
        root = this.style.panel(new BorderLayout(), this.style.bg());
        root.setName("shell");
        commands.onReply(this::showReply);
        assemble();
        show(initial == null ? CenterLayout.WALL : initial);
    }

    private static String nameOf(CenterTheme theme) {
        if (CenterTheme.LIGHT.equals(theme)) {
            return "light";
        }
        if (CenterTheme.HIGH_CONTRAST.equals(theme)) {
            return "high-contrast";
        }
        if (CenterTheme.COLORBLIND_SAFE.equals(theme)) {
            return "colorblind";
        }
        return ThemeChoice.DEFAULT;
    }

    private void assemble() {
        views.clear();
        toggles.clear();
        themeToggles.clear();
        cards = new CardLayout();

        JPanel header = style.panel(new BorderLayout(12, 0), style.surface());
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        JPanel left = style.panel(new FlowLayout(FlowLayout.LEFT, 12, 0), null);
        left.add(style.label("Ostinato command center", style.text(), Font.BOLD, 15f));
        server = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "header.server");
        host = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "header.host");
        alerts = named(style.label(" ", style.muted(), Font.BOLD, 12f), "header.alerts");
        left.add(server);
        left.add(host);
        left.add(alerts);
        header.add(left, BorderLayout.WEST);
        JPanel right = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);
        ButtonGroup themes = new ButtonGroup();
        for (String name : THEMES) {
            JToggleButton t = style.toggle(CenterTheme.label(name));
            t.setName("header.theme." + name);
            t.addActionListener(e -> choose(name));
            themes.add(t);
            themeToggles.put(name, t);
            right.add(t);
        }
        ButtonGroup group = new ButtonGroup();
        for (CenterLayout l : CenterLayout.values()) {
            JToggleButton t = style.toggle(l == CenterLayout.WALL ? "Wall" : "War room");
            t.setName("header.layout." + l.wire());
            t.addActionListener(e -> pick(l));
            group.add(t);
            toggles.put(l, t);
            right.add(t);
        }
        header.add(right, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        body = style.panel(cards, style.bg());
        root.add(body, BorderLayout.CENTER);
        WallView wall = new WallView(style, commands);
        map = new MapView(style, commands);
        add(wall);
        add(map);
        wall.onOpenOnMap(name -> {
            pick(CenterLayout.MAP);
            map.centerOn(name);
        });

        JPanel footer = style.panel(new BorderLayout(12, 0), style.surface());
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        reply = named(style.label("No command sent yet.", style.muted(), Font.PLAIN, 12f), "footer.reply");
        problems = named(style.label(" ", style.warn(), Font.PLAIN, 12f), "footer.problems");
        footer.add(reply, BorderLayout.WEST);
        footer.add(problems, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        JToggleButton theme = themeToggles.get(themeName);
        if (theme != null) {
            theme.setSelected(true);
        }
    }

    private JLabel named(JLabel l, String n) {
        l.setName(n);
        return l;
    }

    /** Adds a layout card. A later slice that replaces {@link MapView} adds its view here instead. */
    private void add(LayoutView v) {
        views.add(v);
        body.add(v.component(), v.layout().wire());
    }

    public JPanel component() {
        return root;
    }

    public CenterLayout shown() {
        return shown;
    }

    public String themeName() {
        return themeName;
    }

    /** Called when the user picks a layout in the header (the layout slice saves and sends it). */
    public void onLayoutPicked(Consumer<CenterLayout> pick) {
        onPick = pick == null ? l -> {} : pick;
    }

    public void show(CenterLayout l) {
        shown = l == null ? CenterLayout.WALL : l;
        cards.show(body, shown.wire());
        JToggleButton t = toggles.get(shown);
        if (t != null && !t.isSelected()) {
            t.setSelected(true);
        }
    }

    public List<LayoutView> views() {
        return List.copyOf(views);
    }

    /** Per-user prefs (control groups, layout, and theme) live under this directory. */
    public void setPrefsDir(Path dir) {
        prefsDir = dir;
        Prefs p = readPrefs(dir);
        followTheme(p.theme());
        map.setPrefsDir(dir);
        followLayout(p.layout());
    }

    public Path prefsDir() {
        return prefsDir;
    }

    /** The shared layout and theme files as text; null where a file is missing or unreadable. */
    public record Prefs(String layout, String theme) {
        public static final Prefs NONE = new Prefs(null, null);
    }

    /** Reads both prefs files. Call it off the Swing thread (the app's worker does, next to the feed snapshot). */
    public static Prefs readPrefs(Path dir) {
        if (dir == null) {
            return Prefs.NONE;
        }
        return new Prefs(readText(LayoutStore.sharedFile(dir)), readText(ThemeChoice.file(dir)));
    }

    private static String readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Header toggle and a tile opened on the map: switch now, remember it, and tell the host. */
    private void pick(CenterLayout l) {
        show(l);
        saveLayout(l);
        sendLayout(l);
        onPick.accept(l);
    }

    private void choose(String name) {
        if (applying || name == null || name.equals(themeName)) {
            return;
        }
        applying = true;
        try {
            applyTheme(CenterTheme.builtIn(name), name);
            saveTheme(name);
            sendTheme(name);
            update(last);
        } finally {
            applying = false;
        }
    }

    /**
     * Rebuilds the cards with {@code theme} and keeps the layout, selection, camera, typed text and last reply.
     * The text is put back at the end of {@link #update}, after the new tiles exist.
     */
    private void applyTheme(CenterTheme theme, String name) {
        Hold snap = capture();
        style = new AppStyle(theme == null ? CenterTheme.DEFAULT_DARK : theme);
        themeName = name == null ? ThemeChoice.DEFAULT : name;
        root.removeAll();
        root.setBackground(style.bg());
        assemble();
        if (prefsDir != null) {
            map.setPrefsDir(prefsDir);
        }
        map.restoreMap(snap.zoom, snap.cx, snap.cz, snap.dim, snap.selected, snap.groups, snap.area, snap.armed,
                snap.mine);
        if (snap.reply != null) {
            reply.setText(snap.reply);
            reply.setForeground(snap.replyColor);
        }
        show(snap.layout);
        hold = snap;
        root.revalidate();
        root.repaint();
    }

    private Hold capture() {
        Hold h = new Hold();
        h.layout = shown == null ? CenterLayout.WALL : shown;
        WallView wall = wall();
        h.wall = wall == null ? null : wall.selected();
        h.reply = reply == null ? null : reply.getText();
        h.replyColor = reply == null ? style.muted() : reply.getForeground();
        h.zoom = map == null ? 4 : map.camera().zoom();
        h.cx = map == null ? 0 : map.camera().centerX();
        h.cz = map == null ? 0 : map.camera().centerZ();
        h.dim = map == null ? 'o' : map.dimension();
        h.selected = map == null ? List.of() : map.selected();
        h.groups = map == null ? "" : map.groups().toText();
        h.area = map == null ? null : map.area();
        h.armed = map != null && map.areaArmed();
        h.mine = map != null && map.areaMine();
        collect(root, h.fields);
        return h;
    }

    private void collect(Component c, Map<String, String> fields) {
        if (c instanceof JTextComponent text && c.getName() != null) {
            fields.put(c.getName(), text.getText());
        }
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) {
                collect(child, fields);
            }
        }
    }

    private void restoreFields(Hold snap) {
        for (Map.Entry<String, String> e : snap.fields.entrySet()) {
            Component c = CenterApp.find(root, e.getKey());
            if (c instanceof JTextComponent text) {
                text.setText(e.getValue());
            }
        }
    }

    private WallView wall() {
        for (LayoutView v : views) {
            if (v instanceof WallView wall) {
                return wall;
            }
        }
        return null;
    }

    private void saveLayout(CenterLayout l) {
        if (prefsDir == null || l == null) {
            return;
        }
        try {
            Path file = LayoutStore.sharedFile(prefsDir);
            LayoutStore.save(file, l);
            layoutSeen = Files.readString(file);
        } catch (IOException ignored) {
            // The card already switched. The next refresh follows whatever the file still says.
        }
    }

    private void saveTheme(String name) {
        if (prefsDir == null || name == null) {
            return;
        }
        try {
            Path file = ThemeChoice.file(prefsDir);
            ThemeChoice.save(file, name);
            themeSeen = Files.readString(file);
        } catch (IOException ignored) {
            // The theme already changed. The next refresh follows whatever the file still says.
        }
    }

    /** A missing file keeps the current card. Garbage in the file is the wall. */
    private void followLayout(String text) {
        if (prefsDir == null || text == null || text.equals(layoutSeen)) {
            return;
        }
        layoutSeen = text;
        CenterLayout loaded = LayoutStore.parse(text);
        if (loaded != shown) {
            show(loaded);
        }
    }

    /** A missing file keeps the current theme. An unknown name is Default dark. */
    private void followTheme(String text) {
        if (applying || prefsDir == null || text == null || text.equals(themeSeen)) {
            return;
        }
        themeSeen = text;
        String name = ThemeChoice.parse(text);
        if (name.equals(themeName)) {
            return;
        }
        applying = true;
        try {
            applyTheme(CenterTheme.builtIn(name), name);
        } finally {
            applying = false;
        }
    }

    private void sendLayout(CenterLayout l) {
        String id = l == CenterLayout.MAP ? "layout-map" : "layout-wall";
        ButtonModel form = headerForm(id);
        if (form != null) {
            commands.press(form, Map.of(), last.hostBot(), null);
        } else {
            commands.line("center layout " + l.wire(), last.hostBot(), null);
        }
    }

    private void sendTheme(String name) {
        ButtonModel form = headerForm("theme-pick");
        if (form != null) {
            commands.press(form, Map.of("name", name), last.hostBot(), null);
        } else {
            commands.line("theme " + name, last.hostBot(), null);
        }
    }

    private ButtonModel headerForm(String id) {
        if (last.catalog() == null) {
            return null;
        }
        for (ButtonModel m : last.catalog().models(FormPlacement.HEADER)) {
            if (id.equals(m.form().id())) {
                return m;
            }
        }
        return null;
    }

    /** One refresh, on the Swing thread. Every card is updated, shown or not, so switching is instant. */
    public void update(SwarmView view) {
        update(view, readPrefs(prefsDir));
    }

    /** One refresh with prefs already read off the Swing thread ({@link #readPrefs}). */
    public void update(SwarmView view, Prefs prefs) {
        last = view == null ? SwarmView.EMPTY : view;
        view = last;
        Prefs p = prefs == null ? Prefs.NONE : prefs;
        followLayout(p.layout());
        followTheme(p.theme());
        server.setText("Server " + view.server());
        String h = view.hostBot();
        host.setText(h == null ? TileStatus.Level.WARN.glyph + " no command host" : "Host " + h);
        host.setForeground(h == null ? style.warn() : style.muted());
        int n = view.alerts().size();
        alerts.setText(n == 0 ? TileStatus.Level.IDLE.glyph + " No alerts"
                : TileStatus.Level.WARN.glyph + " " + n + (n == 1 ? " alert" : " alerts"));
        alerts.setForeground(n == 0 ? style.muted() : style.warn());
        problems.setText(view.problems().isEmpty() ? " " : TileStatus.Level.WARN.glyph + " " + view.problems().get(0)
                + (view.problems().size() > 1 ? " (+" + (view.problems().size() - 1) + ")" : ""));
        for (LayoutView v : views) {
            v.update(view);
        }
        if (hold != null) {
            restoreFields(hold);
            WallView wall = wall();
            if (wall != null && hold.wall != null) {
                wall.select(hold.wall);
            }
            map.refreshArea();
            hold = null;
        }
    }

    private void showReply(CenterReply r) {
        reply.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        reply.setForeground(r.admitted() ? style.ok() : style.warn());
    }

    /** Camera, selection and typed text carried across a theme rebuild. */
    private static final class Hold {
        private CenterLayout layout;
        private String wall;
        private String reply;
        private Color replyColor;
        private int zoom;
        private double cx;
        private double cz;
        private char dim;
        private List<String> selected = List.of();
        private String groups = "";
        private int[] area;
        private boolean armed;
        private boolean mine;
        private final Map<String, String> fields = new LinkedHashMap<>();
    }
}
