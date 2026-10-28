package com.winlator.box;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BoxInitialInstallGuardTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void freshInstallCanRetryAfterOwnedPartialPrefix() throws Exception {
        File root = temp.newFolder("fresh");
        File imagefs = new File(root, "imagefs");
        File marker = new File(root, "owner");
        File spec = write(root, "spec.json", "original-spec");
        BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false);
        assertTrue(BoxInitialInstallGuard.mayResume(marker, spec, false));
        assertTrue(new File(imagefs, "home/xuser-box/.wine").mkdirs());
        BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false);
        BoxInitialInstallGuard.complete(marker);
        assertFalse(marker.exists());
    }

    @Test public void unknownExistingImagefsNeverBecomesOwned() throws Exception {
        File root = temp.newFolder("unknown");
        File imagefs = new File(root, "imagefs");
        File privateFile = write(imagefs, "home/xuser-box/.wine/user.dat", "keep");
        File marker = new File(root, "owner");
        File spec = write(root, "spec.json", "spec");
        try { BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("existing_imagefs")); }
        assertTrue(privateFile.isFile()); assertFalse(marker.exists());
    }

    @Test public void changedSpecCannotReuseOwnedMarker() throws Exception {
        File root = temp.newFolder("changed"); File imagefs = new File(root, "imagefs");
        File marker = new File(root, "owner"); File spec = write(root, "spec.json", "old");
        BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false);
        write(root, "spec.json", "new");
        assertFalse(BoxInitialInstallGuard.mayResume(marker, spec, false));
        try { BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("marker_mismatch")); }
    }

    @Test public void installedDataIsNeverOwnedForReinstall() throws Exception {
        File root = temp.newFolder("installed"); File marker = new File(root, "owner");
        File spec = write(root, "spec.json", "spec");
        try { BoxInitialInstallGuard.beginOrResume(new File(root, "imagefs"), marker, spec, true); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("existing_install")); }
        assertFalse(marker.exists());
    }

    @Test public void interruptedPendingMarkerCanRetryBeforeAnyImagefsExists() throws Exception {
        File root = temp.newFolder("pending"); File imagefs = new File(root, "imagefs");
        File marker = new File(root, "owner"); File spec = write(root, "spec.json", "spec");
        write(root, "owner.pending", "incomplete");
        BoxInitialInstallGuard.beginOrResume(imagefs, marker, spec, false);
        assertTrue(BoxInitialInstallGuard.mayResume(marker, spec, false));
        assertFalse(new File(root, "owner.pending").exists());
        assertFalse(imagefs.exists());
    }

    @Test public void initialPayloadMustBeThePublicDirectoryWithoutArchiveDeletion() {
        BoxSpec.Payload bundled = new BoxSpec.Payload();
        bundled.kind = "directory";
        bundled.source = "file:///storage/emulated/0/Download/FrontMage-BNet";
        bundled.extractDir = "/storage/emulated/0/Download/FrontMage-BNet";
        bundled.mountDrive = "D";
        BoxSpec.Payload requested = new BoxSpec.Payload();
        requested.kind = bundled.kind; requested.source = bundled.source;
        requested.extractDir = bundled.extractDir; requested.mountDrive = bundled.mountDrive;
        assertTrue(BoxInstaller.isAllowedPublicInitialPayload(requested, bundled));
        requested.kind = "archive";
        assertFalse(BoxInstaller.isAllowedPublicInitialPayload(requested, bundled));
        requested.kind = "directory";
        requested.extractDir = "/storage/emulated/0/Download/../user-data";
        assertFalse(BoxInstaller.isAllowedPublicInitialPayload(requested, bundled));
        requested.extractDir = "/storage/emulated/0/Download/Other";
        requested.source = "file:///storage/emulated/0/Download/Other";
        assertFalse(BoxInstaller.isAllowedPublicInitialPayload(requested, bundled));
    }

    private File write(File root, String name, String data) throws IOException {
        File f = new File(root, name); assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
        Files.write(f.toPath(), data.getBytes(StandardCharsets.UTF_8)); return f;
    }
}
