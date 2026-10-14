package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.bastion.DamageBudget;
import baritone.pathing.path.PathExecutor;
import baritone.utils.hunger.EatPlanner;
import baritone.utils.hunger.FoodSaver;
import baritone.utils.hunger.HungerModel;
import baritone.utils.hunger.InventoryFood;
import baritone.utils.hunger.PathHunger;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;

import java.util.List;
import java.util.Optional;

/**
 * Ostinato code: hunger while pathing. Predicts food along the current path, runs emergency food-saving mode, and eats
 * in natural pauses (path search, ladders, long falls, idle) or when it is urgent. Stays out of the way of processes
 * that handle food themselves (bastion, pve, pvp, combat).
 */
public final class HungerBehavior extends Behavior implements Helper {
    private final FoodSaver saver = new FoodSaver();
    private volatile boolean foodSaving;
    private HungerModel.Prediction prediction;
    private int tick;
    private int eatingFor = -1;
    private String lastDecision = "";

    public HungerBehavior(Baritone baritone) {
        super(baritone);
    }

    public boolean foodSaving() {
        return foodSaving || Baritone.settings().foodSavingMode.value;
    }

    public HungerModel.Prediction prediction() {
        return prediction;
    }

    public String status() {
        return (foodSaving() ? "food-saving (" + saver.reason() + ")" : "normal") + ", " + lastDecision;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null) {
            stopEating();
            return;
        }
        LocalPlayer me = ctx.player();
        if (System.getProperty("ostinato.bastion.hpfloor") == null) {
            DamageBudget.floor = Baritone.settings().healthFloor.value;
        }
        HungerModel model = InventoryFood.model(me);
        List<InventoryFood.Slot> slots = InventoryFood.carried(me);
        if (tick++ % 20 == 0) {
            updatePrediction(model);
            boolean manual = Baritone.settings().foodSavingMode.value;
            boolean was = foodSaving;
            foodSaving = (Baritone.settings().autoFoodSaving.value || manual) && saver.update(model, prediction, InventoryFood.points(slots), manual);
            if (was != foodSaving) {
                logDirect("Food-saving mode " + (foodSaving ? "on: " : "off: ") + saver.reason());
                baritone.getPathingBehavior().cancelSegmentIfSafe(); // replan with walking costs (or back to sprinting)
            }
        }
        if (!Baritone.settings().autoEat.value || foreignProcess()) {
            stopEating();
            return;
        }
        if (eatingFor >= 0) {
            eatingFor++;
            // eating finished (stack changed / not using any more) or something took the item away
            if (eatingFor > 2 && !me.isUsingItem() || eatingFor > 60) {
                stopEating();
            } else {
                holdUse();
            }
            return;
        }
        if (me.isUsingItem()) {
            return;
        }
        EatPlanner.Decision d = EatPlanner.decide(model, InventoryFood.foods(slots), pause(me), -1,
                prediction == null ? -1 : prediction.sprintStopsAt, Baritone.settings().healthFloor.value);
        lastDecision = (d.eat ? "eat " + d.food : "no eat") + " (" + d.reason + ")";
        if (!d.eat) {
            return;
        }
        for (InventoryFood.Slot s : slots) {
            if (s.food.id.equals(d.food.id)) {
                if (s.slot >= 9) {
                    ctx.playerController().windowClick(me.inventoryMenu.containerId, s.slot, me.getInventory().getSelectedSlot(), ContainerInput.SWAP, me);
                    return; // eat next tick from the hotbar
                }
                me.getInventory().setSelectedSlot(s.slot);
                logDebug("Eating " + d.food + ": " + d.reason);
                eatingFor = 0;
                holdUse();
                return;
            }
        }
    }

    private void updatePrediction(HungerModel model) {
        PathExecutor cur = baritone.getPathingBehavior().getCurrent();
        if (cur == null) {
            prediction = null;
            return;
        }
        IPath path = cur.getPath();
        boolean sprint = Baritone.settings().allowSprint.value && !foodSaving();
        prediction = model.predict(PathHunger.segments(path.movements(), cur.getPosition(), sprint));
    }

    private EatPlanner.Pause pause(LocalPlayer me) {
        if (!baritone.getPathingBehavior().isPathing()) {
            return baritone.getPathingBehavior().getInProgress().isPresent() ? EatPlanner.Pause.PATH_SEARCH : EatPlanner.Pause.IDLE;
        }
        if (baritone.getPathingBehavior().getInProgress().isPresent() && baritone.getPathingBehavior().getCurrent() == null) {
            return EatPlanner.Pause.PATH_SEARCH;
        }
        if (me.onClimbable()) {
            return EatPlanner.Pause.LADDER;
        }
        if (!me.onGround() && me.fallDistance > 4 && me.getDeltaMovement().y < -0.5) {
            return EatPlanner.Pause.FALLING;
        }
        return EatPlanner.Pause.NONE;
    }

    /** Processes that eat on their own (or need the use key for a shield) keep the use key. */
    private boolean foreignProcess() {
        Optional<IBaritoneProcess> p = baritone.getPathingControlManager().mostRecentInControl();
        if (!p.isPresent()) {
            return false;
        }
        String n = p.get().getClass().getSimpleName();
        return n.contains("Bastion") || n.contains("Pve") || n.contains("Pvp") || n.contains("Combat") || n.contains("Farm") || n.contains("Builder");
    }

    private void holdUse() {
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        ctx.minecraft().options.keyUse.setDown(true);
    }

    private void stopEating() {
        if (eatingFor >= 0) {
            eatingFor = -1;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            if (ctx.minecraft() != null) {
                ctx.minecraft().options.keyUse.setDown(false);
            }
        }
    }
}
