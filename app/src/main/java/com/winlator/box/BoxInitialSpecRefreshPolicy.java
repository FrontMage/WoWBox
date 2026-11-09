package com.winlator.box;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/** Fail closed before refreshing a fresh-install spec with user-owned fields. */
final class BoxInitialSpecRefreshPolicy {
    private BoxInitialSpecRefreshPolicy() {}

    static void validate(JSONObject persisted, BoxSpec bundled, boolean targetDevice,
                         String applicationId) throws JSONException {
        if (persisted == null || bundled == null) throw new IllegalStateException("initial_spec_missing");
        JSONObject baseline = bundled.toJson();
        BoxSpec selected = BoxSpec.fromJson(new JSONObject(baseline.toString()));
        boolean selectedV39 = BoxRedMagicGpuPolicy.applyFreshInstall(selected, targetDevice, applicationId);
        if (!sameValue(persisted, baseline) &&
                (!selectedV39 || !sameValue(persisted, selected.toJson()))) {
            throw new IllegalStateException("initial_spec_has_custom_fields");
        }
    }

    private static boolean sameValue(Object left, Object right) throws JSONException {
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject)left, b = (JSONObject)right;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!b.has(key) || !sameValue(a.get(key), b.get(key))) return false;
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray)left, b = (JSONArray)right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) {
                if (!sameValue(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        return left == right || (left != null && left.equals(right));
    }
}
