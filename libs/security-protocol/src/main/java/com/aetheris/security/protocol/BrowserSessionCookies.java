package com.aetheris.security.protocol;

import java.util.UUID;

/** Cookie namespace shared by Auth and Gateway; the selector itself grants no authority. */
public final class BrowserSessionCookies {
    public static final String SELECTOR = "aetheris_session";
    public static final String ACCESS = "aetheris_access";
    public static final String REFRESH = "aetheris_refresh";
    public static final String LOGOUT = "aetheris_logout";
    private BrowserSessionCookies() { }

    public static boolean validSid(String sid) {
        try { return sid != null && UUID.fromString(sid).toString().equals(sid); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static String name(String purpose, String sid) {
        if (!validSid(sid) || !(ACCESS.equals(purpose) || REFRESH.equals(purpose) || LOGOUT.equals(purpose)))
            throw new IllegalArgumentException("Invalid browser cookie namespace");
        return purpose + "_" + sid;
    }

    /** Only exact known credential names may be retired; never arbitrary caller-supplied cookies. */
    public static String purpose(String name) {
        if (name == null) return null;
        for (String prefix : new String[] {ACCESS, REFRESH, LOGOUT})
            if (name.startsWith(prefix + "_") && validSid(name.substring(prefix.length() + 1))) return prefix;
        return null;
    }
}
