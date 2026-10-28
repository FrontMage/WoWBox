package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class BoxReleaseTransactionTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final class Crash extends Error {}

    private final class Fixture {
        final File root;
        final File journal;
        final File inputs;
        final List<BoxReleaseTransaction.Entry> entries = new ArrayList<>();

        Fixture() throws Exception {
            root = temp.newFolder();
            inputs = temp.newFolder();
            assertTrue(new File(root, "files/box").mkdirs());
            assertTrue(new File(root, "files/imagefs/opt").mkdirs());
            assertTrue(new File(root, "files/imagefs/prefix/system32").mkdirs());
            assertTrue(new File(root, "files/imagefs/fex").mkdirs());
            journal = new File(root, "files/box/release-journal");

            File oldWine = new File(root, "files/imagefs/opt/wine");
            File newWine = new File(inputs, "wine");
            write(new File(oldWine, "lib/wine.dll"), "old-wine");
            write(new File(newWine, "lib/wine.dll"), "new-wine");
            Files.createSymbolicLink(new File(oldWine, "lib/alias.dll").toPath(),
                    java.nio.file.Paths.get("wine.dll"));
            Files.createSymbolicLink(new File(newWine, "lib/alias.dll").toPath(),
                    java.nio.file.Paths.get("wine.dll"));
            for (String name : new String[]{"xinput1_3.dll", "xinput1_4.dll"}) {
                write(new File(oldWine, "lib/" + name), "old-" + name);
                write(new File(newWine, "lib/" + name), "new-" + name);
            }
            entry("files/imagefs/opt/wine", newWine, oldWine, true);
            addFile("files/imagefs/prefix/system32/xinput1_3.dll", "old-xinput1_3.dll", "new-xinput1_3.dll");
            addFile("files/imagefs/prefix/system32/xinput1_4.dll", "old-xinput1_4.dll", "new-xinput1_4.dll");

            addFile("files/imagefs/prefix/system32/ntdll.dll", "old-prefix", "new-prefix");
            addFile("files/imagefs/fex/libarm64ecfex.dll", "old-fex", "new-fex");
            addFile("files/imagefs/fex/libwow64fex.dll", "old-fex32", "new-fex32");
            addFile("files/box/spec.json", "old-spec", "new-spec");
            addFile("files/box/state.json", "old-state", "new-state");
            File addition = new File(inputs, "new-helper"); write(addition, "helper");
            entries.add(new BoxReleaseTransaction.Entry("files/imagefs/prefix/system32/new.exe",
                    addition, null, hash(addition, false), false));
        }

        void addFile(String path, String before, String after) throws Exception {
            File target = new File(root, path), candidate = new File(inputs, "candidate-" + entries.size());
            write(target, before); write(candidate, after); entry(path, candidate, target, false);
        }

        void entry(String path, File candidate, File target, boolean directory) throws Exception {
            entries.add(new BoxReleaseTransaction.Entry(path, candidate, hash(target, directory),
                    hash(candidate, directory), directory));
        }

        void verifyOriginal() throws Exception {
            assertEquals("old-wine", read(new File(root, "files/imagefs/opt/wine/lib/wine.dll")));
            for (String name : new String[]{"xinput1_3.dll", "xinput1_4.dll"}) {
                assertEquals("old-" + name, read(new File(root, "files/imagefs/opt/wine/lib/" + name)));
                assertEquals("old-" + name, read(new File(root, "files/imagefs/prefix/system32/" + name)));
            }
            assertEquals("old-prefix", read(new File(root, "files/imagefs/prefix/system32/ntdll.dll")));
            assertEquals("old-fex", read(new File(root, "files/imagefs/fex/libarm64ecfex.dll")));
            assertEquals("old-fex32", read(new File(root, "files/imagefs/fex/libwow64fex.dll")));
            assertEquals("old-spec", read(new File(root, "files/box/spec.json")));
            assertEquals("old-state", read(new File(root, "files/box/state.json")));
            assertFalse(new File(root, "files/imagefs/prefix/system32/new.exe").exists());
        }

        void verifyCandidate() throws Exception {
            assertEquals("new-wine", read(new File(root, "files/imagefs/opt/wine/lib/wine.dll")));
            for (String name : new String[]{"xinput1_3.dll", "xinput1_4.dll"}) {
                assertEquals("new-" + name, read(new File(root, "files/imagefs/opt/wine/lib/" + name)));
                assertEquals("new-" + name, read(new File(root, "files/imagefs/prefix/system32/" + name)));
            }
            assertTrue(Files.isSymbolicLink(new File(root, "files/imagefs/opt/wine/lib/alias.dll").toPath()));
            assertEquals("new-prefix", read(new File(root, "files/imagefs/prefix/system32/ntdll.dll")));
            assertEquals("new-fex", read(new File(root, "files/imagefs/fex/libarm64ecfex.dll")));
            assertEquals("new-fex32", read(new File(root, "files/imagefs/fex/libwow64fex.dll")));
            assertEquals("new-spec", read(new File(root, "files/box/spec.json")));
            assertEquals("new-state", read(new File(root, "files/box/state.json")));
            assertEquals("helper", read(new File(root, "files/imagefs/prefix/system32/new.exe")));
        }
    }

    @Test public void replacesWinePrefixFexAndStateTogether() throws Exception {
        Fixture fixture = new Fixture();
        BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries);
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void preCommitValidatorSeesEntireCandidateWithBackupsStillPresent() throws Exception {
        Fixture fixture = new Fixture();
        boolean[] called = {false};
        BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries, () -> {
            fixture.verifyCandidate();
            assertTrue(new File(fixture.journal, "backup/0").exists());
            assertTrue(new File(fixture.journal, "backup/5").exists());
            assertFalse(new File(fixture.journal, "commit").exists());
            called[0] = true;
        });
        assertTrue(called[0]);
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void validatorRejectRestoresAllLayers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> { throw new IOException("post activation check failed"); });
            fail();
        } catch (IOException expected) {
            assertEquals("post activation check failed", expected.getMessage());
        }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void validatorCheckedExceptionRestoresAllLayers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> { throw new Exception("validator error"); });
            fail();
        } catch (IOException expected) {
            assertEquals("Release validation failed", expected.getMessage());
            assertEquals("validator error", expected.getCause().getMessage());
        }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void validatorRuntimeExceptionRestoresAllLayers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> { throw new IllegalStateException("validator failed"); });
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("validator failed", expected.getMessage());
        }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void processDeathInsideValidatorRecoversAllLayers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries, () -> {
                fixture.verifyCandidate();
                throw new Crash();
            });
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        assertFalse(new File(fixture.journal, "commit").exists());
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void processDeathAfterValidationBeforeCommitRecoversAllLayers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> fixture.verifyCandidate(),
                    (point, index) -> { if (point.equals("after-validation")) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.verifyOriginal();
    }

    @Test public void validatorMustNotMutateVerifiedTargets() throws Exception {
        Fixture fixture = new Fixture();
        File target = new File(fixture.root, "files/imagefs/prefix/system32/ntdll.dll");
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> write(target, "unexpected mutation"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Validated candidate"));
            assertTrue(expected.getSuppressed().length > 0);
        }
        assertEquals("unexpected mutation", read(target));
        assertTrue(new File(fixture.journal, "backup/0").exists());
        assertTrue(new File(fixture.journal, "backup/1").exists());
        assertFalse(new File(fixture.journal, "commit").exists());
    }

    @Test public void nullValidatorIsRejectedBeforeMutation() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (BoxReleaseTransaction.PreCommitValidator) null);
            fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Missing release validator")); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void unknownPrefixAbortsBeforeAnyTargetChanges() throws Exception {
        Fixture fixture = new Fixture();
        File target = new File(fixture.root, "files/imagefs/prefix/system32/ntdll.dll");
        write(target, "user-custom");
        try { BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("hash mismatch")); }
        assertEquals("user-custom", read(target));
        assertEquals("old-wine", read(new File(fixture.root, "files/imagefs/opt/wine/lib/wine.dll")));
        assertFalse(fixture.journal.exists());
    }

    @Test public void existingUnknownAdditionIsNeverOverwritten() throws Exception {
        Fixture fixture = new Fixture();
        File target = new File(fixture.root, "files/imagefs/prefix/system32/new.exe");
        write(target, "user-owned");
        try { BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unexpected existing")); }
        assertEquals("user-owned", read(target));
        assertEquals("old-spec", read(new File(fixture.root, "files/box/spec.json")));
    }

    @Test public void insufficientSpaceFailsBeforeMutation() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> {}, parent -> 0);
            fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Insufficient free space")); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void platformFilesystemAdapterMustApproveEveryTargetBeforeMutation() throws Exception {
        Fixture fixture = new Fixture();
        int[] calls = {0};
        boolean[] validated = {false};
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    () -> { validated[0] = true; },
                    (first, second) -> ++calls[0] != 3);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("different filesystem"));
        }
        assertEquals(3, calls[0]);
        assertFalse(validated[0]);
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void filesystemAdapterErrorDoesNotMutateAnyTarget() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (first, second) -> { throw new IOException("stat failed"); });
            fail();
        } catch (IOException expected) { assertEquals("stat failed", expected.getMessage()); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void successfulPlatformAdapterAllowsValidatedTransaction() throws Exception {
        Fixture fixture = new Fixture();
        int[] calls = {0};
        BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                fixture::verifyCandidate,
                (first, second) -> { calls[0]++; return true; });
        assertEquals(fixture.entries.size(), calls[0]);
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void copyFailureBeforeManifestLeavesTargetsUntouched() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("during-copy") && index == 1) throw new IOException("disk full"); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (IOException expected) { assertEquals("disk full", expected.getMessage()); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void concurrentSpecAndStateEditsDuringPreparationArePreserved() throws Exception {
        Fixture fixture = new Fixture();
        File spec = new File(fixture.root, "files/box/spec.json");
        File state = new File(fixture.root, "files/box/state.json");
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> {
                        if (point.equals("after-stage") && index == 0) {
                            Files.write(spec.toPath(), "user-custom-target-env".getBytes(StandardCharsets.UTF_8));
                            Files.write(state.toPath(), "user-updated-state".getBytes(StandardCharsets.UTF_8));
                        }
                    }, parent -> Long.MAX_VALUE);
            fail("A concurrent user update must abort the release");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("target"));
        }
        assertEquals("user-custom-target-env", read(spec));
        assertEquals("user-updated-state", read(state));
        assertEquals("old-wine", read(new File(fixture.root, "files/imagefs/opt/wine/lib/wine.dll")));
        assertEquals("old-prefix", read(new File(fixture.root, "files/imagefs/prefix/system32/ntdll.dll")));
        assertFalse(fixture.journal.exists());
    }

    @Test public void concurrentWineModificationDuringPreparationIsNotOverwritten() throws Exception {
        Fixture fixture = new Fixture();
        File target = new File(fixture.root, "files/imagefs/opt/wine/lib/wine.dll");
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-stage") && index == 0)
                        Files.write(target.toPath(), "user-wine-change".getBytes(StandardCharsets.UTF_8)); },
                    parent -> Long.MAX_VALUE);
            fail("A concurrent Wine change must abort the release");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("target"));
        }
        assertEquals("user-wine-change", read(target));
        assertEquals("old-spec", read(new File(fixture.root, "files/box/spec.json")));
        assertFalse(fixture.journal.exists());
    }

    @Test public void ordinaryActivationFailureRollsBackAllPriorEntries() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-activate") && index == 3) throw new IOException("activation failure"); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (IOException expected) { assertEquals("activation failure", expected.getMessage()); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void diskFailureImmediatelyAfterBackupRestoresOriginal() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-backup") && index == 2) throw new IOException("disk failure"); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (IOException expected) { assertEquals("disk failure", expected.getMessage()); }
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void processCrashAtEachBackupAndActivationRecoversOriginal() throws Exception {
        for (int index = 0; index < 7; index++) {
            for (String point : new String[]{"after-backup", "after-activate"}) {
                if (index == 6 && point.equals("after-backup")) continue; // added target has no backup
                Fixture fixture = new Fixture(); final int selected = index;
                try {
                    BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                            (at, item) -> { if (at.equals(point) && item == selected) throw new Crash(); },
                            parent -> Long.MAX_VALUE);
                    fail();
                } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
                BoxReleaseTransaction.recover(fixture.root, fixture.journal);
                fixture.verifyOriginal();
                assertFalse(fixture.journal.exists());
            }
        }
    }

    @Test public void crashAfterCommitKeepsNewFilesAndFinishesCleanup() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-commit")) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void postCommitFailureIsExplicitlyReportedAsCommitted() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-commit")) throw new IOException("cleanup failure"); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (BoxReleaseTransaction.CommittedCleanupException expected) {
            assertEquals("cleanup failure", expected.getCause().getMessage());
        }
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void committedCleanupCanResumeAfterBackupsAndManifestAreRemoved() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-commit")) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        // Emulate process death during cleanup, after backup data was removed.
        deleteTree(new File(fixture.journal, "staged"));
        deleteTree(new File(fixture.journal, "backup"));
        Files.delete(new File(fixture.journal, "manifest").toPath());
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.verifyCandidate();
        assertFalse(fixture.journal.exists());
    }

    @Test public void processDeathWhileWritingOwnerCanBeRecoveredWithoutMutatingTargets() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.journal.mkdir());
        write(new File(fixture.journal, "owner.tmp"), "wowbox-release");
        BoxReleaseTransaction.recover(fixture.root, fixture.journal);
        fixture.verifyOriginal();
        assertFalse(fixture.journal.exists());
    }

    @Test public void recoveryRejectsSymlinkedBackupDirectory() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-stage") && index == 0) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        Files.delete(new File(fixture.journal, "backup").toPath());
        Files.createSymbolicLink(new File(fixture.journal, "backup").toPath(), fixture.inputs.toPath());
        try { BoxReleaseTransaction.recover(fixture.root, fixture.journal); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe release journal")); }
        fixture.verifyOriginal();
    }

    @Test public void unknownMutationDuringRecoveryLeavesEveryBackupForReview() throws Exception {
        Fixture fixture = new Fixture();
        try {
            BoxReleaseTransaction.apply(fixture.root, fixture.journal, fixture.entries,
                    (point, index) -> { if (point.equals("after-activate") && index == 3) throw new Crash(); },
                    parent -> Long.MAX_VALUE);
            fail();
        } catch (Crash expected) { assertTrue(fixture.journal.exists()); }
        File target = new File(fixture.root, "files/imagefs/prefix/system32/ntdll.dll");
        write(target, "unexpected");
        try { BoxReleaseTransaction.recover(fixture.root, fixture.journal); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("hash mismatch")); }
        assertEquals("new-wine", read(new File(fixture.root, "files/imagefs/opt/wine/lib/wine.dll")));
        assertEquals("unexpected", read(target));
        assertTrue(new File(fixture.journal, "backup/0").exists());
        assertTrue(new File(fixture.journal, "backup/1").exists());
    }

    @Test public void rejectsEscapingSymlinksAndTargets() throws Exception {
        Fixture fixture = new Fixture();
        File wine = new File(fixture.inputs, "wine");
        Files.createSymbolicLink(new File(wine, "escape").toPath(), java.nio.file.Paths.get("../../outside"));
        try { BoxReleaseTransaction.sha256(wine, true); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("escapes")); }
        File source = new File(fixture.inputs, "candidate-1");
        List<BoxReleaseTransaction.Entry> invalid = java.util.Collections.singletonList(
                new BoxReleaseTransaction.Entry("../outside", source, null, hash(source, false), false));
        try { BoxReleaseTransaction.apply(fixture.root, fixture.journal, invalid); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe")); }
        fixture.verifyOriginal();
    }

    private static String hash(File file, boolean directory) throws Exception {
        return BoxReleaseTransaction.sha256(file, directory);
    }

    private static void write(File file, String text) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File root) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }
}
