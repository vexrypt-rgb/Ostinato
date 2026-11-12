package baritone.swarm.center.desktop;

import baritone.Baritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.manager.ICommandManager;
import baritone.behavior.SwarmBehavior;
import baritone.swarm.SwarmSample;
import baritone.swarm.center.BotSnapshot;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterRuntime;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.CommandCenterModel;
import baritone.swarm.center.FocusPolicy;
import baritone.swarm.center.LiveTiles;
import baritone.swarm.center.RegistryCall;
import baritone.swarm.center.TileFormatter;
import baritone.swarm.center.ThemeStore;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.UnifiedChat;
import baritone.swarm.center.WindowPlacement;
import baritone.swarm.spawn.SwarmSpawnRegistry;
import net.minecraft.client.Minecraft;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: the command-center desktop window, drawn after the design doc's mockup: title bar with
 * server and alert chips, a tile wall, a focus panel for the selected bot, and the accounts strip.
 * It is not an {@code OstinatoScreen}. Off until {@code #center} or {@link CenterRuntime#open}.
 * Closing hides it and does not despawn. The frame is never created in unit tests: {@link #mayShow()}
 * is false without a display.
 */
public final class CenterWindow implements CenterRuntime.Host {

    private static final String SUBTITLE = "Second monitor, alerts never steal focus";
    private static final CenterWindow INSTANCE = new CenterWindow();

    private ICommandManager commands;
    private CommandCenterModel model;
    private JFrame frame;
    private JLabel serverChip;
    private JLabel alertChip;
    private JPanel wall;
    private JPanel cards;
    private CardLayout cardLayout;
    private final Map<CenterLayout, JToggleButton> layoutButtons = new LinkedHashMap<>();
    private JPanel accounts;
    private TelemetryPanel telemetry;
    /** Ostinato: text width of the focus details, inside the 300 px focus panel's padding and scroll bar. */
    private static final int FOCUS_TEXT_WIDTH = 240;
    private Timer timer;
    private List<String> shownNames = List.of();
    private final Map<String, TileView> views = new LinkedHashMap<>();
    private List<BotSnapshot> lastTiles = List.of();
    private String selected;
    private String focusBuiltFor;
    private JLabel focusName;
    private JLabel focusBadge;
    private JLabel focusDetails;
    private JPanel focusActions;

    private CenterWindow() {}

    /** Documented hook partner. Does not open the window. */
    public static void install(ICommandManager manager) {
        INSTANCE.commands = manager;
        CenterRuntime.setHost(INSTANCE);
    }

    /** False on a headless box and when {@code -Dostinato.center.show=false}. */
    public static boolean mayShow() {
        return !GraphicsEnvironment.isHeadless()
                && !"false".equalsIgnoreCase(System.getProperty("ostinato.center.show", "true"));
    }

    @Override
    public void open() {
        if (!mayShow()) {
            return;
        }
        SwingUtilities.invokeLater(this::show);
    }

    @Override
    public void close() {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(this::hide);
    }

    @Override
    public void layout(CenterLayout layout) {
        if (model != null) {
            model.show(layout);
        }
        if (frame == null || cardLayout == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> showCard(layout));
    }

    private void show() {
        if (!mayShow()) {
            return;
        }
        ensureModel();
        if (frame == null) {
            frame = build();
        }
        place(frame);
        refresh();
        showCard(model.layout());
        // Ostinato: show without activating. A later click may focus a field.
        frame.setAutoRequestFocus(FocusPolicy.AUTO_REQUEST_FOCUS);
        frame.setFocusableWindowState(false);
        frame.setVisible(true);
        frame.setFocusableWindowState(FocusPolicy.FOCUSABLE_AFTER_SHOW);
        if (timer != null) {
            timer.start();
        }
    }

    private void hide() {
        if (timer != null) {
            timer.stop();
        }
        if (frame != null) {
            frame.setVisible(false);
        }
    }

    private void ensureModel() {
        if (model == null) {
            model = new CommandCenterModel(UnifiedChat.registry(), UnifiedChat.configDir());
            model.show(CenterRuntime.layout());
        }
    }

    private JFrame build() {
        JFrame window = new JFrame("Ostinato command center");
        WindowChrome.install(window); // Ostinato: own themed title bar and frame instead of the white system one.
        window.setAutoRequestFocus(FocusPolicy.AUTO_REQUEST_FOCUS);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                CenterRuntime.close();
            }
        });
        window.setContentPane(content());
        window.setSize(1040, 800); // Ostinato: room for the perf strip and link monitor.
        timer = new Timer(2000, event -> refresh());
        timer.setRepeats(true);
        return window;
    }

    /** Ostinato: everything inside the frame. Built again on a theme change, since pieces read colors when built. */
    private JPanel content() {
        JPanel root = Style.panel(new BorderLayout(), Style.bg());
        JPanel top = Style.panel(new BorderLayout(), Style.bg());
        top.add(titleBar(), BorderLayout.NORTH);
        top.add(toolbar(), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        cardLayout = new CardLayout();
        cards = Style.panel(cardLayout, Style.bg());
        wall = new WallPanel();
        JPanel wallHolder = Style.panel(new BorderLayout(), Style.bg());
        wallHolder.add(wall, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wallHolder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Style.bg());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        Style.flatScrollBars(scroll, Style.bg());
        // Ostinato: BorderLayout otherwise lets the wall's minimum size eat the accounts strip.
        scroll.setMinimumSize(new Dimension(0, 80));
        cards.add(scroll, CenterLayout.WALL.wire());
        cards.add(warRoom(), CenterLayout.MAP.wire());

        JPanel middle = Style.panel(new BorderLayout(12, 0), Style.bg());
        middle.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        middle.add(cards, BorderLayout.CENTER);
        middle.add(focusPanel(), BorderLayout.EAST);
        root.add(middle, BorderLayout.CENTER);

        accounts = Style.panel(new BorderLayout(), Style.surface());
        accounts.setPreferredSize(new Dimension(400, 170));
        accounts.setMinimumSize(new Dimension(200, 150));
        // Ostinato: perf strip and link monitor sit between the wall and the accounts strip.
        telemetry = new TelemetryPanel();
        JPanel south = Style.panel(new BorderLayout(), Style.bg());
        south.add(telemetry.root, BorderLayout.NORTH);
        south.add(accounts, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        try {
            AccountPanel.attach(accounts, UnifiedChat.configDir());
        } catch (RuntimeException ignored) {
            // The wall still opens. Sign-in reports the reason on the panel when it can.
        }
        return root;
    }

    @Override
    public void theme(CenterTheme theme) {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(this::restyle);
    }

    /** Ostinato: live theme change. Rebuilds the content and keeps the frame, its place, and the selected bot. */
    private void restyle() {
        if (frame == null) {
            return;
        }
        views.clear();
        layoutButtons.clear();
        shownNames = List.of();
        focusBuiltFor = null;
        WindowChrome.restyle(frame);
        frame.setContentPane(content());
        frame.getContentPane().revalidate();
        frame.getContentPane().repaint();
        refresh();
        showCard(model.layout());
    }

    /** Title, subtitle, and the right-hand chips: server, alerts, Stop all. */
    private JComponent titleBar() {
        JPanel bar = Style.panel(new BorderLayout(12, 0), Style.surface());
        bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 6));
        JPanel titles = Style.panel(new GridLayout(2, 1, 0, 2), null);
        titles.add(Style.label("Ostinato command center", Style.text(), Font.BOLD, 15f));
        titles.add(Style.label(SUBTITLE, Style.muted(), Font.PLAIN, 12f));
        bar.add(titles, BorderLayout.WEST);

        JPanel chips = Style.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), null);
        serverChip = chip("", Style.text(), Style.line());
        chips.add(serverChip);
        alertChip = chip("", Style.muted(), Style.line());
        chips.add(alertChip);
        chips.add(themePicker());
        for (ButtonModel button : model.buttons(FormPlacement.HEADER)) {
            if (button.form().id().startsWith("layout-") || button.form().id().startsWith("theme-")) {
                continue;
            }
            JButton go = button.form().id().startsWith("stop")
                    ? Style.dangerButton(button.form().label()) : Style.button(button.form().label());
            go.addActionListener(event -> dispatch(button, Map.of(), null));
            chips.add(go);
        }
        JPanel right = Style.panel(new BorderLayout(), null);
        right.add(chips, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        // Ostinato: the header is the window's title bar. Drag it to move, double-click to maximize.
        JPanel chrome = Style.panel(new BorderLayout(), Style.surface());
        chrome.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Style.line()));
        chrome.add(bar, BorderLayout.CENTER);
        JPanel buttons = Style.panel(new BorderLayout(), null);
        buttons.add(WindowChrome.controls(CenterRuntime::close), BorderLayout.NORTH);
        chrome.add(buttons, BorderLayout.EAST);
        WindowChrome.draggable(chrome);
        return chrome;
    }

    /** Ostinato: the header theme picker. Choosing runs the registered {@code theme <name>} command. */
    private JComponent themePicker() {
        String current = CenterRuntime.themeName();
        JButton pick = Style.button("Theme: " + ThemeStore.label(current) + " ▾");
        pick.setToolTipText("Color theme. Same as #theme <name>. Saved per user.");
        pick.addActionListener(event -> {
            JPopupMenu menu = new JPopupMenu();
            menu.setBorder(BorderFactory.createLineBorder(Style.line(), 1));
            menu.setBackground(Style.raised());
            for (String name : ThemeStore.names(UnifiedChat.configDir())) {
                boolean on = name.equals(CenterRuntime.themeName());
                // Ostinato: the check mark carries the current choice, not color alone.
                JMenuItem item = new JMenuItem((on ? "✓ " : "    ") + ThemeStore.label(name));
                item.setFont(Style.font(on ? Font.BOLD : Font.PLAIN, 12f));
                item.setOpaque(true);
                item.setBackground(Style.raised());
                item.setForeground(Style.text());
                item.addActionListener(chosen -> chooseTheme(name));
                menu.add(item);
            }
            menu.show(pick, 0, pick.getHeight());
        });
        return pick;
    }

    private void chooseTheme(String name) {
        for (ButtonModel candidate : model.buttons(FormPlacement.HEADER)) {
            if ("theme-pick".equals(candidate.form().id())) {
                dispatch(candidate, Map.of("name", name), null);
                return;
            }
        }
        if (commands != null) {
            commands.execute("theme " + name);
        }
    }

    /** Wall | War room switch on the left, window forms (Spawn) on the right. */
    private JComponent toolbar() {
        JPanel bar = Style.panel(new BorderLayout(12, 0), Style.bg());
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        JPanel segment = Style.panel(new GridLayout(1, 2, 0, 0), null);
        ButtonGroup group = new ButtonGroup();
        for (CenterLayout layout : List.of(CenterLayout.WALL, CenterLayout.MAP)) {
            JToggleButton toggle = Style.toggle(layout == CenterLayout.WALL ? "Wall" : "War room");
            toggle.addActionListener(event -> selectLayout(layout));
            group.add(toggle);
            layoutButtons.put(layout, toggle);
            segment.add(toggle);
        }
        JPanel left = Style.panel(new FlowLayout(FlowLayout.LEFT, 0, 0), null);
        left.add(segment);
        bar.add(left, BorderLayout.WEST);
        JPanel forms = Style.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), null);
        for (ButtonModel button : model.buttons(FormPlacement.WINDOW)) {
            forms.add(inlineForm(button, null));
        }
        bar.add(forms, BorderLayout.EAST);
        return bar;
    }

    private JComponent warRoom() {
        JPanel panel = Style.panel(new BorderLayout(), Style.surface());
        panel.setBorder(Style.box(Style.line(), 24));
        JLabel text = Style.label("War room is a placeholder in v1. No map and no click-to-command.",
                Style.muted(), Font.PLAIN, 13f);
        text.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    /** The right-hand panel for the selected bot. Tile actions live here, not on every tile. */
    private JComponent focusPanel() {
        JPanel panel = Style.panel(null, Style.surface());
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        panel.add(left(Style.caption("Focus")));
        panel.add(Box.createVerticalStrut(6));
        focusName = Style.label("No bot selected", Style.text(), Font.BOLD, 20f);
        panel.add(left(focusName));
        panel.add(Box.createVerticalStrut(4));
        focusBadge = Style.label(" ", Style.muted(), Font.BOLD, 12f);
        panel.add(left(focusBadge));
        panel.add(Box.createVerticalStrut(8));
        focusDetails = Style.label(" ", Style.muted(), Font.PLAIN, 12f);
        focusDetails.setVerticalAlignment(SwingConstants.TOP);
        panel.add(left(focusDetails));
        panel.add(Box.createVerticalStrut(12));
        panel.add(left(Style.caption("Actions")));
        panel.add(Box.createVerticalStrut(6));
        focusActions = Style.panel(null, null);
        focusActions.setLayout(new BoxLayout(focusActions, BoxLayout.Y_AXIS));
        panel.add(left(focusActions));
        panel.add(Box.createVerticalGlue());
        // Ostinato: forms can outgrow a short window, so the panel scrolls instead of clipping.
        JScrollPane scroll = new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Style.accent(), 1));
        scroll.getViewport().setBackground(Style.surface());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        Style.flatScrollBars(scroll, Style.surface());
        scroll.setPreferredSize(new Dimension(300, 200));
        return scroll;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static JLabel chip(String text, Color color, Color edge) {
        JLabel chip = Style.label(text, color, Font.PLAIN, 12f);
        chip.setOpaque(true);
        chip.setBackground(Style.raised());
        chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        return chip;
    }

    private void selectLayout(CenterLayout next) {
        if (model.layout() == next) {
            showCard(next);
            return;
        }
        for (ButtonModel candidate : model.buttons(FormPlacement.HEADER)) {
            if (("layout-" + next.wire()).equals(candidate.form().id())) {
                model.press(caller(""), candidate, Map.of(), null, line -> commands != null && commands.execute(line));
                return;
            }
        }
        try {
            model.remember(next);
        } catch (java.io.IOException ignored) {
            // The card stays. The chat command reports a save failure.
        }
        showCard(next);
    }

    /** One form on a single row: a field per argument, then the button. */
    private JComponent inlineForm(ButtonModel button, String bot) {
        JPanel panel = Style.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), null);
        Map<String, JTextField> fields = new LinkedHashMap<>();
        for (ArgDecl arg : button.form().args()) {
            panel.add(Style.label(arg.name(), Style.muted(), Font.PLAIN, 12f));
            JTextField field = Style.field(bot == null ? 10 : 9, arg.name());
            fields.put(arg.name(), field);
            panel.add(field);
        }
        JButton go = Style.button(button.form().label());
        go.addActionListener(event -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getText());
            }
            if (bot != null && (values.get("name") == null || values.get("name").isBlank())) {
                values.put("name", bot);
            }
            dispatch(button, values, bot);
        });
        panel.add(go);
        return panel;
    }

    /** Focus-panel form: one labeled field per row, then the button, so it fits the narrow panel. */
    private JComponent stackedForm(ButtonModel button, String bot) {
        JPanel panel = Style.panel(new java.awt.GridBagLayout(), null);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(0, 0, 4, 6);
        c.anchor = java.awt.GridBagConstraints.WEST;
        Map<String, JTextField> fields = new LinkedHashMap<>();
        int y = 0;
        for (ArgDecl arg : button.form().args()) {
            if (bot != null && "name".equals(arg.name())) {
                continue;
            }
            c.gridx = 0;
            c.gridy = y;
            c.weightx = 0;
            c.fill = java.awt.GridBagConstraints.NONE;
            panel.add(Style.label(arg.name(), Style.muted(), Font.PLAIN, 12f), c);
            JTextField field = Style.field(8, arg.name());
            fields.put(arg.name(), field);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            panel.add(field, c);
            y++;
        }
        JButton go = Style.button(button.form().label());
        go.addActionListener(event -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getText());
            }
            if (bot != null) {
                values.putIfAbsent("name", bot);
            }
            dispatch(button, values, bot);
        });
        c.gridx = 1;
        c.gridy = y;
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        panel.add(go, c);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private void dispatch(ButtonModel button, Map<String, String> values, String bot) {
        model.press(caller(bot), button, values, bot, line -> commands != null && commands.execute(line));
    }

    private RegistryCall caller(String bot) {
        String actor = "local";
        String owner = "";
        try {
            owner = Baritone.settings().agentOwner.value;
        } catch (RuntimeException ignored) {
            owner = "";
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                actor = mc.player.getName().getString();
            }
        } catch (RuntimeException ignored) {
            actor = "local";
        }
        RegistryCall call = RegistryCall.window(actor, owner, "");
        return bot == null || bot.isBlank() ? call : call.withScope(baritone.swarm.center.RegistryCall.Scope.ONE, bot);
    }

    private void refresh() {
        if (model == null || wall == null) {
            return;
        }
        SwarmBehavior swarm = swarm();
        List<BotSnapshot> tiles = readTiles(swarm);
        lastTiles = tiles;
        if (telemetry != null) {
            telemetry.update(TelemetryPanel.read(localName(), swarm), System.currentTimeMillis());
        }
        model.tiles(tiles);
        String server = LiveTiles.serverLabel(readServer(), tiles);
        model.server(server);
        serverChip.setText("<html><span style='color:" + Style.hex(Style.theme().muted()) + "'>Server</span>&nbsp;&nbsp;"
                + escape(server) + "</html>");
        int alerts = TileStatus.alerts(tiles);
        alertChip.setText(alerts == 0 ? TileStatus.Level.IDLE.glyph + " No alerts"
                : TileStatus.Level.WARN.glyph + " " + alerts + (alerts == 1 ? " alert" : " alerts"));
        alertChip.setForeground(alerts == 0 ? Style.muted() : Style.warn());
        alertChip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(alerts == 0 ? Style.line() : Style.warn(), 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        List<String> names = tiles.stream().map(BotSnapshot::name).toList();
        if (selected == null || !names.contains(selected)) {
            selected = names.isEmpty() ? null : names.get(0);
        }
        if (!names.equals(shownNames)) {
            rebuild(tiles);
            shownNames = names;
        }
        for (BotSnapshot tile : tiles) {
            TileView view = views.get(tile.name());
            if (view != null) {
                view.update(tile, tile.name().equals(selected));
            }
        }
        updateFocus();
    }

    private void rebuild(List<BotSnapshot> tiles) {
        wall.removeAll();
        views.clear();
        for (BotSnapshot tile : tiles) {
            TileView view = new TileView(tile.name());
            views.put(tile.name(), view);
            wall.add(view.root);
        }
        wall.revalidate();
        wall.repaint();
    }

    private void select(String name) {
        selected = name;
        for (BotSnapshot tile : lastTiles) {
            TileView view = views.get(tile.name());
            if (view != null) {
                view.update(tile, tile.name().equals(selected));
            }
        }
        updateFocus();
    }

    private void updateFocus() {
        BotSnapshot bot = null;
        for (BotSnapshot tile : lastTiles) {
            if (tile.name().equals(selected)) {
                bot = tile;
            }
        }
        if (bot == null) {
            focusName.setText("No bot selected");
            focusBadge.setText(" ");
            focusDetails.setText("<html>Spawn a bot or join a world to see it here.</html>");
            if (focusBuiltFor != null) {
                focusActions.removeAll();
                focusActions.revalidate();
                focusActions.repaint();
                focusBuiltFor = null;
            }
            return;
        }
        TileStatus status = TileStatus.of(bot);
        focusName.setText(bot.name());
        focusBadge.setText(status.badge());
        focusBadge.setForeground(levelColor(status.level()));
        List<String> lines = TileFormatter.lines(bot);
        // Ostinato: a fixed width lets a long line (perf) wrap instead of running under the panel edge.
        // Words inside one "·" item stay together, so "ram 398/4050 MB" never splits.
        StringBuilder sb = new StringBuilder("<html><body style='width:" + FOCUS_TEXT_WIDTH + "px'>");
        for (int i = 1; i < lines.size(); i++) {
            if (i > 1) {
                sb.append("<br>");
            }
            sb.append(escape(lines.get(i)).replace(" ", "&nbsp;").replace("&nbsp;·&nbsp;", " · "));
        }
        focusDetails.setText(sb.append("</html>").toString());
        if (!bot.name().equals(focusBuiltFor)) {
            // Ostinato: rebuild the forms only when the selection changes, so typed text survives a refresh.
            focusActions.removeAll();
            for (ButtonModel button : model.buttons(FormPlacement.TILE)) {
                JComponent row;
                if (button.form().args().isEmpty() || isNameOnly(button)) {
                    JButton go = button.form().id().startsWith("despawn")
                            ? Style.dangerButton(button.form().label()) : Style.button(button.form().label());
                    String name = bot.name();
                    go.addActionListener(event -> dispatch(button, Map.of("name", name), name));
                    JPanel holder = Style.panel(new FlowLayout(FlowLayout.LEFT, 0, 0), null);
                    holder.add(go);
                    row = holder;
                } else {
                    row = stackedForm(button, bot.name());
                }
                focusActions.add(left(row));
                focusActions.add(Box.createVerticalStrut(6));
            }
            focusActions.revalidate();
            focusActions.repaint();
            focusBuiltFor = bot.name();
        }
    }

    private static boolean isNameOnly(ButtonModel button) {
        return button.form().args().size() == 1 && "name".equals(button.form().args().get(0).name());
    }

    static Color levelColor(TileStatus.Level level) {
        return switch (level) {
            case OK -> Style.ok();
            case IDLE -> Style.muted();
            case WARN -> Style.warn();
            case BAD -> Style.danger();
        };
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One wall tile: dark vitals area on top, then status dot, name and a status word. Click selects. */
    private final class TileView {
        final JPanel root;
        private final JLabel task;
        private final Vitals vitals = new Vitals();
        private final JLabel dot;
        private final JLabel badge;

        TileView(String name) {
            root = Style.panel(new BorderLayout(0, 8), Style.surface());
            JPanel thumb = Style.panel(new BorderLayout(0, 6), Style.bg());
            thumb.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            task = Style.label(" ", Style.muted(), Font.PLAIN, 12f);
            thumb.add(task, BorderLayout.NORTH);
            thumb.add(vitals, BorderLayout.CENTER);
            root.add(thumb, BorderLayout.CENTER);
            JPanel status = Style.panel(new BorderLayout(6, 0), null);
            dot = Style.label(TileStatus.Level.OK.glyph, Style.ok(), Font.BOLD, 13f);
            // Ostinato: glyphs come from a fallback font that JLabel undersizes, so fix the width.
            dot.setHorizontalAlignment(SwingConstants.CENTER);
            dot.setPreferredSize(new Dimension(dot.getFontMetrics(dot.getFont()).getHeight() + 4,
                    dot.getFontMetrics(dot.getFont()).getHeight()));
            status.add(dot, BorderLayout.WEST);
            status.add(Style.label(name, Style.text(), Font.BOLD, 13f), BorderLayout.CENTER);
            badge = Style.label(" ", Style.muted(), Font.PLAIN, 11f);
            status.add(badge, BorderLayout.EAST);
            root.add(status, BorderLayout.SOUTH);
            root.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            root.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(name);
                }
            });
        }

        void update(BotSnapshot tile, boolean isSelected) {
            TileStatus status = TileStatus.of(tile);
            Color color = levelColor(status.level());
            dot.setText(status.level().glyph);
            dot.setForeground(color);
            badge.setText(status.word());
            badge.setForeground(status.alert() ? color : Style.muted());
            String what = tile.task() == null || tile.task().isBlank() ? "idle" : tile.task();
            task.setText(what);
            vitals.set(tile.health(), tile.hunger());
            // Ostinato: border width stays 2 px so a state change does not shift the grid.
            Color edge = isSelected ? Style.accent() : status.alert() ? Style.danger() : Style.line();
            int width = isSelected || status.alert() ? 2 : 1;
            root.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, width),
                            BorderFactory.createEmptyBorder(2 - width, 2 - width, 2 - width, 2 - width)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        }
    }

    /** Three tile columns that follow the window width and scroll vertically. */
    private static final class WallPanel extends JPanel implements Scrollable {
        WallPanel() {
            super(new GridLayout(0, 3, 12, 12));
            setBackground(Style.bg());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Ostinato: the primary client's swarm behavior, or null before a client exists. */
    private static SwarmBehavior swarm() {
        try {
            return ((Baritone) baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()).getSwarmBehavior();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String localName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.player != null ? mc.player.getName().getString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ostinato: a peer's last sample by name, unknown when it has sent none. */
    private static SwarmSample peerSample(SwarmBehavior swarm, String name) {
        if (swarm == null || name == null) {
            return SwarmSample.UNKNOWN;
        }
        for (SwarmBehavior.PeerView view : swarm.peerViews()) {
            if (view.link().name().equalsIgnoreCase(name)) {
                return view.link().sample();
            }
        }
        return SwarmSample.UNKNOWN;
    }

    private List<BotSnapshot> readTiles(SwarmBehavior swarm) {
        BotSnapshot local = null;
        String server = readServer();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                int health = (int) mc.player.getHealth();
                int hunger = mc.player.getFoodData().getFoodLevel();
                local = LiveTiles.localPlayer(mc.player.getName().getString(), health, hunger, server,
                        swarm == null ? SwarmSample.UNKNOWN : swarm.localSample());
            }
        } catch (RuntimeException ignored) {
            local = null;
        }
        List<BotSnapshot> children = new ArrayList<>();
        for (SwarmSpawnRegistry.Child child : SwarmSpawnRegistry.INSTANCE.list()) {
            boolean alive = child.process() != null && child.process().isAlive();
            children.add(LiveTiles.child(child.name(), child.server(), alive, peerSample(swarm, child.name())));
        }
        return LiveTiles.compose(local, children);
    }

    private static String readServer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return mc.getCurrentServer().ip;
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private void showCard(CenterLayout layout) {
        if (cardLayout == null || cards == null) {
            return;
        }
        CenterLayout use = layout == null ? CenterLayout.WALL : layout;
        cardLayout.show(cards, use.wire());
        JToggleButton toggle = layoutButtons.get(use);
        if (toggle != null && !toggle.isSelected()) {
            toggle.setSelected(true);
        }
    }

    private static void place(JFrame window) {
        List<WindowPlacement.Screen> screens = new ArrayList<>();
        if (!GraphicsEnvironment.isHeadless()) {
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                screens.add(new WindowPlacement.Screen(bounds.x, bounds.y, bounds.width, bounds.height));
            }
        }
        int[] at = WindowPlacement.place(screens, System.getProperty("ostinato.windowPos"));
        window.setLocation(at[0], at[1]);
    }

    /** The account manager fills this panel. Null until the frame exists. */
    public static JPanel accountsPanel() {
        return INSTANCE.accounts;
    }
}
