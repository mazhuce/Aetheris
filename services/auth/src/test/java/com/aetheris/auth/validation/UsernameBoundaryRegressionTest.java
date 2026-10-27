package com.aetheris.auth.validation;

import com.aetheris.auth.config.DataInitializer;
import com.aetheris.auth.controller.AuthController;
import com.aetheris.auth.dto.*;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsernameBoundaryRegressionTest {
    @Test void dtoCannotCreateOrLoginAUsernameRejectedByV2Proofs() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            for(String name:List.of("ad min","ad\u00a0min","ad\u200bmin"," admin","admin ")) {
                assertFalse(factory.getValidator().validate(new CreateAuthUserRequest(name,"long-test-password",null,null,null,"viewer","active")).isEmpty(),"Create must reject incompatible username");
                assertFalse(factory.getValidator().validate(new LoginRequest(name,"password")).isEmpty(),"Login must reject incompatible username");
            }
        }
    }
    @Test void bootstrapRejectsFormatCharactersBeforeSavingAnyAccount() {
        var users=mock(AuthUserService.class);when(users.count()).thenReturn(0L);var encoder=mock(PasswordEncoder.class);
        var initializer=new DataInitializer(users,encoder,"ad\u200bmin","long-test-password","Administrator");
        assertThrows(IllegalStateException.class,initializer::run);verify(users,never()).save(any());verifyNoInteractions(encoder);
    }
    @Test void loginMustNotTrimIntoADifferentExistingAccount() {
        var users=mock(AuthUserService.class);var encoder=mock(PasswordEncoder.class);var jwt=mock(JwtService.class);
        var user=new AuthUser();user.setId(1L);user.setUsername("admin");user.setPasswordHash("hash");user.setRole("admin");user.setStatus("active");
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));when(encoder.matches("password","hash")).thenReturn(true);
        when(jwt.getExpirationMs()).thenReturn(60_000L);when(jwt.getRefreshExpirationMs()).thenReturn(600_000L);
        var controller=new AuthController(users,encoder,jwt,new LoginAttemptService(), mock(com.aetheris.auth.service.PermissionVersionService.class), mock(BrowserSessionService.class));
        assertThrows(IllegalArgumentException.class,()->controller.login(new LoginRequest(" admin ","password")));
        verify(users,never()).findByUsername(anyString());verifyNoInteractions(encoder,jwt);
    }
}
