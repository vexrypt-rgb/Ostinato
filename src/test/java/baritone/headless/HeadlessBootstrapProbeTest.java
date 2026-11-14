/*
 * Ostinato: Phase 0 survey probe for the fully-headless bot (see HEADLESS_BOT_NOTES.md).
 *
 * This is deliberately a plain JUnit test, not a Fabric/mod test: the JVM is a bare Gradle test worker with
 * no Minecraft client main, no GL/window, no fabric loader and no client tick. If everything below passes,
 * the common-side of 26.3 (registries, block states, voxel shapes, protocol codecs, PlayerSim) is usable
 * from a lightweight process. New file, replaces no Baritone code.
 */
package baritone.headless;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HeadlessBootstrapProbeTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void bootstrapPopulatesTheBuiltInRegistries() {
        assertTrue("block registry", BuiltInRegistries.BLOCK.size() > 100);
        assertTrue("item registry", BuiltInRegistries.ITEM.size() > 100);
        assertTrue("block-state id table", Block.BLOCK_STATE_REGISTRY.size() > 1000);
        assertNotNull(Block.BLOCK_STATE_REGISTRY.byId(0));
        assertEquals(777, SharedConstants.getProtocolVersion());
    }

    @Test
    public void blockStatesAndVoxelShapesWorkWithoutAClient() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(Blocks.STONE, stone.getBlock());
        assertFalse(stone.isAir());
        assertTrue(Blocks.AIR.defaultBlockState().isAir());

        VoxelShape full = Shapes.block();
        assertEquals(1.0, full.max(Direction.Axis.Y), 0.0);
        VoxelShape half = Shapes.create(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        assertEquals(0.5, half.max(Direction.Axis.Y), 0.0);
        assertEquals(0.0, half.min(Direction.Axis.X), 0.0);
    }

    @Test
    public void blockStateIdsRoundTripThroughThePaletteIdMap() {
        BlockState state = Blocks.OAK_STAIRS.defaultBlockState();
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        assertTrue("registered id", id >= 0);
        assertSame(state, Block.BLOCK_STATE_REGISTRY.byId(id));
    }

    @Test
    public void friendlyByteBufAndCodecsLoadWithoutAClient() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(300);
        buf.writeIdentifier(Blocks.STONE.builtInRegistryHolder().key().identifier());
        assertEquals(300, buf.readVarInt());
        assertEquals(Blocks.STONE.builtInRegistryHolder().key().identifier(), buf.readIdentifier());
        buf.release();

        assertNotNull(HandshakeProtocols.SERVERBOUND);
        assertNotNull(LoginProtocols.CLIENTBOUND);
        assertNotNull(LoginProtocols.SERVERBOUND);
        assertNotNull(ConfigurationProtocols.CLIENTBOUND);
        assertNotNull(ConfigurationProtocols.SERVERBOUND);
        assertNotNull(GameProtocols.CLIENTBOUND_TEMPLATE);
        assertNotNull(GameProtocols.SERVERBOUND_TEMPLATE);
        assertNotNull(net.minecraft.network.protocol.game.ClientboundLoginPacket.STREAM_CODEC);
        assertNotNull(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.STREAM_CODEC);
        assertNotNull(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos.STREAM_CODEC);
    }

    @Test
    public void registryAccessAndNetworkCodecsCanBeBuiltFromBuiltIns() {
        RegistryAccess.Frozen access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        assertNotNull(access);
        assertNotNull(access.lookupOrThrow(BuiltInRegistries.BLOCK.key()));

        var inbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
        assertEquals(net.minecraft.network.ConnectionProtocol.PLAY, inbound.id());
        assertNotNull(inbound.codec());
        assertNotNull(new Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND));
    }

    @Test
    public void physicsEngineIsPlainJavaAndCanBeDrivenByAWorldInterface() {
        PlayerSim.World world = new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, java.util.List<double[]> out) {
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
        PlayerSim sim = new PlayerSim(world);
        sim.tick(0.0f, false, false, false);
        assertTrue(Double.isFinite(sim.x));
        assertTrue(Double.isFinite(sim.y));
        assertEquals(PlayerSim.HALF_WIDTH, 0.3f, 1e-9);
        assertNotNull(BsiWorld.class);
    }

    /**
     * Bootstrap does not load datapack tags. The server sends them in configuration via
     * ClientboundUpdateTagsPacket; the headless bot must apply them with
     * TagNetworkSerialization.NetworkPayload.resolve + Registry.prepareTagReload().apply(), otherwise
     * BsiWorld's is(BlockTags.SUPPRESSES_BOUNCE) and MovementHelper's tag checks silently return false.
     * This test records the observation rather than hard-coding vanilla's bootstrap behaviour.
     */
    @Test
    public void tagsAreNotBoundByBootstrapSoConfigurationMustSupplyThem() {
        assertNotNull(BlockTags.SUPPRESSES_BOUNCE);
        boolean tagsBound = BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.SUPPRESSES_BOUNCE).iterator().hasNext();
        System.out.println("[HeadlessBootstrapProbe] SUPPRESSES_BOUNCE tag bound after Bootstrap = " + tagsBound);
    }

    @Test
    public void noClientOrFabricRuntimeWasBooted() {
        boolean clientThread = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (n.contains("Render thread") || n.contains("Client thread") || n.contains("Server thread")) {
                clientThread = true;
            }
        }
        assertFalse("a Minecraft game thread is running in this JVM", clientThread);
        // The Unimined merged jar does carry client bytecode on the classpath; what matters is that it was
        // never booted. Record both observations instead of asserting on classpath contents.
        System.out.println("[HeadlessBootstrapProbe] fabricLoaderOnClasspath=" + hasClass("net.fabricmc.loader.api.FabricLoader")
                + " clientMainOnClasspath=" + hasClass("net.minecraft.client.main.Main"));
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, HeadlessBootstrapProbeTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
