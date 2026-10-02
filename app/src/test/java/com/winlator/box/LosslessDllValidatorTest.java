package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class LosslessDllValidatorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsAmd64PeWithAllRequiredShaderResources() throws Exception {
        File file = writePe("valid.dll", 0x8664, 302);
        LosslessDllValidator.Validation result = LosslessDllValidator.validate(file);

        assertTrue(result.reason, result.valid);
        assertEquals("3.2.2.0", result.version);
        assertEquals(0x8664, result.machine);
        assertTrue(result.missingResourceIds.isEmpty());
        assertEquals(64, result.sha256.length());
    }

    @Test
    public void rejectsWrongMachine() throws Exception {
        LosslessDllValidator.Validation result =
                LosslessDllValidator.validate(writePe("arm64.dll", 0xaa64, 302));

        assertFalse(result.valid);
        assertEquals("pe_machine_not_amd64", result.reason);
    }

    @Test
    public void rejectsMissingShaderResource() throws Exception {
        LosslessDllValidator.Validation result =
                LosslessDllValidator.validate(writePe("missing.dll", 0x8664, 301));

        assertFalse(result.valid);
        assertEquals("required_shader_resources_missing", result.reason);
        assertEquals(1, result.missingResourceIds.size());
        assertEquals(Integer.valueOf(302), result.missingResourceIds.get(0));
    }

    @Test
    public void rejectsNonPeInput() throws Exception {
        File file = temporaryFolder.newFile("invalid.dll");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[512]);
        }

        LosslessDllValidator.Validation result = LosslessDllValidator.validate(file);
        assertFalse(result.valid);
        assertEquals("not_pe", result.reason);
    }

    @Test
    public void validatesUserOwnedDllWhenExplicitlyProvided() {
        String path = System.getenv("LOSSLESS_DLL_TEST_PATH");
        Assume.assumeTrue(path != null && !path.trim().isEmpty());

        LosslessDllValidator.Validation result =
                LosslessDllValidator.validate(new File(path));
        assertTrue(result.reason, result.valid);
        assertEquals("3.2.2.0", result.version);
        assertEquals(0x8664, result.machine);
        assertTrue(result.missingResourceIds.isEmpty());
    }

    private File writePe(String name, int machine, int lastResourceId) throws Exception {
        byte[] data = new byte[0x5000];
        put16(data, 0, 0x5a4d);
        put32(data, 0x3c, 0x80);

        int pe = 0x80;
        put32(data, pe, 0x00004550);
        put16(data, pe + 4, machine);
        put16(data, pe + 6, 1);
        put16(data, pe + 20, 240);

        int optional = pe + 24;
        put16(data, optional, 0x20b);
        put32(data, optional + 108, 16);
        put32(data, optional + 128, 0x1000);
        put32(data, optional + 132, 0x3000);

        int section = optional + 240;
        put32(data, section + 8, 0x3000);
        put32(data, section + 12, 0x1000);
        put32(data, section + 16, 0x3000);
        put32(data, section + 20, 0x400);

        int resourceBase = 0x400;
        put16(data, resourceBase + 14, 1);
        put32(data, resourceBase + 16, 10);
        put32(data, resourceBase + 20, 0x80000020);

        int typeDirectory = resourceBase + 0x20;
        int resourceCount = Math.max(0, lastResourceId - 255 + 1);
        put16(data, typeDirectory + 14, resourceCount);
        int languageBaseRelative = 0x200;
        int dataEntryBaseRelative = 0x700;
        int blobBaseRelative = 0x1000;
        for (int i = 0; i < resourceCount; i++) {
            int id = 255 + i;
            int typeEntry = typeDirectory + 16 + i * 8;
            int languageRelative = languageBaseRelative + i * 24;
            int dataEntryRelative = dataEntryBaseRelative + i * 16;
            int blobRelative = blobBaseRelative + i * 4;

            put32(data, typeEntry, id);
            put32(data, typeEntry + 4, 0x80000000L | languageRelative);

            int languageDirectory = resourceBase + languageRelative;
            put16(data, languageDirectory + 14, 1);
            put32(data, languageDirectory + 16, 1033);
            put32(data, languageDirectory + 20, dataEntryRelative);

            int dataEntry = resourceBase + dataEntryRelative;
            put32(data, dataEntry, 0x1000 + blobRelative);
            put32(data, dataEntry + 4, 4);

            int blobOffset = resourceBase + blobRelative;
            put32(data, blobOffset, id);
        }

        put32(data, 0x350, 0xFEEF04BDL);
        put32(data, 0x358, (3L << 16) | 2);
        put32(data, 0x35c, (2L << 16));

        File file = temporaryFolder.newFile(name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
        return file;
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte)(value & 0xff);
        data[offset + 1] = (byte)((value >>> 8) & 0xff);
    }

    private static void put32(byte[] data, int offset, long value) {
        data[offset] = (byte)(value & 0xff);
        data[offset + 1] = (byte)((value >>> 8) & 0xff);
        data[offset + 2] = (byte)((value >>> 16) & 0xff);
        data[offset + 3] = (byte)((value >>> 24) & 0xff);
    }
}
