package com.aetheris.security.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionCookiesTest {
    private static final String SID = "5628a5d4-778f-4f75-83d2-416c1cf2a269";

    @Test void onlyCanonicalFamilyIdentifiersCanSelectCookies() {
        assertTrue(BrowserSessionCookies.validSid(SID));
        for (String value : new String[] {null, "", "1-1-1-1-1", SID.toUpperCase(), SID + " ", " " + SID, SID + ";x=y"}) {
            assertFalse(BrowserSessionCookies.validSid(value));
            assertThrows(IllegalArgumentException.class, () -> BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, value));
        }
    }

    @Test void credentialPurposesCannotSelectAnotherApplicationCookieOrTheSelector() {
        for (String value : new String[] {null, "", BrowserSessionCookies.SELECTOR, "unrelated_cookie", "aetheris_access_"})
            assertThrows(IllegalArgumentException.class, () -> BrowserSessionCookies.name(value, SID));
        assertEquals("aetheris_refresh_" + SID, BrowserSessionCookies.name(BrowserSessionCookies.REFRESH, SID));
    }

    @Test void retirementRecognizesOnlyExactCanonicalCredentialNames() {
        assertEquals(BrowserSessionCookies.LOGOUT, BrowserSessionCookies.purpose("aetheris_logout_" + SID));
        for (String value : new String[] {null, "aetheris_access", "aetheris_access_1-1-1-1-1",
                "aetheris_session_" + SID, "unrelated_" + SID, "aetheris_access_" + SID + "_extra"})
            assertNull(BrowserSessionCookies.purpose(value));
    }
}
