/*
 * Ostinato: test-only vanilla datapack fixture. New file, replaces no Baritone code.
 *
 * The dedicated 26.3 server loads its datapack from the merged jar: one JSON file per registry
 * element plus the tag files. A plain JVM has no resource pack manager, so this reads the same
 * entries straight out of the classpath jar and serves them through the vanilla ResourceManager
 * interface. That lets tests build a real RegistryAccess (elements + tags) and exercise code that
 * needs the client's startup state, without a client or a live server.
 */
package baritone.headless;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class VanillaDatapackResourceManager implements ResourceManager, AutoCloseable {

    private static final String SENTINEL = "data/minecraft/worldgen/biome/plains.json";

    /** Same shape the client's vanilla pack reports: no known pack, so registration info is experimental. */
    private static final PackResources SOURCE = new PackResources() {
        private final PackLocationInfo location = new PackLocationInfo(
                "vanilla-datapack-test", net.minecraft.network.chat.Component.empty(),
                PackSource.BUILT_IN, Optional.empty());

        @Override
        public PackLocationInfo location() {
            return location;
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of("minecraft");
        }

        @Override
        public void close() {
        }
    };

    private final Map<String, byte[]> files;
    private final Set<String> namespaces;

    private VanillaDatapackResourceManager(Map<String, byte[]> files) {
        this.files = files;
        Set<String> found = new LinkedHashSet<>();
        for (String path : files.keySet()) {
            int slash = path.indexOf('/', "data/".length());
            if (slash > 0) {
                found.add(path.substring("data/".length(), slash));
            }
        }
        this.namespaces = Set.copyOf(found);
    }

    static VanillaDatapackResourceManager fromClasspath() {
        URL url = ClassLoader.getSystemResource(SENTINEL);
        if (url == null || !"jar".equals(url.getProtocol())) {
            throw new IllegalStateException("vanilla datapack is not on the classpath as a jar: " + url);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            File jarPath = new File(connection.getJarFileURL().toURI());
            try (JarFile jar = new JarFile(jarPath)) {
                for (JarEntry entry : jar.stream().toList()) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith("data/") && name.endsWith(".json")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            files.put(name, in.readAllBytes());
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read the vanilla datapack from " + url, e);
        }
        return new VanillaDatapackResourceManager(files);
    }

    @Override
    public Optional<Resource> getResource(Identifier id) {
        byte[] bytes = files.get("data/" + id.getNamespace() + "/" + id.getPath());
        return bytes == null ? Optional.empty()
                : Optional.of(new Resource(SOURCE, () -> new ByteArrayInputStream(bytes)));
    }

    @Override
    public Set<String> getNamespaces() {
        return namespaces;
    }

    @Override
    public List<Resource> getResourceStack(Identifier id) {
        return getResource(id).map(List::of).orElseGet(List::of);
    }

    @Override
    public Map<Identifier, Resource> listResources(String directory, Selector selector) {
        Map<Identifier, Resource> out = new LinkedHashMap<>();
        String prefix = directory + "/";
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey().substring("data/".length());
            int slash = path.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String namespace = path.substring(0, slash);
            String relative = path.substring(slash + 1);
            if (!relative.startsWith(prefix)) {
                continue;
            }
            Identifier id = Identifier.fromNamespaceAndPath(namespace, relative);
            byte[] bytes = entry.getValue();
            if (selector.isIncluded(id)) {
                out.put(id, new Resource(SOURCE, () -> new ByteArrayInputStream(bytes)));
            }
        }
        return out;
    }

    @Override
    public Map<Identifier, List<Resource>> listResourceStacks(String directory, Selector selector) {
        Map<Identifier, List<Resource>> out = new LinkedHashMap<>();
        listResources(directory, selector).forEach((id, resource) -> out.put(id, List.of(resource)));
        return out;
    }

    @Override
    public Stream<PackResources> listPacks() {
        return Stream.of(SOURCE);
    }

    @Override
    public void close() {
        files.clear();
    }
}
