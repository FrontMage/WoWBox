package com.winlator.renderer;

import com.winlator.widget.XServerRendererView;
import org.json.JSONObject;

/** Shared window/input surface; GLES and Vulkan keep their own rendering threads. */
public interface XServerRenderer {
    XServerRendererView getRendererView();
    boolean isFullscreen();
    void toggleFullscreen();
    void setCursorVisible(boolean visible);
    boolean isCursorVisible();
    void setForceFullscreenWMClass(String wmClass);
    void setUnviewableWMClasses(String... classes);
    void setMagnifierZoom(float zoom);
    float getMagnifierZoom();
    void setScreenOffsetYRelativeToCursor(boolean enabled);
    void setOnFrameRendered(Runnable callback);
    void runAfterNextFrame(Runnable callback);
    void cancelAfterNextFrame();
    JSONObject buildDisplayStatus();
}
