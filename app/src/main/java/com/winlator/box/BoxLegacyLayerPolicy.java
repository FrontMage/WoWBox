package com.winlator.box;

/** Legacy debug layer mutations are not part of the standard release contract. */
final class BoxLegacyLayerPolicy {
    private BoxLegacyLayerPolicy() {}

    static boolean isStandardPackage(String applicationId) {
        return "com.winlator.llm".equals(applicationId);
    }

    static boolean isBlockedRoute(String applicationId, String route) {
        if (!isStandardPackage(applicationId) || route == null) return false;
        return (route.startsWith("/automation/layers/") &&
                (route.endsWith("/install") || route.endsWith("/select"))) ||
                "/automation/prefix/reconcile".equals(route);
    }

    static void requireLegacyMutationAllowed(String applicationId) {
        if (isStandardPackage(applicationId)) {
            throw new IllegalStateException("legacy_layer_mutation_disabled");
        }
    }
}
