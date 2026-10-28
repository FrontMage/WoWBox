package com.winlator.box;

import static org.junit.Assert.*;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

public class BoxDebugHttpTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class TrackingStream extends InputStream {
        final byte[] bytes; int position;
        TrackingStream(byte[] bytes) { this.bytes = bytes; }
        @Override public int read() { return position == bytes.length ? -1 : bytes[position++] & 0xff; }
        @Override public int read(byte[] target, int offset, int count) {
            if (position == bytes.length) return -1;
            int n = Math.min(1, Math.min(count, bytes.length - position));
            System.arraycopy(bytes, position, target, offset, n); position += n; return n;
        }
    }

    @Test public void serverBindsOnlyLoopbackAndAcceptsAdbStyleConnection() throws Exception {
        try (ServerSocket listener = BoxDebugHttp.openLoopback(0)) {
            assertTrue(listener.getInetAddress().isLoopbackAddress());
            assertEquals("127.0.0.1", listener.getInetAddress().getHostAddress());
            try (java.net.Socket client = new java.net.Socket("127.0.0.1", listener.getLocalPort());
                 java.net.Socket accepted = listener.accept()) {
                assertTrue(client.isConnected()); assertTrue(accepted.getInetAddress().isLoopbackAddress());
            }
        }
    }

    @Test public void unauthorizedRequestDoesNotReadAnyBodyBytes() throws Exception {
        String head = "POST /env HTTP/1.1\r\nX-Box-Token: wrong\r\nContent-Length: 9999999999\r\n\r\n";
        TrackingStream stream = new TrackingStream(bytes(head + "secret-body"));
        BoxDebugHttp.Request request = BoxDebugHttp.read(stream, h -> false);
        assertFalse(request.authorized);
        assertEquals(bytes(head).length, stream.position);
    }

    @Test public void bodyLengthIsUtf8BytesAndPartialReadsAreHandled() throws Exception {
        String body = "{\"message\":\"你好\"}";
        String head = "POST /env HTTP/1.1\r\nX-Box-Token: yes\r\nContent-Length: " + bytes(body).length + "\r\n\r\n";
        TrackingStream stream = new TrackingStream(bytes(head + body));
        BoxDebugHttp.Request request = BoxDebugHttp.read(stream, h -> "yes".equals(h.get("x-box-token")));
        assertTrue(request.authorized); assertEquals(body, request.body); assertEquals(stream.bytes.length, stream.position);
    }

    @Test public void oversizedBodyIsRejectedWithoutReadingIt() throws Exception {
        String head = "POST /env HTTP/1.1\r\nContent-Length: " + (BoxDebugHttp.MAX_BODY_BYTES + 1) + "\r\n\r\n";
        TrackingStream stream = new TrackingStream(bytes(head + "payload"));
        try { BoxDebugHttp.read(stream, h -> true); fail(); }
        catch (BoxDebugHttp.RequestException e) { assertEquals(413, e.status); }
        assertEquals(bytes(head).length, stream.position);
    }

    @Test public void duplicateLengthAndUnsupportedTransferEncodingAreRejected() throws Exception {
        rejects("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n", 400);
        rejects("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", 400);
        rejects("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n", 400);
    }

    @Test public void truncatedBodyAndMalformedUtf8AreRejected() throws Exception {
        rejects("POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\nabc", 400);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(bytes("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\n")); bytes.write(0xc3); bytes.write(0x28);
        try { BoxDebugHttp.read(new ByteArrayInputStream(bytes.toByteArray()), h -> true); fail(); }
        catch (BoxDebugHttp.RequestException e) { assertEquals(400, e.status); }
    }

    @Test public void largeOrTruncatedHeadersAreRejected() throws Exception {
        StringBuilder header = new StringBuilder("GET / HTTP/1.1\r\nX-Long: ");
        for (int i = 0; i < BoxDebugHttp.MAX_LINE_BYTES + 1; i++) header.append('x');
        header.append("\r\n\r\n"); rejects(header.toString(), 431);
        rejects("GET / HTTP/1.1\r\nX-Box-Token: yes", 400);
    }

    @Test public void validWebsocketHandshakeLeavesFrameBytesUnread() throws Exception {
        String head = "GET /stream HTTP/1.1\r\nUpgrade: websocket\r\nX-Box-Token: yes\r\n\r\n";
        TrackingStream stream = new TrackingStream(bytes(head + "next-frame"));
        BoxDebugHttp.Request request = BoxDebugHttp.read(stream, h -> "yes".equals(h.get("x-box-token")));
        assertTrue(request.authorized); assertEquals("websocket", request.headers.get("upgrade"));
        assertEquals(bytes(head).length, stream.position);
    }

    private static void rejects(String raw, int status) throws IOException {
        try { BoxDebugHttp.read(new ByteArrayInputStream(bytes(raw)), h -> true); fail(); }
        catch (BoxDebugHttp.RequestException error) { assertEquals(status, error.status); }
    }
}
