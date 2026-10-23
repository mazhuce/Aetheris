package com.aetheris.quality.service;
import com.aetheris.quality.fixedexecution.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityFixedDefinitionTest {
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 final QualityExecutionStore store=new QualityExecutionStore(jdbc,mock(PlatformTransactionManager.class),mock(ExecutionRecoveryService.class),mock(QualityCheckResultService.class));
 QualityExecutionStore.Definition definition(String json){return new QualityExecutionStore.Definition(8,1,"a".repeat(64),json,null,1,0,null,null,"enabled",null);}
 @Test void projectionIsExactlyVersionOneAndValidatesBeforeDatabaseOrRemoteEffects(){String json="{\"schemaVersion\":1,\"datasourceId\":8,\"targetTable\":\"ods.orders\",\"ruleType\":\"range\",\"expression\":\"amount >= 0\",\"threshold\":0}";assertNotNull(store.executableDefinition(definition(json)));assertNotNull(store.executableDefinition(definition(json.replace("\"threshold\":0","\"threshold\":null"))));for(String invalid:java.util.List.of(json.replace("\"schemaVersion\":1","\"schemaVersion\":2"),json.replace("\"datasourceId\":8","\"datasourceId\":0"),json.replace("\"threshold\":0","\"threshold\":-1"),json.replace("\"threshold\":0","\"threshold\":0.5"),json.replace("\"threshold\":0","\"threshold\":2147483648"),json.replace("amount >= 0","pg_sleep(1) = 0"),json.replace("\"threshold\":0","\"threshold\":0,\"username\":\"admin\""))){assertThrows(IllegalArgumentException.class,()->store.executableDefinition(definition(invalid)));}verifyNoInteractions(jdbc);}
}
