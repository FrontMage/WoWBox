package com.winlator.box;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;

public class WinebootTimestampTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Crash extends Error { }

    private final class Fixture {
        final File root;
        final File prefix;
        final File marker;
        final File stage;
        final File journal;

        Fixture() throws Exception {
            root = temporary.newFolder();
            prefix = new File(root, "imagefs/home/xuser-box/.wine");
            assertTrue(prefix.mkdirs());
            marker = new File(prefix, ".update-timestamp");
            stage = new File(root, "box/stage");
            assertTrue(stage.mkdirs());
            journal = new File(root, "box/transaction");
        }

        void write(String text) throws Exception {
            Files.write(marker.toPath(), text.getBytes(StandardCharsets.US_ASCII));
        }

        String read() throws Exception {
            return new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII);
        }

        BoxReleaseTransaction.Entry prepare() throws Exception {
            return WinebootTimestamp.prepare(root, prefix, stage);
        }

        void apply(BoxReleaseTransaction.Entry entry) throws Exception {
            BoxReleaseTransaction.apply(root, journal, Collections.singletonList(entry));
        }
    }

    @Test public void normalMarkerIsReplacedTransactionallyWithStaleValue() throws Exception {
        Fixture f = new Fixture();
        f.write("1700000000\n");
        Set<PosixFilePermission> oldMode = Files.getPosixFilePermissions(f.marker.toPath());
        f.apply(f.prepare());
        assertEquals("0\n", f.read());
        assertEquals(oldMode, Files.getPosixFilePermissions(f.marker.toPath()));
        assertFalse(f.journal.exists());
    }

    @Test public void missingAndDisableMarkersAreLeftUntouched() throws Exception {
        Fixture f = new Fixture();
        assertNull(f.prepare());
        assertFalse(f.marker.exists());
        f.write("disable custom option\n");
        assertNull(f.prepare());
        assertEquals("disable custom option\n", f.read());
        // Wine tests the prefix, even for unusually large user content.
        f.write("disable" + new String(new char[200]).replace('\0', 'x'));
        assertNull(f.prepare());
        assertEquals(207, f.read().length());
    }

    @Test public void alreadyStaleMarkerIsSafeToPrepareAndCommitAgain() throws Exception {
        Fixture f = new Fixture();
        f.write("0\n");
        f.apply(f.prepare());
        assertEquals("0\n", f.read());
        assertFalse(f.journal.exists());
    }

    @Test public void wineTextModeCrlfTimestampCanBeUpgradedAndRolledBack() throws Exception {
        Fixture f = new Fixture();
        f.write("1700000000\r\n");
        try {
            BoxReleaseTransaction.apply(f.root, f.journal, Collections.singletonList(f.prepare()),
                    (at, index) -> { if (at.equals("after-activate")) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(f.journal.exists()); }
        BoxReleaseTransaction.recover(f.root, f.journal);
        assertEquals("1700000000\r\n", f.read());
        f.apply(f.prepare());
        assertEquals("0\n", f.read());
    }

    @Test public void invalidUserMarkerAndSymlinkAreRejectedWithoutMutation() throws Exception {
        Fixture f = new Fixture();
        f.write("custom-user-value\n");
        try { f.prepare(); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unknown")); }
        assertEquals("custom-user-value\n", f.read());
        Files.delete(f.marker.toPath());
        Files.createSymbolicLink(f.marker.toPath(), new File(f.root, "outside").toPath());
        try { f.prepare(); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe")); }
        assertTrue(Files.isSymbolicLink(f.marker.toPath()));
    }

    @Test public void updateAfterPreparationIsNeverOverwritten() throws Exception {
        Fixture f = new Fixture();
        f.write("1700000000\n");
        BoxReleaseTransaction.Entry candidate = f.prepare();
        f.write("disable\n");
        try { f.apply(candidate); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("hash mismatch")); }
        assertEquals("disable\n", f.read());
    }

    @Test public void interruptionBeforeCommitRestoresOriginalTimestamp() throws Exception {
        for (String point : new String[]{"after-backup", "after-activate", "after-validation"}) {
            Fixture f = new Fixture();
            f.write("1700000000\n");
            BoxReleaseTransaction.Entry candidate = f.prepare();
            try {
                BoxReleaseTransaction.apply(f.root, f.journal, Collections.singletonList(candidate),
                        () -> { }, (at, index) -> { if (at.equals(point)) throw new Crash(); },
                        parent -> Long.MAX_VALUE);
                fail();
            } catch (Crash expected) { assertTrue(f.journal.exists()); }
            BoxReleaseTransaction.recover(f.root, f.journal);
            assertEquals("1700000000\n", f.read());
            assertFalse(f.journal.exists());
        }
    }

    @Test public void committedInterruptionKeepsStaleMarkerForWineboot() throws Exception {
        Fixture f = new Fixture();
        f.write("1700000000\n");
        try {
            BoxReleaseTransaction.apply(f.root, f.journal, Collections.singletonList(f.prepare()),
                    (at, index) -> { if (at.equals("after-commit")) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(f.journal.exists()); }
        BoxReleaseTransaction.recover(f.root, f.journal);
        assertEquals("0\n", f.read());
        assertFalse(f.journal.exists());
    }

    @Test public void activatedWineInfMustHaveNonzeroMtime() throws Exception {
        File wine = temporary.newFolder();
        File inf = new File(wine, "share/wine/wine.inf");
        assertTrue(inf.getParentFile().mkdirs());
        Files.write(inf.toPath(), new byte[]{1});
        assertTrue(inf.setLastModified(0));
        try { WinebootTimestamp.requireActivatedInfHasNonzeroTimestamp(wine); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("timestamp")); }
        assertTrue(inf.setLastModified(1000));
        WinebootTimestamp.requireActivatedInfHasNonzeroTimestamp(wine);
    }
}
