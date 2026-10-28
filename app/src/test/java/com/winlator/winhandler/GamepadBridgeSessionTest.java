package com.winlator.winhandler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.inputcontrols.GamepadState;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

public class GamepadBridgeSessionTest {
    private static final byte XINPUT_MAPPER = GamepadProtocol.DINPUT_MAPPER_TYPE_XINPUT;
    private static final int XINPUT_PORT = 7949;

    @Test
    public void firstHeldInputAfterSourceChangeKeepsExistingReceiverConnected() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        deliver(receiver, session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER,
                source(10, "physical:xbox", new GamepadState())));

        GamepadState held = new GamepadState();
        held.thumbLY = -1;
        held.setPressed(0, true);
        List<GamepadBridgeSession.Packet> handoff = session.publish(XINPUT_MAPPER,
                source(3, "virtual:3", held));

        assertCodes(handoff, RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
        for (GamepadBridgeSession.Packet packet : handoff) {
            receiver.receive(bytes(packet));
            assertTrue("An available source handoff must preserve the connected slot", receiver.connected);
        }
        assertEquals(3, receiver.id);
        assertArrayEquals(encode(held), receiver.state);
    }

    @Test
    public void rediscoveryImmediatelyRestoresCompleteHeldState() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        GamepadState held = new GamepadState();
        held.thumbLX = 0.75f;
        held.thumbRY = -0.5f;
        held.leftTrigger = 0.4f;
        held.rightTrigger = 0.8f;
        held.dpad[0] = true;
        held.setPressed(1, true);
        List<GamepadBridgeSession.Packet> discovery = session.discover(
                XINPUT_PORT, true, true, XINPUT_MAPPER, source(3, "virtual:3", held));

        assertCodes(discovery, RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
        deliver(receiver, discovery);
        assertTrue(receiver.connected);
        assertArrayEquals(encode(held), receiver.state);
    }

    @Test
    public void queuedPressAndReleaseRetainTheirOriginalSnapshots() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        deliver(receiver, session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER,
                source(3, "virtual:3", new GamepadState())));
        GamepadState live = new GamepadState();
        live.thumbLY = -1;
        live.setPressed(0, true);
        byte[] captured = encode(live);
        GamepadBridgeSession.Snapshot snapshot = new GamepadBridgeSession.Snapshot(
                3, "virtual:3", "Gamepad", captured);
        List<GamepadBridgeSession.Packet> pressed = session.publish(XINPUT_MAPPER, snapshot);
        Arrays.fill(captured, (byte)0);
        live.thumbLY = 0;
        live.buttons = 0;
        List<GamepadBridgeSession.Packet> released = session.publish(XINPUT_MAPPER,
                source(3, "virtual:3", live));

        deliver(receiver, pressed);
        assertEquals(1, ByteBuffer.wrap(receiver.state).order(ByteOrder.LITTLE_ENDIAN).getShort(0));
        assertEquals(-32767, ByteBuffer.wrap(receiver.state).order(ByteOrder.LITTLE_ENDIAN).getShort(5));
        deliver(receiver, released);
        assertArrayEquals(encode(live), receiver.state);
    }

    @Test
    public void staleSubscriberPollResynchronizesInsteadOfDisconnecting() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        deliver(receiver, session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER,
                source(10, "physical:xbox", new GamepadState())));
        GamepadState held = new GamepadState();
        held.thumbLX = 1;

        List<GamepadBridgeSession.Packet> reply = session.poll(XINPUT_PORT, 10,
                XINPUT_MAPPER, source(3, "virtual:3", held));
        assertCodes(reply, RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
        for (GamepadBridgeSession.Packet packet : reply) {
            receiver.receive(bytes(packet));
            assertTrue(receiver.connected);
        }
        assertEquals(3, receiver.id);
        assertArrayEquals(encode(held), receiver.state);
        assertCodes(session.publish(XINPUT_MAPPER, source(3, "virtual:3", held)),
                RequestCodes.GET_GAMEPAD_STATE);
    }

    @Test
    public void staleUnsubscribedPollDoesNotInvalidateSubscribedController() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        GamepadBridgeSession.Snapshot physical = source(10, "physical:xbox", new GamepadState());
        deliver(receiver, session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER, physical));

        List<GamepadBridgeSession.Packet> stale = session.poll(7999, 3, XINPUT_MAPPER, physical);
        assertEquals(1, stale.size());
        assertEquals(7999, stale.get(0).port);
        assertEquals(0, bytes(stale.get(0))[1]);
        List<GamepadBridgeSession.Packet> next = session.publish(XINPUT_MAPPER, physical);
        assertCodes(next, RequestCodes.GET_GAMEPAD_STATE);
        deliver(receiver, next);
        assertTrue(receiver.connected);
        assertEquals(10, receiver.id);
    }

    @Test
    public void handoffPreservesEachClientsInputType() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        GamepadBridgeSession.Snapshot physical = source(10, "physical:xbox", new GamepadState());
        session.discover(7949, true, true, XINPUT_MAPPER, physical);
        session.discover(7950, false, true, XINPUT_MAPPER, physical);

        List<GamepadBridgeSession.Packet> packets = session.publish(XINPUT_MAPPER,
                source(3, "virtual:3", new GamepadState()));
        assertEquals(4, packets.size());
        assertEquals(7949, packets.get(0).port);
        assertEquals(0x06, bytes(packets.get(0))[5]);
        assertEquals(7950, packets.get(2).port);
        assertEquals(0x0a, bytes(packets.get(2))[5]);
        assertCodes(packets, RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE,
                RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
    }

    @Test
    public void sameNumericIdDoesNotHidePhysicalToVirtualSourceChange() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER,
                source(3, "physical:xbox", new GamepadState()));
        assertCodes(session.publish(XINPUT_MAPPER, source(3, "virtual:3", new GamepadState())),
                RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
    }

    @Test
    public void unavailableDiscoveryStillSubscribesForLaterSourceActivation() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        LegacyReceiver receiver = new LegacyReceiver();
        deliver(receiver, session.discover(XINPUT_PORT, true, true,
                XINPUT_MAPPER, GamepadBridgeSession.Snapshot.unavailable()));
        assertFalse(receiver.connected);
        assertFalse(session.isEmpty());

        deliver(receiver, session.publish(XINPUT_MAPPER,
                source(3, "virtual:3", new GamepadState())));
        assertTrue(receiver.connected);
        assertEquals(3, receiver.id);
    }

    @Test
    public void unsubscribeStopsPushesAndMapperChangesRefreshMetadata() {
        GamepadBridgeSession session = new GamepadBridgeSession();
        GamepadBridgeSession.Snapshot source = source(3, "virtual:3", new GamepadState());
        session.discover(XINPUT_PORT, true, true, XINPUT_MAPPER, source);
        List<GamepadBridgeSession.Packet> mapperChange = session.publish(
                GamepadProtocol.DINPUT_MAPPER_TYPE_STANDARD, source);
        assertCodes(mapperChange, RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE);
        assertEquals(0x05, bytes(mapperChange.get(0))[5]);

        assertCodes(session.discover(XINPUT_PORT, true, false, XINPUT_MAPPER, source),
                RequestCodes.GET_GAMEPAD);
        assertTrue(session.publish(XINPUT_MAPPER, source).isEmpty());
    }

    private static GamepadBridgeSession.Snapshot source(int id, String key, GamepadState state) {
        return new GamepadBridgeSession.Snapshot(id, key, "Gamepad", encode(state));
    }

    private static byte[] encode(GamepadState state) {
        ByteBuffer bytes = ByteBuffer.allocate(GamepadBridgeSession.STATE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        state.writeTo(bytes);
        return bytes.array();
    }

    private static byte[] bytes(GamepadBridgeSession.Packet packet) {
        ByteBuffer output = ByteBuffer.allocate(1024);
        packet.writeTo(output);
        return Arrays.copyOf(output.array(), output.position());
    }

    private static void assertCodes(List<GamepadBridgeSession.Packet> packets, byte... codes) {
        assertEquals(codes.length, packets.size());
        for (int i = 0; i < codes.length; i++) assertEquals(codes[i], bytes(packets.get(i))[0]);
    }

    private static void deliver(LegacyReceiver receiver, List<GamepadBridgeSession.Packet> packets) {
        for (GamepadBridgeSession.Packet packet : packets) receiver.receive(bytes(packet));
    }

    /** Receiver's shipped identity/connection contract, independent of sender batch construction. */
    private static final class LegacyReceiver {
        boolean connected;
        int id;
        byte[] state = new byte[GamepadBridgeSession.STATE_BYTES];

        void receive(byte[] packet) {
            ByteBuffer wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            if (packet[0] == RequestCodes.GET_GAMEPAD) {
                id = wire.getInt(1);
                if (id > 0) {
                    if (!connected) state = new byte[GamepadBridgeSession.STATE_BYTES];
                    connected = true;
                }
                else connected = false;
            }
            else if (packet[0] == RequestCodes.GET_GAMEPAD_STATE && connected) {
                if (packet[1] != 1 || wire.getInt(2) != id) {
                    connected = false;
                    state = new byte[GamepadBridgeSession.STATE_BYTES];
                }
                else state = Arrays.copyOfRange(packet, 6, 6 + GamepadBridgeSession.STATE_BYTES);
            }
        }
    }
}
