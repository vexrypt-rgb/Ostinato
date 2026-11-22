package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, the Mission control wall (the default layout and the reference slice). One
 * {@link TileView} per bot in the feed, in name order, in as many columns as fit ({@link #TILE_WIDTH} each). Tiles
 * are kept across refreshes and only added or removed when the set of bots changes. Clicking a tile opens the
 * {@link FocusPanel} at the right; Esc or its close button returns to the full grid. The selection survives
 * refreshes and bots joining or leaving, and clears when the selected bot vanishes.
 */
public final class WallView implements LayoutView {

    public static final int TILE_WIDTH = 330;

    private final AppStyle style;
    private final Commands commands;
    private final Grid grid;
    private final JScrollPane scroll;
    private final JLabel empty;
    private final JPanel holder;
    private final JPanel root;
    private final FocusPanel focus;
    private final Map<String, TileView> tiles = new LinkedHashMap<>();
    private List<String> shown = List.of();
    private String selected;
    private SwarmView last = SwarmView.EMPTY;
    private Consumer<String> onOpen = name -> {};

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
        scroll.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "focus-clear");
        scroll.getActionMap().put("focus-clear", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                select(null);
            }
        });

        focus = new FocusPanel(style, () -> select(null));
        root = style.panel(new BorderLayout(), style.bg());
        root.setName("wall.frame");
        root.add(scroll, BorderLayout.CENTER);
        root.add(focus, BorderLayout.EAST);
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.WALL;
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** The selected bot, or null when the wall is showing the full grid. */
    public String selected() {
        return selected;
    }

    /** Double-click on a tile. The shell uses this to switch to the map centered on that bot. */
    public void onOpenOnMap(Consumer<String> open) {
        onOpen = open == null ? name -> {} : open;
    }

    /** Opens the focus panel on {@code name}, or closes it when {@code name} is null. */
    public void select(String name) {
        selected = name == null || name.isBlank() ? null : name;
        applySelection();
    }

    @Override
    public void update(SwarmView view) {
        last = view == null ? SwarmView.EMPTY : view;
        List<String> names = last.bots().stream().map(SwarmView.BotState::name).toList();
        if (selected != null && last.bot(selected) == null) {
            selected = null;
        }
        if (!names.equals(shown)) {
            grid.removeAll();
            Map<String, TileView> keep = new LinkedHashMap<>();
            for (String n : names) {
                TileView t = tiles.get(n);
                if (t == null) {
                    t = new TileView(style, commands, n);
                    String bot = n;
                    t.onSelect(() -> select(bot));
                    t.onOpen(() -> onOpen.accept(bot));
                }
                keep.put(n, t);
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
        for (SwarmView.BotState b : last.bots()) {
            TileView t = tiles.get(b.name());
            if (t != null) {
                t.update(last, b);
            }
        }
        applySelection();
    }

    private void applySelection() {
        for (Map.Entry<String, TileView> e : tiles.entrySet()) {
            e.getValue().setSelected(e.getKey().equals(selected));
        }
        focus.show(last, selected);
        root.revalidate();
        root.repaint();
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
