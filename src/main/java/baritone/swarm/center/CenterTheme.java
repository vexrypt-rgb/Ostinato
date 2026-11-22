package baritone.swarm.center;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: one command-center theme, a set of named color tokens from the design doc (Color schemes / themes).
 * Colors are 0xRRGGBB so tests can check contrast without AWT. Built-ins: Default dark, Light, High contrast and
 * Colorblind safe. Custom themes are JSON files of the same token names (see {@link ThemeStore}).
 * Flavor themes and the in-game theme editor are not in v1.
 */
public record CenterTheme(
        int background,
        int surface,
        int raised,
        int border,
        int text,
        int muted,
        int faint,
        int accent,
        int onAccent,
        int ok,
        int warn,
        int danger
) {
    /** Token names in record order. Custom JSON files use these keys. */
    public static final List<String> TOKENS = List.of("background", "surface", "raised", "border", "text", "muted",
            "faint", "accent", "onAccent", "ok", "warn", "danger");

    public static final CenterTheme DEFAULT_DARK = new CenterTheme(
            0x14161B, // background: window base
            0x1C2028, // surface: header, tiles, panels
            0x262B35, // raised: buttons, inputs
            0x343B48, // border
            0xE8EAEE, // text
            0xA9B0BD, // muted
            0x7D8594, // faint: section captions
            0x5B8DEF, // accent: selection, focus panel
            0x0B0D12, // onAccent
            0x46C46A, // ok
            0xE0A93A, // warn
            0xF06A60  // danger
    );

    public static final CenterTheme LIGHT = new CenterTheme(
            0xF4F5F7, 0xFFFFFF, 0xECEEF2, 0xC4CAD3,
            0x1A1D23, 0x4A5160, 0x687080,
            0x2B5BC0, 0xFFFFFF,
            0x1B7A37, 0x8A5800, 0xC0352B
    );

    /** Pure black and white with bright status colors. Text is well above AAA. */
    public static final CenterTheme HIGH_CONTRAST = new CenterTheme(
            0x000000, 0x000000, 0x1A1A1A, 0xFFFFFF,
            0xFFFFFF, 0xE6E6E6, 0xC8C8C8,
            0x66CCFF, 0x000000,
            0x66FF88, 0xFFE04D, 0xFF7A7A
    );

    /**
     * Default dark surfaces with an Okabe-Ito style status set: ok is sky blue, warn is yellow, danger is orange.
     * No red and green pair, so deuteranopia and protanopia still tell them apart, and each differs in lightness too.
     */
    public static final CenterTheme COLORBLIND_SAFE = new CenterTheme(
            0x14161B, 0x1C2028, 0x262B35, 0x343B48,
            0xE8EAEE, 0xA9B0BD, 0x7D8594,
            0xB79CFF, 0x0B0D12,
            0x56B4E9, 0xF0E442, 0xFF9A4D
    );

    /** Display label for a built-in name. {@code ThemeStore.label} uses this. */
    public static String label(String name) {
        return switch (name == null ? "" : name) {
            case "dark" -> "Default dark";
            case "light" -> "Light";
            case "high-contrast" -> "High contrast";
            case "colorblind" -> "Colorblind safe";
            default -> name == null ? "" : name;
        };
    }

    /** A built-in theme. An unknown name is Default dark. */
    public static CenterTheme builtIn(String name) {
        return switch (name == null ? "" : name) {
            case "light" -> LIGHT;
            case "high-contrast" -> HIGH_CONTRAST;
            case "colorblind" -> COLORBLIND_SAFE;
            default -> DEFAULT_DARK;
        };
    }

    /** Status tokens. Idle is the muted token; the others are the status tokens already on the theme. */
    public int statusOk() { return ok; }
    public int statusIdle() { return muted; }
    public int statusWarn() { return warn; }
    public int statusBad() { return danger; }

    /**
     * Twelve identity colors. The same name always lands on the same slot. The colorblind-safe theme uses
     * {@link #COLORBLIND_IDENTITY}; every other theme uses {@link #IDENTITY}. Markers use this palette.
     */
    public static final int[] IDENTITY = {
            0x5B8DEF, 0xE07A3D, 0x3DDC8A, 0xD6C04A, 0xC46BD0, 0x4ECDC4,
            0xE06B8A, 0x8A7AE0, 0x7DDE4A, 0xE0A05A, 0x5AA0E0, 0xE07070
    };

    /**
     * Twelve colors that stay at least ΔE 10 apart under simulated deuteranopia and protanopia.
     * Light enough to read on the dark map.
     */
    public static final int[] COLORBLIND_IDENTITY = {
            0xC75A5A, 0xA200F2, 0xF2F200, 0x6DC6F2, 0x9E9E00, 0x6DF2C6,
            0x9E4781, 0x6DF283, 0x905AC7, 0x6DF2F2, 0x9E4F00, 0xF26DB0
    };

    public int[] identityPalette() {
        return equals(COLORBLIND_SAFE) ? COLORBLIND_IDENTITY : IDENTITY;
    }

    public int identity(String name) {
        return identityAt(identityPalette(), name);
    }

    public static int identityAt(int[] palette, String name) {
        int[] colors = palette == null || palette.length == 0 ? IDENTITY : palette;
        int h = 0;
        String n = name == null ? "" : name;
        for (int i = 0; i < n.length(); i++) {
            h = 31 * h + n.charAt(i);
        }
        return colors[Math.floorMod(h, colors.length)];
    }

    public int token(String name) {
        return switch (name) {
            case "background" -> background;
            case "surface" -> surface;
            case "raised" -> raised;
            case "border" -> border;
            case "text" -> text;
            case "muted" -> muted;
            case "faint" -> faint;
            case "accent" -> accent;
            case "onAccent" -> onAccent;
            case "ok" -> ok;
            case "warn" -> warn;
            case "danger" -> danger;
            default -> throw new IllegalArgumentException("unknown token " + name);
        };
    }

    /** This theme with the given tokens replaced. Unknown keys are ignored. */
    public CenterTheme with(Map<String, Integer> tokens) {
        Map<String, Integer> all = new LinkedHashMap<>();
        for (String name : TOKENS) {
            Integer given = tokens == null ? null : tokens.get(name);
            all.put(name, (given == null ? token(name) : given) & 0xFFFFFF);
        }
        return new CenterTheme(all.get("background"), all.get("surface"), all.get("raised"), all.get("border"),
                all.get("text"), all.get("muted"), all.get("faint"), all.get("accent"), all.get("onAccent"),
                all.get("ok"), all.get("warn"), all.get("danger"));
    }

    /**
     * The WCAG AA pairs this theme fails, empty when it passes. Text and muted need 4.5 on every surface,
     * captions 3 on surfaces, status and accent colors 4.5 on surfaces, and on-accent text 4.5 on the accent.
     */
    public List<String> aaProblems() {
        List<String> problems = new ArrayList<>();
        int[] grounds = {background, surface, raised};
        String[] groundNames = {"background", "surface", "raised"};
        for (int i = 0; i < grounds.length; i++) {
            need(problems, "text", text, groundNames[i], grounds[i], 4.5);
            need(problems, "muted", muted, groundNames[i], grounds[i], 4.5);
        }
        need(problems, "faint", faint, "surface", surface, 3.0);
        need(problems, "onAccent", onAccent, "accent", accent, 4.5);
        need(problems, "ok", ok, "surface", surface, 4.5);
        need(problems, "warn", warn, "surface", surface, 4.5);
        need(problems, "danger", danger, "surface", surface, 4.5);
        need(problems, "accent", accent, "surface", surface, 4.5);
        for (int i = 0; i < grounds.length; i++) {
            need(problems, "status-ok", statusOk(), groundNames[i], grounds[i], 4.5);
            need(problems, "status-idle", statusIdle(), groundNames[i], grounds[i], 4.5);
            need(problems, "status-warn", statusWarn(), groundNames[i], grounds[i], 4.5);
            need(problems, "status-bad", statusBad(), groundNames[i], grounds[i], 4.5);
        }
        return problems;
    }

    private static void need(List<String> problems, String fg, int a, String bg, int b, double min) {
        double ratio = contrast(a, b);
        if (ratio < min) {
            problems.add(fg + " on " + bg + " " + String.format(java.util.Locale.ROOT, "%.1f", ratio) + ":1");
        }
    }

    /** WCAG relative-luminance contrast ratio between two 0xRRGGBB colors. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Machado, Oliveira and Fernandes 2009, full severity, on linear RGB. */
    public static final double[][] PROTAN = {
            {0.152286, 1.052583, -0.204868},
            {0.114503, 0.786281, 0.099216},
            {-0.003882, -0.048116, 1.051998}
    };
    public static final double[][] DEUTAN = {
            {0.367322, 0.860646, -0.227968},
            {0.280085, 0.672501, 0.047413},
            {-0.011820, 0.042940, 0.968881}
    };

    /** CIE76 ΔE between two colors after the given color-vision matrix. */
    public static double deltaE(int a, int b, double[][] matrix) {
        double[] x = lab(simulate(a, matrix));
        double[] y = lab(simulate(b, matrix));
        double dl = x[0] - y[0];
        double da = x[1] - y[1];
        double db = x[2] - y[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double[] simulate(int rgb, double[][] m) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(1, m[i][0] * r + m[i][1] * g + m[i][2] * b));
        }
        return out;
    }

    private static double linear(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double[] lab(double[] linearRgb) {
        double r = linearRgb[0];
        double g = linearRgb[1];
        double b = linearRgb[2];
        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double xn = 0.95047;
        double yn = 1.0;
        double zn = 1.08883;
        double l = 116 * labf(y / yn) - 16;
        double aa = 500 * (labf(x / xn) - labf(y / yn));
        double bb = 200 * (labf(y / yn) - labf(z / zn));
        return new double[]{l, aa, bb};
    }

    private static double labf(double t) {
        return t > 0.008856451679035631 ? Math.cbrt(t) : t / 0.12841854934601665 + 0.13793103448275862;
    }
}
