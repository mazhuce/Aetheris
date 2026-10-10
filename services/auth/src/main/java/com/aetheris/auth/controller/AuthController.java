package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.service.JwtService;
import com.aetheris.auth.service.LoginAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import io.jsonwebtoken.Claims;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String ACCESS_COOKIE = "aetheris_access";
    public static final String REFRESH_COOKIE = "aetheris_refresh";

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final boolean secureCookies;

    public AuthController(
            AuthUserService authUserService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttempts) {
        this(authUserService, passwordEncoder, jwtService, loginAttempts, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(
            AuthUserService authUserService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttempts,
            @org.springframework.beans.factory.annotation.Value("${auth.cookies.secure:false}") boolean secureCookies) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        // 用户名是标识，不是密码：容忍复制/自动填充带来的首尾空格，
        // 同时让限流键、数据库查询和响应中的用户名保持一致。
        String username = request.username().trim();
        if (!loginAttempts.allow(username)) {
            return ResponseEntity.status(429).body(Map.of("message", "登录尝试过于频繁，请稍后再试"));
        }
        AuthUser user = authUserService.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttempts.recordFailure(username);
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
        if (!"active".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("message", "账号已禁用"));
        }
        loginAttempts.reset(username);
        String role = user.getRole() == null ? "viewer" : user.getRole();
        long sessionVersion = user.getSessionVersion();
        String token = jwtService.generateToken(user.getId(), user.getUsername(), role);
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername(), sessionVersion);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", user.getUsername());
        response.put("role", role);
        if (user.getNickname() != null) {
            response.put("nickname", user.getNickname());
        }
        response.put("expiresIn", jwtService.getExpirationMs());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie(token, jwtService.getExpirationMs()).toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, jwtService.getRefreshExpirationMs()).toString())
                .body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @org.springframework.web.bind.annotation.CookieValue(name = REFRESH_COOKIE, required = false)
            String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return unauthorized();
        }
        try {
            Claims claims = jwtService.parseRefreshToken(refreshToken);
            String username = claims.getSubject();
            Number uidClaim = claims.get("uid", Number.class);
            Number versionClaim = claims.get("sv", Number.class);
            if (username == null || username.isBlank() || uidClaim == null || versionClaim == null) {
                return unauthorized();
            }
            AuthUser user = authUserService.findByUsername(username).orElse(null);
            long currentVersion = user == null ? -1L : user.getSessionVersion();
            long tokenVersion = versionClaim.longValue();
            if (user == null || !user.getId().equals(uidClaim.longValue())
                    || !"active".equals(user.getStatus()) || currentVersion != tokenVersion) {
                return unauthorized();
            }
            long nextVersion = tokenVersion + 1;
            boolean rotated = authUserService.update(null, new UpdateWrapper<AuthUser>()
                    .eq("id", user.getId())
                    .eq("session_version", tokenVersion)
                    .set("session_version", nextVersion));
            if (!rotated) {
                return unauthorized();
            }
            String role = user.getRole() == null ? "viewer" : user.getRole();
            String access = jwtService.generateToken(user.getId(), user.getUsername(), role);
            String nextRefresh = jwtService.generateRefreshToken(user.getId(), user.getUsername(), nextVersion);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("username", user.getUsername());
            response.put("role", role);
            if (user.getNickname() != null) response.put("nickname", user.getNickname());
            response.put("expiresIn", jwtService.getExpirationMs());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, accessCookie(access, jwtService.getExpirationMs()).toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(nextRefresh, jwtService.getRefreshExpirationMs()).toString())
                    .body(response);
        } catch (Exception invalid) {
            return unauthorized();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @org.springframework.web.bind.annotation.CookieValue(name = REFRESH_COOKIE, required = false)
            String refreshToken) {
        // Incrementing the session version invalidates the current refresh token
        // and all older rotated tokens. Cookie clearing remains effective even if
        // the token is already expired or malformed.
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Claims claims = jwtService.parseRefreshToken(refreshToken);
                Number uid = claims.get("uid", Number.class);
                Number version = claims.get("sv", Number.class);
                if (uid != null && version != null) {
                    authUserService.update(null, new UpdateWrapper<AuthUser>()
                            .eq("id", uid.longValue())
                            .eq("session_version", version.longValue())
                            .set("session_version", version.longValue() + 1));
                }
            } catch (Exception ignored) {
                // Logout is idempotent: always clear browser cookies.
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie(ACCESS_COOKIE, "/").toString())
                .header(HttpHeaders.SET_COOKIE, expiredCookie(REFRESH_COOKIE, "/api/auth").toString())
                .build();
    }

    private ResponseCookie accessCookie(String token, long maxAgeMs) {
        return ResponseCookie.from(ACCESS_COOKIE, token).httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path("/").maxAge(Math.max(1, maxAgeMs / 1000)).build();
    }

    private ResponseCookie refreshCookie(String token, long maxAgeMs) {
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path("/api/auth").maxAge(Math.max(1, maxAgeMs / 1000)).build();
    }

    private ResponseCookie expiredCookie(String name, String path) {
        return ResponseCookie.from(name, "").httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path(path).maxAge(0).build();
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, expiredCookie(ACCESS_COOKIE, "/").toString())
                .header(HttpHeaders.SET_COOKIE, expiredCookie(REFRESH_COOKIE, "/api/auth").toString())
                .body(Map.of("message", "登录状态已失效，请重新登录"));
    }
}
