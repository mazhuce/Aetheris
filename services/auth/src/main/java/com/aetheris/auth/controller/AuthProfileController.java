package com.aetheris.auth.controller;

import com.aetheris.auth.dto.AuthUserResponse;
import com.aetheris.auth.dto.ChangePasswordRequest;
import com.aetheris.auth.dto.UpdateProfileRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 当前登录用户的自助接口。
 *
 * <p>与 {@link AuthUserAdminController} 分开的理由不只是路径不同：管理端可以改任意用户的
 * 角色与状态，自助端只能改自己的资料与密码。若合并在一处，很容易在后续迭代中让某个分支
 * 漏掉「不能改自己角色」的判断，从而出现自我提权。
 *
 * <p>身份一律取自网关注入的 {@code X-User-Name}（网关校验 JWT 后写入），
 * 不接受请求体或路径参数指定用户，否则任何登录用户都能改别人的密码。
 */
@RestController
@RequestMapping("/api/auth/me")
public class AuthProfileController {

    private static final Logger log = LoggerFactory.getLogger(AuthProfileController.class);
    private static final String USER_HEADER = "X-User-Name";

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;

    public AuthProfileController(AuthUserService authUserService, PasswordEncoder passwordEncoder) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
    }

    /** 返回当前登录用户的资料；响应体不含密码哈希（AuthUserResponse 本就不映射该字段）。 */
    @GetMapping
    public ResponseEntity<?> profile(@RequestHeader(USER_HEADER) String username) {
        AuthUser user = findByUsername(username);
        if (user == null) {
            // 令牌有效但账号已被删除：让前端把这种情况当作需要重新登录，而非服务故障。
            return ResponseEntity.status(401).body(Map.of("message", "账号不存在，请重新登录"));
        }
        return ResponseEntity.ok(AuthUserResponse.from(user));
    }

    @PutMapping
    public ResponseEntity<?> updateProfile(
            @RequestHeader(USER_HEADER) String username,
            @Valid @RequestBody UpdateProfileRequest request) {
        AuthUser user = findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "账号不存在，请重新登录"));
        }

        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setUpdatedAt(OffsetDateTime.now());
        // updateById 会跳过 null 字段，导致昵称/邮箱/手机号无法清空，故与管理端一样显式置空。
        authUserService.update(user, new LambdaUpdateWrapper<AuthUser>()
                .eq(AuthUser::getId, user.getId())
                .set(request.nickname() == null, AuthUser::getNickname, null)
                .set(request.email() == null, AuthUser::getEmail, null)
                .set(request.phone() == null, AuthUser::getPhone, null));
        return ResponseEntity.ok(AuthUserResponse.from(user));
    }

    @PutMapping("/password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(USER_HEADER) String username,
            @Valid @RequestBody ChangePasswordRequest request) {
        AuthUser user = findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "账号不存在，请重新登录"));
        }

        // 校验旧密码：持有有效令牌不等于知道密码（令牌可能被盗用或来自未登出的公共设备）。
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("Password change rejected for {}: current password mismatch", username);
            return ResponseEntity.badRequest().body(Map.of("message", "当前密码不正确"));
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body(Map.of("message", "新密码不能与当前密码相同"));
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(OffsetDateTime.now());
        authUserService.updateById(user);
        log.info("Password changed for {}", username);
        // 提示前端重新登录：JWT 无状态，旧令牌在过期前仍然有效，这是本实现的已知限制。
        return ResponseEntity.ok(Map.of(
                "message", "密码已修改，请使用新密码重新登录",
                "reloginRequired", true));
    }

    private AuthUser findByUsername(String username) {
        return authUserService.getOne(
                new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username));
    }
}
