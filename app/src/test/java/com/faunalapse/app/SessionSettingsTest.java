package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
    public void customFieldsKeepTheirType() {
        List<FieldInfo.Custom> fields = new ArrayList<>();
        fields.add(new FieldInfo.Custom("Observer", FieldInfo.Custom.TEXT, "Anna"));
        assertNotNull(FieldInfo.nameProblem("", fields));
        assertNotNull(FieldInfo.nameProblem("observer", fields)); // names are keys in the record
        assertNull(FieldInfo.nameProblem("Air temperature (°C)", fields));
        FieldInfo.Custom t = new FieldInfo.Custom("T", FieldInfo.Custom.NUMBER, "21,5");
        assertNull(t.problem());
        assertEquals(21.5, (Double) t.jsonValue(), 0);
        assertNotNull(new FieldInfo.Custom("T", FieldInfo.Custom.NUMBER, "1.2.3").problem());
        assertNotNull(new FieldInfo.Custom("T", FieldInfo.Custom.NUMBER, "Infinity").problem());
        assertNull(new FieldInfo.Custom("D", FieldInfo.Custom.DATE, "2026-10-05").problem());
        assertNotNull(new FieldInfo.Custom("D", FieldInfo.Custom.DATE, "5.10.2026").problem());
        assertNull(new FieldInfo.Custom("H", FieldInfo.Custom.TIME, "14:30").problem());
        assertEquals("Anna", fields.get(0).jsonValue());
    }

    @Test
    public void choicesYesNoNotesAndSetup() {
        List<String> choices = FieldInfo.choiceLines("sunny\n\n cloudy \nrain");
        assertEquals(3, choices.size()); // empty lines left out, spaces trimmed
        assertEquals("cloudy", choices.get(1));
        assertNull(FieldInfo.choicesProblem(choices));
        assertNotNull(FieldInfo.choicesProblem(FieldInfo.choiceLines("only one")));
        assertNotNull(FieldInfo.choicesProblem(FieldInfo.choiceLines("Rain\nrain")));
        assertNull(new FieldInfo.Custom("W", FieldInfo.Custom.CHOICE, "rain", choices).problem());
        assertNotNull(new FieldInfo.Custom("W", FieldInfo.Custom.CHOICE, "snow", choices).problem());
        assertEquals(true, new FieldInfo.Custom("F", FieldInfo.Custom.YES_NO, "yes").jsonValue());
        assertEquals(false, new FieldInfo.Custom("F", FieldInfo.Custom.YES_NO, "no").jsonValue());
        assertNotNull(new FieldInfo.Custom("F", FieldInfo.Custom.YES_NO, "maybe").problem());
        String long101 = new String(new char[101]).replace('\0', 'a');
        assertNotNull(new FieldInfo.Custom("T", FieldInfo.Custom.TEXT, long101).problem());
        assertNull(new FieldInfo.Custom("N", FieldInfo.Custom.NOTES, long101).problem());
        assertNull(FieldInfo.setupProblem("Camera height", "0,3"));
        assertNull(FieldInfo.setupProblem("Camera height", ""));
        assertNotNull(FieldInfo.setupProblem("Camera height", "-1"));
        assertNotNull(FieldInfo.setupProblem("Camera height", "101"));
        SessionSettings s = new SessionSettings(); // fixed focus at 5 diopters: 20 cm
        assertEquals(0.2, FieldInfo.focusDistanceM(s, "calibrated"), 0);
        assertNull(FieldInfo.focusDistanceM(s, "uncalibrated")); // the lens tells no distances
        s.focusFixed = false;
        assertNull(FieldInfo.focusDistanceM(s, "calibrated"));
    }

    @Test
    public void gpsGoalInWholeMetresFrom0To1000() {
        assertEquals(Integer.valueOf(0), FieldInfo.gpsGoal("0"));     // search the full time
        assertEquals(Integer.valueOf(10), FieldInfo.gpsGoal("10"));
        assertEquals(Integer.valueOf(1000), FieldInfo.gpsGoal("1000"));
        assertNull(FieldInfo.gpsGoal("1001"));
        assertNull(FieldInfo.gpsGoal(""));
        assertNull(FieldInfo.gpsGoal("2.5"));
        assertNull(FieldInfo.gpsGoal("-1"));
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
