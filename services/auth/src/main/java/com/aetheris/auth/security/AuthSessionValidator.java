package com.aetheris.auth.security;

import com.aetheris.auth.service.AuthUserService;
import com.aetheris.security.servlet.SessionValidator;
import org.springframework.stereotype.Component;

/** An uncached, local account lookup; never calls auth recursively. */
@Component
public final class AuthSessionValidator implements SessionValidator {
    private final AuthUserService users;
    public AuthSessionValidator(AuthUserService users) { this.users = users; }
    @Override public boolean isActive(long uid, String username, long accessVersion) {
        if (uid <= 0 || username == null || username.isBlank() || accessVersion < 0) return false;
        return users.findByUsername(username).filter(user -> Long.valueOf(uid).equals(user.getId())
                && username.equals(user.getUsername()) && "active".equals(user.getStatus())
                && accessVersion == user.getAccessVersion()).isPresent();
    }
}
