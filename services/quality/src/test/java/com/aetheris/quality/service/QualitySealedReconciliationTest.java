package com.aetheris.quality.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualitySealedReconciliationTest {
 @Test void malformedSealedSnapshotsCannotFallBackToCurrentTableOrChangeResult(){for(String snapshot:List.of("{}","{\"schemaVersion\":1,\"datasourceId\":10,\"ruleType\":\"range\",\"expression\":\"amount >= 0\",\"threshold\":2,\"targetTable\":null}","{\"schemaVersion\":1,\"datasourceId\":10,\"ruleType\":\"range\",\"expression\":\"amount >= 0\",\"threshold\":2,\"targetTable\":\"bad;table\"}")){var jdbc=mock(JdbcTemplate.class);Map<String,Object> row=new HashMap<>();row.put("rule_id",8L);row.put("status","unknown");row.put("threshold_value",2L);row.put("manifest_sealed_at",java.time.OffsetDateTime.now());row.put("execution_snapshot",snapshot);when(jdbc.queryForList(anyString(),eq(11L))).thenReturn(List.of(row));when(jdbc.queryForObject(anyString(),eq(Long.class),eq(11L))).thenReturn(0L);assertThrows(IllegalArgumentException.class,()->new ExecutionRecoveryService(jdbc).reconcile(11L,"failed",true,"Remote query inspected and proven stopped","alice",3L));verify(jdbc,never()).update(anyString(),any(Object[].class));verify(jdbc,never()).queryForObject(anyString(),eq(String.class),any());}}
}
