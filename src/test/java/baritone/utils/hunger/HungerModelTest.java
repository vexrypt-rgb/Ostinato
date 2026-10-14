package baritone.utils.hunger;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato code: HungerModel against the 26.3 FoodData / FoodConstants / ServerPlayer numbers. */
public class HungerModelTest {

    @Test
    public void vanillaConstants() {
        assertEquals(0.1F, HungerModel.EXHAUSTION_SPRINT, 0);
        assertEquals(0.05F, HungerModel.EXHAUSTION_JUMP, 0);
        assertEquals(0.2F, HungerModel.EXHAUSTION_SPRINT_JUMP, 0);
        assertEquals(0.005F, HungerModel.EXHAUSTION_MINE, 0);
        assertEquals(0.1F, HungerModel.EXHAUSTION_ATTACK, 0);
        assertEquals(0.01F, HungerModel.EXHAUSTION_SWIM, 0);
        assertEquals(6.0F, HungerModel.EXHAUSTION_HEAL, 0);
        assertEquals(32, HungerModel.EAT_TICKS);
        assertEquals(16, VanillaFoods.get("dried_kelp").eatTicks);
        assertEquals(1.0F, HungerModel.attackExhaustion(10), 1e-6);
        assertEquals(2.0F, HungerModel.sprintExhaustion(20), 1e-6);
    }

    @Test
    public void foodTable() {
        assertEquals(12.8F, VanillaFoods.get("cooked_beef").saturation, 1e-4);
        assertEquals(14.4F, VanillaFoods.get("golden_carrot").saturation, 1e-4);
        assertEquals(6.0F, VanillaFoods.get("bread").saturation, 1e-4);
        assertEquals(9.6F, VanillaFoods.get("golden_apple").saturation, 1e-4);
        assertEquals(1.2F, VanillaFoods.get("honey_bottle").saturation, 1e-4);
        assertEquals(0.6F, VanillaFoods.get("dried_kelp").saturation, 1e-4);
        assertEquals(10, VanillaFoods.get("rabbit_stew").nutrition);
        // golden apple: Regeneration II (1 hp / 25 ticks) for 5 s = 4 hp, Absorption I = 4 hp
        assertEquals(4F, VanillaFoods.get("golden_apple").regenHealing(), 0);
        assertEquals(4F, VanillaFoods.get("golden_apple").absorption, 0);
        // enchanted: Regeneration II 20 s = 16 hp, Absorption IV = 16 hp
        assertEquals(16F, VanillaFoods.get("enchanted_golden_apple").regenHealing(), 0);
        assertTrue(VanillaFoods.get("golden_apple").alwaysEdible);
        assertTrue(VanillaFoods.get("rotten_flesh").risky);
    }

    @Test
    public void exhaustionDropsOnlyPastFour() {
        HungerModel m = new HungerModel(20, 0, 4.0F, 20);
        m.tick();
        assertEquals(20, m.food);
        m.addExhaustion(0.01F);
        m.tick();
        assertEquals(19, m.food);
        HungerModel s = new HungerModel(20, 0.5F, 4.5F, 20);
        s.tick();
        assertEquals(0F, s.saturation, 0); // a fractional point still takes a whole drop
        assertEquals(20, s.food);
        HungerModel cap = new HungerModel(20, 5, 0, 20);
        cap.addExhaustion(100);
        assertEquals(40F, cap.exhaustion, 0);
    }

    @Test
    public void eatClampsSaturationToFood() {
        HungerModel m = new HungerModel(18, 3, 0, 20);
        m.eat(VanillaFoods.get("cooked_beef"));
        assertEquals(20, m.food);
        assertEquals(15.8F, m.saturation, 1e-4);
        HungerModel low = new HungerModel(2, 0, 0, 20);
        low.eat(VanillaFoods.get("golden_carrot"));
        assertEquals(8, low.food);
        assertEquals(8F, low.saturation, 1e-4); // 14.4 capped at food 8
    }

    @Test
    public void saturationFastRegen() {
        // food 20, saturation >= 6: 1 hp every 10 ticks for 6 exhaustion each
        HungerModel m = new HungerModel(20, 20, 0, 10);
        assertEquals(10, m.ticksToHeal(1, 1000));
        assertEquals(100, m.ticksToHeal(10, 1000));
        HungerModel weak = new HungerModel(20, 3, 0, 10);
        weak.tickTimer = 9;
        weak.tick();
        assertEquals(10.5F, weak.health, 1e-5); // min(sat, 6) / 6
    }

    @Test
    public void normalRegenAt18() {
        HungerModel m = new HungerModel(18, 0, 0, 19);
        assertEquals(80, m.ticksToHeal(1, 1000));
        HungerModel no = new HungerModel(17, 5, 0, 10);
        assertEquals(-1, no.ticksToHeal(1, 1000));
    }

    @Test
    public void starvationNormalStopsAtOneHp() {
        HungerModel m = new HungerModel(0, 0, 0, 3);
        for (int i = 0; i < 80; i++) m.tick();
        assertEquals(2F, m.health, 0);
        for (int i = 0; i < 800; i++) m.tick();
        assertEquals(1F, m.health, 0);
        HungerModel easy = new HungerModel(0, 0, 0, 12);
        easy.difficulty = HungerModel.Difficulty.EASY;
        for (int i = 0; i < 2000; i++) easy.tick();
        assertEquals(10F, easy.health, 0);
    }

    @Test
    public void sprintStopsAfter760MetresFromFreshSpawn() {
        // spawn state: 20 food, 5 saturation. (5 + 14) drops * 4 = 76 exhaustion = 760 m sprinting
        HungerModel m = new HungerModel(20, 5, 0, 20);
        assertEquals(76F, m.exhaustionUntilSprintStops(), 1e-3);
        int ticks = 4000;
        double metres = 0.2806 * ticks; // sprint speed on flat ground ~5.6 m/s
        HungerModel.Prediction p = m.predict(Collections.singletonList(HungerModel.Segment.sprint(metres, ticks)));
        int expected = (int) Math.ceil(76 / (0.1 * 0.2806));
        assertTrue("sprint stops at " + p.sprintStopsAt + " expected ~" + expected, Math.abs(p.sprintStopsAt - expected) <= 2);
        assertTrue(p.endFood <= 6);
    }

    @Test
    public void predictionMixesMoves() {
        HungerModel m = new HungerModel(8, 0, 0, 20);
        List<HungerModel.Segment> segs = java.util.Arrays.asList(
                HungerModel.Segment.jump(true, 10), HungerModel.Segment.mine(100, 200),
                HungerModel.Segment.swim(50, 200), HungerModel.Segment.attack(20, 100), HungerModel.Segment.walk(100));
        HungerModel.Prediction p = m.predict(segs);
        assertEquals(0.2F + 0.5F + 0.5F + 2.0F, p.exhaustionAdded, 1e-4);
        assertEquals(8, p.endFood); // 3.2 exhaustion, no drop yet
        HungerModel.Prediction q = m.predict(Collections.singletonList(HungerModel.Segment.sprint(81, 300)));
        assertEquals(6, q.endFood);
        assertTrue(q.sprintStopsAt > 0);
        assertEquals(8, m.food); // predict works on a copy
    }

    @Test
    public void damageExhaustion() {
        HungerModel m = new HungerModel(20, 0, 3.95F, 20);
        m.damage(2, HungerModel.EXHAUSTION_DAMAGE);
        m.tick();
        assertEquals(19, m.food);
        assertEquals(18F, m.health, 1e-5);
    }
}
