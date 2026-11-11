package baritone.swarm.center.desktop;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Ostinato: health and hunger bars for a tile's dark "thumbnail" area. Every bar carries its number,
 * so the value does not rely on the bar color. Below 0 means unknown and draws "n/a".
 */
final class Vitals extends JComponent {

    private int health = -1;
    private int hunger = -1;

    Vitals() {
        setOpaque(false);
        setFont(Style.font(Font.PLAIN, 11f));
    }

    void set(int health, int hunger) {
        if (health != this.health || hunger != this.hunger) {
            this.health = health;
            this.hunger = hunger;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(140, fm.getHeight() * 2 + 6);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics fm = g.getFontMetrics(getFont());
            int row = fm.getHeight() + 3;
            row(g, fm, 0, "HP", health);
            row(g, fm, row, "Food", hunger);
        } finally {
            g.dispose();
        }
    }

    private void row(Graphics2D g, FontMetrics fm, int y, String name, int value) {
        int labelWidth = fm.stringWidth("Food") + 8;
        String number = value < 0 ? "n/a" : value + "/20";
        int numberWidth = fm.stringWidth("20/20") + 6;
        int baseline = y + fm.getAscent();
        g.setColor(Style.faint());
        g.drawString(name, 0, baseline);
        int barX = labelWidth;
        int barW = Math.max(20, getWidth() - labelWidth - numberWidth);
        int barH = Math.max(4, fm.getAscent() / 2);
        int barY = y + (fm.getHeight() - barH) / 2;
        g.setColor(Style.raised());
        g.fillRoundRect(barX, barY, barW, barH, barH, barH);
        if (value >= 0) {
            int filled = (int) Math.round(barW * Math.min(20, value) / 20.0);
            g.setColor(fill(value));
            g.fillRoundRect(barX, barY, filled, barH, barH, barH);
        }
        g.setColor(value < 0 ? Style.faint() : Style.muted());
        g.drawString(number, getWidth() - fm.stringWidth(number), baseline);
    }

    private static Color fill(int value) {
        if (value <= 6) {
            return Style.danger();
        }
        return value <= 12 ? Style.warn() : Style.ok();
    }
}
