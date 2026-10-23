package com.aetheris.auth.validation;

import com.aetheris.auth.config.DataInitializer;
import com.aetheris.auth.controller.AuthUserAdminController;
import com.aetheris.auth.dto.*;
import com.aetheris.auth.service.*;
import com.aetheris.security.protocol.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsernameRulesTest {
    static List<String> invalid() {return Arrays.asList(null,""," ","a b"," a","a ","a\tb","a\nb","a\u0000b","a\u0085b",
            "a\u00a0b","a\u2007b","a\u202fb","a\u3000b","a\u200bb","a\u200db","a\ufeffb","a\u2066b","a\uD800b","a\uDC00b","a|b","a".repeat(65));}
    @Test void allEntryDtosUseTheSameUnicodeAndLengthPredicate() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            for(String value:invalid()) {
                assertFalse(UsernameRules.isValid(value));assertThrows(IllegalArgumentException.class,()->UsernameRules.requireValid(value));
                assertFalse(factory.getValidator().validate(new LoginRequest(value,"password")).isEmpty());
                assertFalse(factory.getValidator().validate(new CreateAuthUserRequest(value,"long-test-password",null,null,null,"viewer","active")).isEmpty());
            }
            for(String value:List.of("admin","Alice_01.-","管理员","é","e\u0301","用户😀","a".repeat(64),"😀".repeat(32))) {
                assertSame(value,UsernameRules.requireValid(value));
                assertTrue(factory.getValidator().validate(new LoginRequest(value,"password")).isEmpty());
                assertTrue(factory.getValidator().validate(new CreateAuthUserRequest(value,"long-test-password",null,null,null,"viewer","active")).isEmpty());
            }
            assertNotEquals(UsernameRules.requireValid("é"),UsernameRules.requireValid("e\u0301"),"No implicit Unicode normalization");
        }
    }
    @Test void everyAcceptedBoundaryNameCanActuallyBeSignedAndVerifiedByTheSharedProtocol() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("user-policy-test",KeyPurpose.USER,"gateway",pair.getPublic())));
        Clock clock=Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC);
        var signer=new ProtocolSigner("user-policy-test",KeyPurpose.USER,"gateway",pair.getPrivate(),registry,clock,TimePolicy.defaults());var verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
        for(String name:List.of("admin","管理员","é","e\u0301","用户😀","a".repeat(64),"😀".repeat(32))) {
            assertTrue(UsernameRules.isValid(name));String compact=signer.sign(new UserProof(2,"gateway","auth",7,name,0,1800000000,1800000120,"username-test"));
            assertEquals(name,verifier.verifyUser(compact,"auth").proof().username());
        }
        for(String name:invalid()) {
            if(name==null||name.equals("a|b")||name.length()>64)continue;
            assertThrows(ProtocolException.class,()->signer.sign(new UserProof(2,"gateway","auth",7,name,0,1800000000,1800000120,"username-test")));
        }
        // Auth intentionally remains a strict subset: historic pipe ban and 64 UTF-16 units.
        for(String name:List.of("a|b","a".repeat(65))) {
            assertFalse(UsernameRules.isValid(name));assertDoesNotThrow(()->signer.sign(new UserProof(2,"gateway","auth",7,name,0,1800000000,1800000120,"username-test")));
        }
    }
    @Test void initialAdministratorFailsBeforePasswordEncodingOrAnySave() {
        for(String name:invalid()) {
            var users=mock(AuthUserService.class);when(users.count()).thenReturn(0L);var encoder=mock(PasswordEncoder.class);
            var initializer=new DataInitializer(users,encoder,name,"long-test-password","Administrator");
            var failure=assertThrows(IllegalStateException.class,initializer::run);assertTrue(failure.getMessage().startsWith("INITIAL_ADMIN_USERNAME"));
            verify(users,never()).save(any());verifyNoInteractions(encoder);
        }
    }
    @Test void directCreateCannotBypassBeanValidationAndSaveAnIncompatibleName() {
        for(String name:invalid()) {
            var users=mock(AuthUserService.class);var encoder=mock(PasswordEncoder.class);
            var controller=new AuthUserAdminController(users,encoder,mock(AdminGuard.class));
            assertThrows(IllegalArgumentException.class,()->controller.create("admin",null,new CreateAuthUserRequest(name,"long-test-password",null,null,null,"viewer","active")));
            verifyNoInteractions(users,encoder);
        }
    }
    @Test void existingAccountsAreNeitherReadNorRewrittenByBootstrapCompatibilityPolicy() {
        var users=mock(AuthUserService.class);when(users.count()).thenReturn(1L);var encoder=mock(PasswordEncoder.class);
        assertDoesNotThrow(()->new DataInitializer(users,encoder,"old name","unused","unused").run());
        verify(users).count();verifyNoMoreInteractions(users);verifyNoInteractions(encoder);
    }
}
