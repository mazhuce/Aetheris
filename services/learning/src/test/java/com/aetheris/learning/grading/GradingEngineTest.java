package com.aetheris.learning.grading;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.support.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GradingEngineTest {

    private static final String LEARNER_SQL = """
            SELECT order_id, order_status FROM orders_raw
             WHERE is_deleted = false AND order_status = 'paid'
            """;

    private SqlSandboxExecutor executor;
    private GradingEngine engine;
    private LearningModels.LabRecord lab;

    @BeforeEach
    void setUp() {
        executor = mock(SqlSandboxExecutor.class);
        engine = new GradingEngine(executor, new LearningProperties());
        lab = new LearningModels.LabRecord(1L, "l1-valid-orders", "m-l1-sql", "有效订单", "L1", "easy", "SQL",
                "summary", "scenario", "objective", List.of(), List.of("teach.orders_raw"), "SELECT 1",
                "SELECT order_id, order_status FROM orders_raw WHERE is_deleted = false AND order_status = 'paid'",
                Json.readTree("""
                        {"forbiddenKeywords":["insert","update","delete","drop","alter","truncate"],
                         "requiredColumns":["is_deleted","order_status"],"disallowSelectStar":true}
                        """),
                List.of(new LearningModels.RubricItem("safety", "SQL 安全边界", 20),
                        new LearningModels.RubricItem("static", "过滤条件完整性", 30),
                        new LearningModels.RubricItem("result", "结果正确性", 50)),
                70, 20, "published", 1, 1, "light", List.of(), List.of());
    }

    @Test
    void matchingResultScoresFullMarks() {
        var expected = QueryResult.of(List.of("order_id", "order_status"),
                List.of(List.of("1002", "paid")), 5, false);
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(lab.expectedSql()), org.mockito.ArgumentMatchers.anyInt())).thenReturn(expected);
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(LEARNER_SQL), org.mockito.ArgumentMatchers.anyInt())).thenReturn(expected);

        var graded = engine.grade(lab, "sb_u1", LEARNER_SQL);

        assertThat(graded.score()).isEqualTo(100);
        assertThat(graded.passed()).isTrue();
        assertThat(graded.items()).allMatch(LearningModels.GradingItemView::passed);
    }

    @Test
    void mismatchedResultKeepsSafetyAndStructureScore() {
        var expected = QueryResult.of(List.of("order_id", "order_status"),
                List.of(List.of("1002", "paid")), 5, false);
        var wrong = QueryResult.of(List.of("order_id", "order_status"),
                List.of(List.of("9999", "paid")), 5, false);
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(lab.expectedSql()), org.mockito.ArgumentMatchers.anyInt())).thenReturn(expected);
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(LEARNER_SQL), org.mockito.ArgumentMatchers.anyInt())).thenReturn(wrong);

        var graded = engine.grade(lab, "sb_u1", LEARNER_SQL);

        assertThat(graded.score()).isEqualTo(50);
        assertThat(graded.passed()).isFalse();
        assertThat(graded.items()).filteredOn(item -> "result".equals(item.key()))
                .singleElement().satisfies(item -> assertThat(item.passed()).isFalse());
    }

    @Test
    void writeStatementIsBlockedBeforeExecution() {
        var graded = engine.grade(lab, "sb_u1", "DELETE FROM orders_raw");

        assertThat(graded.score()).isZero();
        assertThat(graded.passed()).isFalse();
        assertThat(graded.summary()).contains("未通过");
        assertThat(graded.evidence()).doesNotContainKey("learnerRows");
    }

    @Test
    void sqlExecutionFailureIsReportedAsFeedback() {
        var expected = QueryResult.of(List.of("order_id", "order_status"),
                List.of(List.of("1002", "paid")), 5, false);
        String learner = """
                SELECT missing_column FROM orders_raw WHERE is_deleted = false AND order_status = 'paid'
                """;
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(lab.expectedSql()), org.mockito.ArgumentMatchers.anyInt())).thenReturn(expected);
        when(executor.execute(org.mockito.ArgumentMatchers.eq("sb_u1"), org.mockito.ArgumentMatchers.eq(learner), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new SqlSandboxExecutor.SqlExecutionException("column does not exist", "42703"));

        var graded = engine.grade(lab, "sb_u1", learner);

        assertThat(graded.passed()).isFalse();
        assertThat(graded.summary()).contains("SQL 执行失败");
        assertThat(graded.hints()).isNotEmpty();
    }
}
