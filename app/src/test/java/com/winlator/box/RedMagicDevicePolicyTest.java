package com.winlator.box;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RedMagicDevicePolicyTest {
    @Test public void onlyKnownModelAndSocMatch() {
        assertTrue(RedMagicDevicePolicy.matchesDevice("NX809J", "SM8850"));
        assertTrue(RedMagicDevicePolicy.matchesDevice("nx809j", "sm8850"));
        assertFalse(RedMagicDevicePolicy.matchesDevice("NX809J", "SM8750"));
        assertFalse(RedMagicDevicePolicy.matchesDevice("other", "SM8850"));
        assertFalse(RedMagicDevicePolicy.matchesDevice(null, "SM8850"));
        assertFalse(RedMagicDevicePolicy.matchesDevice("NX809J", null));
    }
}
