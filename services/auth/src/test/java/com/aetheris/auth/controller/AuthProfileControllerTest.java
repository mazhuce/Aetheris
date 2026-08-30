package com.aetheris.auth.controller;

import com.aetheris.auth.dto.AuthUserResponse;
import com.aetheris.auth.dto.ChangePasswordRequest;
import com.aetheris.auth.dto.UpdateProfileRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthProfileControllerTest {

    @Test
    @DisplayName("返回当前登录用户的资料")
    void returnsOwnProfile() {
        Fixture f = new Fixture();

        ResponseEntity<?> response = f.controller.profile("alice");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuthUserResponse body = assertInstanceOf(AuthUserResponse.class, response.getBody());
        assertEquals("alice", body.username());
        assertEquals("viewer", body.role());
    }

    @Test
    @DisplayName("账号已被删除但令牌仍有效时返回 401 而非 500")
    void returns401WhenAccountGone() {
        Fixture f = new Fixture();
        when(f.userService.getOne(any(Wrapper.class))).thenReturn(null);

        // 令牌有效但账号不存在，前端应据此引导重新登录，而不是当成服务故障。
        assertEquals(HttpStatus.UNAUTHORIZED, f.controller.profile("ghost").getStatusCode());
    }

    @Test
    @DisplayName("改密码必须校验当前密码")
    void rejectsWrongCurrentPassword() {
        Fixture f = new Fixture();
        when(f.encoder.matches(eq("wrong-password"), anyString())).thenReturn(false);

        ResponseEntity<?> response = f.controller.changePassword(
                "alice", new ChangePasswordRequest("wrong-password", "new-password-1234"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        // 关键：持有有效令牌不等于知道密码，令牌可能被盗用或来自未登出的公共设备。
        verify(f.userService, never()).updateById(any());
    }

    @Test
    @DisplayName("当前密码正确时更新为新哈希")
    void changesPasswordWhenCurrentMatches() {
        Fixture f = new Fixture();
        when(f.encoder.matches(eq("old-password-123"), anyString())).thenReturn(true);
        when(f.encoder.matches(eq("new-password-456"), anyString())).thenReturn(false);
        when(f.encoder.encode("new-password-456")).thenReturn("hash-of-new");

        ResponseEntity<?> response = f.controller.changePassword(
                "alice", new ChangePasswordRequest("old-password-123", "new-password-456"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(f.userService).updateById(captor.capture());
        assertEquals("hash-of-new", captor.getValue().getPasswordHash());
    }

    @Test
    @DisplayName("新密码与当前密码相同时拒绝")
    void rejectsUnchangedPassword() {
        Fixture f = new Fixture();
        when(f.encoder.matches(anyString(), anyString())).thenReturn(true);

        ResponseEntity<?> response = f.controller.changePassword(
                "alice", new ChangePasswordRequest("same-password-1", "same-password-1"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(f.userService, never()).updateById(any());
    }

    @Test
    @DisplayName("改密码成功后提示需重新登录")
    void signalsReloginAfterPasswordChange() {
        Fixture f = new Fixture();
        when(f.encoder.matches(eq("old-password-123"), anyString())).thenReturn(true);
        when(f.encoder.matches(eq("new-password-456"), anyString())).thenReturn(false);
        when(f.encoder.encode(anyString())).thenReturn("hash-of-new");

        ResponseEntity<?> response = f.controller.changePassword(
                "alice", new ChangePasswordRequest("old-password-123", "new-password-456"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // JWT 无状态，旧令牌在过期前仍有效，需要显式提示前端重新登录。
        assertEquals(true, body.get("reloginRequired"));
    }

    @Test
    @DisplayName("更新资料时角色与状态不受影响，防止自我提权")
    void cannotEscalateRoleViaProfileUpdate() {
        Fixture f = new Fixture();

        f.controller.updateProfile("alice",
                new UpdateProfileRequest("新昵称", "alice@example.com", "13800000000"));

        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(f.userService).update(captor.capture(), any());
        // UpdateProfileRequest 不含 role/status 字段，因此 viewer 无法把自己改成 admin。
        assertEquals("viewer", captor.getValue().getRole());
        assertNotEquals("admin", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("更新资料写入提交的昵称、邮箱与手机号")
    void updatesProfileFields() {
        Fixture f = new Fixture();

        ResponseEntity<?> response = f.controller.updateProfile("alice",
                new UpdateProfileRequest("数据分析师", "new@example.com", "13900000000"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(f.userService).update(captor.capture(), any());
        assertEquals("数据分析师", captor.getValue().getNickname());
        assertEquals("new@example.com", captor.getValue().getEmail());
        assertEquals("13900000000", captor.getValue().getPhone());
    }

    @Test
    @DisplayName("资料响应体不暴露密码哈希")
    void neverExposesPasswordHash() {
        Fixture f = new Fixture();

        ResponseEntity<?> response = f.controller.profile("alice");

        // AuthUserResponse 不映射 passwordHash；用序列化后的文本兜底确认。
        assertTrue(!String.valueOf(response.getBody()).contains("secret-hash"));
    }

    /** 测试夹具：集中构造 mock，避免每个用例重复样板。 */
    private static final class Fixture {
        final AuthUserService userService = mock(AuthUserService.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final AuthProfileController controller;

        Fixture() {
            AuthUser user = new AuthUser();
            user.setId(2L);
            user.setUsername("alice");
            user.setPasswordHash("secret-hash");
            user.setNickname("原昵称");
            user.setRole("viewer");
            user.setStatus("active");
            when(userService.getOne(any(Wrapper.class))).thenReturn(user);
            controller = new AuthProfileController(userService, encoder);
        }
    }
}
