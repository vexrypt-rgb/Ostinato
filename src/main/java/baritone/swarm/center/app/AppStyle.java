package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.TileStatus;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.LayoutManager;

/**
 * Ostinato: Phase 3 window. Swing pieces drawn from one {@link CenterTheme}, passed in rather than read from a global,
 * so a theme change is "build again with the new theme" and tests can render any theme. Every view in the app takes
 * an {@code AppStyle}; no view picks a color of its own.
 */
public final class AppStyle {

    public final CenterTheme theme;

    public AppStyle(CenterTheme theme) {
        this.theme = theme == null ? CenterTheme.DEFAULT_DARK : theme;
    }

    public Color bg() { return new Color(theme.background()); }
    public Color surface() { return new Color(theme.surface()); }
    public Color raised() { return new Color(theme.raised()); }
    public Color line() { return new Color(theme.border()); }
    public Color text() { return new Color(theme.text()); }
    public Color muted() { return new Color(theme.muted()); }
    public Color faint() { return new Color(theme.faint()); }
    public Color accent() { return new Color(theme.accent()); }
    public Color onAccent() { return new Color(theme.onAccent()); }
    public Color ok() { return new Color(theme.ok()); }
    public Color warn() { return new Color(theme.warn()); }
    public Color danger() { return new Color(theme.danger()); }

    /** The color of a status level. Always drawn next to that level's glyph, never alone. */
    public Color level(TileStatus.Level level) {
        return switch (level) {
            case OK -> ok();
            case IDLE -> muted();
            case WARN -> warn();
            case BAD -> danger();
        };
    }

    public static Font font(int style, float size) {
        return new Font(Font.SANS_SERIF, style, Math.round(size)).deriveFont(size);
    }

    public JPanel panel(LayoutManager layout, Color background) {
        JPanel p = new JPanel(layout);
        p.setBackground(background);
        p.setOpaque(background != null);
        return p;
    }

    public JLabel label(String text, Color color, int style, float size) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(font(style, size));
        return l;
    }

    public JButton button(String text) {
        JButton b = new JButton(text);
        flat(b, raised(), text(), line());
        return b;
    }

    public JButton dangerButton(String text) {
        JButton b = new JButton(text);
        flat(b, raised(), danger(), danger());
        return b;
    }

    public JToggleButton toggle(String text) {
        JToggleButton b = new JToggleButton(text);
        flat(b, raised(), text(), line());
        return b;
    }

    /** A text field that shows {@code prompt} (the argument name) in the faint color while it is empty. */
    public JTextField field(int columns, String prompt, String tip) {
        Color hint = faint();
        JTextField f = new JTextField(columns) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    g.setColor(hint);
                    g.setFont(getFont());
                    java.awt.Insets in = getInsets();
                    g.drawString(prompt, in.left, in.top + g.getFontMetrics().getAscent());
                }
            }
        };
        f.setToolTipText(tip);
        f.setBackground(raised());
        f.setForeground(text());
        f.setCaretColor(text());
        f.setFont(font(Font.PLAIN, 12f));
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line(), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        return f;
    }

    public Border box(Color edge, int width, int pad) {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, width),
                BorderFactory.createEmptyBorder(pad, pad, pad, pad));
    }

    /** Flat look; buttons never take focus on click, so pressing one cannot pull the window forward. */
    public void flat(AbstractButton b, Color background, Color foreground, Color edge) {
        b.setBackground(background);
        b.setForeground(foreground);
        b.setFont(font(Font.PLAIN, 12f));
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, 1),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
    }
}
