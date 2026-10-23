package com.aetheris.quality.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServiceIdentitySignerTest {
 @Test void retiredSignerNeverCreatesAdminOrLegacyProof(){for(String name:new String[]{"admin","quality-reader",""}){var signer=new ServiceIdentitySigner("01234567890123456789012345678901",name);assertThrows(IllegalStateException.class,signer::sign);assertThrows(IllegalStateException.class,()->signer.sign(name,1700000000000L));}}
 @Test void retiredSignerIsNotAnActiveSpringComponent(){assertFalse(ServiceIdentitySigner.class.isAnnotationPresent(org.springframework.stereotype.Component.class));}
}
