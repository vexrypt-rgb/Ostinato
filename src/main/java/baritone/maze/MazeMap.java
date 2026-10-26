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

package baritone.maze;

import baritone.maze.MazeSwarmBus.ClaimNotice;
import baritone.maze.MazeSwarmBus.LootNotice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cells this maze has already claimed, and containers that have already been planned, so a second bot does not dig
 * the same room or refill the same chest. The file is the shared copy. {@link MazeSwarmBus} is the hook a later
 * transport will publish through; attaching one does not by itself send anything.
 */
public final class MazeMap implements MazePlanner.CellClaims, LootPlanner.PlacedLedger {

    public static final String HEADER = "ostinato-maze-map 1";

    public static final class Claim {
        public final int cellX;
        public final int cellZ;
        public final long roomSeed;
        public final String themeId;
        public final String botId;

        Claim(int cellX, int cellZ, long roomSeed, String themeId, String botId) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.roomSeed = roomSeed;
            this.themeId = themeId;
            this.botId = botId;
        }
    }

    public static final class LootMark {
        public final int x;
        public final int y;
        public final int z;
        public final String kind;
        public final String themeId;
        public final String payload;

        LootMark(int x, int y, int z, String kind, String themeId, String payload) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.themeId = themeId;
            this.payload = payload;
        }
    }

    private final Map<Long, Claim> claims = new LinkedHashMap<>();
    private final Map<String, LootMark> loot = new LinkedHashMap<>();
    private MazeSwarmBus bus;

    public MazeMap(MazeSwarmBus bus) {
        this.bus = bus == null ? MazeSwarmBus.Nop.INSTANCE : bus;
    }

    public void attach(MazeSwarmBus bus) {
        this.bus = bus == null ? MazeSwarmBus.Nop.INSTANCE : bus;
    }

    public MazeSwarmBus bus() {
        return bus;
    }

    public static long key(int cellX, int cellZ) {
        return (((long) cellX) << 32) ^ (cellZ & 0xffffffffL);
    }

    @Override
    public boolean claimed(int cellX, int cellZ) {
        return claims.containsKey(key(cellX, cellZ));
    }

    public Claim claimAt(int cellX, int cellZ) {
        return claims.get(key(cellX, cellZ));
    }

    public List<Claim> claims() {
        return List.copyOf(claims.values());
    }

    public List<LootMark> lootMarks() {
        return List.copyOf(loot.values());
    }

    /** @return false when the cell was already claimed. The first claim wins. */
    public boolean claim(int cellX, int cellZ, long roomSeed, String themeId, String botId) {
        return claim(cellX, cellZ, roomSeed, themeId, botId, true);
    }

    /**
     * @param publish false while a caller still has to write the file. Publish only after that write succeeds,
     *                so a failed save does not tell other bots the cell is taken.
     */
    public boolean claim(int cellX, int cellZ, long roomSeed, String themeId, String botId, boolean publish) {
        if (claimed(cellX, cellZ)) {
            return false;
        }
        String bot = sanitize(botId);
        claims.put(key(cellX, cellZ), new Claim(cellX, cellZ, roomSeed, themeId, bot));
        if (publish) {
            bus.publishClaim(cellX, cellZ, roomSeed, themeId, bot);
        }
        return true;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return loot.containsKey(lootKey(x, y, z));
    }

    /** @return false when that block was already marked, so the container is not refilled. */
    public boolean recordLoot(LootPlanner.LootPlacement placement) {
        return recordLoot(placement, true);
    }

    public boolean recordLoot(LootPlanner.LootPlacement placement, boolean publish) {
        if (contains(placement.x(), placement.y(), placement.z())) {
            return false;
        }
        String payload = placement.payload();
        String kind = placement.kind().name().toLowerCase();
        loot.put(lootKey(placement.x(), placement.y(), placement.z()),
                new LootMark(placement.x(), placement.y(), placement.z(), kind, placement.theme().id, payload));
        if (publish) {
            bus.publishLoot(placement.x(), placement.y(), placement.z(), kind, placement.theme().id, payload);
        }
        return true;
    }

    /** Applies notices the bus has buffered. A repeat of a cell or container we already have is ignored. */
    public void pull() {
        for (ClaimNotice notice : bus.pollClaims()) {
            if (!claimed(notice.cellX(), notice.cellZ())) {
                claims.put(key(notice.cellX(), notice.cellZ()),
                        new Claim(notice.cellX(), notice.cellZ(), notice.roomSeed(), notice.themeId(), sanitize(notice.botId())));
            }
        }
        for (LootNotice notice : bus.pollLoot()) {
            String key = lootKey(notice.x(), notice.y(), notice.z());
            if (!loot.containsKey(key)) {
                loot.put(key, new LootMark(notice.x(), notice.y(), notice.z(), notice.containerKind(), notice.themeId(), notice.payload()));
            }
        }
    }

    public void save(Path path) throws IOException {
        List<Claim> cells = new ArrayList<>(claims.values());
        cells.sort(Comparator.comparingInt((Claim c) -> c.cellX).thenComparingInt(c -> c.cellZ));
        List<LootMark> marks = new ArrayList<>(loot.values());
        marks.sort(Comparator.comparingInt((LootMark m) -> m.x).thenComparingInt(m -> m.y).thenComparingInt(m -> m.z));
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (Claim claim : cells) {
            sb.append("cell ").append(claim.cellX).append(' ').append(claim.cellZ).append(' ')
                    .append(Long.toUnsignedString(claim.roomSeed)).append(' ')
                    .append(claim.themeId).append(' ').append(claim.botId).append('\n');
        }
        for (LootMark mark : marks) {
            sb.append("loot ").append(mark.x).append(' ').append(mark.y).append(' ').append(mark.z).append(' ')
                    .append(mark.kind).append(' ').append(mark.themeId).append(' ')
                    .append(mark.payload.isEmpty() ? "-" : mark.payload).append('\n');
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Windows often refuses an atomic replace with a plain IOException.
            if (Files.exists(tmp)) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw e;
            }
        }
    }

    public static MazeMap load(Path path, MazeSwarmBus bus) throws IOException {
        MazeMap map = new MazeMap(bus);
        if (path == null || !Files.exists(path)) {
            return map;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        boolean header = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!header) {
                if (!line.equals(HEADER)) {
                    throw new IOException("unsupported maze map header: " + line);
                }
                header = true;
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length == 6 && parts[0].equals("cell")) {
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                long seed = Long.parseUnsignedLong(parts[3]);
                map.claims.put(key(x, z), new Claim(x, z, seed, parts[4], parts[5]));
            } else if (parts.length == 7 && parts[0].equals("loot")) {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                String payload = parts[6].equals("-") ? "" : parts[6];
                map.loot.put(lootKey(x, y, z), new LootMark(x, y, z, parts[4], parts[5], payload));
            } else {
                throw new IOException("bad maze map line: " + line);
            }
        }
        if (!header) {
            throw new IOException("maze map is missing its header");
        }
        return map;
    }

    public static String sanitize(String botId) {
        if (botId == null || botId.isBlank()) {
            return "local";
        }
        String cleaned = botId.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "local" : cleaned;
    }

    private static String lootKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
