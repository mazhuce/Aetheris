package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 内容运营：草稿、审核、发布、MCP 审批与运营分析。 */
@Service
public class LearningAdminService {

    private final LearningRepository repository;

    public LearningAdminService(LearningRepository repository) {
        this.repository = repository;
    }

    public record LabUpsert(String moduleCode, String code, String title, String level, String difficulty,
                            String summary, String scenario, String objective, List<LearningModels.Step> steps,
                            List<String> tableRefs, String starterSql, String expectedSql, Integer passScore,
                            Integer estimatedMinutes, Integer orderNo, String projectCode,
                            List<LearningModels.RubricItem> rubric) {
    }

    public record Decision(boolean approve, String note) {
    }

    public record ApprovalResult(long id, String status, boolean labPublished, String message) {
    }

    public List<LearningModels.LabAdminView> labs() {
        return repository.listAdminLabs();
    }

    public LearningModels.LabAdminView create(LabUpsert request, String author) {
        String code = request.code();
        if (code == null || !code.matches("[a-z0-9][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("任务编码必须为 3-64 位小写字母、数字或连字符");
        }
        if (repository.findAdminLab(code).isPresent()) {
            throw new IllegalArgumentException("任务编码已存在：" + code);
        }
        LearningModels.LabRecord lab = toRecord(0L, request);
        long id = repository.insertLab(lab, request.projectCode(), author);
        return repository.findAdminLab(code)
                .orElseThrow(() -> new IllegalStateException("任务创建后未能读取：" + id));
    }

    public LearningModels.LabAdminView update(String code, LabUpsert request, String author) {
        LearningModels.LabAdminView existing = repository.findAdminLab(code)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + code));
        if (existing.status() != null && "published".equals(existing.status())) {
            throw new IllegalArgumentException("已发布任务请先下架再编辑，避免影响学员正在进行的实验");
        }
        LearningModels.LabRecord lab = toRecord(existing.id(), request, code);
        int updated = repository.updateLab(lab, request.projectCode(), author);
        if (updated == 0) throw new IllegalArgumentException("任务不存在：" + code);
        return repository.findAdminLab(code).orElseThrow();
    }

    public LearningModels.LabAdminView review(String code, boolean approve, String note) {
        repository.findAdminLab(code).orElseThrow(() -> new IllegalArgumentException("任务不存在：" + code));
        if (approve && repository.findAdminLab(code).map(lab -> lab.expectedSql() == null
                || lab.expectedSql().isBlank()).orElse(true)) {
            throw new IllegalArgumentException("缺少标准答案，不能通过审核");
        }
        repository.reviewLab(code, approve, note);
        return repository.findAdminLab(code).orElseThrow();
    }

    public LearningModels.LabAdminView publish(String code) {
        LearningModels.LabAdminView lab = repository.findAdminLab(code)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + code));
        if (!"approved".equals(lab.reviewStatus())) {
            throw new IllegalArgumentException("任务尚未通过审核，不能发布");
        }
        if (repository.publishReviewedLab(code) == 0) {
            throw new IllegalArgumentException("任务缺少标准答案或状态不满足发布条件");
        }
        return repository.findAdminLab(code).orElseThrow();
    }

    public List<LearningModels.ApprovalView> approvals(String status) {
        return repository.listApprovals(status);
    }

    public ApprovalResult decide(long approvalId, boolean approve, long uid, String username, String note) {
        LearningModels.ApprovalView approval = repository.findApproval(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("审批单不存在：" + approvalId));
        if (!"pending".equals(approval.status())) {
            throw new IllegalArgumentException("审批单已处理：" + approval.status());
        }
        if (approve && "LAB_PUBLISH".equals(approval.type())) {
            LearningModels.LabAdminView lab = repository.findAdminLab(approval.targetCode())
                    .orElseThrow(() -> new IllegalArgumentException("目标任务不存在：" + approval.targetCode()));
            if (!"approved".equals(lab.reviewStatus())) {
                throw new IllegalArgumentException("内容尚未通过审核，无法批准发布；请先完成内容审核");
            }
        }
        if (repository.decideApproval(approvalId, approve, uid, username, note) == 0) {
            throw new IllegalStateException("审批状态已被其他管理员更新");
        }
        boolean published = false;
        String message = approve ? "已批准" : "已驳回";
        if (approve && "LAB_PUBLISH".equals(approval.type())) {
            published = repository.publishReviewedLab(approval.targetCode()) > 0;
            message = published ? "已批准并发布任务" : "已批准，但任务缺少标准答案未发布";
        }
        return new ApprovalResult(approvalId, approve ? "approved" : "rejected", published, message);
    }

    public Map<String, Object> analytics() {
        List<LearningModels.LabAnalytics> labs = repository.labAnalytics();
        int attempts = labs.stream().mapToInt(LearningModels.LabAnalytics::attempts).sum();
        int learners = repository.learningOpsOverview().get("learners") instanceof Number number
                ? number.intValue() : 0;
        return Map.of("overview", repository.learningOpsOverview(), "labs", labs,
                "summary", Map.of("totalAttempts", attempts, "learners", learners,
                        "generatedAt", Instant.now().toString()));
    }

    private LearningModels.LabRecord toRecord(long id, LabUpsert request) {
        return toRecord(id, request, request.code());
    }

    private LearningModels.LabRecord toRecord(long id, LabUpsert request, String code) {
        List<LearningModels.RubricItem> rubric = request.rubric() == null || request.rubric().isEmpty()
                ? List.of(new LearningModels.RubricItem("safety", "SQL 安全边界", 20),
                new LearningModels.RubricItem("static", "结构规范", 30),
                new LearningModels.RubricItem("result", "结果正确性", 50))
                : request.rubric();
        return new LearningModels.LabRecord(id, code, request.moduleCode(), request.title(),
                request.level() == null ? "L1" : request.level(),
                request.difficulty() == null ? "easy" : request.difficulty(), "SQL",
                request.summary(), request.scenario(), request.objective(),
                request.steps() == null ? List.of() : request.steps(),
                request.tableRefs() == null ? List.of("teach.orders_raw") : request.tableRefs(),
                request.starterSql(), request.expectedSql(),
                com.aetheris.learning.support.Json.readTree("""
                        {"requireSelect":true,
                         "forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],
                         "requiredColumns":["is_deleted"],"disallowSelectStar":true}
                        """),
                rubric, request.passScore() == null ? 70 : request.passScore(),
                request.estimatedMinutes() == null ? 30 : request.estimatedMinutes(),
                "draft", 1, request.orderNo() == null ? 99 : request.orderNo(), "light");
    }
}
