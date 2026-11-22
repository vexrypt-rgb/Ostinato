package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, the area tool and its order bar. Dragging a rectangle (while {@code area:tool} is on)
 * keeps the box, its size and a Y range. One Send click issues one request per selected bot. Names:
 * {@code area:tool}, {@code area:size}, {@code area:y1}, {@code area:y2}, {@code area:ore},
 * {@code area:mode:goto}, {@code area:mode:mine}, {@code area:preview}, {@code area:send}, {@code area:replies},
 * {@code area:reason}.
 */
final class AreaBar {

    private final AppStyle style;
    private final Commands commands;
    private final JToggleButton tool;
    private final JPanel bar;
    private final JLabel size;
    private final JTextField y1;
    private final JTextField y2;
    private final JTextField ore;
    private final JToggleButton gotoMode;
    private final JToggleButton mineMode;
    private final JLabel preview;
    private final JLabel replies;
    private final JLabel reason;
    private final JButton send;
    private final List<String> replyLines = new ArrayList<>();
    private SwarmView view = SwarmView.EMPTY;
    private List<String> selected = List.of();
    private char dim = 'o';
    private int ax1, az1, ax2, az2;
    private boolean haveArea;
    private boolean filling;

    AreaBar(AppStyle style, Commands commands, Consumer<Boolean> armed) {
        this.style = style;
        this.commands = commands;
        tool = style.toggle("Area");
        tool.setName("area:tool");
        tool.addActionListener(e -> armed.accept(tool.isSelected()));

        bar = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 2), style.surface());
        bar.setName("area");
        size = style.label("no area", style.muted(), Font.PLAIN, 12f);
        size.setName("area:size");
        y1 = field("area:y1", 4);
        y2 = field("area:y2", 4);
        ore = field("area:ore", 10);
        ButtonGroup modes = new ButtonGroup();
        gotoMode = mode(modes, "Goto area", "area:mode:goto");
        mineMode = mode(modes, "Mine area", "area:mode:mine");
        gotoMode.setSelected(true);
        preview = style.label(" ", style.accent(), Font.PLAIN, 11f);
        preview.setName("area:preview");
        send = style.button("Send");
        send.setName("area:send");
        send.addActionListener(e -> send());
        replies = style.label(" ", style.muted(), Font.PLAIN, 11f);
        replies.setName("area:replies");
        reason = style.label(" ", style.warn(), Font.PLAIN, 11f);
        reason.setName("area:reason");
        bar.add(size);
        bar.add(style.label("Y", style.muted(), Font.PLAIN, 12f));
        bar.add(y1);
        bar.add(y2);
        bar.add(ore);
        bar.add(gotoMode);
        bar.add(mineMode);
        bar.add(send);
        bar.add(preview);
        bar.add(reason);
        bar.add(replies);
        onEdit(y1);
        onEdit(y2);
        onEdit(ore);
    }

    JComponent tool() { return tool; }

    JComponent bar() { return bar; }

    void setArea(int x1, int z1, int x2, int z2, List<Integer> ys) {
        ax1 = Math.min(x1, x2);
        ax2 = Math.max(x1, x2);
        az1 = Math.min(z1, z2);
        az2 = Math.max(z1, z2);
        haveArea = true;
        size.setText((ax2 - ax1 + 1) + " x " + (az2 - az1 + 1));
        int[] y = AreaOrders.yRange(ys);
        filling = true;
        y1.setText(Integer.toString(y[0]));
        y2.setText(Integer.toString(y[1]));
        filling = false;
        refresh();
    }

    /** Keep a drawn box without resetting the Y fields the user typed. */
    void keepBox(int x1, int z1, int x2, int z2) {
        ax1 = Math.min(x1, x2);
        ax2 = Math.max(x1, x2);
        az1 = Math.min(z1, z2);
        az2 = Math.max(z1, z2);
        haveArea = true;
        size.setText((ax2 - ax1 + 1) + " x " + (az2 - az1 + 1));
    }

    void arm(boolean on) {
        tool.setSelected(on);
    }

    void mine(boolean on) {
        if (on) {
            mineMode.setSelected(true);
        } else {
            gotoMode.setSelected(true);
        }
    }

    boolean mineSelected() {
        return mineMode.isSelected();
    }

    void refreshNow() {
        refresh();
    }

    void sync(SwarmView view, List<String> selected, char dim) {
        this.view = view == null ? SwarmView.EMPTY : view;
        this.selected = selected == null ? List.of() : List.copyOf(selected);
        this.dim = dim;
        refresh();
    }

    private void refresh() {
        Plan plan = plan();
        reason.setText(plan.refusal == null ? " " : plan.refusal);
        preview.setText(plan.lines.isEmpty() ? " " : String.join(" | ", plan.lines));
        send.setEnabled(plan.refusal == null && !plan.lines.isEmpty());
    }

    private void send() {
        Plan plan = plan();
        if (plan.refusal != null || plan.lines.isEmpty()) {
            reason.setText(plan.refusal == null ? " " : plan.refusal);
            return;
        }
        preview.setText(String.join(" | ", plan.lines));
        if (plan.mine) {
            for (int i = 0; i < plan.lines.size(); i++) {
                commands.line(plan.lines.get(i), plan.bots.get(i), this::showReply);
            }
        } else {
            ButtonModel gotoForm = gotoForm();
            if (gotoForm == null) {
                reason.setText("commands off: no catalog from a command host");
                return;
            }
            for (int i = 0; i < plan.assigns.size(); i++) {
                AreaOrders.Assign a = plan.assigns.get(i);
                commands.press(gotoForm, Map.of("target", a.target()), a.bot(), this::showReply);
            }
        }
    }

    private void showReply(CenterReply r) {
        replyLines.add((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        while (replyLines.size() > 12) {
            replyLines.remove(0);
        }
        replies.setText(String.join(" | ", replyLines));
    }

    private Plan plan() {
        if (!haveArea) {
            return Plan.refuse("drag a rectangle");
        }
        int iy1;
        int iy2;
        try {
            iy1 = Integer.parseInt(y1.getText().trim());
            iy2 = Integer.parseInt(y2.getText().trim());
        } catch (NumberFormatException e) {
            return Plan.refuse("Y range");
        }
        AreaOrders.Box box = new AreaOrders.Box(ax1, iy1, az1, ax2, iy2, az2);
        if (mineMode.isSelected()) {
            if (!hostIsCoordinator()) {
                return Plan.refuse(AreaOrders.HOST_REASON);
            }
            String block = ore.getText() == null ? "" : ore.getText().trim();
            AreaOrders.Mine mine = AreaOrders.mine(box, selected, block);
            if (!mine.ok()) {
                return Plan.refuse(mine.refusal());
            }
            return Plan.mine(mine.lines(dim, block), mine.strips().stream().map(AreaOrders.Strip::bot).toList());
        }
        List<AreaOrders.BotAt> bots = new ArrayList<>();
        for (String name : selected) {
            SwarmView.BotState b = view.bot(name);
            if (b != null && b.feed().pos() != null && b.feed().pos().dim() == dim) {
                bots.add(new AreaOrders.BotAt(name, b.feed().pos().x(), b.feed().pos().y(), b.feed().pos().z()));
            }
        }
        if (bots.isEmpty()) {
            return Plan.refuse(AreaOrders.SELECT_REASON);
        }
        List<AreaOrders.Assign> assigns = AreaOrders.spread(box, bots);
        List<String> lines = new ArrayList<>();
        ButtonModel gotoForm = gotoForm();
        String host = view.hostBot() == null ? "" : view.hostBot();
        for (AreaOrders.Assign a : assigns) {
            lines.add(gotoForm == null
                    ? "goto " + a.target()
                    : Commands.preview(gotoForm, Map.of("target", a.target()), a.bot(), host));
        }
        return Plan.gotoArea(lines, assigns);
    }

    private boolean hostIsCoordinator() {
        String host = view.hostBot();
        SwarmView.BotState b = host == null ? null : view.bot(host);
        return b != null && b.feed().coordinator();
    }

    private ButtonModel gotoForm() {
        if (view.catalog() == null) {
            return null;
        }
        for (ButtonModel b : view.catalog().models(FormPlacement.TILE)) {
            if ("goto".equals(b.form().id())) {
                return b;
            }
        }
        return null;
    }

    private JTextField field(String name, int columns) {
        JTextField f = style.field(columns, name.substring(name.indexOf(':') + 1), name);
        f.setName(name);
        return f;
    }

    private JToggleButton mode(ButtonGroup group, String label, String name) {
        JToggleButton b = style.toggle(label);
        b.setName(name);
        b.addActionListener(e -> refresh());
        group.add(b);
        return b;
    }

    private void onEdit(JTextField f) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if (!filling) refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { if (!filling) refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { if (!filling) refresh(); }
        });
    }

    private static final class Plan {
        final boolean mine;
        final List<String> lines;
        final List<String> bots;
        final List<AreaOrders.Assign> assigns;
        final String refusal;

        private Plan(boolean mine, List<String> lines, List<String> bots, List<AreaOrders.Assign> assigns, String refusal) {
            this.mine = mine;
            this.lines = lines;
            this.bots = bots;
            this.assigns = assigns;
            this.refusal = refusal;
        }

        static Plan refuse(String reason) {
            return new Plan(false, List.of(), List.of(), List.of(), reason);
        }

        static Plan mine(List<String> lines, List<String> bots) {
            return new Plan(true, lines, bots, List.of(), null);
        }

        static Plan gotoArea(List<String> lines, List<AreaOrders.Assign> assigns) {
            return new Plan(false, lines, List.of(), assigns, null);
        }
    }
}
