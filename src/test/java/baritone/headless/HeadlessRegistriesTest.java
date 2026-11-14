/*
 * Ostinato: Phase 1 common-side registry reconstruction test. New file, replaces no Baritone code.
 *
 * Bootstrap creates only the 95 static registries. The datapack registries (worldgen/biome,
 * dimension_type, ...) must come from configuration; this test pins down the two pieces the bot
 * relies on: the bundled vanilla datapack on the classpath, and accumulation of the config packets.
 */
package baritone.headless;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

public class HeadlessRegistriesTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void bootstrapAloneDoesNotProvideDatapackRegistries() {
        RegistryAccess.Frozen builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        assertTrue("block registry must be built in", builtIn.lookup(Registries.BLOCK).isPresent());
        assertTrue("biome is a datapack registry in 26.3", builtIn.lookup(Registries.BIOME).isEmpty());
        assertTrue("dimension_type is a datapack registry in 26.3", builtIn.lookup(Registries.DIMENSION_TYPE).isEmpty());
    }

    @Test
    public void theBundledVanillaDatapackIsReadableWithoutAClient() {
        assertTrue("vanilla biome json must be findable on the classpath",
                HeadlessRegistries.CLASSPATH_RESOURCES.getResource(
                        Identifier.fromNamespaceAndPath("minecraft", "worldgen/biome/plains.json")).isPresent());
        assertTrue("a missing datapack file must be Optional.empty()",
                HeadlessRegistries.CLASSPATH_RESOURCES.getResource(
                        Identifier.fromNamespaceAndPath("minecraft", "does/not/exist.json")).isEmpty());
    }

    @Test
    public void buildBindsDefaultItemComponents() {
        // Ostinato: 26.3 binds item/entity default components from DataComponentInitializers during client
        // startup. A plain JVM never runs that step, so ItemStack decode throws "Components not bound yet".
        // Build the access the server builds (elements + tags from the vanilla datapack) and verify the
        // common-side binding produces usable item components.
        try (VanillaDatapackResourceManager datapack = VanillaDatapackResourceManager.fromClasspath()) {
            net.minecraft.core.LayeredRegistryAccess<net.minecraft.server.RegistryLayer> layered =
                    net.minecraft.server.RegistryLayer.createRegistryAccess();
            RegistryAccess.Frozen loading =
                    layered.getAccessForLoading(net.minecraft.server.RegistryLayer.RELOADABLE);
            java.util.List<net.minecraft.core.Registry.PendingTags<?>> staticTags =
                    net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(datapack, loading);
            java.util.List<net.minecraft.core.HolderLookup.RegistryLookup<?>> lookups =
                    net.minecraft.tags.TagLoader.buildUpdatedLookups(loading, staticTags);
            RegistryAccess.Frozen reloadable = net.minecraft.resources.RegistryDataLoader.load(
                    datapack, lookups, net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES,
                    Runnable::run).join();
            staticTags.forEach(net.minecraft.core.Registry.PendingTags::apply);
            RegistryAccess.Frozen access =
                    layered.replaceFrom(net.minecraft.server.RegistryLayer.RELOADABLE, reloadable).compositeAccess();

            HeadlessRegistries.bindDefaultComponents(access);
            net.minecraft.core.Holder<net.minecraft.world.item.Item> stone =
                    BuiltInRegistries.ITEM.wrapAsHolder(net.minecraft.world.item.Items.STONE);
            assertTrue("item holder components must be bound", stone.areComponentsBound());
            assertEquals("stone is a full stack",
                    64, (int) stone.components().getOrDefault(
                            net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 0));
            net.minecraft.core.Holder<net.minecraft.world.item.Item> netheriteScrap =
                    BuiltInRegistries.ITEM.wrapAsHolder(net.minecraft.world.item.Items.NETHERITE_SCRAP);
            assertTrue("fire-resistant items resolve the damage_type/is_fire tag",
                    netheriteScrap.components().has(net.minecraft.core.component.DataComponents.DAMAGE_RESISTANT));
        }
    }

    @Test
    public void configurationRegistryAndTagPacketsAreAccumulated() {
        HeadlessRegistries registries = new HeadlessRegistries();
        ClientboundRegistryDataPacket data = new ClientboundRegistryDataPacket(
                Registries.BIOME,
                List.of(new RegistrySynchronization.PackedRegistryEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "plains"), Optional.empty())));
        registries.appendContents(data);
        registries.appendContents(data);
        registries.appendTags(new ClientboundUpdateTagsPacket(Map.of()));
        assertEquals(1, registries.registryDataPackets());
        assertEquals(0, registries.tagRegistries());
        assertNull("build() has not been called", registries.built());
    }
}
