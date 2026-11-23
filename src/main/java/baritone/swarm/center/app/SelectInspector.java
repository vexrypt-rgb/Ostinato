package baritone.swarm.center.app;

import baritone.api.command.ArgDecl;
import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 window, the selection bar under the war-room map. One card per selected bot, using the same words
 * as a wall tile and the same tile forms (goto, mine, cancel, despawn), each sent through {@link Commands} for that
 * bot. Typed fields survive a refresh while the selection stays the same.
 *
 * <p>Names: {@code select:inspector}, {@code select:empty}, {@code select:card:<bot>},
 * {@code select:<bot>:task|position|inventory|preview|result}, {@code select:<bot>:button:<form>},
 * {@code select:<bot>:field:<form>:<arg>}.
 */
final class SelectInspector {

    private final AppStyle style;
    private final Commands commands;
    private final JPanel cards = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JScrollPane scroll;
    private final JLabel empty;
    private final Map<String, Card> byName = new LinkedHashMap<>();
    private String host = "";

    SelectInspector(AppStyle style, Commands commands) {
        this.style = style;
        this.commands = commands;
        cards.setBackground(style.surface());
        cards.setOpaque(true);
        empty = style.label("no bots selected", style.muted(), Font.PLAIN, 12f);
        empty.setName("select:empty");
        cards.add(empty);
        scroll = new JScrollPane(cards, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setName("select:inspector");
        scroll.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()));
        scroll.getViewport().setBackground(style.surface());
        scroll.setBackground(style.surface());
        applyHeight();
    }

    JComponent component() {
        return scroll;
    }

    void sync(SwarmView view, List<String> selected) {
        SwarmView shown = view == null ? SwarmView.EMPTY : view;
        host = shown.hostBot() == null ? "" : shown.hostBot();
        List<String> want = selected == null ? List.of() : selected;
        for (String name : new ArrayList<>(byName.keySet())) {
            if (!want.contains(name)) {
                cards.remove(byName.remove(name).root);
            }
        }
        for (String name : want) {
            Card card = byName.get(name);
            if (card == null) {
                card = new Card(name);
                byName.put(name, card);
                cards.add(card.root);
            }
            card.sync(shown);
        }
        empty.setVisible(want.isEmpty());
        applyHeight();
        cards.revalidate();
        cards.repaint();
    }

    private void applyHeight() {
        int h = byName.isEmpty() ? 24 : 132;
        scroll.setPreferredSize(new Dimension(200, h));
    }

    private final class Card {
        final String bot;
        final JPanel root;
        final JLabel task;
        final JLabel position;
        final JLabel inventory;
        final JLabel preview;
        final JLabel result;
        final JPanel actions;
        final Map<String, Map<String, JTextField>> fields = new LinkedHashMap<>();
        List<String> built = List.of();
        String armedDespawn;
        String cancelTarget;

        Card(String bot) {
            this.bot = bot;
            root = style.panel(new BorderLayout(0, 2), style.surface());
            root.setName("select:card:" + bot);
            root.setBorder(style.box(style.line(), 1, 4));
            JPanel body = style.panel(null, null);
            body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
            task = named(style.label(" ", style.text(), Font.PLAIN, 11f), "task");
            position = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "position");
            inventory = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "inventory");
            for (JLabel l : new JLabel[]{task, position, inventory}) {
                l.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(l);
            }
            root.add(body, BorderLayout.NORTH);
            actions = style.panel(null, null);
            actions.setLayout(new javax.swing.BoxLayout(actions, javax.swing.BoxLayout.Y_AXIS));
            preview = named(style.label(" ", style.accent(), Font.PLAIN, 11f), "preview");
            result = named(style.label(" ", style.muted(), Font.PLAIN, 11f), "result");
            JPanel foot = style.panel(null, null);
            foot.setLayout(new javax.swing.BoxLayout(foot, javax.swing.BoxLayout.Y_AXIS));
            for (JComponent c : new JComponent[]{actions, preview, result}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                foot.add(c);
            }
            root.add(foot, BorderLayout.CENTER);
        }

        void sync(SwarmView view) {
            SwarmView.BotState b = view.bot(bot);
            if (b == null) {
                task.setText("not in the swarm");
                position.setText(" ");
                inventory.setText(" ");
                actions.setVisible(false);
                return;
            }
            actions.setVisible(true);
            cancelTarget = TileText.cancelTarget(b.feed().executions());
            task.setText(TileText.task(b.feed()));
            position.setText(TileText.position(b.feed()));
            inventory.setText(TileText.inventory(b.feed().inventory()));
            List<ButtonModel> forms = view.catalog() == null ? List.of()
                    : view.catalog().models(FormPlacement.TILE);
            List<String> ids = new ArrayList<>();
            for (ButtonModel f : forms) {
                ids.add(f.command() + "/" + f.form().id());
            }
            if (!ids.equals(built)) {
                build(forms);
                built = ids;
            }
            if (forms.isEmpty()) {
                preview.setText(view.catalog() == null ? "commands off: no catalog from a command host" : " ");
            }
        }

        private JLabel named(JLabel l, String part) {
            l.setName("select:" + bot + ":" + part);
            return l;
        }

        private void build(List<ButtonModel> forms) {
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
                    f.setName("select:" + bot + ":field:" + b.form().id() + ":" + arg.name());
                    onChange(f, () -> showPreview(b));
                    mine.put(arg.name(), f);
                    row.add(f);
                }
                fields.put(b.form().id(), mine);
                boolean danger = b.form().id().startsWith("despawn");
                JButton go = danger ? style.dangerButton(b.form().label()) : style.button(b.form().label());
                go.setName("select:" + bot + ":button:" + b.form().id());
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
        }

        private Map<String, String> values(ButtonModel b) {
            Map<String, String> out = new LinkedHashMap<>();
            Map<String, JTextField> mine = fields.get(b.form().id());
            if (mine != null) {
                for (Map.Entry<String, JTextField> e : mine.entrySet()) {
                    out.put(e.getKey(), e.getValue().getText());
                }
            }
            TileView.withCancelTarget(b, out, cancelTarget);
            return out;
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

        private void showReply(CenterReply r) {
            result.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
            result.setForeground(r.admitted() ? style.ok() : style.warn());
        }
    }

    private static boolean hasRequired(ButtonModel b) {
        return b.form().args().stream().anyMatch(a -> a.required() && !"name".equals(a.name()));
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }
}
