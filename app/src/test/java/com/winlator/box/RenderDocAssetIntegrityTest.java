package com.winlator.box;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

public class RenderDocAssetIntegrityTest {
    @Test
    public void refusesSameSizeCorruptionBeforeLoadingNativeCode() throws Exception {
        File file = File.createTempFile("renderdoc-integrity", ".so");
        try {
            Files.write(file.toPath(), new byte[] {1, 2, 3, 4});
            String expected = RenderDocAssetIntegrity.sha256(file);
            RenderDocAssetIntegrity.requireFile(file, 4, expected);
            Files.write(file.toPath(), new byte[] {1, 2, 3, 5});
            try { RenderDocAssetIntegrity.requireFile(file, 4, expected); fail("corruption accepted"); }
            catch (IOException expectedFailure) {
                assertTrue(expectedFailure.getMessage().startsWith("asset_integrity:"));
            }
        }
        finally { file.delete(); }
    }

    @Test
    public void refusesTruncatedCopyAndMissingAsset() throws Exception {
        File file = File.createTempFile("renderdoc-integrity", ".so");
        try {
            Files.write(file.toPath(), new byte[] {1, 2, 3, 4});
            String expected = RenderDocAssetIntegrity.sha256(file);
            Files.write(file.toPath(), new byte[] {1, 2});
            try { RenderDocAssetIntegrity.requireFile(file, 4, expected); fail("truncation accepted"); }
            catch (IOException expectedFailure) {}
            file.delete();
            try { RenderDocAssetIntegrity.requireFile(file, 4, expected); fail("missing asset accepted"); }
            catch (IOException expectedFailure) {}
        }
        finally { file.delete(); }
    }

    @Test
    public void manifestCannotEscapeItsPrivateVersionDirectory() {
        assertTrue(RenderDocAssetIntegrity.safeBasename("libvulkan.so.1"));
        assertTrue(RenderDocAssetIntegrity.safeBasename("capture-replay"));
        for (String path : new String[] {"../lib.so", "/lib.so", "sub/lib.so", "sub\\lib.so", "..", ""}) {
            assertFalse(path, RenderDocAssetIntegrity.safeBasename(path));
        }
    }

    @Test
    public void createsVerifiedRegularLoaderAliasWithoutAnExtraPackagedFile() throws Exception {
        File directory = Files.createTempDirectory("renderdoc-loader-alias").toFile();
        File loader = new File(directory, "libvulkan.so.1");
        File alias = new File(directory, "libvulkan.so");
        try {
            Files.write(loader.toPath(), new byte[] {1, 2, 3, 4});
            String hash = RenderDocAssetIntegrity.sha256(loader);
            RenderDocAssetIntegrity.createLoaderAlias(directory, loader.getName(), 4, hash);
            assertTrue(alias.isFile());
            assertFalse(Files.isSymbolicLink(alias.toPath()));
            assertEquals(hash, RenderDocAssetIntegrity.sha256(alias));
            RenderDocAssetIntegrity.requireLoaderAlias(directory, 4, hash);
        }
        finally { alias.delete(); loader.delete(); directory.delete(); }
    }

    @Test
    public void rejectsMissingCorruptAndSymlinkLoaderAliases() throws Exception {
        File directory = Files.createTempDirectory("renderdoc-loader-alias").toFile();
        File loader = new File(directory, "libvulkan.so.1");
        File alias = new File(directory, "libvulkan.so");
        try {
            Files.write(loader.toPath(), new byte[] {1, 2, 3, 4});
            String hash = RenderDocAssetIntegrity.sha256(loader);
            try { RenderDocAssetIntegrity.requireLoaderAlias(directory, 4, hash); fail("missing alias accepted"); }
            catch (IOException expectedFailure) {}
            Files.write(alias.toPath(), new byte[] {1, 2, 3, 5});
            try { RenderDocAssetIntegrity.requireLoaderAlias(directory, 4, hash); fail("corrupt alias accepted"); }
            catch (IOException expectedFailure) {}
            alias.delete();
            Files.createSymbolicLink(alias.toPath(), loader.toPath());
            try { RenderDocAssetIntegrity.requireLoaderAlias(directory, 4, hash); fail("symlink alias accepted"); }
            catch (IOException expectedFailure) { assertEquals("loader_alias_symlink", expectedFailure.getMessage()); }
        }
        finally { alias.delete(); loader.delete(); directory.delete(); }
    }
}
