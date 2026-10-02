package com.winlator;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.box.BoxAutomationState;
import com.winlator.box.BoxDebugConfig;
import com.winlator.box.BoxPaths;
import com.winlator.box.BoxRuntime;
import com.winlator.box.BoxSessionForegroundService;
import com.winlator.box.BoxSessionManager;
import com.winlator.box.BoxSessionResumeGate;
import com.winlator.box.FrameGenerationManager;
import com.winlator.box.WineDebugConfig;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contents.AdrenotoolsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.GPUInformation;
import com.winlator.core.KeyValueSet;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.SessionLogs;
import com.winlator.core.SessionLogWriter;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineSessionProcessController;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.core.WineRequestHandler;
import com.winlator.diagnostics.UuVpnGuard;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.widget.FrameRating;
import com.winlator.widget.HUDLayer;
import com.winlator.widget.ImeBridgeEditText;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executors;

    public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final String TAG_GUEST_DEBUG = "GuestDebug";
    private static volatile XServerDisplayActivity currentInstance;
    private static final int ROOTFS_UTILS_PATCH_VERSION = 4;
    private static final String WINHANDLER_LITE_ASSET = "winhandler/winhandler-lite.exe";
    private static final String WINHANDLER_LITE_GUEST_PATH = "C:\\windows\\winhandler-lite.exe";
    private static final String URL_HANDLER_HTTP_KEY = "Software\\Classes\\http\\shell\\open\\command";
    private static final String URL_HANDLER_HTTPS_KEY = "Software\\Classes\\https\\shell\\open\\command";
    private static final String URL_HANDLER_COMMAND =
            "\"C:\\windows\\winhandler-lite.exe\" --open-url \"%1\"";
    private static final String IME_PROBE_ARM64_ASSET = "winhandler/ime-contract-probe-arm64.exe";
    private static final String IME_PROBE_AMD64_ASSET = "winhandler/ime-contract-probe-amd64.exe";
    private static final String FRONTMAGE_WOW_LAUNCHER_ASSET = "winhandler/frontmage-wow-launcher.exe";
    // Bump when changing assets extracted by applyGeneralPatches() (e.g. imagefs_patches.tzst contents)
    // so existing containers re-apply patches without requiring appVersion/imgVersion changes.
    private static final int GENERAL_PATCH_VERSION = 6;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private HUDLayer hudLayer;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String graphicsDriverConfig = Container.DEFAULT_GRAPHICS_DRIVER_CONFIG;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private KeyValueSet audioDriverConfig;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private SessionLogWriter sessionLogWriter;
    private String sessionLogWriterSessionId;
    private boolean shutdownCompleted = false;
    private boolean shutdownWineDrained = true;
    private boolean restartRequested = false;
    private ImeBridgeEditText imeBridgeView;
    private FrameLayout imeBridgeHost;
    private boolean wowImeFocused = false;
    private long lastImeSubmitUptimeMs = 0L;
    private boolean suppressImeUntilFocusReset = false;
    private long suppressImeAutoShowUntilMs = 0L;
    private WineRequestHandler wineRequestHandler;
    private boolean winHandlerLiteLaunchQueued = false;
    private BoxRuntime boxRuntime;
    private BoxSessionManager boxSessionManager;
    private String boxLaunchMode;
    private String boxExecutableHostPath;
    private String boxExecutableGuestPath;
    private String boxWorkingDirectory;
    private ArrayList<String> boxExecutableArgs = new ArrayList<>();
    private String boxLaunchTargetId;
    private int boxControlsProfileId;
    private String boxSessionId;
    private BoxDebugConfig boxDebugConfig = BoxDebugConfig.none();
    private UuVpnGuard uuVpnGuard;
    private final BoxSessionResumeGate resumeGate = new BoxSessionResumeGate();
    private FrameLayout resumeGateOverlay;
    private ImageButton resumeGateButton;
    private volatile boolean hostLifecycleResumed;
    private boolean xServerViewPaused;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BoxSessionForegroundService.ACTION_RETURN_TO_SESSION.equals(getIntent().getAction())) {
            // A notification tap must only re-use an existing singleTask activity.
            // If Android no longer has that task, do not create an empty Box launch.
            BoxSessionForegroundService.stop(this);
            finish();
            return;
        }
        currentInstance = this;
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        BoxDebugConfig initialDebugConfig = BoxDebugConfig.fromIntent(getIntent());
        if (initialDebugConfig.isEnabled()) {
            WineDebugConfig.persistRequestedProfile(preferences, initialDebugConfig.profile);
        }

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = WineDebugConfig.fromPreferences(preferences).isEnabled();
        if (enableLogs) {
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
            ProcessHelper.addDebugCallback((line) -> Log.d(TAG_GUEST_DEBUG, line));
        }
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.main_menu_logs).setVisible(enableLogs);
        if (XrActivity.isSupported()) menu.findItem(R.id.main_menu_magnifier).setVisible(false);
        navigationView.setNavigationItemSelectedListener(this);

        imageFs = ImageFs.find(this);
        boxRuntime = BoxRuntime.get(this);
        boxSessionManager = boxRuntime.getSessionManager();
        applyBoxLaunchIntent(getIntent());
        uuVpnGuard = UuVpnGuard.start(this);

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        if (!isGenerateWineprefix()) {
            if (boxLaunchMode != null && !boxLaunchMode.isEmpty()) {
                container = boxRuntime.getOrCreateContainer();
                FileUtils.symlink("./xuser-box", new File(imageFs.getRootDir(), ImageFs.HOME_PATH).getAbsolutePath());
                String boxWineId = boxRuntime.getWineRuntimeIdentifier();
                if (boxWineId != null && !boxWineId.isEmpty()) {
                    imageFs.setWinePath(new File(imageFs.getRootDir(), "opt/" + boxWineId).getPath());
                }
            }
            else {
                containerManager = new ContainerManager(this);
                container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
                containerManager.activateContainer(container);
            }

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            taskAffinityMask = (short)ProcessHelper.getAffinityMask(container.getCPUList(true));
            taskAffinityMaskWoW64 = (short)ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
            firstTimeBoot = container.getExtra("appVersion").isEmpty();

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);
            if (!wineInfo.isArm64EC()) {
                WineInfo arm64ec = WineUtils.getFirstArm64ECWineInfo(this);
                if (arm64ec != null) {
                    wineInfo = arm64ec;
                    container.setWineVersion(arm64ec.identifier());
                    container.saveData();
                }
            }

            if (wineInfo != WineInfo.MAIN_WINE_VERSION && (boxLaunchMode == null || boxLaunchMode.isEmpty())) imageFs.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            graphicsDriver = container.getGraphicsDriver();
            graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriver = container.getAudioDriver();
            dxwrapper = container.getDXWrapper();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String audioDriverConfig = container.getExtra("audioDriverConfig");
            screenSize = container.getScreenSize();

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                audioDriverConfig = shortcut.getExtra("audioDriverConfig", container.getExtra("audioDriverConfig"));
                screenSize = shortcut.getExtra("screenSize", container.getScreenSize());

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));
            }

            if (dxwrapper != null && dxwrapper.startsWith("dxvk")) this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);
            this.audioDriverConfig = new KeyValueSet(audioDriverConfig);

            if (!wineInfo.isWin64()) {
                onExtractFileListener = (file, size) -> {
                    String path = file.getPath();
                    if (path.contains("system32/")) return null;
                    return new File(path.replace("syswow64/", "system32/"));
                };
            }
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(
                new ScreenInfo(screenSize),
                boxLaunchMode != null && !boxLaunchMode.isEmpty());
        xServer.setWinHandler(winHandler);
        boolean[] winStarted = {false};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            private boolean shouldDismissStartingUp(Window window) {
                if (window == null) return false;
                if (!window.attributes.isMapped()) return false;
                // Root window has no parent; don't use it as a "session is ready" signal.
                if (window.getParent() == null) return false;
                if (!window.isInputOutput()) return false;
                return window.getWidth() > 1 && window.getHeight() > 1;
            }

            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
            }

            @Override
            public void onMapWindow(Window window) {
                // Some apps (incl. wfm/winhandler flow) may map a visible window before it starts
                // producing content updates or before WM_HINTS/WM_NAME are fully set. If we only
                // dismiss the "Starting up..." dialog on onUpdateWindowContent + isApplicationWindow(),
                // we can get stuck forever. Use a simpler heuristic on map as a fallback.
                if (!winStarted[0] && shouldDismissStartingUp(window)) {
                    Log.i(TAG_GUEST_DEBUG, "Starting up dismissed by mapped window: id=" + window.id
                            + " class=" + window.getClassName()
                            + " name=" + window.getName()
                            + " pid=" + window.getProcessId()
                            + " w=" + window.getWidth()
                            + " h=" + window.getHeight());
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
                assignTaskAffinity(window);
            }

            @Override
            public void onUnmapWindow(Window window) {
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                ensureGraphicsDriverX11();
            }
            setupXEnvironment();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && BoxSessionForegroundService.ACTION_RETURN_TO_SESSION.equals(intent.getAction())) {
            markBoxSessionEvent("foreground-notification-return");
            return;
        }
        setIntent(intent);
        applyBoxLaunchIntent(intent);
        setupSessionLogging();
        if (environment != null &&
                ((boxExecutableGuestPath != null && !boxExecutableGuestPath.isEmpty()) ||
                        (boxExecutableHostPath != null && !boxExecutableHostPath.isEmpty()))) {
            String command = getWineStartCommand();
            if (boxSessionId != null && !boxSessionId.isEmpty()) {
                boxSessionManager.markEvent(boxSessionId, "warm-session-exec " + command);
            }
            Log.i(TAG_GUEST_DEBUG, "Queue warm Box launch on existing WinHandler: " + command);
            winHandler.exec(command);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        hostLifecycleResumed = true;
        BoxAutomationState.setForegroundActivity("XServerDisplayActivity");
        if (uuVpnGuard != null) uuVpnGuard.onHostResumed();
        if (frameRating != null && container != null && container.isShowFPS()) {
            frameRating.startMonitoring();
        }
        if (environment != null && !shutdownCompleted) {
            xServerView.onResume();
            xServerViewPaused = false;
            boolean wineSessionPaused = environment.isWineSessionPaused();
            long generation = resumeGate.onHostResumed(wineSessionPaused);
            if (generation != BoxSessionResumeGate.NO_GENERATION) {
                showResumeGate(false);
                markBoxSessionEvent("resume-gate-waiting generation=" + generation);
                Log.i(TAG_GUEST_DEBUG, "Waiting for manual Wine session resume generation=" + generation);
                xServerView.getRenderer().runAfterNextFrame(
                        () -> onResumeGateSurfaceReady(generation));
            }
            else {
                hideResumeGate();
            }
        }
    }

    @Override
    public void onPause() {
        hostLifecycleResumed = false;
        if (touchpadView != null) touchpadView.clearControllerTouchModifiers();
        BoxAutomationState.clearForegroundActivity("XServerDisplayActivity");
        if (frameRating != null) frameRating.stopMonitoring();
        if (resumeGate.isWaitingForManualResume() && !resumeGate.isSurfaceReady()) {
            markBoxSessionEvent("resume-gate-no-frame generation=" + resumeGate.getGeneration());
        }
        if (xServerView != null) xServerView.getRenderer().cancelAfterNextFrame();
        try {
            if (environment != null) {
                if (!shutdownCompleted) {
                    WineSessionProcessController.PauseResult result = environment.onPause();
                    if (result != null) {
                        markBoxSessionEvent("lifecycle-pause candidates="
                                + result.getInitialProcessCount()
                                + " stopped=" + result.getStopSignalCount()
                                + " alreadyPaused=" + result.wasAlreadyPaused());
                        Log.i(TAG_GUEST_DEBUG, "Paused Wine session candidates="
                                + result.getInitialProcessCount()
                                + " stopped=" + result.getStopSignalCount());
                    }
                }
                resumeGate.onHostPaused(environment.isWineSessionPaused());
                hideResumeGate();
                if (!xServerViewPaused) {
                    xServerView.onPause();
                    xServerViewPaused = true;
                }
            }
        }
        finally {
            // Freeze Wine before pausing the display surface, and finish the
            // Activity transition only after both have completed.
            super.onPause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && touchpadView != null) {
            touchpadView.clearControllerTouchModifiers();
        }
    }

    @Override
    protected void onDestroy() {
        if (xServerView != null) xServerView.getRenderer().cancelAfterNextFrame();
        resumeGate.shutdown();
        hideResumeGate();
        if (uuVpnGuard != null) {
            uuVpnGuard.close();
            uuVpnGuard = null;
        }
        boolean drained = shutdownRuntimeOnce();
        if (drained) markBoxSessionFinishedIfRunning(-1);
        else markBoxSessionEvent("shutdown-incomplete wine-processes-remain");
        BoxAutomationState.clearXServerActive(boxSessionId);
        if (currentInstance == this) currentInstance = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.main_menu_keyboard:
                suppressImeUntilFocusReset = false;
                showSoftKeyboard("menu_keyboard");
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.main_menu_exit:
                exit();
                break;
        }
        return true;
    }

    private void exit() {
        exit(-1);
    }

    private void exit(int exitStatus) {
        synchronized (this) {
            if (restartRequested) return;
            restartRequested = true;
        }
        if (!shutdownRuntimeOnce()) {
            markBoxSessionEvent("shutdown-incomplete wine-processes-remain");
            synchronized (this) {
                restartRequested = false;
                shutdownCompleted = false;
            }
            return;
        }
        markBoxSessionFinishedIfRunning(exitStatus);
        AppUtils.restartApplication(this);
    }

    private void stopFromDebug() {
        synchronized (this) {
            if (restartRequested) return;
            restartRequested = true;
        }
        if (boxSessionId != null && !boxSessionId.isEmpty()) {
            boxSessionManager.markEvent(boxSessionId, "container-stop-requested");
        }
        if (!shutdownRuntimeOnce()) {
            markBoxSessionEvent("container-stop-incomplete wine-processes-remain");
            synchronized (this) {
                restartRequested = false;
                shutdownCompleted = false;
            }
            return;
        }
        markBoxSessionFinishedIfRunning(-1);
        finish();
    }

    private boolean shutdownRuntimeOnce() {
        synchronized (this) {
            if (shutdownCompleted) return shutdownWineDrained;
            shutdownCompleted = true;
            shutdownWineDrained = true;
        }
        if (xServerView != null) xServerView.getRenderer().cancelAfterNextFrame();
        resumeGate.shutdown();
        hideResumeGate();
        int resumed = ProcessHelper.resumeAllWineProcesses();
        markBoxSessionEvent("shutdown-resume continued=" + resumed);
        boolean guestDrained = true;
        try {
            stopSessionLogging();
            guestDrained = stopGuestProgramFirst();
            winHandler.stop();
            if (wineRequestHandler != null) wineRequestHandler.stop();
            if (environment != null) {
                environment.stopEnvironmentComponents(GuestProgramLauncherComponent.class);
            }
        }
        finally {
            shutdownWineDrained =
                    !WineSessionProcessController.getInstance().hasActiveWineProcesses();
            if (!guestDrained && shutdownWineDrained) {
                markBoxSessionEvent("shutdown-drained-after-force-timeout");
            }
            ProcessHelper.setWineSessionProcessEventListener(null);
            if (shutdownWineDrained) BoxSessionForegroundService.stop(this);
        }
        return shutdownWineDrained;
    }

    private void markBoxSessionFinishedIfRunning(int exitStatus) {
        if (boxSessionManager == null || boxSessionId == null || boxSessionId.isEmpty()) return;
        boxSessionManager.markFinishedIfRunning(boxSessionId, exitStatus);
    }

    private void markBoxSessionEvent(String marker) {
        if (boxSessionManager == null || boxSessionId == null || boxSessionId.isEmpty()) return;
        boxSessionManager.markEvent(boxSessionId, marker);
    }

    private boolean stopGuestProgramFirst() {
        if (environment == null) {
            return !WineSessionProcessController.getInstance().hasActiveWineProcesses();
        }
        GuestProgramLauncherComponent guestProgramLauncherComponent =
                environment.getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) {
            return guestProgramLauncherComponent.stopAndDrain();
        }
        return !WineSessionProcessController.getInstance().hasActiveWineProcesses();
    }

    private void stopSessionLogging() {
        if (sessionLogWriter != null) {
            ProcessHelper.removeDebugCallback(sessionLogWriter);
            sessionLogWriter.close();
            sessionLogWriter = null;
        }
        sessionLogWriterSessionId = null;
    }

    private void applyBoxLaunchIntent(@Nullable Intent intent) {
        String previousSessionId = boxSessionId;
        boxLaunchMode = intent != null ? intent.getStringExtra("box_launch_mode") : null;
        boxExecutableHostPath = intent != null ? intent.getStringExtra("box_executable_path") : null;
        boxExecutableGuestPath = intent != null ? intent.getStringExtra("box_executable_guest_path") : null;
        boxWorkingDirectory = intent != null ? intent.getStringExtra("box_working_directory") : null;
        boxLaunchTargetId = intent != null ? intent.getStringExtra("box_launch_target_id") : null;
        boxControlsProfileId = intent != null ? intent.getIntExtra("box_controls_profile_id", 0) : 0;
        ArrayList<String> launchArgs = intent != null ? intent.getStringArrayListExtra("box_executable_args") : null;
        boxExecutableArgs = launchArgs != null ? launchArgs : new ArrayList<>();
        boxSessionId = intent != null ? intent.getStringExtra("box_session_id") : null;
        boxDebugConfig = BoxDebugConfig.fromIntent(intent);
        if (boxDebugConfig.isEnabled()) {
            WineDebugConfig.persistRequestedProfile(preferences, boxDebugConfig.profile);
        }
        if (sessionLogWriter != null && !Objects.equals(previousSessionId, boxSessionId)) {
            stopSessionLogging();
        }
        BoxAutomationState.setXServerActive(boxSessionId);
    }

    private void setupSessionLogging() {
        if (boxSessionId == null || boxSessionId.isEmpty()) return;
        boolean reattach = false;
        if (sessionLogWriter != null) {
            if (boxSessionId.equals(sessionLogWriterSessionId)) return;
            stopSessionLogging();
            reattach = true;
        }
        File logDir = boxSessionManager.getSessionDir(boxSessionId);
        logDir.mkdirs();
        sessionLogWriter = new SessionLogWriter(boxSessionManager.getGuestLogFile(boxSessionId));
        sessionLogWriterSessionId = boxSessionId;
        ProcessHelper.addDebugCallback(sessionLogWriter);
        boxSessionManager.markEvent(boxSessionId, reattach ? "guest-log-reattached" : "guest-log-attached");
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        String generalPatchVersion = String.valueOf(GENERAL_PATCH_VERSION);
        boolean containerDataChanged = false;

        boolean needGeneralPatches =
                !container.getExtra("appVersion").equals(appVersion) ||
                !container.getExtra("imgVersion").equals(imgVersion) ||
                !container.getExtra("generalPatchVersion").equals(generalPatchVersion);
        if (needGeneralPatches) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            container.putExtra("generalPatchVersion", generalPatchVersion);
            containerDataChanged = true;
        }

        String rootfsPatchVersion = String.valueOf(ROOTFS_UTILS_PATCH_VERSION);
        if (!rootfsPatchVersion.equals(container.getExtra("rootfsUtilsPatchVersion"))) {
            applyRootfsUtilsPatches();
            container.putExtra("rootfsUtilsPatchVersion", rootfsPatchVersion);
            containerDataChanged = true;
        }

        String dxwrapperCacheId = getDXWrapperCacheId();
        if (!dxwrapperCacheId.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles();
            container.putExtra("dxwrapper", dxwrapperCacheId);
            containerDataChanged = true;
        }

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection() != Container.STARTUP_SELECTION_NORMAL);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        ensureWinHandlerLiteInstalled(imageFs.getRootDir());
        ensureImeContractProbesInstalled(imageFs.getRootDir());
        ensureFrontMageWowLauncherInstalled(imageFs.getRootDir());
        configureBoxAndroidBrowserUrlHandler();
        if (containerDataChanged) container.saveData();
    }

    private void configureBoxAndroidBrowserUrlHandler() {
        if (boxLaunchMode == null || boxLaunchMode.isEmpty() || container == null) return;

        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        if (!userRegFile.isFile() || !systemRegFile.isFile()) {
            Log.w(TAG_GUEST_DEBUG, "Cannot configure Android browser URL handler: prefix registry is missing");
            return;
        }

        boolean enabled = preferences.getBoolean(
                BoxRuntime.PREF_OPEN_LINKS_IN_ANDROID_BROWSER,
                BoxRuntime.DEFAULT_OPEN_LINKS_IN_ANDROID_BROWSER);
        /*
         * Wine's HKCR view in the Box runtime resolves these protocol classes from
         * system.reg and does not merge the same keys from user.reg. Remove only
         * our obsolete HKCU values, then install the owned machine-class override
         * inside this prefix so ShellExecute observes it through HKCR.
         */
        try (WineRegistryEditor userRegistryEditor = new WineRegistryEditor(userRegFile)) {
            removeOwnedUrlHandler(userRegistryEditor, URL_HANDLER_HTTP_KEY);
            removeOwnedUrlHandler(userRegistryEditor, URL_HANDLER_HTTPS_KEY);
        }
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            if (enabled) {
                registryEditor.setStringValue(URL_HANDLER_HTTP_KEY, null, URL_HANDLER_COMMAND);
                registryEditor.setStringValue(URL_HANDLER_HTTPS_KEY, null, URL_HANDLER_COMMAND);
            }
            else {
                removeOwnedUrlHandler(registryEditor, URL_HANDLER_HTTP_KEY);
                removeOwnedUrlHandler(registryEditor, URL_HANDLER_HTTPS_KEY);
            }
        }
        Log.i(TAG_GUEST_DEBUG, "Android browser URL bridge " + (enabled ? "enabled" : "disabled") +
                " for Box prefix");
    }

    private static void removeOwnedUrlHandler(WineRegistryEditor registryEditor, String key) {
        if (URL_HANDLER_COMMAND.equals(registryEditor.getStringValue(key, null))) {
            registryEditor.removeKey(key, true);
        }
    }

    public boolean isAndroidBrowserUrlBridgeEnabled() {
        return boxLaunchMode != null && !boxLaunchMode.isEmpty() &&
                preferences.getBoolean(
                        BoxRuntime.PREF_OPEN_LINKS_IN_ANDROID_BROWSER,
                        BoxRuntime.DEFAULT_OPEN_LINKS_IN_ANDROID_BROWSER);
    }

    public void openUrlInAndroidBrowser(String url) {
        if (!isAndroidBrowserUrlBridgeEnabled() || url == null) return;

        Uri uri = Uri.parse(url);
        Intent chromeIntent = new Intent(Intent.ACTION_VIEW, uri);
        chromeIntent.setPackage("com.android.chrome");
        try {
            startActivity(chromeIntent);
            return;
        }
        catch (ActivityNotFoundException | SecurityException e) {
            Log.i(TAG_GUEST_DEBUG, "Chrome unavailable for URL bridge; trying Android default browser");
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        }
        catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG_GUEST_DEBUG, "No Android browser is available for URL bridge");
            AppUtils.showToast(this, R.string.android_browser_unavailable);
        }
    }

    private void ensureWineBuiltinsInPrefix() {
        if (container == null || wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return;

        File containerDir = container.getRootDir();
        if (containerDir == null || !containerDir.isDirectory()) return;

        File windowsDir = new File(containerDir, ".wine/drive_c/windows");
        File system32Dir = new File(windowsDir, "system32");
        File syswow64Dir = new File(windowsDir, "syswow64");
        File system32DriversDir = new File(system32Dir, "drivers");

        // Fast-path: if both key DLLs exist and core drivers match, assume the prefix is populated.
        boolean haveSystem32Kernel32 = new File(system32Dir, "kernel32.dll").isFile();
        boolean haveSyswow64Kernel32 = new File(syswow64Dir, "kernel32.dll").isFile();
        boolean haveMountMgrDriver = isPrefixDriverUpToDate(wineInfo, "mountmgr.sys", system32DriversDir);
        if (haveSystem32Kernel32 && haveSyswow64Kernel32 && haveMountMgrDriver) return;

        int copied = 0;
        if (wineInfo.isArm64EC()) {
            copied += copyWineBuiltinsIfMissing(wineInfo, "aarch64-windows", system32Dir);
            copied += syncWineDrivers(wineInfo, "aarch64-windows", system32DriversDir);
        }
        else {
            copied += copyWineBuiltinsIfMissing(wineInfo, "x86_64-windows", system32Dir);
            copied += syncWineDrivers(wineInfo, "x86_64-windows", system32DriversDir);
        }
        copied += copyWineBuiltinsIfMissing(wineInfo, "i386-windows", syswow64Dir);

        Log.i(TAG_GUEST_DEBUG, "Prefix builtins repair: copied=" + copied +
                " wine=" + wineInfo.identifier() +
                " system32_kernel32=" + new File(system32Dir, "kernel32.dll").isFile() +
                " syswow64_kernel32=" + new File(syswow64Dir, "kernel32.dll").isFile() +
                " mountmgr_ok=" + isPrefixDriverUpToDate(wineInfo, "mountmgr.sys", system32DriversDir));
    }

    private int copyWineBuiltinsIfMissing(WineInfo wineInfo, String srcName, File dstDir) {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);
        File[] srcFiles = srcDir.listFiles(file -> file != null && file.isFile());
        if (srcFiles == null || srcFiles.length == 0) {
            Log.w(TAG_GUEST_DEBUG, "Prefix builtins repair: missing src dir: " + srcDir.getAbsolutePath());
            return 0;
        }

        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            Log.w(TAG_GUEST_DEBUG, "Prefix builtins repair: failed to create dst dir: " + dstDir.getAbsolutePath());
            return 0;
        }

        int copied = 0;
        for (File file : srcFiles) {
            String name = file.getName();
            File srcFile = file;

            // Ludashi/bionic special-case: use the i386 iexplore.exe on arm64ec.
            if ("iexplore.exe".equals(name) && wineInfo.isArm64EC() && "aarch64-windows".equals(srcName)) {
                File fallback = new File(wineInfo.path + "/lib/wine/i386-windows/iexplore.exe");
                if (fallback.isFile()) srcFile = fallback;
            }

            // Ludashi/bionic skips these in common extraction.
            if ("tabtip.exe".equals(name) || "icu.dll".equals(name)) continue;

            File dstFile = new File(dstDir, name);
            if (dstFile.exists()) continue;

            if (FileUtils.copy(srcFile, dstFile)) copied++;
        }

        return copied;
    }

    private boolean isPrefixDriverUpToDate(WineInfo wineInfo, String driverName, File dstDriversDir) {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;
        if (driverName == null || driverName.isEmpty()) return false;

        String srcName = wineInfo.isArm64EC() ? "aarch64-windows" : "x86_64-windows";
        File srcFile = new File(wineInfo.path + "/lib/wine/" + srcName + "/" + driverName);
        File dstFile = new File(dstDriversDir, driverName);
        if (!srcFile.isFile() || !dstFile.isFile()) return false;
        return srcFile.length() == dstFile.length();
    }

    private int syncWineDrivers(WineInfo wineInfo, String srcName, File dstDriversDir) {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);
        File[] srcFiles = srcDir.listFiles(file -> file != null && file.isFile() && file.getName().endsWith(".sys"));
        if (srcFiles == null || srcFiles.length == 0) return 0;

        if (!dstDriversDir.isDirectory() && !dstDriversDir.mkdirs()) return 0;

        int copied = 0;
        for (File srcFile : srcFiles) {
            String name = srcFile.getName();
            File dstFile = new File(dstDriversDir, name);
            // If a driver exists but doesn't match the selected Wine build, overwrite it.
            if (dstFile.isFile() && dstFile.length() == srcFile.length()) continue;
            if (FileUtils.copy(srcFile, dstFile)) copied++;
        }
        return copied;
    }

    private void setupXEnvironment() {
        envVars.put("LC_ALL", "en_US.UTF-8");
        envVars.put("WINEPREFIX", imageFs.getRootDir().getPath() + ImageFs.WINEPREFIX);

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setArm64ecWine(wineInfo != null && wineInfo.isArm64EC());
        guestProgramLauncherComponent.setBoxMode(boxSessionId != null && !boxSessionId.isEmpty());

        if (container != null) {
            if (container.getStartupSelection() == Container.STARTUP_SELECTION_AGGRESSIVE) winHandler.killProcess("services.exe");

            boolean wow64Mode = container.isWoW64Mode();
            // On arm64ec Wine builds, "wine explorer" behaves like a wrapper that starts the system32 explorer
            // and drops extra arguments. We need the Winlator-provided explorer shim at C:\windows\explorer.exe
            // so it can forward "winhandler.exe wfm.exe" and keep the container alive.
            String explorerExe = (wineInfo != null && wineInfo.isArm64EC()) ? " C:\\windows\\explorer.exe" : " explorer";
            String guestExecutable = wineInfo.getExecutable(this, wow64Mode)+ explorerExe +" /desktop=shell,"+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setWoW64Mode(wow64Mode);
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (boxRuntime != null) {
                for (java.util.Map.Entry<String, String> entry : boxRuntime.getRuntimeOverrides().entrySet()) {
                    envVars.put(entry.getKey(), entry.getValue());
                }
            }
            if (container.isShowFPS() || (boxLaunchMode != null && !boxLaunchMode.isEmpty())) {
                envVars.put("DXVK_HUD", "0");
                envVars.put("MANGOHUD", "0");
                envVars.remove("MANGOHUD_CONFIG");
                envVars.remove("MANGOHUD_CONFIGFILE");
            }
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            applyBoxDebugConfig();
            WineDebugConfig.State wineDebugState =
                    WineDebugConfig.fromPreferences(preferences);
            // WINEDEBUG has one owner. Remove legacy spec/container/shortcut/runtime
            // copies and apply exactly what Settings currently displays.
            envVars.remove("WINEDEBUG");
            envVars.put("WINEDEBUG", wineDebugState.buildWineDebug());

            // Startup-only frame generation is explicitly disabled unless the
            // managed layer, private DLL and generated config all validate.
            new FrameGenerationManager(this).applyLaunchEnv(envVars);

            setupSessionLogging();

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));
            guestProgramLauncherComponent.setFEXCoreVersion(shortcut != null ? shortcut.getExtra("fexcoreVersion", container.getFEXCoreVersion()) : container.getFEXCoreVersion());
            guestProgramLauncherComponent.setFEXCorePreset(shortcut != null ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset()) : container.getFEXCorePreset());
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        ensureLegacyTmpLink(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH);

        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));
        ensureLegacyTmpLink(rootPath, UnixSocketConfig.XSERVER_PATH);
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals("alsa")) {
            // Use guest-visible socket path for ALSA client.
            String rootPathForGuest = (wineInfo != null && wineInfo.isArm64EC()) ? rootPath : "";
            envVars.put("ANDROID_ALSA_SERVER", rootPathForGuest + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(new ALSAServerComponent(
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)
            ));
            ensureLegacyTmpLink(rootPath, UnixSocketConfig.ALSA_SERVER_PATH);
        }
        else if (audioDriver.equals("pulseaudio")) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", 16));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", 1.0f));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", 1));
            }
            else envVars.put("PULSE_LATENCY_MSEC", (byte)16);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver.equals("virgl")) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        if (boxSessionId != null && !boxSessionId.isEmpty()) boxSessionManager.writeEffectiveEnv(boxSessionId, envVars);
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback(
                status -> runOnUiThread(() -> exit(status)));
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) generateWineprefix();

        // Start the host-side WinHandler server before Wine launches the guest helper.
        // Otherwise the helper may exit early if it can't complete its init handshake.
        winHandler.setOnImeFocusListener((focused, boxName) -> runOnUiThread(() -> {
            int requestId = winHandler.nextImeRequestId();
            JSONObject focusDetails = new JSONObject();
            try {
                focusDetails.put("focused", focused);
                focusDetails.put("focusName", boxName != null ? boxName : "");
            }
            catch (JSONException ignored) {}
            traceImeEvent("focus", requestId, focusDetails);
            if (focused) {
                long now = SystemClock.uptimeMillis();
                if (suppressImeUntilFocusReset) {
                    if (now >= suppressImeAutoShowUntilMs) {
                        suppressImeUntilFocusReset = false;
                    }
                    else {
                    return;
                    }
                }
                if (now < suppressImeAutoShowUntilMs) {
                    return;
                }
                if (wowImeFocused && imeBridgeView != null) {
                    return;
                }
                wowImeFocused = true;
                showSoftKeyboard("ime_focus_true:" + boxName);
            } else {
                suppressImeUntilFocusReset = false;
                wowImeFocused = false;
                suppressImeAutoShowUntilMs = SystemClock.uptimeMillis() + 120L;
                hideSoftKeyboard();
            }
        }));
        winHandler.setOnImeStatusListener(status -> traceImeEvent("helper-status",
                status.optInt("requestId", 0), status));
        winHandler.start();
        if (wineRequestHandler == null) wineRequestHandler = new WineRequestHandler(this);
        wineRequestHandler.start();
        if (!usesWinHandlerLitePrimaryLaunch()) {
            queueWinHandlerLiteStart();
            queueBoxTargetStartFallback();
        }
        ProcessHelper.setWineSessionProcessEventListener(result -> {
            String marker = "lifecycle-pause-verified initial="
                    + result.getInitialProcessCount()
                    + " initialStopped=" + result.getInitialStopSignalCount()
                    + " verified=" + result.getVerifiedStoppedCount()
                    + " retried=" + result.getRetrySignalCount()
                    + " late=" + result.getLateProcessCount()
                    + " lateStopped=" + result.getLateStopSignalCount()
                    + " tracked=" + result.getTrackedProcessCount();
            markBoxSessionEvent(marker);
            Log.i(TAG_GUEST_DEBUG, marker);
        });
        boolean foregroundServiceStarted = BoxSessionForegroundService.start(this);
        markBoxSessionEvent("foreground-service-start requested=" + foregroundServiceStarted);
        try {
            environment.startEnvironmentComponents();
            if (!hostLifecycleResumed) {
                runOnUiThread(this::pauseEnvironmentStartedInBackground);
            }
            if (!guestProgramLauncherComponent.hasRunningProcess()) {
                markBoxSessionEvent("guest-start-failed foreground-service-stop");
                ProcessHelper.setWineSessionProcessEventListener(null);
                BoxSessionForegroundService.stop(this);
            }
        }
        catch (RuntimeException | Error error) {
            markBoxSessionEvent("environment-start-failed foreground-service-stop");
            ProcessHelper.setWineSessionProcessEventListener(null);
            BoxSessionForegroundService.stop(this);
            throw error;
        }
        envVars.clear();
        dxwrapperConfig = null;
        audioDriverConfig = null;
    }

    private void pauseEnvironmentStartedInBackground() {
        if (hostLifecycleResumed || shutdownCompleted || environment == null) return;

        WineSessionProcessController.PauseResult result = environment.onPause();
        if (result != null) {
            String marker = "background-start-pause candidates="
                    + result.getInitialProcessCount()
                    + " stopped=" + result.getStopSignalCount()
                    + " alreadyPaused=" + result.wasAlreadyPaused();
            markBoxSessionEvent(marker);
            Log.i(TAG_GUEST_DEBUG, marker);
        }
        resumeGate.onHostPaused(environment.isWineSessionPaused());
        hideResumeGate();
        if (xServerView != null && !xServerViewPaused) {
            xServerView.getRenderer().cancelAfterNextFrame();
            xServerView.onPause();
            xServerViewPaused = true;
        }
    }

    private void ensureLegacyTmpLink(String rootPath, String socketPath) {
        String legacyPath = socketPath.replaceFirst("^/usr/tmp/", "/tmp/");
        if (legacyPath.equals(socketPath)) return;

        File target = new File(rootPath, socketPath);
        File link = new File(rootPath, legacyPath);
        File linkParent = new File(link.getParent());
        if (!linkParent.isDirectory()) linkParent.mkdirs();

        try {
            if (link.exists()) link.delete();
            FileUtils.symlink(target.getPath(), link.getPath());
        }
        catch (Exception ignored) {}
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        imeBridgeHost = rootView;
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);

        if (shortcut != null) {
            if (shortcut.getExtra("forceFullscreen", "0").equals("1")) renderer.setForceFullscreenWMClass(shortcut.wmClass);
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        boolean icpTapDragMouseGestureEnabled = container != null &&
                "1".equals(container.getExtra(Container.EXTRA_ICP_TAP_DRAG_MOUSE_GESTURE, "0"));
        touchpadView.setTapDragMouseModeEnabled(icpTapDragMouseGestureEnabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        setHostHudVisible(container != null && container.isShowFPS());
        setupResumeGate(rootView);

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }
        else if (boxControlsProfileId > 0) {
            ControlsProfile profile = inputControlsManager.getProfile(boxControlsProfileId);
            if (profile != null) showInputControls(profile);
        }

        boolean imeOverlayKeyboard = preferences.getBoolean("ime_overlay_keyboard", false);
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, visible -> {
            if (imeOverlayKeyboard) {
                renderer.setScreenOffsetYRelativeToCursor(false);
            }
            else {
                renderer.setScreenOffsetYRelativeToCursor(visible);
            }

            if (!visible) {
                destroyImeBridgeView("keyboard_hidden", true);
            }
        });
        setupImeBridge();
    }

    private void setupResumeGate(FrameLayout rootView) {
        resumeGateOverlay = new FrameLayout(this);
        resumeGateOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        resumeGateOverlay.setBackgroundColor(Color.TRANSPARENT);
        resumeGateOverlay.setClickable(true);
        resumeGateOverlay.setFocusable(true);
        resumeGateOverlay.setVisibility(View.GONE);
        resumeGateOverlay.setOnClickListener(view -> {});

        resumeGateButton = new ImageButton(this);
        int buttonSize = dpToPx(72);
        int buttonPadding = dpToPx(18);
        FrameLayout.LayoutParams buttonLayoutParams =
                new FrameLayout.LayoutParams(buttonSize, buttonSize);
        buttonLayoutParams.gravity = Gravity.CENTER;
        resumeGateButton.setLayoutParams(buttonLayoutParams);
        resumeGateButton.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        resumeGateButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        resumeGateButton.setImageResource(R.drawable.icon_play);
        resumeGateButton.setColorFilter(Color.BLACK);
        resumeGateButton.setContentDescription(getString(R.string.resume_windows_session));
        resumeGateButton.setBackground(createResumeButtonBackground());
        resumeGateButton.setOnClickListener(view -> requestManualResume());

        resumeGateOverlay.addView(resumeGateButton);
        rootView.addView(resumeGateOverlay);
    }

    private GradientDrawable createResumeButtonBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.WHITE);
        return background;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void onResumeGateSurfaceReady(long generation) {
        if (!resumeGate.onSurfaceFrame(generation)) {
            String marker = "resume-gate-stale-frame expected="
                    + generation + " current=" + resumeGate.getGeneration();
            markBoxSessionEvent(marker);
            Log.i(TAG_GUEST_DEBUG, marker);
            return;
        }

        showResumeGate(true);
        markBoxSessionEvent("resume-gate-surface-ready generation=" + generation);
        Log.i(TAG_GUEST_DEBUG, "Manual resume surface ready generation=" + generation);
    }

    private void requestManualResume() {
        if (!resumeGate.beginManualResume()) return;
        if (environment == null || shutdownCompleted) {
            resumeGate.cancelManualResume();
            return;
        }

        int resumed = environment.onResume();
        resumeGate.completeManualResume();
        hideResumeGate();
        markBoxSessionEvent("lifecycle-manual-resume continued=" + resumed);
        Log.i(TAG_GUEST_DEBUG, "Manually resumed Wine session processes=" + resumed);
    }

    private void showResumeGate(boolean surfaceReady) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> showResumeGate(surfaceReady));
            return;
        }
        if (resumeGateOverlay == null || resumeGateButton == null) return;

        resumeGateButton.setEnabled(surfaceReady);
        resumeGateButton.setAlpha(surfaceReady ? 1.0f : 0.45f);
        resumeGateOverlay.setVisibility(View.VISIBLE);
        resumeGateOverlay.bringToFront();
        if (surfaceReady) resumeGateButton.requestFocus();
        else resumeGateOverlay.requestFocus();
    }

    private void hideResumeGate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::hideResumeGate);
            return;
        }
        if (resumeGateOverlay != null) resumeGateOverlay.setVisibility(View.GONE);
        if (xServerView != null) {
            xServerView.setFocusableInTouchMode(true);
            xServerView.requestFocus();
        }
    }

    private void setupImeBridge() {
        if (this instanceof XrActivity || imeBridgeHost == null) return;
        ImeBridgeEditText existing = findViewById(R.id.XRTextInput);
        if (existing != null) {
            imeBridgeView = existing;
            configureImeBridgeView(existing);
        }
    }

    private void configureImeBridgeView(ImeBridgeEditText view) {
        if (view == null) return;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0.0f);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setClickable(false);
        view.setLongClickable(false);
        view.setEnabled(true);
        view.setListener(new ImeBridgeEditText.Listener() {
            @Override
            public void onInputConnectionCreated() {
                traceImeEvent("input-connection-created", winHandler.nextImeRequestId(), null);
            }

            @Override
            public void onComposingText(CharSequence text) {
                traceImeTextShape("composition", winHandler.nextImeRequestId(), text, false);
            }

            @Override
            public void onFinishComposingText() {
                traceImeEvent("composition-finished", winHandler.nextImeRequestId(), null);
            }

            @Override
            public void onCommitText(CharSequence text) {
                if (text == null || text.length() == 0 || xServer == null) return;
                traceImeTextShape("commit", 0, text, false);
                forwardImeCommitToWoW(text);
            }

            @Override
            public void onDeleteSurroundingText(int beforeLength, int afterLength) {
                boolean composing = view.isComposingText();
                JSONObject details = new JSONObject();
                try {
                    details.put("beforeLength", beforeLength);
                    details.put("afterLength", afterLength);
                    details.put("compositionActive", composing);
                }
                catch (JSONException ignored) {}
                if (composing || beforeLength <= 0 || winHandler == null) {
                    traceImeEvent("delete", winHandler.nextImeRequestId(), details);
                    return;
                }
                int requestId = winHandler.imeDeleteBackward();
                try {
                    details.put("forwarded", requestId != 0);
                }
                catch (JSONException ignored) {}
                traceImeEvent("delete", requestId, details);
            }

            @Override
            public void onSendKeyEvent(KeyEvent event) {
                if (event == null) return;
                JSONObject details = new JSONObject();
                try {
                    details.put("action", event.getAction());
                    details.put("keyCode", event.getKeyCode());
                    details.put("compositionActive", view.isComposingText());
                }
                catch (JSONException ignored) {}
                traceImeEvent("ime-key", winHandler.nextImeRequestId(), details);
                if (event.getAction() == KeyEvent.ACTION_DOWN &&
                        event.getKeyCode() == KeyEvent.KEYCODE_ENTER && !view.isComposingText()) {
                    sendImeTextToBridge("", true);
                }
            }

            @Override
            public void onEditorAction(int actionCode) {
                if (xServer == null) return;
                JSONObject details = new JSONObject();
                try {
                    details.put("actionCode", actionCode);
                }
                catch (JSONException ignored) {}
                traceImeEvent("editor-action", winHandler.nextImeRequestId(), details);
                sendImeTextToBridge("", true);
            }
        });
    }

    private boolean ensureImeBridgeView() {
        if (this instanceof XrActivity || imeBridgeHost == null) return false;
        if (imeBridgeView != null) return true;

        ImeBridgeEditText view = new ImeBridgeEditText(this);
        view.setId(R.id.XRTextInput);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        lp.gravity = Gravity.TOP | Gravity.START;
        view.setLayoutParams(lp);
        imeBridgeHost.addView(view);
        imeBridgeView = view;
        configureImeBridgeView(view);
        return true;
    }

    private void forwardImeCommitToWoW(CharSequence text) {
        if (text == null || text.length() == 0 || xServer == null) return;
        final String value = text.toString();

        // Keep legacy behavior for non-WoW paths (notepad etc): send text as-is.
        if (!wowImeFocused) {
            sendImeTextToBridge(value, false);
            return;
        }

        final int len = value.length();
        int segmentStart = 0;

        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);
            if (ch != '\n' && ch != '\r') continue;

            if (i > segmentStart) {
                sendImeTextToBridge(value.substring(segmentStart, i), false);
            }
            sendImeTextToBridge("", true);
            segmentStart = i + 1;

            if (ch == '\r' && segmentStart < len && value.charAt(segmentStart) == '\n') {
                segmentStart++;
                i++;
            }
        }

        if (segmentStart < len) {
            sendImeTextToBridge(value.substring(segmentStart), false);
        }
    }

    private void sendImeTextToBridge(String text, boolean submit) {
        String safeText = text == null ? "" : text;
        if (safeText.isEmpty() && !submit) return;
        if (winHandler == null) return;
        long now = SystemClock.uptimeMillis();
        if (submit && now - lastImeSubmitUptimeMs < 250L) {
            traceImeEvent("submit-deduplicated", winHandler.nextImeRequestId(), null);
            return;
        }
        int requestId = winHandler.imeCommitText(safeText, submit);
        JSONObject details = new JSONObject();
        try {
            details.put("submit", submit);
            details.put("forwarded", requestId != 0);
            details.put("utf16Length", safeText.length());
            details.put("codePointCount", safeText.codePointCount(0, safeText.length()));
        }
        catch (JSONException ignored) {}
        traceImeEvent(submit ? "submit" : "commit-forward", requestId, details);
        if (submit && requestId != 0) lastImeSubmitUptimeMs = now;
    }

    private void traceImeTextShape(String event, int requestId, CharSequence text, boolean submit) {
        String value = text != null ? text.toString() : "";
        JSONObject details = new JSONObject();
        try {
            details.put("utf16Length", value.length());
            details.put("codePointCount", value.codePointCount(0, value.length()));
            details.put("submit", submit);
            details.put("textRecorded", false);
        }
        catch (JSONException ignored) {}
        traceImeEvent(event, requestId != 0 ? requestId : winHandler.nextImeRequestId(), details);
    }

    private void traceImeEvent(String event, int requestId, JSONObject details) {
        JSONObject record = new JSONObject();
        try {
            record.put("timestampMs", System.currentTimeMillis());
            record.put("uptimeMs", SystemClock.uptimeMillis());
            record.put("event", event != null ? event : "unknown");
            record.put("requestId", requestId);
            record.put("textRecorded", false);
            if (details != null) {
                Iterator<String> keys = details.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"event".equals(key) && !"requestId".equals(key)) record.put(key, details.opt(key));
                }
            }
        }
        catch (JSONException ignored) {}
        Log.i("ImeTrace", record.toString());
        if (boxSessionManager != null && boxSessionId != null && !boxSessionId.isEmpty()) {
            boxSessionManager.appendJsonLine(boxSessionId, "ime-trace.jsonl", record);
        }
    }

    private void queueWinHandlerLiteStart() {
        if (winHandler == null || winHandlerLiteLaunchQueued) return;
        Log.i(TAG_GUEST_DEBUG, "Queue winhandler-lite launch: " + WINHANDLER_LITE_GUEST_PATH);
        if (boxSessionId != null && !boxSessionId.isEmpty()) {
            boxSessionManager.markEvent(boxSessionId, "queue-winhandler-lite " + WINHANDLER_LITE_GUEST_PATH);
        }
        winHandler.exec(WINHANDLER_LITE_GUEST_PATH);
        winHandlerLiteLaunchQueued = true;
    }

    private boolean usesWinHandlerLitePrimaryLaunch() {
        return boxLaunchMode != null
                && !boxLaunchMode.isEmpty()
                && boxExecutableHostPath != null
                && !boxExecutableHostPath.isEmpty();
    }

    private void queueBoxTargetStartFallback() {
        if (winHandler == null || boxExecutableHostPath == null || boxExecutableHostPath.isEmpty()) return;
        if (!"1".equals(envVars.get("WINLATOR_QUEUE_TARGET_EXEC"))) return;

        String guestPath = BoxRuntime.hostPathToGuestWindowsPath(container, boxExecutableHostPath);
        if (guestPath == null || guestPath.isEmpty()) return;

        Log.i(TAG_GUEST_DEBUG, "Queue selected executable fallback: " + guestPath);
        if (boxSessionId != null && !boxSessionId.isEmpty()) {
            boxSessionManager.markEvent(boxSessionId, "queue-target-exec " + guestPath);
        }
        winHandler.exec(guestPath);
    }

    private void showSoftKeyboard(String reason) {
        JSONObject details = new JSONObject();
        try {
            details.put("reason", reason != null ? reason : "");
        }
        catch (JSONException ignored) {}
        traceImeEvent("keyboard-show", winHandler.nextImeRequestId(), details);
        if (this instanceof XrActivity) {
            AppUtils.showKeyboard(this);
            return;
        }

        if (!ensureImeBridgeView()) {
            AppUtils.showKeyboard(this);
            return;
        }

        if (imeBridgeView.getText() != null) {
            imeBridgeView.getText().clear();
        }
        imeBridgeView.setFocusable(true);
        imeBridgeView.setFocusableInTouchMode(true);
        imeBridgeView.requestFocus();
        InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null || !imm.showSoftInput(imeBridgeView, InputMethodManager.SHOW_IMPLICIT)) {
            AppUtils.showKeyboard(this);
        }
    }

    private void hideSoftKeyboard() {
        traceImeEvent("keyboard-hide", winHandler.nextImeRequestId(), null);
        forceCloseImeSession("hide_soft_keyboard_pre", false);
        destroyImeBridgeView("hide_soft_keyboard", false);
    }

    private void destroyImeBridgeView(String reason, boolean blockUntilFocusReset) {
        JSONObject details = new JSONObject();
        try {
            details.put("reason", reason != null ? reason : "");
            details.put("blockUntilFocusReset", blockUntilFocusReset);
        }
        catch (JSONException ignored) {}
        traceImeEvent("keyboard-destroy", winHandler.nextImeRequestId(), details);
        ImeBridgeEditText view = imeBridgeView;
        if (blockUntilFocusReset) {
            suppressImeUntilFocusReset = true;
            long now = SystemClock.uptimeMillis();
            // Fallback: if WoW/Lua doesn't send ime_focus=false, don't block auto-show forever.
            suppressImeAutoShowUntilMs = Math.max(suppressImeAutoShowUntilMs, now + 1200L);
        }

        forceCloseImeSession("destroy:" + reason, true);

        if (view == null) {
            return;
        }

        view.setListener(null);
        view.clearFocus();
        view.setEnabled(false);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setVisibility(View.GONE);

        ViewGroup parent = (ViewGroup)view.getParent();
        if (parent != null) parent.removeView(view);

        imeBridgeView = null;
        wowImeFocused = false;
    }

    private void forceCloseImeSession(String reason, boolean scheduleSecondPass) {
        InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        View bridge = imeBridgeView;
        if (imm != null) {
            if (bridge != null && bridge.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(bridge.getWindowToken(), 0);
            }
            if (drawerLayout != null && drawerLayout.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(drawerLayout.getWindowToken(), 0);
                imm.restartInput(drawerLayout);
            }
        }

        if (drawerLayout != null) {
            drawerLayout.setFocusableInTouchMode(true);
            drawerLayout.requestFocus();
        }
        if (xServerView != null) {
            xServerView.setFocusableInTouchMode(true);
            xServerView.requestFocus();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
        }

        if (scheduleSecondPass && drawerLayout != null) {
            drawerLayout.postDelayed(() -> forceCloseImeSession(reason + ":second_pass", false), 100L);
        }
    }

    private void setHostHudVisible(boolean visible) {
        if (xServerView == null) return;
        GLRenderer renderer = xServerView.getRenderer();

        if (!visible) {
            renderer.setOnFrameRendered(null);
            if (frameRating != null) frameRating.stopMonitoring();
            if (hudLayer != null) hudLayer.hide();
            return;
        }

        if (hudLayer == null) {
            FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
            hudLayer = new HUDLayer(this);
            frameRating = hudLayer.getFrameRating();
            FrameLayout.LayoutParams hudLayoutParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            hudLayer.setLayoutParams(hudLayoutParams);
            rootView.addView(hudLayer);
        }

        frameRating.setEngineLabel(resolveHudEngineLabel());
        hudLayer.show();
        hudLayer.bringToFront();
        // Measure actual host render FPS rather than X11 content updates so this
        // switch works uniformly for both DXVK and vkd3d-proton applications.
        renderer.setOnFrameRendered(frameRating::update);
        frameRating.startMonitoring();
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        String cacheId = graphicsDriver;
        if (graphicsDriver.equals("wrapper")) {
            cacheId += "-" + graphicsDriverConfig;
        }
        else if (graphicsDriver.equals("virgl")) {
            cacheId += "-"+DefaultVersion.VIRGL;
        }

        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = imageFs.getRootDir();
        File libDir = imageFs.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));
            File icdDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(icdDir);
            icdDir.mkdirs();
            container.putExtra("graphicsDriver", cacheId);
            container.saveData();
        }

        if (graphicsDriver.equals("wrapper")) {
            // Vanilla-aligned: Vulkan wrapper ICD + Adrenotools Turnip bundle.
            ensureVulkanWrapperInstalled(rootDir);
            envVars.put("VK_ICD_FILENAMES", rootDir.getPath() + "/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json");

            if (dxwrapper != null && dxwrapper.startsWith("dxvk")) {
                DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
                if (boxSessionId != null && !boxSessionId.isEmpty()) {
                    if (!envVars.has("DXVK_LOG_PATH")) envVars.put("DXVK_LOG_PATH", boxSessionManager.getSessionDir(boxSessionId).getAbsolutePath());
                    if (!envVars.has("DXVK_LOG_LEVEL")) envVars.put("DXVK_LOG_LEVEL", "info");
                }
                else {
                    boolean enableDxvkLog = preferences.getBoolean("enable_dxvk_log", false);
                    if (enableDxvkLog) {
                        if (!envVars.has("DXVK_LOG_PATH")) envVars.put("DXVK_LOG_PATH", "W:\\\\");
                        if (!envVars.has("DXVK_LOG_LEVEL")) envVars.put("DXVK_LOG_LEVEL", "info");
                    }
                }
            }
            if ("vkd3d".equals(dxwrapper) || "dxvk+vkd3d".equals(dxwrapper)) {
                envVars.put("VKD3D_FEATURE_LEVEL", "12_1");
            }

            ParsedConfig cfg = ParsedConfig.parseSemicolonList(graphicsDriverConfig);

            // Adrenotools driver bundle version (e.g. "turnip26.0.0").
            if (!envVars.has("ADRENOTOOLS_DRIVER_PATH") && !envVars.has("ADRENOTOOLS_DRIVER_NAME")) {
                String driverId = cfg.get("version", "turnip" + DefaultVersion.ADRENOTOOLS_TURNIP);
                new AdrenotoolsManager(this).setDriverById(envVars, imageFs, driverId);
            }

            // Vulkan version exposed by wrapper (vanilla uses 1.3.x).
            if (!envVars.has("WRAPPER_VK_VERSION")) {
                envVars.put("WRAPPER_VK_VERSION", computeWrapperVkVersion(cfg.get("vulkanVersion", "1.3")));
            }

            envVars.put("GALLIUM_DRIVER", "zink");
            if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) {
                envVars.put("MESA_VK_WSI_PRESENT_MODE", cfg.get("presentMode", "mailbox"));
            }

            if (!envVars.has("WRAPPER_EXTENSION_BLACKLIST")) {
                envVars.put("WRAPPER_EXTENSION_BLACKLIST", cfg.get("blacklistedExtensions", ""));
            }
            if (!envVars.has("WRAPPER_RESOURCE_TYPE")) {
                envVars.put("WRAPPER_RESOURCE_TYPE", cfg.get("resourceType", "auto"));
            }
            if (!envVars.has("WRAPPER_DISABLE_PRESENT_WAIT")) {
                envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", cfg.get("disablePresentWait", "0"));
            }

            // The wrapper already owns BCn format virtualization and decompression. Enabling the
            // legacy implicit BCn layer at the same time makes it advertise textureCompressionBC
            // to the wrapper, while BCN_COMPUTE_AUTO skips the layer's own Turnip decode path.
            // The resulting ownerless upload treats compressed blocks as RGBA pixels. Keep an
            // explicit container override possible, but default the wrapper stack to one owner.
            if (!envVars.has("ENABLE_BCN_COMPUTE") && !envVars.has("DISABLE_BCN_COMPUTE")) {
                envVars.put("DISABLE_BCN_COMPUTE", "1");
            }
            if (!envVars.has("BCN_COMPUTE_AUTO")) envVars.put("BCN_COMPUTE_AUTO", "1");
            if (!envVars.has("WRAPPER_EMULATE_BCN")) envVars.put("WRAPPER_EMULATE_BCN", "3");
            if (!envVars.has("WRAPPER_USE_BCN_CACHE")) envVars.put("WRAPPER_USE_BCN_CACHE", cfg.get("bcnEmulationCache", "0"));

            if (!envVars.has("WRAPPER_MAX_IMAGE_COUNT")) envVars.put("WRAPPER_MAX_IMAGE_COUNT", "0");

            if (boxSessionId != null && !boxSessionId.isEmpty()) {
                File sessionDir = boxSessionManager.getSessionDir(boxSessionId);
                sessionDir.mkdirs();
                if (!envVars.has("WRAPPER_LOG_FILE")) envVars.put("WRAPPER_LOG_FILE", boxSessionManager.getWrapperLogFile(boxSessionId).getAbsolutePath());
                if (!envVars.has("WRAPPER_LOG_LEVEL")) envVars.put("WRAPPER_LOG_LEVEL", preferences.getString("wrapper_log_level", "debug"));
            }
            else {
                boolean enableWrapperLog = preferences.getBoolean("enable_wrapper_log", false);
                if (enableWrapperLog) {
                    if (!envVars.has("WRAPPER_LOG_LEVEL")) envVars.put("WRAPPER_LOG_LEVEL", preferences.getString("wrapper_log_level", "debug"));
                }
            }

            // Turnip stability: prefer system memory path (Ludashi default is noconform,sysmem).
            String tuDebug = envVars.get("TU_DEBUG");
            if (tuDebug == null) tuDebug = "";
            if (tuDebug.isEmpty()) tuDebug = "noconform";
            if (!tuDebug.contains("sysmem")) tuDebug = tuDebug + ",sysmem";
            envVars.put("TU_DEBUG", tuDebug);

            boolean useDRI3 = preferences.getBoolean("use_dri3", true);
            if (!useDRI3) {
                envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
                if (!envVars.has("MESA_VK_WSI_DEBUG")) envVars.put("MESA_VK_WSI_DEBUG", "sw");
            }

            if (changed) {
                ensureVulkanWrapperInstalled(rootDir);
            }

            // Ludashi: on first boot, extract arm64ec Zink DLL overrides into the prefix (non-Mali).
            if (firstTimeBoot && wineInfo != null && wineInfo.isArm64EC() &&
                !GPUInformation.getRenderer(this).contains("Mali")) {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    this,
                    "graphics_driver/zink_dlls.tzst",
                    new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
                );
            }
        }
        else if (graphicsDriver.equals("virgl")) {
            envVars.put("GALLIUM_DRIVER", "virpipe");
            envVars.put("VIRGL_NO_READBACK", "true");
            envVars.put("VIRGL_SERVER_PATH", UnixSocketConfig.VIRGL_SERVER_PATH);
            envVars.put("MESA_EXTENSION_OVERRIDE", "-GL_EXT_vertex_array_bgra");
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.1");
            if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
        }
    }

    private static String computeWrapperVkVersion(String vulkanVersion) {
        if (vulkanVersion == null) return "1.3.335";
        String v = vulkanVersion.trim();
        // Vanilla's config stores "1.3" and runtime ends up using "1.3.335".
        if (v.equals("1.3")) return "1.3.335";
        if (v.equals("1.4")) return "1.4.335";
        // If an exact version is already provided, keep it.
        if (v.matches("^\\d+\\.\\d+\\.\\d+$")) return v;
        return "1.3.335";
    }

    private String resolveHudEngineLabel() {
        String renderBackend;
        if (dxwrapper == null || dxwrapper.isEmpty() || "wined3d".equals(dxwrapper)) {
            renderBackend = "WINED3D";
        }
        else if ("vkd3d".equals(dxwrapper)) {
            renderBackend = "VKD3D";
        }
        else if (dxwrapper.startsWith("dxvk")) {
            renderBackend = dxwrapper.contains("vkd3d") ? "DXVK/VKD3D" : "DXVK";
        }
        else {
            renderBackend = dxwrapper.toUpperCase();
        }

        String driverLabel;
        if ("wrapper".equals(graphicsDriver)) {
            ParsedConfig cfg = ParsedConfig.parseSemicolonList(graphicsDriverConfig);
            String version = cfg.get("version", "");
            driverLabel = version.toLowerCase().contains("turnip") ? "TURNIP" : "WRAPPER";
        }
        else if ("virgl".equals(graphicsDriver)) {
            driverLabel = "VIRGL";
        }
        else {
            driverLabel = graphicsDriver != null ? graphicsDriver.toUpperCase() : "";
        }

        return driverLabel.isEmpty() ? renderBackend : renderBackend + " / " + driverLabel;
    }

    private static final class ParsedConfig {
        private final java.util.HashMap<String, String> map = new java.util.HashMap<>();

        static ParsedConfig parseSemicolonList(String data) {
            ParsedConfig cfg = new ParsedConfig();
            if (data == null) return cfg;
            String s = data.trim();
            if (s.isEmpty()) return cfg;
            String[] parts = s.split(";");
            for (String part : parts) {
                if (part == null) continue;
                String p = part.trim();
                if (p.isEmpty()) continue;
                int idx = p.indexOf('=');
                if (idx <= 0) continue;
                String k = p.substring(0, idx).trim();
                String v = p.substring(idx + 1).trim();
                if (!k.isEmpty()) cfg.map.put(k, v);
            }
            return cfg;
        }

        String get(String key, String fallback) {
            String v = map.get(key);
            return v != null ? v : fallback;
        }
    }

    private void ensureTurnipIcdJson(File rootDir) {
        if (rootDir == null) return;
        File icdFile = new File(rootDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
        if (!icdFile.isFile()) return;

        File libFile = new File(rootDir, "usr/lib/libvulkan_freedreno.so");
        String expectedLibPath = libFile.getAbsolutePath();

        try {
            JSONObject json = new JSONObject(FileUtils.readString(icdFile));
            JSONObject icd = json.optJSONObject("ICD");
            if (icd == null) return;
            String current = icd.optString("library_path", "");
            if (expectedLibPath.equals(current)) return;
            icd.put("library_path", expectedLibPath);
            // Keep it pretty for on-device inspection.
            FileUtils.writeString(icdFile, json.toString(4));
        }
        catch (JSONException ignored) {}
    }

    private void ensureVulkanWrapperInstalled(File rootDir) {
        if (rootDir == null) return;

        File wrapperLib = new File(rootDir, "usr/lib/libvulkan_wrapper.so");
        File wrapperIcd = new File(rootDir, "usr/share/vulkan/icd.d/wrapper_icd.aarch64.json");
        File freedrenoLib = new File(rootDir, "usr/lib/libvulkan_freedreno.so");
        File glapiLib = new File(rootDir, "usr/lib/libglapi.so.0.0.0");
        File glLib = new File(rootDir, "usr/lib/libGL.so.1.5.0");
        File validationLayer = new File(rootDir, "usr/lib/libVkLayer_khronos_validation.so");
        File bcnLayer = new File(rootDir, "usr/lib/libbcn_layer.so");
        String boxGraphicsAsset = getBoxGraphicsWrapperAsset();

        // A Box profile owns its host graphics libraries as one immutable layer.  Repair
        // from that exact layer so a bionic imagefs can never fall through to the global
        // glibc extra-libs bundle after the driver cache removes freedreno on first use.
        if (boxGraphicsAsset != null && (!wrapperLib.isFile() || !wrapperIcd.isFile()
                || !freedrenoLib.isFile() || !glapiLib.isFile() || !glLib.isFile())) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, boxGraphicsAsset, rootDir);
        }
        else if (boxGraphicsAsset == null && (!wrapperLib.isFile() || !wrapperIcd.isFile())) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper.tzst", rootDir);
        }
        // A Box-managed graphics stack already contains its Vulkan layers. Only use the
        // legacy split asset as a repair path when those files are actually absent.
        if (!validationLayer.isFile() || !bcnLayer.isFile()) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers.tzst", rootDir);
        }

        // Ensure the Mesa driver + GL helper libs exist (used by wrapper + zink).
        if (boxGraphicsAsset == null && (!freedrenoLib.isFile() || !glapiLib.isFile() || !glLib.isFile())) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
        }

        // Keep freedreno ICD JSON consistent in case something still references it.
        ensureTurnipIcdJson(rootDir);

        // libvulkan_wrapper.so is an ICD, not a Vulkan loader: it exports the
        // vk_icd* entry points but not vkGetDeviceProcAddr.  Wine dlopens the
        // libvulkan.so.1 loader, which in turn loads the wrapper through the ICD
        // manifest.  Keep (or recover) the versioned loader aliases supplied by
        // the selected rootfs instead of ever pointing them at the ICD.
        ensureVulkanLoaderAliases(rootDir);
    }

    private String getBoxGraphicsWrapperAsset() {
        if (boxRuntime == null) return null;
        for (com.winlator.box.BoxSpec.Layer layer : boxRuntime.getSpec().layers) {
            if (!"graphics-wrapper-stack".equals(layer.type)) continue;
            String source = layer.source != null ? layer.source.trim() : "";
            if (source.startsWith("asset://")) return source.substring("asset://".length());
        }
        return null;
    }

    private void ensureVulkanLoaderAliases(File rootDir) {
        File libDir = new File(rootDir, "usr/lib");
        File sonameLink = new File(libDir, "libvulkan.so.1");
        try {
            if (!resolvesToFile(sonameLink)) {
                FileUtils.delete(sonameLink);
                File[] candidates = libDir.listFiles((dir, name) ->
                        name.startsWith("libvulkan.so.1.") && new File(dir, name).isFile());
                if (candidates != null && candidates.length > 0) {
                    java.util.Arrays.sort(candidates, (left, right) -> left.getName().compareTo(right.getName()));
                    FileUtils.symlink(candidates[candidates.length - 1].getName(), sonameLink.getPath());
                }
            }

            File unversionedLink = new File(libDir, "libvulkan.so");
            if (!resolvesToFile(unversionedLink) && resolvesToFile(sonameLink)) {
                FileUtils.delete(unversionedLink);
                FileUtils.symlink("libvulkan.so.1", unversionedLink.getPath());
            }
        }
        catch (Exception e) {
            Log.w("XServerDisplay", "Failed to restore Vulkan loader aliases", e);
        }
    }

    private boolean resolvesToFile(File path) {
        try {
            return path.exists() && path.getCanonicalFile().isFile();
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void showTouchpadHelpDialog() {
        boolean gestureModeEnabled = touchpadView != null && touchpadView.isTapDragMouseModeEnabled();
        ContentDialog dialog = new ContentDialog(this, gestureModeEnabled
                ? R.layout.touch_gesture_help_dialog
                : R.layout.touchpad_help_dialog);
        dialog.setTitle(gestureModeEnabled ? R.string.touch_gesture_help : R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (resumeGate.isWaitingForManualResume()) return true;
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (resumeGate.isWaitingForManualResume()) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_BUTTON_A
                            || keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
                requestManualResume();
            }
            return true;
        }
        observeTouchGestureModifier(event);
        boolean handledByInputControls = inputControlsView.onKeyEvent(event);
        boolean handledByWinHandler = !handledByInputControls && winHandler.onKeyEvent(event);
        boolean handledByKeyboard = !handledByInputControls && !handledByWinHandler && xServer.keyboard.onKeyEvent(event);
        boolean handledByControllers = handledByInputControls || handledByWinHandler || handledByKeyboard;
        boolean handledBySystem = !ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event);
        return handledByControllers || handledBySystem;
    }

    private void observeTouchGestureModifier(KeyEvent event) {
        if (touchpadView == null
                || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_L1
                || !ExternalController.isGameController(event.getDevice())) {
            return;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            touchpadView.setLeftShoulderModifierPressed(event.getDeviceId(), true);
        }
        else if (event.getAction() == KeyEvent.ACTION_UP) {
            touchpadView.setLeftShoulderModifierPressed(event.getDeviceId(), false);
        }
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private void generateWineprefix() {
        Intent intent = getIntent();

        final File rootDir = imageFs.getRootDir();
        final File installedWineDir = imageFs.getInstalledWineDir();
        wineInfo = intent.getParcelableExtra("wine_info");
        envVars.put("WINEARCH", wineInfo.isWin64() ? "win64" : "win32");
        imageFs.setWinePath(wineInfo.path);

        final File containerPatternDir = new File(installedWineDir, "/preinstall/container-pattern");
        if (containerPatternDir.isDirectory()) FileUtils.delete(containerPatternDir);
        containerPatternDir.mkdirs();

        File linkFile = new File(rootDir, ImageFs.HOME_PATH);
        linkFile.delete();
        FileUtils.symlink(".."+FileUtils.toRelativePath(rootDir.getPath(), containerPatternDir.getPath()), linkFile.getPath());

        GuestProgramLauncherComponent guestProgramLauncherComponent = environment.getComponent(GuestProgramLauncherComponent.class);
        String explorerExe = (wineInfo != null && wineInfo.isArm64EC()) ? " C:\\windows\\explorer.exe" : " explorer";
        guestProgramLauncherComponent.setGuestExecutable(wineInfo.getExecutable(this, false)+ explorerExe +" /desktop=shell,"+Container.DEFAULT_SCREEN_SIZE+" winecfg");

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        guestProgramLauncherComponent.setTerminationCallback((status) -> Executors.newSingleThreadExecutor().execute(() -> {
            if (status > 0) {
                AppUtils.showToast(this, R.string.unable_to_install_wine);
                FileUtils.delete(new File(installedWineDir, "/preinstall"));
                AppUtils.restartApplication(this);
                return;
            }

            preloaderDialog.showOnUiThread(R.string.finishing_installation);
            FileUtils.writeString(new File(rootDir, ImageFs.WINEPREFIX+"/.update-timestamp"), "disable\n");

            File userDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/users/xuser");
            File[] userFiles = userDir.listFiles();
            if (userFiles != null) {
                for (File userFile : userFiles) {
                    if (FileUtils.isSymlink(userFile)) {
                        String path = userFile.getPath();
                        userFile.delete();
                        (new File(path)).mkdirs();
                    }
                }
            }

            String suffix = wineInfo.fullVersion()+"-"+wineInfo.getArch();
            File containerPatternFile = new File(installedWineDir, "/preinstall/container-pattern-"+suffix+".tzst");
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(rootDir, ImageFs.WINEPREFIX), containerPatternFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);

            if (!containerPatternFile.renameTo(new File(installedWineDir, containerPatternFile.getName())) ||
                !(new File(wineInfo.path)).renameTo(new File(installedWineDir, wineInfo.identifier()))) {
                containerPatternFile.delete();
            }

            FileUtils.delete(new File(installedWineDir, "/preinstall"));

            preloaderDialog.closeOnUiThread();
            AppUtils.restartApplication(this, R.id.main_menu_settings);
        }));
    }

    private String getDXWrapperCacheId() {
        if (dxwrapper == null || dxwrapper.isEmpty()) return "wined3d";
        if (dxwrapper.startsWith("dxvk")) {
            String ver = dxwrapperConfig != null ? dxwrapperConfig.get("version") : "";
            if (ver == null || ver.isEmpty()) ver = DefaultVersion.DXVK;
            if (dxwrapper.equals("dxvk+vkd3d")) {
                return "dxvk-" + ver + ";vkd3d-" + DefaultVersion.VKD3D;
            }
            return "dxvk-" + ver;
        }
        if (dxwrapper.equals("vkd3d")) return "vkd3d-" + DefaultVersion.VKD3D;
        return dxwrapper;
    }

    private void extractDXWrapperFiles() {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll"};
        if (firstTimeBoot && dxwrapper != null && !dxwrapper.equals("vkd3d")) cloneOriginalDllFiles(dlls);

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        String mode = dxwrapper != null ? dxwrapper : "wined3d";
        boolean useDxvk = mode.equals("dxvk") || mode.equals("dxvk+vkd3d");
        boolean useVkd3d = mode.equals("vkd3d") || mode.equals("dxvk+vkd3d");

        if (mode.equals("wined3d")) {
            restoreOriginalDllFiles(dlls);
            return;
        }

        if (useDxvk) {
            String ver = dxwrapperConfig != null ? dxwrapperConfig.get("version") : "";
            if (ver == null || ver.isEmpty()) ver = DefaultVersion.DXVK;
            // DXVK does not ship D3D12. Keep Wine's original D3D12 implementation
            // only when the container did not explicitly request vkd3d-proton.
            if (!useVkd3d) restoreOriginalDllFiles("d3d12.dll", "d3d12core.dll");
            restoreOriginalDllFiles("ddraw.dll");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/dxvk-" + ver + ".tzst", windowsDir, onExtractFileListener);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
        }

        if (useVkd3d) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/vkd3d-" + DefaultVersion.VKD3D + ".tzst", windowsDir, onExtractFileListener);
        }
    }

    private void extractWinComponentFiles() {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            if (firstTimeBoot) {
                for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(wincomponent[0]);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }

                cloneOriginalDllFiles(dlls.toArray(new String[0]));
                dlls.clear();
            }

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File cacheDir = new File(rootDir, ImageFs.CACHE_PATH+"/original_dlls");
        if (cacheDir.isDirectory()) {
            File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
            String[] dirnames = cacheDir.list();
            int filesCopied = 0;

            for (String dll : dlls) {
                boolean success = false;
                for (String dirname : dirnames) {
                    File srcFile = new File(cacheDir, dirname+"/"+dll);
                    File dstFile = new File(windowsDir, dirname+"/"+dll);
                    if (FileUtils.copy(srcFile, dstFile)) success = true;
                }
                if (success) filesCopied++;
            }

            if (filesCopied == dlls.length) return;
        }

        if (containerManager == null) {
            cloneOriginalDllFiles(dlls);
            return;
        }

        containerManager.extractContainerPatternFile(container.getWineVersion(), container.getRootDir(), (file, size) -> {
            String path = file.getPath();
            if (path.contains("system32/") || path.contains("syswow64/")) {
                for (String dll : dlls) {
                    if (path.endsWith("system32/"+dll) || path.endsWith("syswow64/"+dll)) return file;
                }
            }
            return null;
        });

        cloneOriginalDllFiles(dlls);
    }

    private void cloneOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File cacheDir = new File(rootDir, ImageFs.CACHE_PATH+"/original_dlls");
        if (!cacheDir.isDirectory()) cacheDir.mkdirs();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        String[] dirnames = {"system32", "syswow64"};

        for (String dll : dlls) {
            for (String dirname : dirnames) {
                File dllFile = new File(windowsDir, dirname+"/"+dll);
                if (dllFile.isFile()) FileUtils.copy(dllFile, new File(cacheDir, dirname+"/"+dll));
            }
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        File rootDir = imageFs.getRootDir();
        File tempDir = new File(container.getRootDir(), ".wine/drive_c/windows/temp");
        FileUtils.clear(tempDir);

        String args = "";
        String guestPath = "";
        if ((boxExecutableGuestPath != null && !boxExecutableGuestPath.isEmpty()) ||
                (boxExecutableHostPath != null && !boxExecutableHostPath.isEmpty())) {
            guestPath = boxExecutableGuestPath != null && !boxExecutableGuestPath.isEmpty()
                    ? boxExecutableGuestPath
                    : BoxRuntime.hostPathToGuestWindowsPath(container, boxExecutableHostPath);
            String exeDir = boxWorkingDirectory != null && !boxWorkingDirectory.isEmpty()
                    ? boxWorkingDirectory : FileUtils.getDirname(guestPath);
            String filename = FileUtils.getName(guestPath);
            args += "/dir " + quoteWineCommandArg(exeDir) + " " + quoteWineCommandArg(filename);
            for (String arg : boxExecutableArgs) args += " " + quoteWineCommandArg(arg);
        }
        else if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\""+shortcut.path+"\""+execArgs;
            }
            else {
                String exeDir = FileUtils.getDirname(shortcut.path);
                String filename = FileUtils.getName(shortcut.path);
                int dotIndex, spaceIndex;
                if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                    execArgs = filename.substring(spaceIndex+1)+execArgs;
                    filename = filename.substring(0, spaceIndex);
                }
                args += "/dir "+exeDir.replace(" ", "\\ ")+" \""+filename+"\""+execArgs;
            }
        }
        else args += "\"wfm.exe\"";

        String command = (usesWinHandlerLitePrimaryLaunch() ? "winhandler-lite.exe " : "winhandler.exe ") + args;
        if (boxLaunchMode != null && !boxLaunchMode.isEmpty()) {
            Log.i(TAG_GUEST_DEBUG, "Wine start command: targetId=" + boxLaunchTargetId +
                    " hostPath=" + boxExecutableHostPath + " guestPath=" + guestPath + " command=" + command);
            if (boxSessionId != null && !boxSessionId.isEmpty()) {
                boxSessionManager.markEvent(boxSessionId, "wine-start-command " + command);
            }
        }
        return command;
    }

    private static String quoteWineCommandArg(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private void applyBoxDebugConfig() {
        if (boxDebugConfig == null || !boxDebugConfig.isEnabled()) return;

        for (String name : boxDebugConfig.envOverrides.keySet()) {
            if ("WINEDEBUG".equals(name)) continue;
            envVars.put(name, boxDebugConfig.envOverrides.get(name));
        }

        if (boxSessionId != null && !boxSessionId.isEmpty()) {
            boxSessionManager.writeDebugMeta(boxSessionId, boxDebugConfig.toJson());
            boxSessionManager.markEvent(boxSessionId, "debug-profile-applied profile=" + boxDebugConfig.profile + " focus=" + boxDebugConfig.focus);
        }
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public static JSONObject requestCurrentProcessSnapshot(long timeoutMs) {
        XServerDisplayActivity activity = currentInstance;
        if (activity == null) {
            JSONObject json = new JSONObject();
            try {
                json.put("winHandlerReady", false);
                json.put("stale", true);
                json.put("processes", new JSONArray());
                json.put("errorCode", "process_missing");
                json.put("error", "no active XServerDisplayActivity");
            }
            catch (JSONException ignored) {}
            return json;
        }
        return activity.winHandler.requestProcessSnapshot(timeoutMs);
    }

    public static boolean hasActiveSession() {
        return currentInstance != null;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    private void ensureGraphicsDriverX11() {
        // Force wine to use winex11.drv. Proton 11 otherwise defaults to winewayland.drv, which has
        // no Wayland display on Android and fails with nodrv_CreateWindow -> the desktop never starts.
        // The box install path never sets a Graphics driver (only Audio), so set it here, idempotently,
        // on every launch (cheap; survives pm clear since it re-applies on the next launch).
        File rootDir = imageFs.getRootDir();
        File userRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            registryEditor.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11");
        }
        catch (Exception e) {
            Log.w("XServerDisplay", "Failed to set Graphics=x11", e);
        }
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        // Align with Ludashi/bionic: apply common prefix patches (e.g. winhandler/wfm) into the
        // active container via the /home/xuser symlink in imagefs.
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir, onExtractFileListener);
        // Vanilla: install native input helper (libfakeinput.so) into imagefs so it can be LD_PRELOAD'ed.
        ensureFakeInputInstalled(rootDir);
        ensureWinHandlerLiteInstalled(rootDir);
        ensureFrontMageWowLauncherInstalled(rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void ensureFakeInputInstalled(File rootDir) {
        if (rootDir == null) return;

        try {
            String nativeLibDir = getApplicationInfo() != null ? getApplicationInfo().nativeLibraryDir : null;
            if (nativeLibDir == null || nativeLibDir.isEmpty()) return;

            File src = new File(nativeLibDir, "libfakeinput.so");
            if (!src.isFile()) return;

            File dstDir = new File(rootDir, "usr/lib");
            if (!dstDir.isDirectory()) dstDir.mkdirs();

            File dst = new File(dstDir, "libfakeinput.so");
            if (!dst.isFile() || dst.length() != src.length()) {
                FileUtils.copy(src, dst);
                FileUtils.chmod(dst, 0771);
            }

            // Vanilla sets FAKE_EVDEV_DIR to $ROOT/dev/input; ensure it exists.
            File devInput = new File(rootDir, "dev/input");
            if (!devInput.isDirectory()) devInput.mkdirs();
        }
        catch (Exception ignored) {}
    }

    private void ensureWinHandlerLiteInstalled(File rootDir) {
        if (rootDir == null) return;
        try {
            File dst = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/winhandler-lite.exe");
            FileUtils.copy(this, WINHANDLER_LITE_ASSET, dst);
            FileUtils.chmod(dst, 0771);
        }
        catch (Exception e) {
            Log.w("ImeBridge", "Failed to install winhandler-lite", e);
        }
    }

    private void ensureImeContractProbesInstalled(File rootDir) {
        if (rootDir == null) return;
        try {
            File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
            File arm64 = new File(windowsDir, "ime-contract-probe-arm64.exe");
            File amd64 = new File(windowsDir, "ime-contract-probe-amd64.exe");
            FileUtils.copy(this, IME_PROBE_ARM64_ASSET, arm64);
            FileUtils.copy(this, IME_PROBE_AMD64_ASSET, amd64);
            FileUtils.chmod(arm64, 0771);
            FileUtils.chmod(amd64, 0771);
        }
        catch (Exception e) {
            Log.w("ImeBridge", "Failed to install IME contract probes", e);
        }
    }

    private void ensureFrontMageWowLauncherInstalled(File rootDir) {
        if (rootDir == null) return;
        try {
            File dst = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/frontmage-wow-launcher.exe");
            FileUtils.copy(this, FRONTMAGE_WOW_LAUNCHER_ASSET, dst);
            FileUtils.chmod(dst, 0771);
        }
        catch (Exception e) {
            Log.w(TAG_GUEST_DEBUG, "Failed to install frontmage-wow-launcher", e);
        }
    }

    public static boolean requestDebugRestart() {
        XServerDisplayActivity activity = currentInstance;
        if (activity == null) return false;
        activity.runOnUiThread(activity::restartFromDebug);
        return true;
    }

    public static boolean requestDebugStop() {
        XServerDisplayActivity activity = currentInstance;
        if (activity == null) return false;
        activity.runOnUiThread(activity::stopFromDebug);
        return true;
    }

    private void restartFromDebug() {
        synchronized (this) {
            if (restartRequested) return;
            restartRequested = true;
        }
        if (!shutdownRuntimeOnce()) {
            markBoxSessionEvent("container-restart-incomplete wine-processes-remain");
            synchronized (this) {
                restartRequested = false;
                shutdownCompleted = false;
            }
            return;
        }
        markBoxSessionFinishedIfRunning(-1);
        finish();
    }

    private void applyRootfsUtilsPatches() {
        File rootDir = imageFs.getRootDir();

        String[][] patches = new String[][] {
                {"rootfs-utils/aarch64/usr/bin/env", "/usr/bin/env"},
                {"rootfs-utils/aarch64/usr/bin/cat", "/usr/bin/cat"},
                {"rootfs-utils/aarch64/bin/cat", "/bin/cat"},
                {"rootfs-utils/aarch64/usr/bin/lscpu", "/usr/bin/lscpu"},
                // Bionic-compatible ALSA PCM plugin for the Android ALSA server.
                {"rootfs-utils/aarch64/usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so", "/usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so"}
        };

        for (String[] patch : patches) {
            String assetPath = patch[0];
            File dstFile = new File(rootDir, patch[1]);

            // Remove broken symlinks (busybox 32-bit) before copying the static replacement.
            FileUtils.delete(dstFile);
            FileUtils.copy(this, assetPath, dstFile);
            FileUtils.chmod(dstFile, 0771);

            Log.i(TAG_GUEST_DEBUG, "Patched rootfs utility: " + dstFile.getAbsolutePath());
        }
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

}
