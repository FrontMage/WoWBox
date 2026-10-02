package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.StaticCursor;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadCursor;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class CursorRequests {
    public static void createCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        int sourcePixmapId = inputStream.readInt();
        int maskPixmapId = inputStream.readInt();

        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);

        Pixmap sourcePixmap = client.xServer.pixmapManager.getPixmap(sourcePixmapId);
        if (sourcePixmap == null) throw new BadPixmap(sourcePixmapId);
        if (sourcePixmap.drawable.visual.depth != 1) throw new BadMatch();

        Pixmap maskPixmap = client.xServer.pixmapManager.getPixmap(maskPixmapId);
        if (maskPixmapId != 0 && maskPixmap == null) throw new BadPixmap(maskPixmapId);
        if (maskPixmap != null && (
            maskPixmap.drawable.visual.depth != 1 ||
            maskPixmap.drawable.width != sourcePixmap.drawable.width ||
            maskPixmap.drawable.height != sourcePixmap.drawable.height)) {
            throw new BadMatch();
        }

        int foreRed = inputStream.readUnsignedShort();
        int foreGreen = inputStream.readUnsignedShort();
        int foreBlue = inputStream.readUnsignedShort();
        int backRed = inputStream.readUnsignedShort();
        int backGreen = inputStream.readUnsignedShort();
        int backBlue = inputStream.readUnsignedShort();
        int x = inputStream.readUnsignedShort();
        int y = inputStream.readUnsignedShort();

        int width = Short.toUnsignedInt(sourcePixmap.drawable.width);
        int height = Short.toUnsignedInt(sourcePixmap.drawable.height);
        if (x >= width || y >= height) throw new BadMatch();

        if (client.xServer.isResourceIdInUse(cursorId)) throw new BadIdChoice(cursorId);
        StaticCursor cursor;
        try {
            cursor = client.xServer.cursorManager.createCoreCursor(
                    cursorId,
                    x,
                    y,
                    sourcePixmap,
                    maskPixmap,
                    foreRed,
                    foreGreen,
                    foreBlue,
                    backRed,
                    backGreen,
                    backBlue);
        }
        catch (ArithmeticException | IllegalArgumentException | OutOfMemoryError error) {
            throw new BadAlloc();
        }
        if (cursor == null) throw new BadIdChoice(cursorId);
        client.registerAsOwnerOfResource(cursor);
    }

    public static void freeCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        Cursor cursor = client.xServer.cursorManager.freeCursor(cursorId);
        if (cursor == null) throw new BadCursor(cursorId);
    }

    public static void getPointerMaping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            byte[] buttonsMap = {1, 2, 3};
            byte n = (byte) buttonsMap.length;
            int padLen = -n & 3;

            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(n);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((n + padLen) / 4);
            outputStream.writePad(24);

            for (byte b: buttonsMap)
                outputStream.writeByte(b);
            outputStream.writePad(padLen);
        }
    }
}
