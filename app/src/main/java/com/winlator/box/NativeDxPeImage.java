package com.winlator.box;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class NativeDxPeImage {
    private NativeDxPeImage() {}

    static void requirePlainArm64(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 88 || readU16(input) != 0x5a4d) {
                throw new IOException("Native DX file is not a PE image");
            }
            input.seek(0x3c);
            long offset = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (offset > input.length() - 26) throw new IOException("Native DX PE header is truncated");
            input.seek(offset);
            if (Integer.reverseBytes(input.readInt()) != 0x00004550 ||
                    readU16(input) != 0xaa64) {
                throw new IOException("Native DX DLL must be plain ARM64");
            }
            input.seek(offset + 22);
            if ((readU16(input) & 0x2000) == 0) throw new IOException("Native DX image must be a DLL");
            input.seek(offset + 24);
            if (readU16(input) != 0x20b) throw new IOException("Native DX DLL must be PE32+");
        }
    }

    private static int readU16(RandomAccessFile input) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
    }

    static void requireWineArm64xBuiltin(File file) throws IOException {
        requirePlainArm64(file);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(64);
            byte[] marker = new byte[16];
            input.readFully(marker);
            if (!"Wine builtin DLL".equals(new String(marker, java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Shader compiler must be a Wine builtin");
            }
            input.seek(0x3c);
            long pe = readU32(input);
            input.seek(pe + 6);
            int sections = readU16(input);
            input.seek(pe + 20);
            int optionalSize = readU16(input);
            if (optionalSize < 200 || sections < 1 || sections > 96) {
                throw new IOException("Shader compiler has no ARM64X load configuration");
            }
            input.seek(pe + 24 + 112 + 10 * 8);
            long rva = readU32(input);
            long size = readU32(input);
            if (rva == 0 || size < 208) throw new IOException("Shader compiler has no CHPE metadata");
            for (int i = 0; i < sections; i++) {
                long section = pe + 24 + optionalSize + i * 40L;
                if (section > input.length() - 40) throw new IOException("Truncated shader compiler section");
                input.seek(section + 8);
                long virtualSize = readU32(input), virtualAddress = readU32(input);
                long rawSize = readU32(input), rawOffset = readU32(input);
                if (rva < virtualAddress || rva >= virtualAddress + Math.max(virtualSize, rawSize)) continue;
                long offset = rawOffset + rva - virtualAddress;
                if (offset > input.length() - 208) throw new IOException("Truncated shader compiler metadata");
                input.seek(offset + 200);
                if (Long.reverseBytes(input.readLong()) != 0) return;
            }
            throw new IOException("Shader compiler is missing its ARM64EC view");
        }
    }

    private static long readU32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    }
}
