package com.winlator.renderer;

// Compositor and JNI integration derived from GameNative 205cbf8ddc06f855a1b85ba57c78c4be9a6930aa.
// The full upstream native compositor is retained; XR and SurfaceControl scanout are outside this ARM trial.
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import com.winlator.R;
import com.winlator.widget.VulkanXServerView;
import com.winlator.widget.XServerRendererView;
import com.winlator.xserver.*;
import org.json.JSONObject;
import org.json.JSONException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class VulkanRenderer implements WindowManager.OnWindowModificationListener,
        Pointer.OnPointerMotionListener, XServerRenderer {
    private static final String TAG = "BoxVulkanRenderer";
    public final VulkanXServerView xServerView;
    private final XServer xServer;
    private final Object lock = new Object();
    private long nativeHandle;
    private volatile boolean initComplete;
    private volatile String state = "initializing";
    private volatile String error = "";
    private volatile boolean destroyed;
    private volatile boolean observing = true;
    private volatile CountDownLatch initialized = new CountDownLatch(1);
    private long surfaceGeneration;
    private boolean surfaceActive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean observerQueued = new AtomicBoolean();
    private long observedFrames;
    private long ahbCopies;
    private long cpuUpdates;
    private long afterFrameBaseline;
    private Runnable onFrameRendered;
    private Runnable onNextFrame;
    private Cursor lastCursor;
    private StaticCursor lastCursorFrame;
    private Drawable lastCursorDrawable;
    private boolean cursorStateSent;
    private boolean lastCursorVisibility;
    private long cursorEpoch;
    public final ViewTransformation viewTransformation = new ViewTransformation();
    public int surfaceWidth, surfaceHeight;
    private boolean fullscreen;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor;
    private String[] unviewableWMClasses;
    private String forceFullscreenWMClass;
    private boolean cursorVisible;
    private final Drawable rootCursorDrawable;
    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private static final AtomicLong ID_GEN = new AtomicLong(1);
    private final WeakHashMap<Drawable, Long> drawableIds = new WeakHashMap<>();
    private final AtomicBoolean scenePending = new AtomicBoolean();
    private final boolean flatPresentationEnabled = true;

    public VulkanRenderer(VulkanXServerView view, XServer server) {
        xServerView = view; xServer = server;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        try { System.loadLibrary("vulkan_renderer"); }
        catch (LinkageError failure) { fail("native library unavailable: " + failure); }
    }
    private Drawable createRootCursorDrawable() {
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(xServerView.getContext().getResources(), R.drawable.cursor, opts);
        return Drawable.fromBitmap(bitmap);
    }
    private native long nativeInit(Surface surface, int screenWidth, int screenHeight, String driverPath, String libraryName, String nativeLibDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeUpdateWindowContent(long handle, long id, java.nio.ByteBuffer pixels,
        short width, short height, short stride, int x, int y);
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int x, int y);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);
    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int x, int y, int w, int h, int fenceFd);
    private native void nativeScanoutSetCursorImage(long handle, java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native void nativeSetScanoutWindow(long handle, android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetEffect(long handle, int effectId, float sharpness,
        int effectMask, float brightness, float contrast, float gamma);
    private native long nativeEnableXrTarget(long handle);
    private native void nativeDisableXrTarget(long handle);
    private native long nativeGetXrTargetExtent(long handle);

    private long did(Drawable d) {
        synchronized (lock) { return drawableIds.computeIfAbsent(d, k -> ID_GEN.getAndIncrement()); }
    }

    public void queueSceneUpdate() {
        if (!flatPresentationEnabled) return;
        if (scenePending.compareAndSet(false, true)) {
            xServerView.queueEvent(() -> {
                scenePending.set(false);
                updateScene();
            });
        }
    }

    /** Re-express the legacy GL viewport and cursor-anchored zoom in native logical coordinates. */
    static float[] computeGlEquivalentTransform(int screenW, int screenH, int surfaceW, int surfaceH,
            int fitX, int fitY, int fitW, int fitH, boolean fullscreen,
            boolean relativeY, float requestedZoom, int pointerX, int pointerY) {
        float zoom = relativeY ? 1.0f : requestedZoom;
        float panX = 0.0f, panY = 0.0f;
        if (zoom != 1.0f) {
            panX = Math.max(0.0f, Math.min(pointerX * zoom - screenW * 0.5f,
                    screenW * Math.abs(1.0f - zoom)));
        }
        if (relativeY || zoom != 1.0f) {
            float range = zoom != 1.0f ? Math.abs(1.0f - zoom) : 0.5f;
            float anchor = screenH * (relativeY ? 0.25f : 0.5f);
            panY = Math.max(0.0f, Math.min(pointerY * zoom - anchor, screenH * range));
        }
        float viewportX = fullscreen ? 0.0f : fitX;
        float viewportY = fullscreen ? 0.0f : fitY;
        float ratioX = (float)(fullscreen ? surfaceW : fitW) / surfaceW;
        float ratioY = (float)(fullscreen ? surfaceH : fitH) / surfaceH;
        return new float[] {
                viewportX * screenW / surfaceW - ratioX * panX,
                viewportY * screenH / surfaceH - ratioY * panY,
                ratioX * zoom, ratioY * zoom };
    }

    private void updateTransform() {
        if (nativeHandle == 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return;
        float[] transform = computeGlEquivalentTransform(xServer.screenInfo.width, xServer.screenInfo.height,
                surfaceWidth, surfaceHeight, viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                viewTransformation.viewWidth, viewTransformation.viewHeight, fullscreen,
                screenOffsetYRelativeToCursor, magnifierZoom, xServer.pointer.getX(), xServer.pointer.getY());
        nativeSetTransform(nativeHandle, transform[0], transform[1], transform[2], transform[3]);
    }

    public void updateScene() {
        if (!flatPresentationEnabled) return;
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
        }
        synchronized (lock) {
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = forceFullscreenWMClass == null || window.getClassName().contains(forceFullscreenWMClass);
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false; break;
                    }
                }
            }
            if (viewable) list.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren())
            collectWindows(list, child, child.getX() + x, child.getY() + y);
    }

    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;

        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null && rw.content.width >= screenW && rw.content.height >= screenH) {
                start = i; break;
            }
        }

        
        if (fullscreen) {
            int n = list.size() - start;
            if (n <= 0) { nativeSetRenderList(nativeHandle, new long[0], new int[0], new int[0], 0); return; }
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                RenderableWindow rw = list.get(start + i);
                ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
    }

    public void onDestroyWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    public void onMapWindow(Window window) {
        if (flatPresentationEnabled) queueSceneUpdate();
    }

    public void onUnmapWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    public void onChangeWindowZOrder(Window window) {
        if (flatPresentationEnabled) queueSceneUpdate();
    }

    public void onUpdateWindowGeometry(Window window, boolean resized) {
        if (flatPresentationEnabled) queueSceneUpdate();
    }
    private native long nativeGetPresentedFrameCount(long handle);
    private native boolean nativeUpdateWindowContentAHBAndWait(long handle, long id, long ahbPtr,
            short width, short height, int x, int y);

    public void onSurfaceCreated(Surface surface) {
        if (destroyed || !error.isEmpty()) return;
        final long generation;
        final CountDownLatch ready;
        synchronized (lock) {
            generation = ++surfaceGeneration;
            surfaceActive = true; initComplete = false; state = "initializing";
            initialized = ready = new CountDownLatch(1);
        }
        xServerView.queueEvent(() -> {
            try {
                synchronized (lock) {
                    if (destroyed || generation != surfaceGeneration || !surfaceActive || !surface.isValid()) return;
                    if (nativeHandle != 0 && !nativeReattachSurface(nativeHandle, surface)) {
                        nativeDestroy(nativeHandle); nativeHandle = 0;
                    }
                    if (nativeHandle == 0) {
                        nativeHandle = nativeInit(surface, xServer.screenInfo.width,
                                xServer.screenInfo.height, null, null, null);
                        observedFrames = 0; cursorStateSent = false; lastCursorDrawable = null;
                    }
                    if (nativeHandle == 0) { fail("nativeInit returned 0"); return; }
                    // Preserve the existing linear sampling and unmodified image/color output.
                    nativeSetFilterMode(nativeHandle, 0);
                    nativeSetSwapRB(nativeHandle, false);
                    nativeSetPresentMode(nativeHandle, 2);
                    nativeSetVerboseLog(nativeHandle, false);
                    updateTransform();
                    updateCursorLocked();
                    initComplete = true; state = "ready";
                    Log.i(TAG, "selected=vulkan active=vulkan device=system Vulkan initialization=ready");
                    nativeDumpRendererInfo(nativeHandle);
                }
            } catch (RuntimeException | LinkageError failure) { fail("initialization failed: " + failure); }
            finally { ready.countDown(); }
            queueSceneUpdate(); resumeObservation();
        });
    }
    public void onSurfaceChanged(int width, int height) {
        synchronized (lock) {
            surfaceWidth = width; surfaceHeight = height;
            viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
            if (nativeHandle != 0 && surfaceActive) { nativeResize(nativeHandle, width, height); updateTransform(); }
        }
        queueSceneUpdate();
    }
    public void onSurfaceDestroyed() {
        pauseObservation();
        synchronized (lock) {
            surfaceGeneration++; surfaceActive = false; initComplete = false;
            initialized.countDown();
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
            if (!destroyed && error.isEmpty()) state = "surface-detached";
        }
    }
    public void destroy() {
        destroyed = true; pauseObservation(); initialized.countDown();
        synchronized (lock) {
            surfaceGeneration++; surfaceActive = false; initComplete = false; onNextFrame = null;
            if (nativeHandle != 0) { nativeDestroy(nativeHandle); nativeHandle = 0; }
            state = "destroyed";
        }
        xServer.windowManager.removeOnWindowModificationListener(this);
        xServer.pointer.removeOnPointerMotionListener(this);
    }
    private void fail(String reason) {
        error = reason; state = "failed"; initComplete = false;
        initialized.countDown();
        Log.e(TAG, "selected=vulkan active=failed " + reason);
        main.post(() -> Toast.makeText(xServerView.getContext(), "Vulkan display failed: " + reason,
                Toast.LENGTH_LONG).show());
    }
    private boolean awaitReady() {
        if (!initComplete && error.isEmpty() && !destroyed) {
            try { initialized.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return initComplete && error.isEmpty() && !destroyed;
    }
    /** Returns only after the source pixels have been consumed into owned native storage. */
    public boolean onUpdateWindowContentDirect(Window window, Drawable source, short xOff, short yOff) {
        if (source == null || !awaitReady()) return false;
        synchronized (lock) {
            if (nativeHandle == 0 || !initComplete) return false;
            synchronized (source.renderLock) {
                long id = did(window.getContent());
                int x = window.getRootX() + xOff, y = window.getRootY() + yOff;
                try {
                    Texture texture = source.getTexture();
                    if (texture instanceof GPUImage) {
                        long ahb = ((GPUImage)texture).getHardwareBufferPtr();
                        if (ahb == 0 || !nativeUpdateWindowContentAHBAndWait(nativeHandle, id, ahb,
                                source.width, source.height, x, y)) {
                            fail("AHB GPU copy did not complete; source remains owned by producer");
                            return false;
                        }
                        if (++ahbCopies == 1) Log.i(TAG, "source=AHB consumption=GPU-copy-fence-complete CPU-copy=none");
                    } else {
                        ByteBuffer pixels = source.getData();
                        if (pixels == null || source.height <= 0 || pixels.capacity() % (source.height * 4) != 0) return false;
                        short stride = (short)(pixels.capacity() / (source.height * 4));
                        nativeUpdateWindowContent(nativeHandle, id, pixels, source.width, source.height, stride, x, y);
                        cpuUpdates++;
                    }
                } catch (RuntimeException | LinkageError failure) {
                    fail("window update failed: " + failure); return false;
                }
            }
        }
        queueSceneUpdate();
        return true;
    }
    @Override public void onUpdateWindowContent(Window window) {
        if (window.attributes.isMapped()) onUpdateWindowContentDirect(window, window.getContent(), (short)0, (short)0);
    }
    private void updateCursorLocked() {
        if (nativeHandle == 0) return;
        Cursor cursor = xServer.inputDeviceManager.getPointWindow() != null
                ? xServer.inputDeviceManager.getPointWindow().attributes.getCursor() : null;
        if (cursor != lastCursor) { lastCursor = cursor; cursorEpoch = SystemClock.uptimeMillis(); lastCursorFrame = null; }
        StaticCursor frame = cursor != null ? cursor.resolveFrame(SystemClock.uptimeMillis() - cursorEpoch).cursor : null;
        boolean visible = cursorVisible && (frame == null || frame.isVisible());
        if (!cursorStateSent || visible != lastCursorVisibility) {
            nativeSetCursorVisible(nativeHandle, visible);
            cursorStateSent = true; lastCursorVisibility = visible;
        }
        Drawable selectedImage = frame != null ? frame.cursorImage : rootCursorDrawable;
        if (visible && (frame != lastCursorFrame || selectedImage != lastCursorDrawable)) {
            Drawable image = frame != null ? frame.cursorImage : rootCursorDrawable;
            synchronized (image.renderLock) {
                nativeUpdateCursorImage(nativeHandle, image.getData(), image.width, image.height,
                        (short)(frame != null ? frame.hotSpotX : 0), (short)(frame != null ? frame.hotSpotY : 0));
            }
            lastCursorFrame = frame; lastCursorDrawable = selectedImage;
        }
    }
    @Override public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0 || !initComplete) return;
            nativeSetPointerPos(nativeHandle, x, y); updateCursorLocked();
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }
    @Override public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        synchronized (lock) { if (mask.isSet(WindowAttributes.FLAG_CURSOR)) { lastCursorFrame = null; updateCursorLocked(); } }
    }
    private final Runnable observeFrames = new Runnable() {
        @Override public void run() {
            if (!observing || destroyed) return;
            if (observerQueued.compareAndSet(false, true)) xServerView.queueEvent(() -> {
                try { pollFrames(); } finally { observerQueued.set(false); }
            });
            main.postDelayed(this, 16);
        }
    };
    private void pollFrames() {
        Runnable next = null, each = null; long delta = 0;
        synchronized (lock) {
            if (nativeHandle == 0 || !initComplete) return;
            long frames = nativeGetPresentedFrameCount(nativeHandle);
            delta = Math.max(0, frames - observedFrames);
            if (delta > 0) {
                if (observedFrames == 0) Log.i(TAG, "selected=vulkan active=vulkan actualPresentFrames=" + frames);
                observedFrames = frames; each = onFrameRendered;
                if (onNextFrame != null && frames > afterFrameBaseline) { next = onNextFrame; onNextFrame = null; }
            }
            if (cursorVisible && lastCursor != null && lastCursor.isAnimated()) updateCursorLocked();
        }
        if (each != null) for (long i = 0; i < Math.min(delta, 10000); i++) each.run();
        if (next != null) main.post(next);
    }
    public void resumeObservation() { observing = true; main.removeCallbacks(observeFrames); main.post(observeFrames); }
    public void pauseObservation() { observing = false; main.removeCallbacks(observeFrames); }
    @Override public void runAfterNextFrame(Runnable action) {
        synchronized (lock) {
            onNextFrame = action;
            afterFrameBaseline = nativeHandle != 0 ? nativeGetPresentedFrameCount(nativeHandle) : 0;
        }
        xServerView.requestRender();
    }
    @Override public void cancelAfterNextFrame() { synchronized (lock) { onNextFrame = null; } }
    @Override public void setOnFrameRendered(Runnable callback) { synchronized (lock) { onFrameRendered = callback; } }
    @Override public XServerRendererView getRendererView() { return xServerView; }
    @Override public boolean isFullscreen() { return fullscreen; }
    @Override public void toggleFullscreen() { synchronized (lock) { fullscreen = !fullscreen; updateTransform(); } queueSceneUpdate(); }
    @Override public void setCursorVisible(boolean visible) { synchronized (lock) { cursorVisible = visible; updateCursorLocked(); } }
    @Override public boolean isCursorVisible() { return cursorVisible; }
    @Override public void setForceFullscreenWMClass(String wmClass) { forceFullscreenWMClass = wmClass; queueSceneUpdate(); }
    @Override public void setUnviewableWMClasses(String... classes) { unviewableWMClasses = classes; queueSceneUpdate(); }
    @Override public void setMagnifierZoom(float zoom) { synchronized (lock) { magnifierZoom = zoom; updateTransform(); } queueSceneUpdate(); }
    @Override public float getMagnifierZoom() { return magnifierZoom; }
    @Override public void setScreenOffsetYRelativeToCursor(boolean enabled) { synchronized (lock) { screenOffsetYRelativeToCursor = enabled; updateTransform(); } queueSceneUpdate(); }
    @Override public JSONObject buildDisplayStatus() {
        JSONObject json = new JSONObject();
        synchronized (lock) {
            try {
                json.put("active", state.equals("ready") ? "vulkan" : state);
                json.put("backend", "system Vulkan"); json.put("state", state); json.put("error", error);
                json.put("actualPresentFrames", nativeHandle != 0 ? nativeGetPresentedFrameCount(nativeHandle) : 0);
                json.put("ahbGpuCopies", ahbCopies); json.put("cpuWindowUpdates", cpuUpdates);
            } catch (JSONException ignored) {}
        }
        return json;
    }
    private static class RenderableWindow {
        final Drawable content; final int rootX, rootY;
        RenderableWindow(Drawable content, int x, int y) { this.content=content; rootX=x; rootY=y; }
    }
}
