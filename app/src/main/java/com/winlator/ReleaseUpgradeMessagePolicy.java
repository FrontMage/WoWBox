package com.winlator;

import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/** Classifies stable migration error codes, without exposing private paths. */
final class ReleaseUpgradeMessagePolicy {
    enum Kind { SESSION_ACTIVE, SESSION_UNKNOWN, SPACE, PROTECTED, OVERLAY, UNSUPPORTED, RECOVERY_PENDING, GENERIC }

    static Kind classify(String error) {
        if (error == null) return Kind.GENERIC;
        String normalized = error.toLowerCase(Locale.US);
        Set<String> codes = new HashSet<>();
        for (String part : normalized.split("[^a-z0-9_]+")) if (!part.isEmpty()) codes.add(part);
        if (any(codes, "strict_wine_idle_unknown", "release_upgrade_session_unknown",
                "release_recovery_session_unknown")) return Kind.SESSION_UNKNOWN;
        if (any(codes, "active_wine_processes", "active_box_session", "release_upgrade_session_not_finished",
                "release_recovery_session_not_finished", "release_recovery_requires_idle",
                "release_upgrade_requires_idle")) return Kind.SESSION_ACTIVE;
        if (any(codes, "release_upgrade_insufficient_free_space") ||
                normalized.contains("insufficient free space") || normalized.contains("not enough free space")) return Kind.SPACE;
        if (any(codes, "release_upgrade_requires_unprotected_installed_prefix", "protected_prefix")) return Kind.PROTECTED;
        if (any(codes, "release_upgrade_runtime_overlay_active", "runtime_overlay_active")) return Kind.OVERLAY;
        if (any(codes, "release_upgrade_validation_pending", "release_upgrade_committed_cleanup_pending",
                "release_recovery_failed", "release_recovery_required")) return Kind.RECOVERY_PENDING;
        if (normalized.contains("unexpected wine baseline entry:") ||
                normalized.contains("wine baseline type mismatch:") ||
                normalized.contains("wine baseline hash mismatch:") ||
                normalized.contains("wine baseline symlink mismatch:") ||
                normalized.contains("wine baseline entries missing:")) return Kind.UNSUPPORTED;
        if (any(codes, "release_upgrade_unsupported_old_or_new_runtime", "rwoa_upgrade_unsupported_baseline",
                "release_upgrade_unknown_prefix_file", "release_upgrade_prefix_file_missing_or_unsafe",
                "release_upgrade_layer_identity_or_hash_mismatch", "release_upgrade_existing_layers_unverified",
                "rwoa_upgrade_existing_layers_unverified", "rwoa_upgrade_incomplete_critical_baseline",
                "release_upgrade_input_bridge_mismatch", "release_upgrade_fex_changed",
                "release_upgrade_invalid_wine_baseline", "release_upgrade_metadata_missing_or_unsafe")) return Kind.UNSUPPORTED;
        return Kind.GENERIC;
    }

    static boolean requiresRecovery(String error) {
        if (error == null) return false;
        for (String code : error.toLowerCase(Locale.US).split("[^a-z0-9_]+")) {
            if (code.equals("release_upgrade_validation_pending") ||
                    code.equals("release_upgrade_committed_cleanup_pending") ||
                    code.equals("release_recovery_failed") || code.equals("release_recovery_required")) return true;
        }
        return false;
    }

    private static boolean any(Set<String> codes, String... expected) {
        for (String code : expected) if (codes.contains(code)) return true;
        return false;
    }
    private ReleaseUpgradeMessagePolicy() {}
}
