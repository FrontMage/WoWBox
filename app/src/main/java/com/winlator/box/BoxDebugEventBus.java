package com.winlator.box;

import com.winlator.core.Callback;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public final class BoxDebugEventBus {
    private static final ArrayList<Callback<String>> listeners = new ArrayList<>();

    private BoxDebugEventBus() {}

    public static void subscribe(Callback<String> callback) {
        synchronized (listeners) {
            if (!listeners.contains(callback)) listeners.add(callback);
        }
    }

    public static void unsubscribe(Callback<String> callback) {
        synchronized (listeners) {
            listeners.remove(callback);
        }
    }

    public static void publish(String type, JSONObject payload) {
        publish(buildEvent(type, payload));
    }

    public static JSONObject buildEvent(String type, JSONObject payload) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type);
            json.put("timestamp", System.currentTimeMillis());
            json.put("payload", payload != null ? payload : new JSONObject());
        }
        catch (JSONException ignored) {}
        return json;
    }

    public static void publish(JSONObject event) {
        String data = event != null ? event.toString() : "{}";
        synchronized (listeners) {
            for (Callback<String> callback : listeners) callback.call(data);
        }
    }
}
