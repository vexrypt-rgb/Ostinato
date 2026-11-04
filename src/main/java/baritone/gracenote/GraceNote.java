/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.gracenote;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.gracenote.GraceNoteOutcome;
import baritone.api.gracenote.GraceNotePolicy;
import baritone.api.gracenote.GraceNoteStatus;
import baritone.api.gracenote.IGraceNote;
import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.behavior.PathingBehavior;
import baritone.gracenote.GraceNoteCore.Aim;
import baritone.gracenote.GraceNoteCore.Body;
import baritone.gracenote.GraceNoteCore.Command;
import baritone.gracenote.GraceNoteCore.Forecast;
import baritone.gracenote.GraceNoteCore.Hand;
import baritone.gracenote.GraceNoteCore.Means;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import baritone.utils.HumanInputCaps;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ostinato: Grace Note in the game. Registered as a behavior right after LookBehavior so its tick comes before the
 * path executor's: what it detects, it can hold on the same tick. Its {@link #lateListener} is registered after every
 * other listener, so the bucket press is gated on the hand, view and screen as they are just before the game handles
 * keys. Its {@link #process} holds every other process off (REQUEST_PAUSE, temporary: they keep their state) while it
 * owns movement. Nothing here costs more than a few field reads unless the player is falling.
 */
public final class GraceNote extends Behavior implements IGraceNote {
    /** Ticks a fall is forecast over; the PPE region bound covers 96 blocks down, about 30 ticks at terminal speed. */
    static final int HORIZON = 40;
    static final String OWNER = "GraceNote";

    private final GraceNoteCore core = new GraceNoteCore();
    private final HoldProcess process = new HoldProcess();
    private final AbstractGameEventListener lateListener = new LateListener();
    private volatile Mode mode;
    private long tick;
    private Level world;
    private Object dimension;
    // the forecast an episode is acting on: the landing block, the landing position, the cell the water goes in
    private BlockPos landingBlock, targetCell;
    private double landX, landZ;
    private long outcomeSeen;
    private long ticks, screened, predictions, totalNanos, maxNanos, predictNanos, predictMaxNanos, spentThisTick;
    // the threat context, read again only when the service publishes a new snapshot
    private Object threatSnapshot;
    private int threatLevel;
    private String entityNote;
    /** The engine's first prediction in a session is slow (class loading, first region copy): paid on joining, not mid-fall. */
    private boolean warm;
    private long warmupNanos = -1, notInMax;

    public GraceNote(Baritone baritone) {
        super(baritone);
        String m = System.getProperty("ostinato.graceNote", "observe").trim().toUpperCase(java.util.Locale.ROOT);
        Mode parsed;
        try {
            parsed = Mode.valueOf(m);
        } catch (IllegalArgumentException e) {
            parsed = Mode.OBSERVE;
        }
        this.mode = parsed;
    }

    public IBaritoneProcess process() {
        return process;
    }

    public AbstractGameEventListener lateListener() {
        return lateListener;
    }

    @Override
    public Mode mode() {
        return mode;
    }

    @Override
    public void setMode(Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode);
    }

    @Override
    public GraceNotePolicy policy() {
        return core.policy();
    }

    @Override
    public void setPolicy(GraceNotePolicy policy) {
        core.setPolicy(policy);
    }

    @Override
    public GraceNoteStatus status() {
        return core.status();
    }

    @Override
    public GraceNoteOutcome lastOutcome() {
        return core.lastOutcome();
    }

    @Override
    public boolean holdsControl() {
        return core.holdsControl();
    }

    @Override
    public Telemetry telemetry() {
        GraceNoteCore.Counters c = core.counters;
        return new Telemetry(ticks, screened, predictions, c.emergencies, c.acquisitions, c.clicks, c.avoided, c.survived,
                c.died, c.failed, c.aborted, c.observed, c.deferred, c.thrashBlocked, totalNanos, maxNanos, predictNanos,
                predictMaxNanos);
    }

    /** The phases of the current or last episode, for logs. */
    public java.util.List<Phase> trail() {
        return core.trail();
    }

    // ---- early: before the path executor ----

    @Override
    public void onTick(TickEvent event) {
        long t0 = System.nanoTime();
        tick++;
        ticks++;
        spentThisTick = 0;
        try {
            early(event.getType() == TickEvent.Type.IN && ctx.player() != null && ctx.world() != null);
        } finally {
            spend(t0);
        }
    }

    private void early(boolean inWorld) {
        PathingBehavior pathing = baritone.getPathingBehavior();
        boolean heldLastTick = pathing.emergencyHeldThisTick(); // the executor has not ticked yet this tick
        boolean changed = false;
        LocalPlayer p = inWorld ? ctx.player() : null;
        if (inWorld) {
            Object dim = ctx.world().dimension();
            changed = world != null && (world != ctx.world() || !dim.equals(dimension));
            world = ctx.world();
            dimension = dim;
            if (changed) warm = false;
        } else {
            world = null;
            dimension = null;
            warm = false;
        }
        Body body = p == null ? new Body(false, false, false, "NO_WORLD", 0, 0, 0, false) : body(p);
        Forecast f = null;
        Means m = null;
        Phase phase = core.phase();
        boolean evaluating = phase == Phase.MONITORING || phase == Phase.COMPLETED || phase == Phase.FAILED
                || phase == Phase.ACQUIRING_CONTROL || phase == Phase.EXECUTING;
        if (p != null && mode != Mode.OFF && evaluating && screen(body, phase)) {
            screened++;
            PredictionResult r = predict(p);
            boolean keep = phase == Phase.ACQUIRING_CONTROL || phase == Phase.EXECUTING;
            f = forecast(p, r, keep);
            m = means(p, f, r);
        }
        if (p != null && mode != Mode.OFF) {
            var snap = baritone.getThreatAssessment().latest();
            if (snap != threatSnapshot) {
                threatSnapshot = snap;
                threatLevel = snap == null ? 0 : snap.highestLevel();
                // context only: the threat service is a heuristic with LIMITED confidence, never a trigger for a maneuver
                entityNote = threatLevel >= 4 && snap.immediateSources() > 0 ? "ENTITY_THREAT_REPORTED_ONLY_LEVEL_" + threatLevel : null;
            }
        } else {
            threatSnapshot = null;
            threatLevel = 0;
            entityNote = null;
        }
        if (p != null && mode != Mode.OFF && !warm && f == null && body.onGround()) warmUp(p);
        Command c = core.early(tick, mode, p != null, changed, body, f, m, heldLastTick, threatLevel, entityNote);
        if (p == null) return;
        if (c.hold()) pathing.requestEmergencyHold(OWNER + "#" + core.status().episode());
        if (c.selectSlot() >= 0 && p.getInventory().getSelectedSlot() != c.selectSlot()) p.getInventory().setSelectedSlot(c.selectSlot());
        if (c.aim() != Aim.NONE) aim(p, c.aim());
        report();
    }

    private Body body(LocalPlayer p) {
        float health = p.getHealth() + p.getAbsorptionAmount();
        boolean hurt = p.hurtTime > 0 || p.getLastHurtByMob() != null && p.tickCount - p.getLastHurtByMobTimestamp() <= 20;
        boolean ground = p.onGround(), water = p.isInWater();
        // exemptions only matter in the air; on the ground the span ends anyway
        String exempt = p.isAlive() && (ground || water) ? null : exempt(p);
        return new Body(p.isAlive(), ground, water, exempt, p.getDeltaMovement().y, p.fallDistance, health, hurt);
    }

    /**
     * One throwaway forecast while standing, once per world: its cost is kept apart from the emergency telemetry.
     * Ostinato Presto v4: after the engine's own off-thread warm-up (synthetic terrain, no world access) has loaded and
     * compiled the predictor. Run cold on the main thread this cost 83-92 ms (presto-v4-fix4, fix5), most of it the
     * predictor's first run, which a deadline cannot cut (86.7 ms with one, fix6). Until then the ticks go on as
     * before the warm-up: a fall in that window forecasts cold, as the first one always did.
     */
    private void warmUp(LocalPlayer p) {
        if (!baritone.getPredictivePhysics().warmUp()) return;
        warm = true;
        long t0 = System.nanoTime();
        try {
            BodyState s = baritone.getPredictivePhysics().capture(p);
            baritone.getPredictivePhysics().predict(PredictionRequest.fall(s, HORIZON).priority(PredictionRequest.Priority.PLANNING)
                    .fidelity(PredictionRequest.Fidelity.SCREENING).reach((float) p.blockInteractionRange()).consumer("grace-note-warmup")
                    .deadlineIn(10_000_000L).build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // nothing to warm; the first real forecast pays instead
        } finally {
            warmupNanos = System.nanoTime() - t0;
            notInMax = warmupNanos;
        }
    }

    /** What the last warm-up forecast cost, -1 before one ran. Not part of {@link #telemetry()}. */
    public long warmupNanos() {
        return warmupNanos;
    }

    /** Why this body cannot take fall damage now, or null. */
    static String exempt(LocalPlayer p) {
        if (!p.isAlive()) return "DEAD";
        if (p.isSpectator()) return "SPECTATOR";
        if (p.getAbilities().invulnerable) return "INVULNERABLE";
        if (p.getAbilities().flying) return "FLYING";
        if (p.isFallFlying()) return "GLIDING";
        if (p.isPassenger()) return "RIDING";
        if (p.onClimbable()) return "CLIMBING";
        if (p.isInLava()) return "IN_LAVA";
        if (p.hasEffect(MobEffects.SLOW_FALLING)) return "SLOW_FALLING";
        if (p.hasEffect(MobEffects.LEVITATION)) return "LEVITATING";
        return null;
    }

    /** The cheap gate in front of the engine: falling for real, or an episode that needs a fresh forecast. */
    private static boolean screen(Body b, Phase phase) {
        if (phase == Phase.ACQUIRING_CONTROL || phase == Phase.EXECUTING) return true;
        return b.alive() && b.exempt() == null && !b.onGround() && !b.inWater() && (b.vy() < -0.5 || b.fallDistance() >= 1.5);
    }

    private PredictionResult predict(LocalPlayer p) {
        long t0 = System.nanoTime();
        try {
            BodyState s = baritone.getPredictivePhysics().capture(p);
            predictions++;
            return baritone.getPredictivePhysics().predict(PredictionRequest.fall(s, HORIZON)
                    .priority(PredictionRequest.Priority.EMERGENCY).fidelity(PredictionRequest.Fidelity.SCREENING)
                    .reach((float) p.blockInteractionRange()).consumer("grace-note").deadlineIn(10_000_000L).build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        } finally {
            long dt = System.nanoTime() - t0;
            predictNanos += dt;
            predictMaxNanos = Math.max(predictMaxNanos, dt);
        }
    }

    /**
     * The forecast as the core sees it. Cells the engine never saw (outside its copy, unloaded) leave the landing
     * unknown: untrusted, so no episode starts on it and none acts on it. {@code keep}: an episode under way keeps the
     * landing it is aiming at, so a forecast that moved it (a hit mid-air) has to be re-planned by a new episode.
     */
    private Forecast forecast(LocalPlayer p, PredictionResult r, boolean keep) {
        String rejection = ForecastTrust.rejection(r);
        if (rejection != null) return Forecast.untrusted(rejection);
        PredictionResult.Landing l = r.landing;
        if (l == null) return new Forecast(true, r.confidence.name(), -1, -1, 0, false, false, null);
        BlockPos block = new BlockPos(l.blockX, l.blockY, l.blockZ);
        if (keep && landingBlock != null && !landingBlock.equals(block)) {
            return Forecast.untrusted("LANDING_MOVED");
        }
        // Even within the same block, the projected overlap can change after an impulse.
        landX = l.x;
        landZ = l.z;
        if (!keep || landingBlock == null) {
            landingBlock = block;
            landX = l.x;
            landZ = l.z;
            targetCell = waterCell(block);
        }
        return new Forecast(true, r.confidence.name(), l.tick, l.reachTick, l.damage, l.lethal, l.inFluid, hazard(block));
    }

    /**
     * Where the water goes for a press aimed at the landing block's top, or null when it would not break the fall: the
     * block would take the press itself (a chest opens, a cauldron fills) or would be waterlogged under the feet.
     */
    private BlockPos waterCell(BlockPos block) {
        BlockState s = ctx.world().getBlockState(block);
        if (interactive(s)) return null;
        BlockPos cell = bucketCell(block, Direction.UP);
        return cell.equals(block) && s.isCollisionShapeFullBlock(ctx.world(), block) ? null : cell;
    }

    /** As BucketItem.use picks the cell for a hit: a block that takes water takes it, else the cell on the hit face. */
    private BlockPos bucketCell(BlockPos hit, Direction face) {
        BlockState s = ctx.world().getBlockState(hit);
        if (s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(ctx.player(), ctx.world(), hit, s, Fluids.WATER)) {
            return hit;
        }
        return hit.relative(face);
    }

    /**
     * A block a use press acts on before the item does (the game tries the block first). Not exhaustive: the common
     * ones a fall can end on. Grace Note does not press at them; the bucket would not empty, or would open a screen.
     */
    static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return b instanceof BaseEntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock || b instanceof AbstractCauldronBlock
                || b instanceof ComposterBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof NoteBlock
                || b instanceof DiodeBlock || b instanceof CakeBlock || b instanceof RespawnAnchorBlock || b instanceof StonecutterBlock
                || b instanceof GrindstoneBlock || b instanceof LoomBlock || b instanceof CartographyTableBlock;
    }

    /** What the landing does apart from the fall. Reported; only LAVA changes the verdict (nothing answers it). */
    private String hazard(BlockPos block) {
        BlockState on = ctx.world().getBlockState(block), in = ctx.world().getBlockState(block.above());
        if (in.getFluidState().is(FluidTags.LAVA) || on.getFluidState().is(FluidTags.LAVA)) return "LAVA";
        if (in.is(Blocks.FIRE) || in.is(Blocks.SOUL_FIRE) || on.is(Blocks.CAMPFIRE) || on.is(Blocks.SOUL_CAMPFIRE)) return "FIRE";
        if (on.is(Blocks.MAGMA_BLOCK)) return "MAGMA";
        if (on.is(Blocks.CACTUS) || in.is(Blocks.CACTUS)) return "CACTUS";
        if (in.is(Blocks.SWEET_BERRY_BUSH)) return "BERRY_BUSH";
        if (in.is(Blocks.WITHER_ROSE)) return "WITHER_ROSE";
        if (in.is(Blocks.POWDER_SNOW) || on.is(Blocks.POWDER_SNOW)) return "POWDER_SNOW";
        if (on.is(Blocks.POINTED_DRIPSTONE)) return "DRIPSTONE";
        return null;
    }

    private Means means(LocalPlayer p, Forecast f, PredictionResult r) {
        int slot = -1;
        int selected = p.getInventory().getSelectedSlot();
        if (p.getInventory().getItem(selected).is(Items.WATER_BUCKET)) slot = selected;
        for (int i = 0; i < 9 && slot < 0; i++) if (p.getInventory().getItem(i).is(Items.WATER_BUCKET)) slot = i;
        boolean screenOpen = ctx.minecraft().gui.screen() != null || ctx.minecraft().gui.overlay() != null;
        boolean evaporates = false, replaceable = false, owned = false;
        if (targetCell != null && f.landingTicks() >= 0) {
            evaporates = Boolean.TRUE.equals(ctx.world().environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, targetCell));
            BlockState s = ctx.world().getBlockState(targetCell);
            replaceable = s.isAir() || s.canBeReplaced(Fluids.WATER)
                    || s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(p, ctx.world(), targetCell, s, Fluids.WATER);
        }
        if (f.landingTicks() >= 0) owned = plannedFallOwns(f);
        int ready = readyTicks(p) + (p.isUsingItem() ? 1 : 0);
        return new Means(slot, ready, screenOpen, evaporates, replaceable, AltoClefSettings.getInstance().isInteractionPaused(), owned);
    }

    /**
     * A path fall the executor is running and that lands where the forecast does answers itself (MovementFall has its
     * own bucket), unless the landing kills: then Grace Note holds the executor and only one of them acts.
     */
    private boolean plannedFallOwns(Forecast f) {
        PathingBehavior pathing = baritone.getPathingBehavior();
        PathExecutor cur = pathing.getCurrent();
        if (cur == null || f.lethal() || landingBlock == null) return false;
        int i = cur.getPosition();
        if (i < 0 || i >= cur.getPath().movements().size()) return false;
        if (!(cur.getPath().movements().get(i) instanceof MovementFall fall)) return false;
        BlockPos dest = fall.getDest();
        return dest.getX() == landingBlock.getX() && dest.getZ() == landingBlock.getZ() && Math.abs(dest.getY() - (landingBlock.getY() + 1)) <= 1;
    }

    /** Ticks until the look can be on the landing: 0 when it already is, else turns at this tick's cap. */
    private int readyTicks(LocalPlayer p) {
        Vec3 point = landingPoint(p);
        if (point == null) return 99;
        Rotation want = RotationUtils.calcRotationFromVec3d(nextEye(p), point, ctx.playerRotations());
        float dy = Math.abs(Mth.wrapDegrees(want.getYaw() - p.getYRot())), dp = Math.abs(want.getPitch() - p.getXRot());
        if (dp <= 2 && (dy <= 4 || p.getXRot() >= 85)) return 0;
        boolean human = Baritone.settings().humanLookEverywhere.value;
        float yawStep = Math.min(human ? 60 : 90, HumanInputCaps.maxYaw()), pitchStep = Math.min(human ? 45 : 90, HumanInputCaps.maxPitch());
        int turns = (int) Math.ceil(Math.max(dy / Math.max(1, yawStep), dp / Math.max(1, pitchStep)));
        return Math.max(1, turns) + (human ? 1 : 0);
    }

    /** Where the eye will be when the next tick's crosshair is picked: one step of the current motion on. */
    private Vec3 nextEye(LocalPlayer p) {
        return ctx.playerHead().add(p.getDeltaMovement());
    }

    /** A point on the landing block, under where the player will be: a steep ray from above meets its top face. */
    private Vec3 landingPoint(LocalPlayer p) {
        if (landingBlock == null) return null;
        Vec3 next = p.position().add(p.getDeltaMovement());
        double x = Mth.clamp(next.x, landingBlock.getX() + 0.2, landingBlock.getX() + 0.8);
        double z = Mth.clamp(next.z, landingBlock.getZ() + 0.2, landingBlock.getZ() + 0.8);
        return new Vec3(x, landingBlock.getY() + 0.5, z);
    }

    private void aim(LocalPlayer p, Aim a) {
        Vec3 point = a == Aim.LANDING ? landingPoint(p) : targetCell == null ? null : Vec3.atCenterOf(targetCell).add(0, -0.1, 0);
        if (point == null) return;
        Rotation r = RotationUtils.calcRotationFromVec3d(nextEye(p), point, ctx.playerRotations());
        // an emergency look turns in one tick where a person could (HumanInputCaps still caps it), not at the usual 32°
        baritone.getLookBehavior().turnCap(90);
        baritone.getLookBehavior().updateTarget(r, true);
    }

    // ---- late: after every other listener, just before the game handles keys ----

    private void late() {
        if (!core.holdsControl() || ctx.player() == null || ctx.world() == null) return;
        LocalPlayer p = ctx.player();
        boolean recovering = core.phase() == Phase.RECOVERING;
        boolean foreign = false;
        for (Input in : Input.values()) foreign |= baritone.getInputOverrideHandler().isInputForcedDown(in);
        boolean hand = p.getMainHandItem().is(recovering ? Items.BUCKET : Items.WATER_BUCKET);
        boolean screenOpen = ctx.minecraft().gui.screen() != null || ctx.minecraft().gui.overlay() != null;
        boolean ray = recovering ? rayOnWater(p) : rayOnLanding(p);
        boolean processOwns = baritone.getPathingControlManager().mostRecentInControl().orElse(null) == process;
        GraceNoteCore.Late in = new GraceNoteCore.Late(processOwns && baritone.getPathingBehavior().emergencyHeldThisTick(), foreign, hand, ray, screenOpen, p.isUsingItem());
        if (core.late(tick, in)) {
            // a real press of the use key, handled by the game's keybinds later this tick (as CombatAim.press does)
            KeyMapping km = ctx.minecraft().options.keyUse;
            KeyMapping.click(InputConstants.getKey(km.saveString()));
        }
        report();
    }

    /** As BucketItem.use will raycast now (full bucket: fluids ignored): water lands in a cell the player falls into. */
    private boolean rayOnLanding(LocalPlayer p) {
        if (landingBlock == null || targetCell == null) return false;
        BlockHitResult h = ray(p, ClipContext.Fluid.NONE);
        if (h == null) return false;
        if (interactive(ctx.world().getBlockState(h.getBlockPos()))) return false;
        BlockPos cell = bucketCell(h.getBlockPos(), h.getDirection());
        if (cell.equals(targetCell)) return true;
        // a neighbour column the player's box will still overlap at landing (0.3 either side of the landing centre)
        return cell.getY() == targetCell.getY() && cell.getX() >= Mth.floor(landX - 0.3) && cell.getX() <= Mth.floor(landX + 0.3)
                && cell.getZ() >= Mth.floor(landZ - 0.3) && cell.getZ() <= Mth.floor(landZ + 0.3) && ctx.world().getBlockState(cell).isAir();
    }

    /** As BucketItem.use will raycast now (empty bucket: source fluids): the water put down is what it picks up. */
    private boolean rayOnWater(LocalPlayer p) {
        BlockHitResult h = ray(p, ClipContext.Fluid.SOURCE_ONLY);
        return h != null && h.getBlockPos().equals(targetCell) && waterAt(targetCell);
    }

    private BlockHitResult ray(LocalPlayer p, ClipContext.Fluid fluid) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getViewVector(1f).scale(p.blockInteractionRange()));
        BlockHitResult h = ctx.world().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, p));
        return h.getType() == HitResult.Type.BLOCK ? h : null;
    }

    private boolean waterAt(BlockPos cell) {
        var fs = ctx.world().getFluidState(cell);
        return fs.isSource() && fs.is(FluidTags.WATER);
    }

    // ---- post: after the player moved ----

    @Override
    public void onPostTick(TickEvent event) {
        Phase phase = core.phase();
        if (phase == Phase.MONITORING || phase == Phase.COMPLETED || phase == Phase.FAILED) return;
        long t0 = System.nanoTime();
        try {
            LocalPlayer p = ctx.player();
            if (event.getType() != TickEvent.Type.IN || p == null || ctx.world() == null) return;
            Hand hand = p.getMainHandItem().is(Items.WATER_BUCKET) ? Hand.WATER_BUCKET : p.getMainHandItem().is(Items.BUCKET) ? Hand.BUCKET : Hand.OTHER;
            core.post(tick, new GraceNoteCore.Post(p.isAlive(), p.onGround(), p.isInWater(), p.fallDistance,
                    p.getHealth() + p.getAbsorptionAmount(), hand, targetCell != null && waterAt(targetCell)));
            report();
        } finally {
            spend(t0);
        }
    }

    private void report() {
        Phase phase = core.phase();
        if (phase == Phase.MONITORING) {
            landingBlock = null;
            targetCell = null;
        }
        GraceNoteOutcome o = core.lastOutcome();
        if (o != null && o.sequence() != outcomeSeen) {
            outcomeSeen = o.sequence();
            System.out.println("[GraceNote] " + o + " trail=" + core.trail());
        }
    }

    private void spend(long t0) {
        long dt = System.nanoTime() - t0;
        totalNanos += dt;
        spentThisTick += dt - notInMax; // the worst tick is an emergency tick's figure; the warm-up is reported on its own
        notInMax = 0;
        maxNanos = Math.max(maxNanos, spentThisTick);
    }

    /** Holds every other process off while Grace Note owns movement: they pause, keep their state, resume after. */
    private final class HoldProcess implements IBaritoneProcess {
        @Override
        public boolean isActive() {
            return core.wantsHold();
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {}

        @Override
        public double priority() {
            return 1000; // above every process: an emergency outranks work
        }

        @Override
        public String displayName0() {
            return "Grace Note emergency hold";
        }
    }

    private final class LateListener implements AbstractGameEventListener {
        @Override
        public void onTick(TickEvent event) {
            if (event.getType() != TickEvent.Type.IN) return;
            long t0 = System.nanoTime();
            try {
                late();
            } finally {
                spend(t0);
            }
        }
    }
}
