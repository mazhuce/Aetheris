package com.aetheris.auth.controller;

import com.aetheris.auth.dto.AuthUserResponse;
import com.aetheris.auth.dto.CreateAuthUserRequest;
import com.aetheris.auth.dto.UpdateAuthUserRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/users")
public class AuthUserAdminController {

    private static final String USER_HEADER = "X-User-Name";

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;

    public AuthUserAdminController(AuthUserService authUserService, PasswordEncoder passwordEncoder) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<AuthUserResponse> list() {
        return authUserService.list(
                        new LambdaQueryWrapper<AuthUser>().orderByAsc(AuthUser::getId))
                .stream()
                .map(AuthUserResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateAuthUserRequest request) {
        if (authUserService.findByUsername(request.username()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "用户名已存在"));
        }

        AuthUser user = new AuthUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setRole(request.role());
        user.setStatus(request.status());
        authUserService.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthUserResponse.from(user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String requester,
            @Valid @RequestBody UpdateAuthUserRequest request) {
        AuthUser user = authUserService.getById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        String nextRole = request.role() == null ? user.getRole() : request.role();
        String nextStatus = request.status() == null ? user.getStatus() : request.status();
        if (user.getUsername().equals(requester)
                && (!"admin".equals(nextRole) || !"active".equals(nextStatus))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "不能降级或禁用当前登录账号"));
        }
        if (isRemovingActiveAdmin(user, nextRole, nextStatus) && activeAdminCount() <= 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "系统至少需要一个启用的管理员"));
        }

        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setRole(nextRole);
        user.setStatus(nextStatus);
        user.setUpdatedAt(OffsetDateTime.now());
        authUserService.updateById(user);
        return ResponseEntity.ok(AuthUserResponse.from(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String requester) {
        AuthUser user = authUserService.getById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (user.getUsername().equals(requester)) {
            return ResponseEntity.badRequest().body(Map.of("message", "不能删除当前登录账号"));
        }
        if ("admin".equals(user.getRole()) && "active".equals(user.getStatus()) && activeAdminCount() <= 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "系统至少需要一个启用的管理员"));
        }
        authUserService.removeById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean isRemovingActiveAdmin(AuthUser user, String nextRole, String nextStatus) {
        return "admin".equals(user.getRole())
                && "active".equals(user.getStatus())
                && (!"admin".equals(nextRole) || !"active".equals(nextStatus));
    }

    private long activeAdminCount() {
        return authUserService.count(
                new LambdaQueryWrapper<AuthUser>()
                        .eq(AuthUser::getRole, "admin")
                        .eq(AuthUser::getStatus, "active"));
    }
}
