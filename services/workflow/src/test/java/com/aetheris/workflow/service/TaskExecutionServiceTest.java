package com.aetheris.workflow.service;
import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.workflow.security.V2WorkflowFixture;
import com.aetheris.security.servlet.TrustedUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Superseded raw SQL tests are archived; production SQL requires the sealed-source facade. */
class TaskExecutionServiceTest {
    static class Fixture {
        final TaskService tasks=mock(TaskService.class);final TaskInstanceService instances=mock(TaskInstanceService.class);
        final TaskDependencyService dependencies=mock(TaskDependencyService.class);final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final ExecutionClaimCoordinator coordinator=mock(ExecutionClaimCoordinator.class);final SqlExecutionClient client=mock(SqlExecutionClient.class);
        final WorkflowSqlFacade fixed=mock(WorkflowSqlFacade.class);final Task task=new Task();final TaskExecutionService service;
        Fixture(){task.setId(1L);task.setTaskType("sql");task.setStatus("enabled");when(tasks.getById(anyLong())).thenReturn(task);when(dependencies.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
          service=new TaskExecutionService(tasks,instances,mock(TaskLogService.class),dependencies,coordinator,client,mock(NotificationClient.class),mock(SparkBatchExecutionService.class),mock(QualityExecutionClient.class),mock(LineageEvidenceClient.class),jdbc);service.installFixedSql(fixed);}
        void edge(){var edge=new TaskDependency();edge.setTaskId(1L);edge.setUpstreamTaskId(2L);when(dependencies.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(edge));}
    }
    @Test void stringSqlBridgeRejectsManualCronRetryAndPipelineBeforeClaim(){var f=new Fixture();for(String source:List.of("manual","schedule","retry","pipeline"))assertThrows(IllegalStateException.class,()->f.service.execute(1L,"2026-08-28",source,"admin"));verifyNoInteractions(f.coordinator,f.client,f.fixed);}
    @Test void invalidCalendarDatesNeverReachClaim(){var f=new Fixture();for(String date:Arrays.asList("2026-02-30","2025-02-29","2026-13-01","2026-2-01","0000-01-01","2026-01-01' OR '1'='1",null))assertThrows(IllegalArgumentException.class,()->f.service.execute(1L,date,"manual","alice"));verifyNoInteractions(f.coordinator,f.client,f.fixed);}
    @Test void placeholderExpansionRemainsDeterministicAndRejectsInvalidDate(){var f=new Fixture();assertEquals("INSERT OVERWRITE TABLE t PARTITION(dt='2026-08-28') SELECT 1",f.service.resolvePlaceholders("INSERT OVERWRITE TABLE t PARTITION(dt='${bizDate}') SELECT 1","2026-08-28"));assertThrows(IllegalArgumentException.class,()->f.service.resolvePlaceholders("${bizDate}","2026-02-30"));}
    @Test void noDependenciesIsReady(){var f=new Fixture();assertNull(f.service.findUnmetDependency(1L,"2026-08-28"));}
    @Test void missingUpstreamIsNotReady(){var f=new Fixture();f.edge();assertNotNull(f.service.findUnmetDependency(1L,"2026-08-28"));}
    @Test void successfulUpstreamIsReady(){var f=new Fixture();f.edge();var instance=new TaskInstance();instance.setStatus("success");when(f.instances.getOne(any())).thenReturn(instance);assertNull(f.service.findUnmetDependency(1L,"2026-08-28"));}
    @Test void qualityWaiverDoesNotApplyOutsidePipeline(){var f=new Fixture();f.edge();var quality=new Task();quality.setId(2L);quality.setTaskType("quality");when(f.tasks.getById(2L)).thenReturn(quality);assertNotNull(f.service.findUnmetDependency(1L,"2026-08-28","manual"));verifyNoInteractions(f.jdbc);}
    @Test void existingPipelineQualityWaiverReadSemanticsRemain(){var f=new Fixture();f.edge();var quality=new Task();quality.setId(2L);quality.setTaskType("quality");when(f.tasks.getById(2L)).thenReturn(quality);when(f.jdbc.queryForObject(contains("quality_waiver"),eq(Integer.class),eq(2L),eq("2026-08-28"))).thenReturn(1);assertNull(f.service.findUnmetDependency(1L,"2026-08-28","pipeline"));}
    @Test void nullUserCannotEnterSqlFacade(){var f=new Fixture();assertThrows(IllegalStateException.class,()->f.service.executeManual(1,"2026-08-28",null));verifyNoInteractions(f.fixed,f.coordinator,f.client);}
    @Test void realVerifiedUserEntersOnlyTypedFacade() throws Exception {try(var proof=new V2WorkflowFixture()){var f=new Fixture();var result=new TaskInstance();when(f.fixed.manual(eq(1L),eq("2026-08-28"),any())).thenReturn(result);proof.request(r->assertSame(result,f.service.executeManual(1,"2026-08-28",TrustedUser.require(r))));verify(f.fixed).manual(eq(1L),eq("2026-08-28"),any());verifyNoInteractions(f.client,f.coordinator);}}
    @Test void cronCarriesGenerationToBoundStore(){var f=new Fixture();f.service.executeScheduled(1,"2026-08-28",7);verify(f.fixed).scheduled(1,"2026-08-28",7);verifyNoInteractions(f.client,f.coordinator);}
    @Test void unknownTaskStillFailsBeforeClaim(){var f=new Fixture();when(f.tasks.getById(99L)).thenReturn(null);assertThrows(IllegalArgumentException.class,()->f.service.execute(99L,"2026-08-28","manual","alice"));verifyNoInteractions(f.coordinator);}
    @Test void disabledLegacyTaskCannotBeExecuted(){var f=new Fixture();f.task.setStatus("disabled");assertThrows(IllegalStateException.class,()->f.service.execute(1L,"2026-08-28","manual","alice"));verifyNoInteractions(f.coordinator);}
}
