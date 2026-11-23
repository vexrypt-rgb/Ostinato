package baritone.swarm.center.app;

import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.SwarmView;
import baritone.api.command.ArgDecl;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 window, one bot's tile on the Mission control wall: status glyph and word, task line, health and
 * hunger bars, inventory summary, alerts, and the catalog's tile forms. Each form shows its equivalent {@code #} line
 * as you type and the host's reply after a press. Built once per bot; {@link #update} only changes text, so typed
 * values survive refreshes.
 *
 * <p>Component names (tests and the review checklist use them): {@code tile:<bot>} and
 * {@code tile:<bot>:status|task|bars|inventory|position|alerts|preview|result}, plus
 * {@code tile:<bot>:button:<formId>} and {@code tile:<bot>:field:<formId>:<arg>}.
 */
final class TileView {

    /** Alerts listed on a tile; more show as "+N more". */
    static final int MAX_ALERTS = 3;

    private final AppStyle style;
    private final Commands commands;
    final String bot;
    final JPanel root;
    private final JLabel glyph, name, status, task, inventory, position, perf, progress, preview, result;
    private final Bars bars;
    private final JPanel alerts;
    private final JPanel actions;
    private List<String> builtForms = List.of();
    private String host;
    private final Map<String, Map<String, JTextField>> fields = new LinkedHashMap<>();
    private String armedDespawn;
    private String cancelTarget;
    private boolean selected;
    private TileStatus lastStatus = new TileStatus(TileStatus.Level.IDLE, "idle");
    private Runnable onSelect = () -> {};
    private Runnable onOpen = () -> {};

    TileView(AppStyle style, Commands commands, String bot) {
        this.style = style;
        this.commands = commands;
        this.bot = bot;
        root = style.panel(new BorderLayout(0, 6), style.surface());
        root.setName("tile:" + bot);
        root.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    onSelect.run();
                    if (e.getClickCount() >= 2) {
                        onOpen.run();
                    }
                }
            }
        });

        JPanel head = style.panel(new BorderLayout(6, 0), null);
        glyph = named(style.label(TileStatus.Level.IDLE.glyph, style.muted(), Font.BOLD, 14f), "glyph");
        name = style.label(bot, style.text(), Font.BOLD, 14f);
        status = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "status");
        head.add(glyph, BorderLayout.WEST);
        head.add(name, BorderLayout.CENTER);
        head.add(status, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);

        JPanel body = style.panel(null, null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        task = named(style.label(" ", style.text(), Font.PLAIN, 12f), "task");
        progress = named(style.label(" ", style.accent(), Font.PLAIN, 11f), "progress");
        position = named(style.label(" ", style.faint(), Font.PLAIN, 11f), "position");
        bars = new Bars(style);
        bars.setName("tile:" + bot + ":bars");
        perf = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "perf");
        inventory = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "inventory");
        alerts = style.panel(null, null);
        alerts.setLayout(new BoxLayout(alerts, BoxLayout.Y_AXIS));
        alerts.setName("tile:" + bot + ":alerts");
        for (JComponent c : new JComponent[]{task, progress, position, bars, perf, inventory, alerts}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(c);
            body.add(Box.createVerticalStrut(3));
        }
        root.add(body, BorderLayout.CENTER);

        JPanel foot = style.panel(null, null);
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        actions = style.panel(null, null);
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        preview = named(style.label(" ", style.accent(), Font.PLAIN, 11f), "preview");
        result = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "result");
        for (JComponent c : new JComponent[]{actions, preview, result}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            foot.add(c);
        }
        root.add(foot, BorderLayout.SOUTH);
    }

    private JLabel named(JLabel l, String part) {
        l.setName("tile:" + bot + ":" + part);
        return l;
    }

    void update(SwarmView view, SwarmView.BotState b) {
        cancelTarget = TileText.cancelTarget(b.feed().executions());
        List<Alert> mine = view.alertsFor(bot);
        TileStatus s = Alerts.status(b, mine);
        glyph.setText(s.level().glyph);
        glyph.setForeground(style.level(s.level()));
        status.setText(s.word());
        status.setForeground(s.alert() ? style.level(s.level()) : style.muted());
        task.setText(TileText.task(b.feed()));
        String step = TileDetails.progress(b.feed().path());
        progress.setText(step.isEmpty() ? " " : step);
        progress.setVisible(!step.isEmpty());
        String age = TileText.age(b);
        position.setText(TileText.position(b.feed()) + (age.isEmpty() ? "" : " · " + age));
        bars.set(b.feed().health(), b.feed().hunger());
        perf.setText(TileDetails.perfStrip(b.feed().perf()));
        inventory.setText(TileText.inventory(b.feed().inventory()));

        alerts.removeAll();
        for (int i = 0; i < mine.size() && i < MAX_ALERTS; i++) {
            Alert a = mine.get(i);
            alerts.add(style.label(a.badge(), style.level(a.level()), Font.PLAIN, 11f));
        }
        if (mine.size() > MAX_ALERTS) {
            alerts.add(style.label("+" + (mine.size() - MAX_ALERTS) + " more", style.muted(), Font.PLAIN, 11f));
        }
        alerts.revalidate();

        lastStatus = s;
        applyBorder();

        List<ButtonModel> forms = view.catalog() == null ? List.of()
                : view.catalog().models(baritone.api.command.FormPlacement.TILE);
        List<String> ids = forms.stream().map(f -> f.command() + "/" + f.form().id()).toList();
        host = view.hostBot();
        if (!ids.equals(builtForms)) {
            buildActions(forms);
            builtForms = ids;
        }
        if (forms.isEmpty()) {
            preview.setText(view.catalog() == null ? "commands off: no catalog from a command host" : " ");
        }
    }

    /** Selects this tile (accent border). A click on the tile body calls {@code onSelect}. */
    void setSelected(boolean on) {
        selected = on;
        applyBorder();
    }

    void onSelect(Runnable select) {
        onSelect = select == null ? () -> {} : select;
    }

    /** A double-click opens this bot on the war-room map. */
    void onOpen(Runnable open) {
        onOpen = open == null ? () -> {} : open;
    }

    /** Border stays one width so a state change never shifts the grid. Selection uses the accent. */
    private void applyBorder() {
        root.setBorder(style.box(selected ? style.accent()
                : lastStatus.alert() ? style.level(lastStatus.level()) : style.line(), 2, 8));
    }

    /**
     * One row per form that has a required field (goto, mine); the forms whose fields are all optional or the tile's
     * own name (cancel, despawn) share one row of plain buttons. Optional fields stay off the tile, so those send
     * their tile defaults (cancel: the bot's current command).
     */
    private void buildActions(List<ButtonModel> forms) {
        actions.removeAll();
        fields.clear();
        JPanel plain = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 2), null);
        plain.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (ButtonModel b : forms) {
            List<ArgDecl> shown = !hasRequired(b) ? List.of()
                    : b.form().args().stream().filter(a -> !"name".equals(a.name())).toList();
            JPanel row = shown.isEmpty() ? plain : style.panel(new FlowLayout(FlowLayout.LEFT, 4, 2), null);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            Map<String, JTextField> mine = new LinkedHashMap<>();
            for (ArgDecl arg : shown) {
                JTextField f = style.field(arg.kind() == baritone.api.command.ArgKind.REST ? 12 : 7, arg.name(),
                        arg.name() + (arg.required() ? "" : " (optional)"));
                f.setName("tile:" + bot + ":field:" + b.form().id() + ":" + arg.name());
                f.getDocument().addDocumentListener(new Changed(() -> showPreview(b)));
                mine.put(arg.name(), f);
                row.add(f);
            }
            fields.put(b.form().id(), mine);
            boolean danger = b.form().id().startsWith("despawn");
            JButton go = danger ? style.dangerButton(b.form().label()) : style.button(b.form().label());
            go.setName("tile:" + bot + ":button:" + b.form().id());
            go.setToolTipText(Commands.preview(b, values(b), bot, host));
            go.addActionListener(e -> click(b, go));
            row.add(go);
            if (row != plain) {
                actions.add(row);
            }
        }
        if (plain.getComponentCount() > 0) {
            actions.add(plain);
        }
        actions.revalidate();
        actions.repaint();
    }

    private static boolean hasRequired(ButtonModel b) {
        return b.form().args().stream().anyMatch(a -> a.required() && !"name".equals(a.name()));
    }

    private Map<String, String> values(ButtonModel b) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, JTextField> mine = fields.get(b.form().id());
        if (mine != null) {
            for (Map.Entry<String, JTextField> e : mine.entrySet()) {
                out.put(e.getKey(), e.getValue().getText());
            }
        }
        withCancelTarget(b, out, cancelTarget);
        return out;
    }

    /** Ostinato: a Cancel with no typed id stops the bot's running command (the protocol cancels by id only). */
    static void withCancelTarget(ButtonModel b, Map<String, String> values, String target) {
        String typed = values.get("id");
        if ("cancel".equals(b.form().id()) && (typed == null || typed.isBlank()) && target != null) {
            values.put("id", target);
        }
    }

    private void showPreview(ButtonModel b) {
        preview.setText(Commands.preview(b, values(b), bot, host));
    }

    private void click(ButtonModel b, JButton go) {
        Map<String, String> v = values(b);
        String line = Commands.preview(b, v, bot, host);
        preview.setText(line);
        if (!Commands.ready(b, v, bot)) {
            result.setText("not sent: " + line);
            result.setForeground(style.warn());
            return;
        }
        // Ostinato: a despawn needs a second click within 3 s; no dialog, so nothing takes focus.
        if (b.form().id().startsWith("despawn") && !b.form().id().equals(armedDespawn)) {
            armedDespawn = b.form().id();
            go.setText("Confirm " + b.form().label().toLowerCase(java.util.Locale.ROOT));
            Timer t = new Timer(3000, e -> {
                armedDespawn = null;
                go.setText(b.form().label());
            });
            t.setRepeats(false);
            t.start();
            return;
        }
        armedDespawn = null;
        go.setText(b.form().label());
        result.setText("sending " + line + " ...");
        result.setForeground(style.muted());
        commands.press(b, v, bot, this::showReply);
    }

    void showReply(CenterReply r) {
        result.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        result.setForeground(r.admitted() ? style.ok() : style.warn());
    }

    /** Text-only view of the tile for tests and the accessible description. */
    List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add(glyph.getText() + " " + bot + " " + status.getText());
        out.add(task.getText());
        out.add(position.getText());
        out.add(bars.text());
        out.add(inventory.getText());
        for (Component c : alerts.getComponents()) {
            out.add(((JLabel) c).getText());
        }
        return out;
    }

    private static final class Changed implements DocumentListener {
        private final Runnable run;

        Changed(Runnable run) {
            this.run = run;
        }

        @Override public void insertUpdate(DocumentEvent e) { run.run(); }
        @Override public void removeUpdate(DocumentEvent e) { run.run(); }
        @Override public void changedUpdate(DocumentEvent e) { run.run(); }
    }
}
