package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ManagedDxProfileTest {
    private BoxSpec.Layer layer(String type, String hash) {
        BoxSpec.Layer layer = new BoxSpec.Layer();
        layer.type = type;
        layer.source = "asset://dxwrapper/" + type + ".tzst";
        layer.checksum = hash;
        layer.target = "/home/xuser-box/.wine/drive_c/windows";
        return layer;
    }

    private static String hash(char ch) {
        char[] value = new char[64];
        java.util.Arrays.fill(value, ch);
        return new String(value);
    }

    @Test
    public void unmarkedProfilesRetainLegacyRefresh() {
        BoxSpec spec = new BoxSpec();
        spec.layers.add(layer("dx-wrapper-stack", hash('a')));
        assertNull(ManagedDxProfile.cacheIdentity(spec));
    }

    @Test
    public void sameVersionWithDifferentBinariesInvalidatesCache() {
        BoxSpec spec = new BoxSpec();
        BoxSpec.Layer dx = layer("dx-wrapper-stack", hash('a'));
        dx.capabilities.add(ManagedDxProfile.CAPABILITY);
        spec.layers.add(dx);
        String first = ManagedDxProfile.cacheIdentity(spec);
        dx.checksum = hash('b');
        assertNotEquals(first, ManagedDxProfile.cacheIdentity(spec));
    }

    @Test
    public void nativeOwnershipPreservesTheLauncherLayerAndItsDestination() {
        BoxSpec spec = new BoxSpec();
        BoxSpec.Layer launcher = layer("dx-wrapper-stack", hash('a'));
        BoxSpec.Layer nativeDx = layer(ManagedDxProfile.NATIVE_LAYER_TYPE, hash('b'));
        nativeDx.capabilities.add(ManagedDxProfile.CAPABILITY);
        nativeDx.target = "/opt/native-dx";
        spec.layers.add(launcher);
        spec.layers.add(nativeDx);
        assertEquals(2, ManagedDxProfile.ownedLayers(spec).size());
        String first = ManagedDxProfile.cacheIdentity(spec);
        nativeDx.target = "/opt/other-native-dx";
        assertNotEquals(first, ManagedDxProfile.cacheIdentity(spec));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesUnidentifiedManagedBinaries() {
        BoxSpec spec = new BoxSpec();
        BoxSpec.Layer dx = layer("dx-wrapper-stack", "");
        dx.capabilities.add(ManagedDxProfile.CAPABILITY);
        spec.layers.add(dx);
        ManagedDxProfile.cacheIdentity(spec);
    }
}
