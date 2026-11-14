/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 */
package baritone.headless;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Clientbound packet listeners backed by a JDK dynamic proxy.
 *
 * <p>{@code ClientGamePacketListener} has 127 abstract {@code handleXxx} methods in 26.3 and
 * {@code ClientConfigurationPacketListener} / {@code ClientLoginPacketListener} add more. Writing an
 * adapter with a no-op stub per method would have to be regenerated on every protocol change; the
 * proxy instead dispatches every {@code handleXxx(Packet)} call to one handler by the packet's
 * runtime type, which is exactly what {@code Packet.handle(listener)} invokes.</p>
 *
 * <p>Default interface methods are answered explicitly rather than through
 * {@link InvocationHandler#invokeDefault}: a JDK proxy routes default methods to the invocation
 * handler too, and {@link Connection} calls {@code flow()}, {@code protocol()},
 * {@code isAcceptingMessages()} and {@code shouldHandleMessage()} on every inbound packet. The
 * implementations below are the same ones the interfaces declare by default.</p>
 */
public final class HeadlessPacketListeners {

    /** Receives every clientbound packet; the bot decides what to do with it. */
    public interface Handler {
        void onPacket(Object packet);

        void onDisconnect(String reason);
    }

    private HeadlessPacketListeners() {
    }

    public static <T extends PacketListener> T create(Class<T> iface, ConnectionProtocol protocol, Handler handler) {
        InvocationHandler ih = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "flow":
                    return PacketFlow.CLIENTBOUND;
                case "protocol":
                    return protocol;
                case "isAcceptingMessages":
                case "shouldHandleMessage":
                    return true;
                case "onDisconnect":
                    handler.onDisconnect(String.valueOf(args == null ? null : args[0]));
                    return null;
                case "onPacketError":
                    // Swallowing here would hide a codec mismatch, so print it loudly. Vanilla's
                    // default would throw a ReportedException and disconnect the channel.
                    System.err.println("[HeadlessBot] packet error: " + (args != null && args.length > 1 ? args[1] : ""));
                    if (args != null && args.length > 1 && args[1] instanceof Throwable) {
                        ((Throwable) args[1]).printStackTrace(System.err);
                    }
                    return null;
                case "createDisconnectionInfo":
                    // Ostinato: Connection never logs the cause of a decode failure, and the reason component
                    // only carries the top message. Print the throwable so a codec mismatch is diagnosable.
                    if (args != null && args.length > 1 && args[1] instanceof Throwable) {
                        System.err.println("[HeadlessBot] disconnect cause:");
                        ((Throwable) args[1]).printStackTrace(System.err);
                    }
                    // Same as PacketListener's default implementation.
                    return new DisconnectionDetails((Component) args[0]);
                case "fillCrashReport":
                case "fillListenerSpecificCrashDetails":
                    return null;
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "HeadlessListener[" + protocol + "]";
                default:
                    break;
            }
            if (args != null && args.length == 1) {
                handler.onPacket(args[0]);
            } else if (args != null && args.length > 1) {
                handler.onPacket(args[0]);
            }
            return defaultValue(method.getReturnType());
        };
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, ih));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
