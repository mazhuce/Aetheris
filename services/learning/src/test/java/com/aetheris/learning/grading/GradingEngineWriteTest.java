package com.aetheris.learning.grading;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.support.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GradingEngineWriteTest {

    private SqlSandboxExecutor executor;
    private GradingEngine engine;
    private LearningModels.LabRecord lab;

    @BeforeEach
    void setUp() {
        executor = mock(SqlSandboxExecutor.class);
        engine = new GradingEngine(executor, new LearningProperties());
        lab = new LearningModels.LabRecord(9L, "de-l2-08", "de-m2", "写任务", "L2", "medium", "SQL_WRITE",
                "summary", "scenario", "objective", List.of(),
                List.of("teach.orders_raw"), "SELECT 1", "UPDATE orders_raw SET quantity = 0 WHERE quantity < 0 AND is_deleted = false",
                Json.readTree("""
                        {"allowWrite":true,
                         "targetTable":"orders_raw",
                         "stateQuery":"SELECT order_id FROM orders_raw ORDER BY order_id",
                         "forbiddenKeywords":["insert","delete","create","drop","alter","truncate","grant","copy"],
                         "requiredColumns":["update","where","is_deleted"]}
                        """),
                List.of(new LearningModels.RubricItem("rows", "影响行数", 40),
                        new LearningModels.RubricItem("filters", "WHERE 边界", 40),
                        new LearningModels.RubricItem("style", "SQL 规范", 20)),
                60, 20, "published", 1, 5, "light", List.of(), List.of());
    }

    @Test
    void passesWhenTheFinalTableStateMatches() {
        QueryResult state = QueryResult.of(List.of("order_id"), List.of(List.of("1")), 5, false);
        when(executor.execute(eq("sb_u1"), anyString(), anyInt())).thenReturn(state);

        LearningModels.GradedSubmission graded = engine.grade(lab, "sb_u1",
                "UPDATE orders_raw SET quantity = 0 WHERE quantity < 0 AND is_deleted = false");

        assertThat(graded.passed()).isTrue();
        assertThat(graded.summary()).contains("最终表状态一致");
        verify(executor, times(2)).executeUpdate(eq("sb_u1"), anyString(), anyInt());
        verify(executor, times(2)).execute(eq("sb_u1"), anyString(), anyInt());
        verify(executor, times(2)).resetTable("sb_u1", "orders_raw");
    }

    @Test
    void failsWhenTheFinalStateDiffersButStillResetsTheTable() {
        QueryResult learner = QueryResult.of(List.of("order_id"), List.of(List.of("1")), 5, false);
        QueryResult expected = QueryResult.of(List.of("order_id"), List.of(List.of("2")), 5, false);
        when(executor.execute(eq("sb_u1"), anyString(), anyInt())).thenReturn(learner, expected);

        LearningModels.GradedSubmission graded = engine.grade(lab, "sb_u1",
                "UPDATE orders_raw SET quantity = 0 WHERE quantity < 0 AND is_deleted = false");

        assertThat(graded.passed()).isFalse();
        assertThat(graded.summary()).contains("最终表状态与标准答案不一致");
        verify(executor, times(2)).resetTable("sb_u1", "orders_raw");
    }
}