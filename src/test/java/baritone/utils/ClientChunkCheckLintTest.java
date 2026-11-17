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

package baritone.utils;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Ostinato: in 26.3 {@code ClientLevel.hasChunk(int, int)} returns true for every chunk, and
 * {@code LevelReader.hasChunkAt} delegates to it, so on a client neither tells a held chunk from one the client never
 * received (which reads as air). Ask the chunk source instead ({@code level.getChunkSource().hasChunk(cx, cz)}, or
 * {@code Level.isLoaded(pos)}, which does). This test fails on any new level-wide check. The sites below were audited
 * on 17 Nov 2026 (main @ 4cc3f5769); each says why it is left as it is. Remove an entry when its line is fixed.
 */
public class ClientChunkCheckLintTest {

    /** A level-wide chunk check: {@code .hasChunkAt(} anywhere, or {@code .hasChunk(} not on a chunk source. */
    private static final Pattern LEVEL_CHECK = Pattern.compile("\\.hasChunkAt\\(|(?<!getChunkSource\\(\\))(?<!chunkProvider)(?<!provider)(?<!npfContext)\\.hasChunk\\(");

    /** File (relative to the source root) and a fragment of the line → why it stays. */
    private static final Map<String, String> AUDITED = new LinkedHashMap<>();

    static {
        AUDITED.put("baritone/behavior/SwarmBehavior.java|!w.hasChunkAt(p)",
                "Phase 2 reservation world; replaced by LoadedWorld in PR 156 (fix in that PR, not here)");
        AUDITED.put("baritone/legit/McSeenView.java|!world.hasChunkAt(pos)",
                "legit visibility: unloaded reads as see-through air; effect on LegitTargets needs the legit-mode owner and a live run");
        AUDITED.put("baritone/legit/LegitGatherLive.java|!world.hasChunkAt(pos)",
                "legit gather facts: same question as McSeenView");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(m)) return Integer.MIN_VALUE",
                "landing scan: an unloaded column reads as 40 blocks of air and ends as too deep, the same result");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(s) || !floor(s.below())",
                "safety scan within 10 blocks of the player: always loaded");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(b)) return null",
                "clutch: the column under a falling player is always loaded");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(b) || !ctx.world().getBlockState(b).is(Blocks.NETHERRACK)",
                "lava floor sample: unloaded reads as air, which is skipped like unloaded");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(e.getKey())) return true",
                "step targets over ~96 blocks away count as done; narrow, needs a bastion live run to change");
        AUDITED.put("baritone/process/BastionProcess.java|!ctx.world().hasChunkAt(b)) { unloaded = true; continue; }",
                "plan points: matters only past render distance; chestsExhausted also needs the bot within 40 blocks; needs a bastion live run");
        AUDITED.put("baritone/scherzo/Scherzo.java|!level.hasChunkAt(p)) return Double.NaN",
                "opponent ground: an unloaded column reads as air and also ends in NaN, the same result");
        AUDITED.put("baritone/structure/PkTest.java|!lv.hasChunk(x >> 4, z >> 4)",
                "test harness plate scan: unloaded reads as air, so no plates, the same as skipping");
    }

    private static Path root(String rel) {
        Path p = Path.of(rel);
        return Files.exists(p) ? p : Path.of("..").resolve(rel);
    }

    @Test
    public void noNewLevelWideChunkChecks() throws IOException {
        List<String> found = new ArrayList<>();
        for (String srcRoot : new String[]{"src/main/java", "src/launch/java", "src/api/java"}) {
            Path base = root(srcRoot);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String rel = base.relativize(f).toString().replace('\\', '/');
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
                        if (code.trim().startsWith("*") || !LEVEL_CHECK.matcher(code).find()) {
                            continue;
                        }
                        boolean audited = false;
                        for (String k : AUDITED.keySet()) {
                            int bar = k.indexOf('|');
                            if (rel.equals(k.substring(0, bar)) && code.contains(k.substring(bar + 1))) {
                                audited = true;
                                break;
                            }
                        }
                        if (!audited) {
                            found.add(srcRoot + "/" + rel + ":" + (i + 1) + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertTrue("level-wide chunk checks are always true on a 26.3 client; use the chunk source:\n"
                + String.join("\n", found), found.isEmpty());
    }

    @Test
    public void thePatternCatchesTheLevelChecksAndNotTheChunkSource() {
        assertTrue(LEVEL_CHECK.matcher("if (!world.hasChunkAt(pos)) {").find());
        assertTrue(LEVEL_CHECK.matcher("if (level.hasChunk(cx, cz)) {").find());
        assertTrue(!LEVEL_CHECK.matcher("level.getChunkSource().hasChunk(cx, cz)").find());
        assertTrue(!LEVEL_CHECK.matcher("return provider.hasChunk(blockX >> 4, blockZ >> 4);").find());
    }
}
