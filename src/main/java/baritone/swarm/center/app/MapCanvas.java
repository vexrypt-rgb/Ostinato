package baritone.swarm.center.app;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainFeed;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: Phase 3 window, the war-room drawing surface. Terrain is rasterized into one backbuffer the size of the
 * view (never one image per slice per zoom). Markers, paths, the chunk grid and the minimap are drawn on top.
 * Arrow keys pan when this canvas is given the key event; the wheel zooms. Nothing here asks for focus.
 */
final class MapCanvas extends javax.swing.JComponent {

    interface Listener {
        void cursor(int blockX, int blockZ);

        /** Right-click on a marker. {@code bot} is null when the click missed. */
        void markerMenu(String bot);
    }

    final MapCamera camera = new MapCamera();
    private final AppStyle style;
    private final Listener listener;
    private SwarmView view = SwarmView.EMPTY;
    private char dim = 'o';
    private boolean placed;
    private BufferedImage buffer;
    private int pressX, pressY;
    private boolean panning;

    MapCanvas(AppStyle style, Listener listener) {
        this.style = style;
        this.listener = listener;
        setName("map:canvas");
        setOpaque(true);
        setBackground(style.bg());
        setFocusable(true);
        setFont(AppStyle.font(Font.PLAIN, 11f));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressX = e.getX();
                pressY = e.getY();
                String hit = hit(e.getX(), e.getY());
                if (e.getButton() == MouseEvent.BUTTON3) {
                    listener.markerMenu(hit);
                    panning = false;
                } else if (e.getButton() == MouseEvent.BUTTON1 && hit == null) {
                    panning = true;
                    listener.markerMenu(null);
                } else {
                    panning = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!panning) {
                    return;
                }
                camera.panPixels(e.getX() - pressX, e.getY() - pressY);
                pressX = e.getX();
                pressY = e.getY();
                placed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                panning = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                reportCursor(e.getX(), e.getY());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener((MouseWheelEvent e) -> {
            syncSize();
            camera.zoomAt(e.getX(), e.getY(), -e.getWheelRotation());
            placed = true;
            reportCursor(e.getX(), e.getY());
            repaint();
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int step = 32;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT -> camera.panPixels(step, 0);
                    case KeyEvent.VK_RIGHT -> camera.panPixels(-step, 0);
                    case KeyEvent.VK_UP -> camera.panPixels(0, step);
                    case KeyEvent.VK_DOWN -> camera.panPixels(0, -step);
                    default -> { return; }
                }
                placed = true;
                repaint();
            }
        });
    }

    void setView(SwarmView view) {
        this.view = view == null ? SwarmView.EMPTY : view;
        if (!placed) {
            frameOn(firstBot());
        }
        repaint();
    }

    void setDim(char dim) {
        this.dim = dim;
        repaint();
    }

    char dim() {
        return dim;
    }

    /** Centers on a bot in the current dimension. No such bot leaves the camera where it is. */
    boolean centerOn(String name) {
        SwarmView.BotState b = bot(name);
        if (b == null || b.feed().pos() == null || b.feed().pos().dim() != dim) {
            return false;
        }
        syncSize();
        camera.setCenter(b.feed().pos().x(), b.feed().pos().z());
        placed = true;
        repaint();
        return true;
    }

    /** Screen point of a marker in this dimension, or null. */
    double[] markerScreen(String name) {
        SwarmView.BotState b = bot(name);
        if (b == null || b.feed().pos() == null || b.feed().pos().dim() != dim) {
            return null;
        }
        syncSize();
        return camera.worldToScreen(b.feed().pos().x(), b.feed().pos().z());
    }

    String hit(int sx, int sy) {
        return MapHit.nearest(screenMarkers(), sx, sy, MapHit.RADIUS);
    }

    private void frameOn(SwarmView.BotState b) {
        syncSize();
        if (b != null && b.feed().pos() != null) {
            camera.setCenter(b.feed().pos().x(), b.feed().pos().z());
        }
        placed = true;
    }

    private SwarmView.BotState firstBot() {
        String host = view.hostBot();
        SwarmView.BotState hostBot = host == null ? null : bot(host);
        if (hostBot != null && hostBot.feed().pos() != null && hostBot.feed().pos().dim() == dim) {
            return hostBot;
        }
        for (SwarmView.BotState b : view.bots()) {
            if (b.feed().pos() != null && b.feed().pos().dim() == dim) {
                return b;
            }
        }
        return null;
    }

    private SwarmView.BotState bot(String name) {
        return name == null ? null : view.bot(name);
    }

    private void syncSize() {
        if (getWidth() > 0 && getHeight() > 0) {
            camera.size(getWidth(), getHeight());
        }
    }

    private void reportCursor(int sx, int sy) {
        syncSize();
        double[] w = camera.screenToWorld(sx, sy);
        listener.cursor((int) Math.floor(w[0]), (int) Math.floor(w[1]));
    }

    private List<MapHit.Marker> screenMarkers() {
        syncSize();
        List<MapHit.Marker> out = new ArrayList<>();
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            out.add(new MapHit.Marker(b.name(), camera.screenX(p.x()), camera.screenZ(p.z())));
        }
        return out;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        syncSize();
        int w = getWidth();
        int h = getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        rasterTerrain(w, h);
        Graphics2D g = buffer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawGrid(g, w, h);
        drawPaths(g);
        drawMarkers(g);
        drawMinimap(g, w, h);
        g.dispose();
        graphics.drawImage(buffer, 0, 0, null);
    }

    private void rasterTerrain(int w, int h) {
        int[] pix = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
        int bg = style.theme.background();
        for (int i = 0; i < pix.length; i++) {
            pix[i] = bg;
        }
        List<TerrainFeed> slices = new ArrayList<>();
        for (TerrainFeed t : view.terrain().values()) {
            if (t.dim() == dim) {
                slices.add(t);
            }
        }
        if (slices.isEmpty()) {
            return;
        }
        int zoom = camera.zoom();
        int x0 = (int) Math.floor(camera.screenToWorld(0, 0)[0]) - 1;
        int z0 = (int) Math.floor(camera.screenToWorld(0, 0)[1]) - 1;
        int x1 = (int) Math.ceil(camera.screenToWorld(w, h)[0]) + 1;
        int z1 = (int) Math.ceil(camera.screenToWorld(w, h)[1]) + 1;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                String id = MapTerrain.block(slices, dim, x, z);
                int rgb = BlockColors.color(id);
                if (rgb == 0) {
                    continue;
                }
                int sx = (int) Math.floor(camera.screenX(x));
                int sy = (int) Math.floor(camera.screenZ(z));
                fill(pix, w, h, sx, sy, zoom, zoom, rgb);
            }
        }
    }

    private static void fill(int[] pix, int w, int h, int sx, int sy, int bw, int bh, int rgb) {
        int x1 = Math.min(w, sx + Math.max(1, bw));
        int y1 = Math.min(h, sy + Math.max(1, bh));
        int x0 = Math.max(0, sx);
        int y0 = Math.max(0, sy);
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) {
                pix[row + x] = rgb;
            }
        }
    }

    private void drawGrid(Graphics2D g, int w, int h) {
        g.setColor(new Color(style.theme.border()));
        g.setStroke(new BasicStroke(1f));
        double[] origin = camera.screenToWorld(0, 0);
        double[] end = camera.screenToWorld(w, h);
        int x0 = (int) Math.floor(origin[0]) & ~15;
        int z0 = (int) Math.floor(origin[1]) & ~15;
        int x1 = (int) Math.ceil(end[0]);
        int z1 = (int) Math.ceil(end[1]);
        g.setFont(getFont());
        for (int x = x0; x <= x1; x += 16) {
            int sx = (int) Math.round(camera.screenX(x));
            g.drawLine(sx, 0, sx, h);
        }
        for (int z = z0; z <= z1; z += 16) {
            int sy = (int) Math.round(camera.screenZ(z));
            g.drawLine(0, sy, w, sy);
            for (int x = x0; x <= x1; x += 16) {
                int sx = (int) Math.round(camera.screenX(x));
                if (sx >= 0 && sx < w - 40 && sy >= 12 && sy < h) {
                    g.setColor(new Color(style.theme.faint()));
                    g.drawString((x >> 4) + "," + (z >> 4), sx + 3, sy - 3);
                    g.setColor(new Color(style.theme.border()));
                }
            }
        }
    }

    private void drawPaths(Graphics2D g) {
        g.setStroke(new BasicStroke(2f));
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            BotFeed.Route path = b.feed().path();
            g.setColor(new Color(BotColors.of(b.name())));
            double sx = camera.screenX(p.x());
            double sy = camera.screenZ(p.z());
            for (int[] node : path.nodes()) {
                if (node == null || node.length < 3) {
                    continue;
                }
                double nx = camera.screenX(node[0]);
                double nz = camera.screenZ(node[2]);
                g.drawLine((int) Math.round(sx), (int) Math.round(sy), (int) Math.round(nx), (int) Math.round(nz));
                sx = nx;
                sy = nz;
            }
            int[] goal = path.goalPos();
            if (goal != null && goal.length >= 3) {
                int gx = (int) Math.round(camera.screenX(goal[0]));
                int gz = (int) Math.round(camera.screenZ(goal[2]));
                g.setColor(new Color(style.theme.accent()));
                g.drawOval(gx - 6, gz - 6, 12, 12);
            }
        }
    }

    private void drawMarkers(Graphics2D g) {
        g.setFont(getFont());
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            int sx = (int) Math.round(camera.screenX(p.x()));
            int sy = (int) Math.round(camera.screenZ(p.z()));
            Color color = new Color(BotColors.of(b.name()));
            TileStatus status = Alerts.status(b, view.alertsFor(b.name()));
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            drawShape(g, status.level(), sx, sy);
            drawFacing(g, p.yaw(), sx, sy);
            g.setColor(new Color(style.theme.text()));
            g.drawString(status.level().glyph + " " + b.name(), sx + 10, sy - 8);
        }
    }

    /** Circle, hollow circle, triangle, cross. The shape matches the status glyph, not the color. */
    static void drawShape(Graphics2D g, TileStatus.Level level, int sx, int sy) {
        int r = 7;
        switch (level) {
            case OK -> g.fillOval(sx - r, sy - r, r * 2, r * 2);
            case IDLE -> g.drawOval(sx - r, sy - r, r * 2, r * 2);
            case WARN -> {
                int[] xs = {sx, sx - r, sx + r};
                int[] ys = {sy - r, sy + r, sy + r};
                g.fillPolygon(xs, ys, 3);
            }
            case BAD -> {
                g.drawLine(sx - r, sy - r, sx + r, sy + r);
                g.drawLine(sx - r, sy + r, sx + r, sy - r);
            }
        }
    }

    /** Minecraft yaw: 0 looks +Z, and positive yaw turns toward -X. */
    static void drawFacing(Graphics2D g, float yaw, int sx, int sy) {
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int len = 14;
        g.drawLine(sx, sy, sx + (int) Math.round(dx * len), sy + (int) Math.round(dz * len));
    }

    private void drawMinimap(Graphics2D g, int w, int h) {
        int mw = 168;
        int mh = 112;
        int mx = w - mw - 8;
        int my = h - mh - 8;
        if (mx < 0 || my < 0) {
            return;
        }
        g.setColor(new Color(style.theme.surface()));
        g.fillRect(mx, my, mw, mh);
        g.setColor(new Color(style.theme.border()));
        g.drawRect(mx, my, mw, mh);
        double[] a = camera.screenToWorld(0, 0);
        double[] b = camera.screenToWorld(w, h);
        double minX = Math.min(a[0], b[0]);
        double maxX = Math.max(a[0], b[0]);
        double minZ = Math.min(a[1], b[1]);
        double maxZ = Math.max(a[1], b[1]);
        for (SwarmView.BotState bot : view.bots()) {
            BotFeed.Pos p = bot.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        double spanX = Math.max(16, maxX - minX);
        double spanZ = Math.max(16, maxZ - minZ);
        double scale = Math.min((mw - 12) / spanX, (mh - 12) / spanZ);
        int viewX = mx + 6 + (int) Math.round((Math.min(a[0], b[0]) - minX) * scale);
        int viewY = my + 6 + (int) Math.round((Math.min(a[1], b[1]) - minZ) * scale);
        int viewW = Math.max(2, (int) Math.round(Math.abs(b[0] - a[0]) * scale));
        int viewH = Math.max(2, (int) Math.round(Math.abs(b[1] - a[1]) * scale));
        g.setColor(new Color(style.theme.accent()));
        g.drawRect(viewX, viewY, viewW, viewH);
        for (SwarmView.BotState bot : view.bots()) {
            BotFeed.Pos p = bot.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            int sx = mx + 6 + (int) Math.round((p.x() - minX) * scale);
            int sy = my + 6 + (int) Math.round((p.z() - minZ) * scale);
            g.setColor(new Color(BotColors.of(bot.name())));
            g.fillRect(sx - 2, sy - 2, 4, 4);
        }
    }
}
