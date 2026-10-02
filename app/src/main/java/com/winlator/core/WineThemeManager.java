package com.winlator.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;

import com.winlator.R;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xserver.ScreenInfo;

import java.io.File;

public abstract class WineThemeManager {
    public enum Theme {LIGHT, DARK}
    public enum BackgroundType {IMAGE, COLOR}
    public static final String DEFAULT_DESKTOP_THEME = Theme.DARK+","+BackgroundType.IMAGE+",#0B1118";

    public static class ThemeInfo {
        public final Theme theme;
        public final BackgroundType backgroundType;
        public final int backgroundColor;

        public ThemeInfo(String value) {
            String[] values = value.split(",");
            theme = Theme.valueOf(values[0]);
            if (values.length < 3) {
                backgroundColor = Color.parseColor(values[1]);
                backgroundType = BackgroundType.IMAGE;
            }
            else {
                backgroundType = BackgroundType.valueOf(values[1]);
                backgroundColor = Color.parseColor(values[2]);
            }
        }
    }

    public static void apply(Context context, ThemeInfo themeInfo, ScreenInfo screenInfo) {
        File rootDir = ImageFs.find(context).getRootDir();
        File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
        String background = Color.red(themeInfo.backgroundColor)+" "+Color.green(themeInfo.backgroundColor)+" "+Color.blue(themeInfo.backgroundColor);

        if (themeInfo.backgroundType == BackgroundType.IMAGE) createWallpaperBMPFile(context, screenInfo);

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (themeInfo.backgroundType == BackgroundType.IMAGE) {
                registryEditor.setStringValue("Control Panel\\Desktop", "Wallpaper", ImageFs.CACHE_PATH+"/wallpaper.bmp");
            }
            else registryEditor.removeValue("Control Panel\\Desktop", "Wallpaper");

            if (themeInfo.theme == Theme.LIGHT) {
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "38 59 77");
                registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "224 224 224");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "38 59 77");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "11 17 24");
                registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "200 200 200");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Menu", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Window", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "0 0 0");
            }
            else if (themeInfo.theme == Theme.DARK) {
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "51 69 86");
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "38 59 77");
                registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "51 69 86");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "83 107 128");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "242 245 247");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "38 59 77");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "170 182 194");
                registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "11 17 24");
                registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "51 69 86");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "170 182 194");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "242 245 247");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "Menu", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "28 43 57");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "214 168 75");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "242 245 247");
                registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "28 43 57");
                registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "242 245 247");
                registryEditor.setStringValue("Control Panel\\Colors", "Window", "20 33 45");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "11 17 24");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "242 245 247");
            }
        }
    }

    private static void createWallpaperBMPFile(Context context, ScreenInfo screenInfo) {
        final int outputHeight = 480;
        int outputWidth = (int)Math.ceil(((float)outputHeight / screenInfo.height) * screenInfo.width);

        Bitmap outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Canvas canvas = new Canvas(outputBitmap);

        File userWallpaperFile = getUserWallpaperFile(context);
        if (userWallpaperFile.isFile()) {
            Bitmap image = BitmapFactory.decodeFile(userWallpaperFile.getPath());
            Rect srcRect = new Rect(0, 0, image.getWidth(), image.getHeight());
            Rect dstRect = new Rect(0, 0, outputWidth, outputHeight);
            canvas.drawBitmap(image, srcRect, dstRect, paint);
        }
        else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inTargetDensity = DisplayMetrics.DENSITY_HIGH;
            Bitmap wallpaperBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.wallpaper, options);
            canvas.drawColor(0xff0B1118);
            if (wallpaperBitmap != null) {
                float sourceAspect = (float)wallpaperBitmap.getWidth() / wallpaperBitmap.getHeight();
                float destinationAspect = (float)outputWidth / outputHeight;
                Rect srcRect;
                if (sourceAspect > destinationAspect) {
                    int cropWidth = Math.round(wallpaperBitmap.getHeight() * destinationAspect);
                    int left = (wallpaperBitmap.getWidth() - cropWidth) / 2;
                    srcRect = new Rect(left, 0, left + cropWidth, wallpaperBitmap.getHeight());
                }
                else {
                    int cropHeight = Math.round(wallpaperBitmap.getWidth() / destinationAspect);
                    int top = (wallpaperBitmap.getHeight() - cropHeight) / 2;
                    srcRect = new Rect(0, top, wallpaperBitmap.getWidth(), top + cropHeight);
                }
                RectF dstRect = new RectF(0, 0, outputWidth, outputHeight);
                canvas.drawBitmap(wallpaperBitmap, srcRect, dstRect, paint);
            }
        }

        ImageFs imageFs = ImageFs.find(context);
        MSBitmap.create(outputBitmap, new File(imageFs.getRootDir(), ImageFs.CACHE_PATH+"/wallpaper.bmp"));
    }

    public static File getUserWallpaperFile(Context context) {
        return new File(ImageFs.find(context).getRootDir(), ImageFs.CONFIG_PATH+"/user-wallpaper.png");
    }
}
