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
import java.awt.event.InputEvent;
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
 * Arrow keys pan when this canvas is given the key event; the wheel zooms. A click marks the canvas as the place
 * control-group keys apply; nothing here asks for focus.
 */
final class MapCanvas extends javax.swing.JComponent {

    interface Listener {
        void cursor(int blockX, int blockZ);

        /** Right-click on a marker. {@code bot} is null when the click missed. */
        void markerMenu(String bot);

        /** Tooltip for whatever the pointer is over, or empty. */
        void hover(String text);

        /** The selection or a control group changed. {@code groupsStored} means a slot was just written. */
        void selectionChanged(boolean groupsStored);

        /** A finished area drag, in inclusive block coordinates. */
        void areaDrawn(int x1, int z1, int x2, int z2);
    }

    final MapCamera camera = new MapCamera();
    private final AppStyle style;
    private final Listener listener;
    private SwarmView view = SwarmView.EMPTY;
    private char dim = 'o';
    private boolean placed;
    private BufferedImage buffer;
    boolean showLeases = true;
    boolean showObservations = true;
    boolean showPaths = true;
    boolean showTerrain = true;
    final MapSelection selection = new MapSelection();
    final MapGroups groups = new MapGroups();
    private int pressX, pressY;
    private int originX, originY, currentX, currentY;
    private boolean panning;
    private boolean boxing;
    private boolean boxMoved;
    private boolean boxAdd;
    private int boxButton;
    /** Set by a click on this canvas. Control-group keys and Esc apply only while this is set. */
    private boolean pointerFocus;
    /** When set, a plain left drag draws an area instead of panning. */
    boolean areaArmed;
    private boolean areaDragging;
    private boolean areaMoved;
    private boolean areaSet;
    private int areaX1, areaZ1, areaX2, areaZ2;

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
                pointerFocus = true;
                originX = pressX = currentX = e.getX();
                originY = pressY = currentY = e.getY();
                boxMoved = false;
                boxing = false;
                areaDragging = false;
                areaMoved = false;
                String hit = hit(e.getX(), e.getY());
                boolean shift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                if (e.getButton() == MouseEvent.BUTTON1 && areaArmed && !shift) {
                    areaDragging = true;
                    panning = false;
                    listener.markerMenu(null);
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    listener.markerMenu(hit);
                    boxing = true;
                    boxAdd = false;
                    boxButton = MouseEvent.BUTTON3;
                    panning = false;
                } else if (e.getButton() == MouseEvent.BUTTON1 && hit != null) {
                    if (shift) {
                        selection.shiftClick(hit);
                    } else {
                        selection.click(hit);
                    }
                    listener.markerMenu(null);
                    listener.selectionChanged(false);
                    panning = false;
                    repaint();
                } else if (e.getButton() == MouseEvent.BUTTON1 && shift) {
                    boxing = true;
                    boxAdd = true;
                    boxButton = MouseEvent.BUTTON1;
                    panning = false;
                    listener.markerMenu(null);
                } else if (e.getButton() == MouseEvent.BUTTON1) {
                    panning = true;
                    listener.markerMenu(null);
                } else {
                    panning = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                currentX = e.getX();
                currentY = e.getY();
                if (areaDragging) {
                    if (Math.abs(e.getX() - originX) + Math.abs(e.getY() - originY) >= 4) {
                        areaMoved = true;
                    }
                    repaint();
                    return;
                }
                if (panning) {
                    camera.panPixels(e.getX() - pressX, e.getY() - pressY);
                    pressX = e.getX();
                    pressY = e.getY();
                    placed = true;
                    repaint();
                    return;
                }
                if (boxing && Math.abs(e.getX() - originX) + Math.abs(e.getY() - originY) >= 4) {
                    if (!boxMoved && boxButton == MouseEvent.BUTTON3) {
                        listener.markerMenu(null);
                    }
                    boxMoved = true;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                currentX = e.getX();
                currentY = e.getY();
                if (areaDragging) {
                    if (areaMoved) {
                        commitArea();
                    } else {
                        String hit = hit(e.getX(), e.getY());
                        if (hit != null) {
                            selection.click(hit);
                            listener.selectionChanged(false);
                        }
                    }
                    areaDragging = false;
                    areaMoved = false;
                    panning = false;
                    repaint();
                    return;
                }
                if (boxing && boxMoved) {
                    selection.box(screenMarkers(), originX, originY, currentX, currentY, boxAdd);
                    listener.selectionChanged(false);
                }
                boxing = false;
                boxMoved = false;
                panning = false;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                reportCursor(e.getX(), e.getY());
                listener.hover(tipAt(e.getX(), e.getY()));
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
                if (groupKey(e)) {
                    return;
                }
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

    /** Esc clears. Ctrl+1..9 stores the selection, 1..9 recalls it. Ignored until the canvas has been clicked. */
    private boolean groupKey(KeyEvent e) {
        boolean esc = e.getKeyCode() == KeyEvent.VK_ESCAPE;
        int slot = slotOf(e.getKeyCode());
        if (!esc && slot == 0) {
            return false;
        }
        if (!pointerFocus && !isFocusOwner()) {
            return true;
        }
        if (esc) {
            selection.clear();
        } else if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
            groups.store(slot, selection.names());
            listener.selectionChanged(true);
            repaint();
            return true;
        } else {
            selection.replace(groups.recall(slot));
        }
        listener.selectionChanged(false);
        repaint();
        return true;
    }

    private static int slotOf(int code) {
        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
            return code - KeyEvent.VK_1 + 1;
        }
        if (code >= KeyEvent.VK_NUMPAD1 && code <= KeyEvent.VK_NUMPAD9) {
            return code - KeyEvent.VK_NUMPAD1 + 1;
        }
        return 0;
    }

    /** Inclusive XZ of the drawn area, or null. */
    int[] areaBounds() {
        return areaSet ? new int[]{areaX1, areaZ1, areaX2, areaZ2} : null;
    }

    private void commitArea() {
        syncSize();
        double[] a = camera.screenToWorld(originX, originY);
        double[] b = camera.screenToWorld(currentX, currentY);
        areaX1 = (int) Math.floor(Math.min(a[0], b[0]));
        areaZ1 = (int) Math.floor(Math.min(a[1], b[1]));
        areaX2 = (int) Math.floor(Math.max(a[0], b[0]));
        areaZ2 = (int) Math.floor(Math.max(a[1], b[1]));
        areaSet = true;
        listener.areaDrawn(areaX1, areaZ1, areaX2, areaZ2);
    }

    private void drawArea(Graphics2D g) {
        g.setStroke(new BasicStroke(2f));
        g.setFont(getFont());
        if (areaDragging && areaMoved) {
            int x = Math.min(originX, currentX);
            int y = Math.min(originY, currentY);
            g.setColor(new Color(style.theme.accent()));
            g.drawRect(x, y, Math.max(1, Math.abs(currentX - originX)), Math.max(1, Math.abs(currentY - originY)));
        }
        if (!areaSet) {
            return;
        }
        MapOverlay.Rect r = MapOverlay.screenBox(camera, new int[]{areaX1, 0, areaZ1, areaX2, 0, areaZ2});
        g.setColor(new Color(style.theme.accent()));
        g.drawRect(r.x(), r.y(), r.w(), r.h());
        g.setColor(new Color(style.theme.text()));
        g.drawString((areaX2 - areaX1 + 1) + " x " + (areaZ2 - areaZ1 + 1), r.x() + 2, Math.max(12, r.y() - 2));
    }

    private void drawSelectionBox(Graphics2D g) {
        if (!boxing || !boxMoved) {
            return;
        }
        int x = Math.min(originX, currentX);
        int y = Math.min(originY, currentY);
        g.setColor(new Color(style.theme.accent()));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
        g.drawRect(x, y, Math.max(1, Math.abs(currentX - originX)), Math.max(1, Math.abs(currentY - originY)));
    }

    void setView(SwarmView view) {
        this.view = view == null ? SwarmView.EMPTY : view;
        if (!placed) {
            frameOn(firstBot());
        }
        repaint();
    }

    void markPlaced() {
        placed = true;
    }

    void setAreaWorld(int x1, int z1, int x2, int z2) {
        areaSet = true;
        areaX1 = Math.min(x1, x2);
        areaX2 = Math.max(x1, x2);
        areaZ1 = Math.min(z1, z2);
        areaZ2 = Math.max(z1, z2);
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
        if (showLeases) {
            drawLeases(g, w, h);
        }
        if (showPaths) {
            drawPaths(g);
        }
        if (showObservations) {
            drawObservations(g);
        }
        drawMarkers(g);
        drawSelectionBox(g);
        drawArea(g);
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
        if (!showTerrain || slices.isEmpty()) {
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
            g.setColor(new Color(style.theme.identity(b.name())));
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
            Color color = new Color(style.theme.identity(b.name()));
            TileStatus status = Alerts.status(b, view.alertsFor(b.name()));
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            drawShape(g, status.level(), sx, sy);
            if (selection.contains(b.name())) {
                g.setColor(new Color(style.theme.accent()));
                g.drawOval(sx - 12, sy - 12, 24, 24);
                g.setColor(color);
            }
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
            g.setColor(new Color(style.theme.identity(bot.name())));
            g.fillRect(sx - 2, sy - 2, 4, 4);
        }
    }

    private void drawLeases(Graphics2D g, int w, int h) {
        g.setFont(getFont());
        for (MapOverlay.LeaseMark lease : MapOverlay.leases(view.coordinator(), dim)) {
            if (lease.box() == null) {
                continue;
            }
            MapOverlay.Rect r = MapOverlay.screenBox(camera, lease.box());
            int rgb = leaseRgb(lease.type());
            Color color = lease.live() ? new Color(rgb)
                    : new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 110);
            g.setColor(color);
            if ("vein".equals(lease.type())) {
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
                g.drawRect(r.x(), r.y(), r.w(), r.h());
            } else if ("chest".equals(lease.type())) {
                g.setStroke(new BasicStroke(2f));
                int cx = r.x() + r.w() / 2;
                int cy = r.y() + r.h() / 2;
                g.fillRect(cx - 4, cy - 4, 8, 8);
            } else {
                g.setStroke(new BasicStroke(2f));
                g.drawRect(r.x(), r.y(), r.w(), r.h());
            }
            int[] at = MapOverlay.labelAt(r, w, h, 8, 12);
            g.setColor(new Color(style.theme.text()));
            g.drawString(lease.label(), at[0], at[1]);
        }
    }

    private void drawObservations(Graphics2D g) {
        g.setStroke(new BasicStroke(2f));
        for (MapOverlay.ObsMark obs : MapOverlay.observations(view.coordinator(), dim)) {
            if (obs.box() == null) {
                continue;
            }
            MapOverlay.Rect r = MapOverlay.screenBox(camera, obs.box());
            int sx = r.x() + r.w() / 2;
            int sy = r.y() + r.h() / 2;
            g.setColor(new Color(style.theme.accent()));
            drawObsShape(g, obs.kind(), obs.state(), obs.suspect(), sx, sy);
        }
    }

    /** Kind picks the shape; state picks filled, hollow, cross or a suspect ring. */
    static void drawObsShape(Graphics2D g, String kind, String state, boolean suspect, int sx, int sy) {
        boolean hollow = "stale".equals(state) || "gone".equals(state);
        int r = 6;
        switch (kind == null ? "" : kind) {
            case "chest" -> {
                if (hollow) g.drawRect(sx - r, sy - r, r * 2, r * 2);
                else g.fillRect(sx - r, sy - r, r * 2, r * 2);
            }
            case "block" -> {
                if (hollow) g.drawOval(sx - r, sy - r, r * 2, r * 2);
                else g.fillOval(sx - r, sy - r, r * 2, r * 2);
            }
            case "structure" -> g.drawRect(sx - r - 2, sy - r, (r + 2) * 2, r * 2);
            case "waypoint" -> {
                g.drawLine(sx - r, sy, sx + r, sy);
                g.drawLine(sx, sy - r, sx, sy + r);
            }
            default -> { // vein, and anything else: a diamond
                int[] xs = {sx, sx - r, sx, sx + r};
                int[] ys = {sy - r, sy, sy + r, sy};
                if (hollow) g.drawPolygon(xs, ys, 4);
                else g.fillPolygon(xs, ys, 4);
            }
        }
        if ("gone".equals(state)) {
            g.drawLine(sx - r, sy - r, sx + r, sy + r);
            g.drawLine(sx - r, sy + r, sx + r, sy - r);
        }
        if (suspect) {
            g.drawOval(sx - r - 3, sy - r - 3, (r + 3) * 2, (r + 3) * 2);
        }
    }

    private int leaseRgb(String type) {
        return switch (type) {
            case "vein" -> style.theme.warn();
            case "chest" -> style.theme.ok();
            default -> style.theme.accent();
        };
    }

    private String tipAt(int sx, int sy) {
        if (showLeases) {
            for (MapOverlay.LeaseMark lease : MapOverlay.leases(view.coordinator(), dim)) {
                if (lease.box() == null) {
                    continue;
                }
                if (MapOverlay.screenBox(camera, lease.box()).contains(sx, sy)) {
                    return lease.tip();
                }
            }
        }
        if (showObservations) {
            for (MapOverlay.ObsMark obs : MapOverlay.observations(view.coordinator(), dim)) {
                if (obs.box() == null) {
                    continue;
                }
                MapOverlay.Rect r = MapOverlay.screenBox(camera, obs.box());
                int cx = r.x() + r.w() / 2;
                int cy = r.y() + r.h() / 2;
                if (Math.abs(sx - cx) <= 8 && Math.abs(sy - cy) <= 8) {
                    return obs.tip();
                }
            }
        }
        return "";
    }
}
