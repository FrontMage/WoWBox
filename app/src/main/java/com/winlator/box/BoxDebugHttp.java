package com.winlator.box;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Bounded HTTP/1 reader. It never reads an unauthenticated request body. */
final class BoxDebugHttp {
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int MAX_LINE_BYTES = 8 * 1024;
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int MAX_HEADERS = 64;

    private BoxDebugHttp() {}

    static ServerSocket openLoopback(int port) throws IOException {
        return new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
    }

    static final class RequestException extends IOException {
        final int status;
        RequestException(int status, String message) { super(message); this.status = status; }
    }

    static final class Request {
        final String method, path, body;
        final Map<String, String> headers;
        final boolean authorized;
        Request(String method, String path, String body, Map<String, String> headers, boolean authorized) {
            this.method = method; this.path = path; this.body = body;
            this.headers = headers; this.authorized = authorized;
        }
    }

    static Request read(InputStream input, Predicate<Map<String, String>> authorize) throws IOException {
        if (input == null || authorize == null) throw new IllegalArgumentException("Missing request input or authorization");
        int[] headerBytes = {0};
        String requestLine = readLine(input, headerBytes, true);
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3 || !parts[0].matches("[A-Z]{1,16}") ||
                !parts[1].startsWith("/") || parts[1].length() > MAX_LINE_BYTES ||
                !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))) {
            throw new RequestException(400, "invalid_request_line");
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            String line = readLine(input, headerBytes, false);
            if (line.isEmpty()) break;
            if (count >= MAX_HEADERS) throw new RequestException(400, "too_many_headers");
            int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new RequestException(400, "invalid_header");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.US);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new RequestException(400, "invalid_header_name");
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) throw new RequestException(400, "duplicate_header");
            headers.put(name, value);
        }
        if (!authorize.test(headers)) return new Request(parts[0], parts[1], "", headers, false);
        if (headers.containsKey("transfer-encoding")) throw new RequestException(400, "transfer_encoding_unsupported");
        int length = 0;
        if (headers.containsKey("content-length")) {
            String raw = headers.get("content-length");
            if (!raw.matches("[0-9]{1,10}")) throw new RequestException(400, "invalid_content_length");
            long value;
            try { value = Long.parseLong(raw); }
            catch (NumberFormatException error) { throw new RequestException(400, "invalid_content_length"); }
            if (value > MAX_BODY_BYTES) throw new RequestException(413, "request_body_too_large");
            length = (int)value;
        }
        if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && length != 0) {
            throw new RequestException(400, "websocket_body_not_allowed");
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) throw new RequestException(400, "truncated_body");
            if (read == 0) continue;
            offset += read;
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return new Request(parts[0], parts[1], decoded, headers, true);
        } catch (CharacterCodingException error) {
            throw new RequestException(400, "invalid_utf8_body");
        }
    }

    private static String readLine(InputStream input, int[] total, boolean allowEmptyStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean sawAny = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (!sawAny && allowEmptyStream) return null;
                throw new RequestException(400, "truncated_headers");
            }
            sawAny = true;
            if (++total[0] > MAX_HEADER_BYTES) throw new RequestException(431, "headers_too_large");
            if (value == '\n') break;
            if (value == '\r') {
                int next = input.read();
                if (next < 0) throw new RequestException(400, "truncated_headers");
                if (++total[0] > MAX_HEADER_BYTES) throw new RequestException(431, "headers_too_large");
                if (next != '\n') throw new RequestException(400, "invalid_line_ending");
                break;
            }
            if ((value < 0x20 && value != '\t') || value > 0x7e) {
                throw new RequestException(400, "invalid_header_character");
            }
            if (out.size() >= MAX_LINE_BYTES) throw new RequestException(431, "header_line_too_large");
            out.write(value);
        }
        return new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }
}
