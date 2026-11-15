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
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
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
        bindDefaultComponents(result);
        built = result;
        return result;
    }

    /**
     * Ostinato: 26.3 builds each item's/entity type's default DataComponentMap in
     * DataComponentInitializers, and the client applies those pending maps at startup (and again on a
     * memory connection through RegistryDataCollector.updateComponents). A plain JVM never runs that
     * client init, so without this every ItemStack decoded from a packet throws
     * "Components not bound yet" (for example an item entity's tracked DATA_ITEM in SetEntityData).
     */
    /**
     * Ostinato: {@code Level}'s constructor calls {@code PalettedContainerFactory.create} and
     * {@code DamageSources}, which need real {@code Registry} objects for biome and damage_type.
     * Bootstrap's access does not have those datapack registries. A live bot already rebuilt them
     * from configuration packets; that access is kept. Otherwise the vanilla data-gen world
     * registries are copied into {@code MappedRegistry}s and merged over the built-in access.
     * Packet decoding keeps using the bot's own access either way.
     */
    public static RegistryAccess forLevel(RegistryAccess access) {
        if (access != null
                && access.lookup(Registries.BIOME).isPresent()
                && access.lookup(Registries.DAMAGE_TYPE).isPresent()) {
            return access;
        }
        RegistryAccess.Frozen cached = vanillaBody;
        if (cached != null) {
            return cached;
        }
        synchronized (HeadlessRegistries.class) {
            if (vanillaBody != null) {
                return vanillaBody;
            }
            Map<ResourceKey<? extends Registry<?>>, Registry<?>> map = new LinkedHashMap<>();
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries()
                    .forEach(entry -> map.put(entry.key(), entry.value()));
            // Only the two registries Level's constructor looks up. Copying every worldgen
            // registry re-registers values that are already in a built-in registry.
            HolderLookup.Provider world = VanillaRegistries.createWorldLookup();
            copyLookup(map, world, Registries.BIOME);
            copyLookup(map, world, Registries.DAMAGE_TYPE);
            vanillaBody = new RegistryAccess.ImmutableRegistryAccess(map).freeze();
            return vanillaBody;
        }
    }

    private static volatile RegistryAccess.Frozen vanillaBody;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T> void copyLookup(Map<ResourceKey<? extends Registry<?>>, Registry<?>> map,
                                       HolderLookup.Provider provider,
                                       ResourceKey<? extends Registry<T>> key) {
        HolderLookup.RegistryLookup<T> lookup = provider.lookupOrThrow(key);
        if (lookup instanceof Registry<?> registry) {
            map.put(registry.key(), registry);
            return;
        }
        MappedRegistry<T> materialized = new MappedRegistry<>(key, lookup.registryLifecycle());
        lookup.listElements().forEach(ref -> {
            if (materialized.get(ref.key()).isEmpty()) {
                materialized.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN);
            }
        });
        materialized.freeze();
        map.put(key, materialized);
    }

    public static void bindDefaultComponents(HolderLookup.Provider access) {
        // Ostinato: a second call (unit test, then a registry build) must not rebind holders.
        for (var pending : net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(access)) {
            pending.forEach((holder, components) -> {
                if (!holder.areComponentsBound()) {
                    holder.bindComponents(components);
                }
            });
        }
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
