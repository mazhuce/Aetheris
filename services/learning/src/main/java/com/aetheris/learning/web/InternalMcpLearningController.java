package com.aetheris.learning.web;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.service.CatalogService;
import com.aetheris.learning.service.ProgressService;
import com.aetheris.learning.service.SubmissionService;
import com.aetheris.learning.support.Json;
import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅供 mcp-server 调用的内部接口。用户身份来自 WORKLOAD + USER 双证明，
 * 不接受请求体里的 uid；高风险动作只创建审批单，不直接执行。
 */
@RestController
@RequestMapping("/internal/v2/mcp")
@ConditionalOnProperty(name = "aetheris.security.registry-file")
public class InternalMcpLearningController {

    private final CatalogService catalogService;
    private final ProgressService progressService;
    private final SubmissionService submissionService;
    private final LearningRepository repository;

    public InternalMcpLearningController(CatalogService catalogService, ProgressService progressService,
                                         SubmissionService submissionService, LearningRepository repository) {
        this.catalogService = catalogService;
        this.progressService = progressService;
        this.submissionService = submissionService;
        this.repository = repository;
    }

    public record LabRequest(String labCode) {
    }

    public record PublishRequestBody(String labCode, String reason) {
    }

    @PostMapping("/learning-path")
    public Map<String, Object> learningPath(HttpServletRequest request) {
        TrustedUser user = TrustedUser.require(request);
        long uid = user.proof().uid();
        LearningModels.ProgressView progress = progressService.progress(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("learner", user.proof().username());
        result.put("paths", catalogService.listPaths(uid));
        result.put("progress", progress);
        return result;
    }

    @PostMapping("/lab")
    public Map<String, Object> lab(@RequestBody LabRequest body, HttpServletRequest request) {
        TrustedUser user = TrustedUser.require(request);
        if (body == null || body.labCode() == null || body.labCode().isBlank()) {
            throw new IllegalArgumentException("缺少 labCode");
        }
        LearningModels.LabDetail lab = catalogService.labDetail(body.labCode(), user.proof().uid());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lab", lab);
        result.put("lastResult", submissionService.lastResult(user.proof().uid(), body.labCode()));
        return result;
    }

    @PostMapping("/submissions")
    public List<LearningModels.SubmissionView> submissions(@RequestBody LabRequest body, HttpServletRequest request) {
        TrustedUser user = TrustedUser.require(request);
        if (body == null || body.labCode() == null || body.labCode().isBlank()) {
            throw new IllegalArgumentException("缺少 labCode");
        }
        return submissionService.list(user.proof().uid(), body.labCode(), 10);
    }

    /** 高风险动作只落审批单：MCP 不直接发布内容。 */
    @PostMapping("/lab-publish-request")
    public Map<String, Object> requestPublish(@RequestBody PublishRequestBody body, HttpServletRequest request) {
        TrustedUser user = TrustedUser.require(request);
        if (body == null || body.labCode() == null || body.labCode().isBlank()) {
            throw new IllegalArgumentException("缺少 labCode");
        }
        LearningModels.LabAdminView lab = repository.findAdminLab(body.labCode())
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + body.labCode()));
        if (lab.expectedSql() == null || lab.expectedSql().isBlank()) {
            throw new IllegalArgumentException("任务缺少标准答案，不能申请发布");
        }
        LearningModels.ApprovalView existing = repository.findPendingApproval("LAB_PUBLISH", lab.code())
                .orElse(null);
        if (existing != null) {
            return approvalResponse(existing);
        }
        long id = repository.insertApproval("LAB_PUBLISH", lab.code(),
                Json.write(Map.of("labCode", lab.code(), "labTitle", lab.title(),
                        "reviewStatus", lab.reviewStatus())),
                body.reason() == null || body.reason().isBlank() ? "MCP 请求发布实验任务" : body.reason(),
                user.proof().uid(), user.proof().username());
        return approvalResponse(repository.findApproval(id).orElseThrow());
    }

    @GetMapping("/approvals/{id}")
    public Map<String, Object> approval(@PathVariable long id, HttpServletRequest request) {
        TrustedUser user = TrustedUser.require(request);
        LearningModels.ApprovalView approval = repository.findApproval(id)
                .orElseThrow(() -> new IllegalArgumentException("审批单不存在：" + id));
        if (approval.requestedBy() != user.proof().uid()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "只能查看自己发起的审批");
        }
        return approvalResponse(approval);
    }

    private static Map<String, Object> approvalResponse(LearningModels.ApprovalView approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvalId", approval.id());
        result.put("type", approval.type());
        result.put("labCode", approval.targetCode());
        result.put("status", approval.status());
        result.put("reason", approval.reason());
        result.put("requestedBy", approval.requestedByName());
        result.put("decidedBy", approval.decidedByName());
        result.put("decisionNote", approval.decisionNote());
        result.put("createdAt", approval.createdAt());
        result.put("decidedAt", approval.decidedAt());
        return result;
    }
}
