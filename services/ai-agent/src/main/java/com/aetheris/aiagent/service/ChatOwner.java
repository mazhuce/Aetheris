package com.aetheris.aiagent.service;

import com.aetheris.security.servlet.TrustedUser;
import java.util.Objects;

/** Stable ownership, captured from admitted identity; neither a request DTO nor a stored proof/AV. */
public final class ChatOwner {
    private final long uid;
    private final String username;

    private ChatOwner(TrustedUser user) {
        Objects.requireNonNull(user, "Verified AI user required");
        uid = user.proof().uid(); username = user.proof().username();
        if (uid <= 0 || username == null || username.isBlank()) throw new IllegalArgumentException("Verified AI user required");
    }
    public static ChatOwner from(TrustedUser user) { return new ChatOwner(user); }
    public long uid() { return uid; }
    public String username() { return username; }
    @Override public boolean equals(Object other) { return other instanceof ChatOwner owner && uid == owner.uid; }
    @Override public int hashCode() { return Long.hashCode(uid); }
    @Override public String toString() { return "ChatOwner[uid=" + uid + "]"; }
}
