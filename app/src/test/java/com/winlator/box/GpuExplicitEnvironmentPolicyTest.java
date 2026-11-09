package com.winlator.box;

import com.winlator.core.EnvVars;
import java.util.HashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpuExplicitEnvironmentPolicyTest {
    @Test public void nonBoxAndNonArm64ecKeepOriginalEarlyInjection() {
        assertFalse(GpuExplicitEnvironmentPolicy.shouldDefer(false, false)); assertFalse(GpuExplicitEnvironmentPolicy.shouldDefer(false, true));
        assertFalse(GpuExplicitEnvironmentPolicy.shouldDefer(true, false)); assertTrue(GpuExplicitEnvironmentPolicy.shouldDefer(true, true));
    }
    @Test public void explicitPairOverridesPreviouslyGeneratedPair() {
        HashMap<String,String> user = new HashMap<>();
        user.put("ADRENOTOOLS_DRIVER_PATH", "/user/"); user.put("ADRENOTOOLS_DRIVER_NAME", "user.so");
        EnvVars merged = new EnvVars(); merged.put("ADRENOTOOLS_DRIVER_PATH", "/generated/"); merged.put("ADRENOTOOLS_DRIVER_NAME", "old.so");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(user, null, merged));
        assertEquals("/user/", merged.get("ADRENOTOOLS_DRIVER_PATH")); assertEquals("user.so", merged.get("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void incompleteAndEmptyValuesNeverBorrowGeneratedCounterpart() {
        HashMap<String,String> user = new HashMap<>(); user.put("ADRENOTOOLS_DRIVER_NAME", "");
        EnvVars merged = new EnvVars(); merged.put("ADRENOTOOLS_DRIVER_PATH", "/generated/"); merged.put("ADRENOTOOLS_DRIVER_NAME", "old.so");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(user, null, merged));
        assertFalse(merged.has("ADRENOTOOLS_DRIVER_PATH")); assertTrue(merged.has("ADRENOTOOLS_DRIVER_NAME")); assertEquals("", merged.get("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void noExplicitValuesLeavesExistingEnvironmentAlone() {
        EnvVars merged = new EnvVars(); merged.put("ADRENOTOOLS_DRIVER_NAME", "old.so");
        assertFalse(GpuExplicitEnvironmentPolicy.apply(new HashMap<>(), null, merged)); assertEquals("old.so",merged.get("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void launchOverridesApplyAfterPersistentEnvironment() {
        HashMap<String,String> user = new HashMap<>(); user.put("ADRENOTOOLS_DRIVER_PATH", "/persistent/"); user.put("ADRENOTOOLS_DRIVER_NAME", "persistent.so");
        EnvVars launch = new EnvVars(); launch.put("ADRENOTOOLS_DRIVER_NAME", "debug.so");
        EnvVars merged = new EnvVars(); merged.put("ADRENOTOOLS_DRIVER_PATH", "/generated/"); merged.put("ADRENOTOOLS_DRIVER_NAME", "generated.so");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(user, launch, merged)); assertEquals("/persistent/",merged.get("ADRENOTOOLS_DRIVER_PATH")); assertEquals("debug.so",merged.get("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void shortcutPartialNeverInheritsGeneratedCounterpart() {
        EnvVars launch = new EnvVars(); launch.put("ADRENOTOOLS_DRIVER_PATH", "/shortcut/");
        EnvVars merged = new EnvVars(); merged.put("ADRENOTOOLS_DRIVER_PATH", "/generated/"); merged.put("ADRENOTOOLS_DRIVER_NAME", "generated.so");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(new HashMap<>(), launch, merged)); assertEquals("/shortcut/",merged.get("ADRENOTOOLS_DRIVER_PATH")); assertFalse(merged.has("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void explicitIcdReplacesGeneratedWrapperWithoutSelectingAdrenotools() {
        EnvVars launch = new EnvVars(); launch.put("VK_ICD_FILENAMES", "/custom/icd.json");
        EnvVars merged = new EnvVars(); merged.put("VK_ICD_FILENAMES", "/generated/wrapper.json"); merged.put("ADRENOTOOLS_DRIVER_PATH", "/generated/"); merged.put("ADRENOTOOLS_DRIVER_NAME", "generated.so");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(new HashMap<>(),launch,merged));
        assertEquals("/custom/icd.json",merged.get("VK_ICD_FILENAMES")); assertFalse(merged.has("ADRENOTOOLS_DRIVER_PATH")); assertFalse(merged.has("ADRENOTOOLS_DRIVER_NAME"));
    }
    @Test public void explicitEmptyIcdRemainsEmptyRatherThanGenerated() {
        HashMap<String,String> user = new HashMap<>(); user.put("VK_ICD_FILENAMES", "");
        EnvVars merged = new EnvVars(); merged.put("VK_ICD_FILENAMES", "/generated/wrapper.json");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(user,null,merged)); assertEquals("",merged.get("VK_ICD_FILENAMES"));
    }
    @Test public void explicitModernIcdDoesNotMixWithGeneratedLegacyIcd() {
        EnvVars launch = new EnvVars(); launch.put("VK_DRIVER_FILES", "/custom/modern.json");
        EnvVars merged = new EnvVars(); merged.put("VK_ICD_FILENAMES", "/generated/wrapper.json");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(new HashMap<>(),launch,merged)); assertFalse(merged.has("VK_ICD_FILENAMES")); assertEquals("/custom/modern.json",merged.get("VK_DRIVER_FILES"));
    }
    @Test public void additiveIcdKeepsExistingWrapperForExistingDefaultDriver() {
        EnvVars launch = new EnvVars(); launch.put("VK_ADD_DRIVER_FILES", "/custom/extra.json");
        EnvVars merged = new EnvVars(); merged.put("VK_ICD_FILENAMES", "/generated/wrapper.json");
        assertTrue(GpuExplicitEnvironmentPolicy.apply(new HashMap<>(),launch,merged)); assertTrue(GpuExplicitEnvironmentPolicy.onlyAdditiveIcd(new HashMap<>(),launch));
        assertEquals("/generated/wrapper.json",merged.get("VK_ICD_FILENAMES")); assertEquals("/custom/extra.json",merged.get("VK_ADD_DRIVER_FILES"));
    }
}
