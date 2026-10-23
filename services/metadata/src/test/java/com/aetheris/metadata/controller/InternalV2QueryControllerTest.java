package com.aetheris.metadata.controller;

import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalV2QueryControllerTest {
    final MetadataProofFixture proof=new MetadataProofFixture();
    final InternalAgentToolController agent=mock(InternalAgentToolController.class);
    final InternalTableQueryController tables=mock(InternalTableQueryController.class);
    final InternalSemanticMetricController metrics=mock(InternalSemanticMetricController.class);
    final Set<String> used=ConcurrentHashMap.newKeySet();final AtomicBoolean sessionActive=new AtomicBoolean(true);
    jakarta.validation.ValidatorFactory validators;MockMvc mvc;
    @BeforeEach void setup(){
        validators=Validation.buildDefaultValidatorFactory();
        mvc=MockMvcBuilders.standaloneSetup(new InternalV2QueryController(agent,tables,metrics,validators.getValidator()))
                .setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .addFilters(proof.filter((c,n,e)->used.add(c+":"+n),(uid,name,av)->uid==42&&name.equals("alice")&&av==3&&sessionActive.get())).build();
        when(agent.searchTables(any(),any(),any())).thenReturn(List.of(Map.of("id",7)));
        doReturn(ResponseEntity.ok(Map.of("tableId",7))).when(agent).describeTable(any(),anyLong());
        when(agent.query(any(),anyMap())).thenReturn(ResponseEntity.ok(Map.of("rowCount",1)));
        when(tables.query(any(),anyLong(),any())).thenReturn(ResponseEntity.ok(Map.of("rowCount",1)));
        when(metrics.list(any(),any())).thenReturn(List.of());when(metrics.query(any(),anyString(),any())).thenReturn(Map.of("rowCount",1));
    }
    @AfterEach void close(){validators.close();}
    MockHttpServletRequestBuilder call(String caller,String suffix,String operation,String body){return call(caller,suffix,operation,body,proof.user(caller));}
    MockHttpServletRequestBuilder call(String caller,String suffix,String operation,String body,String user){
        String path="/internal/v2/"+suffix;byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        var request=post(path).contentType("application/json").content(raw)
                .header("X-Aetheris-Workload-Proof",proof.request(caller,path,operation,raw,user));
        if(user!=null)request.header("X-Aetheris-User-Proof",user);return request;
    }
    @Test void sixExactPostContractsPreserveRawUserBindingAndSignActualResponses() throws Exception {
        var cases=List.of(new String[]{"ai-agent","agent/tables/search","metadata.agent.search","{\"keyword\":\"订单\"}"},
                new String[]{"ai-agent","agent/tables/describe","metadata.agent.describe","{\"tableId\":7}"},
                new String[]{"serving","agent/query","metadata.user.sql-query","{\"datasourceId\":3,\"sql\":\"SELECT\\n1\"}"},
                new String[]{"serving","tables/query","metadata.user.table-query","{\"tableId\":7,\"query\":{\"columns\":[\"id\"],\"limit\":10}}"},
                new String[]{"ai-agent","semantic/metrics/list","metadata.agent.metric-list","{}"},
                new String[]{"serving","semantic/metrics/query","metadata.user.metric-query","{\"metricCode\":\"订单.净额\",\"query\":{\"limit\":5}}"});
        for(var item:cases){
            var input=call(item[0],item[1],item[2],item[3]);String token=(String)input.buildRequest(new org.springframework.mock.web.MockServletContext()).getHeader("X-Aetheris-Workload-Proof");
            String user=input.buildRequest(new org.springframework.mock.web.MockServletContext()).getHeader("X-Aetheris-User-Proof");
            var request=proof.verifier.verifyRequest(token,new ExpectedRequest("metadata","POST","/internal/v2/"+item[1],
                    BodyHash.sha256(item[3].getBytes(StandardCharsets.UTF_8)),item[2],BodyHash.userProof(user))).proof();
            var result=mvc.perform(input).andExpect(status().isOk()).andReturn();
            proof.verifier.verifyResponse(result.getResponse().getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("metadata",item[0],request.nonce(),
                    200,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        }
        verify(agent).query(any(),eq(Map.of("datasourceId",3L,"sql","SELECT\n1")));
        verify(metrics).query(any(),eq("订单.净额"),any());
    }
    @Test void unknownDuplicateTrailingScalarAndBodySubjectAreRejectedBeforeDelegates() throws Exception {
        for(String body:List.of("null","{}","{\"datasourceId\":3,\"sql\":\"SELECT 1\",\"username\":\"admin\"}",
                "{\"datasourceId\":3,\"datasourceId\":4,\"sql\":\"SELECT 1\"}","{\"datasourceId\":3,\"sql\":\"SELECT 1\"} {}",
                "{\"datasourceId\":3.5,\"sql\":\"SELECT 1\"}","{\"datasourceId\":3,\"sql\":true}"))
            mvc.perform(call("ai-agent","agent/query","metadata.user.sql-query",body)).andExpect(status().isBadRequest()).andExpect(header().exists("X-Aetheris-Response-Proof"));
        mvc.perform(call("serving","tables/query","metadata.user.table-query","{\"tableId\":7,\"query\":{\"limit\":101}}" )).andExpect(status().isBadRequest());
        verifyNoInteractions(agent,tables,metrics);
    }
    @Test void callerOperationAudienceAndMissingUserCannotReachQueries() throws Exception {
        mvc.perform(call("workflow","agent/query","metadata.user.sql-query","{}" )).andExpect(status().isForbidden());
        mvc.perform(call("serving","agent/tables/search","metadata.agent.search","{}" )).andExpect(status().isForbidden());
        mvc.perform(call("ai-agent","agent/query","metadata.agent.search","{}" )).andExpect(status().isUnauthorized());
        mvc.perform(call("ai-agent","agent/query","metadata.user.sql-query","{}",null)).andExpect(status().isUnauthorized());
        mvc.perform(call("ai-agent","agent/query","metadata.user.sql-query","{}",proof.user("serving"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(agent,tables,metrics);
    }
    @Test void legacyQueryAndGetOrQueryStringContractsStayClosed() throws Exception {
        mvc.perform(post("/internal/agent/query").header("X-Internal-Token","old").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v2/agent/tables/search")).andExpect(status().isNotFound());
        mvc.perform(call("ai-agent","agent/tables/search","metadata.agent.search","{}").queryParam("keyword","x")).andExpect(status().isBadRequest());
        verifyNoInteractions(agent,tables,metrics);
    }
    @Test void replayOrSessionRevocationCannotExecuteTheDelegateAgain() throws Exception {
        var request=call("ai-agent","agent/tables/search","metadata.agent.search","{}");
        mvc.perform(request).andExpect(status().isOk());mvc.perform(request).andExpect(status().isConflict());
        verify(agent,times(1)).searchTables(any(),any(),any());sessionActive.set(false);
        mvc.perform(call("ai-agent","agent/tables/search","metadata.agent.search","{}")).andExpect(status().isForbidden());
        verifyNoMoreInteractions(agent);
    }
}
