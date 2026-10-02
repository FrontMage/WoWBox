package com.winlator.box;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TitanProfileSyncConfigTest {
    @Test
    public void acceptsExpectedWindowsSourceAndBuildsScopedTarCommand() {
        TitanProfileSyncConfig config = new TitanProfileSyncConfig(
                "192.168.0.115",
                22,
                "Administrator",
                "D:\\World of Warcraft\\_classic_titan_\\");

        assertTrue(config.validate().isEmpty());
        assertTrue(config.buildTarCommand().contains(
                "-C \"D:\\World of Warcraft\\_classic_titan_\""));
        assertTrue(config.buildTarCommand().endsWith(
                "\"WTF\" \"Interface\\AddOns\""));
        assertFalse(config.buildMetadataCommand().contains(
                "D:\\World of Warcraft"));
        assertTrue(config.buildMetadataCommand().length() < 8192);
        String script = config.buildMetadataScript();
        assertTrue(script.contains("Get-CimInstance Win32_Process"));
        assertTrue(script.contains("$_.Name -ieq 'Wow.exe'"));
        assertTrue(script.contains("$_.Name -ieq 'WowClassic.exe'"));
        assertTrue(script.contains("$_.ExecutablePath.StartsWith($root+'\\'"));
        assertFalse(script.contains("ProcessName -match '^Wow'"));
    }

    @Test
    public void mapsPowerShellClixmlGateErrorsToShortMessages() {
        assertTrue(TitanProfileSshClient.sanitizeRemoteError(
                "#< CLIXML WOW_RUNNING <Objs Version=\"1.1.0.1\">")
                .equals("Windows source WoW is running"));
        assertTrue(TitanProfileSshClient.sanitizeRemoteError(
                "#< CLIXML TAR_MISSING <Objs Version=\"1.1.0.1\">")
                .equals("Windows tar.exe is unavailable"));
    }

    @Test
    public void rejectsRelativeTraversalAndShellMetacharacters() {
        assertFalse(TitanProfileSyncConfig.isSafeWindowsAbsolutePath("World of Warcraft"));
        assertFalse(TitanProfileSyncConfig.isSafeWindowsAbsolutePath(
                "D:\\World of Warcraft\\..\\Windows"));
        assertFalse(TitanProfileSyncConfig.isSafeWindowsAbsolutePath(
                "D:\\World of Warcraft & whoami"));
        assertFalse(TitanProfileSyncConfig.isSafeWindowsAbsolutePath(
                "D:\\World of Warcraft%TEMP%"));
        assertFalse(TitanProfileSyncConfig.isSafeWindowsAbsolutePath(
                "D:\\World of Warcraft\nwhoami"));
    }

    @Test
    public void validatesPortHostAndUsername() {
        assertFalse(new TitanProfileSyncConfig(
                "", 22, "Administrator", TitanProfileSyncConfig.DEFAULT_SOURCE_PATH)
                .validate().isEmpty());
        assertFalse(new TitanProfileSyncConfig(
                "host", 0, "Administrator", TitanProfileSyncConfig.DEFAULT_SOURCE_PATH)
                .validate().isEmpty());
        assertFalse(new TitanProfileSyncConfig(
                "host", 22, "", TitanProfileSyncConfig.DEFAULT_SOURCE_PATH)
                .validate().isEmpty());
    }

    @Test
    public void acceptsTheFixedGuestDirectoryWithoutTreatingItAsAnExecutable() {
        assertTrue(BoxRuntime.isValidGuestDirectory(
                TitanProfileSyncConfig.TARGET_GUEST_PATH));
        assertFalse(BoxRuntime.isValidGuestPath(
                TitanProfileSyncConfig.TARGET_GUEST_PATH));
    }

    @Test
    public void detectsAnyRemoteSnapshotChange() throws Exception {
        TitanProfileSshClient.RemoteSnapshot original = snapshot(
                2, 3, 5, 100, repeat('a', 64));
        assertTrue(original.isValid());
        assertTrue(original.sameTree(snapshot(
                2, 3, 5, 100, repeat('a', 64))));
        assertFalse(original.sameTree(snapshot(
                2, 3, 5, 101, repeat('a', 64))));
        assertFalse(original.sameTree(snapshot(
                2, 3, 5, 100, repeat('b', 64))));
    }

    @Test
    public void enforcesSourceSizePlusConfiguredSafetyMargin() {
        long mib = 1024L * 1024L;
        assertFalse(TitanProfileSyncManager.hasRequiredSpace(600 * mib, 100 * mib));
        assertTrue(TitanProfileSyncManager.hasRequiredSpace(612 * mib, 100 * mib));
        assertFalse(TitanProfileSyncManager.hasRequiredSpace(
                Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(TitanProfileSyncManager.hasRequiredSpace(-1, 1));
    }

    private static TitanProfileSshClient.RemoteSnapshot snapshot(
            int wtfFiles, int addonFiles, int totalFiles, long bytes, String hash) {
        return new TitanProfileSshClient.RemoteSnapshot(
                wtfFiles, addonFiles, totalFiles, bytes, hash);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
