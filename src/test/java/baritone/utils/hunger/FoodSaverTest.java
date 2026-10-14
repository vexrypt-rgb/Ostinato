package baritone.utils.hunger;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Ostinato code. */
public class FoodSaverTest {

    @Test
    public void entersWhenRouteCannotBeCoveredAndExitsWhenRestored() {
        FoodSaver s = new FoodSaver();
        HungerModel m = new HungerModel(12, 0, 0, 20);
        HungerModel.Prediction long_ = m.predict(Collections.singletonList(HungerModel.Segment.sprint(400, 1500)));
        assertTrue(long_.sprintStopsAt > 0);
        assertTrue(s.update(m, long_, 0, false));
        // still low: stays on even with a little food
        assertTrue(s.update(new HungerModel(12, 0, 0, 20), null, 0, false));
        // ate: 20 food, food carried
        assertFalse(s.update(new HungerModel(20, 10, 0, 20), null, 20, false));
    }

    @Test
    public void enoughFoodCarriedKeepsSprinting() {
        FoodSaver s = new FoodSaver();
        HungerModel m = new HungerModel(12, 0, 0, 20);
        HungerModel.Prediction p = m.predict(Collections.singletonList(HungerModel.Segment.sprint(400, 1500)));
        assertFalse(s.update(m, p, 40, false));
    }

    @Test
    public void manualAndLowFood() {
        FoodSaver s = new FoodSaver();
        assertTrue(s.update(new HungerModel(20, 5, 0, 20), null, 50, true));
        assertFalse(s.update(new HungerModel(20, 5, 0, 20), null, 50, false));
        assertTrue(s.update(new HungerModel(6, 0, 0, 20), null, 0, false));
    }

    @Test
    public void attacksInFoodSaving() {
        assertTrue(FoodSaver.allowAttack(false, false, 0.2));
        assertFalse(FoodSaver.allowAttack(true, false, 1));
        assertFalse(FoodSaver.allowAttack(true, true, 0.5));
        assertTrue(FoodSaver.allowAttack(true, true, 0.95));
    }
}
