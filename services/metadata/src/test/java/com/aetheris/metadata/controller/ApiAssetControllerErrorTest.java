package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.mapper.ApiAssetMapper;
import com.aetheris.metadata.security.*;
import com.aetheris.metadata.service.ApiAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiAssetControllerErrorTest {
    private final ApiAssetMapper mapper=mock(ApiAssetMapper.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final MetadataSecurity security=mock(MetadataSecurity.class);
    private MockMvc mvc;
    @BeforeEach void setup(){
        when(security.require(any(),anyString(),nullable(Long.class),anyString(),anyMap())).thenReturn(new AetherisIdentity(7L,"owner"));
        mvc=MockMvcBuilders.standaloneSetup(new ApiAssetController(new ApiAssetService(mapper,jdbc),security))
                .setControllerAdvice(new MetadataAccessExceptionHandler()).build();
    }
    private ApiAsset asset(String status){var a=new ApiAsset();a.setId(7L);a.setAssetCode("PUBLIC_TOTAL");a.setAssetName("Public total");a.setApiPath("/api/serving/v1/apis/PUBLIC_TOTAL/query");a.setHttpMethod("POST");a.setStatus(status);a.setTargetType("metric");a.setTargetRef("MISSING");return a;}
    private void noWrite(){verify(mapper,never()).insert(any(ApiAsset.class));verify(mapper,never()).updateById(any(ApiAsset.class));verify(mapper,never()).deleteById(anyLong());}
    @Test void missingPublishedTargetIsAClientErrorWithoutWriting() throws Exception {
        when(mapper.selectById(7L)).thenReturn(asset("registered"));
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isBadRequest());noWrite();
    }
    @Test void repeatedPublicationIsAStateConflictWithoutWriting() throws Exception {
        when(mapper.selectById(7L)).thenReturn(asset("published"));
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isConflict());noWrite();
    }
    @Test void malformedJsonIsAClientErrorWithoutWriting() throws Exception {
        mvc.perform(post("/api/metadata/api-assets").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());noWrite();
    }
    @Test void malformedPathIdIsAClientErrorWithoutWriting() throws Exception {
        mvc.perform(post("/api/metadata/api-assets/not-an-id/publish")).andExpect(status().isBadRequest());noWrite();
    }
    @Test void missingAssetRemains404() throws Exception {
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isNotFound());noWrite();
    }
    @Test void permissionFailureRemains403() throws Exception {
        when(security.require(any(),anyString(),nullable(Long.class),anyString(),anyMap())).thenThrow(new MetadataSecurity.ForbiddenException("没有权限"));
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isForbidden());noWrite();
    }
    @Test void authorizationOutageRemains503() throws Exception {
        when(security.require(any(),anyString(),nullable(Long.class),anyString(),anyMap())).thenThrow(new MetadataSecurity.PermissionServiceUnavailableException("权限暂不可用"));
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isServiceUnavailable());noWrite();
    }
    @Test void unexpectedDatabaseFaultRemainsSafe500() throws Exception {
        when(mapper.selectById(7L)).thenReturn(asset("registered"));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("MISSING"))).thenThrow(new IllegalStateException("do-not-disclose-driver-detail"));
        mvc.perform(post("/api/metadata/api-assets/7/publish")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务内部错误"));noWrite();
    }
}
