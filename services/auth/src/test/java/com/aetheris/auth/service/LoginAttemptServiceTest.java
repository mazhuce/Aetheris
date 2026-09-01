package com.aetheris.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败锁定逻辑的单元测试。
 * <p>
 * 策略：5 次失败后锁定 15 分钟。锁定期内拒绝所有尝试，锁定期满后自动放行。
 * 成功登录后应重置计数器，否则偶发输错密码的用户会被累积锁定。
 */
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    @DisplayName("初始状态允许尝试")
    void allowsInitialAttempt() {
        assertTrue(service.allow("alice"));
    }

    @Test
    @DisplayName("失败次数未达上限时仍然允许")
    void allowsWhenBelowMaxFailures() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("alice");
        }
        assertTrue(service.allow("alice"));
    }

    @Test
    @DisplayName("达到 5 次失败后锁定")
    void locksWhenMaxFailuresReached() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice");
        }
        assertFalse(service.allow("alice"));
    }

    @Test
    @DisplayName("锁定期间 recordFailure 调用不会改变锁定状态")
    void staysLockedDespiteAdditionalFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice");
        }
        // 锁定期内继续记录失败不应复位计时器，否则攻击者可通过持续请求延长锁定。
        service.recordFailure("alice");
        assertFalse(service.allow("alice"));
    }

    @Test
    @DisplayName("锁定按用户名隔离，一个用户锁定不影响其他人")
    void countsPerUser() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice");
        }
        assertFalse(service.allow("alice"));
        assertTrue(service.allow("bob"));
    }

    @Test
    @DisplayName("reset 方法清除失败计数，恢复允许状态")
    void resetClearsFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice");
        }
        assertFalse(service.allow("alice"));

        service.reset("alice");
        assertTrue(service.allow("alice"));
    }

    @Test
    @DisplayName("用户名大小写不敏感，Alice 与 alice 共用同一计数器")
    void usernameIsCaseInsensitive() {
        service.recordFailure("Alice");
        service.recordFailure("ALICE");
        service.recordFailure("alice");
        service.recordFailure("AlIcE");
        service.recordFailure("alice");

        assertFalse(service.allow("ALICE"));
    }

    @Test
    @DisplayName("null 用户名按空字符串处理，不抛 NPE")
    void nullUsernameIsTolerated() {
        service.recordFailure(null);
        assertTrue(service.allow(null));
        service.reset(null);
        assertTrue(service.allow(null));
    }
}