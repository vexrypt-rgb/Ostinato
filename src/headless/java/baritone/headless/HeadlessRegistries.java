/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Common-side replacement for the client-only RegistryDataCollector. Bootstrap does NOT create
 * datapack registries (worldgen/biome, damage_type, ...), so RegistryAccess.fromRegistryOfRegistries
 * cannot decode ClientboundLoginPacket or chunk biome palettes. Vanilla's own RegistryDataLoader,
 * fed by the server's ClientboundRegistryDataPacket / ClientboundUpdateTagsPacket and by the
 * bundled vanilla datapack on the classpath, rebuilds exactly what the client would have.
 */
package baritone.headless;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

public final class HeadlessRegistries {

    /**
     * The Mojmap merged jar contains the vanilla datapack under data/&lt;namespace&gt;/&lt;path&gt;.
     * RegistryDataLoader needs it for registries the server does not send (or sends only tags for).
     * No resource pack manager, no client, no Filesystem: plain classpath reads.
     */
    public static final ResourceProvider CLASSPATH_RESOURCES = id -> {
        String path = "data/" + id.getNamespace() + "/" + id.getPath();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(path)
                : loader.getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            IoSupplier<InputStream> supplier = () -> new ByteArrayInputStream(bytes);
            return Optional.of(new Resource(null, supplier));
        } catch (IOException e) {
            return Optional.empty();
        }
    };

    private static final Executor DIRECT = Runnable::run;

    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> elements =
            new LinkedHashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags =
            new LinkedHashMap<>();
    private volatile RegistryAccess.Frozen built;

    public void appendContents(ClientboundRegistryDataPacket packet) {
        elements.computeIfAbsent(packet.registry(), k -> new ArrayList<>()).addAll(packet.entries());
    }

    public void appendTags(ClientboundUpdateTagsPacket packet) {
        tags.putAll(packet.tags());
    }

    public int registryDataPackets() {
        return elements.size();
    }

    public int tagRegistries() {
        return tags.size();
    }

    public RegistryAccess.Frozen built() {
        return built;
    }

    public RegistryAccess.Frozen build() {
        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        RegistryAccess.Frozen loading = layered.getAccessForLoading(RegistryLayer.RELOADABLE);

        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> network = new HashMap<>();
        elements.forEach((key, entries) -> network.put(key, new RegistryDataLoader.NetworkedRegistryData(
                entries, tags.getOrDefault(key, TagNetworkSerialization.NetworkPayload.EMPTY))));

        List<Registry.PendingTags<?>> pendingStaticTags = new ArrayList<>();
        tags.forEach((key, payload) -> {
            if (payload.isEmpty()) {
                return;
            }
            if (RegistrySynchronization.isNetworkable(key)) {
                network.compute(key, (k, existing) -> new RegistryDataLoader.NetworkedRegistryData(
                        existing == null ? List.of() : existing.elements(), payload));
            } else {
                resolveStaticTags(loading, key, payload, pendingStaticTags);
            }
        });

        List<HolderLookup.RegistryLookup<?>> lookups =
                TagLoader.buildUpdatedLookups(loading, pendingStaticTags);
        RegistryAccess.Frozen reloadable;
        try {
            reloadable = RegistryDataLoader.load(network, CLASSPATH_RESOURCES, lookups,
                    RegistryDataLoader.SYNCHRONIZED_REGISTRIES, DIRECT).join();
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not reconstruct 26.3 registries from "
                    + elements.size() + " config registries and the bundled vanilla datapack", e);
        }
        RegistryAccess.Frozen result = layered.replaceFrom(RegistryLayer.RELOADABLE, reloadable).compositeAccess();
        pendingStaticTags.forEach(Registry.PendingTags::apply);
        built = result;
        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void resolveStaticTags(RegistryAccess.Frozen access,
                                          ResourceKey<? extends Registry<?>> key,
                                          TagNetworkSerialization.NetworkPayload payload,
                                          List<Registry.PendingTags<?>> out) {
        var found = access.lookup((ResourceKey) key);
        if (found.isEmpty()) {
            return;
        }
        Registry registry = (Registry) found.get();
        out.add(registry.prepareTagReload(payload.resolve(registry)));
    }

    /** Convenience for tests and for callers that need a registry id by name. */
    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
