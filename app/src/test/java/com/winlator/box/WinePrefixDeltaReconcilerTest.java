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
