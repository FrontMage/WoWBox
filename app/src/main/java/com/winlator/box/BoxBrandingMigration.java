package com.winlator.box;

import com.winlator.core.WineThemeManager;

final class BoxBrandingMigration {
    static final String LEGACY_CONTAINER_NAME = "Winlator Box";
    static final String WOW_BOX_CONTAINER_NAME = "WoW Box";
    static final String LEGACY_DESKTOP_THEME = "LIGHT,IMAGE,#0277bd";

    private BoxBrandingMigration() {}

    static Result migrate(String name, String desktopTheme) {
        String migratedName = LEGACY_CONTAINER_NAME.equals(name) ? WOW_BOX_CONTAINER_NAME : name;
        String migratedTheme = LEGACY_DESKTOP_THEME.equalsIgnoreCase(desktopTheme)
                ? WineThemeManager.DEFAULT_DESKTOP_THEME
                : desktopTheme;
        return new Result(
                migratedName,
                migratedTheme,
                !equals(name, migratedName) || !equals(desktopTheme, migratedTheme));
    }

    private static boolean equals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    static final class Result {
        final String name;
        final String desktopTheme;
        final boolean changed;

        Result(String name, String desktopTheme, boolean changed) {
            this.name = name;
            this.desktopTheme = desktopTheme;
            this.changed = changed;
        }
    }
}
