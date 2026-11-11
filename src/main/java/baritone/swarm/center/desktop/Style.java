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
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.LayoutManager;

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
        return scroll;
    }
}
