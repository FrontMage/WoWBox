package com.winlator.winhandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class UrlBridgeProtocol {
    public static final byte LITE_OPEN_URL = 0x75;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 6;
    public static final int MAX_UTF8_BYTES = 1800;

    private UrlBridgeProtocol() {}

    public static String parseOpenUrlPacket(byte[] packet, int packetLen) {
        if (packet == null || packetLen < HEADER_SIZE || packetLen > packet.length) return null;
        if (packet[0] != LITE_OPEN_URL || (packet[1] & 0xff) != VERSION) return null;

        int declaredSize = getUnsignedShortLE(packet, 2);
        int urlLength = getUnsignedShortLE(packet, 4);
        if (declaredSize != packetLen || urlLength != packetLen - HEADER_SIZE ||
                urlLength <= 0 || urlLength > MAX_UTF8_BYTES) {
            return null;
        }

        final String url;
        try {
            ByteBuffer bytes = ByteBuffer.wrap(packet, HEADER_SIZE, urlLength);
            url = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        }
        catch (CharacterCodingException e) {
            return null;
        }

        if (!(url.regionMatches(true, 0, "http://", 0, 7) ||
                url.regionMatches(true, 0, "https://", 0, 8))) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char value = url.charAt(i);
            if (Character.isISOControl(value)) return null;
        }
        return url;
    }

    private static int getUnsignedShortLE(byte[] packet, int offset) {
        return ByteBuffer.wrap(packet, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xffff;
    }
}
