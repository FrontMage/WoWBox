package com.winlator.core;

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

public class WineBuiltinRestoreTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void runtimeMarkerRequiresMatchingOrExplicitlyPreservedLegacyIdentity() {
        assertTrue(WineBuiltinRestore.runtimeMarkerAllowed("preserve", false, false, false));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("replace", false, false, false));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("preserve", true, false, false));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("replace", true, false, false));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("preserve", true, true, true));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("replace", true, true, true));
        assertFalse(WineBuiltinRestore.runtimeMarkerAllowed("preserve", false, true, false));
        assertTrue(WineBuiltinRestore.runtimeMarkerAllowed("preserve", true, false, true));
        assertTrue(WineBuiltinRestore.runtimeMarkerAllowed("replace", true, false, true));
    }

    @Test
    public void restoredFilesComeFromActiveArm64ecWineNotUnversionedLegacyCache() throws Exception {
        File root = temp.newFolder("fixture");
        File runtime = new File(root, "wine");
        File windows = new File(root, "prefix/windows");
        write(new File(root, "original_dlls/system32/ddraw.dll"), "old-cache-arm64");
        write(new File(root, "original_dlls/syswow64/ddraw.dll"), "old-cache-i386");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "new-arm64");
        write(new File(runtime, "lib/wine/i386-windows/ddraw.dll"), "new-i386");
        write(new File(windows, "system32/ddraw.dll"), "wrapper-arm64");
        write(new File(windows, "syswow64/ddraw.dll"), "wrapper-i386");

        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");

        assertEquals("new-arm64", read(new File(windows, "system32/ddraw.dll")));
        assertEquals("new-i386", read(new File(windows, "syswow64/ddraw.dll")));
        assertEquals("old-cache-arm64", read(new File(root, "original_dlls/system32/ddraw.dll")));
    }

    @Test
    public void sameWinePathChangedContentsAndRollbackAlwaysUseCurrentRuntime() throws Exception {
        File root = temp.newFolder("switch");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File source = new File(runtime, "lib/wine/aarch64-windows/ddraw.dll");
        File target = new File(windows, "system32/ddraw.dll");
        write(source, "wine-a");
        write(target, "wrapper");
        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
        assertEquals("wine-a", read(target));

        write(source, "wine-b");
        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
        assertEquals("wine-b", read(target));

        write(source, "wine-a");
        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
        assertEquals("wine-a", read(target));
    }

    @Test
    public void nativeX64UsesX8664NotArm64() throws Exception {
        File root = temp.newFolder("native");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        write(new File(runtime, "lib/wine/x86_64-windows/ddraw.dll"), "x64");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "arm64");
        write(new File(windows, "system32/ddraw.dll"), "wrapper");

        WineBuiltinRestore.restore(runtime, windows, false, "ddraw.dll");
        assertEquals("x64", read(new File(windows, "system32/ddraw.dll")));
    }

    @Test
    public void missingSourceForExistingArchitectureFailsWithoutChangingAnyDestination() throws Exception {
        File root = temp.newFolder("incomplete");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "new-arm64");
        File arm64 = new File(windows, "system32/ddraw.dll");
        File i386 = new File(windows, "syswow64/ddraw.dll");
        write(arm64, "before-arm64");
        write(i386, "before-i386");
        try {
            WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
            fail("A missing active Wine source must not partially restore destinations");
        } catch (java.io.IOException expected) {
            assertEquals("before-arm64", read(arm64));
            assertEquals("before-i386", read(i386));
        }
    }

    @Test
    public void createsMissingBuiltinWhenArchitectureDirectoryAndSourceExist() throws Exception {
        File root = temp.newFolder("missing-target");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "arm64");
        assertTrue(new File(windows, "system32").mkdirs());

        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
        assertEquals("arm64", read(new File(windows, "system32/ddraw.dll")));
    }

    @Test
    public void optionalBuiltinAbsentFromBothRuntimeAndPrefixIsIgnored() throws Exception {
        File root = temp.newFolder("missing-optional");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "arm64");
        write(new File(windows, "system32/ddraw.dll"), "old");

        WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll", "optional.dll");
        assertEquals("arm64", read(new File(windows, "system32/ddraw.dll")));
    }

    @Test
    public void symbolicLinkSourceFailsClosed() throws Exception {
        File root = temp.newFolder("source-symlink");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File source = new File(runtime, "lib/wine/aarch64-windows/ddraw.dll");
        File outside = new File(root, "outside");
        File target = new File(windows, "system32/ddraw.dll");
        write(outside, "untrusted");
        write(target, "before");
        assertTrue(source.getParentFile().mkdirs());
        Files.createSymbolicLink(source.toPath(), outside.toPath());
        try {
            WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
            fail("Symlink source must be rejected");
        } catch (java.io.IOException expected) {
            assertEquals("before", read(target));
        }
    }

    @Test
    public void missingSourceForSecondComponentPreservesFirstAndThirdPartyComponent() throws Exception {
        File root = temp.newFolder("multiple-components");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File first = new File(windows, "system32/ddraw.dll");
        File second = new File(windows, "system32/native-component.dll");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "new");
        write(first, "original");
        write(second, "third-party");
        try {
            WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll", "native-component.dll");
            fail("No verified Wine source for native component");
        } catch (java.io.IOException expected) {
            assertEquals("original", read(first));
            assertEquals("third-party", read(second));
        }
    }

    @Test
    public void interruptedTransactionRollsBackBeforeLaterValidationFailure() throws Exception {
        File root = temp.newFolder("interrupted");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File transaction = new File(windows, ".wine-builtin-restore");
        File target = new File(windows, "system32/ddraw.dll");
        assertTrue(runtime.mkdirs());
        write(target, "partially-installed");
        write(new File(transaction, "manifest"), "system32/ddraw.dll\t1\n");
        write(new File(transaction, "0.old"), "previous-value");
        try {
            WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
            fail("Missing runtime source must fail");
        } catch (java.io.IOException expected) {
            assertEquals("previous-value", read(target));
            assertTrue(!transaction.exists());
        }
    }

    @Test
    public void nativeX86UsesI386ForSystem32() throws Exception {
        File root = temp.newFolder("x86");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        write(new File(runtime, "lib/wine/i386-windows/ddraw.dll"), "i386");
        write(new File(runtime, "lib/wine/x86_64-windows/ddraw.dll"), "wrong-x64");
        File target = new File(windows, "system32/ddraw.dll");
        write(target, "before");

        WineBuiltinRestore.restore(runtime, windows, "x86", false, "ddraw.dll");
        assertEquals("i386", read(target));
    }

    @Test
    public void nativeOnlyComponentRetainedWhileOtherBuiltinsRestored() throws Exception {
        File root = temp.newFolder("native-only");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File builtin = new File(windows, "system32/ddraw.dll");
        File nativeOnly = new File(windows, "system32/d3dcsx_42.dll");
        write(new File(runtime, "lib/wine/aarch64-windows/ddraw.dll"), "new-wine");
        write(builtin, "old-wine");
        write(nativeOnly, "native-component");

        WineBuiltinRestore.restore(runtime, windows, "arm64ec", true, "ddraw.dll", "d3dcsx_42.dll");
        assertEquals("new-wine", read(builtin));
        assertEquals("native-component", read(nativeOnly));
    }

    @Test
    public void interruptedCleanupOfCommittedTransactionDoesNotRestoreOldFiles() throws Exception {
        File root = temp.newFolder("committed");
        File runtime = new File(root, "wine");
        File windows = new File(root, "windows");
        File transaction = new File(windows, ".wine-builtin-restore");
        File target = new File(windows, "system32/ddraw.dll");
        assertTrue(runtime.mkdirs());
        write(target, "new-value");
        write(new File(transaction, "manifest"), "system32/ddraw.dll\t1\n");
        write(new File(transaction, "0.old"), "old-value");
        write(new File(transaction, "committed"), "committed\n");
        try {
            WineBuiltinRestore.restore(runtime, windows, true, "ddraw.dll");
            fail("Missing runtime source must fail");
        } catch (java.io.IOException expected) {
            assertEquals("new-value", read(target));
            assertTrue(!transaction.exists());
        }
    }

    private static void write(File file, String contents) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
