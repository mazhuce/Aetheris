package com.aetheris.auth.controller;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalSessionControllerTest {
    @Test void accountIdentityStatusAndAccessVersionAllMustMatch() {
        AuthUserService users = mock(AuthUserService.class);
        AuthUser alice = new AuthUser(); alice.setId(42L); alice.setUsername("alice"); alice.setStatus("active");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("missing")).thenReturn(Optional.empty());
        var controller = new InternalSessionController(users);
        assertTrue(controller.check(new InternalSessionController.SessionRequest(42L, "alice", 0)).active());
        assertFalse(controller.check(new InternalSessionController.SessionRequest(7L, "alice", 0)).active());
        assertFalse(controller.check(new InternalSessionController.SessionRequest(42L, "missing", 0)).active());
        alice.setAccessVersion(1L);
        assertFalse(controller.check(new InternalSessionController.SessionRequest(42L, "alice", 0)).active());
        assertTrue(controller.check(new InternalSessionController.SessionRequest(42L, "alice", 1)).active());
        alice.setStatus("disabled");
        assertFalse(controller.check(new InternalSessionController.SessionRequest(42L, "alice", 1)).active());
    }
}
