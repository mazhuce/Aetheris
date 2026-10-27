package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.fixedexecution.FixedJson;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.Set;

/** Waivers apply only to a completed gate failure of the current fenced attempt. */
final class QualityGateEvidence {
    private QualityGateEvidence() { }

    static Map<String,Object> result(Long ruleId, QualityExecutionClient.QualityResult result) {
        if (ruleId == null || ruleId <= 0 || result == null || result.id() == null || result.id() <= 0
                || !ruleId.equals(result.ruleId()) || result.status() == null || !Set.of("passed","failed").contains(result.status())
                || result.violationCount() == null || result.violationCount() < 0
                || result.thresholdValue() == null || result.thresholdValue() < 0 || result.finishedAt() == null
                || (result.durationMs() != null && result.durationMs() < 0)
                || "passed".equals(result.status()) != (result.violationCount() <= result.thresholdValue())) {
            throw new IllegalStateException("质量结果凭证无效");
        }
        return Map.of("schemaVersion",1,"kind","quality-gate","resultId",result.id(),"ruleId",ruleId,
                "status",result.status(),"violationCount",result.violationCount(),
                "thresholdValue",result.thresholdValue(),"finishedAt",result.finishedAt().toString());
    }

    static boolean confirmedFailure(JdbcTemplate jdbc, Task task, TaskInstance instance) {
        if (task == null || !"quality".equals(task.getTaskType()) || task.getQualityRuleId() == null
                || instance == null || !"failed".equals(instance.getStatus()) || instance.getFinishedAt() == null
                || instance.getId() == null || instance.getActiveAttemptId() == null || instance.getFencingToken() == null) return false;
        try {
            var rows = jdbc.queryForList("""
                    SELECT attempt.result_metadata_json::text
                    FROM workflow.execution_attempt attempt
                    JOIN workflow.task_instance instance ON instance.id=attempt.instance_id
                    JOIN workflow.task task ON task.id=instance.task_id
                    WHERE instance.id=? AND task.id=? AND instance.active_attempt_id=attempt.id
                      AND attempt.id=? AND instance.fencing_token=? AND attempt.fencing_token=instance.fencing_token
                      AND task.task_type='quality' AND instance.status='failed' AND attempt.status='failed'
                      AND attempt.quality_rule_id=task.quality_rule_id AND attempt.finished_at IS NOT NULL
                    """, String.class, instance.getId(), task.getId(), instance.getActiveAttemptId(), instance.getFencingToken());
            if (rows.size() != 1) return false;
            var n = FixedJson.parse(rows.getFirst());
            FixedJson.fields(n,"schemaVersion","kind","resultId","ruleId","status","violationCount","thresholdValue","finishedAt");
            return FixedJson.number(n,"schemaVersion",1) == 1 && "quality-gate".equals(FixedJson.text(n,"kind"))
                    && "failed".equals(FixedJson.text(n,"status")) && FixedJson.number(n,"resultId",1) > 0
                    && FixedJson.number(n,"ruleId",1) == task.getQualityRuleId()
                    && FixedJson.number(n,"violationCount",0) > FixedJson.number(n,"thresholdValue",0)
                    && java.time.OffsetDateTime.parse(FixedJson.text(n,"finishedAt")) != null;
        } catch (RuntimeException missingOrUnavailable) { return false; }
    }
}
