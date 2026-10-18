/*
 * Ostinato: regression suite. PkTest course scripts, read headlessly. New file, replaces no Baritone code.
 */
package baritone.regress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: a PkTest course script (the files pk-queue.ps1 feeds -Dostinato.pktest.goals): {@code tag}, {@code cmd},
 * {@code set}, {@code tp}, {@code goal}, {@code near}, plus the live-only {@code wait}, {@code hop}, {@code vizprof}
 * which a headless run skips. Only the {@code fill}, {@code setblock} and {@code forceload add} commands change the
 * headless world; any other command is counted in {@link #ignoredCommands}.
 */
final class Course {

    record Step(String kind, int x, int y, int z, int r, String text, int line) {}

    final String name;
    final List<Step> steps = new ArrayList<>();
    int ignoredCommands;

    private Course(String name) {
        this.name = name;
    }

    static Course read(String name, List<String> lines) {
        Course c = new Course(name);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] a = line.split("\\s+");
            switch (a[0]) {
                case "cmd" -> c.steps.add(new Step("cmd", 0, 0, 0, 0, line.substring(4).trim(), i + 1));
                case "set" -> c.steps.add(new Step("set", 0, 0, 0, 0, line.substring(4).trim(), i + 1));
                case "tag" -> c.steps.add(new Step("tag", 0, 0, 0, 0, a.length > 1 ? a[1] : "", i + 1));
                case "tp", "goal" -> c.steps.add(new Step(a[0], fl(a[1]), fl(a[2]), fl(a[3]), 0, "", i + 1));
                case "near" -> c.steps.add(new Step("near", fl(a[1]), fl(a[2]), fl(a[3]), a.length > 4 ? Integer.parseInt(a[4]) : 2, "", i + 1));
                default -> { } // wait, hop, vizprof: timing and input probes for the live client
            }
        }
        return c;
    }

    static Course read(String name, Path file) throws IOException {
        return read(name, Files.readAllLines(file));
    }

    private static int fl(String s) {
        return (int) Math.floor(Double.parseDouble(s));
    }

    /** Applies one {@code cmd} line to the headless world; false when it is not a world-building command. */
    boolean apply(CourseWorld w, String cmd) {
        String[] a = cmd.split("\\s+");
        try {
            switch (a[0]) {
                case "setblock" -> {
                    if (a.length < 5 || cmd.contains("~")) return ignored();
                    w.set(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), w.parse(a[4]));
                    return true;
                }
                case "fill" -> {
                    if (a.length < 8 || cmd.contains("~")) return ignored();
                    int x0 = Integer.parseInt(a[1]), y0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]);
                    int x1 = Integer.parseInt(a[4]), y1 = Integer.parseInt(a[5]), z1 = Integer.parseInt(a[6]);
                    var state = w.parse(a[7]);
                    String mode = a.length > 8 ? a[8] : "replace";
                    var filter = mode.equals("replace") && a.length > 9 ? w.parse(a[9]) : null;
                    int ax = Math.min(x0, x1), bx = Math.max(x0, x1), ay = Math.min(y0, y1), by = Math.max(y0, y1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
                    var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    for (int x = ax; x <= bx; x++)
                        for (int y = ay; y <= by; y++)
                            for (int z = az; z <= bz; z++) {
                                boolean shell = x == ax || x == bx || y == ay || y == by || z == az || z == bz;
                                switch (mode) {
                                    case "keep" -> { if (w.get0(x, y, z).isAir()) w.set(x, y, z, state); }
                                    case "hollow" -> w.set(x, y, z, shell ? state : air);
                                    case "outline" -> { if (shell) w.set(x, y, z, state); }
                                    default -> { if (filter == null || w.get0(x, y, z) == filter) w.set(x, y, z, state); }
                                }
                            }
                    return true;
                }
                case "forceload" -> {
                    if (a.length < 6 || !a[1].equals("add")) return ignored();
                    int x0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]), x1 = Integer.parseInt(a[4]), z1 = Integer.parseInt(a[5]);
                    for (int x = Math.min(x0, x1) >> 4; x <= Math.max(x0, x1) >> 4; x++)
                        for (int z = Math.min(z0, z1) >> 4; z <= Math.max(z0, z1) >> 4; z++) w.touch(x << 4, z << 4);
                    return true;
                }
                default -> {
                    return ignored();
                }
            }
        } catch (NumberFormatException e) {
            return ignored();
        }
    }

    private boolean ignored() {
        ignoredCommands++;
        return false;
    }
}
