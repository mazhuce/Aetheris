package com.aetheris.serving.security;

import com.aetheris.serving.config.ServingV2SecurityConfiguration;
import com.aetheris.serving.controller.*;
import com.aetheris.serving.service.*;
import com.aetheris.serving.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublishedKeyWireTest {
    static byte[] raw(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static final String PATH="/internal/v2/published-key-admissions/redeem",OP="serving.published-key.redeem";
    static byte[] reference(){return PublishedKeyJson.bytes(new PublishedKeyAdmissionService.Reference(UUID.randomUUID(),"a".repeat(64),"b".repeat(64)).map());}
    static String proof(V2ServingFixture f,String caller,String path,String operation,byte[] body,String user,String nonce){long now=f.clock.instant().getEpochSecond();return f.signer(caller,KeyPurpose.WORKLOAD,caller).sign(new WorkloadRequestProof(2,caller,"serving","POST",path,BodyHash.sha256(body),nonce,now,now+30,operation,BodyHash.userProof(user)));}
    @Test void cryptoFilterMvcSignsResponsesAndBindsCallerOperationAndSingleNonce()throws Exception {
        try(var f=new V2ServingFixture()){
            var service=mock(PublishedKeyAdmissionService.class);when(service.redeem(any())).thenAnswer(call->{PublishedKeyAdmissionService.Reference.parse(call.getArgument(0));return new PublishedKeyAdmissionService.Redeemed("{}","a".repeat(64));});
            Set<String> nonces=ConcurrentHashMap.newKeySet();var filter=new WorkloadProofFilter("serving",f.verifier,f.servingSigner,ServingV2SecurityConfiguration.workloadRoutes(),(c,n,e)->nonces.add(c+":"+n),f.sessions(),f.clock,TimePolicy.defaults());
            var mvc=MockMvcBuilders.standaloneSetup(new PublishedKeyAdmissionController(service)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
            byte[] body=reference();String nonce=UUID.randomUUID().toString(),valid=proof(f,"metadata",PATH,OP,body,null,nonce);
            var response=mvc.perform(post(PATH).content(body).contentType(MediaType.APPLICATION_JSON).header(SignedHttpClient.REQUEST_PROOF,valid)).andExpect(status().isOk()).andReturn().getResponse();
            f.verifier.verifyResponse(response.getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("serving","metadata",nonce,200,BodyHash.sha256(response.getContentAsByteArray())));
            assertEquals(Set.of("canonicalAdmission","admissionSha256"),PublishedKeyJson.JSON.readValue(response.getContentAsByteArray(),Map.class).keySet());
            response=mvc.perform(post(PATH).content(body).header(SignedHttpClient.REQUEST_PROOF,valid)).andExpect(status().isConflict()).andReturn().getResponse();
            f.verifier.verifyResponse(response.getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("serving","metadata",nonce,409,BodyHash.sha256(response.getContentAsByteArray())));
            for(String caller:List.of("serving","workflow","system"))mvc.perform(post(PATH).content(body).header(SignedHttpClient.REQUEST_PROOF,proof(f,caller,PATH,OP,body,null,UUID.randomUUID().toString()))).andExpect(status().isForbidden());
            mvc.perform(post(PATH).content(body).header(SignedHttpClient.REQUEST_PROOF,proof(f,"metadata",PATH,"auth.session.check",body,null,UUID.randomUUID().toString()))).andExpect(status().isUnauthorized());
            String user=f.token();mvc.perform(post(PATH).content(body).header(SignedHttpClient.USER_PROOF,user).header(SignedHttpClient.REQUEST_PROOF,proof(f,"metadata",PATH,OP,body,user,UUID.randomUUID().toString()))).andExpect(status().isForbidden());
            mvc.perform(post(PATH).content(body).header("X-Internal-Token","old")).andExpect(status().isUnauthorized());
            assertThrows(ProtocolException.class,()->proof(f,"metadata",PATH+"/",OP,body,null,UUID.randomUUID().toString()));
            mvc.perform(post(PATH+"-extra").content(body).header(SignedHttpClient.REQUEST_PROOF,proof(f,"metadata",PATH+"-extra",OP,body,null,UUID.randomUUID().toString()))).andExpect(status().isNotFound());
            byte[] invalid=raw("{\"username\":\"admin\"}");response=mvc.perform(post(PATH).content(invalid).header(SignedHttpClient.REQUEST_PROOF,proof(f,"metadata",PATH,OP,invalid,null,UUID.randomUUID().toString()))).andExpect(status().isBadRequest()).andReturn().getResponse();assertNotNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));
            verify(service,times(2)).redeem(any()); // one successful call, one strict DTO rejection
            assertTrue(f.requests.isEmpty());
        }
    }
    @Test void controllerWithoutTheFilterCannotTrustCallerHeadersOrOrdinaryAttributes()throws Exception {
        var service=mock(PublishedKeyAdmissionService.class);var mvc=MockMvcBuilders.standaloneSetup(new PublishedKeyAdmissionController(service)).build();
        mvc.perform(post(PATH).content(reference()).header("X-Service-Name","metadata").header("X-User-Name","admin")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void ownerTupleCallsAreLiveAndDependencyFailuresStay503()throws Exception {
        try(var f=new V2ServingFixture()){
            var owners=new ApiKeyOwnerValidator(f.auth);f.request(request->assertEquals(new ApiKeyOwner(42,"alice",3),owners.current(TrustedUser.require(request))));
            f.active.set(false);assertThrows(ServingSecurity.ForbiddenException.class,()->owners.requireActive(new ApiKeyOwner(42,"alice",3)));f.active.set(true);
            assertThrows(ServingSecurity.ForbiddenException.class,()->owners.requireActive(new ApiKeyOwner(42,"alice",2)));
            f.fault="unsigned";assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->owners.requireActive(new ApiKeyOwner(42,"alice",3))).getStatusCode().value());
            assertTrue(f.requests.stream().filter(r->r.operation().equals("auth.session.check")).allMatch(r->r.userProof()==null));
        }
    }
    @Test void publicWireRejectsDuplicateUnknownTrailingCoercionAndOversizedBeforeAnyDatabaseOrMetadata()throws Exception {
        var metadata=mock(MetadataQueryClient.class);var security=mock(ServingSecurity.class);var keys=mock(ApiKeyService.class);var admissions=mock(PublishedKeyAdmissionService.class);var controller=new VersionedServingController(metadata,security,mock(ServingRateLimiter.class),keys);controller.setAdmissions(admissions);
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String invalid:List.of("{\"limit\":1,\"limit\":2}","{\"owner\":\"admin\"}","{}{}","{\"limit\":\"1\"}","{\"endDate\":\"2025-02-29\"}"," ".repeat(16385)))mvc.perform(post("/api/serving/v1/apis/ORDERS/query").contentType(MediaType.APPLICATION_JSON).header("X-API-Key","key").content(invalid)).andExpect(status().isBadRequest());
        verifyNoInteractions(metadata,keys,admissions);
    }
    @Test void publicKeyUsesOnlyItsAdmissionAndAuditsTheActualDependencyStatus()throws Exception {
        try(var f=new V2ServingFixture()){
            var metadata=mock(MetadataQueryClient.class);var security=mock(ServingSecurity.class);var keys=mock(ApiKeyService.class);var admissions=mock(PublishedKeyAdmissionService.class);var usage=mock(ApiUsageService.class);var rate=mock(ServingRateLimiter.class);
            var controller=new VersionedServingController(metadata,security,rate,keys,usage);controller.setAdmissions(admissions);
            var owner=new ApiKeyOwner(42,"alice",3);var ref=new PublishedKeyAdmissionService.Reference(UUID.randomUUID(),"a".repeat(64),"b".repeat(64));
            var asset=Map.<String,Object>of("id",11L,"assetCode","ORDERS","version","v1","targetType","table","targetRef","13");
            when(keys.authenticate("fixture-key")).thenReturn(Optional.of(new ApiKeyService.ApiKeyPrincipal(8,"alice","redacted",60,42L,3L)));when(rate.allow("alice","api:ORDERS")).thenReturn(true);when(metadata.publishedApiAsset("ORDERS")).thenReturn(asset);
            when(admissions.create(eq("fixture-key"),eq(asset),any(),any())).thenReturn(new PublishedKeyAdmissionService.Admission(ref,8,owner,"safe-request"));when(metadata.publishedKeyQuery(ref)).thenReturn(Map.of("rowCount",2));
            var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).addFilters(ServingV2SecurityConfiguration.directFilter(f.verifier,f.sessions())).build();
            mvc.perform(post("/api/serving/v1/apis/ORDERS/query").header("X-API-Key","fixture-key").header("X-User-Name","admin").header(SignedHttpClient.USER_PROOF,f.token("metadata",42,3)).content("{}")).andExpect(status().isOk());
            verifyNoInteractions(security);verify(metadata,never()).queryTable(any(),any(),any());verify(metadata,never()).queryMetric(any(),any(),any());verify(usage).record(eq("ORDERS"),eq("alice"),eq("api_key"),eq(200),anyLong(),isNull(),eq(new ApiUsageService.Context(8,ref.admissionId(),"safe-request",42)));
            when(metadata.publishedKeyQuery(ref)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Dependency unavailable"));
            mvc.perform(post("/api/serving/v1/apis/ORDERS/query").header("X-API-Key","fixture-key").content("{}")).andExpect(status().isServiceUnavailable());
            verify(usage).record(eq("ORDERS"),eq("alice"),eq("api_key"),eq(503),anyLong(),anyString(),eq(new ApiUsageService.Context(8,ref.admissionId(),"safe-request",42)));
        }
    }
}
