package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskDependency;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.TaskLog;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskExecutionServiceTest {

    @Test
    @DisplayName("同一业务日期复用实例行，重跑不新增记录")
    void reusesInstanceForSameBizDate() {
        Fixture f = new Fixture();
        TaskInstance existing = instance(7L, "2026-08-28", "failed", 1);
        when(f.instances.getOne(any())).thenReturn(existing);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(100L, 50L));

        f.service.execute(1L, "2026-08-28", "manual", "alice");

        // 幂等的关键：更新既有行而非 save 新行，否则实例列表会因反复重跑而膨胀，
        // 且唯一索引会直接报错。
        verify(f.instances, never()).save(any());
        verify(f.instances, times(2)).updateById(any());
        assertEquals(2, existing.getAttempt(), "重跑应累加 attempt");
    }

    @Test
    @DisplayName("首次执行时新建实例")
    void createsInstanceOnFirstRun() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(10L, 20L));

        f.service.execute(1L, "2026-08-28", "manual", "alice");

        ArgumentCaptor<TaskInstance> captor = ArgumentCaptor.forClass(TaskInstance.class);
        verify(f.instances).save(captor.capture());
        assertEquals(1, captor.getValue().getAttempt());
        assertEquals("2026-08-28", captor.getValue().getBizDate());
    }

    @Test
    @DisplayName("同一日期已有实例正在运行时拒绝重复触发")
    void rejectsConcurrentRunForSameBizDate() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(instance(7L, "2026-08-28", "running", 1));

        // 防止定时触发与人工触发同时写入同一分区。
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service.execute(1L, "2026-08-28", "manual", "alice"));
        assertEquals("该任务在此业务日期已有实例正在执行", error.getMessage());
        verify(f.client, never()).execute(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("任务停用时拒绝执行")
    void rejectsDisabledTask() {
        Fixture f = new Fixture();
        f.task.setStatus("disabled");

        assertThrows(IllegalStateException.class,
                () -> f.service.execute(1L, "2026-08-28", "manual", "alice"));
        verify(f.client, never()).execute(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("任务不存在时抛出参数异常")
    void rejectsUnknownTask() {
        Fixture f = new Fixture();
        when(f.tasks.getById(anyLong())).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> f.service.execute(99L, "2026-08-28", "manual", "alice"));
    }

    @Test
    @DisplayName("上游未成功时标记 skipped 而非 failed")
    void skipsWhenUpstreamNotReady() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(null, (TaskInstance) null);
        TaskDependency edge = new TaskDependency();
        edge.setTaskId(1L);
        edge.setUpstreamTaskId(2L);
        when(f.dependencies.list(any(Wrapper.class))).thenReturn(List.of(edge));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "schedule", "scheduler");

        // 依赖未就绪不是故障，用 failed 会让告警噪声掩盖真正的错误。
        assertEquals("skipped", result.getStatus());
        verify(f.client, never()).execute(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("上游成功后允许执行")
    void runsWhenUpstreamSucceeded() {
        Fixture f = new Fixture();
        TaskDependency edge = new TaskDependency();
        edge.setTaskId(1L);
        edge.setUpstreamTaskId(2L);
        when(f.dependencies.list(any(Wrapper.class))).thenReturn(List.of(edge));
        // 第一次查本任务实例返回 null，第二次查上游实例返回成功。
        when(f.instances.getOne(any()))
                .thenReturn(null)
                .thenReturn(instance(9L, "2026-08-28", "success", 1));
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(5L, 10L));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "schedule", "scheduler");

        assertEquals("success", result.getStatus());
    }

    @Test
    @DisplayName("失败后按 max_retries 重试，最终仍失败则标记 failed")
    void retriesThenFails() {
        Fixture f = new Fixture();
        f.task.setMaxRetries(2);
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenThrow(new SqlExecutionException("目标表不存在"));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "manual", "alice");

        assertEquals("failed", result.getStatus());
        // 1 次首试 + 2 次重试 = 3 次调用。
        verify(f.client, times(3)).execute(anyLong(), anyString(), anyInt());
        assertEquals("目标表不存在", result.getErrorMessage());
    }

    @Test
    @DisplayName("重试中途成功则不再继续重试")
    void stopsRetryingAfterSuccess() {
        Fixture f = new Fixture();
        f.task.setMaxRetries(3);
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenThrow(new SqlExecutionException("偶发超时"))
                .thenReturn(new SqlExecutionClient.ExecutionResult(42L, 30L));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "manual", "alice");

        assertEquals("success", result.getStatus());
        verify(f.client, times(2)).execute(anyLong(), anyString(), anyInt());
        assertEquals(42L, result.getAffectedRows());
    }

    @Test
    @DisplayName("业务日期占位符被替换")
    void resolvesBizDatePlaceholder() {
        Fixture f = new Fixture();

        String resolved = f.service.resolvePlaceholders(
                "INSERT OVERWRITE TABLE t PARTITION (dt='${bizDate}') SELECT 1", "2026-08-28");

        assertEquals(
                "INSERT OVERWRITE TABLE t PARTITION (dt='2026-08-28') SELECT 1",
                resolved);
    }

    @Test
    @DisplayName("占位符替换后的 SQL 才是实际执行的语句")
    void executesResolvedSql() {
        Fixture f = new Fixture();
        f.task.setTaskSql("INSERT OVERWRITE TABLE t PARTITION (dt='${bizDate}') SELECT 1");
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(1L, 1L));

        f.service.execute(1L, "2026-08-28", "manual", "alice");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(f.client).execute(anyLong(), sql.capture(), anyInt());
        assertEquals(
                "INSERT OVERWRITE TABLE t PARTITION (dt='2026-08-28') SELECT 1",
                sql.getValue());
    }

    @Test
    @DisplayName("Hive 返回 -1 行数时保留原值，不伪造成 0")
    void preservesUnknownAffectedRows() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(-1L, 100L));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "manual", "alice");

        // 伪造成 0 会被误读为「没写入任何数据」，-1 表示「行数未知」。
        assertEquals(-1L, result.getAffectedRows());
        assertEquals("success", result.getStatus());
    }

    @Test
    @DisplayName("无依赖时直接执行")
    void runsWhenNoDependencies() {
        Fixture f = new Fixture();
        when(f.dependencies.list(any(Wrapper.class))).thenReturn(List.of());

        assertNull(f.service.findUnmetDependency(1L, "2026-08-28"));
    }

    @Test
    @DisplayName("每次执行都写日志，便于事后判断是偶发还是稳定失败")
    void writesExecutionLogs() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(1L, 1L));

        f.service.execute(1L, "2026-08-28", "manual", "alice");

        verify(f.logs, times(2)).save(any(TaskLog.class));
    }

    @Test
    @DisplayName("日志写入失败不影响任务本身成功")
    void toleratesLogFailure() {
        Fixture f = new Fixture();
        when(f.instances.getOne(any())).thenReturn(null);
        when(f.logs.save(any(TaskLog.class))).thenThrow(new RuntimeException("日志表不可写"));
        when(f.client.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(new SqlExecutionClient.ExecutionResult(1L, 1L));

        TaskInstance result = f.service.execute(1L, "2026-08-28", "manual", "alice");

        assertEquals("success", result.getStatus());
    }

    /** 测试夹具：集中构造 mock，避免每个用例重复十行样板。 */
    private static final class Fixture {
        final TaskService tasks = mock(TaskService.class);
        final TaskInstanceService instances = mock(TaskInstanceService.class);
        final TaskLogService logs = mock(TaskLogService.class);
        final TaskDependencyService dependencies = mock(TaskDependencyService.class);
        final SqlExecutionClient client = mock(SqlExecutionClient.class);
        final Task task = new Task();
        final TaskExecutionService service;

        Fixture() {
            task.setId(1L);
            task.setName("ODS 到 DWD 订单加工");
            task.setStatus("enabled");
            task.setDatasourceId(5L);
            task.setTaskSql("INSERT OVERWRITE TABLE dwd.dwd_order SELECT * FROM ods.ods_order");
            task.setTimeoutSeconds(1800);
            task.setMaxRetries(0);
            when(tasks.getById(anyLong())).thenReturn(task);
            when(dependencies.list(any(Wrapper.class))).thenReturn(List.of());
            service = new TaskExecutionService(tasks, instances, logs, dependencies, client);
        }
    }

    private static TaskInstance instance(Long id, String bizDate, String status, int attempt) {
        TaskInstance instance = new TaskInstance();
        instance.setId(id);
        instance.setTaskId(1L);
        instance.setBizDate(bizDate);
        instance.setStatus(status);
        instance.setAttempt(attempt);
        return instance;
    }
}
