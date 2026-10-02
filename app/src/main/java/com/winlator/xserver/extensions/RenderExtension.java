package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.CursorManager;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.PictFormat;
import com.winlator.xserver.Picture;
import com.winlator.xserver.RenderPictFormatsWire;
import com.winlator.xserver.StaticCursor;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadCursor;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadLength;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPictFormat;
import com.winlator.xserver.errors.BadPicture;
import com.winlator.xserver.errors.BadRequest;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class RenderExtension implements Extension {
    private static final String TAG = "RenderCursor";
    public static final byte MAJOR_OPCODE = (byte)151;
    public static final byte FIRST_ERROR = (byte)132;
    public static final int SERVER_MAJOR_VERSION = 0;
    public static final int SERVER_MINOR_VERSION = 8;

    private static final int KNOWN_PICTURE_ATTRIBUTE_MASK = 0x1fff;

    private static final class ClientOpcodes {
        private static final int QUERY_VERSION = 0;
        private static final int QUERY_PICT_FORMATS = 1;
        private static final int CREATE_PICTURE = 4;
        private static final int FREE_PICTURE = 7;
        private static final int CREATE_CURSOR = 27;
        private static final int CREATE_ANIM_CURSOR = 31;
    }

    @Override
    public String getName() {
        return "RENDER";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(
            XClient client,
            XInputStream inputStream,
            XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = Byte.toUnsignedInt(client.getRequestData());
        Log.d(TAG, "request minor=" + opcode +
                " sequence=" + Short.toUnsignedInt(client.getSequenceNumber()));
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                return;
            case ClientOpcodes.QUERY_PICT_FORMATS:
                queryPictFormats(client, outputStream);
                return;
            case ClientOpcodes.CREATE_PICTURE:
                try (XLock lock = client.xServer.lockAll()) {
                    createPicture(client, inputStream);
                }
                return;
            case ClientOpcodes.FREE_PICTURE:
                try (XLock lock = client.xServer.lock(XServer.Lockable.PICTURE_MANAGER)) {
                    freePicture(client, inputStream);
                }
                return;
            case ClientOpcodes.CREATE_CURSOR:
                try (XLock lock = client.xServer.lockAll()) {
                    createCursor(client, inputStream);
                }
                return;
            case ClientOpcodes.CREATE_ANIM_CURSOR:
                try (XLock lock = client.xServer.lockAll()) {
                    createAnimatedCursor(client, inputStream);
                }
                return;
            default:
                throw new BadRequest();
        }
    }

    private static void queryVersion(
            XClient client,
            XInputStream inputStream,
            XOutputStream outputStream) throws IOException, XRequestError {
        requireExactLength(client, 8);
        long clientMajor = inputStream.readUnsignedInt();
        long clientMinor = inputStream.readUnsignedInt();
        int negotiatedMinor = clientMajor > 0
                ? SERVER_MINOR_VERSION
                : (int)Math.min(clientMinor, SERVER_MINOR_VERSION);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(SERVER_MAJOR_VERSION);
            outputStream.writeInt(negotiatedMinor);
            outputStream.writePad(16);
        }
    }

    private static void queryPictFormats(
            XClient client,
            XOutputStream outputStream) throws IOException, XRequestError {
        requireExactLength(client, 0);
        ByteBuffer payload = RenderPictFormatsWire.encode(
                client.xServer.renderPictFormats,
                client.xServer.windowManager.rootWindow.getContent().visual.id,
                outputStream.getByteOrder());
        int payloadBytes = payload.remaining();
        if ((payloadBytes & 3) != 0) throw new IllegalStateException("Unaligned Render reply");

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(payloadBytes / 4);
            outputStream.writeInt(3);
            outputStream.writeInt(1);
            outputStream.writeInt(3);
            outputStream.writeInt(1);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.write(payload);
        }
    }

    private static void createPicture(
            XClient client,
            XInputStream inputStream) throws XRequestError {
        int requestBytes = client.getRemainingRequestLength();
        if (requestBytes < 16) throw new BadLength();

        int pictureId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        int formatId = inputStream.readInt();
        int valueMask = inputStream.readInt();

        int expectedBytes = 16 + Integer.bitCount(valueMask) * 4;
        if (requestBytes != expectedBytes) throw new BadLength();
        if ((valueMask & ~KNOWN_PICTURE_ATTRIBUTE_MASK) != 0) {
            throw new BadValue(valueMask);
        }
        if (valueMask != 0) throw new BadImplementation();
        if (!client.isValidResourceId(pictureId) ||
                client.xServer.isResourceIdInUse(pictureId)) {
            throw new BadIdChoice(pictureId);
        }

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null || drawable.visual == null) throw new BadDrawable(drawableId);

        PictFormat format = client.xServer.renderPictFormats.get(formatId);
        if (format == null) throw new BadPictFormat(formatId);
        if (format.depth != Byte.toUnsignedInt(drawable.visual.depth)) throw new BadMatch();

        int width = Short.toUnsignedInt(drawable.width);
        int height = Short.toUnsignedInt(drawable.height);
        try {
            CursorManager.validateStaticGeometry(width, height);
            if (CursorManager.checkedPixelBytes(width, height) >
                    CursorManager.MAX_CURSOR_PIXEL_BYTES) {
                throw new BadAlloc();
            }
            Picture picture = client.xServer.pictureManager.createPicture(
                    pictureId, drawable, format);
            if (picture == null) throw new BadIdChoice(pictureId);
            client.registerAsOwnerOfResource(picture);
        }
        catch (ArithmeticException | IllegalArgumentException | IllegalStateException |
               OutOfMemoryError error) {
            throw new BadAlloc();
        }
    }

    private static void freePicture(
            XClient client,
            XInputStream inputStream) throws XRequestError {
        requireExactLength(client, 4);
        int pictureId = inputStream.readInt();
        if (client.xServer.pictureManager.freePicture(pictureId) == null) {
            throw new BadPicture(pictureId);
        }
    }

    private static void createCursor(
            XClient client,
            XInputStream inputStream) throws XRequestError {
        requireExactLength(client, 12);
        int cursorId = inputStream.readInt();
        int pictureId = inputStream.readInt();
        int hotSpotX = inputStream.readUnsignedShort();
        int hotSpotY = inputStream.readUnsignedShort();

        if (!client.isValidResourceId(cursorId) ||
                client.xServer.isResourceIdInUse(cursorId)) {
            throw new BadIdChoice(cursorId);
        }

        Picture picture = client.xServer.pictureManager.getPicture(pictureId);
        if (picture == null) throw new BadPicture(pictureId);
        if (picture.format != client.xServer.renderPictFormats.argb32 ||
                picture.format.depth != 32) {
            throw new BadMatch();
        }
        if (hotSpotX >= picture.width || hotSpotY >= picture.height) throw new BadMatch();

        try {
            StaticCursor cursor = client.xServer.cursorManager.createRenderCursor(
                    cursorId,
                    hotSpotX,
                    hotSpotY,
                    picture.width,
                    picture.height,
                    picture.copyArgbPremultiplied());
            if (cursor == null) throw new BadIdChoice(cursorId);
            client.registerAsOwnerOfResource(cursor);
        }
        catch (ArithmeticException | IllegalArgumentException | OutOfMemoryError error) {
            throw new BadAlloc();
        }
    }

    private static void createAnimatedCursor(
            XClient client,
            XInputStream inputStream) throws XRequestError {
        int requestBytes = client.getRemainingRequestLength();
        if (requestBytes < 12 || ((requestBytes - 4) & 7) != 0) throw new BadLength();
        int frameCount = (requestBytes - 4) / 8;
        if (frameCount <= 0 || frameCount > CursorManager.MAX_ANIMATION_FRAMES) {
            throw new BadAlloc();
        }

        int cursorId = inputStream.readInt();
        if (!client.isValidResourceId(cursorId) ||
                client.xServer.isResourceIdInUse(cursorId)) {
            throw new BadIdChoice(cursorId);
        }

        StaticCursor[] frames = new StaticCursor[frameCount];
        long[] delaysMs = new long[frameCount];
        long totalBytes = 0;
        for (int i = 0; i < frameCount; i++) {
            int frameId = inputStream.readInt();
            Cursor frame = client.xServer.cursorManager.getCursor(frameId);
            if (frame == null) throw new BadCursor(frameId);
            if (!(frame instanceof StaticCursor)) throw new BadMatch();
            frames[i] = (StaticCursor)frame;
            delaysMs[i] = inputStream.readUnsignedInt();
            try {
                totalBytes = Math.addExact(totalBytes, frame.getPixelBytes());
            }
            catch (ArithmeticException error) {
                throw new BadAlloc();
            }
            if (totalBytes > CursorManager.MAX_CURSOR_PIXEL_BYTES) throw new BadAlloc();
        }

        try {
            Cursor cursor = client.xServer.cursorManager.createAnimatedCursor(
                    cursorId, frames, delaysMs);
            if (cursor == null) throw new BadIdChoice(cursorId);
            client.registerAsOwnerOfResource(cursor);
        }
        catch (ArithmeticException | IllegalArgumentException | OutOfMemoryError error) {
            throw new BadAlloc();
        }
    }

    private static void requireExactLength(XClient client, int expected)
            throws BadLength {
        if (client.getRemainingRequestLength() != expected) throw new BadLength();
    }
}
