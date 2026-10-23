package com.aetheris.system.security;

import com.aetheris.security.protocol.*;
import com.aetheris.system.controller.*;
import com.aetheris.system.entity.*;
import com.aetheris.system.service.*;
import com.aetheris.system.web.GlobalExceptionHandler;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real proofs + MVC; local session/nonce doubles, not live auth/PG. */
class SystemV2MvcTest {
    AuditLogService audits;PermissionEventService events;OperationsInboxStore notifications;
    ValidatorFactory validation;MockMvc mvc;
    static final String AUDIT="/internal/v2/audit/logs",EVENT="/internal/v2/audit/permission-events",NOTIFY="/internal/v2/notifications";
    static final String AUDIT_BODY="{\"username\":\"admin\",\"userRole\":\"admin\",\"method\":\"POST\",\"path\":\"/api/system/users\",\"statusCode\":201,\"durationMs\":4,\"clientIp\":\"127.0.0.1\"}";
    static final String EVENT_BODY="{\"username\":\"alice\",\"resourceType\":\"metric\",\"resourceId\":\"1\",\"action\":\"query\",\"decision\":\"DENIED\"}";
    @BeforeEach void setup() {
        audits=mock(AuditLogService.class);events=mock(PermissionEventService.class);notifications=mock(OperationsInboxStore.class);
        validation=Validation.buildDefaultValidatorFactory();var bodies=new SystemInternalRequests(validation.getValidator());
        mvc=MockMvcBuilders.standaloneSetup(new InternalAuditLogController(audits,bodies),new InternalPermissionEventController(events,bodies),new InternalNotificationController(notifications,bodies))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(V2TestFixture.workload(),V2TestFixture.direct()).build();
    }
    @AfterEach void close(){validation.close();}
    MvcResult call(String caller,String path,String operation,String json,String nonce,String user) throws Exception {
        byte[] body=json.getBytes(StandardCharsets.UTF_8);
        var request=post(path).contentType("application/json").content(body).header("X-Aetheris-Workload-Proof",V2TestFixture.workloadProof(caller,path,body,nonce,operation,user));
        if(user!=null)request.header("X-Aetheris-User-Proof",user);
        return mvc.perform(request).andReturn();
    }
    void signed(MvcResult result,String caller,String nonce,int expected) {
        var response=result.getResponse();assertEquals(expected,response.getStatus());
        if(expected==202)assertEquals(0,response.getContentAsByteArray().length);
        V2TestFixture.VERIFIER.verifyResponse(response.getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("system",caller,nonce,expected,BodyHash.sha256(response.getContentAsByteArray())));
    }
    @Test void gatewayAuditAndAuthPermissionEventsRemainAcceptedAndSigned() throws Exception {
        String nonce=Nonce.random();signed(call("gateway",AUDIT,"system.audit.record",AUDIT_BODY,nonce,null),"gateway",nonce,202);
        nonce=Nonce.random();signed(call("auth",EVENT,"system.permission-event.record",EVENT_BODY,nonce,null),"auth",nonce,202);
        verify(audits).save(any(AuditLog.class));verify(events).save(any(PermissionEvent.class));
    }
    @Test void authPermissionEventPreservesFullProtocolRequestIdBoundary() throws Exception {
        for(int length:List.of(64,65,128)) {
            String requestId="r".repeat(length),nonce=Nonce.random();
            String json=EVENT_BODY.substring(0,EVENT_BODY.length()-1)+",\"requestId\":\""+requestId+"\"}";
            signed(call("auth",EVENT,"system.permission-event.record",json,nonce,null),"auth",nonce,202);
        }
        var saved=ArgumentCaptor.forClass(PermissionEvent.class);
        verify(events,times(3)).save(saved.capture());
        assertEquals(List.of("r".repeat(64),"r".repeat(65),"r".repeat(128)),
                saved.getAllValues().stream().map(PermissionEvent::getRequestId).toList());
        String nonce=Nonce.random(),json=EVENT_BODY.substring(0,EVENT_BODY.length()-1)+",\"requestId\":\""+"r".repeat(129)+"\"}";
        signed(call("auth",EVENT,"system.permission-event.record",json,nonce,null),"auth",nonce,400);
        verifyNoMoreInteractions(events);
    }
    @Test void callersCannotWriteAnotherServicesJournal() throws Exception {
        for(String caller:List.of("auth","workflow","quality","ingestion","metadata","serving","ai-agent","system"))
            assertEquals(403,call(caller,AUDIT,"system.audit.record",AUDIT_BODY,Nonce.random(),null).getResponse().getStatus());
        for(String caller:List.of("gateway","workflow","metadata"))assertEquals(403,call(caller,EVENT,"system.permission-event.record",EVENT_BODY,Nonce.random(),null).getResponse().getStatus());
        verifyNoInteractions(audits,events,notifications);
    }
    @Test void notificationsDeriveSourceAndRejectSourceReplacement() throws Exception {
        for(String caller:List.of("workflow","quality","ingestion","metadata")) {
            String nonce=Nonce.random();signed(call(caller,NOTIFY,"system.notification.record",notification("warning","/development/workflow"),nonce,null),caller,nonce,202);
        }
        var saved=ArgumentCaptor.forClass(String.class);verify(notifications,times(4)).record(saved.capture(),any());assertEquals(List.of("workflow","quality","ingestion","metadata"),saved.getAllValues());
        String nonce=Nonce.random();String replaced=notification("warning",null).replace("\"sourceId\":null","\"sourceId\":null,\"source\":\"quality\"");
        signed(call("workflow",NOTIFY,"system.notification.record",replaced,nonce,null),"workflow",nonce,400);
        assertEquals(403,call("gateway",NOTIFY,"system.notification.record","{}",Nonce.random(),null).getResponse().getStatus());
        verifyNoMoreInteractions(notifications);
    }
    @Test void nonceIsClaimedOnceAndEveryActualResponseIsBound() throws Exception {
        String nonce=Nonce.random();signed(call("gateway",AUDIT,"system.audit.record",AUDIT_BODY,nonce,null),"gateway",nonce,202);
        signed(call("gateway",AUDIT,"system.audit.record",AUDIT_BODY,nonce,null),"gateway",nonce,409);verify(audits,times(1)).save(any(AuditLog.class));
    }
    @Test void userProofIsForbiddenForServiceJournals() throws Exception {
        assertEquals(403,call("gateway",AUDIT,"system.audit.record",AUDIT_BODY,Nonce.random(),V2TestFixture.user(1,"admin","gateway")).getResponse().getStatus());verifyNoInteractions(audits);
    }
    @Test void unknownDuplicateTrailingCoercedAndOversizedFieldsAreRejectedWithSigned400() throws Exception {
        for(String json:List.of(AUDIT_BODY.substring(0,AUDIT_BODY.length()-1)+",\"extra\":1}",AUDIT_BODY.replace("\"username\":\"admin\"","\"username\":\"admin\",\"username\":\"other\""),
                AUDIT_BODY+"{}",AUDIT_BODY.replace("201","\"201\""),AUDIT_BODY.replace("\"admin\",\"userRole\"","\""+"a".repeat(65)+"\",\"userRole\""),
                AUDIT_BODY.replace("201","999"),AUDIT_BODY.replace("201","201.5"),AUDIT_BODY.replace("\"POST\"","\"INVALID\""))) {
            String nonce=Nonce.random();signed(call("gateway",AUDIT,"system.audit.record",json,nonce,null),"gateway",nonce,400);
        }
        verifyNoInteractions(audits);
    }
    @Test void unsafeLinksAndUnknownLevelsNeverPersist() throws Exception {
        for(String value:List.of("//outside.example","https://outside.example","/a/../b","/%2foutside","/a?x=1","/a#fragment","/a\\\\b")) {
            String nonce=Nonce.random();signed(call("workflow",NOTIFY,"system.notification.record",notification("warning",value),nonce,null),"workflow",nonce,400);
        }
        String nonce=Nonce.random();signed(call("quality",NOTIFY,"system.notification.record",notification("success",null),nonce,null),"quality",nonce,400);verifyNoInteractions(notifications);
    }
    @Test void eventAndNotificationDtosAlsoRejectUnknownAndDuplicateFields() throws Exception {
        for(String json:List.of(EVENT_BODY.substring(0,EVENT_BODY.length()-1)+",\"extra\":1}",EVENT_BODY.replace("\"username\":\"alice\"","\"username\":\"alice\",\"username\":\"admin\""))) {
            String nonce=Nonce.random();signed(call("auth",EVENT,"system.permission-event.record",json,nonce,null),"auth",nonce,400);
        }
        String notify=notification("info",null);
        for(String json:List.of(notify.substring(0,notify.length()-1)+",\"extra\":1}",notify.replace("\"title\":\"failure\"","\"title\":\"failure\",\"title\":\"other\""))) {
            String nonce=Nonce.random();signed(call("workflow",NOTIFY,"system.notification.record",json,nonce,null),"workflow",nonce,400);
        }
        verifyNoInteractions(events,notifications);
    }
    @Test void operationsBodyCannotSelectRecipientsOrCoerceAnyScalar() throws Exception {
        String valid=notification("info",null);
        for(String json:List.of(valid+"{}",valid.replace("\"sourceId\":null","\"sourceId\":\"12\""),
                valid.replace("\"sourceId\":null","\"sourceId\":1.5"),valid.replace("\"title\":\"failure\"","\"title\":true"),
                valid.replace("\"sourceId\":null","\"sourceId\":null,\"recipient\":\"admin\""),
                valid.replace("\"eventId\":\"","\"eventId\":\"NOT-UUID-"),"{\"recipient\":\"admin\",\"title\":\"old sender\"}")) {
            String nonce=Nonce.random();signed(call("workflow",NOTIFY,"system.notification.record",json,nonce,null),"workflow",nonce,400);
        }
        verifyNoInteractions(notifications);
    }
    @Test void failedDurableCommitNeverReturnsAccepted() throws Exception {
        when(notifications.record(anyString(),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Inbox unavailable"));
        String nonce=Nonce.random();signed(call("metadata",NOTIFY,"system.notification.record",notification("critical",null),nonce,null),"metadata",nonce,503);
    }
    @Test void operationsDtoBoundariesAreTypedAndLimitedBeforeAnyPersistence() throws Exception {
        String valid=notification("info",null);
        var accepted=(com.fasterxml.jackson.databind.node.ObjectNode)V2TestFixture.JSON.readTree(valid);
        accepted.put("title","t".repeat(256));accepted.put("message","m".repeat(1024));accepted.put("sourceId",Long.MAX_VALUE);accepted.put("linkPath","/"+"a".repeat(255));
        String nonce=Nonce.random();signed(call("metadata",NOTIFY,"system.notification.record",V2TestFixture.JSON.writeValueAsString(accepted),nonce,null),"metadata",nonce,202);
        verify(notifications).record(eq("metadata"),any());
        clearInvocations(notifications);
        for(String invalid:List.of(valid.replace("\"title\":\"failure\"","\"title\":\""+"x".repeat(257)+"\""),
                valid.replace("\"message\":null","\"message\":\""+"x".repeat(1025)+"\""),
                valid.replace("\"message\":null","\"message\":\""+"x".repeat(16385)+"\""),
                valid.replace("\"sourceId\":null","\"sourceId\":0"),valid.replace("\"sourceId\":null","\"sourceId\":9223372036854775808"),
                valid.replace("\"level\":\"info\"","\"level\":null"),valid.replace("\"message\":null,",""))) {
            nonce=Nonce.random();signed(call("workflow",NOTIFY,"system.notification.record",invalid,nonce,null),"workflow",nonce,400);
        }
        verifyNoInteractions(notifications);
    }
    String notification(String level,String link) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("eventId",UUID.randomUUID().toString());body.put("sourceId",null);body.put("level",level);
        body.put("title","failure");body.put("message",null);body.put("linkPath",link);return V2TestFixture.JSON.writeValueAsString(body);
    }
    @Test void legacyTokenAdjacentMethodsAndUnfilteredControllersCannotAuthorize() throws Exception {
        for(String path:List.of("/internal/audit/logs","/internal/audit/permission-events","/internal/notifications"))assertEquals(400,mvc.perform(post(path).header("X-Internal-Token","old-token").content(AUDIT_BODY)).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get(AUDIT)).andReturn().getResponse().getStatus());
        var bodies=new SystemInternalRequests(validation.getValidator());
        var naked=MockMvcBuilders.standaloneSetup(new InternalAuditLogController(audits,bodies),new InternalPermissionEventController(events,bodies),new InternalNotificationController(notifications,bodies)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String path:List.of(AUDIT,EVENT,NOTIFY))assertEquals(401,naked.perform(post(path).header("X-Internal-Token","old-token").contentType("application/json").content(AUDIT_BODY)).andReturn().getResponse().getStatus());verifyNoInteractions(audits,events,notifications);
    }
}
