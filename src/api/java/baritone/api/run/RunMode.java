/*
 * Ostinato: swarm run mode. Not part of upstream Baritone.
 */
package baritone.api.run;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;

import java.util.Locale;

/**
 * {@code #set runMode normal|hidden|headless}. The default is {@link Value#NORMAL}.
 * <p>
 * {@link #windowCreateMode()} reads {@code -Dostinato.runMode} only. The window is created before settings load,
 * so that property is the initial window policy. A missing or blank property is {@link Value#NORMAL}. An unknown
 * token is rejected: the window stays on the normal policy, and {@link #propertyError()} holds the reason.
 * <p>
 * After {@link #installStartup}, {@link #current()} follows the setting, so {@code #set runMode} changes the mode.
 * When the property was a known token, {@link #installStartup} copies it over the loaded setting once. That is how
 * the property wins the initial mode. A later {@code #set} wins after that.
 * <p>
 * {@link Value#HEADLESS} is the true-headless path ({@link baritone.api.headless.HeadlessPolicy}). {@link #isHeadless()}
 * reports it. Hidden-mode render reduction does not run for it. A process started as headless does not create a
 * GPU device or an SDL window. See the headless notes.
 */
public final class RunMode {

    /** JVM property read at window-create time. {@code -Dostinato.runMode=normal|hidden|headless}. */
    public static final String PROPERTY = "ostinato.runMode";

    private static final String UNSET = "\u0000";

    private static volatile boolean settingsAuthoritative;
    private static volatile String propertyError;
    private static String cachedRaw = UNSET;
    private static Value cachedMode = Value.NORMAL;

    private RunMode() {}

    public enum Value {
        NORMAL,
        HIDDEN,
        HEADLESS;

        /** Lowercase token stored by {@code #set} and {@code settings.txt}. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Accepts {@code normal}, {@code hidden}, and {@code headless}, ignoring case and surrounding space.
         *
         * @throws IllegalArgumentException when {@code raw} is anything else, including null or blank
         */
        public static Value parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("runMode expects normal, hidden, or headless");
            }
            String token = raw.trim().toLowerCase(Locale.ROOT);
            return switch (token) {
                case "normal" -> NORMAL;
                case "hidden" -> HIDDEN;
                case "headless" -> HEADLESS;
                default -> throw new IllegalArgumentException(
                        "runMode expects normal, hidden, or headless, not \"" + raw.trim() + "\"");
            };
        }
    }

    /**
     * Mode for code that runs before settings exist, including SDL window creation. Does not read the setting.
     * Unknown tokens become {@link Value#NORMAL} and are recorded in {@link #propertyError()}.
     */
    public static Value windowCreateMode() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            propertyError = null;
            cachedRaw = raw == null ? "" : raw;
            cachedMode = Value.NORMAL;
            return Value.NORMAL;
        }
        if (raw.equals(cachedRaw)) {
            return cachedMode;
        }
        cachedRaw = raw;
        try {
            cachedMode = Value.parse(raw);
            propertyError = null;
            return cachedMode;
        } catch (IllegalArgumentException e) {
            propertyError = e.getMessage();
            cachedMode = Value.NORMAL;
            System.err.println("Ostinato: " + e.getMessage() + ". Using normal for the initial window.");
            return Value.NORMAL;
        }
    }

    /** Last rejection from {@link #windowCreateMode()} or {@link #installStartup}, or null when the property is valid or unset. */
    public static String propertyError() {
        return propertyError;
    }

    /**
     * Call once settings have been read. A known {@code -Dostinato.runMode} overwrites the loaded value so the
     * setting matches the window that was just created. An unknown token does not overwrite the loaded value.
     */
    public static void installStartup(Settings settings) {
        String raw = System.getProperty(PROPERTY);
        if (settings != null && raw != null && !raw.isBlank()) {
            try {
                settings.runMode.value = Value.parse(raw);
                propertyError = null;
            } catch (IllegalArgumentException e) {
                propertyError = e.getMessage();
                System.err.println("Ostinato: " + e.getMessage()
                        + ". The runMode setting was left as loaded, and the initial window stays normal.");
            }
        }
        settingsAuthoritative = true;
    }

    /** True after {@link #installStartup}. Tests use {@link #resetForTests()} to read the property again. */
    public static boolean settingsAuthoritative() {
        return settingsAuthoritative;
    }

    /**
     * Effective mode. Before settings are installed, this is {@link #windowCreateMode()}. Afterwards it is the
     * setting, which {@code #set runMode} updates.
     */
    public static Value current() {
        if (!settingsAuthoritative) {
            return windowCreateMode();
        }
        Value setting = BaritoneAPI.getSettings().runMode.value;
        return setting == null ? Value.NORMAL : setting;
    }

    public static boolean isHidden() {
        return current() == Value.HIDDEN;
    }

    public static boolean isHeadless() {
        return current() == Value.HEADLESS;
    }

    public static boolean isNormal() {
        return current() == Value.NORMAL;
    }

    /** Tests only. Does not change the setting or the JVM property. */
    public static void resetForTests() {
        settingsAuthoritative = false;
        propertyError = null;
        cachedRaw = UNSET;
        cachedMode = Value.NORMAL;
    }
}
