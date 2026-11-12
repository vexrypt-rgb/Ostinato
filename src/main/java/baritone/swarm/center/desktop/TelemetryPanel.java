package baritone.swarm.center.desktop;

import baritone.behavior.SwarmBehavior;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmSample;
import baritone.swarm.center.LinkFormatter;
import baritone.swarm.center.PerfFormatter;
import baritone.swarm.center.TileStatus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: the performance strip and the swarm link monitor, side by side above the accounts strip.
 * Built with the theme's colors, so a theme change rebuilds it with the rest of the content. Every row
 * shows this client first, then each swarm peer from its last status sample. Levels carry a glyph as
 * well as a color.
 */
final class TelemetryPanel {

    /** One bot's row: its name, whether it is this client, its sample, and link facts only we know. */
    record Row(String name, boolean self, SwarmSample sample, boolean local, int receivedInWindow, long sampleAtMs) {}

    final JPanel root;
    private final JPanel perfRows;
    private final JPanel linkRows;
    private final JLabel linkNote;
    private List<Row> shown = List.of();

    TelemetryPanel() {
        root = Style.panel(new GridLayout(1, 2, 12, 0), Style.bg());
        root.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        perfRows = rows();
        linkRows = rows();
        linkNote = Style.label(LinkFormatter.budgetNote(-1), Style.faint(), Font.PLAIN, 11f);
        root.add(section("Performance", perfRows, null));
        root.add(section("Swarm link", linkRows, linkNote));
    }

    private static JPanel rows() {
        JPanel rows = Style.panel(null, Style.surface());
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        return rows;
    }

    private static JComponent section(String title, JPanel rows, JLabel note) {
        JPanel box = Style.panel(new BorderLayout(0, 4), Style.surface());
        box.setBorder(Style.box(Style.line(), 8));
        JPanel head = Style.panel(new BorderLayout(8, 0), null);
        // Ostinato: CENTER, not WEST, so the caption keeps any slack and is never cut to "PERFORMA...".
        head.add(Style.caption(title), BorderLayout.CENTER);
        if (note != null) {
            head.add(note, BorderLayout.EAST);
        }
        box.add(head, BorderLayout.NORTH);
        JPanel holder = Style.panel(new BorderLayout(), Style.surface());
        holder.add(rows, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Style.surface());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        Style.flatScrollBars(scroll, Style.surface());
        box.add(scroll, BorderLayout.CENTER);
        box.setPreferredSize(new Dimension(300, 112));
        return box;
    }

    /** Rows from the swarm behavior: this client, then peers. Safe with a null behavior (no client yet). */
    static List<Row> read(String selfName, SwarmBehavior swarm) {
        List<Row> out = new ArrayList<>();
        SwarmSample local = swarm == null ? SwarmSample.UNKNOWN : swarm.localSample();
        out.add(new Row(selfName == null || selfName.isBlank() ? "this client" : selfName, true, local, true, -1, -1));
        if (swarm != null) {
            for (SwarmBehavior.PeerView view : swarm.peerViews()) {
                SwarmControl.PeerLink p = view.link();
                out.add(new Row(p.name(), false, p.sample(), view.local(), p.receivedInWindow(), p.sampleAtMs()));
            }
        }
        return out;
    }

    void update(List<Row> rows, long nowMs) {
        if (rows.equals(shown) && rows.stream().allMatch(r -> r.self() || r.sampleAtMs() < 0)) {
            return; // Ostinato: nothing moved and no age to tick, so skip the relayout.
        }
        shown = rows;
        perfRows.removeAll();
        linkRows.removeAll();
        int cap = -1;
        for (Row row : rows) {
            perfRows.add(line(row, PerfFormatter.cells(row.sample())));
            List<PerfFormatter.Cell> link = new ArrayList<>(LinkFormatter.cells(row.sample()));
            link.add(LinkFormatter.route(row.local(), row.self()));
            if (!row.self()) {
                link.add(LinkFormatter.received(row.receivedInWindow()));
                link.add(LinkFormatter.age(row.sampleAtMs(), nowMs, SwarmControl.SAMPLE_INTERVAL_MS));
            } else {
                cap = row.sample().chatCap();
            }
            linkRows.add(line(row, link));
        }
        linkNote.setText(LinkFormatter.budgetNote(cap));
        perfRows.revalidate();
        perfRows.repaint();
        linkRows.revalidate();
        linkRows.repaint();
    }

    private static JComponent line(Row row, List<PerfFormatter.Cell> cells) {
        JPanel line = Style.panel(new FlowLayout(FlowLayout.LEFT, 10, 1), null);
        JLabel name = Style.label(row.name(), row.self() ? Style.accent() : Style.text(), Font.BOLD, 12f);
        name.setPreferredSize(new Dimension(96, name.getPreferredSize().height));
        name.setToolTipText(row.name());
        line.add(name);
        for (PerfFormatter.Cell cell : cells) {
            line.add(Style.label(text(cell), color(cell.level()), Font.PLAIN, 12f));
        }
        line.setAlignmentX(0f);
        return line;
    }

    /** Ostinato: a worrying figure carries the level glyph too, so it does not rely on color alone. */
    private static String text(PerfFormatter.Cell cell) {
        return cell.level() == TileStatus.Level.WARN || cell.level() == TileStatus.Level.BAD
                ? cell.level().glyph + " " + cell.text() : cell.text();
    }

    private static Color color(TileStatus.Level level) {
        return switch (level) {
            case OK -> Style.text();
            case IDLE -> Style.faint();
            case WARN -> Style.warn();
            case BAD -> Style.danger();
        };
    }
}
