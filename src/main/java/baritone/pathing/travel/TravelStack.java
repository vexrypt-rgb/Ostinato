package baritone.pathing.travel;

import baritone.api.movement.MovementBackendKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ostinato: which travel layer drives a spot, in order. The block route stays the Baritone search.
 * Auto is kinematic, then physics, then the sim probe, then Baritone's own per-movement backup.
 * A named preference tries that layer and then the backup, so a bench can pin one layer.
 */
public final class TravelStack {

    public enum Layer {
        KINEMATIC, PHYSICS, SIM, BARITONE
    }

    private TravelStack() {}

    /** {@code travelStack} setting text. Unknown and empty mean auto. */
    public static MovementBackendKind preference(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return MovementBackendKind.AUTO;
        }
        switch (raw.trim().toLowerCase()) {
            case "baritone":
            case "classic":
            case "backup":
                return MovementBackendKind.BARITONE;
            case "kinematic":
            case "kin":
                return MovementBackendKind.KINEMATIC;
            case "physics":
            case "phys":
                return MovementBackendKind.PHYSICS;
            case "sim":
            case "tas":
            case "probe":
                return MovementBackendKind.SIM;
            default:
                return MovementBackendKind.AUTO;
        }
    }

    /**
     * Layers to try, backup last. {@code kinematicOn} is {@code kinematicTravel} (experimental movement counts).
     * {@code simOn} is {@code simTravel}. Auto always includes physics after kinematic: that search only runs
     * when the kinematic family left the spot, and it keeps its own cooldown.
     */
    public static List<Layer> order(MovementBackendKind preference, boolean kinematicOn, boolean simOn) {
        MovementBackendKind pref = preference == null ? MovementBackendKind.AUTO : preference;
        List<Layer> layers = new ArrayList<>(4);
        switch (pref) {
            case BARITONE:
                layers.add(Layer.BARITONE);
                break;
            case KINEMATIC:
                if (kinematicOn) {
                    layers.add(Layer.KINEMATIC);
                }
                layers.add(Layer.BARITONE);
                break;
            case PHYSICS:
                layers.add(Layer.PHYSICS);
                layers.add(Layer.BARITONE);
                break;
            case SIM:
                if (simOn) {
                    layers.add(Layer.SIM);
                }
                layers.add(Layer.BARITONE);
                break;
            case AUTO:
            default:
                if (kinematicOn) {
                    layers.add(Layer.KINEMATIC);
                }
                layers.add(Layer.PHYSICS);
                if (simOn) {
                    layers.add(Layer.SIM);
                }
                layers.add(Layer.BARITONE);
                break;
        }
        return Collections.unmodifiableList(layers);
    }

    /** True when a solved sim or physics plan means the Baritone movement backup must not run this tick. */
    public static boolean backupUnused(Layer drove) {
        return drove != null && drove != Layer.BARITONE;
    }
}
