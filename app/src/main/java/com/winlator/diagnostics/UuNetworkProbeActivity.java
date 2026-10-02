package com.winlator.diagnostics;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.TextView;

import com.winlator.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Local-only package-alias network probe. It records routing metadata and bounded connection
 * timings, never traffic payloads, cookies, authorization headers, or login URLs.
 */
public final class UuNetworkProbeActivity extends Activity {
    private static final int DEFAULT_REPEATS = 10;
    private static final int MAX_REPEATS = 50;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String REQUIRED_PACKAGE = "com.blizzard.diablo.immortal";
    private static final String DEFAULT_TARGETS =
            "account.battle.net:443,tw.actual.battle.net:1119,kr.actual.battle.net:1119";

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setGravity(Gravity.START);
        statusView.setPadding(32, 32, 32, 32);
        statusView.setTextIsSelectable(true);
        statusView.setMovementMethod(new ScrollingMovementMethod());
        statusView.setText("UU network probe is starting…");
        setContentView(statusView);

        if (!BuildConfig.UU_PROBE_BUILD || !REQUIRED_PACKAGE.equals(getPackageName())) {
            statusView.setText("Refusing to run outside the local UU probe package.");
            return;
        }

        new Thread(this::runProbe, "uu-network-probe").start();
    }

    private void runProbe() {
        JSONObject result = new JSONObject();
        try {
            int repeats = Math.max(1, Math.min(MAX_REPEATS,
                    getIntent().getIntExtra("repeats", DEFAULT_REPEATS)));
            String label = sanitizeLabel(getIntent().getStringExtra("label"));
            String targets = getIntent().getStringExtra("targets");
            if (targets == null || targets.trim().isEmpty()) targets = DEFAULT_TARGETS;

            result.put("schemaVersion", 1);
            result.put("capturedAtEpochMs", System.currentTimeMillis());
            result.put("label", label);
            result.put("packageName", getPackageName());
            result.put("uid", Process.myUid());
            result.put("repeats", repeats);
            result.put("network", collectNetworkState());
            result.put("egress", collectEgressAddress());

            JSONArray targetResults = new JSONArray();
            for (Target target : parseTargets(targets)) {
                targetResults.put(probeTarget(target, repeats));
            }
            result.put("targets", targetResults);

            File output = writeResult(label, result);
            result.put("resultFile", output.getAbsolutePath());
        }
        catch (Exception e) {
            putQuietly(result, "fatalError", e.getClass().getSimpleName());
        }

        final String rendered = renderJson(result);
        runOnUiThread(() -> statusView.setText(rendered));
        android.util.Log.i("UuNetworkProbe", summarize(result));
    }

    private JSONObject collectNetworkState() throws JSONException {
        JSONObject json = new JSONObject();
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            json.put("error", "ConnectivityManager unavailable");
            return json;
        }

        Network network = manager.getActiveNetwork();
        if (network == null) {
            json.put("active", false);
            return json;
        }

        json.put("active", true);
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps != null) {
            json.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            json.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            json.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            json.put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            json.put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            json.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }

        LinkProperties links = manager.getLinkProperties(network);
        if (links != null) {
            json.put("interfaceName", links.getInterfaceName());
            JSONArray dns = new JSONArray();
            for (InetAddress server : links.getDnsServers()) {
                dns.put(server.getHostAddress());
            }
            json.put("dnsServers", dns);
        }
        return json;
    }

    private JSONObject collectEgressAddress() throws JSONException {
        JSONObject json = new JSONObject();
        String[] endpoints = {
                "https://api.ipify.org",
                "https://www.cloudflare.com/cdn-cgi/trace"
        };
        JSONArray failures = new JSONArray();
        for (String endpoint : endpoints) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "WoW-Box-UU-Probe/1");
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    failures.put("http-" + code);
                    continue;
                }
                String response = readBounded(connection.getInputStream(), 1024).trim();
                String address = parseEgressAddress(response);
                if (address != null) {
                    json.put("address", address);
                    json.put("serviceHost", new URL(endpoint).getHost());
                    return json;
                }
                failures.put("unexpected-response");
            }
            catch (Exception e) {
                failures.put(e.getClass().getSimpleName());
            }
            finally {
                if (connection != null) connection.disconnect();
            }
        }
        json.put("errors", failures);
        return json;
    }

    private JSONObject probeTarget(Target target, int repeats) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("host", target.host);
        json.put("port", target.port);

        JSONArray resolved = new JSONArray();
        List<InetAddress> addresses = new ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(target.host)) {
                resolved.put(address.getHostAddress());
                addresses.add(address);
            }
        }
        catch (Exception e) {
            json.put("dnsError", e.getClass().getSimpleName());
        }
        json.put("resolvedAddresses", resolved);

        JSONArray attempts = new JSONArray();
        List<Long> successfulMs = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            JSONObject attempt = new JSONObject();
            if (addresses.isEmpty()) {
                attempt.put("ok", false);
                attempt.put("error", "UnresolvedHost");
                attempts.put(attempt);
                continue;
            }
            InetAddress address = addresses.get(i % addresses.size());
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS);
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                successfulMs.add(elapsed);
                attempt.put("ok", true);
                attempt.put("connectMs", elapsed);
                attempt.put("remoteAddress", socket.getInetAddress().getHostAddress());
            }
            catch (Exception e) {
                attempt.put("ok", false);
                attempt.put("error", e.getClass().getSimpleName());
            }
            attempts.put(attempt);
        }

        json.put("attempts", attempts);
        json.put("successCount", successfulMs.size());
        json.put("failureCount", repeats - successfulMs.size());
        if (!successfulMs.isEmpty()) {
            Collections.sort(successfulMs);
            json.put("medianMs", percentile(successfulMs, 50));
            json.put("p95Ms", percentile(successfulMs, 95));
        }
        return json;
    }

    private File writeResult(String label, JSONObject result) throws Exception {
        File dir = new File(getFilesDir(), "uu-probe");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create result directory");
        }
        File output = new File(dir, System.currentTimeMillis() + "-" + label + ".json");
        File temporary = new File(output.getAbsolutePath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (!temporary.renameTo(output)) {
            throw new IllegalStateException("cannot publish result");
        }
        return output;
    }

    private static List<Target> parseTargets(String value) {
        List<Target> targets = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            int separator = trimmed.lastIndexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) continue;
            String host = trimmed.substring(0, separator).trim();
            try {
                int port = Integer.parseInt(trimmed.substring(separator + 1).trim());
                if (!host.isEmpty() && port >= 1 && port <= 65535) {
                    targets.add(new Target(host, port));
                }
            }
            catch (NumberFormatException ignored) {}
        }
        if (targets.isEmpty()) {
            targets.addAll(Arrays.asList(
                    new Target("account.battle.net", 443),
                    new Target("tw.actual.battle.net", 1119)));
        }
        return targets;
    }

    private static long percentile(List<Long> sorted, int percent) {
        int index = (int) Math.ceil((percent / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.trim().isEmpty()) return "manual";
        String sanitized = label.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.length() > 48) sanitized = sanitized.substring(0, 48);
        return sanitized.isEmpty() ? "manual" : sanitized;
    }

    private static String readBounded(InputStream stream, int maxChars) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int value;
            while (builder.length() < maxChars && (value = reader.read()) != -1) {
                builder.append((char) value);
            }
        }
        return builder.toString();
    }

    private static String parseEgressAddress(String response) {
        String direct = response.trim();
        if (direct.matches("[0-9a-fA-F:.]{3,64}")) return direct;
        for (String line : response.split("\\r?\\n")) {
            if (!line.startsWith("ip=")) continue;
            String address = line.substring(3).trim();
            if (address.matches("[0-9a-fA-F:.]{3,64}")) return address;
        }
        return null;
    }

    private static void putQuietly(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        }
        catch (JSONException ignored) {}
    }

    private static String renderJson(JSONObject value) {
        try {
            return value.toString(2);
        }
        catch (JSONException ignored) {
            return value.toString();
        }
    }

    private static String summarize(JSONObject result) {
        JSONObject network = result.optJSONObject("network");
        boolean vpn = network != null && network.optBoolean("vpn", false);
        return String.format(Locale.ENGLISH,
                "complete label=%s uid=%d vpn=%s targets=%d",
                result.optString("label", "unknown"),
                result.optInt("uid", -1),
                vpn,
                result.optJSONArray("targets") != null
                        ? result.optJSONArray("targets").length() : 0);
    }

    private static final class Target {
        final String host;
        final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
