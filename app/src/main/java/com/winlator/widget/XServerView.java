package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final Handler cursorHandler = new Handler(Looper.getMainLooper());
    private final Runnable cursorRenderCallback = this::requestRender;
    private volatile boolean cursorAnimationsVisible = true;
    private volatile boolean cursorAnimationsResumed = true;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void scheduleCursorRender(long delayMs) {
        if (!cursorAnimationsVisible || !cursorAnimationsResumed) return;
        cursorHandler.removeCallbacks(cursorRenderCallback);
        cursorHandler.postDelayed(cursorRenderCallback, Math.max(1L, delayMs));
    }

    public void cancelCursorRender() {
        cursorHandler.removeCallbacks(cursorRenderCallback);
    }

    public void setCursorAnimationsVisible(boolean visible) {
        cursorAnimationsVisible = visible;
        if (!visible) cancelCursorRender();
    }

    @Override
    public void onPause() {
        cursorAnimationsResumed = false;
        cancelCursorRender();
        renderer.resetCursorAnimation();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        cursorAnimationsResumed = true;
        renderer.resetCursorAnimation();
        requestRender();
    }

    @Override
    protected void onDetachedFromWindow() {
        cursorAnimationsResumed = false;
        cancelCursorRender();
        super.onDetachedFromWindow();
    }
}
