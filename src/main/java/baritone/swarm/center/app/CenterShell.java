package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.LayoutStore;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, everything inside the frame: header (title, server, command host, alert count, layout
 * toggle), one card per {@link LayoutView}, and a footer with the last command reply and any refused feed files. A
 * plain panel, so tests build and click it without a display; {@link CenterApp} puts it in a frame.
 *
 * <p>Names: {@code header.server}, {@code header.host}, {@code header.alerts}, {@code header.layout.wall},
 * {@code header.layout.map}, {@code footer.reply}, {@code footer.problems}.
 */
public final class CenterShell {

    private final AppStyle style;
    private final JPanel root;
    private final JLabel server, host, alerts, reply, problems;
    private final CardLayout cards = new CardLayout();
    private final JPanel body;
    private final List<LayoutView> views = new ArrayList<>();
    private final Map<CenterLayout, JToggleButton> toggles = new EnumMap<>(CenterLayout.class);
    private final MapView map;
    private final Commands commands;
    private CenterLayout shown;
    private Consumer<CenterLayout> onPick = l -> {};
    private Path prefsDir;
    private String layoutSeen;
    private SwarmView last = SwarmView.EMPTY;

    public CenterShell(AppStyle style, Commands commands, CenterLayout initial) {
        this.style = style;
        this.commands = commands;
        root = style.panel(new BorderLayout(), style.bg());
        root.setName("shell");

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

        commands.onReply(this::showReply);
        show(initial == null ? CenterLayout.WALL : initial);
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

    /** Per-user prefs (control groups, layout, and later the theme) live under this directory. */
    public void setPrefsDir(Path dir) {
        prefsDir = dir;
        map.setPrefsDir(dir);
        followLayout();
    }

    /** Header toggle and a tile opened on the map: switch now, remember it, and tell the host. */
    private void pick(CenterLayout l) {
        show(l);
        saveLayout(l);
        sendLayout(l);
        onPick.accept(l);
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

    /** A missing file keeps the current card. Garbage in the file is the wall. */
    private void followLayout() {
        if (prefsDir == null) {
            return;
        }
        Path file = LayoutStore.sharedFile(prefsDir);
        String text;
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            text = Files.readString(file);
        } catch (IOException e) {
            return;
        }
        if (text.equals(layoutSeen)) {
            return;
        }
        layoutSeen = text;
        CenterLayout loaded = LayoutStore.load(file);
        if (loaded != shown) {
            show(loaded);
        }
    }

    private void sendLayout(CenterLayout l) {
        String id = l == CenterLayout.MAP ? "layout-map" : "layout-wall";
        ButtonModel form = null;
        if (last.catalog() != null) {
            for (ButtonModel m : last.catalog().models(FormPlacement.HEADER)) {
                if (id.equals(m.form().id())) {
                    form = m;
                    break;
                }
            }
        }
        if (form != null) {
            commands.press(form, Map.of(), last.hostBot(), null);
        } else {
            commands.line("center layout " + l.wire(), last.hostBot(), null);
        }
    }

    /** One refresh, on the Swing thread. Every card is updated, shown or not, so switching is instant. */
    public void update(SwarmView view) {
        last = view == null ? SwarmView.EMPTY : view;
        followLayout();
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
    }

    private void showReply(CenterReply r) {
        reply.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        reply.setForeground(r.admitted() ? style.ok() : style.warn());
    }
}
