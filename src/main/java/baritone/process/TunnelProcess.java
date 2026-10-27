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
    private boolean restoreBreakFromAbove, restoreAllowInventory, resumeEdge;
    private int pickWait;
    private Map<String, Integer> lastNeeds = Map.of();
    private boolean copperMissed; // Ostinato: a copper kit ask came back unmet; fall back to iron/stone

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
        // Ostinato: soft kit (armor, torches, food, bed, iron upgrade) waits NEED_BACKOFF edges so digging starts at once;
        // only a pickaxe is a hard gate (pickGate). Previously the whole iron armor kit had to be built first.
        edgesSinceNeedFail = 0;
        resumeEdge = false;
        pickWait = 0;
        bed = null;
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
                if (bestPickLeft() > 0) return false;
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
            resumeEdge = edge != null; // Ostinato: retry goes through pickGate in onTick, not straight to the builder
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
        need.put("stone_pickaxe", heldStone + 1 + Math.min(pickAskMisses, 2));
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
        if (!copperMissed && before.keySet().stream().anyMatch(k -> k.contains("copper_") && now.containsKey(k))) {
            copperMissed = true;
            System.out.println("TUNNELS copper kit unmet " + before + "; falling back to iron/stone");
        }
        if (pickAsked) {
            pickAsked = false;
            if (bestPickLeft() > 0) pickAskMisses = 0;
            else {
                pickAskMisses++; // Ostinato: hard pick ask returned with no usable pick; next ask escalates the count
                System.out.println("TUNNELS pick ask unmet (no usable pickaxe after provision) misses=" + pickAskMisses);
            }
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
        String tier = copperMissed ? "iron" : "copper";
        if (picks > 0 && (ironPicks == 0 || bestPick < 0.15)) n.put(tier + "_pickaxe", ironPicks + 1);
        if (shovels == 0) n.put(copperMissed ? "stone_shovel" : "copper_shovel", 1);
        if (swords == 0) n.put(copperMissed ? "stone_sword" : "copper_sword", 1);
        if (food < 6) n.put("food", 20);
        if (torches < 8) n.put("torch", 16);
        if (ctx.player().getItemBySlot(EquipmentSlot.HEAD).isEmpty()) n.put("equip:" + tier + "_helmet", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.CHEST).isEmpty()) n.put("equip:" + tier + "_chestplate", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.LEGS).isEmpty()) n.put("equip:" + tier + "_leggings", 1);
        if (ctx.player().getItemBySlot(EquipmentSlot.FEET).isEmpty()) n.put("equip:" + tier + "_boots", 1);
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
