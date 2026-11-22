package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.JComponent;

/**
 * Ostinato: Phase 3 window, one layout card (the slice contract). {@link WallView} is the reference; the War room map
 * is {@link MapView}. A view:
 * <ul>
 *   <li>is built once with the window's {@link AppStyle} and {@link Commands} (built again on a theme change),</li>
 *   <li>gets every refresh through {@link #update} on the Swing thread, with a complete immutable snapshot, and keeps
 *   typed text and selection across refreshes,</li>
 *   <li>sends commands only through {@link Commands} (which shows the equivalent {@code #} line), never by itself,</li>
 *   <li>never calls toFront, requestFocus, a dialog or a beep (a lint test checks the sources).</li>
 * </ul>
 */
public interface LayoutView {

    /** The header toggle that shows this card. */
    CenterLayout layout();

    JComponent component();

    void update(SwarmView view);
}
