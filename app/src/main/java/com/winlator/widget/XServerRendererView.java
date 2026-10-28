package com.winlator.widget;

import android.content.Context;
import android.view.View;
import com.winlator.renderer.XServerRenderer;

public interface XServerRendererView {
    Context getContext();
    View asView();
    XServerRenderer getRenderer();
    void queueEvent(Runnable action);
    void requestRender();
    void onResume();
    void onPause();
}
