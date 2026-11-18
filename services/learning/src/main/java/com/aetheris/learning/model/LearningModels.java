package com.aetheris.learning.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 学习域的只读视图与内部记录，统一放在一处避免碎片化 DTO。 */
public final class LearningModels {

    private LearningModels() {
    }

    public record PathSummary(String code, String title, String description, String targetRole, String level,
                              int version, int estimatedHours, int labCount, int completedLabs, int progressPercent) {
    }

    public record PathDetail(String code, String title, String description, String targetRole, String level,
                             int estimatedHours, List<ModuleView> modules, List<SkillView> skills) {
    }

    public record ProjectView(String code, String title, String summary, String level, String scenario,
                              List<String> deliverables, int labCount, int passedLabs) {
    }

    public record ProjectDetail(String code, String title, String summary, String level, String scenario,
                                List<String> deliverables, List<LabCard> labs) {
    }

    public record ModuleView(String code, String title, String objective, String level,
                            List<String> relatedFeatures, List<LabCard> labs) {
    }

    public record LabCard(String code, String title, String level, String difficulty, String summary,
                          int estimatedMinutes, int orderNo, String status, Integer bestScore, boolean passed) {
    }

    public record SkillView(String code, String title, String level, List<String> knowledgePoints, String outcome,
                            int labs, int passedLabs, int bestScore) {
    }

    public record LabDetail(String code, String title, String level, String difficulty, String taskType,
                            String summary, String scenario, String objective, List<Step> steps, List<String> tableRefs,
                            String starterSql, int passScore, int estimatedMinutes, int version, String projectCode,
                            List<RubricItem> rubric, String sandboxSchema, boolean sandboxActive,
                            Integer bestScore, boolean passed, String lastStatus,
                            List<String> relatedFeatures, List<LabKnowledge> knowledge, List<String> datasetRefs,
                            List<DatasetInfo> datasets, String nextLabCode, String nextLabTitle) {
    }

    public record DatasetVerificationView(String code, int version, int rowCount, int expectedRowCount,
                                         String checksum, String expectedChecksum,
                                         boolean rowCountMatches, boolean checksumMatches) {
    }

    public record DatasetManifestView(String code, int version, String tableName, int rowCount,
                                      String checksum, String license, String source, boolean piiReviewed,
                                      String status, String note) {
    }

    public record DatasetInfo(String code, int version, String tableName, int rowCount,
                              String license, String source, boolean piiReviewed) {
    }

    public record Step(String title, String detail) {
    }

    public record RubricItem(String key, String name, int weight) {
    }

    public record LabKnowledge(String title, String detail) {
    }

    public record LabRecord(long id, String code, String moduleCode, String title, String level, String difficulty,
                            String taskType, String summary, String scenario, String objective, List<Step> steps,
                            List<String> tableRefs, String starterSql, String expectedSql, JsonNode staticPolicy,
                            List<RubricItem> rubric, int passScore, int estimatedMinutes, String status, int version,
                            int orderNo, String sandboxTier, List<String> relatedFeatures,
                            List<LabKnowledge> knowledge) {
    }

    public record SandboxInfo(String schemaName, String status, String tier, Instant expiresAt, Instant createdAt,
                              Instant lastResetAt) {
    }

    public record SandboxRow(long id, long learnerUid, String labCode, String schemaName, String status,
                             String tier, Instant expiresAt, Instant createdAt, Instant lastResetAt) {
        public SandboxInfo toInfo() {
            return new SandboxInfo(schemaName, status, tier == null ? "light" : tier, expiresAt, createdAt,
                    lastResetAt);
        }
    }

    public record SubmissionView(long id, String labCode, int attempt, String sql, String notes, String status,
                                 Integer score, Boolean passed, Instant createdAt, Instant gradedAt,
                                 GradingView result) {
    }

    public record GradingView(int score, boolean passed, String summary, List<GradingItemView> items,
                              List<String> sampleColumns, List<List<String>> sampleRows, List<String> hints) {
    }

    public record GradingItemView(String key, String name, int weight, boolean passed, String detail) {
    }

    public record ProgressView(int completed, int total, int percent, int averageScore, List<SkillView> skills,
                               List<CredentialView> credentials) {
    }

    public record CredentialView(long id, String type, String title, Instant issuedAt) {
    }

    public record HintView(int level, String title, String message, boolean lastHint) {
    }

    public record GradedSubmission(int score, boolean passed, String summary, List<GradingItemView> items,
                                   Map<String, Object> evidence, List<String> sampleColumns,
                                   List<List<String>> sampleRows, List<String> hints) {
    }

    public record SubmissionRow(long id, long learnerUid, String labCode, int attempt, String status, Integer score,
                                Boolean passed, Instant createdAt) {
    }

    public record LabAdminView(long id, String code, String moduleCode, String projectCode, String title, String level,
                               String difficulty, String summary, String objective, String status, String reviewStatus,
                               String reviewNote, String author, int version, int passScore, int estimatedMinutes,
                               String expectedSql, java.time.Instant updatedAt) {
    }

    public record ApprovalView(long id, String type, String targetCode, String payloadJson, String reason,
                               String status, long requestedBy, String requestedByName, String decidedByName,
                               String decisionNote, Instant createdAt, Instant decidedAt) {
    }

    public record AttemptEventView(String eventType, String detail, Instant createdAt) {
    }

    public record TutorMessageView(String role, String content, String model, Instant createdAt) {
    }

    public record TutorChatView(String reply, String model, boolean fallback, List<TutorMessageView> history) {
    }

    public record LabAnalytics(String labCode, String title, String level, int attempts, int learners,
                               int passedLearners, double averageScore, String topFailure) {
    }
}
