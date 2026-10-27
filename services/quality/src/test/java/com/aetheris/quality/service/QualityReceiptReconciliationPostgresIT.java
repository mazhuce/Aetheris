package com.aetheris.quality.service;

import com.aetheris.quality.entity.*;
import com.aetheris.quality.fixedexecution.*;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.TrustedUser;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real ordinary quality role and transactional state; workflow parent and metadata readback are controlled peers. */
class QualityReceiptReconciliationPostgresIT {
    @Configuration @EnableTransactionManagement static class Transactions{}
    AnnotationConfigApplicationContext context;JdbcTemplate jdbc;QualityExecutionStore store;ExecutionRecoveryService recovery;
    QualityCheckResultService results;QualityQueryClient metadata;QualityReceiptReconciler reconciler;QualityActor actor;
    final List<Long> ownRules=new ArrayList<>();
    @BeforeEach void setup(){
        String url=Objects.requireNonNull(System.getenv("AETHERIS_QUALITY_CHILD_TEST_URL"));assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_api_key_audit"));
        var source=new DriverManagerDataSource(url,"aetheris_quality",Objects.requireNonNull(System.getenv("AETHERIS_QUALITY_CHILD_TEST_PASSWORD")));jdbc=spy(new JdbcTemplate(source));
        assertEquals("aetheris_quality",jdbc.queryForObject("SELECT current_user",String.class));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM workflow.task"));
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.registerBean(ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));context.refresh();recovery=context.getBean(ExecutionRecoveryService.class);
        results=mock(QualityCheckResultService.class);when(results.getById(any())).thenAnswer(i->jdbc.queryForObject("SELECT * FROM quality.quality_check_result WHERE id=?",new BeanPropertyRowMapper<>(QualityCheckResult.class),((Number)i.getArgument(0)).longValue()));
        store=new QualityExecutionStore(jdbc,context.getBean(PlatformTransactionManager.class),recovery,results);metadata=mock(QualityQueryClient.class);reconciler=new QualityReceiptReconciler(jdbc,metadata,recovery,results);
        var user=mock(TrustedUser.class);when(user.proof()).thenReturn(new UserProof(2,"gateway","quality",42,"fixture-user",3,1,61,"quality-receipt-fixture"));actor=QualityActor.manual(user);
    }
    @AfterEach void close(){try{if(jdbc!=null)for(long id:ownRules)jdbc.update("UPDATE quality.quality_check_result SET status='error',failure_code='CONTROLLED_FIXTURE_RETIRED' WHERE rule_id=? AND status='unknown'",id);}finally{if(context!=null)context.close();}}
    WorkflowQualityParent parent(){
        long id=jdbc.queryForObject("INSERT INTO quality.quality_rule(name,datasource_id,target_table,rule_type,expression,threshold,status) VALUES(?,910001,'business.orders','sql','SELECT COUNT(*) FROM business.orders',0,'enabled') RETURNING id",Long.class,"receipt-"+UUID.randomUUID());var rule=store.approve(store.definition(id),actor);
        ownRules.add(id);
        var d=FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("taskType","quality").put("qualityRuleId",id).put("qualityExecutionRevision",rule.revision()).put("qualityDefinitionSha256",rule.definitionSha256()).put("timeoutSeconds",30).put("maxRetries",0);
        for(String key:List.of("datasourceId","sql","targetTable","jobDefinitionId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable"))d.putNull(key);d.set("dependencies",FixedJson.JSON.createObjectNode());
        String hash=WorkflowQualityParent.definitionHash(d);var n=FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("origin","workflow").put("operation",WorkflowQualityParent.OPERATION).put("resourceId",100000+id).put("runId",200000+id).put("attemptId",300000+id).put("fence",1).put("executionRevision",8).put("definitionSha256",hash);
        n.set("execution",d);n.set("actor",actor.json());n.set("approval",FixedJson.JSON.createObjectNode().put("uid",43).put("username","fixture-approver").put("accessVersion",4).put("revision",8).put("definitionSha256",hash));n.set("parameters",FixedJson.JSON.createObjectNode().put("bizDate","2024-02-29"));n.putNull("parent");
        String canonical=n.toString(),manifestHash=BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8));return WorkflowQualityParent.parse(canonical,manifestHash,new WorkflowQualityParent.Reference(200000+id,300000+id,1,8,manifestHash));
    }
    QualityExecutionStore.Claim unknown(WorkflowQualityParent parent){var claim=store.claimChild(parent);jdbc.update("UPDATE quality.quality_check_result SET status='unknown',violation_count=NULL,duration_ms=NULL,finished_at=clock_timestamp() WHERE id=?",claim.result().getId());return claim;}
    String status(long id){return jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,id);}
    void response(long target,Long count,boolean failed){doAnswer(i->{var ref=(QualityQueryClient.Submission)i.getArgument(0);if(ref.runId()!=target)return null;((LongConsumer)i.getArgument(2)).accept(900000+target);if(failed)throw new QualityQueryClient.QualityExecutionException("controlled signed failed original receipt",false);return new QualityQueryClient.QueryResult(count,7);}).when(metadata).reconcile(any(),any(),any());}
    @Test void childClaimIsIdempotentAndCannotBeResolvedByAnUnboundManualSummary(){var p=parent();var a=store.claimChild(p);var b=store.claimChild(p);assertFalse(a.reused());assertTrue(b.reused());assertEquals(a.result().getId(),b.result().getId());jdbc.update("UPDATE quality.quality_check_result SET status='unknown' WHERE id=?",a.result().getId());assertThrows(IllegalStateException.class,()->recovery.reconcile(a.result().getId(),"passed",true,"controlled manual summary is not a receipt","fixture-admin",0L));assertEquals("unknown",status(a.result().getId()));}
    @Test void pendingOriginalReceiptDoesNotCompleteOrSubmitAgain(){var claim=unknown(parent());reconciler.reconcile();assertEquals("unknown",status(claim.result().getId()));assertNull(jdbc.queryForObject("SELECT metadata_receipt_id FROM quality.quality_check_result WHERE id=?",Long.class,claim.result().getId()));verify(metadata,never()).execute(any(),any());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.execution_reconciliation_audit WHERE run_id=?",Integer.class,claim.result().getId()));}
    @Test void lostReceiptIdIsReadBackAndStatusHasAnActualIsoTimestamp(){var p=parent();var claim=unknown(p);response(claim.result().getId(),0L,false);reconciler.reconcile();assertEquals("passed",status(claim.result().getId()));try(var child=new WorkflowChildQualityService(mock(SignedHttpClient.class),store,mock(QualityFixedExecutionFacade.class),jdbc)){var result=child.status(p.reference());assertEquals(900000+claim.result().getId(),result.metadataReceiptId());assertEquals(7,result.durationMs());assertNotNull(java.time.OffsetDateTime.parse(result.finishedAt()));}verify(metadata,never()).execute(any(),any());}
    @Test void failedCountAndConfirmedExecutionErrorAreDifferentTerminalReceipts(){var a=unknown(parent());response(a.result().getId(),2L,false);reconciler.reconcile();assertEquals("failed",status(a.result().getId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,a.result().getId()));var b=unknown(parent());response(b.result().getId(),null,true);reconciler.reconcile();assertEquals("error",status(b.result().getId()));assertNull(jdbc.queryForObject("SELECT violation_count FROM quality.quality_check_result WHERE id=?",Long.class,b.result().getId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,b.result().getId()));}
    @Test void quarantineCompletionAndAuditRollBackTogetherThenOriginalReceiptCanBeReadAgain(){var claim=unknown(parent());long id=claim.result().getId();response(id,2L,false);doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled completion write failure")).when(jdbc).update(startsWith("UPDATE quality.quality_check_result SET status=?"),any(Object[].class));reconciler.reconcile();assertEquals("unknown",status(id));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,id));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.execution_reconciliation_audit WHERE run_id=?",Integer.class,id));doCallRealMethod().when(jdbc).update(startsWith("UPDATE quality.quality_check_result SET status=?"),any(Object[].class));new QualityReceiptReconciler(jdbc,metadata,recovery,results).reconcile();assertEquals("failed",status(id));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,id));}
    @Test void moreThanThirtyTwoPendingReceiptsCannotStarveALaterCompletedOne(){long last=0;var ids=new ArrayList<Long>();for(int i=0;i<34;i++){last=unknown(parent()).result().getId();ids.add(last);}response(last,0L,false);for(int i=0;i<20&&!"passed".equals(status(last));i++)reconciler.reconcile();assertEquals("passed",status(last));for(long id:ids.subList(0,33))assertEquals("unknown",status(id));verify(metadata,never()).execute(any(),any());}
    @Test void twoReconciliationInstancesRecordOneQuarantineAndOneCompletion() throws Exception {long id=unknown(parent()).result().getId();response(id,2L,false);var other=new QualityReceiptReconciler(jdbc,metadata,recovery,results);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(reconciler::reconcile);var b=pool.submit(other::reconcile);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}assertEquals("failed",status(id));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM quality.execution_reconciliation_audit WHERE run_id=?",Integer.class,id));}
}
