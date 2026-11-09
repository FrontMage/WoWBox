package com.winlator.box;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class BattleNetCefLaunchPolicyTest {
    private static final String PACKAGE = BattleNetCefLaunchPolicy.STANDARD_PACKAGE;
    private static final String LAUNCHER = BattleNetCefLaunchPolicy.BNET_LAUNCHER;
    private static final String FLAG = BattleNetCefLaunchPolicy.V8_WORKAROUND;

    @Test public void appendsForTargetWithoutMutatingSavedArguments() {
        ArrayList<String> original = new ArrayList<>(Collections.singletonList("--existing"));
        assertEquals(Arrays.asList("--existing", FLAG),
                BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "bnet", LAUNCHER, original));
        assertEquals(Collections.singletonList("--existing"), original);
    }
    @Test public void skipsNonTargetDevicePackageTargetAndPath() {
        assertEquals(Collections.emptyList(), BattleNetCefLaunchPolicy.arguments(false, PACKAGE, "bnet", LAUNCHER, Collections.emptyList()));
        assertEquals(Collections.emptyList(), BattleNetCefLaunchPolicy.arguments(true, "other", "bnet", LAUNCHER, Collections.emptyList()));
        assertEquals(Collections.emptyList(), BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "wow", LAUNCHER, Collections.emptyList()));
        assertEquals(Collections.emptyList(), BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "bnet", "C:\\other.exe", Collections.emptyList()));
    }
    @Test public void preservesExistingJsFlagsAndAvoidsDuplicates() {
        for (String flag : Arrays.asList("--js-flags=--jitless", "--js-flags=", "--js-flags", "--JS-FLAGS=--custom")) {
            assertEquals(Collections.singletonList(flag), BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "bnet", LAUNCHER, Collections.singletonList(flag)));
        }
        ArrayList<String> once = BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "bnet", LAUNCHER.toLowerCase(), Collections.emptyList());
        assertEquals(Collections.singletonList(FLAG), once);
        assertEquals(once, BattleNetCefLaunchPolicy.arguments(true, PACKAGE, "bnet", LAUNCHER, once));
    }
}
