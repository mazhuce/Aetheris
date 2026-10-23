package com.aetheris.workflow.controller;
import com.aetheris.workflow.config.BusinessCalendar;
import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.security.*;
import com.aetheris.workflow.service.*;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** Real crypto filters, actual production MVC/advice/authorization, loopback auth; persistence/executors mocked. */
class WorkflowV2MvcTest {
 V2WorkflowFixture f;MockMvc mvc;
 final TaskService tasks=mock(TaskService.class);final TaskInstanceService instances=mock(TaskInstanceService.class);
 final TaskLogService logs=mock(TaskLogService.class);final TaskDependencyService dependencies=mock(TaskDependencyService.class);
 final ExecutionAttemptService attempts=mock(ExecutionAttemptService.class);final ExecutionResolutionService resolutions=mock(ExecutionResolutionService.class);
 final TaskExecutionService execution=mock(TaskExecutionService.class);final TaskSchedulerService scheduler=mock(TaskSchedulerService.class);
 final SchedulerSyncRecoveryService schedulerRecovery=mock(SchedulerSyncRecoveryService.class);final SqlExecutionClient sql=mock(SqlExecutionClient.class);
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 @BeforeAll static void mappings(){for(Class<?> type:List.of(Task.class,TaskInstance.class,TaskDependency.class,TaskLog.class,ExecutionAttempt.class)){com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"workflow-v2-mvc"),type);}}
 @BeforeEach void setup() throws Exception {
  f=new V2WorkflowFixture();Task task=new Task();task.setId(8L);task.setName("original");task.setTaskType("quality");task.setQualityRuleId(7L);task.setStatus("enabled");task.setDefinitionVersion(2);task.setOwner("different-owner");
  when(tasks.getById(8L)).thenReturn(task);when(tasks.update(any(),any())).thenReturn(true);when(tasks.removeById(8L)).thenReturn(true);
  when(tasks.save(any())).thenAnswer(i->{((Task)i.getArgument(0)).setId(8L);return true;});
  var auth=new WorkflowAuthorizationService(jdbc,f.authorization);
  var controller=new TaskController(tasks,instances,logs,attempts,resolutions,dependencies,execution,scheduler,schedulerRecovery,sql,mock(ManagedSparkCatalogService.class),mock(SparkBatchExecutionService.class),auth);controller.setAuthorization(f.authorization);
  mvc=MockMvcBuilders.standaloneSetup(controller,new BusinessCalendarController(new BusinessCalendar()))
    .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new GatewayIdentityInterceptor(f.authorization)).addFilters(f.workload(),f.direct()).build();
 }
 @AfterEach void cleanup() throws Exception {if(f!=null)f.close();}
 @Test void wrongAudienceExpiredDisabledAndBareHeadersNeverReachBusiness() throws Exception {
  Clock past=Clock.offset(f.clock,Duration.ofSeconds(-120));long then=past.instant().getEpochSecond();var oldSigner=new ProtocolSigner("user",KeyPurpose.USER,"gateway",f.keys.get("user").getPrivate(),f.registry,past,TimePolicy.defaults());String expired=oldSigner.sign(new UserProof(2,"gateway","workflow",42,"alice",3,then,then+60,"expired-workflow"));
  for(var input:List.of(Map.entry("bad.proof",401),Map.entry(f.token("metadata",42,3),401),Map.entry(expired,401),Map.entry(f.token("workflow",7,3),403),Map.entry(f.token("workflow",42,2),403)))mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,input.getKey())).andExpect(status().is(input.getValue()));
  mvc.perform(get("/api/workflow/tasks/8").requestAttr("aetheris.username","admin").header("X-Aetheris-Identity","legacy").header("X-Internal-Token","legacy").header("X-User-Role","admin")).andExpect(status().isUnauthorized());
  f.active.set(false);mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isForbidden());verifyNoInteractions(tasks,execution,sql,jdbc);
 }
 @Test void readRevocationMissingRecordAndDependencyFaultRemain200403404503() throws Exception {
  mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token()).header("X-User-Name","admin")).andExpect(status().isOk());
  f.allowed.set(false);mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isForbidden());
  f.allowed.set(true);when(tasks.getById(8L)).thenReturn(null);mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isNotFound());
  f.permissionFaultOnly=true;f.fault="unsigned";mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isServiceUnavailable());verify(tasks,times(2)).getById(8L);
 }
 @Test void delegatedRenamePreservesDefinitionAndCannotAcquireDatasourceWrite() throws Exception {
  mvc.perform(put("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"name\":\"renamed\"}")).andExpect(status().isOk());
  verify(tasks).update(argThat(t->t.getName().equals("renamed")&&t.getQualityRuleId()==null&&t.getTaskSql()==null),any());
  mvc.perform(put("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"name\":\"bad\",\"qualityRuleId\":999}")).andExpect(status().isForbidden());
  assertTrue(f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).allMatch(r->r.body().path("resourceType").asText().equals("workflow_task")));verifyNoInteractions(sql,execution);
 }
 @Test void creationRequiresRealManageAndAuditUsernameComesFromTypedProjection() throws Exception {
  String raw="{\"name\":\"new\",\"taskType\":\"quality\",\"qualityRuleId\":7}";
  mvc.perform(post("/api/workflow/tasks").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content(raw)).andExpect(status().isForbidden());verify(tasks,never()).save(any());
  f.manage.set(true);mvc.perform(post("/api/workflow/tasks").header(SignedHttpClient.USER_PROOF,f.token()).header("X-User-Name","admin").contentType("application/json").content(raw)).andExpect(status().isOk());verify(tasks).save(argThat(t->"alice".equals(t.getOwner())));
  mvc.perform(post("/api/workflow/tasks").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
 }
 @Test void resolutionUsesCurrentManageAndNeverTrustsStringAdmin() throws Exception {
  String raw="{\"status\":\"failed\",\"reason\":\"verified remote stopped\",\"evidenceRef\":\"fixture-only\"}";
  mvc.perform(post("/api/workflow/tasks/instances/19/resolve").header(SignedHttpClient.USER_PROOF,f.token()).header("X-User-Name","admin").contentType("application/json").content(raw)).andExpect(status().isForbidden());verifyNoInteractions(resolutions);
  f.permissionFaultOnly=true;f.fault="tampered";mvc.perform(post("/api/workflow/tasks/instances/19/resolve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content(raw)).andExpect(status().isServiceUnavailable());verifyNoInteractions(resolutions);
 }
 @Test void deleteRetainsExplicitPermissionAndUnauthenticatedInternalNeverRuns() throws Exception {
  mvc.perform(delete("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isNoContent());verify(tasks).removeById(8L);
  mvc.perform(post("/internal/lakehouse/maintenance").header("X-Internal-Token","legacy")).andExpect(status().isBadRequest());
  mvc.perform(post("/internal/v2/anything").header(SignedHttpClient.REQUEST_PROOF,"forged")).andExpect(status().isNotFound());verifyNoInteractions(execution,sql);
 }
 @Test void allSecondaryDispatchesDuplicateProofAndNeighborPrefixesReject() throws Exception {
  for(DispatcherType type:List.of(DispatcherType.ASYNC,DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR))mvc.perform(get("/api/workflow/tasks/8").with(r->{r.setDispatcherType(type);return r;}).header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isBadRequest());
  mvc.perform(get("/api/workflow/tasks/8").header(SignedHttpClient.USER_PROOF,f.token(),f.token())).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/workflow-neighbor/tasks").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isForbidden());
  mvc.perform(get("/api/tasks-neighbor").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isForbidden());
  mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/workflow/business-calendar").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isOk());verifyNoInteractions(tasks,execution,sql);
 }
}
