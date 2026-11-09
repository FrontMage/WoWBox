package com.winlator.box;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Crash injection on the exact six PE and three metadata targets of the RWOA update. */
public class BoxRwoaReleaseTransactionTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private static final class Crash extends Error {}
    private static final String[] PATHS = {
        "imagefs/opt/wine/lib/wine/i386-windows/ntdll.dll",
        "imagefs/opt/wine/lib/wine/x86_64-windows/ntdll.dll",
        "imagefs/opt/wine/lib/wine/aarch64-windows/ntdll.dll",
        "imagefs/home/xuser-box/.wine/drive_c/windows/syswow64/ntdll.dll",
        "imagefs/home/xuser-box/.wine/drive_c/windows/system32/ntdll.dll",
        "imagefs/home/xuser-box/.wine/drive_c/windows/sysarm32/ntdll.dll",
        "imagefs/opt/wine/.wowbox-runtime-sha256",
        "box/box-spec.json", "box/box-state.json"
    };
    private static void write(File file, String value) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private final class Fixture {
        final File root = temp.newFolder(), inputs = temp.newFolder();
        final File journal = new File(root, "box/release-upgrade-transaction");
        final List<BoxReleaseTransaction.Entry> entries = new ArrayList<>();
        final File userFile = new File(root, "imagefs/home/xuser-box/.wine/drive_c/game/keep.txt");
        Fixture() throws Exception {
            for (int i = 0; i < PATHS.length; i++) {
                File before = new File(root, PATHS[i]), after = new File(inputs, "candidate-" + i);
                write(before, "before-" + i); write(after, "after-" + i);
                entries.add(new BoxReleaseTransaction.Entry(PATHS[i], after,
                        BoxReleaseTransaction.sha256(before, false), BoxReleaseTransaction.sha256(after, false), false));
            }
            write(userFile, "user-owned-data");
        }
        void check(String prefix) throws Exception {
            for (int i = 0; i < PATHS.length; i++) assertEquals(prefix + i, read(new File(root, PATHS[i])));
            assertEquals("user-owned-data", read(userFile));
        }
    }
    @Test public void recoveryRestoresAllSixPeAndMetadataAtEveryStep() throws Exception {
        for (int index = 0; index < PATHS.length; index++) for (String point : new String[]{"after-backup", "after-activate"}) {
            Fixture fixture = new Fixture(); final int selected = index;
            try {
                BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                        (at, item) -> { if (at.equals(point) && item == selected) throw new Crash(); },
                        parent -> Long.MAX_VALUE);
                fail();
            } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
            BoxReleaseTransaction.recover(fixture.root, fixture.journal);
            fixture.check("before-");
            assertFalse(fixture.journal.exists());
        }
    }
    @Test public void committedRecoveryKeepsAllSixPeAndMetadata() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (at, item) -> { if (at.equals("after-commit")) throw new Crash(); }, parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.check("after-");
        assertFalse(fixture.journal.exists());
    }
    @Test public void stalePendingStateSnapshotCannotOverwriteConcurrentUserSettings() throws Exception {
        Fixture fixture = new Fixture();
        File state = new File(fixture.root, "box/box-state.json");
        String snapshotSha = BoxReleaseTransaction.sha256(state, false);
        File staged = new File(fixture.inputs, "cleared-pending");
        write(staged, "state-with-pending-removed");
        write(state, "new-user-settings");
        List<BoxReleaseTransaction.Entry> changes = new ArrayList<>();
        changes.add(new BoxReleaseTransaction.Entry("box/box-state.json", staged, snapshotSha,
                BoxReleaseTransaction.sha256(staged, false), false));
        try { BoxReleaseTransaction.apply(fixture.root, fixture.journal, changes); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("hash mismatch")); }
        assertEquals("new-user-settings", read(state));
        assertFalse(fixture.journal.exists());
    }
}
