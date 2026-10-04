package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionSettingsTest {

    @Test
    public void phoneNamesAreSafeInFolderNames() {
        assertTrue(SessionSettings.validPhoneName(""));
        assertTrue(SessionSettings.validPhoneName("P1"));
        assertTrue(SessionSettings.validPhoneName("Meadow-3_b"));
        assertFalse(SessionSettings.validPhoneName("P 1")); // space
        assertFalse(SessionSettings.validPhoneName("a/b"));
        assertFalse(SessionSettings.validPhoneName("Wiese-Ü"));
        assertFalse(SessionSettings.validPhoneName("123456789012345678901")); // 21 characters
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
