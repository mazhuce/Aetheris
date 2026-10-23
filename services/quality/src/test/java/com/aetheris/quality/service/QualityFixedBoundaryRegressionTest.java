package com.aetheris.quality.service;
import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.security.ServiceIdentitySigner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityFixedBoundaryRegressionTest {
 @Test void bareUsernameAndMissingProductionStoreCannotExecute() {
  var rules=mock(QualityRuleService.class);var rule=new QualityRule();rule.setId(1L);rule.setDatasourceId(7L);rule.setTargetTable("business.t");rule.setRuleType("sql");rule.setExpression("SELECT 0");rule.setThreshold(0);rule.setStatus("enabled");when(rules.getById(1L)).thenReturn(rule);
  var client=mock(QualityQueryClient.class);var results=mock(QualityCheckResultService.class);
  var service=new QualityExecutionService(rules,results,client,mock(NotificationClient.class));
  assertThrows(IllegalStateException.class,()->service.execute(1L,"admin"));
 }
 @Test void oldServicePrincipalCannotProduceAnAdminIdentity() {
  var signer=new ServiceIdentitySigner("01234567890123456789012345678901","admin");
  assertThrows(IllegalStateException.class,signer::sign);
 }
}
