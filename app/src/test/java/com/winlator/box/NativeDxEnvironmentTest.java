package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class NativeDxEnvironmentTest {
    private static final String OLD = "C:\\windows\\wowbox-native-arm64\\old";
    private static final String NEW = "C:\\windows\\wowbox-native-arm64\\new";

    @Test
    public void updatesOnlyTheRecordedOwnedDirectory() {
        String original = "C:\\Tools;;" + OLD + ";C:\\Other;";
        NativeDxEnvironment.Update update = NativeDxEnvironment.mergePath(original, OLD, NEW);
        assertEquals("C:\\Tools;;C:\\Other;" + NEW, update.path);
        assertEquals(NEW, update.ownedDirectory);
    }

    @Test
    public void preservesAnExistingUserPathWithoutClaimingIt() {
        String original = "C:\\Tools;" + NEW + ";";
        NativeDxEnvironment.Update update = NativeDxEnvironment.mergePath(original, "", NEW);
        assertEquals(original, update.path);
        assertEquals("", update.ownedDirectory);
    }

    @Test
    public void cannotRemoveAnUnrelatedPathThroughTheOwnershipRecord() {
        NativeDxEnvironment.Update update =
                NativeDxEnvironment.mergePath("C:\\Tools;C:\\Other", "C:\\Tools", NEW);
        assertEquals("C:\\Tools;C:\\Other;" + NEW, update.path);
    }

    @Test
    public void registryPathReadPreservesExpandableValuesAndCase() throws Exception {
        File registry = File.createTempFile("native-dx-registry", ".reg");
        try {
            Files.write(registry.toPath(), ("WINE REGISTRY Version 2\n\n[Environment] 1\n" +
                    "\"PATH\"=str(2):\"%SystemRoot%\\\\system32;C:\\\\Tools\"\n\n" +
                    "[Unrelated] 1\n\"Path\"=\"unrelated\"\n").getBytes(StandardCharsets.UTF_8));
            NativeDxEnvironment.PathValue path = NativeDxEnvironment.readPath(registry);
            assertEquals("PATH", path.name);
            assertEquals("%SystemRoot%\\system32;C:\\Tools", path.value);
            assertTrue(path.expand);
        }
        finally { registry.delete(); }
    }

    @Test(expected = IOException.class)
    public void rejectsDuplicateCaseAliasesBeforeChangingTheRegistry() throws Exception {
        File registry = File.createTempFile("native-dx-registry", ".reg");
        try {
            Files.write(registry.toPath(), ("[Environment] 1\n" +
                    "\"Path\"=\"first\"\n\"PATH\"=\"second\"\n").getBytes(StandardCharsets.UTF_8));
            NativeDxEnvironment.readPath(registry);
        }
        finally { registry.delete(); }
    }

    @Test(expected = IllegalArgumentException.class)
    public void forbidsAPathIntoTheGameDirectory() {
        BoxSpec.Layer layer = new BoxSpec.Layer();
        layer.target = "/home/xuser-box/.wine/drive_c/Games/World of Warcraft";
        NativeDxEnvironment.windowsDirectory(layer);
    }
}
