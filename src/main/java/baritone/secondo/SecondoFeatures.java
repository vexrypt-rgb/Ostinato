/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

import baritone.Baritone;
import baritone.api.Settings;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: gates for features Secondo may use once they are on this branch.
 * Detection is classpath plus the feature's own flag. {@link #invoke} is always false in v1:
 * a missing type, a flag that is off, and a flag that is on all take the same path (no call).
 * <p>
 * Planned types, first landed on the PPE branch; since the combine/main-maze-ppe merges all six are on this main too,
 * but {@link #invoke} still calls none of them:
 * <ul>
 * <li>{@code baritone.api.physics.IPredictivePhysics} — PPE. Consulted only if Grace Note, Presto, or Bombard is ready.</li>
 * <li>{@code baritone.api.gracenote.IGraceNote} — water clutch. Property {@code ostinato.graceNote}, default observe.</li>
 * <li>{@code baritone.pathing.cadenza.Cadenza} — stand cells, aim margin, cache invalidation. Setting {@code cadenza}, default off.</li>
 * <li>{@code baritone.bombard.Bombard} — TNT combat helper. Property {@code ostinato.bombard}, default off.</li>
 * <li>{@code baritone.scherzo.Scherzo} — fight prediction. Property {@code ostinato.scherzo}, default observe.</li>
 * <li>{@code baritone.pathing.kinematic.Presto} — predictive execution consumer. Setting {@code presto}, default off. Secondo does not own it.</li>
 * </ul>
 */
public final class SecondoFeatures {

    public enum Id {
        PPE("ppe", "baritone.api.physics.IPredictivePhysics", null, null, null),
        GRACE_NOTE("grace", "baritone.api.gracenote.IGraceNote", null, "ostinato.graceNote", "observe"),
        CADENZA("cadenza", "baritone.pathing.cadenza.Cadenza", "cadenza", null, null),
        BOMBARD("bombard", "baritone.bombard.Bombard", null, "ostinato.bombard", "off"),
        SCHERZO("scherzo", "baritone.scherzo.Scherzo", null, "ostinato.scherzo", "observe"),
        PRESTO("presto", "baritone.pathing.kinematic.Presto", "presto", null, null);

        public final String wire;
        public final String typeName;
        /** Boolean setting name on main Settings, or null when this feature uses a property or no flag. */
        public final String setting;
        public final String property;
        /** Used when the property is unset. Null when there is no property. */
        public final String propertyDefault;

        Id(String wire, String typeName, String setting, String property, String propertyDefault) {
            this.wire = wire;
            this.typeName = typeName;
            this.setting = setting;
            this.property = property;
            this.propertyDefault = propertyDefault;
        }
    }

    public enum State { ABSENT, OFF, READY }

    /** Reads type presence, a boolean setting, and a system property. A missing setting is off. */
    public interface Lookup {
        boolean typePresent(String typeName);

        boolean settingOn(String settingName);

        /** Null when the property is unset. */
        String property(String key);
    }

    private SecondoFeatures() {
    }

    public static boolean classPresent(String typeName) {
        try {
            Class.forName(typeName, false, SecondoFeatures.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static State probe(Id id, Lookup lookup) {
        if (!lookup.typePresent(id.typeName)) {
            return State.ABSENT;
        }
        if (id.setting != null && !lookup.settingOn(id.setting)) {
            return State.OFF;
        }
        if (id.property != null) {
            String raw = lookup.property(id.property);
            if (!flagOn(raw == null ? id.propertyDefault : raw)) {
                return State.OFF;
            }
        }
        return State.READY;
    }

    public static Map<Id, State> probeAll(Lookup lookup) {
        EnumMap<Id, State> out = new EnumMap<>(Id.class);
        for (Id id : Id.values()) {
            out.put(id, probe(id, lookup));
        }
        return out;
    }

    /** Live gate. Settings that are not on this branch read as off. */
    public static Map<Id, State> probeHere() {
        Settings settings = Baritone.settings();
        return probeAll(new Lookup() {
            @Override
            public boolean typePresent(String typeName) {
                return classPresent(typeName);
            }

            @Override
            public boolean settingOn(String settingName) {
                Settings.Setting<?> s = settings.byLowerName.get(settingName.toLowerCase(Locale.ROOT));
                return s != null && Boolean.TRUE.equals(s.value);
            }

            @Override
            public String property(String key) {
                return System.getProperty(key);
            }
        });
    }

    /**
     * PPE is useful to Secondo only when a consumer that needs a prediction is ready.
     * The class being present is not enough.
     */
    public static boolean consultPpe(Map<Id, State> states) {
        if (states.get(Id.PPE) != State.READY) {
            return false;
        }
        return states.get(Id.GRACE_NOTE) == State.READY
                || states.get(Id.PRESTO) == State.READY
                || states.get(Id.BOMBARD) == State.READY;
    }

    /**
     * v1 call site. Always false: ready means "the type and flag are there", not "call it".
     * The next phase replaces this once the type is on main's compile classpath.
     */
    public static boolean invoke(Id id, State state) {
        return false;
    }

    public static String report(Map<Id, State> states) {
        StringBuilder sb = new StringBuilder();
        for (Id id : Id.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            State st = states.get(id);
            sb.append(id.wire).append('=').append(st == null ? "absent" : st.name().toLowerCase(Locale.ROOT));
        }
        sb.append(" ppeConsult=").append(consultPpe(states) ? "yes" : "no");
        sb.append(" invoke=noop");
        return sb.toString();
    }

    static boolean flagOn(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "live", "observe" -> true;
            default -> false;
        };
    }
}
