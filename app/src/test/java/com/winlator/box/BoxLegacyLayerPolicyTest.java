package com.winlator.box;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class BoxLegacyLayerPolicyTest {
    @Test public void blocksAllStandardReleaseLegacyMutations() {
        String[] routes = {"gpu-driver/install", "gpu-driver/select", "graphics-wrapper-stack/install",
                "cpu-emu-stack/install", "input-bridge-stack/install", "frame-generation-stack/install",
                "dx-wrapper-stack/install", "dx-wrapper-stack/select", "native-dx-wrapper-stack/install",
                "wine-runtime/install"};
        for (String route : routes) {
            assertTrue(route, BoxLegacyLayerPolicy.isBlockedRoute("com.winlator.llm", "/automation/layers/" + route));
            assertFalse(route, BoxLegacyLayerPolicy.isBlockedRoute("com.blizzard.diablo.immortal", "/automation/layers/" + route));
        }
        assertTrue(BoxLegacyLayerPolicy.isBlockedRoute("com.winlator.llm", "/automation/prefix/reconcile"));
        for (String route : new String[]{"/automation/install", "/automation/layers/verify", "/automation/preflight",
                "/automation/launch", "/automation/stop", "/spec"}) {
            assertFalse(route, BoxLegacyLayerPolicy.isBlockedRoute("com.winlator.llm", route));
        }
    }

    @Test public void lowerLevelGuardRejectsStandardButAllowsOtherVariants() {
        try { BoxLegacyLayerPolicy.requireLegacyMutationAllowed("com.winlator.llm"); fail(); }
        catch (IllegalStateException expected) { assertEquals("legacy_layer_mutation_disabled", expected.getMessage()); }
        BoxLegacyLayerPolicy.requireLegacyMutationAllowed("com.blizzard.diablo.immortal");
    }

    @Test public void concurrentLegacyRequestsCannotEnterMutation() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();
        Runnable attempt = () -> {
            try {
                start.await();
                synchronized (BoxInstaller.class) {
                    BoxLegacyLayerPolicy.requireLegacyMutationAllowed("com.winlator.llm");
                    mutations.incrementAndGet();
                }
            } catch (IllegalStateException expected) {
                assertEquals("legacy_layer_mutation_disabled", expected.getMessage());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        Thread one = new Thread(attempt), two = new Thread(attempt);
        one.start(); two.start(); start.countDown(); one.join(); two.join();
        assertEquals(0, mutations.get());
    }
}
