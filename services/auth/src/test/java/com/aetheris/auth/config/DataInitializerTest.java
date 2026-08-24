package com.aetheris.auth.config;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataInitializerTest {

    @Test
    void requiresStrongPasswordWhenDatabaseHasNoUsers() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userService.count()).thenReturn(0L);

        DataInitializer initializer = new DataInitializer(
                userService, encoder, "admin", "short", "系统管理员");

        assertThrows(IllegalStateException.class, () -> initializer.run());
    }

    @Test
    void createsConfiguredInitialAdministrator() throws Exception {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userService.count()).thenReturn(0L);
        when(encoder.encode(anyString())).thenReturn("encoded-password");

        DataInitializer initializer = new DataInitializer(
                userService, encoder, "owner", "strong-password", "平台管理员");
        initializer.run();

        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userService).save(captor.capture());
        assertEquals("owner", captor.getValue().getUsername());
        assertEquals("encoded-password", captor.getValue().getPasswordHash());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }
}
