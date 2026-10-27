package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.*;
import com.aetheris.auth.validation.UsernameRules;
import com.aetheris.security.protocol.BrowserSessionCookies;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String ACCESS_COOKIE = BrowserSessionCookies.ACCESS;
    public static final String REFRESH_COOKIE = BrowserSessionCookies.REFRESH;
    public static final String LOGOUT_COOKIE = BrowserSessionCookies.LOGOUT;
    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final PermissionVersionService permissionVersions;
    private final BrowserSessionService sessions;
    private final boolean secureCookies;

    public AuthController(AuthUserService users, PasswordEncoder encoder, JwtService jwt, LoginAttemptService attempts,
                          PermissionVersionService versions, BrowserSessionService sessions) {
        this(users, encoder, jwt, attempts, versions, sessions, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(AuthUserService users, PasswordEncoder encoder, JwtService jwt, LoginAttemptService attempts,
                          PermissionVersionService versions, BrowserSessionService sessions,
                          @org.springframework.beans.factory.annotation.Value("${auth.cookies.secure:false}") boolean secureCookies) {
        this.authUserService = users; this.passwordEncoder = encoder; this.jwtService = jwt;
        this.loginAttempts = attempts; this.permissionVersions = versions; this.sessions = sessions;
        this.secureCookies = secureCookies;
    }

    public ResponseEntity<Map<String, Object>> login(LoginRequest request) { return login(request, null); }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                   jakarta.servlet.http.HttpServletRequest servletRequest) {
        String username = UsernameRules.requireValid(request.username());
        String source = servletRequest == null ? null : servletRequest.getHeader("X-Aetheris-Client-IP");
        if (source == null && servletRequest != null) source = servletRequest.getRemoteAddr();
        if (source != null && source.length() > 128) source = "invalid-source";
        if (!loginAttempts.allow(username, source))
            return ResponseEntity.status(429).body(Map.of("message", "登录尝试过于频繁，请稍后再试"));
        try {
            AuthUser user = authUserService.findByUsername(username).orElse(null);
            if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                loginAttempts.recordFailure(username, source);
                return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
            }
            if (!"active".equals(user.getStatus()))
                return ResponseEntity.status(403).body(Map.of("message", "账号已禁用"));
            long permissionVersion = permissionVersions.current();
            var session = sessions.open(user, Duration.ofMillis(jwtService.getRefreshExpirationMs()));
            if (session.isEmpty()) return unauthorized();
            loginAttempts.reset(username);
            return authenticated(session.get(), permissionVersion, true, servletRequest);
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
        String selected = selectedFamily(request);
        String token = credential(request, REFRESH_COOKIE, selected);
        final JwtService.BrowserCredential proof;
        try {
            if (token == null || token.isBlank()) return unauthorized();
            proof = jwtService.parseRefreshToken(token);
            if (!proof.sid().toString().equals(selected)) return unauthorized();
        } catch (JwtException | IllegalArgumentException invalid) {
            return unauthorized();
        }
        try {
            // Read dependencies before consuming a single-use refresh credential.
            long permissionVersion = permissionVersions.current();
            return sessions.rotate(proof.sid(), proof.uid(), proof.username(), proof.accessVersion(), proof.refreshVersion())
                    .map(session -> authenticated(session, permissionVersion, false, null)).orElseGet(this::unauthorized);
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        String selected = selectedFamily(request);
        String token = credential(request, LOGOUT_COOKIE, selected);
        final JwtService.BrowserCredential proof;
        try {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Missing logout proof");
            proof = jwtService.parseLogoutToken(token);
            if (!proof.sid().toString().equals(selected)) throw new IllegalArgumentException("Wrong logout family");
        } catch (JwtException | IllegalArgumentException invalid) {
            return clearCookies(ResponseEntity.status(401), selected, true).body(Map.of(
                    "code", "BROWSER_SESSION_LOGOUT_UNCONFIRMED", "message", "本地已退出，服务端会话撤销未确认"));
        }
        try {
            sessions.revoke(proof.sid(), proof.uid(), proof.username(), proof.accessVersion());
            return clearCookies(ResponseEntity.status(204), selected, true).build();
        } catch (DataAccessException unavailable) {
            // Keep only the logout capability so an explicit retry can still revoke this family.
            return clearCookies(ResponseEntity.status(503), selected, false).body(Map.of(
                    "code", "BROWSER_SESSION_LOGOUT_UNCONFIRMED", "message", "本地已退出，服务端会话暂时无法撤销，请重试退出"));
        }
    }

    private ResponseEntity<Map<String, Object>> authenticated(BrowserSessionService.Session session, long permissionVersion,
                                                              boolean passwordLogin, HttpServletRequest request) {
        long familyRemainingMs = Math.max(0, Duration.between(Instant.now(), session.expiresAt()).toMillis());
        if (familyRemainingMs < 1000) return unauthorized();
        long accessRemainingMs = Math.min(jwtService.getExpirationMs(), familyRemainingMs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", session.uid()); body.put("username", session.username());
        body.put("role", session.role() == null ? "viewer" : session.role());
        body.put("accessVersion", session.accessVersion()); body.put("permissionVersion", permissionVersion);
        if (session.nickname() != null) body.put("nickname", session.nickname());
        body.put("expiresIn", accessRemainingMs);
        String sid = session.sid().toString();
        var response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.name(ACCESS_COOKIE, sid), jwtService.generateToken(session), "/", accessRemainingMs).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.name(REFRESH_COOKIE, sid), jwtService.generateRefreshToken(session), "/api/auth", familyRemainingMs).toString());
        if (passwordLogin) {
            // Refresh/logout never write this selector. Their delayed responses only touch their own family.
            response.header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.SELECTOR, sid, "/", familyRemainingMs).toString(),
                    cookie(BrowserSessionCookies.name(LOGOUT_COOKIE, sid), jwtService.generateLogoutToken(session), "/api/auth", familyRemainingMs).toString());
            retirePreviousCookies(response, request, sid);
        }
        return response.body(body);
    }

    private ResponseCookie cookie(String name, String value, String path, long maxAgeMs) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path(path).maxAge(Math.max(0, maxAgeMs / 1000)).build();
    }

    private ResponseEntity.BodyBuilder clearCookies(ResponseEntity.BodyBuilder response, String sid, boolean logoutProof) {
        response.header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!BrowserSessionCookies.validSid(sid)) return response;
        response.header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.name(ACCESS_COOKIE, sid), "", "/", 0).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.name(REFRESH_COOKIE, sid), "", "/api/auth", 0).toString());
        if (logoutProof) response.header(HttpHeaders.SET_COOKIE, cookie(BrowserSessionCookies.name(LOGOUT_COOKIE, sid), "", "/api/auth", 0).toString());
        return response;
    }

    private static String selectedFamily(HttpServletRequest request) {
        String sid = uniqueCookie(request, BrowserSessionCookies.SELECTOR);
        return BrowserSessionCookies.validSid(sid) ? sid : null;
    }

    private static String credential(HttpServletRequest request, String purpose, String sid) {
        return sid == null ? null : uniqueCookie(request, BrowserSessionCookies.name(purpose, sid));
    }

    private static String uniqueCookie(HttpServletRequest request, String name) {
        String result = null; boolean found = false;
        if (request == null || request.getCookies() == null) return null;
        for (var value : request.getCookies()) if (name.equals(value.getName())) {
            if (found) return null;
            found = true; result = value.getValue();
        }
        return result;
    }

    private void retirePreviousCookies(ResponseEntity.BodyBuilder response, HttpServletRequest request, String currentSid) {
        if (request == null || request.getCookies() == null) return;
        var retired = new java.util.HashSet<String>();
        for (var value : request.getCookies()) {
            String name = value.getName(), purpose = BrowserSessionCookies.purpose(name);
            if (purpose == null && (ACCESS_COOKIE.equals(name) || REFRESH_COOKIE.equals(name) || LOGOUT_COOKIE.equals(name))) purpose = name;
            if (purpose != null && !name.equals(BrowserSessionCookies.name(purpose, currentSid)) && retired.add(name))
                response.header(HttpHeaders.SET_COOKIE, cookie(name, "", ACCESS_COOKIE.equals(purpose) ? "/" : "/api/auth", 0).toString());
        }
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        // A losing concurrent refresh must not erase the winner's new cookies.
        return ResponseEntity.status(401).body(Map.of("message", "登录状态已失效，请重新登录"));
    }

    private ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "BROWSER_SESSION_UNAVAILABLE", "message", "登录服务暂时不可用，请稍后重试"));
    }
}
