package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class WineRuntimeTransactionTest {
    private static final String CHECKSUM =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CANDIDATE_CHECKSUM =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void committedCandidateWinsOverStaleBackup() throws Exception {
        File parent = temporaryFolder.newFolder("runtime-root");
        File runtime = new File(parent, "candidate");
        File backup = new File(parent, "candidate.wine-runtime-backup");
        assertTrue(runtime.mkdirs());
        assertTrue(backup.mkdirs());
        write(new File(backup, "identity"), "baseline");
        BoxInstaller.writeWineRuntimeMarker(runtime, CHECKSUM);

        BoxInstaller.recoverWineRuntimeTransaction(runtime, CHECKSUM);

        assertTrue(runtime.isDirectory());
        assertFalse(backup.exists());
        assertEquals(
                CHECKSUM,
                new String(
                        Files.readAllBytes(
                                new File(
                                        parent,
                                        ".candidate.winlator-runtime-asset.sha256").toPath()),
                        StandardCharsets.US_ASCII).trim());
    }

    @Test
    public void incompleteCandidateRestoresBackup() throws Exception {
        File parent = temporaryFolder.newFolder("runtime-root");
        File runtime = new File(parent, "candidate");
        File backup = new File(parent, "candidate.wine-runtime-backup");
        File prepared = new File(parent, "candidate.wine-runtime-new");
        assertTrue(runtime.mkdirs());
        assertTrue(backup.mkdirs());
        assertTrue(prepared.mkdirs());
        write(new File(runtime, "partial"), "candidate");
        write(new File(backup, "identity"), "baseline");
        write(new File(prepared, "partial"), "prepared");

        BoxInstaller.recoverWineRuntimeTransaction(runtime, CHECKSUM);

        assertTrue(new File(runtime, "identity").isFile());
        assertFalse(new File(runtime, "partial").exists());
        assertFalse(backup.exists());
        assertFalse(prepared.exists());
    }

    @Test
    public void markerSymlinkFailsClosed() throws Exception {
        File parent = temporaryFolder.newFolder("runtime-root");
        File runtime = new File(parent, "candidate");
        File outside = new File(parent, "outside");
        assertTrue(runtime.mkdirs());
        write(outside, "protected");
        Files.createSymbolicLink(
                new File(
                        parent,
                        ".candidate.winlator-runtime-asset.sha256").toPath(),
                outside.toPath());

        try {
            BoxInstaller.recoverWineRuntimeTransaction(runtime, CHECKSUM);
            fail("marker symlink must be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Unsafe Wine runtime"));
        }
        assertEquals("protected", new String(
                Files.readAllBytes(outside.toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void candidateSwapCanRestoreSameIdentifierRuntime() throws Exception {
        File parent = temporaryFolder.newFolder("runtime-root");
        File runtime = new File(parent, "same-runtime-id");
        File backup = new File(parent, "same-runtime-id.wine-runtime-backup");
        prepareRuntime(runtime, "candidate");
        prepareRuntime(backup, "baseline");
        BoxInstaller.writeWineRuntimeMarker(runtime, CANDIDATE_CHECKSUM);

        BoxInstaller.rollbackWineRuntimeTransaction(runtime, CHECKSUM);

        assertEquals("baseline", read(new File(runtime, "identity")));
        assertFalse(backup.exists());
        assertEquals(
                CHECKSUM,
                read(new File(
                        parent,
                        ".same-runtime-id.winlator-runtime-asset.sha256")).trim());
    }

    @Test
    public void staleCandidateBeforeSpecActivationRestoresBackup() throws Exception {
        File parent = temporaryFolder.newFolder("runtime-root");
        File runtime = new File(parent, "same-runtime-id");
        File backup = new File(parent, "same-runtime-id.wine-runtime-backup");
        prepareRuntime(runtime, "candidate");
        prepareRuntime(backup, "baseline");
        BoxInstaller.writeWineRuntimeMarker(runtime, CANDIDATE_CHECKSUM);

        BoxInstaller.recoverWineRuntimeTransaction(runtime, CHECKSUM);

        assertEquals("baseline", read(new File(runtime, "identity")));
        assertFalse(backup.exists());
    }

    private static void prepareRuntime(File root, String identity) throws Exception {
        assertTrue(new File(root, "bin").mkdirs());
        assertTrue(new File(root, "lib/wine/aarch64-unix").mkdirs());
        write(new File(root, "bin/wine"), identity);
        write(new File(root, "bin/wineserver"), identity);
        write(new File(root, "lib/wine/aarch64-unix/ntdll.so"), identity);
        write(new File(root, "identity"), identity);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
