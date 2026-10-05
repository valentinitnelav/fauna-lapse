package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SessionSettingsTest {

    @Test
    public void typedPositionsInDecimalDegrees() {
        assertNull(FieldInfo.positionProblem(48.858370, 2.294481, null));
        assertNull(FieldInfo.positionProblem(-33.8568, -151.2153, 5f)); // south and west are negative
        assertNull(FieldInfo.positionProblem(90, 180, 0f));
        assertNotNull(FieldInfo.positionProblem(90.1, 0, null));
        assertNotNull(FieldInfo.positionProblem(95.5, 0, null));
        assertNotNull(FieldInfo.positionProblem(0, -180.5, null));
        assertNotNull(FieldInfo.positionProblem(Double.NaN, 0, null));
        assertNotNull(FieldInfo.positionProblem(0, 0, -1f));
        assertNotNull(FieldInfo.positionProblem(0, 0, 10001f));
        assertEquals("48.858370", FieldInfo.degrees(48.85837));
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
