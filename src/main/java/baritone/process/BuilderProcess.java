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

package baritone.process;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.TerminalCostGoal;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.schematic.partition.PartitionPlan;
import baritone.api.schematic.partition.RegionPart;
import baritone.api.schematic.partition.SchematicCells;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.cadenza.Cadenza;
import baritone.pathing.cadenza.CadenzaGoal;
import baritone.pathing.cadenza.CadenzaSession;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.ToolSet;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    /** Ostinato Cadenza: positioning memo and the placement in progress (used only with the cadenza setting). */
    private final CadenzaSession cadenza = new CadenzaSession();

    /**
     * Ostinato Cadenza v2 instrumentation (read by the live A/B only, changes nothing): what the last tick did about
     * placing. NONE: nothing placeable from here; BLOCKED: placeable but not now (not safe to cancel, airborne, the
     * post-break delay, interaction paused); ALIGN: turning toward it; CLICK: right-clicking; BREAK: breaking instead.
     */
    public enum PlacePhase { OTHER, BREAK, NONE, BLOCKED, ALIGN, CLICK }

    public static volatile PlacePhase lastPlacePhase = PlacePhase.OTHER;
    /** Degrees of yaw and pitch still to turn to the placement in view, as of the last tick that had one. */
    public static volatile float lastPlaceYawErr, lastPlacePitchErr;
    /** Why the last BLOCKED tick could not place: 1 not safe to cancel, 2 airborne, 4 post-break delay, 8 paused. */
    public static volatile int lastPlaceBlocked;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    private final Map<BlockState, Integer> protectedItems = new HashMap<>();
    private final Function<Map.Entry<Property<?>, Comparable<?>>, PropertyContainer> PROPERTY_ENTRY_TO_STRING_FUNCTION = new Function<>() {
        public PropertyContainer apply(Map.Entry<Property<?>, Comparable<?>> entry) {
            if (entry == null) {
                return null;
            } else {
                Property<?> property = (Property) entry.getKey();
                String var10000 = property.getName();
                final PropertyContainer propertyContainer = new PropertyContainer(var10000, this.getName(property, (Comparable) entry.getValue()));
                return propertyContainer;
                //return var10000 + "=" + this.getName(property, (Comparable) entry.getValue());
            }
        }

        private <T extends Comparable<T>> String getName(Property<T> property, Comparable<?> comparable) {
            return property.getName((T) comparable);
        }
    };
    private boolean fromAltoclef;
    private Map<BlockState, Integer> missing;
    private boolean active;
    private Stack<Object> stateStack = new Stack<>();

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    private Vec3i schemSize;
    private boolean fromAltoclefFinished;
    private Map<BlockPos, HistoryInfo> blockBreakHistory = new HashMap<>();
    private RegionGuard regionGuard; // set by buildRegion when buildRegionProtectForeign is on, else null

    private void pushState() {
        stateStack.clear();
        stateStack.push(this.approxPlaceable);
        stateStack.push(this.ticks);
        stateStack.push(this.incorrectPositions);
        stateStack.push(this.name);
        stateStack.push(this.schematic);
        stateStack.push(this.realSchematic);
        stateStack.push(this.origin);
        stateStack.push(this.paused);
        stateStack.push(this.layer);
        stateStack.push(this.numRepeats);
        stateStack.push(this.observedCompleted);
        stateStack.push(this.active);
        stateStack.push(this.missing);
        stateStack.push(this.schemSize);
        stateStack.push(this.fromAltoclefFinished);
        stateStack.push(this.fromAltoclef);
        stateStack.push(this.regionGuard);
    }

    @Override
    public double priority() {
        return super.priority();
    }

    @Override
    public String displayName() {
        return super.displayName();
    }


    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.regionGuard = null; // a plain build is not restricted; buildRegion sets this after calling build
        //Shouldn't get initially called
        if (this.fromAltoclef && this.stateStack.isEmpty()) {
            pushState();
        }
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        this.schematic = orient(this.schematic);
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        this.active = true;
        if (this.missing != null) {
            this.missing.clear();
        } else {
            missing = new HashMap<>();
        }
        this.schemSize = new Vec3i(schematic.widthX(), schematic.heightY(), schematic.lengthZ());
        this.fromAltoclefFinished = false;
        this.fromAltoclef = false;
    }

    private void protectItemOfMissing() {
        if (missing != null) {
            protectedItems.putAll(missing);
            protectedItems.keySet().forEach(e -> {
                if (!AltoClefSettings.getInstance().isItemProtected(e.getBlock().asItem())) {
                    AltoClefSettings.getInstance().protectItem(e.getBlock().asItem());
                }
            });
        }
    }

    @Override
    public void popStack() {
        if (this.stateStack.isEmpty()) {
            logDebug("ERROR in BuildProcess: No state present to pop");
            return;
        }
        this.regionGuard = (RegionGuard) stateStack.pop();
        this.fromAltoclef = (boolean) stateStack.pop();
        this.fromAltoclefFinished = (boolean) stateStack.pop();
        this.schemSize = (Vec3i) stateStack.pop();
        this.missing = (Map<BlockState, Integer>) stateStack.pop();
        this.active = (boolean) stateStack.pop();
        this.observedCompleted = (LongOpenHashSet) stateStack.pop();
        this.numRepeats = (int) stateStack.pop();
        this.layer = (int) stateStack.pop();
        this.paused = (boolean) stateStack.pop();
        this.origin = (Vec3i) stateStack.pop();
        this.realSchematic = (ISchematic) stateStack.pop();
        this.schematic = (ISchematic) stateStack.pop();
        this.name = (String) stateStack.pop();
        this.incorrectPositions = (HashSet<BetterBlockPos>) stateStack.pop();
        this.ticks = (int) stateStack.pop();
        this.approxPlaceable = (List<BlockState>) stateStack.pop();
        pushState();
        if (!stateStack.isEmpty()) {
            logDebug("ERROR: state stack was not empty after state restoration. Will throw away the rest for now.");
            stateStack.clear();
        }
    }

    @Override
    public boolean isFromAltoclefFinished() {
        return this.fromAltoclefFinished;
    }

    private void stopProtectItemOfMissing() {
        if (protectedItems != null && !protectedItems.isEmpty()) {
            protectedItems.keySet().forEach(e -> {
                if (AltoClefSettings.getInstance().isItemProtected(e.getBlock().asItem())) {
                    AltoClefSettings.getInstance().stopProtectingItem(e.getBlock().asItem());
                }
            });
        }
        assert protectedItems != null;
        protectedItems.clear();
    }

    public void resume() {
        if (!this.stateStack.isEmpty()) {
            popStack();
        }

        this.paused = false;
        this.active = true;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    /** buildSchematicMirror then buildSchematicRotation, exactly as {@link #build(String, ISchematic, Vec3i)} applies them. */
    private static ISchematic orient(ISchematic schematic) {
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            schematic = new MirroredSchematic(schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            schematic = new RotatedSchematic(schematic, Baritone.settings().buildSchematicRotation.value);
        }
        return schematic;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        ISchematic parsed = load(schematic, origin);
        if (parsed == null) {
            return false;
        }
        build(name, parsed, origin);
        return true;
    }

    /** Parses a schematic file and applies mapArtMode / buildOnlySelection. Null if it can't be read. */
    private ISchematic load(File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return null;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return applyMapArtAndSelection(origin, parsed);
    }

    @Override
    public void buildRegion(String name, ISchematic schematic, Vec3i origin, int regionIndex, PartitionPlan plan, RegionPart part) {
        SchematicCells.requireFits(schematic, plan);
        build(name, MaskSchematic.create(schematic, plan.regionMask(regionIndex, part)), origin);
        if (Baritone.settings().buildRegionProtectForeign.value) {
            // the whole region (seams included) is ours even when only building one part of it
            this.regionGuard = new RegionGuard(plan, regionIndex, this.origin);
        }
    }

    @Override
    public boolean buildRegion(String name, File schematic, Vec3i origin, int regionIndex, PartitionPlan plan) {
        ISchematic parsed = load(schematic, origin);
        if (parsed == null) {
            return false;
        }
        buildRegion(name, parsed, origin, regionIndex, plan, RegionPart.WHOLE);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Pair<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().first();
                BlockPos origin = schematic.get().second();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Pair<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.second();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.first());
                build(schematic.first().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    // Ostinato: stall breaker. With breakFromAbove the block under the feet was always skipped, so a survival dig that ends
    // standing on the last incorrect interior block (nothing else in reach, every GoalBreak already "reached") softlocked.
    // After the feet stay put for STALL_TICKS with nothing to break, allow that block too.
    private static final int STALL_TICKS = 100;
    private boolean digUnderFeet;
    private int stallTicks;
    private BetterBlockPos stallFeet;
    public String stallDebug = "";

    private boolean shafting;

    // Ostinato: run107. Run106 room 6 sat 10 min at -249,117,-279 in its own 1-wide entry hole: toBreak flickered between
    // an interior block "reachable" only through the hole (the aim swung between the hole's sides, never clicked) and a
    // path whose ascend into the hole never moved. Every found target reset stallTicks, so the breakers below never ran.
    // Progress is the feet moving or the incorrect count dropping; without it for NO_PROGRESS_TICKS the flickering target
    // is dropped and the breakers run as if nothing were reachable.
    private static final int NO_PROGRESS_TICKS = 300;
    // Ostinato: run141. Feet shuffled exactly 3 blocks (distSqr 9) between two stands, which cleared the dropped
    // set and reset the stall timer, so the same occluded cell was re-aimed for 20 min. Five blocks is travel.
    private static final int STALL_MOVE_SQR = 25;
    private int noProgressTicks;
    private int progressIncorrect = -1;
    private BetterBlockPos progressFeet;
    /** Targets the watchdog dropped from these feet; skipped until the feet move, so the flicker does not come back. */
    private final Set<BetterBlockPos> droppedTargets = new HashSet<>();
    private BetterBlockPos droppedFeet;
    // Ostinato: run141. A reachable block was dropped after 15s of not walking, then the approach reported inReach
    // and walked away before the crack finished. Hold left-click on this block until it breaks or the budget ends.
    private BetterBlockPos commitBreak;
    private int commitTicks;

    private Optional<Pair<BetterBlockPos, Rotation>> toBreakNearPlayerWithStall(BuilderCalculationContext bcc) {
        digUnderFeet = false;
        shafting = false;
        BetterBlockPos feet = ctx.playerFeet();
        // Ostinato: run113 room5 shuffled 72-75,79,124 for 15+ min; a 1-block step cleared the dropped set so the same
        // blocked targets came straight back. Keep them until real progress (incorrect drops) or the feet move >=3 away.
        int incNow = incorrectPositions == null ? -1 : incorrectPositions.size();
        if (droppedFeet == null || feet.distSqr(droppedFeet) >= STALL_MOVE_SQR || (progressIncorrect >= 0 && incNow < progressIncorrect)) {
            droppedTargets.clear();
            droppedFeet = feet;
        }
        Optional<Pair<BetterBlockPos, Rotation>> committed = committedSwing(bcc, feet);
        if (committed != null) return committed;
        Optional<Pair<BetterBlockPos, Rotation>> r = toBreakNearPlayer(bcc);
        int incorrect = incorrectPositions == null ? -1 : incorrectPositions.size();
        // Ostinato: run113, 1-block shuffles counted as progress. run141: a 3-block stand shuffle did too.
        if (progressFeet == null || feet.distSqr(progressFeet) >= STALL_MOVE_SQR || incorrect < progressIncorrect) {
            noProgressTicks = 0;
            progressFeet = feet;
        } else {
            noProgressTicks++;
        }
        progressIncorrect = incorrect;
        boolean noProgress = noProgressTicks > NO_PROGRESS_TICKS;
        if (noProgress && r.isPresent() && commitBreak == null) {
            // Ostinato: run141. This block is already in reach. Mine it; dropping it made the breaker aim through it.
            commitBreak = r.get().first();
            commitTicks = 0;
            System.out.println("MAZETEST dig stall breaker: no progress for " + noProgressTicks + " ticks, mining " + commitBreak + " feet=" + feet + " incorrect=" + incorrect);
            return committedSwing(bcc, feet);
        }
        if (r.isPresent() || !feet.equals(stallFeet)) {
            stallTicks = 0;
            stallFeet = feet;
        } else {
            stallTicks++;
            if (stallTicks > STALL_TICKS && Baritone.settings().breakFromAbove.value && !noProgress) {
                digUnderFeet = true;
                r = toBreakNearPlayer(bcc);
                digUnderFeet = false;
                if (r.isPresent() && (stallTicks == STALL_TICKS + 1 || stallTicks % 100 == 0)) System.out.println("MAZETEST dig stall breaker: under-feet at " + r.get().first() + " feet=" + feet + " stall=" + stallTicks);
            }
            if (stallTicks > STALL_TICKS && Baritone.settings().breakFromAbove.value) {
                // Ostinato: run113. Aim-nearest kept swinging at blocks 3-4 away behind other blocks; the shaft fallback
                // needed the target >=4 up or >=6 away so it never ran. After FORCE_SHAFT_TICKS without progress, tunnel
                // toward the nearest incorrect block first, whatever the geometry.
                if (!r.isPresent() && noProgressTicks > FORCE_SHAFT_TICKS) {
                    r = mineShaftToward(bcc, feet, true);
                    shafting = r.isPresent();
                }
                if (!r.isPresent() && stallTicks > 2 * STALL_TICKS && incorrectPositions != null) {
                    // Ostinato: still nothing "reachable" (seen live: adjacent feet-level interior blocks never qualified).
                    // Aim at the nearest incorrect block within reach and swing; whatever the ray hits first is in the way anyway.
                    double reach = ctx.playerController().getBlockReachDistance();
                    BetterBlockPos best = null;
                    double bestD = Double.MAX_VALUE;
                    for (BetterBlockPos p : incorrectPositions) {
                        if (bcc.bsi.get0(p.x, p.y, p.z).getBlock() instanceof AirBlock) continue;
                        if (droppedTargets.contains(p)) continue;
                        double d = ctx.playerHead().distanceToSqr(p.x + 0.5, p.y + 0.5, p.z + 0.5);
                        if (d < bestD && d <= reach * reach) { bestD = d; best = p; }
                    }
                    if (best != null) {
                        if (best.equals(aimTarget)) aimTicks++; else { aimTarget = best; aimTicks = 0; }
                        int aimBudget = Math.max(AIM_GIVEUP_TICKS, breakBudget(bcc.bsi.get0(best.x, best.y, best.z)));
                        if (aimTicks > aimBudget) {
                            System.out.println("MAZETEST dig stall breaker: aim gave up on " + best + " feet=" + feet);
                            droppedTargets.add(best);
                            aimTarget = null;
                            // Ostinato: run119 room2. Feet sat at -46,94,-15 for 20+ min cycling aim/give-up over targets 3-4
                            // blocks away behind other blocks: the breaker cancels pathing every tick, so it never walks closer.
                            // Hand off to a Baritone GoalNear next to the dig face so the next swing comes from a new stand.
                            approachTarget = best;
                            approachTicks = 0;
                        }
                        Rotation aimRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(best.x + 0.5, best.y + 0.5, best.z + 0.5), ctx.playerRotations());
                        // Ostinato: run116 long_hall stood 19 min aiming at blocks 2 away: the click only fired on-ground/safe-to-cancel
                        // and the ray hit a different block. Retarget to whatever the ray hits (it is in the way) and force the swing.
                        BetterBlockPos aimPos = best;
                        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), aimRot, reach, false);
                        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                            BetterBlockPos hp = new BetterBlockPos(bhr.getBlockPos());
                            Block hb = bcc.bsi.get0(hp.x, hp.y, hp.z).getBlock();
                            BlockState hs = bcc.bsi.get0(hp.x, hp.y, hp.z);
                            BlockState hDesired = bcc.getSchematic(hp.x, hp.y, hp.z, hs);
                            if (!hp.equals(best) && hDesired != null && valid(hs, hDesired, false)) {
                                // Ostinato: run118 room4 long_hall. The ray hit the finished z=9 wall; breaking it raised incorrect
                                // 94->111, the builder replaced it, and the breaker broke it again for 10+ min. A correct schematic
                                // block in the way means best is not reachable from here: drop it, never break built work.
                                if (stallTicks % 100 == 0) System.out.println("MAZETEST dig stall breaker: ray hits built " + hp + ", dropping " + best);
                                droppedTargets.add(best);
                                aimTarget = null;
                                aimPos = null;
                                approachTarget = best; // Ostinato: run119, reach it from another side instead
                                approachTicks = 0;
                            } else if (!hp.equals(best) && !(hb instanceof AirBlock) && hb != Blocks.BEDROCK && hp.y >= feet.y) {
                                // Ostinato: run141. The swing was aimed at best while the ray hit hp, so the crack
                                // alternated and neither block broke. Commit to the block the crosshair actually hits.
                                commitBreak = hp;
                                commitTicks = 0;
                                System.out.println("MAZETEST dig stall breaker: commit-occluder " + hp + " toward " + best + " feet=" + feet);
                                return committedSwing(bcc, feet);
                            }
                        }
                        if (aimPos != null) {
                            r = Optional.of(new Pair<>(aimPos, aimRot));
                            shafting = true;
                        }
                        if (aimPos != null && stallTicks == 2 * STALL_TICKS + 1 || stallTicks % 100 == 0) System.out.println("MAZETEST dig stall breaker: aimed at nearest " + best + " feet=" + feet + " stall=" + stallTicks);
                    }
                }
            }
            // Ostinato: run45 stood in a 1-high crawl under the room (feet y=42, shell y=80) while every
            // search to the GoalBreak composite failed (P01). The dirt ceiling is not a schematic block, so
            // the old breaker never swung. Mine the first solid toward the nearest incorrect block.
            if (r.isEmpty() && stallTicks > STALL_TICKS) {
                r = mineShaftToward(bcc, feet, false);
                shafting = r.isPresent();
            }
        }
        stallDebug = "toBreak=" + r.map(p -> p.first().toString()).orElse("none") + " stall=" + stallTicks;
        return r;
    }

    /**
     * Ostinato: run141. Swing at {@link #commitBreak} until it is gone or the break budget runs out.
     * Null when nothing is committed (caller keeps going). A present value is the swing for this tick.
     */
    private Optional<Pair<BetterBlockPos, Rotation>> committedSwing(BuilderCalculationContext bcc, BetterBlockPos feet) {
        if (commitBreak == null) return null;
        BetterBlockPos t = commitBreak;
        BlockState st = bcc.bsi.get0(t.x, t.y, t.z);
        BlockState desired = bcc.getSchematic(t.x, t.y, t.z, st);
        // A cell with no schematic entry is an occluder (the ray hit it on the way to the room). It still has to go.
        boolean matchesPlan = desired != null && valid(st, desired, false);
        boolean done = st.getBlock() instanceof AirBlock || st.getBlock() == Blocks.WATER || st.getBlock() == Blocks.LAVA
                || matchesPlan || st.getBlock() == Blocks.BEDROCK;
        if (done) {
            commitBreak = null;
            commitTicks = 0;
            return null;
        }
        int budget = breakBudget(st);
        if (++commitTicks > budget) {
            System.out.println("MAZETEST dig stall breaker: commit-giveup " + t + " ticks=" + commitTicks + " budget=" + budget + " feet=" + feet);
            droppedTargets.add(t);
            falseReachCounts.merge(t, 1, Integer::sum);
            commitBreak = null;
            commitTicks = 0;
            aimTarget = null;
            aimTicks = 0;
            return null;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Optional<Rotation> rot = RotationUtils.reachable(ctx, t, reach);
        if (rot.isEmpty()) {
            rot = Optional.of(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(t.x + 0.5, t.y + 0.5, t.z + 0.5), ctx.playerRotations()));
        }
        shafting = true;
        if (commitTicks == 1 || commitTicks % 40 == 0) {
            System.out.println("MAZETEST dig stall breaker: commit-break " + t + " tick=" + commitTicks + "/" + budget + " feet=" + feet);
        }
        return Optional.of(new Pair<>(t, rot.get()));
    }

    /** Ticks to keep a stall swing going, from the best hotbar tool. Long enough that a bare hand finishes stone. */
    private int breakBudget(BlockState st) {
        double perTick = st == null ? 0 : new ToolSet(ctx.player()).getStrVsBlock(st);
        return DigStallMath.budgetTicks(perTick);
    }

    /** First solid block toward the nearest incorrect schematic block, when that block is out of the 5-block window. */
    private static final int FORCE_SHAFT_TICKS = 900;
    private static final int AIM_GIVEUP_TICKS = 120;
    private BetterBlockPos aimTarget;
    private int aimTicks;

    private static final int APPROACH_MAX_TICKS = 600;
    private BetterBlockPos approachTarget;
    private int approachTicks;
    // Ostinato: run136. Stage 0 = GoalNear(t,2); stage 1 = GoalGetToBlock(t) (stand touching the block, so a face is visible).
    private int approachStage;
    private int approachArrivedTicks;
    private final java.util.Map<BetterBlockPos, Integer> falseReachCounts = new java.util.HashMap<>(); // Ostinato: run137
    private int assembleNullRetries;

    // Ostinato: run119. Path next to an incorrect block the aim fallback gave up on, instead of re-aiming from the same spot.
    private PathingCommand approachDigFace(BuilderCalculationContext bcc, boolean calcFailed) {
        if (approachTarget == null) return null;
        BetterBlockPos t = approachTarget;
        BlockState st = bcc.bsi.get0(t.x, t.y, t.z);
        BlockState desired = bcc.getSchematic(t.x, t.y, t.z, st);
        double reach = ctx.playerController().getBlockReachDistance();
        boolean done = st.getBlock() instanceof AirBlock || desired == null || valid(st, desired, false);
        boolean inReach = ctx.playerHead().distanceToSqr(t.x + 0.5, t.y + 0.5, t.z + 0.5) <= (reach - 1) * (reach - 1)
                && RotationUtils.reachable(ctx, t, reach).isPresent();
        // Ostinato: run137 lobby ceiling. Feet -51,86,-18 vs target -50,89,-18: distance+reachable() said inReach, approach ended,
        // aim gave up, re-approach said inReach again - forever, never pillared. Distance is not a break. Reject inReach when the
        // cell is >=4 above feet, or when this target already "ended inReach" once without being broken (and is >=2 above feet).
        int dyFeet = t.y - ctx.playerFeet().y;
        if (inReach && approachStage < 2 && (dyFeet >= 4 || dyFeet >= 2 && falseReachCounts.getOrDefault(t, 0) >= 1)) {
            System.out.println("MAZETEST dig stall breaker: approach inReach rejected (false-positive) target=" + t + " feet=" + ctx.playerFeet() + " dy=" + dyFeet + " prevInReachEnds=" + falseReachCounts.getOrDefault(t, 0));
            inReach = false;
            approachStage = 2; approachTicks = 0; approachArrivedTicks = 0;
            System.out.println("MAZETEST dig stall breaker: approach escalate pillar-for-ceiling target=" + t + " feet=" + ctx.playerFeet() + " stand=" + new BetterBlockPos(t.x, t.y - 2, t.z));
        }
        // Ostinato: run137. Stage 2 = GoalBlock right under the cell (feet t.y-2, head touching it); Baritone pillars/scaffolds up to it.
        Goal approachGoal = approachStage == 0 ? new GoalNear(t, 2) : approachStage == 1 ? new GoalGetToBlock(t) : new GoalBlock(t.x, t.y - 2, t.z);
        if (approachStage == 2) inReach = inReach && t.y - ctx.playerFeet().y <= 2; // Ostinato: only the real stand counts
        // Ostinato: run136. Feet -32,77,-65 sat inside GoalNear(-33,78,-64, 2) for the whole 600-tick budget: the goal was
        // already reached, the target's faces were occluded, nothing moved. Arrived without a face = this stand is useless.
        boolean arrived = approachGoal.isInGoal(ctx.playerFeet());
        approachArrivedTicks = arrived ? approachArrivedTicks + 1 : 0;
        boolean dead = calcFailed || approachArrivedTicks > 40;
        if (done || inReach || dead || ++approachTicks > APPROACH_MAX_TICKS) {
            System.out.println("MAZETEST dig stall breaker: approach " + t + " ended done=" + done + " inReach=" + inReach + " calcFailed=" + calcFailed + " arrived=" + arrived + " stage=" + approachStage + " ticks=" + approachTicks + " feet=" + ctx.playerFeet());
            if (done) falseReachCounts.remove(t);
            if (!done && inReach) falseReachCounts.merge(t, 1, Integer::sum); // Ostinato: run137 count inReach ends
            if (!done && !inReach && approachStage == 1 && t.y - ctx.playerFeet().y >= 3) {
                // Ostinato: run137. Get-to-block failed on a ceiling cell: stand under it (pillar) before forcing a shaft.
                approachStage = 2; approachTicks = 0; approachArrivedTicks = 0;
                System.out.println("MAZETEST dig stall breaker: approach escalate pillar-for-ceiling target=" + t + " feet=" + ctx.playerFeet() + " stand=" + new BetterBlockPos(t.x, t.y - 2, t.z));
                return new PathingCommand(new GoalBlock(t.x, t.y - 2, t.z), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            if (!done && !inReach && approachStage == 0) {
                // Ostinato: run136. Escalate to standing right against the block instead of giving up.
                approachStage = 1; approachTicks = 0; approachArrivedTicks = 0;
                System.out.println("MAZETEST dig stall breaker: approach escalate get-to-block " + t);
                return new PathingCommand(new GoalGetToBlock(t), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            approachTarget = null;
            approachStage = 0; approachArrivedTicks = 0;
            stallTicks = 0;
            if (!done && !inReach) {
                // Ostinato: run136. Both stands failed: tunnel toward it (forced shaft) on the next stall instead of idling.
                droppedTargets.remove(t);
                noProgressTicks = FORCE_SHAFT_TICKS + 1;
                stallTicks = STALL_TICKS + 1;
                stallFeet = ctx.playerFeet();
                System.out.println("MAZETEST dig stall breaker: approach failed, forcing shaft toward " + t);
            } else if (inReach && !done) {
                // Ostinato: run141. inReach used to reset the stall timer and walk off. The block is still there:
                // mine it. After two of those, the face is a lie (ray blocked) — shaft past it instead of looping.
                if (falseReachCounts.getOrDefault(t, 0) >= 3) {
                    droppedTargets.add(t);
                    commitBreak = null;
                    commitTicks = 0;
                    noProgressTicks = FORCE_SHAFT_TICKS + 1;
                    stallTicks = STALL_TICKS + 1;
                    stallFeet = ctx.playerFeet();
                    System.out.println("MAZETEST dig stall breaker: in-reach gave up, shafting past " + t + " feet=" + ctx.playerFeet());
                } else {
                    commitBreak = t;
                    commitTicks = 0;
                    System.out.println("MAZETEST dig stall breaker: approach in-reach, mining " + t + " feet=" + ctx.playerFeet());
                }
            } else {
                noProgressTicks = 0;
            }
            return null;
        }
        if (approachTicks == 1 || approachTicks % 100 == 0) System.out.println("MAZETEST dig stall breaker: approaching " + t + " stage=" + approachStage + " feet=" + ctx.playerFeet() + " ticks=" + approachTicks);
        return new PathingCommand(approachGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    private Optional<Pair<BetterBlockPos, Rotation>> mineShaftToward(BuilderCalculationContext bcc, BetterBlockPos feet, boolean force) {
        if (incorrectPositions == null || incorrectPositions.isEmpty()) return Optional.empty();
        BetterBlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BetterBlockPos p : incorrectPositions) {
            if (bcc.bsi.get0(p.x, p.y, p.z).getBlock() instanceof AirBlock) continue;
            double d = feet.distSqr(p);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best == null) return Optional.empty();
        int dyGoal = best.y - feet.y;
        if (!force && dyGoal < 4 && bestD < 36) return Optional.empty();
        int sx = Integer.signum(best.x - feet.x);
        int sz = Integer.signum(best.z - feet.z);
        BetterBlockPos[] candidates = dyGoal >= 4
                ? new BetterBlockPos[] {
                    new BetterBlockPos(feet.x, feet.y + 1, feet.z),
                    new BetterBlockPos(feet.x, feet.y + 2, feet.z),
                    new BetterBlockPos(feet.x + sx, feet.y + 1, feet.z + sz),
                    new BetterBlockPos(feet.x, feet.y + 3, feet.z),
                    new BetterBlockPos(feet.x + sx, feet.y + 2, feet.z + sz) }
                : new BetterBlockPos[] {
                    new BetterBlockPos(feet.x + sx, feet.y + 1, feet.z + sz),
                    new BetterBlockPos(feet.x + sx, feet.y, feet.z + sz),
                    new BetterBlockPos(feet.x + sx, feet.y + 2, feet.z + sz),
                    new BetterBlockPos(feet.x + sx, feet.y + 1, feet.z),
                    new BetterBlockPos(feet.x, feet.y + 1, feet.z + sz),
                    new BetterBlockPos(feet.x, feet.y + 2, feet.z) };
        double reach = ctx.playerController().getBlockReachDistance();
        for (BetterBlockPos p : candidates) {
            if (p.y < feet.y) continue;
            BlockState st = bcc.bsi.get0(p.x, p.y, p.z);
            Block b = st.getBlock();
            if (b instanceof AirBlock || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.BEDROCK) continue;
            if (st.getCollisionShape(ctx.world(), p).isEmpty()) continue;
            BlockState pDesired = bcc.getSchematic(p.x, p.y, p.z, st);
            if (pDesired != null && valid(st, pDesired, false)) continue; // Ostinato: run118, never shaft through built work
            Optional<Rotation> rot = RotationUtils.reachable(ctx, p, reach);
            // A 1-high crawl puts the eye inside the ceiling block, so the strict reach test misses the dirt we are looking at.
            if (rot.isEmpty() && p.x == feet.x && p.z == feet.z) {
                rot = Optional.of(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5), ctx.playerRotations()));
            }
            if (rot.isPresent()) {
                if (stallTicks == STALL_TICKS + 1 || stallTicks % 100 == 0) {
                    System.out.println("MAZETEST dig stall breaker: shaft " + p + " toward " + best + " feet=" + feet + " stall=" + stallTicks);
                }
                return Optional.of(new Pair<>(p, rot.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<Pair<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z && !digUnderFeet) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (droppedTargets.contains(pos)) continue; // Ostinato: run107, the watchdog gave up on it from here
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Pair<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        // Ostinato Cadenza v2: of everything placeable from here, the one the camera is already closest to (stock: the
        // first in scan order, which can be behind the player while another is in front)
        boolean leastTurn = Baritone.settings().cadenza.value && Baritone.settings().cadenzaBuilder.value
                && (Baritone.settings().cadenzaTurnAware.value || Baritone.settings().cadenzaBatch.value);
        Rotation look = ctx.playerRotations();
        Placement best = null;
        double bestTurn = Double.POSITIVE_INFINITY;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            if (!leastTurn) {
                                return opt;
                            }
                            double turn = turnDegrees(look, opt.get().rot);
                            if (turn < bestTurn) {
                                bestTurn = turn;
                                best = opt.get();
                            }
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Ostinato Cadenza v2: the larger of the yaw and pitch turns between two looks (both axes turn at once). */
    static double turnDegrees(Rotation from, Rotation to) {
        return Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(to.getYaw() - from.getYaw())), Math.abs(to.getPitch() - from.getPitch()));
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        if (state == null) return false;
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekAim(rot); // Ostinato: uncapped settle (IAimProcessor.peekAim)
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }


    private BlockPos getFromHistory(final BlockPos pos) {
        if (pos == null) return null;

        final Optional<BlockPos> opt = blockBreakHistory.keySet().stream()
                .filter(e -> e.getX() == pos.getX() && e.getY() == pos.getY() && e.getZ() == pos.getZ()).findFirst();

        return opt.orElse(null);
    }

    private boolean anyHistoryMatch(final BlockPos pos) {
        return getFromHistory(pos) != null;
    }
    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        // Ostinato: a process ticked earlier this tick (e.g. #maze.tunnels pausing an edge for a pickaxe) may have cancelled
        // this build after executeProcesses queued it; report inactive instead of NPE-ing on the cleared origin.
        if (schematic == null) return null;
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private void noteRemoval(final BlockPos pos) {
        if (pos == null) return;
        if (anyHistoryMatch(pos)) {
            final BlockPos orig = getFromHistory(pos);
            final HistoryInfo info = blockBreakHistory.get(orig);
            if (!info.brokenPreviously) {
                info.brokenPreviously = true;
            }
        }
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        lastPlacePhase = PlacePhase.OTHER;
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                if (this.fromAltoclef) {
                    this.fromAltoclefFinished = true;
                }
                onLostControl();
                if (this.fromAltoclefFinished) {
                    this.stateStack.clear();
                }
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin.getX(), origin.getY(), origin.getZ()).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        // Ostinato: run119, walk to a stand within reach of a dig face the stall breaker could not hit from here.
        PathingCommand approach = approachDigFace(bcc, calcFailed);
        if (approach != null) return approach;
        Optional<Pair<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayerWithStall(bcc);
        // Shafting is the crawl under the room: the stuck path is why we are here, so drop it and swing even if not onGround.
        if (toBreak.isPresent() && (shafting || (isSafeToCancel && ctx.player().onGround()))) {
            if (shafting) {
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().second();
            BetterBlockPos pos = toBreak.get().first();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                if (anyHistoryMatch(pos)) {
                    noteRemoval(pos);
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            lastPlacePhase = PlacePhase.BREAK;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        boolean useCadenza = Baritone.settings().cadenza.value && Baritone.settings().cadenzaBuilder.value;
        if (useCadenza) {
            BlockPos placing = cadenza.current();
            if (placing != null && !MovementHelper.isReplaceable(placing.getX(), placing.getY(), placing.getZ(), bcc.bsi.get0(placing), bcc.bsi)) {
                cadenza.confirm(); // the block is there
            }
            cadenza.tick();
        }
        if (toPlace.isPresent()) {
            Rotation want = toPlace.get().rot, have = ctx.playerRotations();
            lastPlaceYawErr = Math.abs(net.minecraft.util.Mth.wrapDegrees(want.getYaw() - have.getYaw()));
            lastPlacePitchErr = Math.abs(want.getPitch() - have.getPitch());
            lastPlacePhase = PlacePhase.BLOCKED; // until the branch below takes it
            lastPlaceBlocked = (isSafeToCancel ? 0 : 1) | (ctx.player().onGround() ? 0 : 2) | (ticks <= 0 ? 0 : 4)
                    | (AltoClefSettings.getInstance().isInteractionPaused() ? 8 : 0);
        } else {
            lastPlacePhase = PlacePhase.NONE;
        }
        if (!AltoClefSettings.getInstance().isInteractionPaused() && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            lastPlacePhase = PlacePhase.ALIGN;
            if (useCadenza) {
                cadenza.attempt(toPlace.get().placeAgainst.relative(toPlace.get().side), 40);
            }
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if ((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                lastPlacePhase = PlacePhase.CLICK;
            }
            stopProtectItemOfMissing();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (useCadenza && toPlace.isEmpty() && isSafeToCancel && ticks <= 0) {
            // standing in a planned placement cell with nothing placeable from it: drop the cell after a few ticks
            cadenza.nothingReachable(ctx.playerFeet(), ctx.player().onGround());
        }

        if (!AltoClefSettings.getInstance().isInteractionPaused() && Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                // Ostinato: run136. After a failed dig-face approach the builder found no goal, paused silently and sat
                // 5+ min (no log, tenorclef idle). While incorrect blocks remain, walk up to the nearest one instead.
                if (assembleNullRetries < 20 && incorrectPositions != null && approachTarget == null) {
                    BetterBlockPos feetNow = ctx.playerFeet();
                    BetterBlockPos near = null;
                    double nd = Double.MAX_VALUE;
                    for (BetterBlockPos p : incorrectPositions) {
                        if (bcc.bsi.get0(p.x, p.y, p.z).getBlock() instanceof AirBlock) continue;
                        double d = feetNow.distSqr(p);
                        if (d < nd) { nd = d; near = p; }
                    }
                    if (near != null) {
                        assembleNullRetries++;
                        droppedTargets.clear();
                        approachTarget = near; approachStage = 1; approachTicks = 0; approachArrivedTicks = 0;
                        System.out.println("MAZETEST dig stall breaker: no build goal, approaching nearest incorrect " + near + " retry=" + assembleNullRetries + " feet=" + feetNow);
                        return new PathingCommand(new GoalGetToBlock(near), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                    }
                }
                System.out.println("MAZETEST dig stall breaker: no build goal, pausing retries=" + assembleNullRetries);
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        assembleNullRetries = 0; // Ostinato: run136
        updateMovement();
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private void updateMovement() {
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec == null || exec.finished() || exec.failed()) {
            return;
        }
        // A zero-length path (start == goal) has no movements; get(0) crashed the client (TenorClef s297t).
        java.util.List<?> movements = exec.getPath().movements();
        if (exec.getPosition() < 0 || exec.getPosition() >= movements.size()) {
            return;
        }
        Movement movement = (Movement) movements.get(exec.getPosition());
        movement.update();
    }

    private boolean isDefaultState(final BlockState state) {
        // 26.x: getValues() streams Property.Value records instead of returning a map
        return state.getValues().noneMatch(v -> {
            String key = v.property().getName();
            String value = v.valueName();
            return (key.equals("part") && value.equals("head")) || (key.equals("half") && value.equals("upper"));
        });
    }


    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        List<BetterBlockPos> placeNow = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                placeNow.add(pos);
            }
        });
        if (Baritone.settings().cadenza.value && Baritone.settings().cadenzaBuilder.value && !placeNow.isEmpty()) {
            Goal primary = cadenzaPlacementGoal(bcc, placeNow, sourceLiquids);
            return new JankyGoalComposite(primary, new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        placeNow.forEach(pos -> toPlace.add(placementGoal(pos, bcc)));
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    /**
     * Ostinato Cadenza: stand where the nearest placements can be made from (the sneaking eye sees a face to click
     * within reach), instead of merely next to them. Placements past cadenzaMaxTargets, ones Cadenza finds no cell for
     * and source liquids keep their old goals.
     */
    private Goal cadenzaPlacementGoal(BuilderCalculationContext bcc, List<BetterBlockPos> placeNow, List<BetterBlockPos> sourceLiquids) {
        double reach = ctx.playerController().getBlockReachDistance();
        double eye = ctx.player().getEyeHeight(Pose.CROUCHING);
        int max = Baritone.settings().cadenzaMaxTargets.value;
        BetterBlockPos feet = ctx.playerFeet();
        List<BetterBlockPos> byDistance = new ArrayList<>(placeNow);
        byDistance.sort(Comparator.comparingDouble(feet::distSqr));
        List<Cadenza.Target> targets = new ArrayList<>();
        List<Goal> extras = new ArrayList<>();
        for (BetterBlockPos pos : byDistance) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
            if (targets.size() < max && desired != null && placementPlausible(pos, desired)) {
                targets.add(new Cadenza.Target(Cadenza.Kind.PLACE, pos, reach, eye, false));
            } else {
                extras.add(placementGoal(pos, bcc));
            }
        }
        sourceLiquids.forEach(pos -> extras.add(new GoalBlock(pos.above())));
        // only cells searchForPlacables places from: within 5 sideways, the target 5 below to 1 above the feet, and at
        // head height only under a block
        Cadenza.CellFilter inSearchBox = (t, x, y, z) -> {
            int dy = t.pos.y - y;
            return Math.abs(t.pos.x - x) <= 5 && Math.abs(t.pos.z - z) <= 5 && dy >= -5 && dy <= 1
                    && (dy < 1 || !(bcc.bsi.get0(t.pos.x, t.pos.y + 1, t.pos.z).getBlock() instanceof AirBlock));
        };
        return cadenza.goal(bcc, targets, Cadenza.Forbidden.NONE, inSearchBox, Baritone.settings().cadenzaShortHorizon.value,
                CadenzaSession.liveOptions(CadenzaGoal.Ordering.ANY), r -> {
            if (Baritone.settings().cadenzaDebug.value) {
                logDirect("Cadenza: no cell for " + r.target + " (" + r.status + "), old goal kept");
            }
            extras.add(placementGoal(r.target.pos, bcc));
        }, extras);
    }

    /** Ostinato: passes the primary goal's terminal costs (Cadenza) through; a plain primary charges nothing. */
    public static class JankyGoalComposite implements TerminalCostGoal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public double terminalCost(int x, int y, int z) {
            if (primary instanceof TerminalCostGoal t && !fallback.isInGoal(x, y, z)) {
                return t.terminalCost(x, y, z);
            }
            return 0;
        }

        @Override
        public double terminalCost(int x, int y, int z, int fromX, int fromY, int fromZ) {
            if (primary instanceof TerminalCostGoal t && !fallback.isInGoal(x, y, z)) {
                return t.terminalCost(x, y, z, fromX, fromY, fromZ);
            }
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        // Ostinato: run105. The player standing in the target made placementPlausible false for every face, so the goal fell
        // back to GoalPlace (stand on top, pillar). In a 2-high tunnel with a protected ceiling that never works, and the
        // path to a side stand crosses the target, flipping the goal mid-walk: rooms 6 and 7 left a seal open after 6 min of
        // walk in / stall / wander. The player can step out of the way; only other entities make the cell implausible.
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        boolean plausible = desired != null && (desired.getCollisionShape(ctx.world(), pos).isEmpty()
                || ctx.world().isUnobstructed(ctx.player(), desired.getCollisionShape(ctx.world(), pos).move(pos.getX(), pos.getY(), pos.getZ())));
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && plausible) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        if (this.fromAltoclef && this.stateStack.isEmpty()) {
            pushState();
        }
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        origin = null;
        missing = null;
        schemSize = null;
        fromAltoclef = false;
        active = false;
        regionGuard = null;
        blockBreakHistory.clear();
        cadenza.reset();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop) != second.getValue(prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final RegionGuard regionGuard;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.regionGuard = BuilderProcess.this.regionGuard;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        @Override
        public boolean isPossiblyProtected(int x, int y, int z) {
            // buildRegion + buildRegionProtectForeign: other bots' regions are off limits for breaking and placing
            return super.isPossiblyProtected(x, y, z) || (regionGuard != null && regionGuard.isForeign(x, y, z));
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }

    private static final class PropertyContainer {
        private final String propertyKey;
        private final String propertyValue;

        public PropertyContainer(final String propertyKey, final String propertyValue) {
            this.propertyKey = propertyKey;
            this.propertyValue = propertyValue;
        }

        public String getPropertyKey() {
            return this.propertyKey;
        }

        public String getPropertyValue() {
            return this.propertyValue;
        }
    }

    /**
     * World positions inside the whole schematic but outside this bot's region, with the same mirror/rotation and origin
     * the builder uses, so they line up with the masked schematic being built.
     */
    private static final class RegionGuard {

        private final ISchematic footprint;
        private final ISchematic owned;
        private final int originX, originY, originZ;

        RegionGuard(PartitionPlan plan, int regionIndex, Vec3i origin) {
            ISchematic box = new AbstractSchematic(plan.widthX(), plan.heightY(), plan.lengthZ()) {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return current; // only inSchematic is ever asked
                }
            };
            this.footprint = orient(box);
            this.owned = orient(MaskSchematic.create(box, plan.regionMask(regionIndex, RegionPart.WHOLE)));
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
        }

        boolean isForeign(int x, int y, int z) {
            int rx = x - originX, ry = y - originY, rz = z - originZ;
            return footprint.inSchematic(rx, ry, rz, null) && !owned.inSchematic(rx, ry, rz, null);
        }
    }

    private static final class HistoryInfo {
        public long counter = 0;
        public boolean brokenPreviously = false;
    }

}
