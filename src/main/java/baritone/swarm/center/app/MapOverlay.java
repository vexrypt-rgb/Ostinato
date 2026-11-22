package baritone.swarm.center.app;

import baritone.swarm.center.feed.CoordinatorFeed;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: Phase 3 window, the leases and observations the war-room map draws. Pure: a box becomes a screen
 * rectangle, a label is kept inside the view, and untyped key leases are listed instead of drawn.
 */
public final class MapOverlay {

    /** A screen rectangle. {@code w} and {@code h} are at least 1. */
    public record Rect(int x, int y, int w, int h) {
        public boolean contains(int sx, int sy) {
            return sx >= x && sy >= y && sx < x + w && sy < y + h;
        }
    }

    /** One drawn lease. {@code label} is {@code bot · gen · state}. {@code tip} adds the end reason when there is one. */
    public record LeaseMark(String id, String type, char dim, int[] box, String bot, long gen, String state,
                            String label, String tip, boolean live) {}

    /** One observation marker. Waypoints with no box are not drawn. */
    public record ObsMark(String key, String kind, char dim, int[] box, String state, boolean suspect, String tip) {}

    private MapOverlay() {}

    public static List<LeaseMark> leases(CoordinatorFeed coordinator, char dim) {
        List<LeaseMark> out = new ArrayList<>();
        if (coordinator == null) {
            return out;
        }
        for (CoordinatorFeed.Lease l : coordinator.leases()) {
            if (l.box() == null || "key".equals(l.type()) || l.dim() != dim) {
                continue;
            }
            String label = l.bot() + " · gen " + l.gen() + " · " + l.state();
            String tip = l.endReason().isEmpty() ? label : label + " · " + l.endReason();
            out.add(new LeaseMark(l.id(), l.type(), l.dim(), l.box(), l.bot(), l.gen(), l.state(), label, tip, l.live()));
        }
        return out;
    }

    /** Untyped key leases, every dimension, one line each. */
    public static List<String> keys(CoordinatorFeed coordinator) {
        List<String> out = new ArrayList<>();
        if (coordinator == null) {
            return out;
        }
        for (CoordinatorFeed.Lease l : coordinator.leases()) {
            if (l.box() != null && !"key".equals(l.type())) {
                continue;
            }
            String line = (l.bot().isEmpty() ? l.id() : l.bot()) + " · gen " + l.gen() + " · " + l.state();
            if (!l.endReason().isEmpty()) {
                line = line + " · " + l.endReason();
            }
            out.add(line);
        }
        return out;
    }

    public static List<ObsMark> observations(CoordinatorFeed coordinator, char dim) {
        List<ObsMark> out = new ArrayList<>();
        if (coordinator == null) {
            return out;
        }
        for (CoordinatorFeed.Observation o : coordinator.observations()) {
            if (o.dim() != dim || o.box() == null) {
                continue;
            }
            String tip = (o.detail().isEmpty() ? o.kind() : o.detail()) + " · " + o.bot() + " · " + o.confidence()
                    + "% · " + (o.ageMs() < 0 ? "age n/a" : (o.ageMs() / 1000) + " s");
            out.add(new ObsMark(o.key(), o.kind(), o.dim(), o.box(), o.state(), o.suspect(), tip));
        }
        return out;
    }

    /**
     * Inclusive block box {@code x1,y1,z1,x2,y2,z2} as a screen rectangle covering every block of it.
     * A missing box is a 1-pixel rect at the origin.
     */
    public static Rect screenBox(MapCamera camera, int[] box) {
        if (camera == null || box == null || box.length < 6) {
            return new Rect(0, 0, 1, 1);
        }
        int x1 = Math.min(box[0], box[3]);
        int z1 = Math.min(box[2], box[5]);
        int x2 = Math.max(box[0], box[3]) + 1;
        int z2 = Math.max(box[2], box[5]) + 1;
        double sx1 = camera.screenX(x1);
        double sz1 = camera.screenZ(z1);
        double sx2 = camera.screenX(x2);
        double sz2 = camera.screenZ(z2);
        int x = (int) Math.round(Math.min(sx1, sx2));
        int y = (int) Math.round(Math.min(sz1, sz2));
        int w = Math.max(1, (int) Math.round(Math.abs(sx2 - sx1)));
        int h = Math.max(1, (int) Math.round(Math.abs(sz2 - sz1)));
        return new Rect(x, y, w, h);
    }

    /** Where to put a label so it stays inside the view. Above the box when there is room, otherwise inside it. */
    public static int[] labelAt(Rect box, int viewW, int viewH, int textW, int textH) {
        int x = box == null ? 2 : box.x();
        int y = box == null ? textH : box.y() - 4;
        if (y < textH) {
            y = box == null ? textH : Math.min(viewH - 2, box.y() + textH + 2);
        }
        x = Math.max(2, Math.min(Math.max(2, viewW - textW - 2), x));
        y = Math.max(textH, Math.min(Math.max(textH, viewH - 2), y));
        return new int[]{x, y};
    }
}
