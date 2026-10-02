package com.winlator.box;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class SafeTitanTarExtractorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsOnlyTheTwoAuthorizedTreesWithoutChangingContent() throws Exception {
        byte[] config = "SET scriptProfile \"1\"\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] questie = "soundOnQuestComplete = true\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] tar = archive(
                item("WTF/Config.wtf", config),
                item("Interface/AddOns/Questie/SavedVariables.lua", questie));
        File output = temporaryFolder.newFolder("valid");

        SafeTitanTarExtractor.Result result = SafeTitanTarExtractor.extract(
                new ByteArrayInputStream(tar),
                output,
                new SafeTitanTarExtractor.Limits(10, 1024),
                () -> false,
                (files, bytes) -> {});

        assertEquals(1, result.wtfFiles);
        assertEquals(1, result.addonFiles);
        assertEquals(config.length + questie.length, result.totalBytes);
        assertTrue(result.contentSnapshot.matches("[0-9a-f]{64}"));
        assertArrayEquals(config, Files.readAllBytes(
                new File(output, "WTF/Config.wtf").toPath()));
        assertArrayEquals(questie, Files.readAllBytes(
                new File(output, "Interface/AddOns/Questie/SavedVariables.lua").toPath()));
    }

    @Test
    public void rejectsTraversalAbsoluteAndUnauthorizedRoots() throws Exception {
        assertRejected(archive(item("../escape", bytes("bad"))));
        assertRejected(archive(item("/absolute", bytes("bad"))));
        assertRejected(archive(item("Interface/FrameXML/file.lua", bytes("bad"))));
        assertRejected(archive(item("WTF\\Config.wtf", bytes("bad"))));
    }

    @Test
    public void rejectsLinksDuplicatesAndLimits() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry link =
                    new TarArchiveEntry("WTF/link", TarConstants.LF_SYMLINK);
            link.setLinkName("../../outside");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        assertRejected(bytes.toByteArray());

        assertRejected(archive(
                item("WTF/a", bytes("1")),
                item("wtf/A", bytes("2"))));

        byte[] twoFiles = archive(
                item("WTF/a", bytes("1")),
                item("Interface/AddOns/a", bytes("2")));
        try {
            SafeTitanTarExtractor.extract(
                    new ByteArrayInputStream(twoFiles),
                    temporaryFolder.newFolder(),
                    new SafeTitanTarExtractor.Limits(1, 1024),
                    () -> false,
                    null);
            fail("file limit should fail");
        }
        catch (Exception expected) {
            assertTrue(expected.getMessage().contains("count"));
        }

        byte[] large = archive(item("WTF/a", bytes("12345")));
        try {
            SafeTitanTarExtractor.extract(
                    new ByteArrayInputStream(large),
                    temporaryFolder.newFolder(),
                    new SafeTitanTarExtractor.Limits(10, 4),
                    () -> false,
                    null);
            fail("byte limit should fail");
        }
        catch (Exception expected) {
            assertTrue(expected.getMessage().contains("size"));
        }
    }

    @Test
    public void cancellationLeavesOnlyStagingData() throws Exception {
        byte[] tar = archive(item("WTF/a", new byte[128 * 1024]));
        File output = temporaryFolder.newFolder();
        final boolean[] cancelled = {false};
        try {
            SafeTitanTarExtractor.extract(
                    new ByteArrayInputStream(tar),
                    output,
                    new SafeTitanTarExtractor.Limits(10, 1024 * 1024),
                    () -> cancelled[0],
                    (files, bytes) -> cancelled[0] = bytes > 0);
            fail("cancel should fail");
        }
        catch (Exception expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        }
        assertFalse(new File(output.getParentFile(), "escape").exists());
    }

    @Test
    public void rejectsTruncatedFilePayload() throws Exception {
        byte[] complete = archive(item("WTF/Config.wtf", new byte[4096]));
        byte[] truncated = Arrays.copyOf(complete, 1024);
        assertRejected(truncated);
    }

    @Test
    public void contentSnapshotIncludesPathsAsWellAsSizes() throws Exception {
        SafeTitanTarExtractor.Result first = extractResult(archive(
                item("WTF/a.wtf", bytes("same"))));
        SafeTitanTarExtractor.Result second = extractResult(archive(
                item("WTF/b.wtf", bytes("same"))));

        assertEquals(first.totalFiles, second.totalFiles);
        assertEquals(first.totalBytes, second.totalBytes);
        assertFalse(first.contentSnapshot.equals(second.contentSnapshot));
    }

    private void assertRejected(byte[] tar) throws Exception {
        try {
            SafeTitanTarExtractor.extract(
                    new ByteArrayInputStream(tar),
                    temporaryFolder.newFolder(),
                    new SafeTitanTarExtractor.Limits(100, 1024 * 1024),
                    () -> false,
                    null);
            fail("archive should be rejected");
        }
        catch (Exception expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private SafeTitanTarExtractor.Result extractResult(byte[] tar) throws Exception {
        return SafeTitanTarExtractor.extract(
                new ByteArrayInputStream(tar),
                temporaryFolder.newFolder(),
                new SafeTitanTarExtractor.Limits(100, 1024 * 1024),
                () -> false,
                null);
    }

    private static Item item(String name, byte[] data) {
        return new Item(name, data);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] archive(Item... items) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Item item : items) {
                TarArchiveEntry entry = new TarArchiveEntry(item.name);
                entry.setSize(item.data.length);
                tar.putArchiveEntry(entry);
                tar.write(item.data);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final class Item {
        final String name;
        final byte[] data;

        Item(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }
}
