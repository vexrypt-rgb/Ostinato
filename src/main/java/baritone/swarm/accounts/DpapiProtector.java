package baritone.swarm.accounts;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Locale;

/**
 * Ostinato: Windows DPAPI ({@code CryptProtectData} / {@code CryptUnprotectData}), current user, no prompt.
 * A machine without DPAPI refuses sign-in instead of writing a plaintext token.
 */
public final class DpapiProtector implements SecretProtector {

    private static final int UI_FORBIDDEN = 0x1;
    private static final StructLayout BLOB = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cbData"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pbData")
    );
    private static final VarHandle CB = BLOB.varHandle(MemoryLayout.PathElement.groupElement("cbData"));
    private static final VarHandle PB = BLOB.varHandle(MemoryLayout.PathElement.groupElement("pbData"));

    private final MethodHandle protect;
    private final MethodHandle unprotect;
    private final MethodHandle localFree;

    private DpapiProtector(MethodHandle protect, MethodHandle unprotect, MethodHandle localFree) {
        this.protect = protect;
        this.unprotect = unprotect;
        this.localFree = localFree;
    }

    public static boolean available() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        try {
            platform();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static SecretProtector platform() {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup crypt = SymbolLookup.libraryLookup("crypt32", Arena.global());
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
            FunctionDescriptor cryptDesc = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            MethodHandle protect = linker.downcallHandle(crypt.find("CryptProtectData").orElseThrow(), cryptDesc);
            MethodHandle unprotect = linker.downcallHandle(crypt.find("CryptUnprotectData").orElseThrow(), cryptDesc);
            MethodHandle free = linker.downcallHandle(kernel.find("LocalFree").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            return new DpapiProtector(protect, unprotect, free);
        } catch (Throwable e) {
            throw new IllegalStateException("Windows DPAPI is not available");
        }
    }

    @Override
    public byte[] protect(byte[] plain) {
        return call(protect, plain);
    }

    @Override
    public byte[] unprotect(byte[] blob) {
        return call(unprotect, blob);
    }

    private byte[] call(MethodHandle fn, byte[] in) {
        if (in == null) {
            throw new IllegalStateException("Windows DPAPI is not available");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(in.length, 1));
            if (in.length > 0) {
                data.asSlice(0, in.length).copyFrom(MemorySegment.ofArray(in));
            }
            MemorySegment input = arena.allocate(BLOB);
            CB.set(input, 0L, in.length);
            PB.set(input, 0L, data);
            MemorySegment output = arena.allocate(BLOB);
            int ok = (int) fn.invokeExact(input, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, UI_FORBIDDEN, output);
            if (ok == 0) {
                throw new IllegalStateException("Windows DPAPI is not available");
            }
            int size = (int) CB.get(output, 0L);
            MemorySegment pointer = (MemorySegment) PB.get(output, 0L);
            if (size < 0 || pointer == null || pointer.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Windows DPAPI is not available");
            }
            try {
                return pointer.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                MemorySegment released = (MemorySegment) localFree.invokeExact(pointer);
                if (released != null && !released.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("Windows DPAPI is not available");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Windows DPAPI is not available");
        }
    }

}
