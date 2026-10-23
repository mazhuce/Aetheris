package com.aetheris.auth.security;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionValidatorTest {
    @Test void alwaysQueriesActualUidNameStatusAndVersionWithoutCaching() {
        var users=mock(AuthUserService.class);var validator=new AuthSessionValidator(users);
        var user=new AuthUser();user.setId(7L);user.setUsername("alice");user.setStatus("active");user.setAccessVersion(4L);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        assertTrue(validator.isActive(7,"alice",4));
        user.setAccessVersion(5L);assertFalse(validator.isActive(7,"alice",4));
        assertTrue(validator.isActive(7,"alice",5));
        user.setStatus("disabled");assertFalse(validator.isActive(7,"alice",5));
        user.setStatus("active");user.setId(8L);assertFalse(validator.isActive(7,"alice",5));
        user.setId(7L);user.setUsername("other");assertFalse(validator.isActive(7,"alice",5));
        verify(users,times(6)).findByUsername("alice");
    }
    @Test void invalidBindingRejectsAndStorageFailureDoesNotFallback() {
        var users=mock(AuthUserService.class);var validator=new AuthSessionValidator(users);
        assertFalse(validator.isActive(0,"alice",0));assertFalse(validator.isActive(7," ",0));assertFalse(validator.isActive(7,"alice",-1));
        verifyNoInteractions(users);
        when(users.findByUsername("alice")).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class,()->validator.isActive(7,"alice",0));
    }
}
