package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: Phase 3 window, the War room map. Top-down XZ of the selected dimension, terrain from the bots' feeds,
 * one marker per bot in that dimension. Pan by dragging empty map or the arrow keys; zoom with the wheel, 1 to 16
 * pixels per block. A Y slider is not drawn: the feed is a surface overview, not a cave cut.
 *
 * <p>Names: {@code map}, {@code map:canvas}, {@code map:cursor}, {@code map:bots}, {@code map:dim:o|n|e},
 * {@code map:menu}, {@code map:center:<bot>}, {@code select:inspector}.
 */
public final class MapView implements LayoutView {

    private final AppStyle style;
    private final Commands commands;
    private final JPanel root;
    private final MapCanvas canvas;
    private final JLabel cursor;
    private final JLabel bots;
    private final JLabel hover;
    private final JLabel keys;
    private final JPanel menu;
    private final JToggleButton overworld, nether, end;
    private final SelectInspector inspector;
    private final AreaBar areaBar;
    private SwarmView last = SwarmView.EMPTY;
    private Path groupsFile;

    public MapView(AppStyle style, Commands commands) {
        this.style = style;
        this.commands = commands;
        root = style.panel(new BorderLayout(), style.bg());
        root.setName("map");
        canvas = new MapCanvas(style, new MapCanvas.Listener() {
            @Override
            public void cursor(int blockX, int blockZ) {
                cursor.setText(blockX + ", " + blockZ);
            }

            @Override
            public void markerMenu(String bot) {
                showMenu(bot);
            }

            @Override
            public void hover(String text) {
                hover.setText(text == null || text.isEmpty() ? " " : text);
            }

            @Override
            public void selectionChanged(boolean groupsStored) {
                if (groupsStored) {
                    saveGroups();
                }
                inspector.sync(last, canvas.selection.names());
                areaBar.sync(last, canvas.selection.names(), canvas.dim());
            }

            @Override
            public void areaDrawn(int x1, int z1, int x2, int z2) {
                List<Integer> ys = new ArrayList<>();
                for (String name : canvas.selection.names()) {
                    SwarmView.BotState b = last.bot(name);
                    if (b != null && b.feed().pos() != null) {
                        ys.add(b.feed().pos().blockY());
                    }
                }
                areaBar.setArea(x1, z1, x2, z2, ys);
            }
        });
        inspector = new SelectInspector(style, commands);
        areaBar = new AreaBar(style, commands, armed -> canvas.areaArmed = armed);

        JPanel header = style.panel(new BorderLayout(), style.surface());
        header.setBorder(style.box(style.line(), 0, 6));
        JPanel dims = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        ButtonGroup group = new ButtonGroup();
        overworld = dimButton(group, dims, "Overworld", 'o');
        nether = dimButton(group, dims, "Nether", 'n');
        end = dimButton(group, dims, "End", 'e');
        overworld.setSelected(true);
        dims.add(layerToggle(dims, "Leases", "map:toggle:leases", on -> canvas.showLeases = on));
        dims.add(layerToggle(dims, "Observations", "map:toggle:observations", on -> canvas.showObservations = on));
        dims.add(layerToggle(dims, "Paths", "map:toggle:paths", on -> canvas.showPaths = on));
        dims.add(layerToggle(dims, "Terrain", "map:toggle:terrain", on -> canvas.showTerrain = on));
        dims.add(areaBar.tool());
        header.add(dims, BorderLayout.WEST);
        JPanel info = style.panel(new FlowLayout(FlowLayout.RIGHT, 12, 0), null);
        bots = style.label("0 bots", style.muted(), Font.PLAIN, 12f);
        bots.setName("map:bots");
        cursor = style.label("0, 0", style.text(), Font.PLAIN, 12f);
        cursor.setName("map:cursor");
        info.add(bots);
        info.add(cursor);
        header.add(info, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        menu = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), style.surface());
        menu.setName("map:menu");
        menu.setVisible(false);
        JPanel south = style.panel(new BorderLayout(), style.surface());
        JLabel legend = style.label("region solid edge · vein dashed · chest square · key listed"
                + " · vein diamond · chest square · block circle · structure frame · waypoint plus"
                + " · confirmed filled · stale hollow · gone cross · suspect ring", style.muted(), Font.PLAIN, 11f);
        legend.setName("map:legend");
        keys = style.label("no key leases", style.muted(), Font.PLAIN, 11f);
        keys.setName("map:keys");
        hover = style.label(" ", style.text(), Font.PLAIN, 11f);
        hover.setName("map:hover");
        JPanel lines = style.panel(new java.awt.GridLayout(3, 1), null);
        lines.add(legend);
        lines.add(keys);
        lines.add(hover);
        south.add(areaBar.bar(), BorderLayout.NORTH);
        JPanel under = style.panel(new BorderLayout(), null);
        under.add(inspector.component(), BorderLayout.NORTH);
        under.add(lines, BorderLayout.CENTER);
        under.add(menu, BorderLayout.SOUTH);
        south.add(under, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        root.add(canvas, BorderLayout.CENTER);
    }

    private JToggleButton layerToggle(JPanel row, String label, String name, java.util.function.Consumer<Boolean> set) {
        JToggleButton b = style.toggle(label);
        b.setName(name);
        b.setSelected(true);
        b.addActionListener(e -> {
            set.accept(b.isSelected());
            canvas.repaint();
        });
        return b;
    }

    private JToggleButton dimButton(ButtonGroup group, JPanel row, String label, char dim) {
        JToggleButton b = style.toggle(label);
        b.setName("map:dim:" + dim);
        b.addActionListener(e -> {
            canvas.setDim(dim);
            areaBar.sync(last, canvas.selection.names(), dim);
        });
        group.add(b);
        row.add(b);
        return b;
    }

    private void showMenu(String bot) {
        menu.removeAll();
        if (bot == null) {
            menu.setVisible(false);
            menu.revalidate();
            return;
        }
        JButton center = style.button("Center on " + bot);
        center.setName("map:center:" + bot);
        center.addActionListener(e -> {
            canvas.centerOn(bot);
            menu.setVisible(false);
        });
        menu.add(center);
        menu.setVisible(true);
        menu.revalidate();
        root.repaint();
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.MAP;
    }

    @Override
    public JComponent component() {
        return root;
    }

    public MapCamera camera() {
        return canvas.camera;
    }

    public char dimension() {
        return canvas.dim();
    }

    /** Screen point of a marker, or null when that bot is not drawn in the current dimension. */
    public double[] markerScreen(String name) {
        return canvas.markerScreen(name);
    }

    public boolean centerOn(String name) {
        return canvas.centerOn(name);
    }

    public List<String> selected() {
        return canvas.selection.names();
    }

    /** Replace the selection and refresh the inspector. */
    public void replaceSelection(List<String> names) {
        canvas.selection.replace(names);
        inspector.sync(last, canvas.selection.names());
        areaBar.sync(last, canvas.selection.names(), canvas.dim());
    }

    /** Zoom and center the user left the camera at, and keep the next refresh from framing a bot. */
    public void placeCamera(int zoom, double x, double z) {
        canvas.camera.setZoom(zoom);
        canvas.camera.setCenter(x, z);
        canvas.markPlaced();
    }

    /** Put a theme rebuild back where the map was: camera, dimension, selection, groups, area. */
    public void restoreMap(int zoom, double x, double z, char dim, List<String> selected, String groups,
                           int[] area, boolean armed, boolean mine) {
        canvas.camera.setZoom(zoom);
        canvas.camera.setCenter(x, z);
        canvas.markPlaced();
        canvas.setDim(dim);
        canvas.selection.replace(selected);
        if (groups != null) {
            canvas.groups.parse(groups);
        }
        if (area != null && area.length == 4) {
            canvas.setAreaWorld(area[0], area[1], area[2], area[3]);
            areaBar.keepBox(area[0], area[1], area[2], area[3]);
        }
        canvas.areaArmed = armed;
        areaBar.arm(armed);
        areaBar.mine(mine);
    }

    public boolean areaArmed() {
        return canvas.areaArmed;
    }

    public boolean areaMine() {
        return areaBar.mineSelected();
    }

    public void refreshArea() {
        areaBar.refreshNow();
    }

    /** Inclusive XZ of the drawn area, or null when none has been dragged. */
    public int[] area() {
        return canvas.areaBounds();
    }

    public MapGroups groups() {
        return canvas.groups;
    }

    /** Control groups are read and written under {@code <dir>/prefs/<user>.properties}. */
    public void setPrefsDir(Path dir) {
        groupsFile = dir == null ? null : MapGroups.file(dir);
        if (groupsFile != null) {
            canvas.groups.read(groupsFile);
        }
    }

    private void saveGroups() {
        if (groupsFile == null) {
            return;
        }
        try {
            canvas.groups.save(groupsFile);
        } catch (IOException ignored) {
            // The in-memory group is kept. The next store tries the file again.
        }
    }

    @Override
    public void update(SwarmView view) {
        last = view == null ? SwarmView.EMPTY : view;
        canvas.setView(last);
        inspector.sync(last, canvas.selection.names());
        areaBar.sync(last, canvas.selection.names(), canvas.dim());
        int n = 0;
        if (view != null) {
            for (SwarmView.BotState b : view.bots()) {
                if (b.feed().pos() != null && b.feed().pos().dim() == canvas.dim()) {
                    n++;
                }
            }
        }
        bots.setText(n + (n == 1 ? " bot" : " bots"));
        List<String> keyLines = MapOverlay.keys(view == null ? null : view.coordinator());
        keys.setText(keyLines.isEmpty() ? "no key leases" : String.join(" | ", keyLines));
        if (canvas.dim() == 'o' && !overworld.isSelected()) {
            overworld.setSelected(true);
        } else if (canvas.dim() == 'n' && !nether.isSelected()) {
            nether.setSelected(true);
        } else if (canvas.dim() == 'e' && !end.isSelected()) {
            end.setSelected(true);
        }
    }
}
