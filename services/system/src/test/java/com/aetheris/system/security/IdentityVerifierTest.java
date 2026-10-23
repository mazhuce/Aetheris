package com.aetheris.system.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class IdentityVerifierTest {
 @Test void verifiesGatewaySignedIdentity(){var request=new MockHttpServletRequest("GET","/api/system/activity");V2TestFixture.trusted(42,"alice").postProcessRequest(request);var identity=new IdentityVerifier().verify(request);assertEquals(42L,identity.uid());assertEquals("alice",identity.username());}
 @Test void rejectsIdentityWithoutPositiveUid(){assertThrows(RuntimeException.class,()->V2TestFixture.user(0,"alice","system"));}
 @Test void rejectsWrongLegacyTokenSignature(){assertNull(new IdentityVerifier("another-token",300000).verify(IdentityVerifierTestHelper.sign(42L,"alice","wrong-token")));}
 @Test void rejectsRawUserProofWithoutFilter(){var request=new MockHttpServletRequest();request.addHeader("X-Aetheris-User-Proof",V2TestFixture.user(42,"alice","system"));assertNull(new IdentityVerifier().verify(request));}
 @Test void rejectsStaleTimestamp() throws Exception {var request=new MockHttpServletRequest("GET","/api/system/activity");request.addHeader("X-Aetheris-User-Proof",V2TestFixture.userAt(42,"alice","system",V2TestFixture.CLOCK.instant().getEpochSecond()-600));var response=new MockHttpServletResponse();V2TestFixture.direct().doFilter(request,response,(a,b)->fail("Expired proof reached chain"));assertEquals(401,response.getStatus());}
 @Test void rejectsMalformedValues(){var verifier=new IdentityVerifier();assertNull(verifier.verify((String)null));assertNull(verifier.verify(""));assertNull(verifier.verify("a.b.c"));}
 @Test void legacyConfigurationCannotEnableHmac(){var verifier=new IdentityVerifier("",300000);assertFalse(verifier.isConfigured());assertNull(verifier.verify(IdentityVerifierTestHelper.sign(42L,"alice","old-token")));}
}
