package com.winlator.box;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BoxWinebootOptionalPolicyTest {
    private static final String PREFIX = "/home/xuser-box/.wine/drive_c/windows/";
    private static final String PATH = PREFIX + "system32/fusion.dll";
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static JSONObject row(String path, boolean exists, String hash) throws Exception {
        return new JSONObject().put("path", path).put("exists", exists)
                .put("size", exists ? 123 : 0).put("sha256", hash);
    }
    private static JSONArray items(JSONObject item) { return new JSONArray().put(item); }
    private static LinkedHashMap<String, String> optional() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(); result.put(PATH, SHA); return result;
    }

    @Test public void optionalMayTransitionFromCandidateToAbsentAndBack() throws Exception {
        JSONArray present = items(row(PATH, true, SHA));
        JSONArray absent = items(row(PATH, false, ""));
        assertTrue(BoxDebugServer.allCriticalFilesExist(absent, optional()));
        assertTrue(BoxDebugServer.criticalFilesMatch(present, absent, Collections.emptyMap(), new JSONArray(), optional()));
        assertTrue(BoxDebugServer.criticalFilesMatch(absent, present, Collections.emptyMap(), new JSONArray(), optional()));
    }

    @Test public void unknownOptionalContentAndUnknownRecordedBaselineFailClosed() throws Exception {
        JSONArray present = items(row(PATH, true, SHA));
        JSONArray changed = items(row(PATH, true, OTHER));
        assertFalse(BoxDebugServer.allCriticalFilesExist(changed, optional()));
        assertFalse(BoxDebugServer.criticalFilesMatch(present, changed, Collections.emptyMap(), new JSONArray(), optional()));
        assertFalse(BoxDebugServer.criticalFilesMatch(changed, present, Collections.emptyMap(), new JSONArray(), optional()));
    }

    @Test public void ordinaryCriticalFileMayNotDisappear() throws Exception {
        String path = PREFIX + "system32/ntdll.dll";
        JSONArray before = items(row(path, true, SHA));
        JSONArray after = items(row(path, false, ""));
        assertFalse(BoxDebugServer.allCriticalFilesExist(after, optional()));
        assertFalse(BoxDebugServer.criticalFilesMatch(before, after, Collections.emptyMap(), new JSONArray(), optional()));
    }

    @Test public void optionalUnsafeOrIndeterminateRecordIsNeverTreatedAsAbsent() throws Exception {
        JSONObject unsafe = row(PATH, false, "").put("unsafe", true);
        assertFalse(BoxDebugServer.allCriticalFilesExist(items(unsafe), optional()));
        assertFalse(BoxDebugServer.criticalFilesMatch(items(row(PATH, true, SHA)), items(unsafe),
                Collections.emptyMap(), new JSONArray(), optional()));
    }

    @Test public void onlyPinnedManifestAndRuntimeCanAuthorizeOptionalEntries() {
        String path = "asset://wine_prefix_delta/wowbox-v010-to-mainline.json";
        String hash = "01dbef7048c506116d4b4232063706f99dc139f99b3c6b967e92baa11c443fa6";
        String runtime = "proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff";
        assertTrue(BoxInstaller.isTrustedWinebootOptionalManifest(path, hash, runtime));
        assertFalse(BoxInstaller.isTrustedWinebootOptionalManifest(path, OTHER, runtime));
        assertFalse(BoxInstaller.isTrustedWinebootOptionalManifest("asset://other.json", hash, runtime));
        assertFalse(BoxInstaller.isTrustedWinebootOptionalManifest(path, hash, "different-runtime"));
    }
}
