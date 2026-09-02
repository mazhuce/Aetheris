package com.aetheris.workflow.controller;

import com.aetheris.workflow.dto.ReleaseActionRequest;
import com.aetheris.workflow.entity.ReleaseRecord;
import com.aetheris.workflow.service.ReleaseRecordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 发布管理接口：发布单 CRUD 与审批、发布、回滚流转。 */
@RestController
@RequestMapping("/api/workflow/releases")
public class ReleaseRecordController {

    private static final Logger log = LoggerFactory.getLogger(ReleaseRecordController.class);
    private static final String USER_HEADER = "X-User-Name";
    private static final String ROLE_HEADER = "X-User-Role";

    private final ReleaseRecordService releaseRecordService;

    public ReleaseRecordController(ReleaseRecordService releaseRecordService) {
        this.releaseRecordService = releaseRecordService;
    }

    /** 发布单列表，可按状态与发布物类型过滤，按创建时间倒序（最新申请在前）。 */
    @GetMapping
    public List<ReleaseRecord> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String releaseType) {
        return releaseRecordService.list(
                new LambdaQueryWrapper<ReleaseRecord>()
                        .eq(StringUtils.hasText(status), ReleaseRecord::getStatus, status)
                        .eq(StringUtils.hasText(releaseType), ReleaseRecord::getReleaseType, releaseType)
                        .orderByDesc(ReleaseRecord::getCreatedAt));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReleaseRecord> getById(@PathVariable Long id) {
        ReleaseRecord record = releaseRecordService.getById(id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @Valid @RequestBody ReleaseRecord record,
            @RequestHeader(value = USER_HEADER, required = false) String username,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        record.setId(null);
        applyDefaults(record, username);
        releaseRecordService.save(record);
        return ResponseEntity.ok(record);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody ReleaseRecord record,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        ReleaseRecord existing = releaseRecordService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        // 编辑只改定义信息（标题/对象/摘要等）；状态、审批人、发布时间等流转字段
        // 以库内为准，否则一个 PUT 就能绕过 approve/publish 的状态机校验。
        record.setId(id);
        record.setStatus(existing.getStatus());
        record.setRequestedBy(existing.getRequestedBy());
        record.setApprovedBy(existing.getApprovedBy());
        record.setReleasedAt(existing.getReleasedAt());
        record.setCreatedAt(existing.getCreatedAt());
        record.setUpdatedAt(OffsetDateTime.now());
        releaseRecordService.updateById(record);
        return ResponseEntity.ok(releaseRecordService.getById(id));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        if (releaseRecordService.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        releaseRecordService.removeById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 审批通过。审批人取网关透传的 X-User-Name 而非请求体：
     * 身份属于认证层的可信数据，客户端自报的名字不能作为审计依据。
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
            @PathVariable Long id,
            @RequestBody(required = false) ReleaseActionRequest request,
            @RequestHeader(value = USER_HEADER, required = false) String username,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        if (request != null && StringUtils.hasText(request.remark())) {
            log.debug("Release {} approve remark: {}", id, request.remark());
        }
        return transition(id, "pending", "approved", "审批通过",
                record -> record.setApprovedBy(username == null ? "unknown" : username));
    }

    /** 审批拒绝：pending 的终态分支，只有待审批的单子可以拒绝。 */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable Long id,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        return transition(id, "pending", "rejected", "拒绝", null);
    }

    /** 执行发布：审批通过不代表已上线，此处落实际发布时间。 */
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(
            @PathVariable Long id,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        return transition(id, "approved", "released", "发布",
                record -> record.setReleasedAt(OffsetDateTime.now()));
    }

    /** 回滚：只有已发布的单子可回滚；released_at 保留，作为发布窗口的留痕。 */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<?> rollback(
            @PathVariable Long id,
            @RequestHeader(value = ROLE_HEADER, defaultValue = "viewer") String role) {
        requireAdmin(role);
        return transition(id, "released", "rolled_back", "回滚", null);
    }

    /**
     * 统一执行状态流转：加载 → 校验前置状态 → 变更 → 落库。
     *
     * <p>非法流转返回 409 而非 400：请求本身格式没有问题，冲突在于资源当前的状态，
     * 与 TaskController 手动触发时「同日已有实例在跑」的口径一致。
     *
     * @param mutator 状态以外的附加变更（记录审批人、发布时间等）；可为 null
     */
    private ResponseEntity<?> transition(
            Long id, String expected, String target, String action, Consumer<ReleaseRecord> mutator) {
        ReleaseRecord record = releaseRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        if (!expected.equals(record.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "发布单当前状态为 " + record.getStatus() + "，仅 " + expected + " 状态可以" + action));
        }
        if (mutator != null) {
            mutator.accept(record);
        }
        record.setStatus(target);
        record.setUpdatedAt(OffsetDateTime.now());
        releaseRecordService.updateById(record);
        return ResponseEntity.ok(record);
    }

    private void applyDefaults(ReleaseRecord record, String username) {
        if (record.getStatus() == null) {
            record.setStatus("pending");
        }
        if (record.getReleaseType() == null) {
            record.setReleaseType("table");
        }
        if (record.getRequestedBy() == null && username != null) {
            record.setRequestedBy(username);
        }
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new ForbiddenException();
        }
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "仅管理员可以修改或操作发布记录"));
    }

    public static class ForbiddenException extends RuntimeException { }
}
