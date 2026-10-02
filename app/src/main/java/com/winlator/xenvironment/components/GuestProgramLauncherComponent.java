package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.box.BoxRuntime;
import com.winlator.contents.AdrenotoolsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineSessionProcessController;
import com.winlator.fexcore.FEXCorePreset;
import com.winlator.fexcore.FEXCorePresetManager;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "GuestLauncher";
    private static final String DXVK_STDERR_TAG = "DXVK_STDERR";
    private static final long WINE_SHUTDOWN_GRACE_MS = 1500L;
    private static final long WINE_FORCE_KILL_WAIT_MS = 500L;
    private static final long WINE_SHUTDOWN_POLL_MS = 25L;
    private static final long WINE_FORCE_KILL_RETRY_MS = 50L;
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private String fexcoreVersion = DefaultVersion.FEXCORE;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private boolean arm64ecWine = false;
    private boolean boxMode = false;
    private File lastRootDir;
    private String[] lastEnvp;
    private String lastWineBinPath;
    private boolean lastArm64ecWine = false;

    private static String ensureWineDebugChannels(String wineDebug, String... channels) {
        if (wineDebug == null) wineDebug = "";
        wineDebug = wineDebug.trim();

        Set<String> enabled = new HashSet<>();
        Set<String> disabled = new HashSet<>();

        if (!wineDebug.isEmpty()) {
            String[] parts = wineDebug.split(",");
            for (String part : parts) {
                String token = part.trim();
                if (token.isEmpty()) continue;

                char prefix = token.charAt(0);
                String name = (prefix == '+' || prefix == '-') ? token.substring(1) : token;
                if (name.isEmpty()) continue;

                if (prefix == '-') disabled.add(name);
                else enabled.add(name);
            }
        }

        String out = wineDebug;
        for (String ch : channels) {
            if (ch == null) continue;
            ch = ch.trim();
            if (ch.isEmpty()) continue;
            if (enabled.contains(ch) || disabled.contains(ch)) continue;

            if (out.isEmpty()) out = "+" + ch;
            else out += ",+" + ch;
            enabled.add(ch);
        }

        return out;
    }

    private static boolean shouldMirrorDxvkToLogcat(String line) {
        if (line == null || line.isEmpty()) return false;
        return line.contains("DXVK")
                || line.contains("DXGI")
                || line.contains("SWAPTRACE")
                || line.contains("Presenter:")
                || line.contains("Vulkan:");
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractFexcoreFiles();
            BoxRuntime.get(environment.getContext()).applyRuntimeOverlay();
            pid = execGuestProgram();
            recordBoxGuestPid(pid);
        }
    }

    @Override
    public void stop() {
        stopAndDrain();
    }

    public boolean stopAndDrain() {
        synchronized (lock) {
            // A stopped wineserver cannot perform an orderly shutdown. Resume the
            // complete Wine session before asking it to exit, even when the
            // launcher/waiter PID has already disappeared.
            ProcessHelper.resumeAllWineProcesses();
            final int currentPid = pid;
            final boolean wasArm64ecWine = lastArm64ecWine;

            if (wasArm64ecWine) {
                requestWineserverShutdownLocked();
            }
            else if (currentPid != -1) {
                try {
                    Process.killProcess(currentPid);
                    Log.i(TAG, "Force-killed guest process pid=" + currentPid);
                }
                catch (Throwable t) {
                    Log.w(TAG, "Failed to force-kill guest process pid=" + currentPid, t);
                }
            }

            boolean drained = waitForWineProcesses(WINE_SHUTDOWN_GRACE_MS);
            if (!drained) {
                drained = forceDrainWineProcesses(WINE_FORCE_KILL_WAIT_MS);
            }
            if (!drained) {
                Log.e(TAG, "Wine process groups remain after force-kill verification");
            }

            pid = -1;
            clearLastLaunchStateLocked();
            return drained;
        }
    }

    private boolean forceDrainWineProcesses(long timeoutMs) {
        WineSessionProcessController controller = WineSessionProcessController.getInstance();
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        int sweeps = 0;
        int candidates = 0;
        int liveThreads = 0;
        int signals = 0;

        do {
            WineSessionProcessController.ForceKillResult result =
                    controller.forceKillWineProcesses();
            sweeps++;
            candidates += result.getCandidateProcessCount();
            liveThreads += result.getLiveThreadCount();
            signals += result.getKillSignalCount();
            if (!controller.hasActiveWineProcesses()) {
                Log.w(TAG, "Wine shutdown grace expired; force-drain completed sweeps="
                        + sweeps + " candidates=" + candidates
                        + " liveThreads=" + liveThreads + " signals=" + signals);
                return true;
            }

            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) break;
            SystemClock.sleep(Math.min(WINE_FORCE_KILL_RETRY_MS, remaining));
        } while (true);

        boolean drained = !controller.hasActiveWineProcesses();
        Log.w(TAG, "Wine shutdown grace expired; force-drain finished drained=" + drained
                + " sweeps=" + sweeps + " candidates=" + candidates
                + " liveThreads=" + liveThreads + " signals=" + signals);
        return drained;
    }

    private boolean waitForWineProcesses(long timeoutMs) {
        WineSessionProcessController controller = WineSessionProcessController.getInstance();
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        while (controller.hasActiveWineProcesses()) {
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) return false;
            SystemClock.sleep(Math.min(WINE_SHUTDOWN_POLL_MS, remaining));
        }
        return true;
    }

    private void clearLastLaunchStateLocked() {
        lastRootDir = null;
        lastEnvp = null;
        lastWineBinPath = null;
        lastArm64ecWine = false;
    }

    private String resolveWineserverPathLocked() {
        if (lastWineBinPath == null || lastWineBinPath.isEmpty()) return null;
        String wineserverPath = lastWineBinPath + "/wineserver";
        if (new File(wineserverPath).isFile()) return wineserverPath;
        String wineserver64Path = lastWineBinPath + "/wineserver64";
        if (new File(wineserver64Path).isFile()) return wineserver64Path;
        return null;
    }

    private int requestWineserverShutdownLocked() {
        if (lastRootDir == null || lastEnvp == null) return -1;
        String wineserverPath = resolveWineserverPathLocked();
        if (wineserverPath == null) return -1;
        int wsPid = ProcessHelper.exec(
                wineserverPath + " -k",
                lastEnvp,
                lastRootDir,
                status -> Log.i(TAG, "wineserver -k exited " + status));
        if (wsPid != -1) {
            Log.i(TAG, "Requested wineserver shutdown with -k, pid=" + wsPid);
        }
        return wsPid;
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public boolean hasRunningProcess() {
        synchronized (lock) {
            return pid != -1;
        }
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public void setArm64ecWine(boolean arm64ecWine) {
        this.arm64ecWine = arm64ecWine;
    }

    public void setBoxMode(boolean boxMode) {
        this.boxMode = boxMode;
    }

    private void recordBoxGuestPid(int guestPid) {
        if (guestPid <= 0 || environment == null) return;
        String sessionId = BoxRuntime.get(environment.getContext()).getSessionManager().getCurrentSessionId();
        if (sessionId == null || sessionId.isEmpty()) return;
        BoxRuntime.get(environment.getContext()).getSessionManager().updateGuestPid(sessionId, guestPid);
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public void setFEXCoreVersion(String fexcoreVersion) {
        this.fexcoreVersion = fexcoreVersion != null ? fexcoreVersion : DefaultVersion.FEXCORE;
    }

    public void setFEXCorePreset(String fexcorePreset) {
        this.fexcorePreset = fexcorePreset != null ? fexcorePreset : FEXCorePreset.INTERMEDIATE;
    }

    private int execGuestProgram() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        File rootDir = imageFs.getRootDir();
        File tmpDir = environment.getTmpDir();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;

        EnvVars envVars = new EnvVars();
        envVars.putAll(FEXCorePresetManager.getEnvVars(context, fexcorePreset));
        envVars.put("HOME", ImageFs.HOME_PATH);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", "/tmp");
        envVars.put("DISPLAY", ":0");
        String winePath = imageFs.getWinePath();
        String rootPath = rootDir.getPath();
        boolean isArm64ecWine = this.arm64ecWine || (winePath != null && winePath.contains("arm64ec"));
        String winePathResolved = winePath != null ? winePath : "";
        if (isArm64ecWine) {
            if (!winePathResolved.isEmpty() && !winePathResolved.startsWith("/")) {
                winePathResolved = rootPath + "/" + winePathResolved;
            }
        }
        String wineBinPath = (isArm64ecWine ? winePathResolved : winePath) + "/bin";

        if (isArm64ecWine) {
            // Align with bionic build: use absolute rootfs paths and minimal bionic loader path.
            envVars.put("HOME", rootPath + ImageFs.HOME_PATH);
            envVars.put("USER", ImageFs.USER);
            File usrTmpDir = new File(rootDir, "/usr/tmp");
            if (!usrTmpDir.isDirectory()) usrTmpDir.mkdirs();
            envVars.put("TMPDIR", usrTmpDir.getAbsolutePath());
            envVars.put("XDG_DATA_DIRS", rootPath + "/usr/share");
            envVars.put("XDG_CONFIG_DIRS", rootPath + "/usr/etc/xdg");
            envVars.put("GST_PLUGIN_PATH", rootPath + "/usr/lib/gstreamer-1.0");
            envVars.put("FONTCONFIG_PATH", rootPath + "/usr/etc/fonts");
            envVars.put("VK_LAYER_PATH", rootPath + "/usr/share/vulkan/implicit_layer.d:" + rootPath + "/usr/share/vulkan/explicit_layer.d");
            envVars.put("WRAPPER_LAYER_PATH", rootPath + "/usr/lib");
            envVars.put("WRAPPER_CACHE_PATH", rootPath + "/usr/var/cache");
            envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
            envVars.put("PREFIX", rootPath + "/usr");
            envVars.put("DISPLAY", ":0");
            envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
            envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
            envVars.put("ALSA_CONFIG_PATH", rootPath + "/usr/share/alsa/alsa.conf:" + rootPath + "/usr/etc/alsa/conf.d/android_aserver.conf");
            envVars.put("ALSA_PLUGIN_DIR", rootPath + "/usr/lib/alsa-lib");
            envVars.put("OPENSSL_CONF", rootPath + "/usr/etc/tls/openssl.cnf");
            envVars.put("SSL_CERT_FILE", rootPath + "/usr/etc/tls/cert.pem");
            envVars.put("SSL_CERT_DIR", rootPath + "/usr/etc/tls/certs");
            envVars.put("WINE_X11FORCEGLX", "1");
            envVars.put("WINE_GST_NO_GL", "1");
            envVars.put("SteamGameId", "0");
            envVars.put("PROTON_AUDIO_CONVERT", "0");
            envVars.put("PROTON_VIDEO_CONVERT", "0");
            envVars.put("PROTON_DEMUX", "0");

            envVars.put("PATH", wineBinPath + ":" + rootPath + "/usr/bin");
            envVars.put(
                    "LD_LIBRARY_PATH",
                    rootPath + "/usr/lib" + ":/system/lib64"
            );
        } else {
            envVars.put("PATH", wineBinPath + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            envVars.put("LD_LIBRARY_PATH", "/usr/lib:/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf");
        }
        envVars.put("ANDROID_SYSVSHM_SERVER", UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NEW_NDIS", "1");

        String dnsOverride = "";
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
                if (linkProperties != null) {
                    List<InetAddress> dnsServers = linkProperties.getDnsServers();
                    if (dnsServers != null && !dnsServers.isEmpty()) {
                        dnsOverride = dnsServers.get(0).getHostAddress();
                    }
                }
            }
        }
        if (!dnsOverride.isEmpty()) {
            envVars.put("ANDROID_RESOLV_DNS", dnsOverride);
        } else {
            envVars.put("ANDROID_RESOLV_DNS", "8.8.4.4");
        }

        String ldPreload = "";
        File sysvshmLib = new File(imageFs.getLibDir(), "libandroid-sysvshm.so");
        if (sysvshmLib.isFile()) {
            ldPreload = sysvshmLib.getAbsolutePath();
        }

        // Vanilla: inject fakeinput when available (used for evdev emulation).
        File fakeinputLib = new File(imageFs.getLibDir(), "libfakeinput.so");
        if (fakeinputLib.isFile()) {
            ldPreload = !ldPreload.isEmpty() ? (ldPreload + ":" + fakeinputLib.getAbsolutePath()) : fakeinputLib.getAbsolutePath();
            // Provide guest-visible location for the fake evdev devices.
            File evdevDir = new File(rootDir, "dev/input");
            if (evdevDir.isDirectory()) {
                envVars.put("FAKE_EVDEV_DIR", rootPath + "/dev/input");
            }
        }

        boolean enableFridaGadget = this.envVars != null && "1".equals(this.envVars.get("WINLATOR_FRIDA_GADGET"));
        File fridaGadgetLib = new File(imageFs.getLibDir(), "libfrida-gadget.so");
        if (enableFridaGadget && fridaGadgetLib.isFile()) {
            ldPreload = !ldPreload.isEmpty() ? (fridaGadgetLib.getAbsolutePath() + ":" + ldPreload) : fridaGadgetLib.getAbsolutePath();
        }
        envVars.put("LD_PRELOAD", ldPreload);

        if (this.envVars != null) {
            if (this.envVars.has("MANGOHUD")) this.envVars.remove("MANGOHUD");
            if (this.envVars.has("MANGOHUD_CONFIG")) this.envVars.remove("MANGOHUD_CONFIG");
            envVars.putAll(this.envVars);
        }

        // ARM64EC/WoW64 defaults:
        // Keep compatibility with vanilla by not forcing FEX SMC policy by default.
        // If needed, users can set FEX_SMC_CHECKS explicitly via container env vars.

        // Vanilla alignment: do not force extra FEX_* defaults here.
        // Preset env vars are provided by FEXCorePresetManager and can be overridden per container.

        if (isArm64ecWine) {
            File wineDataDir = new File(winePathResolved, "share/wine");
            if (wineDataDir.isDirectory()) {
                // wineboot resolves wine.inf and the bundled Wine fonts through
                // WINEDATADIR.  Without it, a prebuilt prefix can keep font
                // substitutions (for example MS Shell Dlg -> Tahoma) while
                // missing the matching Fonts registry entries, which sends CEF
                // into recursive system-font fallback.
                envVars.put("WINEDATADIR", wineDataDir.getAbsolutePath());
            } else {
                Log.w(TAG, "Wine data directory is missing: " + wineDataDir.getAbsolutePath());
            }
            File winePrefixDir = new File(rootDir, ImageFs.WINEPREFIX);
            envVars.put("HOME", rootPath + ImageFs.HOME_PATH);
            envVars.put("WINEPREFIX", winePrefixDir.getAbsolutePath());
            envVars.put("TMPDIR", new File(rootDir, "/usr/tmp").getAbsolutePath());
            envVars.put("ANDROID_SYSVSHM_SERVER", rootPath + UnixSocketConfig.SYSVSHM_SERVER_PATH);
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("LD_PRELOAD", ldPreload);
            // Bionic Wine uses Android's libc/libdl/libm and the bionic X11/audio
            // compatibility libraries from the selected imagefs.
            envVars.put("LD_LIBRARY_PATH", "/system/lib64:" + rootPath + "/usr/lib");
            envVars.put("PATH", wineBinPath + ":" + rootPath + "/usr/bin");
            // Allow overriding WoW64 backend via env. Default is FEX's WoW64 bridge.
            // If the user sets HODLL (e.g. wowbox64.dll), don't clobber it.
            if (!envVars.has("HODLL") || envVars.get("HODLL") == null || envVars.get("HODLL").isEmpty()) {
                envVars.put("HODLL", "libwow64fex.dll");
            }
            envVars.remove("HODLL64");

            // Inject Adrenotools so the wrapper can load the selected Turnip asset.
            new AdrenotoolsManager(context).setDriverById(envVars, imageFs,
                    BoxRuntime.get(context).getGpuDriverId());
        }

        boolean bindSHM = envVars.get("WINEESYNC").equals("1");

        if (isArm64ecWine) {
            String command = nativeLibraryDir + "/libsigquit-exec.so "
                    + wineBinPath + "/" + guestExecutable;
            // For the desktop session startup we launch Wine's shell and then keep the Android
            // activity alive until the Wine server exits. On arm64ec builds our shell bootstrap
            // may exit quickly with status 0 after spawning winhandler/wfm, which is expected.
            // If we treat that as "session finished" then the container will flash-exit.
            final boolean keepAliveUntilWineserver =
                    command.contains("/desktop=shell,")
                            && (command.contains("winhandler.exe") || command.contains("winhandler-lite.exe"));

            Log.i(TAG, "Launching guest command: " + command);
            Log.i(TAG, "Guest env (arm64ec): " + envVars.toString());
            // Snapshot envp early. The activity clears its EnvVars instance after startup, but
            // we still need the original environment for follow-up keepalive processes.
            final String[] envp = envVars.toStringArray();
            lastRootDir = rootDir;
            lastEnvp = envp;
            lastWineBinPath = wineBinPath;
            lastArm64ecWine = true;
            final Callback<String> dxvkLogcatMirror = line -> {
                if (shouldMirrorDxvkToLogcat(line)) Log.i(DXVK_STDERR_TAG, line);
            };
            ProcessHelper.addDebugCallback(dxvkLogcatMirror);

            final Runnable finalizeLogging = () -> {
                ProcessHelper.removeDebugCallback(dxvkLogcatMirror);
            };

            final Callback<Integer> finalTermination = (status) -> {
                synchronized (lock) {
                    pid = -1;
                }
                finalizeLogging.run();
                Log.i(TAG, "Guest process terminated with status: " + status);
                if (terminationCallback != null) terminationCallback.call(status);
            };

            return ProcessHelper.exec(command, envp, rootDir, (status) -> {
                // Normal program launch path: one process is the session.
                if (!keepAliveUntilWineserver) {
                    finalTermination.call(status);
                    return;
                }

                // The ARM64EC shell bootstrap can return non-zero after successfully spawning
                // winhandler/target children. Keep the session alive until Wine's server indicates
                // all child processes have exited.
                Log.i(TAG, "Shell bootstrap exited " + status + "; waiting on wineserver -w");

                String wineserverPath = wineBinPath + "/wineserver";
                File wineserverFile = new File(wineserverPath);
                if (!wineserverFile.isFile()) {
                    String alt = wineBinPath + "/wineserver64";
                    if (new File(alt).isFile()) wineserverPath = alt;
                    else wineserverPath = "wineserver";
                }

                int wsPid = ProcessHelper.exec(wineserverPath + " -w", envp, rootDir, (wsStatus) -> {
                    Log.i(TAG, "wineserver -w exited " + wsStatus);
                    finalTermination.call(wsStatus);
                });

                synchronized (lock) {
                    pid = wsPid;
                }

                if (wsPid == -1) {
                    Log.i(TAG, "failed to exec wineserver -w; exiting session");
                    finalTermination.call(0);
                }
            });
        }

        String command = nativeLibraryDir+"/libproot.so";
        command += " --kill-on-exit";
        command += " --rootfs="+rootDir;
        command += " --cwd="+ImageFs.HOME_PATH;
        command += " --bind=/dev";

        if (bindSHM) {
            File shmDir = new File(rootDir, "/tmp/shm");
            shmDir.mkdirs();
            command += " --bind="+shmDir.getAbsolutePath()+":/dev/shm";
        }

        command += " --bind=/proc";
        command += " --bind=/sys";

        // Box64 is a bionic binary and needs the Android linker/runtime.
        File systemDir = new File(rootDir, "/system");
        if (!systemDir.isDirectory()) systemDir.mkdirs();
        File apexDir = new File(rootDir, "/apex");
        if (!apexDir.isDirectory()) apexDir.mkdirs();
        if (new File("/system").isDirectory()) command += " --bind=/system";
        if (new File("/apex").isDirectory()) command += " --bind=/apex";

        // Expose host log directory to the guest for Vulkan/DXVK/Box64 logs.
        File externalLogDir = new File("/storage/emulated/0/Download/Winlator");
        if (!externalLogDir.isDirectory()) externalLogDir.mkdirs();
        command += " --bind=" + externalLogDir.getAbsolutePath();

        // Provide legacy rootfs path expected by some Wine builds.
        File legacyRootfsParent = new File(rootDir, "/data/data/com.winlator/files");
        if (!legacyRootfsParent.isDirectory()) legacyRootfsParent.mkdirs();
        File hostRootfs = new File(environment.getContext().getFilesDir(), "rootfs");
        if (hostRootfs.exists()) {
            command += " --bind=" + hostRootfs.getPath() + ":/data/data/com.winlator/files/rootfs";
        }

        if (bindingPaths != null) {
            for (String path : bindingPaths) command += " --bind="+(new File(path)).getAbsolutePath();
        }

        command += " /usr/bin/env "+envVars.toEscapedString()+" "+guestExecutable;
        Log.i(TAG, "Launching guest command: " + command);

        envVars.clear();
        envVars.put("PROOT_TMP_DIR", tmpDir);
        envVars.put("PROOT_LOADER", nativeLibraryDir+"/libproot-loader.so");
        if (!wow64Mode) envVars.put("PROOT_LOADER_32", nativeLibraryDir+"/libproot-loader32.so");
        String[] envp = envVars.toStringArray();
        lastRootDir = rootDir;
        lastEnvp = envp;
        lastWineBinPath = wineBinPath;
        lastArm64ecWine = false;

        return ProcessHelper.exec(command, envp, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            Log.i(TAG, "Guest process terminated with status: " + status);
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractFexcoreFiles() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String requestedVersion = fexcoreVersion != null ? fexcoreVersion : DefaultVersion.FEXCORE;
        File system32Dir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows/system32");
        if (!system32Dir.isDirectory() && !system32Dir.mkdirs()) return;

        File wow64Dll = new File(system32Dir, "libwow64fex.dll");
        File arm64ecDll = new File(system32Dir, "libarm64ecfex.dll");
        File wowbox64Dll = new File(system32Dir, "wowbox64.dll");
        // Development-oriented behavior: always overwrite the FEX bridge DLLs on container start.
        // We intentionally don't rely on versioning here, because during active FEX iteration
        // we may rebuild the same "version" and still need the new DLLs to be picked up.
        if (wow64Dll.isFile()) wow64Dll.delete();
        if (arm64ecDll.isFile()) arm64ecDll.delete();
        if (boxMode && copyBoxFexcoreFiles(imageFs.getRootDir(), system32Dir)) {
            preferences.edit().putString("current_fexcore_version", "box:cpu-emu-stack").apply();
        }
        else {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "fexcore/fexcore-" + requestedVersion + ".tzst",
                    system32Dir
            );
            preferences.edit().putString("current_fexcore_version", requestedVersion).apply();
        }

        // Optional WoW64 CPU backend used by Winlator-Ludashi for x86-heavy titles (e.g. WoW).
        // We ship it to keep parity, but only use it if the user sets HODLL=wowbox64.dll.
        if (!wowbox64Dll.isFile()) {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "wowbox64/wowbox64-0.3.7.tzst",
                    system32Dir
            );
        }
    }

    private boolean copyBoxFexcoreFiles(File rootDir, File system32Dir) {
        File srcWow64 = new File(rootDir, "libwow64fex.dll");
        File srcArm64ec = new File(rootDir, "libarm64ecfex.dll");
        if (!srcWow64.isFile() || !srcArm64ec.isFile()) {
            Log.w(TAG, "Box cpu-emu-stack FEX DLLs missing; falling back to legacy FEX asset extraction"
                    + " libwow64fex=" + srcWow64.getAbsolutePath()
                    + " exists=" + srcWow64.isFile()
                    + " libarm64ecfex=" + srcArm64ec.getAbsolutePath()
                    + " exists=" + srcArm64ec.isFile());
            return false;
        }

        return copyBoxFexcoreFile(srcWow64, system32Dir)
                && copyBoxFexcoreFile(srcArm64ec, system32Dir);
    }

    private boolean copyBoxFexcoreFile(File srcFile, File system32Dir) {
        File dstFile = new File(system32Dir, srcFile.getName());
        boolean copied = FileUtils.copy(srcFile, dstFile);
        if (!copied) {
            Log.w(TAG, "Failed to copy Box cpu-emu-stack FEX DLL; falling back to legacy FEX asset extraction"
                    + " src=" + srcFile.getAbsolutePath()
                    + " dst=" + dstFile.getAbsolutePath());
            return false;
        }
        if (srcFile.canExecute()) FileUtils.chmod(dstFile, 0771);
        return true;
    }

    public WineSessionProcessController.PauseResult suspendProcess() {
        return ProcessHelper.pauseAllWineProcesses();
    }

    public int resumeProcess() {
        return ProcessHelper.resumeAllWineProcesses();
    }

    public boolean isWineSessionPaused() {
        return ProcessHelper.isWineSessionPaused();
    }
}
