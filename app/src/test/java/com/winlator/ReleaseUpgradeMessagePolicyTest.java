package com.winlator;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class ReleaseUpgradeMessagePolicyTest {
    @Test public void activeAndUnknownSessionsGetDifferentGuidance() {
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SESSION_ACTIVE,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_failed: release_upgrade_session_not_finished"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SESSION_ACTIVE,
                ReleaseUpgradeMessagePolicy.classify("active_wine_processes"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SESSION_UNKNOWN,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_failed: strict_wine_idle_unknown"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SESSION_UNKNOWN,
                ReleaseUpgradeMessagePolicy.classify("release_recovery_session_unknown"));
    }
    @Test public void spaceProtectedOverlayAndUnknownFilesHaveSpecificGuidance() {
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SPACE,
                ReleaseUpgradeMessagePolicy.classify("Insufficient free space to stage the release"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.PROTECTED,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_requires_unprotected_installed_prefix"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.OVERLAY,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_runtime_overlay_active"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.UNSUPPORTED,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_unknown_prefix_file: system32/foo.dll"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.UNSUPPORTED,
                ReleaseUpgradeMessagePolicy.classify("rwoa_upgrade_incomplete_critical_baseline: prefix-template"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.UNSUPPORTED,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_failed: Unexpected Wine baseline entry: file.dll"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.UNSUPPORTED,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_failed: Wine baseline hash mismatch: file.dll"));
    }
    @Test public void recoveryDoesNotClaimRollbackAndUnknownTextDoesNotMimicCodes() {
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.RECOVERY_PENDING,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_failed: release_upgrade_validation_pending"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.RECOVERY_PENDING,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_committed_cleanup_pending"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.SESSION_UNKNOWN,
                ReleaseUpgradeMessagePolicy.classify("release_upgrade_validation_pending: strict_wine_idle_unknown"));
        assertTrue(ReleaseUpgradeMessagePolicy.requiresRecovery("release_upgrade_validation_pending: strict_wine_idle_unknown"));
        assertFalse(ReleaseUpgradeMessagePolicy.requiresRecovery("release_upgrade_session_not_finished"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.GENERIC,
                ReleaseUpgradeMessagePolicy.classify("some_unknown_failure"));
        assertEquals(ReleaseUpgradeMessagePolicy.Kind.GENERIC,
                ReleaseUpgradeMessagePolicy.classify("prefix_protected_prefix_suffix"));
    }
}
