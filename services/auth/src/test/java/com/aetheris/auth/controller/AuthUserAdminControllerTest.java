package com.aetheris.auth.controller;

import com.aetheris.auth.dto.AuthUserResponse;
import com.aetheris.auth.dto.CreateAuthUserRequest;
import com.aetheris.auth.dto.UpdateAuthUserRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AdminGuard;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.web.AccessDeniedException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserAdminControllerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void securityChangesRevokeBothTokenTypesAndEntityUpdatesCannotOverwriteVersionCounters() {
        var table = com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "version-test"), AuthUser.class);
        String entityAssignments = table.getAllSqlSet(false, "et.");
        org.junit.jupiter.api.Assertions.assertFalse(entityAssignments.contains("session_version"));
        org.junit.jupiter.api.Assertions.assertFalse(entityAssignments.contains("access_version"));
        for (boolean changeRole : List.of(false, true)) {
            AuthUserService users = mock(AuthUserService.class);
            when(users.getById(2L)).thenReturn(user(2L, "alice", "viewer", "active"));
            var controller = new AuthUserAdminController(users, mock(PasswordEncoder.class), mock(AdminGuard.class));
            controller.update(2L, "admin", null, new UpdateAuthUserRequest(null, "new name", null,
                    null, changeRole ? "admin" : null, null));
            ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(users).update(any(AuthUser.class), wrapper.capture());
            String sqlSet = wrapper.getValue().getSqlSet();
            assertEquals(changeRole, sqlSet.contains("session_version = session_version + 1"));
            assertEquals(changeRole, sqlSet.contains("access_version = access_version + 1"));
        }
    }

    @Test
    void createsLoginAccountWithHashedPassword() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userService.findByUsername("analyst")).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        AuthUserAdminController controller = new AuthUserAdminController(userService, encoder, mock(AdminGuard.class));

        ResponseEntity<?> response = controller.create("admin", null, new CreateAuthUserRequest(
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
        AuthUserAdminController controller = new AuthUserAdminController(userService, encoder, mock(AdminGuard.class));

        ResponseEntity<?> response = controller.update(1L, "admin", null,
                new UpdateAuthUserRequest(null, null, null, null, "admin", "disabled"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(userService, never()).updateById(admin);
    }

    @Test
    void preventsCurrentAdministratorFromDeletingSelf() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthUser admin = user(1L, "admin", "admin", "active");
        when(userService.getById(1L)).thenReturn(admin);
        AuthUserAdminController controller = new AuthUserAdminController(
                userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        ResponseEntity<?> response = controller.delete(1L, "admin", null);

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

    @Test
    void listReturnsAllUsers() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthUser alice = user(1L, "alice", "admin", "active");
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(alice));
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        List<AuthUserResponse> result = controller.list("admin", null);

        assertEquals(1, result.size());
        assertEquals("alice", result.get(0).username());
        assertEquals("admin", result.get(0).role());
    }

    @Test
    void createUserRejectsDuplicateUsername() {
        AuthUserService userService = mock(AuthUserService.class);
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice", "viewer", "active")));
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        ResponseEntity<?> response = controller.create("admin", null, new CreateAuthUserRequest(
                "alice", "pass", null, null, null, "viewer", "active"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(userService, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateOtherUserSucceeds() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthUser target = user(2L, "viewer1", "viewer", "active");
        when(userService.getById(2L)).thenReturn(target);
        when(userService.count(any(Wrapper.class))).thenReturn(2L);
        when(encoder.encode(anyString())).thenReturn("new-hash");
        AuthUserAdminController controller = new AuthUserAdminController(userService, encoder, mock(AdminGuard.class));

        ResponseEntity<?> response = controller.update(2L, "admin", null,
                new UpdateAuthUserRequest("new-pass", "新昵称", "e@b.c", "1", "admin", "active"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("admin", target.getRole());
        assertEquals("new-hash", target.getPasswordHash());
        assertEquals("新昵称", target.getNickname());
        // update 调用包含 null 字段显式置空
        verify(userService).update(any(AuthUser.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateReturns404WhenUserNotFound() {
        AuthUserService userService = mock(AuthUserService.class);
        when(userService.getById(99L)).thenReturn(null);
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        assertEquals(HttpStatus.NOT_FOUND, controller.update(99L, "admin", null, new UpdateAuthUserRequest(null, null, null, null, "admin", "active")).getStatusCode());
    }

    @Test
    void deleteOtherUserSucceeds() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthUser target = user(2L, "viewer1", "viewer", "active");
        when(userService.getById(2L)).thenReturn(target);
        when(userService.count(any(Wrapper.class))).thenReturn(2L);
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        ResponseEntity<?> response = controller.delete(2L, "admin", null);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(userService).removeById(2L);
    }

    @Test
    void deleteReturns404WhenUserNotFound() {
        AuthUserService userService = mock(AuthUserService.class);
        when(userService.getById(99L)).thenReturn(null);
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        assertEquals(HttpStatus.NOT_FOUND, controller.delete(99L, "admin", null).getStatusCode());
    }

    @Test
    void deletePreventsRemovingLastActiveAdmin() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthUser admin = user(1L, "admin", "admin", "active");
        when(userService.getById(1L)).thenReturn(admin);
        when(userService.count(any(Wrapper.class))).thenReturn(1L);
        AuthUserAdminController controller = new AuthUserAdminController(userService, mock(PasswordEncoder.class), mock(AdminGuard.class));

        ResponseEntity<?> response = controller.delete(1L, "other-admin", null);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(userService, never()).removeById(1L);
    }
}
