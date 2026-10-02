package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoxSpecAssetSelectionTest {
    @Test
    public void preservesExistingDefaultsWhenNoExplicitMinimalSpecIsSet() {
        assertEquals(
                BoxSpec.DEFAULT_ASSET_PATH,
                BoxSpec.selectBundledAssetPath(false, false, ""));
        assertEquals(
                BoxSpec.ODIN_UU_EFLAGS_INLINE_ASSET_PATH,
                BoxSpec.selectBundledAssetPath(false, true, ""));
        assertEquals(
                BoxSpec.ODIN_STANDARD_LOCK_WITNESS_ASSET_PATH,
                BoxSpec.selectBundledAssetPath(true, false, ""));
        assertEquals(
                BoxSpec.ODIN_UU_EFLAGS_INLINE_ASSET_PATH,
                BoxSpec.selectBundledAssetPath(true, true, ""));
    }

    @Test
    public void explicitMinimalSpecOverridesEitherMinimalVariant() {
        String asset = "box_spec_thor_suspend_fix.json";
        assertEquals(asset, BoxSpec.selectBundledAssetPath(true, false, asset));
        assertEquals(asset, BoxSpec.selectBundledAssetPath(true, true, asset));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMinimalSpecPathTraversal() {
        BoxSpec.selectBundledAssetPath(true, false, "../box_spec_thor.json");
    }

    @Test
    public void validatesOnlySafeBoxSpecBasenames() {
        assertTrue(BoxSpec.isSafeSpecAssetBasename("box_spec.json"));
        assertTrue(BoxSpec.isSafeSpecAssetBasename("box_spec_thor-6a.v1.json"));
        assertFalse(BoxSpec.isSafeSpecAssetBasename("thor.json"));
        assertFalse(BoxSpec.isSafeSpecAssetBasename("box_spec_../thor.json"));
        assertFalse(BoxSpec.isSafeSpecAssetBasename("box_spec_thor\\candidate.json"));
    }

    @Test
    public void launchTargetAcceptsControlsProfileId() {
        BoxSpec.Target target = new BoxSpec.Target();
        target.id = "maple-classic";
        target.guestPath = "C:\\Maple\\MxdLauncher.exe";
        target.workingDirectory = "C:\\Maple";
        target.controlsProfileId = 4;

        BoxRuntime.validateLaunchTarget(target);
        assertEquals(4, target.controlsProfileId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void launchTargetRejectsNegativeControlsProfileId() {
        BoxSpec.Target target = new BoxSpec.Target();
        target.id = "maple-classic";
        target.guestPath = "C:\\Maple\\MxdLauncher.exe";
        target.workingDirectory = "C:\\Maple";
        target.controlsProfileId = -1;

        BoxRuntime.validateLaunchTarget(target);
    }

    @Test
    public void launchTargetInheritsBundledControlsProfileForSameExecutable() {
        BoxSpec.Target active = new BoxSpec.Target();
        active.id = "maple-classic";
        active.guestPath = "C:\\Maple\\MxdLauncher.exe";
        BoxSpec.Target bundled = new BoxSpec.Target();
        bundled.id = active.id;
        bundled.guestPath = active.guestPath;
        bundled.controlsProfileId = 4;

        assertEquals(4, BoxRuntime.resolveControlsProfileId(active, bundled));

        active.controlsProfileId = 7;
        assertEquals(7, BoxRuntime.resolveControlsProfileId(active, bundled));
    }

    @Test
    public void launchTargetRejectsBundledControlsProfileForDifferentExecutable() {
        BoxSpec.Target active = new BoxSpec.Target();
        active.id = "maple-classic";
        active.guestPath = "C:\\Maple\\MxdLauncher.exe";
        BoxSpec.Target bundled = new BoxSpec.Target();
        bundled.id = active.id;
        bundled.guestPath = "C:\\Other\\Launcher.exe";
        bundled.controlsProfileId = 4;

        assertEquals(0, BoxRuntime.resolveControlsProfileId(active, bundled));
    }

    @Test
    public void installedIdentityIncludesWinePrefixDeltaContract() {
        BoxSpec.Layer layer = new BoxSpec.Layer();
        layer.source = "asset://prefix.tzst";
        layer.runtimeIdentifier = "candidate-runtime";
        layer.checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        layer.target = "/home/xuser-box";
        layer.winePrefixDeltaManifest = "asset://wine_prefix_delta/thor.json";
        layer.winePrefixDeltaChecksum =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        assertTrue(BoxDebugServer.installedLayerIdentityMatches(
                layer,
                layer.source,
                layer.runtimeIdentifier,
                layer.checksum,
                layer.target,
                layer.winePrefixDeltaManifest,
                layer.winePrefixDeltaChecksum));
        assertFalse(BoxDebugServer.installedLayerIdentityMatches(
                layer,
                layer.source,
                layer.runtimeIdentifier,
                layer.checksum,
                layer.target,
                layer.winePrefixDeltaManifest,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
    }

    @Test
    public void managedPrefixAndInputMustFollowWineRuntime() {
        BoxSpec spec = specWithManagedRuntimeBindings("runtime-a");
        assertTrue(BoxInstaller.managedWineRuntimeBindingsMatch(spec));

        layer(spec, "input-bridge-stack").runtimeIdentifier = "runtime-b";
        assertFalse(BoxInstaller.managedWineRuntimeBindingsMatch(spec));
    }

    @Test
    public void inPlaceCandidateRequiresSameTargetAndReplacePolicy() {
        BoxSpec.Layer active = layer(
                "wine-runtime",
                "asset://control.txz",
                "runtime-a",
                "/opt/runtime-a");
        BoxSpec.Layer candidate = layer(
                "wine-runtime",
                "asset://candidate.txz",
                "runtime-a",
                "/opt/runtime-a");
        candidate.replacePolicy = "replace";
        assertTrue(BoxInstaller.isInPlaceWineCandidateLayer(active, candidate));

        candidate.runtimeIdentifier = "runtime-b";
        assertFalse(BoxInstaller.isInPlaceWineCandidateLayer(active, candidate));
        candidate.runtimeIdentifier = "runtime-a";
        candidate.replacePolicy = "preserve";
        assertFalse(BoxInstaller.isInPlaceWineCandidateLayer(active, candidate));
    }

    @Test
    public void wineCandidateMayActivatePairedPrefixDeltaMetadata() throws Exception {
        BoxSpec active = specWithManagedRuntimeBindings("runtime-a");
        BoxSpec candidate = pairedCandidate("runtime-a");
        layer(candidate, "wine-runtime").capabilities.add("amd64-driver-host");

        assertTrue(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                candidate));

        BoxSpec next = pairedCandidate("runtime-a");
        layer(next, "wine-runtime").source = "asset://candidate-next.txz";
        layer(next, "wine-runtime").checksum = checksum('d');
        layer(next, "prefix-template").winePrefixDeltaManifest =
                "asset://wine_prefix_delta/candidate-next.json";
        layer(next, "prefix-template").winePrefixDeltaChecksum = checksum('e');
        assertTrue(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                candidate,
                next));
    }

    @Test
    public void wineCandidateStillAllowsExistingNoDeltaFlow() throws Exception {
        BoxSpec active = specWithManagedRuntimeBindings("runtime-a");
        BoxSpec candidate = specWithManagedRuntimeBindings("runtime-a");
        layer(candidate, "wine-runtime").source = "asset://candidate.txz";
        layer(candidate, "wine-runtime").checksum = checksum('b');

        assertTrue(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                candidate));
    }

    @Test
    public void wineCandidateRejectsUnrelatedPrefixChanges() throws Exception {
        BoxSpec active = specWithManagedRuntimeBindings("runtime-a");

        BoxSpec sourceChanged = pairedCandidate("runtime-a");
        layer(sourceChanged, "prefix-template").source = "asset://other-prefix.tzst";
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                sourceChanged));

        BoxSpec checksumChanged = pairedCandidate("runtime-a");
        layer(checksumChanged, "prefix-template").checksum = checksum('f');
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                checksumChanged));

        BoxSpec targetChanged = pairedCandidate("runtime-a");
        layer(targetChanged, "prefix-template").target = "/home/other";
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                targetChanged));

        BoxSpec runtimeChanged = pairedCandidate("runtime-a");
        layer(runtimeChanged, "prefix-template").runtimeIdentifier = "runtime-b";
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                runtimeChanged));

        BoxSpec policyChanged = pairedCandidate("runtime-a");
        layer(policyChanged, "prefix-template").replacePolicy = "preserve";
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                policyChanged));
    }

    @Test
    public void wineCandidateRejectsOtherLayerChangesAndDeltaRemoval() throws Exception {
        BoxSpec active = specWithManagedRuntimeBindings("runtime-a");
        BoxSpec unrelated = pairedCandidate("runtime-a");
        layer(unrelated, "input-bridge-stack").source = "asset://other-input.tzst";
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                active,
                unrelated));

        BoxSpec activeWithDelta = pairedCandidate("runtime-a");
        BoxSpec removed = specWithManagedRuntimeBindings("runtime-a");
        layer(removed, "wine-runtime").source = "asset://candidate-next.txz";
        layer(removed, "wine-runtime").checksum = checksum('d');
        assertFalse(BoxInstaller.sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
                activeWithDelta,
                removed));
    }

    @Test
    public void pairedPrefixLayerRemainsMismatchedUntilReconcileRecordsIt()
            throws Exception {
        BoxSpec active = specWithManagedRuntimeBindings("runtime-a");
        BoxSpec candidate = pairedCandidate("runtime-a");
        BoxSpec.Layer expected = layer(candidate, "prefix-template");
        BoxSpec.Layer installedBeforeReconcile = layer(active, "prefix-template");

        assertFalse(BoxDebugServer.installedLayerIdentityMatches(
                expected,
                installedBeforeReconcile.source,
                installedBeforeReconcile.runtimeIdentifier,
                installedBeforeReconcile.checksum,
                installedBeforeReconcile.target,
                installedBeforeReconcile.winePrefixDeltaManifest,
                installedBeforeReconcile.winePrefixDeltaChecksum));
        assertTrue(BoxDebugServer.installedLayerIdentityMatches(
                expected,
                expected.source,
                expected.runtimeIdentifier,
                expected.checksum,
                expected.target,
                expected.winePrefixDeltaManifest,
                expected.winePrefixDeltaChecksum));
    }

    private static BoxSpec specWithManagedRuntimeBindings(String runtimeIdentifier) {
        BoxSpec spec = new BoxSpec();
        spec.boxId = "test";
        spec.layers.add(layer(
                "wine-runtime",
                "asset://wine.txz",
                runtimeIdentifier,
                "/opt/" + runtimeIdentifier));
        spec.layers.add(layer(
                "prefix-template",
                "asset://prefix.tzst",
                runtimeIdentifier,
                "/home/xuser-box"));
        spec.layers.add(layer(
                "input-bridge-stack",
                "asset://input.tzst",
                runtimeIdentifier,
                "/home/xuser-box/.wine/drive_c/windows/system32"));
        return spec;
    }

    private static BoxSpec.Layer layer(
            String type,
            String source,
            String runtimeIdentifier,
            String target) {
        BoxSpec.Layer layer = new BoxSpec.Layer();
        layer.type = type;
        layer.source = source;
        layer.runtimeIdentifier = runtimeIdentifier;
        layer.target = target;
        layer.checksum =
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        return layer;
    }

    private static BoxSpec.Layer layer(BoxSpec spec, String type) {
        for (BoxSpec.Layer layer : spec.layers) {
            if (type.equals(layer.type)) return layer;
        }
        throw new AssertionError(type);
    }

    private static BoxSpec pairedCandidate(String runtimeIdentifier) {
        BoxSpec candidate = specWithManagedRuntimeBindings(runtimeIdentifier);
        layer(candidate, "wine-runtime").source = "asset://candidate.txz";
        layer(candidate, "wine-runtime").checksum = checksum('b');
        layer(candidate, "prefix-template").winePrefixDeltaManifest =
                "asset://wine_prefix_delta/candidate.json";
        layer(candidate, "prefix-template").winePrefixDeltaChecksum = checksum('c');
        return candidate;
    }

    private static String checksum(char value) {
        StringBuilder checksum = new StringBuilder(64);
        for (int i = 0; i < 64; i++) checksum.append(value);
        return checksum.toString();
    }
}
