package baritone.swarm.center.desktop;

import baritone.swarm.center.WindowEdges;
import baritone.swarm.center.WindowEdges.Bounds;
import baritone.swarm.center.WindowEdges.Edge;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Ostinato: the command center draws its own window frame. The default Windows title bar stayed white in
 * every theme. This one uses the theme tokens: the header doubles as the title bar (drag to move,
 * double-click to maximize), three flat window buttons sit at its right, and a thin frame resizes the window.
 * Nothing here takes focus.
 */
final class WindowChrome {

    /** Width of the resize frame around the window, in pixels. */
    static final int GRIP = 5;
    static final Dimension MIN_SIZE = new Dimension(760, 520);
    private static final String NORMAL_BOUNDS = "ostinato.normalBounds";

    private WindowChrome() {}

    /** Call once, before the frame is shown: borderless frame, resize border on the root pane. */
    static void install(JFrame frame) {
        frame.setUndecorated(true);
        frame.setMinimumSize(MIN_SIZE);
        Mover mover = new Mover(false);
        frame.getRootPane().addMouseListener(mover);
        frame.getRootPane().addMouseMotionListener(mover);
        // Ostinato: text is measured at the screen's scale. Moved to a monitor with another scale, labels that
        // never change keep their old width and get cut ("PERFORMA..."), so measure everything again.
        frame.addPropertyChangeListener("graphicsConfiguration",
                event -> SwingUtilities.invokeLater(() -> relayout(frame)));
        restyle(frame);
    }

    private static void relayout(JFrame frame) {
        invalidateTree(frame.getRootPane());
        frame.validate();
        frame.repaint();
    }

    private static void invalidateTree(Component component) {
        component.invalidate();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                invalidateTree(child);
            }
        }
    }

    /** Colors of the frame. Call again after a theme change or maximize. */
    static void restyle(JFrame frame) {
        JRootPane root = frame.getRootPane();
        frame.setBackground(Style.surface());
        if (maximized(frame)) {
            root.setBorder(BorderFactory.createEmptyBorder());
        } else {
            root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Style.line(), 1),
                    BorderFactory.createMatteBorder(GRIP - 1, GRIP - 1, GRIP - 1, GRIP - 1, Style.surface())));
        }
        root.revalidate();
        root.repaint();
    }

    /** Lets a header move the window and, near the window edge, resize it. */
    static void draggable(JComponent header) {
        Mover mover = new Mover(true);
        header.addMouseListener(mover);
        header.addMouseMotionListener(mover);
    }

    /** Minimize, maximize/restore and close, flush with the header's top right corner. */
    static JComponent controls(Runnable close) {
        JPanel row = Style.panel(new FlowLayout(FlowLayout.RIGHT, 0, 0), null);
        row.add(new ChromeButton(Glyph.MINIMIZE, "Minimize", button -> {
            JFrame frame = frameOf(button);
            if (frame != null) {
                frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
            }
        }));
        row.add(new ChromeButton(Glyph.MAXIMIZE, "Maximize", button -> toggleMaximize(frameOf(button))));
        row.add(new ChromeButton(Glyph.CLOSE, "Close (the swarm keeps running)", button -> close.run()));
        return row;
    }

    static boolean maximized(JFrame frame) {
        return frame != null && frame.getRootPane().getClientProperty(NORMAL_BOUNDS) != null;
    }

    /**
     * Maximize to the usable area of the window's screen, or restore. Done by hand: an undecorated frame
     * with MAXIMIZED_BOTH covers the taskbar, and setMaximizedBounds misplaces it on a second monitor.
     */
    static void toggleMaximize(JFrame frame) {
        if (frame == null) {
            return;
        }
        JRootPane root = frame.getRootPane();
        Object normal = root.getClientProperty(NORMAL_BOUNDS);
        if (normal instanceof Rectangle bounds) {
            root.putClientProperty(NORMAL_BOUNDS, null);
            restyle(frame);
            frame.setBounds(bounds);
        } else {
            root.putClientProperty(NORMAL_BOUNDS, frame.getBounds());
            restyle(frame);
            frame.setBounds(usableArea(frame.getGraphicsConfiguration()));
        }
        frame.validate();
        frame.repaint();
    }

    private static Rectangle usableArea(GraphicsConfiguration config) {
        Rectangle screen = config.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(config);
        return new Rectangle(screen.x + in.left, screen.y + in.top,
                screen.width - in.left - in.right, screen.height - in.top - in.bottom);
    }

    private static JFrame frameOf(Component component) {
        Window window = component == null ? null : SwingUtilities.getWindowAncestor(component);
        return window instanceof JFrame frame ? frame : null;
    }

    private static int cursorFor(Edge edge) {
        return switch (edge) {
            case N -> Cursor.N_RESIZE_CURSOR;
            case S -> Cursor.S_RESIZE_CURSOR;
            case W -> Cursor.W_RESIZE_CURSOR;
            case E -> Cursor.E_RESIZE_CURSOR;
            case NW -> Cursor.NW_RESIZE_CURSOR;
            case NE -> Cursor.NE_RESIZE_CURSOR;
            case SW -> Cursor.SW_RESIZE_CURSOR;
            case SE -> Cursor.SE_RESIZE_CURSOR;
            case NONE -> Cursor.DEFAULT_CURSOR;
        };
    }

    /** Resizes from the window edge; on a header it also moves the window and maximizes on double-click. */
    private static final class Mover extends MouseAdapter {
        private final boolean moves;
        private Edge edge = Edge.NONE;
        private Point pressedAt;
        private Rectangle startBounds;

        Mover(boolean moves) {
            this.moves = moves;
        }

        private Edge edgeAt(MouseEvent e, JFrame frame) {
            if (maximized(frame)) {
                return Edge.NONE;
            }
            Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), frame.getRootPane());
            return WindowEdges.hit(p.x, p.y, frame.getRootPane().getWidth(), frame.getRootPane().getHeight(), GRIP);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            JFrame frame = frameOf(e.getComponent());
            if (frame != null) {
                e.getComponent().setCursor(Cursor.getPredefinedCursor(cursorFor(edgeAt(e, frame))));
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (pressedAt == null) {
                e.getComponent().setCursor(Cursor.getDefaultCursor());
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            JFrame frame = frameOf(e.getComponent());
            if (frame == null || !SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            edge = edgeAt(e, frame);
            pressedAt = e.getLocationOnScreen();
            startBounds = frame.getBounds();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            JFrame frame = frameOf(e.getComponent());
            if (frame == null || pressedAt == null) {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - pressedAt.x;
            int dy = now.y - pressedAt.y;
            if (edge != Edge.NONE) {
                Dimension min = frame.getMinimumSize();
                Bounds next = WindowEdges.resize(new Bounds(startBounds.x, startBounds.y, startBounds.width,
                        startBounds.height), edge, dx, dy, min.width, min.height);
                frame.setBounds(next.x(), next.y(), next.width(), next.height());
                frame.validate();
            } else if (moves && !maximized(frame)) {
                frame.setLocation(startBounds.x + dx, startBounds.y + dy);
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            pressedAt = null;
            edge = Edge.NONE;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (moves && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                JFrame frame = frameOf(e.getComponent());
                if (frame != null && edgeAt(e, frame) == Edge.NONE) {
                    toggleMaximize(frame);
                }
            }
        }
    }

    private enum Glyph { MINIMIZE, MAXIMIZE, CLOSE }

    /** A flat window button that paints its own glyph, so it looks the same in every font. */
    private static final class ChromeButton extends JButton {
        private final Glyph glyph;
        private boolean hover;

        ChromeButton(Glyph glyph, String tip, java.util.function.Consumer<ChromeButton> action) {
            this.glyph = glyph;
            setToolTipText(tip);
            setFocusable(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setPreferredSize(new Dimension(46, 34));
            getModel().addChangeListener(event -> {
                boolean over = getModel().isRollover();
                if (over != hover) {
                    hover = over;
                    repaint();
                }
            });
            addActionListener(event -> action.accept(this));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean pressed = getModel().isPressed();
                Color ink = Style.muted();
                if (hover || pressed) {
                    if (glyph == Glyph.CLOSE) {
                        g2.setColor(Style.danger());
                        ink = Style.color(Style.theme().onAccent());
                    } else {
                        g2.setColor(pressed ? Style.line() : Style.raised());
                        ink = Style.text();
                    }
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                g2.setColor(ink);
                g2.setStroke(new BasicStroke(1.2f));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                int r = 5;
                switch (glyph) {
                    case MINIMIZE -> g2.drawLine(cx - r, cy, cx + r, cy);
                    case CLOSE -> {
                        g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                        g2.drawLine(cx - r, cy + r, cx + r, cy - r);
                    }
                    case MAXIMIZE -> {
                        if (maximized(frameOf(this))) { // restore: two stacked squares
                            g2.drawRect(cx - r + 2, cy - r, 2 * r - 2, 2 * r - 2);
                            g2.setColor(hover ? Style.raised() : Style.surface());
                            g2.fillRect(cx - r, cy - r + 2, 2 * r - 2, 2 * r - 2);
                            g2.setColor(ink);
                            g2.drawRect(cx - r, cy - r + 2, 2 * r - 2, 2 * r - 2);
                        } else {
                            g2.drawRect(cx - r, cy - r, 2 * r, 2 * r);
                        }
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
