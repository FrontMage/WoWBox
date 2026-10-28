package com.winlator.box;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import org.junit.Test;

public class NativeDxPeImageTest {
    private File image(int machine) throws IOException {
        byte[] bytes = new byte[512];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(0, (short)0x5a4d);
        buffer.putInt(0x3c, 0x80);
        buffer.putInt(0x80, 0x00004550);
        buffer.putShort(0x84, (short)machine);
        buffer.putShort(0x96, (short)0x2000);
        buffer.putShort(0x98, (short)0x20b);
        File file = File.createTempFile("native-dx-pe", ".dll");
        Files.write(file.toPath(), bytes);
        return file;
    }

    @Test
    public void acceptsArm64Dll() throws Exception {
        File file = image(0xaa64);
        try { NativeDxPeImage.requirePlainArm64(file); }
        finally { file.delete(); }
    }

    @Test(expected = IOException.class)
    public void rejectsAmd64Dll() throws Exception {
        File file = image(0x8664);
        try { NativeDxPeImage.requirePlainArm64(file); }
        finally { file.delete(); }
    }

    @Test(expected = IOException.class)
    public void rejectsArm64EcDll() throws Exception {
        File file = image(0xa641);
        try { NativeDxPeImage.requirePlainArm64(file); }
        finally { file.delete(); }
    }

    @Test
    public void acceptsWineArm64xCompilerWithChpeView() throws Exception {
        File file = image(0xaa64);
        try {
            byte[] bytes = new byte[1024];
            System.arraycopy(Files.readAllBytes(file.toPath()), 0, bytes, 0, 512);
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            System.arraycopy("Wine builtin DLL".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    0, bytes, 64, 16);
            data.putShort(0x86, (short)1); // section count
            data.putShort(0x94, (short)0xf0); // optional header size
            data.putInt(0x98 + 112 + 80, 0x1000);
            data.putInt(0x98 + 112 + 84, 208);
            data.putInt(0x188 + 8, 0x200);
            data.putInt(0x188 + 12, 0x1000);
            data.putInt(0x188 + 16, 0x200);
            data.putInt(0x188 + 20, 0x200);
            data.putLong(0x200 + 200, 0x180001100L);
            Files.write(file.toPath(), bytes);
            NativeDxPeImage.requireWineArm64xBuiltin(file);
        }
        finally { file.delete(); }
    }

    @Test(expected = IOException.class)
    public void rejectsNativeDxvkAsAReplacementForWineCompiler() throws Exception {
        File file = image(0xaa64);
        try { NativeDxPeImage.requireWineArm64xBuiltin(file); }
        finally { file.delete(); }
    }
}
