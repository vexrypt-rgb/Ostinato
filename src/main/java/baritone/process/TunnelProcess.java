package baritone.process;

import baritone.Baritone;
import baritone.api.maze.ITunnelProvisioner;
import baritone.api.maze.TunnelProvisioners;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.maze.TunnelPlan;
import baritone.maze.TunnelSchematic;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: {@code #maze.tunnels}. Digs an endless seeded 3D maze of 3x3 tunnels in survival, one edge at a time,
 * through the builder. Dig only: no cladding, rooms, containers or seals; sparse floor torches per {@link TunnelPlan}.
 * <p>
 * Survival loop, checked before every edge: a spare pickaxe when the best one is under 15% durability, food, torches,
 * iron armor worn, and a bed within {@link #BED_RANGE} blocks of the dig face (spawn stays near the work). Anything
 * missing goes to the registered {@link ITunnelProvisioner} (TenorClef) and digging waits until it finishes. A need
 * that could not be met is retried after {@link #NEED_BACKOFF} edges; digging continues meanwhile.
 */
public final class TunnelProcess extends BaritoneProcessHelper {

    public static final int BED_RANGE = 96, NEED_BACKOFF = 8, MAX_TRIES = 3;

    private enum Phase { OFF, ENTRY, NEXT, DIG, PROVISION }

    private Phase phase = Phase.OFF;
    private TunnelPlan plan;
    private TunnelPlan.Edge edge;
    private Set<BlockPos> cells;
    private int tries, edgesDone, abandoned, provisions, edgesSinceNeedFail = NEED_BACKOFF;
    private BlockPos bed, entryTop;
    private String summary = "idle";
    private boolean restoreBreakFromAbove;
    private Map<String, Integer> lastNeeds = Map.of();

    public TunnelProcess(Baritone baritone) {
        super(baritone);
    }

    public boolean running() {
        return phase != Phase.OFF;
    }

    public String start(long seed) {
        if (ctx.player() == null) return "No world";
        BlockPos feet = ctx.playerFeet();
        entryTop = feet;
        // Ostinato: root node 16 blocks east and 16 down from where the command was used; an entry ramp leads there.
        plan = new TunnelPlan(seed, feet.getX() + 16, feet.getY() - 16, feet.getZ());
        phase = Phase.ENTRY;
        tries = edgesDone = abandoned = provisions = 0;
        if (!Baritone.settings().breakFromAbove.value) {
            Baritone.settings().breakFromAbove.value = true;
            restoreBreakFromAbove = true;
        }
        summary = "Tunnels seed=" + seed + " root=" + plan.center(new TunnelPlan.Node(0, 0, 0)).toShortString();
        return summary;
    }

    public void stop() {
        if (phase == Phase.DIG && baritone.getBuilderProcess().isActive()) baritone.getPathingBehavior().cancelEverything();
        phase = Phase.OFF;
        if (restoreBreakFromAbove) Baritone.settings().breakFromAbove.value = false;
        restoreBreakFromAbove = false;
        summary = "idle";
    }

    public String status() {
        if (plan == null) return "Tunnels idle";
        return "Tunnels " + phase + " dug=" + plan.dugCount() + " edges=" + edgesDone + " frontier=" + plan.frontierSize()
                + " abandoned=" + abandoned + " provisions=" + provisions + " bed=" + (bed == null ? "none" : bed.toShortString())
                + (edge == null ? "" : " edge=" + plan.center(edge.from()).toShortString() + "->" + plan.center(edge.to()).toShortString())
                + " needs=" + lastNeeds;
    }

    public TunnelPlan plan() {
        return plan;
    }

    @Override
    public boolean isActive() {
        if (phase == Phase.OFF || ctx.player() == null) return false;
        if (phase == Phase.DIG) {
            if (baritone.getBuilderProcess().isActive()) return false;
            finishEdge();
        }
        if (phase == Phase.PROVISION) {
            ITunnelProvisioner p = TunnelProvisioners.get();
            if (p != null && p.busy()) return false;
            afterProvision();
        }
        return true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase == Phase.ENTRY) {
            startDig(null, TunnelPlan.carve(entryTop, plan.center(new TunnelPlan.Node(0, 0, 0))), null, "entry");
        } else if (phase == Phase.NEXT) {
            if (!provisionIfNeeded()) {
                edge = plan.next(ctx.playerFeet());
                if (edge == null) {
                    summary = "Tunnels: no frontier";
                    logDirect(summary);
                    stop();
                } else {
                    BlockPos a = plan.center(edge.from()), b = plan.center(edge.to());
                    startDig(edge, TunnelPlan.carve(a, b), null, "edge");
                }
            }
        }
        return new PathingCommand((Goal) null, PathingCommandType.DEFER);
    }

    private void startDig(TunnelPlan.Edge e, Set<BlockPos> c, BlockPos unused, String kind) {
        cells = c;
        BlockPos torch = null;
        if (e != null) {
            // Ostinato: torch rule (TunnelPlan): far node on every TORCH_EVERY-th edge of depth, else the near node when it
            // is a junction. The builder only places it when a torch is in the inventory, and never breaks one.
            if (e.depth() % TunnelPlan.TORCH_EVERY == 0) torch = plan.center(e.to());
            else if (plan.torchAt(e.from())) torch = plan.center(e.from());
        }
        TunnelSchematic s = TunnelSchematic.of(c, torch);
        String name = "tunnel-" + (e == null ? "entry" : plan.center(e.to()).toShortString().replace(" ", ""));
        baritone.getBuilderProcess().build(name, s, s.origin);
        phase = Phase.DIG;
        summary = "Digging " + name;
        System.out.println("TUNNELS dig " + kind + " " + name + " cells=" + c.size() + " torch=" + (torch == null ? "-" : torch.toShortString()));
    }

    private int stillSolid() {
        int n = 0;
        for (BlockPos p : cells) {
            var st = ctx.world().getBlockState(p);
            if (!st.isAir() && !st.is(net.minecraft.world.level.block.Blocks.TORCH) && st.getFluidState().isEmpty()) n++;
        }
        return n;
    }

    private void finishEdge() {
        int solid = stillSolid();
        if (solid > 0 && ++tries < MAX_TRIES) {
            System.out.println("TUNNELS retry " + tries + " solid=" + solid);
            phase = edge == null ? Phase.ENTRY : Phase.NEXT;
            if (edge != null) { startDig(edge, cells, null, "retry"); }
            return;
        }
        if (edge == null) {
            if (solid > 0) System.out.println("TUNNELS entry left solid=" + solid + " (continuing)");
            plan.markDug(new TunnelPlan.Node(0, 0, 0), null, 0);
            System.out.println("TUNNELS root dug " + plan.center(new TunnelPlan.Node(0, 0, 0)).toShortString());
        } else if (solid > 0) {
            abandoned++;
            plan.abandon(edge);
            System.out.println("TUNNELS abandon " + plan.center(edge.to()).toShortString() + " solid=" + solid);
        } else {
            plan.markDug(edge.to(), edge.from(), edge.depth());
            edgesDone++;
            edgesSinceNeedFail++;
        }
        tries = 0;
        phase = Phase.NEXT;
        if (edgesDone % 5 == 0) System.out.println("TUNNELS progress " + status() + " feet=" + ctx.playerFeet().toShortString());
    }

    /** Returns true when a provision job was handed off and digging must wait. */
    private boolean provisionIfNeeded() {
        Map<String, Integer> needs = needs();
        lastNeeds = needs;
        if (needs.isEmpty() || edgesSinceNeedFail < NEED_BACKOFF) return false;
        ITunnelProvisioner p = TunnelProvisioners.get();
        if (p == null) {
            System.out.println("TUNNELS needs " + needs + " but no provisioner registered");
            edgesSinceNeedFail = 0;
            return false;
        }
        if (!p.provision(needs)) {
            System.out.println("TUNNELS provisioner refused " + needs);
            edgesSinceNeedFail = 0;
            return false;
        }
        provisions++;
        phase = Phase.PROVISION;
        summary = "Provisioning " + needs;
        System.out.println("TUNNELS provision " + needs + " via " + p.name() + " feet=" + ctx.playerFeet().toShortString());
        return true;
    }

    private void afterProvision() {
        Map<String, Integer> before = lastNeeds;
        Map<String, Integer> now = needs();
        if (before.containsKey("bed") && !now.containsKey("bed")) {
            System.out.println("TUNNELS bed spawn set near " + bed.toShortString());
        }
        if (!now.isEmpty()) edgesSinceNeedFail = 0; // back off; dig on and ask again later
        System.out.println("TUNNELS provision done left=" + now);
        lastNeeds = now;
        phase = Phase.NEXT;
    }

    private BlockPos face() {
        return edge != null ? plan.center(edge.to()) : ctx.playerFeet();
    }

    /** What the survival kit is missing right now. Catalogue names for TenorClef. */
    private Map<String, Integer> needs() {
        Map<String, Integer> n = new LinkedHashMap<>();
        var inv = ctx.player().getInventory();
        int ironPicks = 0, picks = 0, food = 0, torches = 0, beds = 0;
        double bestPick = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ItemTags.PICKAXES)) {
                picks++;
                if (s.is(Items.IRON_PICKAXE)) ironPicks++;
                double left = s.getMaxDamage() <= 0 ? 1 : 1 - (double) s.getDamageValue() / s.getMaxDamage();
                bestPick = Math.max(bestPick, left);
            }
            if (s.get(DataComponents.FOOD) != null) food += s.getCount();
            if (s.is(Items.TORCH)) torches += s.getCount();
            if (s.is(ItemTags.BEDS)) beds++;
        }
        if (picks == 0 || bestPick < 0.15) n.put("iron_pickaxe", ironPicks + 1);
        if (food < 6) n.put("food", 20);
        if (torches < 8) n.put("torch", 32);
        if (ctx.player().getItemBySlot(EquipmentSlot.HEAD).isEmpty()) n.put("equip:iron_helmet", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.CHEST).isEmpty()) n.put("equip:iron_chestplate", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.LEGS).isEmpty()) n.put("equip:iron_leggings", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.FEET).isEmpty()) n.put("equip:iron_boots", 1);
        BlockPos f = face();
        if (bed == null || horiz(bed, f) > BED_RANGE) {
            if (phase == Phase.PROVISION && lastNeeds.containsKey("bed")) {
                // Ostinato: provisioner placed a bed by the player; that is the new spawn.
                bed = ctx.playerFeet();
            } else {
                n.put("bed", 1);
            }
        }
        return n;
    }

    private static double horiz(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public void onLostControl() {
    }

    @Override
    public String displayName0() {
        return summary;
    }
}
