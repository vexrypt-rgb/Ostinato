/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * The transport seam exists so the login -> configuration -> play state machine can be unit tested
 * against a recording fake without a socket, while the live bot uses vanilla's own Connection.
 */
package baritone.headless;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;

/**
 * Everything the protocol state machine needs from the network. The live implementation is a thin
 * wrapper over {@link Connection}; nothing here touches {@code net.minecraft.client.*}.
 */
public interface HeadlessTransport {

    /** Vanilla's own handshake + inbound LOGIN setup, run once the channel is connected. */
    void initiate(String host, int port, ClientLoginPacketListener loginListener);

    void send(Packet<?> packet);

    void setupInbound(ProtocolInfo<?> info, PacketListener listener);

    void setupOutbound(ProtocolInfo<?> info);

    void setupCompression(int threshold, boolean rejectBadPackets);

    void tick();

    boolean isConnected();

    /** Live transport. */
    final class ConnectionTransport implements HeadlessTransport {

        private final Connection connection;

        public ConnectionTransport(Connection connection) {
            this.connection = connection;
        }

        public Connection connection() {
            return connection;
        }

        @Override
        public void initiate(String host, int port, ClientLoginPacketListener loginListener) {
            connection.initiateServerboundPlayConnection(host, port, loginListener);
        }

        @Override
        public void send(Packet<?> packet) {
            connection.send(packet);
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void setupInbound(ProtocolInfo<?> info, PacketListener listener) {
            // Ostinato: ProtocolInfo<T>/T are erased here; the listener proxy implements the exact
            // clientbound interface for this protocol, and Connection.validateListener checks
            // flow() and protocol() before accepting it.
            connection.setupInboundProtocol((ProtocolInfo) info, (PacketListener) listener);
        }

        @Override
        public void setupOutbound(ProtocolInfo<?> info) {
            connection.setupOutboundProtocol(info);
        }

        @Override
        public void setupCompression(int threshold, boolean rejectBadPackets) {
            connection.setupCompression(threshold, rejectBadPackets);
        }

        @Override
        public void tick() {
            connection.tick();
        }

        @Override
        public boolean isConnected() {
            return connection.isConnected();
        }
    }
}
