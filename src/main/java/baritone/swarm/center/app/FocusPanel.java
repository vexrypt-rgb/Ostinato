package baritone.swarm.center.app;

import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Ostinato: Phase 3 window, the wall's focus panel. One bot, opened without leaving the wall. The grid stays; this
 * panel sits at its right until Esc or the close button.
 *
 * <p>Names: {@code focus}, {@code focus:close}, and {@code focus:position|dimension|path|executions|leases|inventory|
 * perf|link|age|gather|alerts}.
 */
final class FocusPanel extends JPanel {

    /** Narrow enough that 3 tile columns still fit in a 1280-wide window. */
    static final int WIDTH = 210;

    private final AppStyle style;
    private final JLabel title;
    private final JLabel position, dimension, path, executions, leases, inventory, perf, link, age, gather, alerts;
    private final Runnable onClose;

    FocusPanel(AppStyle style, Runnable onClose) {
        super(new BorderLayout());
        this.style = style;
        this.onClose = onClose;
        setBackground(style.surface());
        setOpaque(true);
        setName("focus");
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, style.line()));

        JPanel head = style.panel(new BorderLayout(6, 0), style.surface());
        head.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        title = style.label("Focus", style.text(), Font.BOLD, 14f);
        title.setName("focus:title");
        JButton close = style.button("Close");
        close.setName("focus:close");
        close.addActionListener(e -> onClose.run());
        head.add(title, BorderLayout.CENTER);
        head.add(close, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        JPanel body = style.panel(null, style.surface());
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        position = row(body, "Position", "focus:position");
        dimension = row(body, "Dimension", "focus:dimension");
        path = row(body, "Path", "focus:path");
        executions = row(body, "Commands", "focus:executions");
        leases = row(body, "Leases", "focus:leases");
        inventory = row(body, "Inventory", "focus:inventory");
        perf = row(body, "Performance", "focus:perf");
        link = row(body, "Link", "focus:link");
        age = row(body, "Feed", "focus:age");
        gather = row(body, "Gather", "focus:gather");
        alerts = row(body, "Alerts", "focus:alerts");

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setBackground(style.surface());
        scroll.getViewport().setBackground(style.surface());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        setVisible(false);
    }

    private JLabel row(JPanel body, String caption, String name) {
        JLabel cap = style.label(caption, style.faint(), Font.PLAIN, 10f);
        cap.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel value = style.label(" ", style.text(), Font.PLAIN, 11f);
        value.setName(name);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(cap);
        body.add(value);
        body.add(Box.createVerticalStrut(6));
        return value;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, 400);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(WIDTH, 0);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(WIDTH, Integer.MAX_VALUE);
    }

    /** Shows {@code bot}, or hides the panel when the selection is empty. */
    void show(SwarmView view, String bot) {
        SwarmView.BotState b = bot == null || view == null ? null : view.bot(bot);
        if (b == null) {
            setVisible(false);
            return;
        }
        title.setText(b.name());
        position.setText(TileText.position(b.feed()));
        dimension.setText(TileDetails.dimension(b.feed()));
        path.setText(TileDetails.path(b.feed().path()));
        executions.setText(TileDetails.executions(b.feed().executions()));
        leases.setText(TileDetails.leases(b.feed().leases()));
        inventory.setText(TileText.inventory(b.feed().inventory()));
        perf.setText(TileDetails.perf(b.feed().perf()));
        link.setText(TileDetails.link(b.feed().link()));
        age.setText(TileDetails.age(b));
        gather.setText(TileDetails.gather(b.feed()));
        alerts.setText(TileDetails.alerts(view.alertsFor(b.name())));
        setVisible(true);
        revalidate();
    }
}
