package baritone.swarm.center.desktop;

import baritone.swarm.center.CenterRuntime;
import baritone.swarm.center.CenterTheme;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Dimension;

/**
 * Ostinato: Swing pieces drawn with the current theme's tokens. Flat controls, 1 px borders, no focus grab.
 * Pieces read the theme when built, so a theme change rebuilds the window's content.
 */
final class Style {

    static CenterTheme theme() {
        return CenterRuntime.theme();
    }

    private Style() {}

    static Color color(int rgb) {
        return new Color(rgb);
    }

    static Color bg() {
        return color(theme().background());
    }

    static Color surface() {
        return color(theme().surface());
    }

    static Color raised() {
        return color(theme().raised());
    }

    static Color line() {
        return color(theme().border());
    }

    static Color text() {
        return color(theme().text());
    }

    static Color muted() {
        return color(theme().muted());
    }

    static Color faint() {
        return color(theme().faint());
    }

    static Color accent() {
        return color(theme().accent());
    }

    static Color ok() {
        return color(theme().ok());
    }

    static Color warn() {
        return color(theme().warn());
    }

    static Color danger() {
        return color(theme().danger());
    }

    static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    static Font font(int style, float size) {
        return new JLabel().getFont().deriveFont(style, size);
    }

    static Border box(Color edge, int pad) {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, 1),
                BorderFactory.createEmptyBorder(pad, pad, pad, pad));
    }

    static JPanel panel(LayoutManager layout, Color background) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(background);
        panel.setOpaque(background != null);
        return panel;
    }

    static JLabel label(String text, Color color, int style, float size) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(font(style, size));
        return label;
    }

    /** Small all-caps section caption, for example "ACCOUNTS". */
    static JLabel caption(String text) {
        return label(text.toUpperCase(java.util.Locale.ROOT), faint(), Font.BOLD, 11f);
    }

    static JButton button(String text) {
        JButton button = new RoomyButton(text);
        flat(button, raised(), text(), line());
        return button;
    }

    /** A button whose text and edge carry the danger color, for Stop all. */
    static JButton dangerButton(String text) {
        JButton button = new RoomyButton(text);
        flat(button, raised(), danger(), danger());
        return button;
    }

    /**
     * Ostinato: on a 200% display the measured label came out about 8% narrower than the painted one, so
     * longer labels clipped to "Microsoft sig...". A little slack per character keeps every label whole.
     */
    private static final class RoomyButton extends JButton {
        RoomyButton(String text) {
            super(text);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            if (!isPreferredSizeSet() && getText() != null) {
                size.width += Math.max(4, getFontMetrics(getFont()).stringWidth(getText()) / 8);
            }
            return size;
        }
    }

    static JToggleButton toggle(String text) {
        JToggleButton toggle = new JToggleButton(text);
        flat(toggle, raised(), muted(), line());
        toggle.addChangeListener(event -> {
            boolean on = toggle.isSelected();
            toggle.setBackground(on ? accent() : raised());
            toggle.setForeground(on ? color(theme().onAccent()) : muted());
        });
        return toggle;
    }

    static void flat(AbstractButton button, Color background, Color foreground, Color edge) {
        button.setFocusable(false);
        button.setFocusPainted(false);
        // Ostinato: measure and paint with one font, or HiDPI layouts clip the label to "Add offl...".
        button.setFont(font(Font.BOLD, 12f));
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static JTextField field(int columns, String tip) {
        JTextField field = new JTextField(columns);
        field.setBackground(bg());
        field.setForeground(text());
        field.setCaretColor(text());
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line(), 1),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        if (tip != null) {
            field.setToolTipText(tip);
        }
        return field;
    }

    static <T> void list(JList<T> list) {
        list.setBackground(bg());
        list.setForeground(text());
        list.setSelectionBackground(accent());
        list.setSelectionForeground(color(theme().onAccent()));
        list.setFocusable(false);
    }

    static JScrollPane scroll(JComponent view, Color background) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createLineBorder(line(), 1));
        scroll.getViewport().setBackground(background);
        scroll.setBackground(background);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        flatScrollBars(scroll, background);
        return scroll;
    }

    /**
     * Ostinato: thin scroll bars in the theme colors. The look-and-feel bar was light grey with arrow buttons
     * in every theme, which glared in the dark ones.
     */
    static void flatScrollBars(JScrollPane scroll, Color track) {
        for (JScrollBar bar : new JScrollBar[] {scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
            bar.setUI(new FlatScrollBarUI(track));
            bar.setBackground(track);
            bar.setOpaque(true);
            bar.setFocusable(false);
        }
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
        JPanel corner = panel(null, track);
        scroll.setCorner(JScrollPane.LOWER_RIGHT_CORNER, corner);
    }

    /** Ostinato: a rounded thumb on a plain track, no arrow buttons. The thumb brightens under the mouse. */
    private static final class FlatScrollBarUI extends BasicScrollBarUI {
        private final Color track;

        FlatScrollBarUI(Color track) {
            this.track = track;
        }

        @Override
        protected void configureScrollBarColors() {
            thumbColor = line();
            trackColor = track;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return noButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return noButton();
        }

        private static JButton noButton() {
            JButton none = new JButton();
            none.setPreferredSize(new Dimension(0, 0));
            none.setMinimumSize(new Dimension(0, 0));
            none.setMaximumSize(new Dimension(0, 0));
            none.setFocusable(false);
            return none;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            g.setColor(trackColor);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isThumbRollover() || isDragging ? muted() : thumbColor);
                int arc = Math.min(bounds.width, bounds.height) - 4;
                g2.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, arc, arc);
            } finally {
                g2.dispose();
            }
        }
    }
}
