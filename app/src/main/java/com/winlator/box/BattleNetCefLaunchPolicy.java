package com.winlator.box;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The RedMagic-only V8 mitigation applies only to the standard GUI launcher. */
final class BattleNetCefLaunchPolicy {
    static final String STANDARD_PACKAGE = "com.winlator.llm";
    static final String BNET_LAUNCHER = "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe";
    static final String V8_WORKAROUND = "--js-flags=--no-incremental-marking";

    private BattleNetCefLaunchPolicy() {}

    static ArrayList<String> arguments(boolean targetDevice, String applicationId,
                                       String targetId, String guestPath, List<String> original) {
        ArrayList<String> result = new ArrayList<>(original);
        if (!targetDevice || !STANDARD_PACKAGE.equals(applicationId) || !"bnet".equals(targetId) ||
                guestPath == null || !BNET_LAUNCHER.equalsIgnoreCase(guestPath)) return result;
        for (String arg : result) {
            if (arg == null) continue;
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("--js-flags") || lower.startsWith("--js-flags=")) return result;
        }
        result.add(V8_WORKAROUND);
        return result;
    }
}
