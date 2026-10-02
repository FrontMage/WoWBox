package com.winlator.core;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;

public class EnvVars implements Iterable<String> {
    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {}

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        data.put(name, String.valueOf(value));
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) return;
        for (String part : splitEscaped(values.trim())) {
            int index = part.indexOf("=");
            if (index <= 0) continue;
            String name = part.substring(0, index);
            String value = unescape(part.substring(index+1));
            data.put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        data.putAll(envVars.data);
    }

    public String get(String name) {
        return data.getOrDefault(name, "");
    }

    public void remove(String name) {
        data.remove(name);
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public void clear() {
        data.clear();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return toEscapedString();
    }

    public String toEscapedString() {
        String result = "";
        for (String key : data.keySet()) {
            if (!result.isEmpty()) result += " ";
            String value = data.get(key);
            result += key+"="+escape(value);
        }
        return result;
    }

    public String[] toStringArray() {
        String[] stringArray = new String[data.size()];
        int index = 0;
        for (String key : data.keySet()) stringArray[index++] = key+"="+data.get(key);
        return stringArray;
    }

    @NonNull
    @Override
    public Iterator<String> iterator() {
        return data.keySet().iterator();
    }

    private static String[] splitEscaped(String values) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < values.length(); i++) {
            char c = values.charAt(i);
            if (escaped) {
                current.append('\\');
                current.append(c);
                escaped = false;
            }
            else if (c == '\\') {
                escaped = true;
            }
            else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            }
            else current.append(c);
        }
        if (escaped) current.append('\\');
        if (current.length() > 0) parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) result.append('\\');
            result.append(c);
        }
        return result.toString();
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                if (Character.isWhitespace(c)) result.append(c);
                else {
                    result.append('\\');
                    result.append(c);
                }
                escaped = false;
            }
            else if (c == '\\') escaped = true;
            else result.append(c);
        }
        if (escaped) result.append('\\');
        return result.toString();
    }
}
