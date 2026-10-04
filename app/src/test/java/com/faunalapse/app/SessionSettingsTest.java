package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionSettingsTest {

    @Test
    public void phoneNamesAreSafeInFolderNames() {
        assertFalse(FieldInfo.validPhoneName("")); // the folder name starts with it
        assertTrue(FieldInfo.validPhoneName("P1"));
        assertTrue(FieldInfo.validPhoneName("Meadow-3_b"));
        assertTrue(FieldInfo.validPhoneName("SM-M127F-3A9C"));
        assertFalse(FieldInfo.validPhoneName("P 1")); // space
        assertFalse(FieldInfo.validPhoneName("a/b"));
        assertFalse(FieldInfo.validPhoneName("Wiese-Ü"));
        assertFalse(FieldInfo.validPhoneName("123456789012345678901")); // 21 characters
    }

    @Test
    public void runLengthFromOneMinuteTo720Hours() {
        SessionSettings s = new SessionSettings();
        for (int min : new int[] {1, 45, 60, 90, 720 * 60}) {
            s.runMin = min;
            assertNull(s.problem());
        }
        s.runMin = 0;
        assertEquals("Run length: use 1 min to 720 h.", s.problem());
        s.runMin = 720 * 60 + 1;
        assertEquals("Run length: use 1 min to 720 h.", s.problem());
    }

    @Test
    public void batteryCurrentInMilliamperes() {
        assertEquals(350.0, BatteryMeter.milliamperes(-350), 0);        // Samsung M12: mA
        assertEquals(350.0, BatteryMeter.milliamperes(-350000), 0);     // Android's documented µA
        assertEquals(42.5, BatteryMeter.milliamperes(42500), 0);
        assertNull(BatteryMeter.milliamperes(0));                       // not reported
        assertNull(BatteryMeter.milliamperes(Integer.MIN_VALUE));
    }

    @Test
    public void savedSideIsAMultipleOf32InTheLimits() {
        SessionSettings s = new SessionSettings();
        for (int px : new int[] {224, 320, 640, 1024, 1280, 1920, 4096}) {
            s.savedSidePx = px;
            assertNull(s.problem());
        }
        s.savedSidePx = 1000;
        assertEquals("Saved square side: use a multiple of 32 from 224 to 4096 px, for example 992 or 1024.",
                s.problem());
        s.savedSidePx = 192;
        assertEquals("Saved square side: use a multiple of 32 from 224 to 4096 px, for example 224 or 256.",
                s.problem());
        s.savedSidePx = 5000;
        assertEquals("Saved square side: use a multiple of 32 from 224 to 4096 px, for example 4064 or 4096.",
                s.problem());
    }
}
