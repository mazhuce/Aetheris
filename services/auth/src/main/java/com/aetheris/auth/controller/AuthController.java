package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.service.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(
            AuthUserService authUserService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        AuthUser user = authUserService.findByUsername(request.username()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
        if (!"active".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("message", "账号已禁用"));
        }
        String token = jwtService.generateToken(user.getUsername());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", user.getUsername(),
                "nickname", user.getNickname(),
                "expiresIn", jwtService.getExpirationMs()
        ));
    }
}
