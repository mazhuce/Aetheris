package com.aetheris.auth.controller;

import com.aetheris.auth.dto.AuthUserResponse;
import com.aetheris.auth.dto.CreateAuthUserRequest;
import com.aetheris.auth.dto.UpdateAuthUserRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserAdminControllerTest {

    @Test
    void createsLoginAccountWithHashedPassword() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userService.findByUsername("analyst")).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        AuthUserAdminController controller = new AuthUserAdminController(userService, encoder);

        ResponseEntity<?> response = controller.create(new CreateAuthUserRequest(
                "analyst", "strong-password", "分析师", "analyst@example.com", null,
                "viewer", "active"));

        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userService).save(captor.capture());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("encoded-password", captor.getValue().getPasswordHash());
        assertEquals("viewer", captor.getValue().getRole());
        assertInstanceOf(AuthUserResponse.class, response.getBody());
    }

    @Test
    void preventsCurrentAdministratorFromDisablingSelf() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthUser admin = user(1L, "admin", "admin", "active");
        when(userService.getById(1L)).thenReturn(admin);
        AuthUserAdminController controller = new AuthUserAdminController(userService, encoder);

        ResponseEntity<?> response = controller.update(
                1L, "admin", new UpdateAuthUserRequest(null, null, null, null, "admin", "disabled"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(userService, never()).updateById(admin);
    }

    @Test
    void preventsCurrentAdministratorFromDeletingSelf() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthUser admin = user(1L, "admin", "admin", "active");
        when(userService.getById(1L)).thenReturn(admin);
        AuthUserAdminController controller = new AuthUserAdminController(
                userService, mock(PasswordEncoder.class));

        ResponseEntity<?> response = controller.delete(1L, "admin");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(userService, never()).removeById(1L);
    }

    private AuthUser user(Long id, String username, String role, String status) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
