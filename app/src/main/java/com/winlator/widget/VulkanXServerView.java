package com.winlator.widget;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.xserver.XServer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** GameNative 205cbf8d SurfaceView compositor integration; no EGL context is created. */
public final class VulkanXServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private final VulkanRenderer renderer;
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> new Thread(r, "BoxVulkanScene"));
    public VulkanXServerView(Context context, XServer server) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new VulkanRenderer(this, server);
        getHolder().addCallback(this);
    }
    @Override public View asView() { return this; }
    @Override public VulkanRenderer getRenderer() { return renderer; }
    @Override public void queueEvent(Runnable action) {
        try { events.execute(action); } catch (RejectedExecutionException ignored) { /* View is destroyed. */ }
    }
    @Override public void requestRender() { renderer.queueSceneUpdate(); }
    @Override public void onResume() { renderer.resumeObservation(); requestRender(); }
    @Override public void onPause() { renderer.pauseObservation(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { renderer.onSurfaceCreated(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { renderer.onSurfaceChanged(width, height); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { renderer.onSurfaceDestroyed(); }
    @Override protected void onDetachedFromWindow() {
        renderer.destroy();
        events.shutdownNow();
        super.onDetachedFromWindow();
    }
}
