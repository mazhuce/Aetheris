package com.aetheris.serving.security;

import com.aetheris.serving.service.*;
import com.aetheris.serving.dto.*;
import com.aetheris.serving.config.ServingV2SecurityConfiguration;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedKeyAdmissionRedTest {
    @Test void usernameOnlyCreationCannotIssueCredentials() {
        var jdbc=mock(JdbcTemplate.class);
        assertThrows(ServingSecurity.UnauthorizedException.class,()->new ApiKeyService(jdbc).create(new ApiKeyCreateRequest("report",60,null),"admin","admin"));
        verifyNoInteractions(jdbc);
    }
    @Test void usernameOnlyApplicationCannotChooseItsIdentity() {
        var jdbc=mock(JdbcTemplate.class);
        assertThrows(ServingSecurity.UnauthorizedException.class,()->new ApiKeyApplicationService(jdbc,mock(ApiKeyService.class)).request(new ApiKeyApplicationRequest("report","reason",60,null),"admin"));
        verifyNoInteractions(jdbc);
    }
    @Test void onlyMetadataWorkloadCanReachTheExactRedeemRoute() throws Exception {
        try(var f=new V2ServingFixture()) {
            var installation=mock(ProtocolInstallation.class);when(installation.verifier()).thenReturn(f.verifier);when(installation.workloadSigner()).thenReturn(f.servingSigner);
            var nonces=mock(PostgresNonceStore.class);when(nonces.claim(anyString(),anyString(),anyLong())).thenReturn(true);
            var filter=new ServingV2SecurityConfiguration().servingWorkloadProofFilter(installation,nonces,f.sessions()).getFilter();
            byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);String path="/internal/v2/published-key-admissions/redeem";long now=f.clock.instant().getEpochSecond();
            var proof=new WorkloadRequestProof(2,"metadata","serving","POST",path,BodyHash.sha256(bytes),"a".repeat(32),now,now+30,"serving.published-key.redeem",BodyHash.userProof(null));
            var request=new MockHttpServletRequest("POST",path);request.setContent(bytes);request.addHeader(SignedHttpClient.REQUEST_PROOF,f.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(proof));
            var response=new MockHttpServletResponse();var count=new AtomicInteger();filter.doFilter(request,response,(r,s)->count.incrementAndGet());
            assertEquals(1,count.get());assertEquals(200,response.getStatus());assertNotNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));
        }
    }
}
