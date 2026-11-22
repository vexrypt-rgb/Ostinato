package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 window, the Mission control wall (the default layout and the reference slice). One
 * {@link TileView} per bot in the feed, in name order, in as many columns as fit ({@link #TILE_WIDTH} each). Tiles
 * are kept across refreshes and only added or removed when the set of bots changes.
 */
public final class WallView implements LayoutView {

    public static final int TILE_WIDTH = 330;

    private final AppStyle style;
    private final Commands commands;
    private final Grid grid;
    private final JScrollPane scroll;
    private final JLabel empty;
    private final JPanel holder;
    private final Map<String, TileView> tiles = new LinkedHashMap<>();
    private List<String> shown = List.of();

    public WallView(AppStyle style, Commands commands) {
        this.style = style;
        this.commands = commands;
        grid = new Grid();
        grid.setBackground(style.bg());
        grid.setName("wall.grid");
        holder = style.panel(new BorderLayout(), style.bg());
        holder.add(grid, BorderLayout.NORTH);
        empty = style.label("No bot feeds yet. Turn on swarmCenterFeed on the bots and point them at this directory.",
                style.muted(), Font.PLAIN, 13f);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setName("wall.empty");
        holder.add(empty, BorderLayout.CENTER);
        scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        scroll.getViewport().setBackground(style.bg());
        scroll.setBackground(style.bg());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setName("wall");
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.WALL;
    }

    @Override
    public JComponent component() {
        return scroll;
    }

    @Override
    public void update(SwarmView view) {
        List<String> names = view.bots().stream().map(SwarmView.BotState::name).toList();
        if (!names.equals(shown)) {
            grid.removeAll();
            Map<String, TileView> keep = new LinkedHashMap<>();
            for (String n : names) {
                TileView t = tiles.get(n);
                keep.put(n, t != null ? t : new TileView(style, commands, n));
            }
            tiles.clear();
            tiles.putAll(keep);
            for (TileView t : tiles.values()) {
                grid.add(t.root);
            }
            shown = names;
            empty.setVisible(names.isEmpty());
            grid.revalidate();
            grid.repaint();
        }
        for (SwarmView.BotState b : view.bots()) {
            TileView t = tiles.get(b.name());
            if (t != null) {
                t.update(view, b);
            }
        }
    }

    /** The tile for a bot, for tests. */
    TileView tile(String bot) {
        return tiles.get(bot);
    }

    /** Columns follow the viewport width; rows scroll. */
    private final class Grid extends JPanel implements Scrollable {
        Grid() {
            super(new GridLayout(0, 3, 12, 12));
        }

        @Override
        public void doLayout() {
            int cols = Math.max(1, (scroll.getViewport().getWidth() + 12) / (TILE_WIDTH + 12));
            GridLayout g = (GridLayout) getLayout();
            if (g.getColumns() != cols) {
                g.setColumns(cols);
            }
            super.doLayout();
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
