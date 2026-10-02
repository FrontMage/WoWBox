package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.core.WineThemeManager;

import org.junit.Test;

public class BoxBrandingMigrationTest {
    @Test
    public void migratesOnlyTheLegacyDefaults() {
        BoxBrandingMigration.Result result = BoxBrandingMigration.migrate(
                "Winlator Box",
                "LIGHT,IMAGE,#0277bd");

        assertTrue(result.changed);
        assertEquals("WoW Box", result.name);
        assertEquals(WineThemeManager.DEFAULT_DESKTOP_THEME, result.desktopTheme);
    }

    @Test
    public void preservesCustomNameAndTheme() {
        BoxBrandingMigration.Result result = BoxBrandingMigration.migrate(
                "My RPG setup",
                "LIGHT,COLOR,#123456");

        assertFalse(result.changed);
        assertEquals("My RPG setup", result.name);
        assertEquals("LIGHT,COLOR,#123456", result.desktopTheme);
    }

    @Test
    public void migrationIsIdempotent() {
        BoxBrandingMigration.Result first = BoxBrandingMigration.migrate(
                "Winlator Box",
                "LIGHT,IMAGE,#0277bd");
        BoxBrandingMigration.Result second = BoxBrandingMigration.migrate(
                first.name,
                first.desktopTheme);

        assertFalse(second.changed);
        assertEquals(first.name, second.name);
        assertEquals(first.desktopTheme, second.desktopTheme);
    }

    @Test
    public void customImageThemeIsNotReplaced() {
        BoxBrandingMigration.Result result = BoxBrandingMigration.migrate(
                "WoW Box",
                "DARK,IMAGE,#334455");

        assertFalse(result.changed);
        assertEquals("DARK,IMAGE,#334455", result.desktopTheme);
    }
}
