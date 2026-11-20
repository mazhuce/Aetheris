package com.aetheris.learning.service;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.grading.GradingEngine;
import com.aetheris.learning.grading.QueryResult;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.sandbox.SandboxService;
import com.aetheris.learning.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 提交与判分编排：先落提交，再执行判分，最后写入不可变的评分证据。 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final LearningRepository repository;
    private final SandboxService sandboxService;
    private final GradingEngine gradingEngine;
    private final ProgressService progressService;
    private final LearningProperties properties;

    public SubmissionService(LearningRepository repository, SandboxService sandboxService,
                             GradingEngine gradingEngine, ProgressService progressService,
                             LearningProperties properties) {
        this.repository = repository;
        this.sandboxService = sandboxService;
        this.gradingEngine = gradingEngine;
        this.progressService = progressService;
        this.properties = properties;
    }

    public LearningModels.SubmissionView submit(long uid, String username, String labCode, String sql, String notes) {
        LearningModels.LabRecord lab = repository.findLab(labCode)
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + labCode));
        if (!"published".equals(lab.status())) {
            throw new IllegalArgumentException("实验任务尚未发布：" + labCode);
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("请先编写 SQL 再提交");
        }
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > properties.getGrading().getMaxSqlBytes()) {
            throw new IllegalArgumentException("SQL 过长，请控制在 "
                    + properties.getGrading().getMaxSqlBytes() + " 字节以内");
        }
        LearningModels.SandboxInfo sandbox = sandboxService.ensure(uid, username, labCode);
        int attempt = 0;
        long submissionId = 0;
        for (int tries = 1; ; tries++) {
            attempt = repository.nextAttempt(uid, labCode);
            try {
                submissionId = repository.insertSubmission(uid, username, labCode, lab.version(), attempt, sql, notes);
                break;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // 并发提交可能拿到同一个 attempt，唯一索引兜底后重试一次取新编号。
                if (tries >= 3) throw duplicate;
            }
        }
        try {
            LearningModels.GradedSubmission graded = gradingEngine.grade(lab, sandbox.schemaName(), sql);
            long resultId = repository.insertGradingResult(submissionId, graded.score(), graded.passed(),
                    graded.summary(), Json.write(graded.evidence()));
            for (LearningModels.GradingItemView item : graded.items()) {
                repository.insertGradingItem(resultId, item);
            }
            Object rows = graded.evidence().get("learnerRows");
            Object checksum = graded.evidence().get("learnerChecksum");
            Object duration = graded.evidence().get("durationMs");
            repository.insertExecutionEvidence(submissionId, sandbox.schemaName(),
                    QueryResult.sha256(sql), rows instanceof Number number ? number.intValue() : 0,
                    checksum == null ? null : String.valueOf(checksum),
                    duration instanceof Number number ? number.intValue() : 0);
            String eventType = processEventType(graded);
            repository.insertAttemptEvent(submissionId, eventType,
                    graded.summary() == null ? eventType : graded.summary().substring(0, Math.min(500, graded.summary().length())));
            repository.completeSubmission(submissionId, graded.score(), graded.passed());
            progressService.recordAttempt(uid, labCode, graded.score(), graded.passed());
            String pathCode = repository.pathOfLab(labCode);
            if (graded.passed() && repository.countPassedLabs(uid) == 1) {
                repository.insertLearningEvent(uid, "learner_first_lab_passed",
                        pathCode.isBlank() ? null : pathCode, labCode,
                        com.aetheris.learning.support.Json.write(java.util.Map.of("score", graded.score())));
            }
            if (!pathCode.isBlank()) {
                repository.findPath(pathCode).ifPresent(path ->
                        progressService.issueIfEligible(uid, pathCode, path.title()));
            }
            LearningModels.GradingView view = new LearningModels.GradingView(graded.score(), graded.passed(),
                    graded.summary(), graded.items(), graded.sampleColumns(), graded.sampleRows(), graded.hints());
            return new LearningModels.SubmissionView(submissionId, labCode, attempt, sql, notes, "graded",
                    graded.score(), graded.passed(), Instant.now(), Instant.now(), view);
        } catch (RuntimeException failure) {
            repository.markSubmissionError(submissionId);
            log.warn("判分失败 submission={} lab={} reason={}", submissionId, labCode, failure.getMessage());
            throw failure;
        }
    }

    public LearningModels.SubmissionView get(long uid, long submissionId) {
        LearningModels.SubmissionView submission = repository.findSubmission(submissionId, uid)
                .orElseThrow(() -> new IllegalArgumentException("提交记录不存在"));
        LearningModels.SubmissionView withResult = repository.findResult(submissionId)
                .map(result -> new LearningModels.SubmissionView(submission.id(), submission.labCode(),
                        submission.attempt(), submission.sql(), submission.notes(), submission.status(),
                        result.score(), result.passed(), submission.createdAt(), result.createdAt(),
                        new LearningModels.GradingView(result.score(), result.passed(), result.summary(),
                                items(result), List.of(), List.of(), List.of())))
                .orElse(submission);
        return withResult;
    }

    public List<LearningModels.SubmissionView> list(long uid, String labCode, int limit) {
        int bounded = Math.clamp(limit, 1, 50);
        return repository.listSubmissions(uid, labCode, bounded).stream()
                .map(row -> new LearningModels.SubmissionView(row.id(), row.labCode(), row.attempt(), null, null,
                        row.status(), row.score(), row.passed(), row.createdAt(), null, null))
                .toList();
    }

    private static String processEventType(LearningModels.GradedSubmission graded) {
        Object safety = graded.evidence().get("safetyPassed");
        boolean safetyPassed = !(safety instanceof Boolean flag) || flag;
        boolean result = graded.items().stream()
                .filter(item -> "result".equals(item.key())).allMatch(LearningModels.GradingItemView::passed);
        if (!safetyPassed) return "SAFETY_BLOCKED";
        if (graded.evidence().containsKey("sqlState")) return "SQL_ERROR";
        if (!result) return "RESULT_MISMATCH";
        return "PASSED";
    }

    private List<LearningModels.GradingItemView> items(LearningRepository.ResultRow result) {
        return repository.listResultItems(result.id()).stream()
                .map(item -> new LearningModels.GradingItemView(item.itemKey(), item.name(), item.weight(),
                        item.passed(), item.detail()))
                .toList();
    }

    public LearningModels.GradingView lastResult(long uid, String labCode) {
        return repository.latestSubmission(uid, labCode)
                .flatMap(row -> repository.findResult(row.id()).map(result -> new LearningModels.GradingView(
                        result.score(), result.passed(), result.summary(), items(result), List.of(), List.of(), List.of())))
                .orElse(null);
    }
}
