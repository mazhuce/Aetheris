package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.service.JwtService;
import com.aetheris.auth.service.LoginAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;

    public AuthController(
            AuthUserService authUserService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttempts) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        if (!loginAttempts.allow(request.username())) {
            return ResponseEntity.status(429).body(Map.of("message", "登录尝试过于频繁，请稍后再试"));
        }
        AuthUser user = authUserService.findByUsername(request.username()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttempts.recordFailure(request.username());
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
        if (!"active".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("message", "账号已禁用"));
        }
        loginAttempts.reset(request.username());
        String role = user.getRole() == null ? "viewer" : user.getRole();
        String token = jwtService.generateToken(user.getId(), user.getUsername(), role);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("role", role);
        if (user.getNickname() != null) {
            response.put("nickname", user.getNickname());
        }
        response.put("expiresIn", jwtService.getExpirationMs());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
    }
}
