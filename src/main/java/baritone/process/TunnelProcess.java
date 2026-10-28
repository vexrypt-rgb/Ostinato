package baritone.process;

import baritone.Baritone;
import baritone.api.maze.ITunnelProvisioner;
import baritone.api.maze.TunnelProvisioners;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RotationUtils;
import baritone.maze.TunnelPlan;
import baritone.maze.TunnelSchematic;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    public static final int BED_RANGE = 96, NEED_BACKOFF = 8, MAX_TRIES = 3, DIG_STALL_TICKS = 2400, TORCH_TICKS = 200;

    private enum Phase { OFF, ENTRY, NEXT, DIG, PROVISION }

    private Phase phase = Phase.OFF;
    private TunnelPlan plan;
    private TunnelPlan.Edge edge;
    private Set<BlockPos> cells;
    private int tries, edgesDone, abandoned, provisions, edgesSinceNeedFail = NEED_BACKOFF;
    private BlockPos bed, entryTop;
    private String summary = "idle";
    private boolean restoreBreakFromAbove, restoreAllowInventory, resumeEdge;
    private int pickWait;
    // Ostinato: junction torch placed by this process after its node is dug (the builder never placed one: run76 sat 3 h
    // in DIG next to a torch cell with every block already air). digSolid/digStall: no-progress watchdog on the builder.
    private BlockPos torchAt;
    private int torchTicks, digTicks, digSolid, digStall;
    private Map<String, Integer> lastNeeds = Map.of();
    // Ostinato: kit pieces (pickaxe, sword, helmet, ...) whose copper ask came back unmet; only those fall back to
    // iron/stone. run73 made the copper pick, sword, helmet, chest and legs, but the soft budget ran out on the boots,
    // and the old single flag then switched every piece (and all later pick upgrades) to iron.
    private final java.util.Set<String> copperMissed = new java.util.HashSet<>();

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
        // Ostinato: root node 17 blocks east and 16 down from where the command was used; an entry stair leads there
        // (16 steps need 17 columns). The stair cuts through the node just west of the root, so that node is blocked.
        plan = new TunnelPlan(seed, feet.getX() + 17, feet.getY() - 16, feet.getZ());
        plan.block(new TunnelPlan.Node(-1, 0, 0));
        phase = Phase.ENTRY;
        tries = edgesDone = abandoned = provisions = 0;
        // Ostinato: soft kit (armor, torches, food, bed, iron upgrade) waits NEED_BACKOFF edges so digging starts at once;
        // only a pickaxe is a hard gate (pickGate). Previously the whole iron armor kit had to be built first.
        edgesSinceNeedFail = 0;
        resumeEdge = false;
        pickWait = 0;
        bed = null;
        copperMissed.clear();
        torchAt = null;
        pickAskMisses = 0;
        // Ostinato: ToolSet only scans the hotbar. A pick crafted into the main inventory left the builder mining stone
        // by hand; allowInventory lets InventoryBehavior swap the best stone tool into the hotbar.
        if (!Baritone.settings().allowInventory.value) {
            Baritone.settings().allowInventory.value = true;
            restoreAllowInventory = true;
        }
        if (!Baritone.settings().breakFromAbove.value) {
            Baritone.settings().breakFromAbove.value = true;
            restoreBreakFromAbove = true;
        }
        summary = "Tunnels seed=" + seed + " root=" + plan.center(new TunnelPlan.Node(0, 0, 0)).toShortString();
        return summary;
    }

    public void stop() {
        // Ostinato: go OFF first so a re-entrant isActive() during cancel cannot restart a dig.
        boolean digging = phase == Phase.DIG && baritone.getBuilderProcess().isActive();
        phase = Phase.OFF;
        if (digging) baritone.getBuilderProcess().onLostControl();
        if (restoreBreakFromAbove) Baritone.settings().breakFromAbove.value = false;
        restoreBreakFromAbove = false;
        if (restoreAllowInventory) Baritone.settings().allowInventory.value = false;
        restoreAllowInventory = false;
        summary = "idle";
    }

    public String status() {
        if (plan == null) return "Tunnels idle";
        return "Tunnels " + phase + " dug=" + plan.dugCount() + " edges=" + edgesDone + " frontier=" + plan.frontierSize()
                + " abandoned=" + abandoned + " provisions=" + provisions + " bed=" + (bed == null ? "none" : bed.toShortString())
                + (edge == null ? "" : " edge=" + plan.center(edge.from()).toShortString() + "->" + plan.center(edge.to()).toShortString())
                + " needs=" + lastNeeds;
    }

    public boolean provisioning() {
        return phase == Phase.PROVISION;
    }

    public TunnelPlan plan() {
        return plan;
    }

    @Override
    public boolean isActive() {
        if (phase == Phase.OFF || ctx.player() == null) return false;
        if (phase == Phase.DIG) {
            if (baritone.getBuilderProcess().isActive()) {
                if (bestPickLeft() > 0) {
                    if (++digTicks % 20 != 0) return false;
                    int solid = stillSolid();
                    if (solid < digSolid) {
                        digSolid = solid;
                        digStall = 0;
                        return false;
                    }
                    if ((digStall += 20) < DIG_STALL_TICKS) return false;
                    // Ostinato: no block cleared for DIG_STALL_TICKS. Leave DIG first (cancel-before-state), then stop
                    // only the builder and let finishEdge retry or abandon the edge.
                    System.out.println("TUNNELS dig stalled " + DIG_STALL_TICKS / 20 + "s solid=" + solid + "; stopping builder");
                    phase = edge == null ? Phase.ENTRY : Phase.NEXT;
                    baritone.getBuilderProcess().onLostControl();
                    finishEdge();
                    return true;
                }
                // Ostinato: pick broke (or was never there) mid-edge. Stop the builder instead of finishing by fist; the
                // gate in onTick gets a pick, then the same edge resumes without spending a retry.
                // Ostinato: leave DIG before cancelling and stop only the builder. cancelEverything() here re-entered
                // isActive() while still in DIG, which ran finishEdge() -> retry startDig() and left the builder
                // running, crashing with "Digging tunnel-... stayed active after being cancelled".
                resumeEdge = edge != null;
                phase = edge == null ? Phase.ENTRY : Phase.NEXT;
                baritone.getBuilderProcess().onLostControl();
                System.out.println("TUNNELS pick gone mid-dig; pausing edge for a pickaxe");
                return true;
            }
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
        if (phase == Phase.NEXT && torchAt != null) {
            PathingCommand c = placeTorch();
            if (c != null) return c;
        }
        if ((phase == Phase.ENTRY || phase == Phase.NEXT) && pickGate()) {
            // waiting for a pickaxe (provision in flight or retry pending); never dig without one
        } else if (phase == Phase.ENTRY) {
            startDig(null, TunnelPlan.carve(entryTop, plan.center(new TunnelPlan.Node(0, 0, 0))), null, "entry");
        } else if (phase == Phase.NEXT) {
            if (resumeEdge && edge != null) {
                resumeEdge = false;
                startDig(edge, cells, null, "resume");
            } else if (!provisionIfNeeded()) {
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
        digTicks = digStall = 0;
        digSolid = Integer.MAX_VALUE;
        TunnelSchematic s = TunnelSchematic.of(c, null); // Ostinato: dig only; torches are placeTorch()'s job
        String name = "tunnel-" + (e == null ? "entry" : plan.center(e.to()).toShortString().replace(" ", ""));
        baritone.getBuilderProcess().build(name, s, s.origin);
        phase = Phase.DIG;
        summary = "Digging " + name;
        System.out.println("TUNNELS dig " + kind + " " + name + " cells=" + c.size());
    }

    /**
     * Ostinato: one floor torch at {@link #torchAt} (a node centre, floor block under it): walk within reach, hold a torch,
     * click the top of the floor block. Null when done or given up (no torch, cell not air, TORCH_TICKS spent), so the
     * maze never waits on a torch.
     */
    private PathingCommand placeTorch() {
        BlockPos t = torchAt;
        var at = ctx.world().getBlockState(t);
        boolean done = at.is(Blocks.TORCH);
        if (done || !at.isAir() || ++torchTicks > TORCH_TICKS
                || !baritone.getInventoryBehavior().throwaway(false, st -> st.is(Items.TORCH), true)) {
            System.out.println("TUNNELS torch " + (done ? "placed " : "skipped ") + t.toShortString() + " ticks=" + torchTicks);
            torchAt = null;
            torchTicks = 0;
            return null;
        }
        Vec3 hit = Vec3.atCenterOf(t.below()).add(0, 0.5, 0);
        if (ctx.playerHead().distanceTo(hit) > 4.0) return new PathingCommand(new GoalNear(t, 1), PathingCommandType.SET_GOAL_AND_PATH);
        baritone.getInventoryBehavior().throwaway(true, st -> st.is(Items.TORCH), true);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), hit, ctx.playerRotations()), true);
        if (torchTicks % 5 == 0 && ctx.player().getMainHandItem().is(Items.TORCH)) {
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, t.below(), false));
        }
        return new PathingCommand((Goal) null, PathingCommandType.REQUEST_PAUSE);
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
            resumeEdge = edge != null; // Ostinato: retry goes through pickGate in onTick, not straight to the builder
            return;
        }
        if (edge == null) {
            if (solid > 0) System.out.println("TUNNELS entry left solid=" + solid + " (continuing)");
            plan.markDug(new TunnelPlan.Node(0, 0, 0), null, 0);
            if (plan.torchAt(new TunnelPlan.Node(0, 0, 0))) torchAt = plan.center(new TunnelPlan.Node(0, 0, 0));
            System.out.println("TUNNELS root dug " + plan.center(new TunnelPlan.Node(0, 0, 0)).toShortString());
        } else if (solid > 0) {
            abandoned++;
            plan.abandon(edge);
            System.out.println("TUNNELS abandon " + plan.center(edge.to()).toShortString() + " solid=" + solid);
        } else {
            plan.markDug(edge.to(), edge.from(), edge.depth());
            // Ostinato: torch rule (TunnelPlan): a node that became a junction or a ramp end. The bot stands in it now.
            if (plan.torchAt(edge.to())) torchAt = plan.center(edge.to());
            edgesDone++;
            edgesSinceNeedFail++;
        }
        tries = 0;
        phase = Phase.NEXT;
        if (edgesDone % 5 == 0) System.out.println("TUNNELS progress " + status() + " feet=" + ctx.playerFeet().toShortString());
    }

    /**
     * Ostinato: hard gate before any dig. Returns true (do not dig) while no usable pickaxe is in the inventory, and hands
     * the provisioner a stone pickaxe job (TenorClef chains logs -> planks -> wooden pick -> cobble -> stone pick).
     * No backoff here: digging stone by hand is never the fallback. Asks again every 200 ticks if refused.
     */
    private boolean pickAsked; // Ostinato: last provision was the hard pick ask
    private int pickAskMisses;

    private boolean pickGate() {
        if (bestPickLeft() > 0) {
            pickWait = 0;
            return false;
        }
        if (pickWait-- > 0) return true;
        pickWait = 200;
        Map<String, Integer> need = new LinkedHashMap<>();
        // Ostinato: TenorClef counts a stone_pickaxe by item count, so a worn one (<=2 uses, not "usable" here) already
        // satisfies {stone_pickaxe=1} and the job finishes in 0.1s with nothing made -> run70 looped forever. Ask for one
        // more than the stone picks already held (worn or not), and one more again each time a hard ask came back
        // with still no usable pick, so the provisioner must actually craft a fresh one.
        int heldStone = 0;
        var inv = ctx.player().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.STONE_PICKAXE)) heldStone += inv.getItem(i).getCount();
        // Ostinato: +2 (a fresh pick and a spare). A stone pick lasts ~2 edges, and every ask can mean a trip up for logs;
        // one table visit for two picks halves the trips.
        need.put("stone_pickaxe", heldStone + 2 + Math.min(pickAskMisses, 2));
        pickAsked = true;
        lastNeeds = need;
        ITunnelProvisioner p = TunnelProvisioners.get();
        if (p == null || !p.provision(need)) {
            summary = "Tunnels: no pickaxe and " + (p == null ? "no provisioner" : "provisioner refused") + "; waiting";
            System.out.println("TUNNELS " + summary);
            return true;
        }
        provisions++;
        phase = Phase.PROVISION;
        summary = "Provisioning pickaxe";
        System.out.println("TUNNELS provision " + need + " (hard: no pickaxe) via " + p.name() + " feet=" + ctx.playerFeet().toShortString());
        return true;
    }

    /** Best remaining durability fraction over pickaxes not about to break (0 = none usable). */
    private double bestPickLeft() {
        var inv = ctx.player().getInventory();
        double best = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(ItemTags.PICKAXES)) continue;
            if (s.getMaxDamage() > 0 && s.getMaxDamage() - s.getDamageValue() <= 2) continue;
            best = Math.max(best, s.getMaxDamage() <= 0 ? 1 : 1 - (double) s.getDamageValue() / s.getMaxDamage());
        }
        return best;
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
        if (before.containsKey("bed") && !now.containsKey("bed") && bed != null) {
            System.out.println("TUNNELS bed spawn set near " + bed.toShortString());
        }
        for (String k : before.keySet()) {
            int i = k.indexOf("copper_");
            if (i >= 0 && now.containsKey(k) && copperMissed.add(k.substring(i + 7))) {
                System.out.println("TUNNELS copper " + k.substring(i + 7) + " unmet; that piece falls back to iron/stone");
            }
        }
        if (pickAsked) {
            pickAsked = false;
            if (bestPickLeft() > 0) pickAskMisses = 0;
            else {
                pickAskMisses++; // Ostinato: hard pick ask returned with no usable pick; next ask escalates the count
                System.out.println("TUNNELS pick ask unmet (no usable pickaxe after provision) misses=" + pickAskMisses);
            }
        } else if (!now.isEmpty()) {
            // Ostinato: only a soft ask backs the soft kit off. Hard pick asks (every ~2 edges with stone picks) also reset
            // this, so run72 reached 10+ edges without ever asking for copper, armor, torches or a bed.
            edgesSinceNeedFail = 0; // back off; dig on and ask again later
        }
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
        int ironPicks = 0, picks = 0, food = 0, torches = 0, beds = 0, shovels = 0, swords = 0;
        double bestPick = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ItemTags.PICKAXES)) {
                picks++;
                if (!s.is(Items.WOODEN_PICKAXE) && !s.is(Items.STONE_PICKAXE) && !s.is(Items.GOLDEN_PICKAXE)) ironPicks++; // copper, iron or better
                double left = s.getMaxDamage() <= 0 ? 1 : 1 - (double) s.getDamageValue() / s.getMaxDamage();
                bestPick = Math.max(bestPick, left);
            }
            if (s.get(DataComponents.FOOD) != null) food += s.getCount();
            if (s.is(Items.TORCH)) torches += s.getCount();
            if (s.is(ItemTags.SHOVELS)) shovels++;
            if (s.is(ItemTags.SWORDS)) swords++;
            if (s.is(ItemTags.BEDS)) beds++;
        }
        // Ostinato: no pick at all is pickGate's job (hard); here only the iron upgrade / worn spare (soft).
        // Ostinato: copper first (copper ore is everywhere in 26.3); once a copper ask comes back unmet, iron/stone.
        if (picks > 0 && (ironPicks == 0 || bestPick < 0.15)) n.put(tier("pickaxe", "iron") + "_pickaxe", ironPicks + 1);
        if (shovels == 0) n.put(tier("shovel", "stone") + "_shovel", 1);
        if (swords == 0) n.put(tier("sword", "stone") + "_sword", 1);
        if (food < 6) n.put("food", 20);
        if (torches < 8) n.put("torch", 16);
        if (ctx.player().getItemBySlot(EquipmentSlot.HEAD).isEmpty()) n.put("equip:" + tier("helmet", "iron") + "_helmet", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.CHEST).isEmpty()) n.put("equip:" + tier("chestplate", "iron") + "_chestplate", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.LEGS).isEmpty()) n.put("equip:" + tier("leggings", "iron") + "_leggings", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.FEET).isEmpty()) n.put("equip:" + tier("boots", "iron") + "_boots", 1);
        BlockPos f = face();
        if (bed == null || horiz(bed, f) > BED_RANGE) {
            BlockPos placed = phase == Phase.PROVISION && lastNeeds.containsKey("bed") ? bedNear(ctx.playerFeet(), 5) : null;
            if (placed != null && horiz(placed, f) <= BED_RANGE) {
                // Ostinato: only a bed block actually in the world counts. The old code saved the player's feet as the
                // bed whenever a bed job ended, placed or not, so spawn was never really set.
                bed = placed;
            } else {
                n.put("bed", 1);
            }
        }
        return n;
    }

    /** Ostinato: copper first; a piece whose copper ask came back unmet uses {@code fallback}. */
    private String tier(String piece, String fallback) {
        return copperMissed.contains(piece) ? fallback : "copper";
    }

    private BlockPos bedNear(BlockPos c, int r) {
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            if (ctx.world().getBlockState(p).is(net.minecraft.tags.BlockTags.BEDS)) return p.immutable();
        }
        return null;
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
