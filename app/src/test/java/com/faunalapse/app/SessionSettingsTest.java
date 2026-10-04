package com.faunalapse.app;

import static org.junit.Assert.assertFalse;
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
}
