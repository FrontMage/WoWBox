package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class WinePrefixDeltaReconcilerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void replacesDeclaredBaselineAcrossAllSupportedPrefixRoots() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry system32 = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old-system32",
                "new-system32");
        WinePrefixDeltaReconciler.Entry sysarm32 = fixture.entry(
                "sysarm32/rundll32.exe",
                "lib/wine/aarch64-windows/rundll32.exe",
                "old-sysarm32",
                "new-sysarm32");
        WinePrefixDeltaReconciler.Entry syswow64 = fixture.entry(
                "syswow64/ntdll.dll",
                "lib/wine/i386-windows/ntdll.dll",
                "old-syswow64",
                "new-syswow64");

        WinePrefixDeltaReconciler.Result result = WinePrefixDeltaReconciler.reconcile(
                fixture.runtime,
                fixture.windows,
                Arrays.asList(system32, sysarm32, syswow64));

        assertEquals(3, result.replaced);
        assertEquals(0, result.alreadyCurrent);
        assertEquals("new-system32", fixture.read("system32/ntdll.dll"));
        assertEquals("new-sysarm32", fixture.read("sysarm32/rundll32.exe"));
        assertEquals("new-syswow64", fixture.read("syswow64/ntdll.dll"));
    }

    @Test
    public void candidateHashIsAnIdempotentNoOp() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry entry = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old",
                "candidate");
        fixture.writePrefix("system32/ntdll.dll", "candidate");

        WinePrefixDeltaReconciler.Result result = WinePrefixDeltaReconciler.reconcile(
                fixture.runtime,
                fixture.windows,
                Collections.singletonList(entry));

        assertEquals(0, result.replaced);
        assertEquals(1, result.alreadyCurrent);
        assertEquals("candidate", fixture.read("system32/ntdll.dll"));
    }

    @Test
    public void unknownDestinationFailsBeforeAnyEntryIsModified() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry first = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old-first",
                "new-first");
        WinePrefixDeltaReconciler.Entry second = fixture.entry(
                "syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll",
                "declared-old-second",
                "new-second");
        fixture.writePrefix("syswow64/kernel32.dll", "third-party-second");

        try {
            WinePrefixDeltaReconciler.reconcile(
                    fixture.runtime,
                    fixture.windows,
                    Arrays.asList(first, second));
            fail("unknown destination hash must fail closed");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unknown destination hash"));
        }

        assertEquals("old-first", fixture.read("system32/ntdll.dll"));
        assertEquals("third-party-second", fixture.read("syswow64/kernel32.dll"));
    }

    @Test
    public void candidateSourceHashMismatchFailsClosed() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry entry = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old",
                "candidate");
        fixture.writeRuntime("lib/wine/aarch64-windows/ntdll.dll", "different-source");

        try {
            WinePrefixDeltaReconciler.reconcile(
                    fixture.runtime,
                    fixture.windows,
                    Collections.singletonList(entry));
            fail("source mismatch must fail closed");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("source hash mismatch"));
        }
        assertEquals("old", fixture.read("system32/ntdll.dll"));
    }

    @Test
    public void missingDestinationFailsBeforeAnyEntryIsModified() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry first = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old-first",
                "new-first");
        WinePrefixDeltaReconciler.Entry missing = fixture.entry(
                "syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll",
                "old-missing",
                "new-missing");
        assertTrue(new File(fixture.windows, "syswow64/kernel32.dll").delete());

        try {
            WinePrefixDeltaReconciler.reconcile(
                    fixture.runtime,
                    fixture.windows,
                    Arrays.asList(first, missing));
            fail("missing destination must fail closed");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("destination is missing"));
        }

        assertEquals("old-first", fixture.read("system32/ntdll.dll"));
    }

    @Test
    public void freshPrefixSeedsMissingFilesBeforeStrictReconcile() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll", "old-a", "new-a");
        WinePrefixDeltaReconciler.Entry second = f.entry("sysarm32/kernel32.dll",
                "lib/wine/aarch64-windows/kernel32.dll", "old-b", "new-b");
        assertTrue(new File(f.windows, "sysarm32/kernel32.dll").delete());
        WinePrefixDeltaReconciler.seedFreshMissing(f.runtime, f.windows, Arrays.asList(first, second));
        assertEquals("old-a", f.read("system32/ntdll.dll"));
        assertEquals("new-b", f.read("sysarm32/kernel32.dll"));
        WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Arrays.asList(first, second));
        assertEquals("new-a", f.read("system32/ntdll.dll"));
        assertEquals("new-b", f.read("sysarm32/kernel32.dll"));
    }

    @Test
    public void freshPrefixRejectsUnknownBeforeSeedingAnything() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll", "old-a", "new-a");
        WinePrefixDeltaReconciler.Entry second = f.entry("sysarm32/kernel32.dll",
                "lib/wine/aarch64-windows/kernel32.dll", "old-b", "new-b");
        f.writePrefix("system32/ntdll.dll", "third-party");
        assertTrue(new File(f.windows, "sysarm32/kernel32.dll").delete());
        try {
            WinePrefixDeltaReconciler.seedFreshMissing(f.runtime, f.windows, Arrays.asList(first, second));
            fail("Unknown destination must fail before writing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Unknown fresh"));
        }
        assertEquals("third-party", f.read("system32/ntdll.dll"));
        assertTrue(!new File(f.windows, "sysarm32/kernel32.dll").exists());
    }

    @Test
    public void verifiedAlternativeBaselineMayBeUpdated() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/iexplore.exe",
                "lib/wine/aarch64-windows/iexplore.exe", "baseline-a", "candidate");
        File alternate = f.writePrefix("system32/iexplore.exe", "baseline-b");
        WinePrefixDeltaReconciler.Entry entry = new WinePrefixDeltaReconciler.Entry(
                first.prefixPath, first.runtimePath, first.baselineSha256, first.candidateSha256,
                Collections.singletonList(WinePrefixDeltaReconciler.sha256(alternate)));
        WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Collections.singletonList(entry));
        assertEquals("candidate", f.read("system32/iexplore.exe"));
    }

    @Test
    public void winebootOptionalMayBeAbsentOrCandidateButNotUnknown() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry initial = f.entry("system32/fusion.dll",
                "lib/wine/aarch64-windows/fusion.dll", "old", "candidate");
        WinePrefixDeltaReconciler.Entry optional = new WinePrefixDeltaReconciler.Entry(
                initial.prefixPath, initial.runtimePath, initial.baselineSha256,
                initial.candidateSha256, Collections.emptyList(), true);
        File target = new File(f.windows, initial.prefixPath);
        assertTrue(target.delete());
        assertTrue(WinePrefixDeltaReconciler.candidateStateMatches(f.windows, Collections.singletonList(optional)));
        WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Collections.singletonList(optional));
        assertTrue(!target.exists());
        f.writePrefix(initial.prefixPath, "candidate");
        assertTrue(WinePrefixDeltaReconciler.candidateStateMatches(f.windows, Collections.singletonList(optional)));
        f.writePrefix(initial.prefixPath, "third-party");
        assertTrue(!WinePrefixDeltaReconciler.candidateStateMatches(f.windows, Collections.singletonList(optional)));
        try { WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Collections.singletonList(optional)); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("unknown destination")); }
        assertEquals("third-party", f.read(initial.prefixPath));
    }

    @Test
    public void optionalNeverPermitsDanglingSymlinkOrOtherFiles() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry initial = f.entry("syswow64/comctl32_v6.dll",
                "lib/wine/i386-windows/comctl32_v6.dll", "old", "candidate");
        WinePrefixDeltaReconciler.Entry optional = new WinePrefixDeltaReconciler.Entry(
                initial.prefixPath, initial.runtimePath, initial.baselineSha256,
                initial.candidateSha256, Collections.emptyList(), true);
        File target = new File(f.windows, initial.prefixPath);
        assertTrue(target.delete());
        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get("missing-target"));
        assertTrue(!WinePrefixDeltaReconciler.candidateStateMatches(f.windows, Collections.singletonList(optional)));
        try { WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Collections.singletonList(optional)); fail(); }
        catch (IllegalArgumentException | IllegalStateException expected) { /* fail closed */ }
        try {
            new WinePrefixDeltaReconciler.Entry("sysarm32/fusion.dll", initial.runtimePath,
                    initial.baselineSha256, initial.candidateSha256, Collections.emptyList(), true);
            fail("sysarm32 is not authorized for removal");
        } catch (IllegalArgumentException expected) { /* exact allowlist */ }
    }

    @Test
    public void freshSeedDoesNotMaterializeOptionalMissingFiles() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry initial = f.entry("system32/ngen.exe",
                "lib/wine/aarch64-windows/ngen.exe", "old", "candidate");
        WinePrefixDeltaReconciler.Entry optional = new WinePrefixDeltaReconciler.Entry(
                initial.prefixPath, initial.runtimePath, initial.baselineSha256,
                initial.candidateSha256, Collections.emptyList(), true);
        assertTrue(new File(f.windows, initial.prefixPath).delete());
        WinePrefixDeltaReconciler.seedFreshMissing(f.runtime, f.windows, Collections.singletonList(optional));
        assertTrue(!new File(f.windows, initial.prefixPath).exists());
    }

    @Test
    public void interruptedRollbackHandlesOptionalMissingEntry() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll", "old-a", "new-a");
        WinePrefixDeltaReconciler.Entry second = f.entry("syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll", "old-b", "new-b");
        WinePrefixDeltaReconciler.Entry initial = f.entry("system32/fusion.dll",
                "lib/wine/aarch64-windows/fusion.dll", "old-f", "new-f");
        WinePrefixDeltaReconciler.Entry optional = new WinePrefixDeltaReconciler.Entry(
                initial.prefixPath, initial.runtimePath, initial.baselineSha256,
                initial.candidateSha256, Collections.emptyList(), true);
        assertTrue(new File(f.windows, initial.prefixPath).delete());
        f.writePrefix("system32/ntdll.dll.wine-delta-backup", "old-a");
        f.writePrefix("system32/ntdll.dll", "new-a");
        f.writePrefix("syswow64/kernel32.dll.wine-delta-new", "new-b");
        WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Arrays.asList(first, second, optional));
        assertEquals("new-a", f.read(first.prefixPath));
        assertEquals("new-b", f.read(second.prefixPath));
        assertTrue(!new File(f.windows, optional.prefixPath).exists());
    }

    @Test
    public void interruptedStagingWithAlreadyCurrentEntryCanRetry() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll", "old-a", "new-a");
        WinePrefixDeltaReconciler.Entry second = f.entry("syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll", "old-b", "new-b");
        f.writePrefix(first.prefixPath, "new-a");
        f.writePrefix(second.prefixPath + ".wine-delta-new", "new-b");
        WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Arrays.asList(first, second));
        assertEquals("new-a", f.read(first.prefixPath));
        assertEquals("new-b", f.read(second.prefixPath));
        assertTrue(!new File(f.windows, second.prefixPath + ".wine-delta-new").exists());
    }

    @Test
    public void mixedActivationWithCandidateMissingBackupFailsBeforeAnyRollback() throws Exception {
        Fixture f = fixture();
        WinePrefixDeltaReconciler.Entry first = f.entry("system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll", "old-a", "new-a");
        WinePrefixDeltaReconciler.Entry second = f.entry("syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll", "old-b", "new-b");
        WinePrefixDeltaReconciler.Entry third = f.entry("system32/advapi32.dll",
                "lib/wine/aarch64-windows/advapi32.dll", "old-c", "new-c");
        f.writePrefix(first.prefixPath, "new-a"); // Could be initially current or have a lost backup.
        f.writePrefix(second.prefixPath, "new-b");
        f.writePrefix(second.prefixPath + ".wine-delta-backup", "old-b");
        f.writePrefix(third.prefixPath + ".wine-delta-new", "new-c");
        try { WinePrefixDeltaReconciler.reconcile(f.runtime, f.windows, Arrays.asList(first, second, third)); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("without backup")); }
        assertEquals("new-a", f.read(first.prefixPath));
        assertEquals("new-b", f.read(second.prefixPath));
        assertEquals("old-b", f.read(second.prefixPath + ".wine-delta-backup"));
        assertEquals("old-c", f.read(third.prefixPath));
        assertEquals("new-c", f.read(third.prefixPath + ".wine-delta-new"));
    }

    @Test
    public void interruptedPartialActivationIsRolledBackAndRetried() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry first = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old-first",
                "new-first");
        WinePrefixDeltaReconciler.Entry second = fixture.entry(
                "syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll",
                "old-second",
                "new-second");

        fixture.writePrefix("system32/ntdll.dll.wine-delta-backup", "old-first");
        fixture.writePrefix("system32/ntdll.dll", "new-first");
        fixture.writePrefix("syswow64/kernel32.dll.wine-delta-new", "new-second");

        WinePrefixDeltaReconciler.Result result = WinePrefixDeltaReconciler.reconcile(
                fixture.runtime,
                fixture.windows,
                Arrays.asList(first, second));

        assertEquals(2, result.replaced);
        assertEquals("new-first", fixture.read("system32/ntdll.dll"));
        assertEquals("new-second", fixture.read("syswow64/kernel32.dll"));
        assertTrue(!new File(
                fixture.windows,
                "system32/ntdll.dll.wine-delta-backup").exists());
        assertTrue(!new File(
                fixture.windows,
                "syswow64/kernel32.dll.wine-delta-new").exists());
    }

    @Test
    public void committedCandidateWithStaleBackupIsFinalized() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry entry = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old",
                "candidate");
        fixture.writePrefix("system32/ntdll.dll.wine-delta-backup", "old");
        fixture.writePrefix("system32/ntdll.dll", "candidate");

        WinePrefixDeltaReconciler.Result result = WinePrefixDeltaReconciler.reconcile(
                fixture.runtime,
                fixture.windows,
                Collections.singletonList(entry));

        assertEquals(0, result.replaced);
        assertEquals(1, result.alreadyCurrent);
        assertTrue(!new File(
                fixture.windows,
                "system32/ntdll.dll.wine-delta-backup").exists());
    }

    @Test
    public void candidateStateRequiresEveryDeclaredHash() throws Exception {
        Fixture fixture = fixture();
        WinePrefixDeltaReconciler.Entry first = fixture.entry(
                "system32/ntdll.dll",
                "lib/wine/aarch64-windows/ntdll.dll",
                "old-first",
                "new-first");
        WinePrefixDeltaReconciler.Entry second = fixture.entry(
                "syswow64/kernel32.dll",
                "lib/wine/i386-windows/kernel32.dll",
                "old-second",
                "new-second");
        fixture.writePrefix("system32/ntdll.dll", "new-first");
        fixture.writePrefix("syswow64/kernel32.dll", "new-second");

        assertTrue(WinePrefixDeltaReconciler.candidateStateMatches(
                fixture.windows,
                Arrays.asList(first, second)));

        fixture.writePrefix("syswow64/kernel32.dll", "old-second");
        assertTrue(!WinePrefixDeltaReconciler.candidateStateMatches(
                fixture.windows,
                Arrays.asList(first, second)));
    }

    @Test
    public void rejectsDxAndXinputManagedLayerFiles() {
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath("system32/d3d12.dll"));
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath("syswow64/dxgi.dll"));
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath("system32/xinput1_3.dll"));
        assertTrue(WinePrefixDeltaReconciler.isAllowedPrefixPath("system32/ntdll.dll"));
        assertTrue(WinePrefixDeltaReconciler.isAllowedPrefixPath(
                "system32/drivers/mountmgr.sys"));
    }

    @Test
    public void rejectsTraversalAndUnownedRoots() {
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath(
                "system32/../syswow64/ntdll.dll"));
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath(
                "/system32/ntdll.dll"));
        assertTrue(!WinePrefixDeltaReconciler.isAllowedPrefixPath(
                "windows/ntdll.dll"));
        assertTrue(!WinePrefixDeltaReconciler.isAllowedRuntimePath(
                "lib/wine/aarch64-windows/../../secret"));
        assertTrue(WinePrefixDeltaReconciler.isAllowedRuntimePath(
                "lib/wine/aarch64-windows/ntdll.dll"));
    }

    private Fixture fixture() throws Exception {
        return new Fixture(temporaryFolder.newFolder("runtime"), temporaryFolder.newFolder("windows"));
    }

    private static final class Fixture {
        final File runtime;
        final File windows;

        Fixture(File runtime, File windows) {
            this.runtime = runtime;
            this.windows = windows;
        }

        WinePrefixDeltaReconciler.Entry entry(
                String prefixPath,
                String runtimePath,
                String baseline,
                String candidate) throws Exception {
            File baselineFile = writePrefix(prefixPath, baseline);
            File candidateFile = writeRuntime(runtimePath, candidate);
            return new WinePrefixDeltaReconciler.Entry(
                    prefixPath,
                    runtimePath,
                    WinePrefixDeltaReconciler.sha256(baselineFile),
                    WinePrefixDeltaReconciler.sha256(candidateFile));
        }

        File writePrefix(String path, String content) throws Exception {
            return write(new File(windows, path), content);
        }

        File writeRuntime(String path, String content) throws Exception {
            return write(new File(runtime, path), content);
        }

        String read(String path) throws Exception {
            return new String(
                    Files.readAllBytes(new File(windows, path).toPath()),
                    StandardCharsets.UTF_8);
        }

        private File write(File file, String content) throws Exception {
            File parent = file.getParentFile();
            assertTrue(parent.isDirectory() || parent.mkdirs());
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return file;
        }
    }
}
