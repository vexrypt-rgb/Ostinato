/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.gui.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Key names for the {@code guiKeybind} setting mapped to GLFW key codes. The codes are written out as ints so
 * this class needs no LWJGL.
 */
public final class KeyNames {

    public static final int NONE = -1;
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_CODE = new HashMap<>();

    static {
        // first name registered for a code is the canonical display name
        k(345, "RCONTROL", "RCTRL", "RIGHT_CONTROL", "RIGHTCONTROL");
        k(344, "RSHIFT", "RIGHT_SHIFT", "RIGHTSHIFT");
        k(346, "RALT", "RIGHT_ALT", "RIGHTALT");
        k(341, "LCONTROL", "LCTRL", "LEFT_CONTROL");
        k(340, "LSHIFT", "LEFT_SHIFT");
        k(342, "LALT", "LEFT_ALT");
        k(96, "GRAVE", "BACKTICK", "`");
        k(258, "TAB");
        k(260, "INSERT");
        k(261, "DELETE");
        k(268, "HOME");
        k(269, "END");
        k(266, "PAGE_UP", "PAGEUP");
        k(267, "PAGE_DOWN", "PAGEDOWN");
        k(92, "BACKSLASH", "\\");
        k(59, "SEMICOLON", ";");
        k(39, "APOSTROPHE", "'");
        k(44, "COMMA", ",");
        k(46, "PERIOD", ".");
        k(47, "SLASH", "/");
        k(45, "MINUS", "-");
        k(61, "EQUAL", "=");
        k(91, "LEFT_BRACKET", "[");
        k(93, "RIGHT_BRACKET", "]");
        k(32, "SPACE");
        for (char c = 'A'; c <= 'Z'; c++) {
            k(c, String.valueOf(c));
        }
        for (int d = 0; d <= 9; d++) {
            k(48 + d, String.valueOf(d));
            k(320 + d, "KP_" + d, "NUMPAD" + d);
        }
        for (int f = 1; f <= 25; f++) {
            k(289 + f, "F" + f);
        }
    }

    private KeyNames() {}

    private static void k(int code, String... names) {
        for (String n : names) {
            BY_NAME.put(n, code);
        }
        BY_CODE.putIfAbsent(code, names[0]);
    }

    /** @return the GLFW key code, or {@link #NONE} for blank/NONE/unknown names */
    public static int parse(String name) {
        if (name == null) {
            return NONE;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.startsWith("KEY_")) {
            n = n.substring(4);
        }
        if (n.isEmpty() || n.equals("NONE")) {
            return NONE;
        }
        Integer c = BY_NAME.get(n);
        if (c != null) {
            return c;
        }
        try {
            int code = Integer.parseInt(n);
            return code > 0 ? code : NONE;
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    /** Canonical name for a key code, falling back to the number (still parseable by {@link #parse}). */
    public static String name(int code) {
        if (code < 0) {
            return "NONE";
        }
        String n = BY_CODE.get(code);
        return n != null ? n : String.valueOf(code);
    }
}
