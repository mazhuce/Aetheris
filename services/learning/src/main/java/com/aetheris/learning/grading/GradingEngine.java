package com.aetheris.learning.grading;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五层判分中的前三层在 M2 落地：静态安全、结构规范、动态结果比对。
 * 过程判分依赖平台执行审计，性能判分依赖真实引擎指标，留到阶段 2。
 */
@Component
public class GradingEngine {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GradingEngine.class);

    private final SqlSandboxExecutor executor;
    private final LearningProperties properties;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> sandboxLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public GradingEngine(SqlSandboxExecutor executor, LearningProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    public LearningModels.GradedSubmission grade(LearningModels.LabRecord lab, String sandboxSchema, String learnerSql) {
        int timeoutMs = "medium".equals(lab.sandboxTier())
                ? properties.getSandbox().getMediumStatementTimeoutMs()
                : properties.getGrading().getStatementTimeoutMs();
        SqlGuard.Evaluation evaluation = SqlGuard.evaluate(learnerSql, lab.staticPolicy());
        List<LearningModels.GradingItemView> items = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("labCode", lab.code());
        evidence.put("labVersion", lab.version());
        evidence.put("staticChecks", evaluation.safety().stream().map(SqlGuard.Check::detail).toList());
        evidence.put("structureChecks", evaluation.structure().stream().map(SqlGuard.Check::detail).toList());

        boolean safetyPassed = evaluation.safetyPassed();
        boolean structurePassed = evaluation.structurePassed();
        evidence.put("safetyPassed", safetyPassed);
        evidence.put("structurePassed", structurePassed);
        boolean resultPassed = false;
        String resultDetail = "未执行结果比对。";
        QueryResult learnerResult = null;

        boolean writeMode = lab.staticPolicy().path("allowWrite").asBoolean(false);
        boolean orderedResult = lab.staticPolicy().path("orderedResult").asBoolean(false);
        if (orderedResult && !writeMode) {
            evidence.put("orderedResult", true);
        }
        if (safetyPassed && (!writeMode || structurePassed)) {
            if (writeMode) {
                WriteOutcome outcome = gradeWrite(lab, sandboxSchema, learnerSql, timeoutMs);
                learnerResult = outcome.learner();
                resultPassed = outcome.passed();
                resultDetail = outcome.detail();
                evidence.put("writeMode", true);
                if (outcome.expected() != null) {
                    evidence.put("expectedRows", outcome.expected().rowCount());
                    evidence.put("expectedChecksum", outcome.expected().checksum());
                }
                if (learnerResult != null) {
                    evidence.put("learnerRows", learnerResult.rowCount());
                    evidence.put("learnerChecksum", learnerResult.checksum());
                    evidence.put("durationMs", learnerResult.durationMs());
                }
                if (!resultPassed) {
                    hints.add("写任务判分后目标表会重置为教学初始数据；请检查 WHERE 条件与目标值是否覆盖了正确的行。");
                }
            } else {
                QueryResult expected;
                try {
                    expected = executor.execute(sandboxSchema, lab.expectedSql(), timeoutMs);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("教学答案执行失败，请联系管理员：" + failure.getMessage(), failure);
                }
                if (expected.rowCount() == 0) {
                    throw new IllegalStateException("标准答案结果为空，教学数据可能缺失，任务暂不可判分：" + lab.code());
                }
                try {
                    learnerResult = executor.execute(sandboxSchema, learnerSql, timeoutMs);
                    String learnerChecksum = orderedResult
                            ? QueryResult.checksum(learnerResult.rows(), true) : learnerResult.checksum();
                    String expectedChecksum = orderedResult
                            ? QueryResult.checksum(expected.rows(), true) : expected.checksum();
                    if (learnerResult.truncated()) {
                        resultDetail = "结果集超过 " + 5000 + " 行，请先聚合或过滤后再提交。";
                    } else if (learnerResult.columns().size() != expected.columns().size()) {
                        resultDetail = "列数不一致：期望 " + expected.columns().size() + " 列，实际 "
                                + learnerResult.columns().size() + " 列。";
                    } else if (!learnerChecksum.equals(expectedChecksum)) {
                        resultDetail = "结果与标准答案不一致：期望 " + expected.rowCount() + " 行，实际 "
                                + learnerResult.rowCount() + " 行。请检查过滤条件、去重逻辑与聚合口径。";
                    } else {
                        resultPassed = true;
                        resultDetail = "结果完全一致：" + learnerResult.rowCount() + " 行，校验和 "
                                + learnerChecksum.substring(0, 12) + "。";
                    }
                    evidence.put("learnerRows", learnerResult.rowCount());
                    evidence.put("expectedRows", expected.rowCount());
                    evidence.put("learnerChecksum", learnerChecksum);
                    evidence.put("expectedChecksum", expectedChecksum);
                    evidence.put("durationMs", learnerResult.durationMs());
                } catch (SqlSandboxExecutor.SqlExecutionException failure) {
                    resultDetail = "SQL 执行失败：" + failure.getMessage();
                    hints.add("先单独执行 WHERE 条件，确认能返回数据，再逐步加入聚合或窗口函数。");
                    evidence.put("sqlState", failure.sqlState());
                }
            }
        } else if (safetyPassed) {
            resultDetail = "结构规范未通过，已跳过执行。";
            hints.add("写任务的结构规范不通过时不会执行，请先满足目标表与写法要求。");
        } else {
            resultDetail = "存在安全或语法问题，已跳过结果比对。";
            hints.add(writeMode
                    ? "写任务只允许单条 INSERT / UPDATE / DELETE，且不能显式引用 teach schema。"
                    : "只允许单条只读 SELECT / WITH 语句，且不能包含任何写操作关键字。");
        }

        if (!structurePassed) {
            hints.addAll(evaluation.structure().stream().filter(check -> !check.passed())
                    .map(SqlGuard.Check::detail).limit(3).toList());
        }
        if (!resultPassed && learnerResult != null) {
            hints.add("对照任务目标逐条检查：过滤条件、去重策略、聚合维度、输出列顺序与名称。");
        }

        int score = weightedScore(lab, safetyPassed, structurePassed, resultPassed, learnerResult != null);
        boolean passed = score >= lab.passScore();

        for (LearningModels.RubricItem rubric : lab.rubric()) {
            boolean itemPassed = switch (rubric.key()) {
                case "safety" -> safetyPassed;
                case "static", "structure" -> structurePassed;
                case "result" -> resultPassed;
                default -> resultPassed;
            };
            String detail = switch (rubric.key()) {
                case "safety" -> joinChecks(evaluation.safety());
                case "static", "structure" -> joinChecks(evaluation.structure());
                case "result" -> resultDetail;
                default -> resultDetail;
            };
            items.add(new LearningModels.GradingItemView(rubric.key(), rubric.name(), rubric.weight(), itemPassed, detail));
        }
        if (lab.rubric().stream().noneMatch(item -> "result".equals(item.key()))) {
            items.add(new LearningModels.GradingItemView("result", "结果正确性", 50, resultPassed, resultDetail));
        }

        String summary = passed
                ? "任务通过，得分 " + score + "。" + resultDetail
                : "任务未通过，得分 " + score + "。" + firstFailure(items);

        List<String> columns = learnerResult == null ? List.of() : learnerResult.columns();
        List<List<String>> sample = learnerResult == null ? List.of()
                : learnerResult.sample(properties.getGrading().getSampleRows());
        return new LearningModels.GradedSubmission(score, passed, summary, items, evidence, columns, sample,
                hints.stream().distinct().limit(4).toList());
    }

    private record WriteOutcome(QueryResult learner, QueryResult expected, boolean passed, String detail) {
    }

    private WriteOutcome gradeWrite(LearningModels.LabRecord lab, String schema, String learnerSql, int timeoutMs) {
        JsonNode policy = lab.staticPolicy();
        String table = policy.path("targetTable").asText("");
        String stateQuery = policy.path("stateQuery").asText("");
        if (table.isBlank() || stateQuery.isBlank()) {
            throw new IllegalStateException("写任务缺少 targetTable/stateQuery 定义");
        }
        synchronized (sandboxLocks.computeIfAbsent(schema, key -> new Object())) {
            QueryResult learner = null;
            String failureDetail = null;
            try {
                executor.executeUpdate(schema, learnerSql, timeoutMs, table);
                learner = executor.execute(schema, stateQuery, timeoutMs);
            } catch (SqlSandboxExecutor.SqlExecutionException failure) {
                failureDetail = "SQL 执行失败：" + failure.getMessage();
            }
            QueryResult expected;
            try {
                executor.resetTable(schema, table);
                executor.executeUpdate(schema, lab.expectedSql(), timeoutMs, table);
                expected = executor.execute(schema, stateQuery, timeoutMs);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("教学答案执行失败，请联系管理员：" + failure.getMessage(), failure);
            } finally {
                try {
                    executor.resetTable(schema, table);
                } catch (RuntimeException cleanup) {
                    LOG.warn("写任务目标表重置失败 schema={} table={}", schema, table);
                }
            }
            if (expected.rowCount() == 0) {
                throw new IllegalStateException("标准答案结果为空，教学数据可能缺失，任务暂不可判分：" + lab.code());
            }
            if (learner == null) {
                return new WriteOutcome(null, expected, false, failureDetail);
            }
            if (learner.truncated()) {
                return new WriteOutcome(learner, expected, false, "最终表状态超过 5000 行，请先缩小操作范围。");
            }
            if (learner.columns().size() != expected.columns().size()) {
                return new WriteOutcome(learner, expected, false, "列数不一致：期望 " + expected.columns().size()
                        + " 列，实际 " + learner.columns().size() + " 列。");
            }
            if (!learner.checksum().equals(expected.checksum())) {
                return new WriteOutcome(learner, expected, false, "最终表状态与标准答案不一致：期望 "
                        + expected.rowCount() + " 行，实际 " + learner.rowCount()
                        + " 行。请检查 WHERE 条件与目标值。");
            }
            return new WriteOutcome(learner, expected, true, "最终表状态一致：" + learner.rowCount()
                    + " 行，校验和 " + learner.checksum().substring(0, 12) + "。");
        }
    }

    private static int weightedScore(LearningModels.LabRecord lab, boolean safety, boolean structure, boolean result,
                                     boolean executed) {
        List<LearningModels.RubricItem> rubric = lab.rubric();
        boolean resultDeclared = rubric.stream().anyMatch(item -> "result".equals(item.key()));
        int total = rubric.stream().mapToInt(LearningModels.RubricItem::weight).sum();
        if (total <= 0) {
            return (safety ? 20 : 0) + (structure ? 30 : 0) + (result ? 50 : 0);
        }
        int earned = 0;
        for (LearningModels.RubricItem item : rubric) {
            boolean itemPassed = switch (item.key()) {
                case "safety" -> safety;
                case "static", "structure" -> structure;
                case "result" -> result;
                default -> result;
            };
            if (itemPassed) earned += item.weight();
        }
        if (!resultDeclared) {
            total += 50;
            earned += result ? 50 : 0;
        }
        if (!executed && !resultDeclared) {
            earned = Math.min(earned, total - 50);
        }
        return Math.max(0, Math.min(100, Math.round(earned * 100f / total)));
    }

    private static String joinChecks(List<SqlGuard.Check> checks) {
        if (checks.isEmpty()) return "无需额外检查。";
        return String.join(" ", checks.stream().map(SqlGuard.Check::detail).toList());
    }

    private static String firstFailure(List<LearningModels.GradingItemView> items) {
        return items.stream().filter(item -> !item.passed()).map(LearningModels.GradingItemView::detail)
                .findFirst().orElse("请检查任务要求。");
    }
}
