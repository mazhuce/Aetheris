package com.aetheris.learning.web;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.security.CurrentUser;
import com.aetheris.learning.service.LearningAdminService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/** 内容运营：草稿、审核、发布、MCP 审批与运营分析。 */
@RestController
@RequestMapping("/api/learning/admin")
public class LearningAdminController {

    private final LearningRepository repository;
    private final LearningAdminService adminService;
    private final LearningProperties properties;

    public LearningAdminController(LearningRepository repository, LearningAdminService adminService,
                                   LearningProperties properties) {
        this.repository = repository;
        this.adminService = adminService;
        this.properties = properties;
    }

    public record ReviewRequest(boolean approve, String note) {
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return repository.learningOpsOverview();
    }

    @GetMapping("/labs")
    public List<LearningModels.LabAdminView> labs(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.labs();
    }

    @PostMapping("/labs")
    public LearningModels.LabAdminView create(@RequestBody LearningAdminService.LabUpsert request,
                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.create(request, user.username());
    }

    @PutMapping("/labs/{code}")
    public LearningModels.LabAdminView update(@PathVariable String code,
                                              @RequestBody LearningAdminService.LabUpsert request,
                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.update(code, request, user.username());
    }

    @PostMapping("/labs/{code}/review")
    public LearningModels.LabAdminView review(@PathVariable String code, @RequestBody ReviewRequest request,
                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.review(code, request.approve(), request.note());
    }

    @PostMapping("/labs/{code}/publish")
    public LearningModels.LabAdminView publish(@PathVariable String code,
                                               @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.publish(code);
    }

    @PostMapping("/labs/{code}/unpublish")
    public Map<String, Object> unpublish(@PathVariable String code,
                                         @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        int updated = repository.unpublishLab(code);
        if (updated == 0) throw new IllegalArgumentException("任务不存在：" + code);
        return Map.of("code", code, "status", "draft");
    }

    @GetMapping("/approvals")
    public List<LearningModels.ApprovalView> approvals(@RequestParam(required = false) String status,
                                                       @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.approvals(status);
    }

    @PostMapping("/approvals/{id}/decision")
    public LearningAdminService.ApprovalResult decide(@PathVariable long id,
                                                      @RequestBody LearningAdminService.Decision decision,
                                                      @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.decide(id, decision.approve(), user.uid(), user.username(), decision.note());
    }

    @GetMapping("/datasets")
    public List<LearningModels.DatasetManifestView> datasets(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return repository.listDatasetManifests();
    }

    public record DatasetRegisterRequest(String code, Integer version, String tableName, String license,
                                         String source, Boolean piiReviewed, String note) {
    }

    @PostMapping("/datasets")
    public LearningModels.DatasetManifestView registerDataset(@RequestBody DatasetRegisterRequest request,
                                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        if (request.version() == null) throw new IllegalArgumentException("版本号不能为空");
        return repository.registerDatasetManifest(request.code(), request.version(), request.tableName(),
                request.license(), request.source(), Boolean.TRUE.equals(request.piiReviewed()), request.note());
    }

    @GetMapping("/datasets/verify")
    public List<LearningModels.DatasetVerificationView> verifyDatasets(
            @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return repository.verifyDatasetManifests();
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        requireAdmin(user);
        return adminService.analytics();
    }

    private void requireAdmin(CurrentUser user) {
        if (!properties.getAdminUsers().contains(user.username())) {
            throw new ResponseStatusException(FORBIDDEN, "需要内容管理员权限");
        }
    }
}
