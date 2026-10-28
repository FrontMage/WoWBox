package com.winlator.winhandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Called under WinHandler's action-queue lock so announcement and enqueue order agree. */
final class GamepadBridgeSession {
    static final int STATE_BYTES = 13;
    private static final int MIN_PACKET_BYTES = 64;

    static final class Snapshot {
        final int id;
        private final String sourceKey;
        private final String name;
        private final byte[] state;

        Snapshot(int id, String sourceKey, String name, byte[] state) {
            if (id <= 0 || state.length != STATE_BYTES) {
                throw new IllegalArgumentException("A connected gamepad needs an id and complete state");
            }
            this.id = id;
            this.sourceKey = sourceKey;
            this.name = name == null ? "" : name;
            this.state = state.clone();
        }

        private Snapshot() {
            id = 0;
            sourceKey = "";
            name = "";
            state = new byte[STATE_BYTES];
        }

        static Snapshot unavailable() {
            return new Snapshot();
        }

        private boolean sameSource(Snapshot other) {
            return other != null && id == other.id && sourceKey.equals(other.sourceKey)
                    && name.equals(other.name);
        }
    }

    static final class Packet {
        final int port;
        private final byte[] bytes;

        private Packet(int port, byte[] bytes) {
            this.port = port;
            this.bytes = bytes;
        }

        void writeTo(ByteBuffer destination) {
            destination.put(bytes);
        }
    }

    private static final class Client {
        final int port;
        final boolean xinput;
        Snapshot announced;
        byte announcedFlags;

        Client(int port, boolean xinput) {
            this.port = port;
            this.xinput = xinput;
        }
    }

    private final Map<Integer, Client> clients = new LinkedHashMap<>();

    List<Packet> discover(int port, boolean xinput, boolean notify,
                          byte mapperType, Snapshot snapshot) {
        Client client = new Client(port, xinput);
        if (notify) clients.put(port, client);
        else clients.remove(port);
        List<Packet> packets = new ArrayList<>();
        if (notify) appendCurrent(packets, client, mapperType, snapshot, true);
        else packets.add(metadataPacket(port,
                GamepadProtocol.encodeGamepadType(xinput, mapperType), snapshot));
        return packets;
    }

    List<Packet> publish(byte mapperType, Snapshot snapshot) {
        List<Packet> packets = new ArrayList<>();
        for (Client client : clients.values()) {
            appendCurrent(packets, client, mapperType, snapshot, false);
        }
        return packets;
    }

    List<Packet> poll(int port, int requestedId, byte mapperType, Snapshot snapshot) {
        List<Packet> packets = new ArrayList<>();
        Client client = clients.get(port);
        if (client != null) {
            // An old polling id must not disconnect a subscriber during a usable handoff.
            appendCurrent(packets, client, mapperType, snapshot, requestedId != snapshot.id);
        }
        else {
            packets.add(statePacket(port, requestedId == snapshot.id
                    ? snapshot : Snapshot.unavailable()));
        }
        return packets;
    }

    boolean isEmpty() {
        return clients.isEmpty();
    }

    void clear() {
        clients.clear();
    }

    private static void appendCurrent(List<Packet> packets, Client client, byte mapperType,
                                      Snapshot snapshot, boolean forceMetadata) {
        byte flags = GamepadProtocol.encodeGamepadType(client.xinput, mapperType);
        if (forceMetadata || !snapshot.sameSource(client.announced)
                || flags != client.announcedFlags) {
            packets.add(metadataPacket(client.port, flags, snapshot));
            client.announced = snapshot;
            client.announcedFlags = flags;
        }
        packets.add(statePacket(client.port, snapshot));
    }

    private static Packet metadataPacket(int port, byte flags, Snapshot snapshot) {
        byte[] name = snapshot.name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(Math.max(MIN_PACKET_BYTES,
                snapshot.id == 0 ? 5 : 10 + name.length)).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(RequestCodes.GET_GAMEPAD).putInt(snapshot.id);
        if (snapshot.id != 0) packet.put(flags).putInt(name.length).put(name);
        return new Packet(port, packet.array());
    }

    private static Packet statePacket(int port, Snapshot snapshot) {
        ByteBuffer packet = ByteBuffer.allocate(MIN_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(RequestCodes.GET_GAMEPAD_STATE).put((byte)(snapshot.id != 0 ? 1 : 0));
        if (snapshot.id != 0) packet.putInt(snapshot.id).put(snapshot.state);
        return new Packet(port, packet.array());
    }
}
