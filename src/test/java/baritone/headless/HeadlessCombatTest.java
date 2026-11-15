/*
 * Ostinato: headless combat parsing, weapon ranking, entity tracking, and reach math.
 */
package baritone.headless;

import net.minecraft.SharedConstants;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeadlessCombatTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!Items.DIRT.builtInRegistryHolder().areComponentsBound()) {
            HeadlessRegistries.bindDefaultComponents(VanillaRegistries.createWorldLookup());
        }
    }

    @Test
    public void diamondSwordBeatsStoneSwordAndFist() {
        assertTrue(HeadlessCombat.weaponScore(new ItemStack(Items.DIAMOND_SWORD))
                > HeadlessCombat.weaponScore(new ItemStack(Items.STONE_SWORD)));
        assertTrue(HeadlessCombat.weaponScore(new ItemStack(Items.IRON_SWORD))
                > HeadlessCombat.weaponScore(new ItemStack(Items.IRON_AXE)));
        assertEquals(0.0, HeadlessCombat.weaponScore(ItemStack.EMPTY), 0.0);
        assertTrue(HeadlessCombat.isFood(new ItemStack(Items.COOKED_BEEF)));
        assertFalse(HeadlessCombat.isFood(new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    public void trackerRemoveClearsTheTarget() {
        HeadlessEntityTracker tracker = new HeadlessEntityTracker();
        tracker.handle(new ClientboundAddEntityPacket(
                7, UUID.randomUUID(), 0, 0, 0, 0f, 0f, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0.0), 1);
        assertEquals(1, tracker.size());
        assertNotNull(tracker.nearest(Vec3.ZERO, "zombie", 48));
        assertTrue(tracker.handle(new ClientboundRemoveEntitiesPacket(7), 1));
        assertEquals(0, tracker.size());
        assertNull(tracker.nearest(Vec3.ZERO, "zombie", 48));
    }

    @Test
    public void vanillaReachUsesTheEntityBoxNotALongerRange() {
        HeadlessEntityTracker tracker = new HeadlessEntityTracker();
        tracker.handle(new ClientboundAddEntityPacket(
                9, UUID.randomUUID(), 8.0, 0.0, 0.0, 0f, 0f, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0.0), 1);
        HeadlessEntityTracker.Tracked tracked = tracker.get(9);
        Vec3 eye = new Vec3(0.0, 1.62, 0.0);
        double reach = 3.0;
        assertTrue(tracked.box().distanceToSqr(eye) > reach * reach);
        tracker.handle(new ClientboundAddEntityPacket(
                10, UUID.randomUUID(), 2.0, 0.0, 0.0, 0f, 0f, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0.0), 1);
        HeadlessEntityTracker.Tracked close = tracker.get(10);
        assertTrue(close.box().distanceToSqr(eye) <= reach * reach);
    }
}
