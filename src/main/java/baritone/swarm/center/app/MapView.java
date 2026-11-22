package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * Ostinato: Phase 3 window, the War room / RTS map card. Placeholder until the map slices land (see
 * PHASE3-GROK-HANDOFF.md, slices M1 to M4): it only counts what the map will draw, so the layout toggle already works.
 */
public final class MapView implements LayoutView {

    private final JPanel root;
    private final JLabel text;

    public MapView(AppStyle style, Commands commands) {
        root = style.panel(new BorderLayout(), style.surface());
        root.setName("map");
        text = style.label(" ", style.muted(), Font.PLAIN, 13f);
        text.setHorizontalAlignment(SwingConstants.CENTER);
        text.setName("map.placeholder");
        root.add(text, BorderLayout.CENTER);
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.MAP;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(SwarmView view) {
        int leases = view.coordinator() == null ? 0 : view.coordinator().leases().size();
        text.setText("War room map is not built yet. Feed: " + view.bots().size() + " bots, " + leases + " leases, "
                + view.terrain().size() + " terrain slices.");
    }
}
