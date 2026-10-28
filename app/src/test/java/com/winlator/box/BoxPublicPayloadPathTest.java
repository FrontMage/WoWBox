package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

public class BoxPublicPayloadPathTest {
    private File resolve(String kind, String source, String dir, String drive, String userDownloads) {
        return BoxPublicPayloadPath.resolve(kind, source, dir, drive,
                new File(userDownloads), new File(userDownloads, "WinlatorBox/test"));
    }

    @Test public void publishedDefaultUsesCurrentAndroidUserDownloads() {
        assertEquals("/storage/emulated/0/Download/FrontMage-BNet",
                resolve("directory", BoxPublicPayloadPath.DEFAULT_SOURCE, BoxPublicPayloadPath.DEFAULT_DIRECTORY,
                        "D", "/storage/emulated/0/Download").getPath());
        assertEquals("/storage/emulated/10/Download/FrontMage-BNet",
                resolve("directory", BoxPublicPayloadPath.DEFAULT_SOURCE, BoxPublicPayloadPath.DEFAULT_DIRECTORY,
                        "D", "/storage/emulated/10/Download").getPath());
    }

    @Test public void customAbsolutePathIsNeverRemappedOrRewritten() {
        String custom = "/storage/emulated/0/Download/Custom-Games";
        assertEquals(custom, resolve("directory", "file://" + custom, custom, "D",
                "/storage/emulated/10/Download").getPath());
        // Even a custom source using the default extract path is not the bundled profile.
        assertEquals(BoxPublicPayloadPath.DEFAULT_DIRECTORY,
                resolve("directory", "file:///custom-source", BoxPublicPayloadPath.DEFAULT_DIRECTORY, "D",
                        "/storage/emulated/10/Download").getPath());
    }

    @Test public void differentKindOrDriveDoesNotTriggerDefaultAlias() {
        assertFalse(BoxPublicPayloadPath.isDefault("archive", BoxPublicPayloadPath.DEFAULT_SOURCE,
                BoxPublicPayloadPath.DEFAULT_DIRECTORY, "D"));
        assertFalse(BoxPublicPayloadPath.isDefault("directory", BoxPublicPayloadPath.DEFAULT_SOURCE,
                BoxPublicPayloadPath.DEFAULT_DIRECTORY, "E"));
        assertTrue(BoxPublicPayloadPath.isDefault("directory", BoxPublicPayloadPath.DEFAULT_SOURCE,
                BoxPublicPayloadPath.DEFAULT_DIRECTORY, "D"));
        assertEquals(BoxPublicPayloadPath.DEFAULT_DIRECTORY,
                resolve("archive", BoxPublicPayloadPath.DEFAULT_SOURCE, BoxPublicPayloadPath.DEFAULT_DIRECTORY, "D",
                        "/storage/emulated/10/Download").getPath());
    }

    @Test public void missingCustomExtractDirectoryUsesExistingBoxFallback() {
        assertEquals("/storage/emulated/10/Download/WinlatorBox/test/payload",
                resolve("directory", "file:///somewhere", "", "D", "/storage/emulated/10/Download").getPath());
    }

    @Test public void resolvingDefaultForSecondaryUserDoesNotChangePersistedFields() {
        BoxSpec.Payload payload = new BoxSpec.Payload();
        payload.kind = "directory"; payload.source = BoxPublicPayloadPath.DEFAULT_SOURCE;
        payload.extractDir = BoxPublicPayloadPath.DEFAULT_DIRECTORY; payload.mountDrive = "D";
        assertEquals("/storage/emulated/10/Download/FrontMage-BNet",
                BoxPublicPayloadPath.resolve(payload.kind, payload.source, payload.extractDir, payload.mountDrive,
                        new File("/storage/emulated/10/Download"), new File("/unused")).getPath());
        assertEquals("directory", payload.kind);
        assertEquals("file:///storage/emulated/0/Download/FrontMage-BNet", payload.source);
        assertEquals("/storage/emulated/0/Download/FrontMage-BNet", payload.extractDir);
        assertEquals("D", payload.mountDrive);
    }
}
