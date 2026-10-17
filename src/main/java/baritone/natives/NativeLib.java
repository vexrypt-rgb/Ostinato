package baritone.natives;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Ostinato: the Rust library {@code ostinato-native} (see {@code docs/RUST_NATIVE.md}), loaded once through the FFM API
 * on first use. Every caller has a Java path that gives the same answer, and takes it whenever {@link #get()} is null:
 * no library for this platform, switched off with {@code -Dostinato.native=false}, or anything at all going wrong
 * while loading or calling it.
 * <p>
 * The library ships in the jar at {@code natives/ostinato/<os>-<arch>/} and is copied out to a folder named after its
 * hash under the temp directory, so two games or two versions never fight over one file. {@code
 * -Dostinato.native.path=<file>} loads a given file instead (development builds).
 */
public final class NativeLib {

    /** Must match {@code ABI_VERSION} in {@code native/src/lib.rs}. */
    public static final int ABI_VERSION = 2;

    /** Return codes shared by the exports. */
    public static final int NONE = -1, OUT_OF_REGION = -2, BAD_ARGS = -3, UNSUPPORTED = -4, PANIC = -5, TOO_SMALL = -6;

    /** {@code ost_tas_search}: see {@code native/src/lib.rs}. */
    public final MethodHandle tasSearch;
    /** {@code ost_sim_run}: rolls the simulator over given inputs, for the parity tests. */
    public final MethodHandle simRun;
    /** {@code ost_sincos}: {@code StrictMath.sin} and {@code cos}, for the parity tests. */
    public final MethodHandle sincos;
    public final Path path;

    private NativeLib(Path path, MethodHandle tasSearch, MethodHandle simRun, MethodHandle sincos) {
        this.path = path;
        this.tasSearch = tasSearch;
        this.simRun = simRun;
        this.sincos = sincos;
    }

    private static volatile boolean loaded;
    private static volatile NativeLib lib;
    private static volatile String status = "not loaded yet";

    /** The loaded library, or null: then use the Java code. */
    public static NativeLib get() {
        if (!loaded) {
            synchronized (NativeLib.class) {
                if (!loaded) {
                    lib = load();
                    loaded = true;
                }
            }
        }
        return lib;
    }

    /** One line on what happened: loaded from where, or why not. */
    public static String status() {
        get();
        return status;
    }

    /** Stops using the library for the rest of the session, after it misbehaved. */
    public static void disable(String why) {
        get();
        if (lib != null) {
            lib = null;
            status = "disabled: " + why;
            log(status);
        }
    }

    private static void log(String msg) {
        System.out.println("[Ostinato] native library " + msg);
    }

    /** {@code <os>-<arch>} of the jar's folder for this machine, or null where none is built. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.startsWith("windows") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : null;
        String a = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
        return o == null || a == null ? null : o + "-" + a;
    }

    /** The library's file name on this OS. */
    public static String fileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "ostinato_native.dll" : os.contains("mac") || os.contains("darwin") ? "libostinato_native.dylib" : "libostinato_native.so";
    }

    private static NativeLib load() {
        try {
            if ("false".equalsIgnoreCase(System.getProperty("ostinato.native"))) {
                status = "off (-Dostinato.native=false)";
                return null;
            }
            Path path;
            String override = System.getProperty("ostinato.native.path");
            if (override != null && !override.isEmpty()) {
                path = Path.of(override);
            } else {
                String dir = platform();
                if (dir == null) {
                    status = "not built for " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
                    return null;
                }
                byte[] bytes;
                try (InputStream in = NativeLib.class.getResourceAsStream("/natives/ostinato/" + dir + "/" + fileName())) {
                    if (in == null) {
                        status = "not in this jar for " + dir;
                        return null;
                    }
                    bytes = in.readAllBytes();
                }
                path = extract(bytes);
            }
            SymbolLookup symbols = SymbolLookup.libraryLookup(path, Arena.global());
            Linker linker = Linker.nativeLinker();
            MethodHandle abi = linker.downcallHandle(symbols.find("ost_abi_version").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
            int version = (int) abi.invoke();
            if (version != ABI_VERSION) {
                status = "has ABI " + version + ", expected " + ABI_VERSION;
                log(status);
                return null;
            }
            MethodHandle tas = linker.downcallHandle(symbols.find("ost_tas_search").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, // snapshot
                    ADDRESS, ADDRESS, ADDRESS, JAVA_INT, // state, params, turns
                    ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)); // out
            MethodHandle sim = linker.downcallHandle(symbols.find("ost_sim_run").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                    ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle sc = linker.downcallHandle(symbols.find("ost_sincos").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            status = "loaded from " + path;
            log(status);
            return new NativeLib(path, tas, sim, sc);
        } catch (Throwable t) {
            status = "failed to load: " + t;
            log(status);
            return null;
        }
    }

    /** Copies the library out to {@code <tmp>/ostinato-native/<hash>/}, or reuses an identical copy already there. */
    private static Path extract(byte[] bytes) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ostinato-native", hash);
        Path file = dir.resolve(fileName());
        if (same(file, bytes)) {
            return file;
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "lib", ".part");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // another game loaded the same file meanwhile and Windows holds it open: fine if it is the same bytes
            if (!same(file, bytes)) {
                throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return file;
    }

    private static boolean same(Path file, byte[] bytes) {
        try {
            return Files.isRegularFile(file) && Files.size(file) == bytes.length && Arrays.equals(Files.readAllBytes(file), bytes);
        } catch (IOException e) {
            return false;
        }
    }
}
