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
