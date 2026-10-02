package com.winlator.box;

import android.content.Context;

import com.winlator.BuildConfig;
import com.winlator.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class BoxSpec {
    public static final String DEFAULT_ASSET_PATH = "box_spec.json";
    public static final String ODIN_STANDARD_LOCK_WITNESS_ASSET_PATH = "box_spec_odin_standard_lock_witness.json";
    public static final String ODIN_UU_EFLAGS_INLINE_ASSET_PATH = "box_spec_odin_uu_eflags_inline.json";

    public static String getBundledAssetPath() {
        return selectBundledAssetPath(
                BuildConfig.MINIMAL_ASSETS_BUILD,
                BuildConfig.UU_PROBE_BUILD,
                BuildConfig.MINIMAL_SPEC_ASSET);
    }

    static String selectBundledAssetPath(boolean minimalAssets, boolean uuProbe, String minimalSpec) {
        if (minimalAssets) {
            if (minimalSpec != null && !minimalSpec.isEmpty()) {
                if (!isSafeSpecAssetBasename(minimalSpec)) {
                    throw new IllegalArgumentException("Unsafe minimal Box spec asset: " + minimalSpec);
                }
                return minimalSpec;
            }
            return uuProbe
                    ? ODIN_UU_EFLAGS_INLINE_ASSET_PATH
                    : ODIN_STANDARD_LOCK_WITNESS_ASSET_PATH;
        }
        return uuProbe ? ODIN_UU_EFLAGS_INLINE_ASSET_PATH : DEFAULT_ASSET_PATH;
    }

    static boolean isSafeSpecAssetBasename(String value) {
        return value != null &&
                value.matches("^box_spec(_[A-Za-z0-9][A-Za-z0-9._-]*)?\\.json$") &&
                !value.contains("..") &&
                value.indexOf('/') < 0 &&
                value.indexOf('\\') < 0;
    }

    public int schemaVersion = 1;
    public String boxId = "default-box";
    public String displayName = "WoW Box";
    public final ArrayList<Layer> layers = new ArrayList<>();
    public Payload payload = new Payload();
    public final ArrayList<Mount> extraMounts = new ArrayList<>();
    public Graphics graphics = new Graphics();
    public Launch launch = new Launch();
    public Env env = new Env();
    public DebugServer debugServer = new DebugServer();

    public static BoxSpec load(Context context, File file) {
        if (file != null && file.isFile()) {
            return fromJsonString(FileUtils.readString(file));
        }
        return fromJsonString(FileUtils.readString(context, getBundledAssetPath()));
    }

    public static BoxSpec fromJsonString(String data) {
        if (data == null || data.trim().isEmpty()) return new BoxSpec();
        try {
            return fromJson(new JSONObject(data));
        }
        catch (JSONException ignored) {
            return new BoxSpec();
        }
    }

    public static BoxSpec fromJson(JSONObject json) throws JSONException {
        BoxSpec spec = new BoxSpec();
        spec.schemaVersion = json.optInt("schemaVersion", 1);
        spec.boxId = json.optString("boxId", spec.boxId);
        spec.displayName = json.optString("displayName", spec.displayName);

        JSONArray layers = json.optJSONArray("layers");
        if (layers != null) {
            spec.layers.clear();
            for (int i = 0; i < layers.length(); i++) {
                spec.layers.add(Layer.fromJson(layers.getJSONObject(i)));
            }
        }

        JSONObject payload = json.optJSONObject("payload");
        if (payload != null) spec.payload = Payload.fromJson(payload);

        JSONArray extraMounts = json.optJSONArray("extraMounts");
        if (extraMounts != null) {
            spec.extraMounts.clear();
            for (int i = 0; i < extraMounts.length(); i++) {
                spec.extraMounts.add(Mount.fromJson(extraMounts.getJSONObject(i)));
            }
        }

        JSONObject launch = json.optJSONObject("launch");
        if (launch != null) spec.launch = Launch.fromJson(launch);


        JSONObject graphics = json.optJSONObject("graphics");
        if (graphics != null) spec.graphics = Graphics.fromJson(graphics);

        JSONObject env = json.optJSONObject("env");
        if (env != null) spec.env = Env.fromJson(env);

        JSONObject debugServer = json.optJSONObject("debugServer");
        if (debugServer != null) spec.debugServer = DebugServer.fromJson(debugServer);
        return spec;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", schemaVersion);
        json.put("boxId", boxId);
        json.put("displayName", displayName);

        JSONArray layersJson = new JSONArray();
        for (Layer layer : layers) layersJson.put(layer.toJson());
        json.put("layers", layersJson);

        json.put("payload", payload.toJson());

        JSONArray mountsJson = new JSONArray();
        for (Mount mount : extraMounts) mountsJson.put(mount.toJson());
        json.put("extraMounts", mountsJson);

        json.put("graphics", graphics.toJson());
        json.put("launch", launch.toJson());
        json.put("env", env.toJson());
        json.put("debugServer", debugServer.toJson());
        return json;
    }

    public static class Layer {
        public String type = "";
        public String source = "";
        public String runtimeIdentifier = "";
        public String archive = "";
        public String checksum = "";
        public String target = "";
        public String replacePolicy = "replace";
        public String winePrefixDeltaManifest = "";
        public String winePrefixDeltaChecksum = "";
        public final ArrayList<String> capabilities = new ArrayList<>();

        public static Layer fromJson(JSONObject json) {
            Layer layer = new Layer();
            layer.type = json.optString("type", "");
            layer.source = json.optString("source", "");
            layer.runtimeIdentifier = json.optString("runtimeIdentifier", "");
            layer.archive = json.optString("archive", "");
            layer.checksum = json.optString("checksum", "");
            layer.target = json.optString("target", "");
            layer.replacePolicy = json.optString("replacePolicy", "replace");
            layer.winePrefixDeltaManifest = json.optString("winePrefixDeltaManifest", "");
            layer.winePrefixDeltaChecksum = json.optString("winePrefixDeltaChecksum", "");
            JSONArray capabilities = json.optJSONArray("capabilities");
            if (capabilities != null) {
                for (int i = 0; i < capabilities.length(); i++) {
                    layer.capabilities.add(capabilities.optString(i, ""));
                }
            }
            return layer;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("source", source);
            json.put("runtimeIdentifier", runtimeIdentifier);
            json.put("archive", archive);
            json.put("checksum", checksum);
            json.put("target", target);
            json.put("replacePolicy", replacePolicy);
            json.put("winePrefixDeltaManifest", winePrefixDeltaManifest);
            json.put("winePrefixDeltaChecksum", winePrefixDeltaChecksum);
            JSONArray capabilitiesJson = new JSONArray();
            for (String capability : capabilities) capabilitiesJson.put(capability);
            json.put("capabilities", capabilitiesJson);
            return json;
        }

        public boolean hasCapability(String capability) {
            return capability != null && capabilities.contains(capability);
        }
    }

    public static class Payload {
        public String source = "";
        public String kind = "directory";
        public String mountDrive = "D";
        public String extractDir = "";

        public static Payload fromJson(JSONObject json) {
            Payload payload = new Payload();
            payload.source = json.optString("source", "");
            payload.kind = json.optString("kind", "directory");
            payload.mountDrive = json.optString("mountDrive", "D");
            payload.extractDir = json.optString("extractDir", "");
            return payload;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("source", source);
            json.put("kind", kind);
            json.put("mountDrive", mountDrive);
            json.put("extractDir", extractDir);
            return json;
        }
    }

    public static class Mount {
        public String source = "";
        public String mountDrive = "D";

        public static Mount fromJson(JSONObject json) {
            Mount mount = new Mount();
            mount.source = json.optString("source", "");
            mount.mountDrive = json.optString("mountDrive", "D");
            return mount;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("source", source);
            json.put("mountDrive", mountDrive);
            return json;
        }
    }

    public static class Launch {
        public final ArrayList<String> discoveryRoots = new ArrayList<>();
        public boolean desktopButtonEnabled = true;
        public String defaultTargetId = "";
        public final ArrayList<Target> targets = new ArrayList<>();

        public static Launch fromJson(JSONObject json) {
            Launch launch = new Launch();
            JSONArray roots = json.optJSONArray("discoveryRoots");
            if (roots != null) {
                for (int i = 0; i < roots.length(); i++) launch.discoveryRoots.add(roots.optString(i));
            }
            launch.desktopButtonEnabled = json.optBoolean("desktopButtonEnabled", true);
            launch.defaultTargetId = json.optString("defaultTargetId", "");
            JSONArray targets = json.optJSONArray("targets");
            if (targets != null) {
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject target = targets.optJSONObject(i);
                    if (target != null) launch.targets.add(Target.fromJson(target));
                }
            }
            return launch;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            JSONArray roots = new JSONArray();
            for (String root : discoveryRoots) roots.put(root);
            json.put("discoveryRoots", roots);
            json.put("desktopButtonEnabled", desktopButtonEnabled);
            json.put("defaultTargetId", defaultTargetId);
            JSONArray targetsJson = new JSONArray();
            for (Target target : targets) targetsJson.put(target.toJson());
            json.put("targets", targetsJson);
            return json;
        }

        public Target findTarget(String id) {
            if (id == null || id.isEmpty()) return null;
            for (Target target : targets) if (id.equals(target.id)) return target;
            return null;
        }
    }

    public static class Target {
        public String id = "";
        public String label = "";
        public String kind = "executable";
        public String guestPath = "";
        public String workingDirectory = "";
        public final ArrayList<String> args = new ArrayList<>();
        public boolean requiresExists = true;
        public boolean hidden = false;
        public String requiresTargetId = "";
        public String hiddenWhenTargetIdExists = "";
        public int controlsProfileId = 0;

        public static Target fromJson(JSONObject json) {
            Target target = new Target();
            target.id = json.optString("id", "");
            target.label = json.optString("label", target.id);
            target.kind = json.optString("kind", "executable");
            target.guestPath = json.optString("guestPath", "");
            target.workingDirectory = json.optString("workingDirectory", "");
            target.requiresExists = json.optBoolean("requiresExists", true);
            target.hidden = json.optBoolean("hidden", false);
            target.requiresTargetId = json.optString("requiresTargetId", "");
            target.hiddenWhenTargetIdExists = json.optString("hiddenWhenTargetIdExists", "");
            target.controlsProfileId = Math.max(0, json.optInt("controlsProfileId", 0));
            JSONArray args = json.optJSONArray("args");
            if (args != null) {
                for (int i = 0; i < args.length(); i++) target.args.add(args.optString(i, ""));
            }
            return target;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("label", label);
            json.put("kind", kind);
            json.put("guestPath", guestPath);
            json.put("workingDirectory", workingDirectory);
            JSONArray argsJson = new JSONArray();
            for (String arg : args) argsJson.put(arg);
            json.put("args", argsJson);
            json.put("requiresExists", requiresExists);
            json.put("hidden", hidden);
            json.put("requiresTargetId", requiresTargetId);
            json.put("hiddenWhenTargetIdExists", hiddenWhenTargetIdExists);
            if (controlsProfileId > 0) json.put("controlsProfileId", controlsProfileId);
            return json;
        }
    }

    public static class Graphics {
        public String dxvkVersion = "";
        public String turnipVersion = "";
        public boolean vkd3dEnabled = false;
        public final ArrayList<DxvkCandidate> dxvkCandidates = new ArrayList<>();
        public final ArrayList<TurnipCandidate> turnipCandidates = new ArrayList<>();

        public static Graphics fromJson(JSONObject json) {
            Graphics graphics = new Graphics();
            graphics.dxvkVersion = json.optString("dxvkVersion", "");
            graphics.turnipVersion = json.optString("turnipVersion", "");
            graphics.vkd3dEnabled = json.optBoolean("vkd3dEnabled", false);
            JSONArray candidates = json.optJSONArray("dxvkCandidates");
            if (candidates != null) {
                for (int i = 0; i < candidates.length(); i++) {
                    JSONObject candidate = candidates.optJSONObject(i);
                    if (candidate != null) graphics.dxvkCandidates.add(DxvkCandidate.fromJson(candidate));
                }
            }
            JSONArray turnipCandidates = json.optJSONArray("turnipCandidates");
            if (turnipCandidates != null) {
                for (int i = 0; i < turnipCandidates.length(); i++) {
                    JSONObject candidate = turnipCandidates.optJSONObject(i);
                    if (candidate != null) graphics.turnipCandidates.add(TurnipCandidate.fromJson(candidate));
                }
            }
            return graphics;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("dxvkVersion", dxvkVersion);
            json.put("turnipVersion", turnipVersion);
            json.put("vkd3dEnabled", vkd3dEnabled);
            JSONArray candidates = new JSONArray();
            for (DxvkCandidate candidate : dxvkCandidates) candidates.put(candidate.toJson());
            json.put("dxvkCandidates", candidates);
            JSONArray turnipCandidatesJson = new JSONArray();
            for (TurnipCandidate candidate : turnipCandidates) turnipCandidatesJson.put(candidate.toJson());
            json.put("turnipCandidates", turnipCandidatesJson);
            return json;
        }

        public DxvkCandidate findDxvkCandidate(String version) {
            if (version == null || version.isEmpty()) return null;
            for (DxvkCandidate candidate : dxvkCandidates) {
                if (version.equals(candidate.version)) return candidate;
            }
            return null;
        }

        public TurnipCandidate findTurnipCandidate(String version) {
            if (version == null || version.isEmpty()) return null;
            for (TurnipCandidate candidate : turnipCandidates) {
                if (version.equals(candidate.version)) return candidate;
            }
            return null;
        }
    }

    public static class DxvkCandidate {
        public String version = "";
        public String source = "";
        public String checksum = "";
        public String system32Machine = "";

        public static DxvkCandidate fromJson(JSONObject json) {
            DxvkCandidate candidate = new DxvkCandidate();
            candidate.version = json.optString("version", "");
            candidate.source = json.optString("source", "");
            candidate.checksum = json.optString("checksum", "");
            candidate.system32Machine = json.optString("system32Machine", "");
            return candidate;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("version", version);
            json.put("source", source);
            json.put("checksum", checksum);
            json.put("system32Machine", system32Machine);
            return json;
        }
    }

    public static class TurnipCandidate {
        public String version = "";
        public String source = "";
        public String checksum = "";
        public String driverId = "";

        public static TurnipCandidate fromJson(JSONObject json) {
            TurnipCandidate candidate = new TurnipCandidate();
            candidate.version = json.optString("version", "");
            candidate.source = json.optString("source", "");
            candidate.checksum = json.optString("checksum", "");
            candidate.driverId = json.optString("driverId", "");
            return candidate;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("version", version);
            json.put("source", source);
            json.put("checksum", checksum);
            json.put("driverId", driverId);
            return json;
        }
    }

    public static class Env {
        public final LinkedHashMap<String, String> base = new LinkedHashMap<>();
        public String fexPreset = "intermediate";
        public final LinkedHashMap<String, String> overrides = new LinkedHashMap<>();

        public static Env fromJson(JSONObject json) throws JSONException {
            Env env = new Env();
            copyJsonObject(json.optJSONObject("base"), env.base);
            env.fexPreset = json.optString("fexPreset", "intermediate");
            copyJsonObject(json.optJSONObject("overrides"), env.overrides);
            return env;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("base", new JSONObject(base));
            json.put("fexPreset", fexPreset);
            json.put("overrides", new JSONObject(overrides));
            return json;
        }
    }

    public static class DebugServer {
        public boolean enabled = true;
        public String bind = "0.0.0.0";
        public int port = 39090;

        public static DebugServer fromJson(JSONObject json) {
            DebugServer debugServer = new DebugServer();
            debugServer.enabled = json.optBoolean("enabled", true);
            debugServer.bind = json.optString("bind", "0.0.0.0");
            debugServer.port = json.optInt("port", 39090);
            return debugServer;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("bind", bind);
            json.put("port", port);
            return json;
        }
    }

    private static void copyJsonObject(JSONObject json, Map<String, String> out) throws JSONException {
        out.clear();
        if (json == null) return;
        JSONArray names = json.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            out.put(key, json.optString(key, ""));
        }
    }
}
