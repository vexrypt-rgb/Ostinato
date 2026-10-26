package baritone.training;

// Ostinato: fixed-seed circuit worlds. Layouts are authored, not a Minecraft chunk generator.

import java.util.Arrays;

/**
 * Small deterministic world a circuit resets. Seeds select a layout:
 * 71001 plains camp, 71002 hill quarry, 71003 night thicket, 71004 compass hunt.
 */
public final class TrainingWorld {

    public static final long SEED_PLAINS = 71001L;
    public static final long SEED_HILLS = 71002L;
    public static final long SEED_NIGHT = 71003L;
    public static final long SEED_COMPASS = 71004L;
    /** Ostinato: locker camp. Not part of the default suite. */
    public static final long SEED_CAMP = 71005L;

    static final int SX = 24;
    static final int SY = 6;
    static final byte AIR = 0;
    static final byte GRASS = 1;
    static final byte LOG = 2;
    static final byte STONE = 3;
    static final byte TABLE = 4;
    static final byte DEPOSIT = 5;
    static final byte WATER = 6;
    static final byte LAVA = 7;
    static final byte PIT = 8;
    static final byte LODESTONE = 9;

    public final String circuitId;
    public final long seed;
    public final String biome;
    public final double temperature;
    public final String jumpMajority;
    public final String terrain;
    public final String mobPressure;
    public final int riseBlocks;
    public final int gapBlocks;
    public final String goalItem;
    public final String craftGoal;

    private final byte[] cells = new byte[SX * SY * SX];
    private final int mobX;
    private final int mobY;
    private final int mobZ;
    private final boolean hasMob;
    private final int startX;
    private final int startY;
    private final int startZ;
    private final int startYaw;
    private final int startDirt;
    /** Ostinato: a mob beside the path knocks an unarmored bot into the pit. Stock circuits leave this false. */
    final boolean hazard;
    /** Ostinato: the camp locker can supply every catalog kit. Stock circuits leave this false. */
    final boolean locker;

    int x;
    int y;
    int z;
    int yaw;
    int logs;
    int planks;
    int sticks;
    int cobble;
    int dirt;
    int pick = -1;
    int durabilitySpent;
    int broken;
    int placed;
    int pathFailures;
    int deaths;
    String deathCause = "";
    boolean deposited;
    int ticks;
    int mineTicks = -1;
    int craftTicks = -1;
    int placeTicks = -1;
    private int mineLock;
    private int mineLeft;
    private int craftLock;
    private int craftLeft;
    private boolean knocked;
    String lastCue = "";
    /** Compass target. Negative when this circuit has no needle. */
    private int markX = -1;
    private int markZ = -1;
    /** Ostinato: set only by {@link #equip}. False keeps mine times and movement on the harness baseline. */
    boolean gearLive;
    String toolName = "HAND";
    int armorRank;

    private TrainingWorld(String circuitId, long seed, String biome, double temperature, String jumpMajority,
            String terrain, String mobPressure, int riseBlocks, int gapBlocks, String goalItem, String craftGoal,
            int startX, int startY, int startZ, int startYaw, int startDirt, int mobX, int mobY, int mobZ, boolean hasMob,
            boolean hazard, boolean locker) {
        this.circuitId = circuitId;
        this.seed = seed;
        this.biome = biome;
        this.temperature = temperature;
        this.jumpMajority = jumpMajority;
        this.terrain = terrain;
        this.mobPressure = mobPressure;
        this.riseBlocks = riseBlocks;
        this.gapBlocks = gapBlocks;
        this.goalItem = goalItem;
        this.craftGoal = craftGoal;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.startYaw = startYaw;
        this.startDirt = startDirt;
        this.mobX = mobX;
        this.mobY = mobY;
        this.mobZ = mobZ;
        this.hasMob = hasMob;
        this.hazard = hazard;
        this.locker = locker;
    }

    public static TrainingWorld create(String circuitId) {
        return switch (circuitId) {
            case "log-planks" -> plains();
            case "stone-step" -> hills();
            case "night-listen" -> night();
            case "compass-hunt" -> compass();
            case "gear-camp" -> camp();
            default -> throw new IllegalArgumentException(circuitId);
        };
    }

    public static String[] suiteIds() {
        return new String[]{"log-planks", "stone-step", "night-listen", "compass-hunt"};
    }

    /** The trainingSeed setting. Unknown text is refused. */
    public static String circuitForSeed(String raw) {
        return switch (raw == null ? "" : raw.trim()) {
            case "71001" -> "log-planks";
            case "71002" -> "stone-step";
            case "71003" -> "night-listen";
            case "71004" -> "compass-hunt";
            case "71005" -> "gear-camp";
            default -> throw new IllegalArgumentException(raw);
        };
    }

    private static TrainingWorld plains() {
        TrainingWorld w = new TrainingWorld("log-planks", SEED_PLAINS, "PLAINS", 0.8, "WALK", "FLAT", "DAY",
                0, 0, "PLANKS", "PLANKS", 4, 1, 2, 0, 0, 0, 0, 0, false, false, false);
        w.fillFloor(0, 16, 0, 16, 0);
        w.set(4, 1, 6, LOG);
        w.set(4, 1, 1, DEPOSIT);
        w.snapshot();
        w.reset();
        return w;
    }

    private static TrainingWorld hills() {
        TrainingWorld w = new TrainingWorld("stone-step", SEED_HILLS, "HILLS", 0.2, "STEP", "RISE", "DAY",
                1, 0, "COBBLE", "PICK", 2, 1, 2, 0, 8, 0, 0, 0, false, false, false);
        w.fillFloor(0, 16, 0, 7, 0);
        w.fillFloor(0, 16, 8, 14, 1);
        w.set(2, 1, 5, LOG);
        w.set(2, 1, 6, LOG);
        w.set(3, 1, 2, TABLE);
        w.set(2, 1, 1, DEPOSIT);
        w.set(2, 2, 10, STONE);
        w.snapshot();
        w.reset();
        return w;
    }

    private static TrainingWorld night() {
        TrainingWorld w = new TrainingWorld("night-listen", SEED_NIGHT, "FOREST", 0.7, "WALK", "FLAT", "NIGHT",
                0, 0, "PLANKS", "PLANKS", 5, 1, 5, 0, 0, 11, 1, 5, true, false, false);
        w.fillFloor(0, 16, 0, 16, 0);
        w.set(9, 1, 5, LOG);
        w.set(5, 1, 2, DEPOSIT);
        w.set(5, 0, 9, PIT);
        w.set(5, 1, 9, PIT);
        w.set(4, 0, 5, AIR); // void hole west of the start, for the death probe
        w.set(8, 0, 8, WATER);
        w.set(12, 0, 5, LAVA);
        w.snapshot();
        w.reset();
        return w;
    }

    /** Lodestone due south, outside the 8-block view. The player starts facing west, so the needle is the cue. */
    private static TrainingWorld compass() {
        TrainingWorld w = new TrainingWorld("compass-hunt", SEED_COMPASS, "PLAINS", 0.8, "WALK", "FLAT", "DAY",
                0, 0, "COMPASS", "COMPASS", 4, 1, 4, 1, 0, 0, 0, 0, false, false, false);
        w.fillFloor(0, 20, 0, 20, 0);
        w.markCompass(4, 16);
        w.snapshot();
        w.reset();
        return w;
    }

    /**
     * Ostinato: flat quarry with a kit locker. A zombie stands east of the path and a pit sits west of it.
     * No logs, so a pick has to come from the locker. Not in {@link #suiteIds()}.
     */
    private static TrainingWorld camp() {
        TrainingWorld w = new TrainingWorld("gear-camp", SEED_CAMP, "MESA", 2.0, "WALK", "FLAT", "NIGHT",
                0, 0, "COBBLE", "PICK", 4, 1, 4, 0, 0, 5, 1, 6, true, true, true);
        w.fillFloor(0, 16, 0, 16, 0);
        w.set(4, 1, 9, STONE);
        w.set(4, 1, 2, DEPOSIT);
        w.set(3, 0, 6, PIT);
        w.set(3, 1, 6, PIT);
        w.snapshot();
        w.reset();
        return w;
    }

    private void markCompass(int mx, int mz) {
        markX = mx;
        markZ = mz;
        set(mx, 1, mz, LODESTONE);
    }

    boolean hasNeedle() {
        return markX >= 0;
    }

    int markX() {
        return markX;
    }

    int markZ() {
        return markZ;
    }

    private byte[] snap;

    private void snapshot() {
        snap = Arrays.copyOf(cells, cells.length);
    }

    public void reset() {
        System.arraycopy(snap, 0, cells, 0, cells.length);
        x = startX;
        y = startY;
        z = startZ;
        yaw = startYaw;
        logs = planks = sticks = cobble = 0;
        dirt = startDirt;
        pick = -1;
        durabilitySpent = broken = placed = pathFailures = deaths = ticks = 0;
        mineTicks = craftTicks = placeTicks = -1;
        deathCause = "";
        deposited = false;
        mineLock = mineLeft = craftLock = craftLeft = 0;
        knocked = false;
        lastCue = "";
        gearLive = false;
        toolName = "HAND";
        armorRank = 0;
    }

    /** Tool the stock circuit already reaches with no locker. Empty when the camp must choose one. */
    String stockTool() {
        if ("COBBLE".equals(goalItem) && contains(LOG)) {
            return "WOOD_PICK";
        }
        if (locker) {
            return "";
        }
        return "HAND";
    }

    boolean hasLocker() {
        return locker;
    }

    /** Ostinato: grant a locker kit. Mine pace and knockoff stay off until this is called. */
    void equip(GearChoice.Equip equip) {
        gearLive = true;
        toolName = equip.tool;
        armorRank = GearChoice.armorRank(equip.armor);
        int granted = GearChoice.pickDurability(equip.tool);
        if (granted >= 0) {
            pick = granted;
        }
    }

    private boolean contains(byte id) {
        for (byte cell : cells) {
            if (cell == id) {
                return true;
            }
        }
        return false;
    }

    public String inventoryPressure() {
        if (dirt >= 36) {
            return "FULL";
        }
        if (dirt >= 8) {
            return "MESSY";
        }
        return "EMPTY";
    }

    public String bucketKey() {
        return biome + "|" + jumpMajority + "|" + terrain + "|" + mobPressure + "|" + inventoryPressure();
    }

    public boolean dead() {
        return deaths > 0;
    }

    public boolean success() {
        return deposited && deaths == 0;
    }

    public String deathCause() {
        return deathCause;
    }

    /** Ostinato: test probe. Moves the feet onto a cell without a give. */
    public void debugStand(int px, int py, int pz, int pyaw) {
        x = px;
        y = py;
        z = pz;
        yaw = pyaw;
    }

    public void act(String action) {
        if (dead() || deposited) {
            return;
        }
        ticks++;
        lastCue = "";
        switch (action) {
            case "TURN_LEFT" -> yaw = (yaw + 1) & 3;
            case "TURN_RIGHT" -> yaw = (yaw + 3) & 3;
            case "WAIT" -> { }
            case "WALK" -> move(false);
            case "JUMP" -> move(true);
            case "MINE" -> mine();
            case "CRAFT" -> craft();
            case "PLACE" -> place();
            default -> pathFailures++;
        }
        if (!dead() && markX >= 0 && Math.abs(x - markX) + Math.abs(z - markZ) <= 1) {
            deposited = true;
            if (placeTicks < 0) {
                placeTicks = ticks;
            }
        }
        if (!dead() && hasMob && manhattan(mobX, mobZ) <= 2) {
            addCue("MOB_HURT@" + bearing(mobX - x, mobZ - z));
        }
    }

    /** Walk one block because a mob hit the player toward {@code faceYaw}. */
    public void actKnock(int faceYaw) {
        yaw = faceYaw;
        knocked = true;
        act("WALK");
    }

    private void move(boolean jump) {
        // Ostinato: unarmored locker kit beside the camp mob is shoved into the pit. Stock circuits never enter.
        if (gearLive && hazard && armorRank == 0 && hasMob && manhattan(mobX, mobZ) <= 1) {
            knocked = true;
            int sx = x + Integer.signum(x - mobX);
            int sz = z + Integer.signum(z - mobZ);
            if (sx == x && sz == z) {
                sz = z + 1;
            }
            if (at(sx, y, sz) == PIT || at(sx, y - 1, sz) == PIT || !floorAt(sx, y, sz)) {
                die("KNOCKOFF");
                return;
            }
            x = sx;
            z = sz;
            addCue("MOB_HURT@" + bearing(mobX - x, mobZ - z));
            return;
        }
        int nx = x + fx(yaw);
        int nz = z + fz(yaw);
        if (jump && solid(nx, y, nz) && !solid(nx, y + 1, nz)) {
            x = nx;
            y = y + 1;
            z = nz;
            addCue("FOOTSTEP@SELF");
            knocked = false;
            return;
        }
        if (at(nx, y, nz) == PIT || at(nx, y - 1, nz) == PIT) {
            die(knocked ? "KNOCKOFF" : "FALL");
            return;
        }
        if (solid(nx, y, nz)) {
            pathFailures++;
            knocked = false;
            return;
        }
        int ny = y;
        if (!floorAt(nx, ny, nz)) {
            if (floorAt(nx, ny - 1, nz)) {
                ny = ny - 1;
            } else {
                die(knocked ? "KNOCKOFF" : "VOID");
                return;
            }
        }
        if (y - ny >= 4) {
            die(knocked ? "KNOCKOFF" : "FALL");
            return;
        }
        x = nx;
        y = ny;
        z = nz;
        addCue("FOOTSTEP@SELF");
        knocked = false;
    }

    private void mine() {
        int nx = x + fx(yaw);
        int nz = z + fz(yaw);
        byte b = at(nx, y, nz);
        if (b != LOG && b != STONE) {
            pathFailures++;
            mineLock = 0;
            return;
        }
        if (b == STONE && pick < 0) {
            pathFailures++;
            mineLock = 0;
            return;
        }
        if (mineLock != b) {
            mineLock = b;
            mineLeft = b == LOG ? 8 : 6;
            // Ostinato: a locker tool can mine faster. The stock assignment above is what gearLive false keeps.
            if (gearLive) {
                int paced = GearChoice.breakTicks(toolName, b);
                if (paced < 0) {
                    pathFailures++;
                    mineLock = 0;
                    return;
                }
                mineLeft = paced;
            }
        }
        mineLeft--;
        if (mineLeft > 0) {
            return;
        }
        set(nx, y, nz, AIR);
        broken++;
        mineLock = 0;
        if (b == LOG) {
            logs++;
            if (mineTicks < 0) {
                mineTicks = ticks;
            }
        } else {
            cobble++;
            pick--;
            durabilitySpent++;
        }
        addCue("BLOCK_BREAK@SELF");
    }

    private void craft() {
        int kind = chooseCraft();
        if (kind == 0) {
            pathFailures++;
            craftLock = 0;
            return;
        }
        if (craftLock != kind) {
            craftLock = kind;
            craftLeft = kind == 3 ? 6 : kind == 2 ? 2 : 4;
        }
        craftLeft--;
        if (craftLeft > 0) {
            return;
        }
        if (kind == 1) {
            logs--;
            planks += 4;
            if ("PLANKS".equals(craftGoal) && craftTicks < 0) {
                craftTicks = ticks;
            }
        } else if (kind == 2) {
            planks -= 2;
            sticks += 4;
        } else {
            planks -= 3;
            sticks -= 2;
            pick = 59;
            if ("PICK".equals(craftGoal)) {
                craftTicks = ticks;
            }
        }
        craftLock = 0;
    }

    /** 1 planks, 2 sticks, 3 pick, 0 nothing legal. */
    private int chooseCraft() {
        if (pick < 0 && planks >= 3 && sticks >= 2 && orthogonal(TABLE)) {
            return 3;
        }
        if (sticks < 2 && planks >= 2) {
            return 2;
        }
        if (logs >= 1 && planks < 5) {
            return 1;
        }
        return 0;
    }

    private void place() {
        int nx = x + fx(yaw);
        int nz = z + fz(yaw);
        if (at(nx, y, nz) != DEPOSIT) {
            pathFailures++;
            return;
        }
        if ("PLANKS".equals(goalItem) && planks > 0) {
            planks--;
        } else if ("COBBLE".equals(goalItem) && cobble > 0) {
            cobble--;
        } else {
            pathFailures++;
            return;
        }
        deposited = true;
        placed++;
        placeTicks = ticks;
    }

    private boolean orthogonal(byte block) {
        for (int face = 0; face < 4; face++) {
            if (at(x + fx(face), y, z + fz(face)) == block) {
                return true;
            }
        }
        return false;
    }

    private void die(String cause) {
        deaths++;
        deathCause = cause;
        x = startX;
        y = startY;
        z = startZ;
    }

    boolean mining() {
        return mineLock != 0;
    }

    boolean hasMob() {
        return hasMob;
    }

    int mobX() { return mobX; }
    int mobY() { return mobY; }
    int mobZ() { return mobZ; }

    byte at(int px, int py, int pz) {
        if (px < 0 || py < 0 || pz < 0 || px >= SX || py >= SY || pz >= SX) {
            return AIR;
        }
        return cells[px + SX * (py + SY * pz)];
    }

    private void set(int px, int py, int pz, byte id) {
        cells[px + SX * (py + SY * pz)] = id;
    }

    private boolean solid(int px, int py, int pz) {
        byte id = at(px, py, pz);
        return id == GRASS || id == LOG || id == STONE || id == TABLE || id == DEPOSIT || id == LODESTONE;
    }

    private boolean floorAt(int px, int feetY, int pz) {
        byte below = at(px, feetY - 1, pz);
        return below == GRASS || below == LOG || below == STONE || below == TABLE || below == DEPOSIT;
    }

    private void fillFloor(int x0, int x1, int z0, int z1, int floorY) {
        for (int ix = x0; ix < x1; ix++) {
            for (int iz = z0; iz <= z1; iz++) {
                set(ix, floorY, iz, GRASS);
            }
        }
    }

    private static int fx(int face) {
        return face == 1 ? -1 : face == 3 ? 1 : 0;
    }

    private static int fz(int face) {
        return face == 0 ? 1 : face == 2 ? -1 : 0;
    }

    private int manhattan(int px, int pz) {
        return Math.abs(px - x) + Math.abs(pz - z);
    }

    static String bearing(int dx, int dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "E" : "W";
        }
        if (dz > 0) {
            return "S";
        }
        if (dz < 0) {
            return "N";
        }
        return "SELF";
    }

    static String nameOf(byte id) {
        return switch (id) {
            case LOG -> "LOG";
            case STONE -> "STONE";
            case TABLE -> "TABLE";
            case DEPOSIT -> "DEPOSIT";
            case WATER -> "WATER";
            case LAVA -> "LAVA";
            case PIT -> "PIT";
            case GRASS -> "GRASS";
            case LODESTONE -> "LODESTONE";
            default -> "AIR";
        };
    }

    private void addCue(String cue) {
        if (lastCue.isEmpty()) {
            lastCue = cue;
        } else if (!lastCue.contains(cue)) {
            lastCue = lastCue + ";" + cue;
        }
    }

}
