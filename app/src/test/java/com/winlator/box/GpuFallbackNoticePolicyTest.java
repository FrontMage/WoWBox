package com.winlator.box;

import org.junit.Test;
import static org.junit.Assert.*;

public class GpuFallbackNoticePolicyTest {
    @Test public void onlyVerifiedFallbackShowsAfterRuntimeUpdate() {
        assertTrue(GpuFallbackNoticePolicy.showFallback(true,false,false));
        assertFalse(GpuFallbackNoticePolicy.showFallback(false,false,false));
        assertFalse(GpuFallbackNoticePolicy.showFallback(true,true,false));
        assertFalse(GpuFallbackNoticePolicy.showFallback(true,false,true));
    }
    @Test public void unverifiedDriversShowBlockedOnlyWhenNoUpdateHasPriority() {
        assertTrue(GpuFallbackNoticePolicy.showBlocked(true,false,false));
        assertFalse(GpuFallbackNoticePolicy.showBlocked(false,false,false));
        assertFalse(GpuFallbackNoticePolicy.showBlocked(true,true,false));
        assertFalse(GpuFallbackNoticePolicy.showBlocked(true,false,true));
    }
}
