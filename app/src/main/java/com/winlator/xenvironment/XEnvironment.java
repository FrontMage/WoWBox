package com.winlator.xenvironment;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.WineSessionProcessController;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final ImageFs imageFs;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return context;
    }

    public ImageFs getImageFs() {
        return imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
    }

    public void stopEnvironmentComponents() {
        stopEnvironmentComponents(null);
    }

    public void stopEnvironmentComponents(Class<? extends EnvironmentComponent> excludedClass) {
        for (int i = components.size() - 1; i >= 0; i--) {
            EnvironmentComponent component = components.get(i);
            if (excludedClass != null && excludedClass.isInstance(component)) continue;
            component.stop();
        }
    }

    public WineSessionProcessController.PauseResult onPause() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        return guestProgramLauncherComponent != null
                ? guestProgramLauncherComponent.suspendProcess()
                : null;
    }

    public int onResume() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        return guestProgramLauncherComponent != null
                ? guestProgramLauncherComponent.resumeProcess()
                : 0;
    }

    public boolean isWineSessionPaused() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        return guestProgramLauncherComponent != null
                && guestProgramLauncherComponent.isWineSessionPaused();
    }
}
