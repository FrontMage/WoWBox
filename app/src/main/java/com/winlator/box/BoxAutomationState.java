package com.winlator.box;

import org.json.JSONException;
import org.json.JSONObject;

public class BoxAutomationState {
    private static final Object lock = new Object();
    private static boolean appForeground;
    private static String foregroundActivity = "";
    private static boolean xServerActive;
    private static String xServerSessionId = "";
    private static long updatedAt;

    public static void setForegroundActivity(String activityName) {
        synchronized (lock) {
            appForeground = true;
            foregroundActivity = activityName != null ? activityName : "";
            updatedAt = System.currentTimeMillis();
        }
    }

    public static void clearForegroundActivity(String activityName) {
        synchronized (lock) {
            if (activityName == null || activityName.equals(foregroundActivity)) {
                appForeground = false;
                foregroundActivity = "";
                updatedAt = System.currentTimeMillis();
            }
        }
    }

    public static void setXServerActive(String sessionId) {
        synchronized (lock) {
            xServerActive = true;
            xServerSessionId = sessionId != null ? sessionId : "";
            updatedAt = System.currentTimeMillis();
        }
    }

    public static void clearXServerActive(String sessionId) {
        synchronized (lock) {
            if (sessionId == null || sessionId.equals(xServerSessionId)) {
                xServerActive = false;
                xServerSessionId = "";
                updatedAt = System.currentTimeMillis();
            }
        }
    }

    public static JSONObject toForegroundJson() throws JSONException {
        synchronized (lock) {
            JSONObject json = new JSONObject();
            json.put("appForeground", appForeground);
            json.put("foregroundActivity", foregroundActivity);
            json.put("xServerActive", xServerActive);
            json.put("xServerSessionId", xServerSessionId);
            json.put("updatedAt", updatedAt);
            return json;
        }
    }
}
