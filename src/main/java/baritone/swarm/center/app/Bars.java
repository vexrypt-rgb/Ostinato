package baritone.swarm.center.app;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Ostinato: Phase 3 window, a tile's health and hunger bars. Each bar is labelled with its number ("health 18/20"),
 * so the value never rests on the bar's color. Below 0 draws "n/a".
 */
final class Bars extends JComponent {

    private final AppStyle style;
    private int health = -1;
    private int hunger = -1;

    Bars(AppStyle style) {
        this.style = style;
        setOpaque(false);
        setFont(AppStyle.font(Font.PLAIN, 11f));
    }

    void set(int health, int hunger) {
        if (health != this.health || hunger != this.hunger) {
            this.health = health;
            this.hunger = hunger;
            repaint();
        }
    }

    /** What the bars say, for tests and the accessible name. */
    String text() {
        return "health " + value(health) + " · hunger " + value(hunger);
    }

    private static String value(int v) {
        return v < 0 ? "n/a" : v + "/20";
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(200, fm.getHeight() * 2 + 6);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics fm = g.getFontMetrics(getFont());
            int row = fm.getHeight() + 3;
            bar(g, fm, 0, "health " + value(health), health, health >= 0 && health <= 6);
            bar(g, fm, row, "hunger " + value(hunger), hunger, hunger >= 0 && hunger <= 6);
        } finally {
            g.dispose();
        }
    }

    private void bar(Graphics2D g, FontMetrics fm, int y, String label, int value, boolean low) {
        int textW = fm.stringWidth("hunger 20/20") + 8;
        g.setColor(style.muted());
        g.drawString(label, 0, y + fm.getAscent());
        int x = textW, w = Math.max(20, getWidth() - textW), h = fm.getHeight() - 6;
        g.setColor(style.raised());
        g.fillRect(x, y + 3, w, h);
        if (value >= 0) {
            g.setColor(low ? style.warn() : style.ok());
            g.fillRect(x, y + 3, (int) Math.round(w * Math.min(20, value) / 20.0), h);
        }
        g.setColor(style.line());
        g.drawRect(x, y + 3, w - 1, h - 1);
    }
}
