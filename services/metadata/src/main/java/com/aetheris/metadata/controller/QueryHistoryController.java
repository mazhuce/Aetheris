package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.QueryHistoryResponse;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 查询历史端点：当前用户在即席查询页的执行记录，支持按状态筛选、
 * 单条删除与有界清理。所有谓词绑定验签且实时验证的稳定 UID，
 * 用户名仅显示，旧 NULL 归属不回填。
 */
@RestController
@RequestMapping("/api/metadata/query-history")
public class QueryHistoryController {

    private final QueryHistoryService queryHistoryService;
    private final MetadataSecurity security;

    public QueryHistoryController(QueryHistoryService queryHistoryService, MetadataSecurity security) {
        this.queryHistoryService = queryHistoryService;
        this.security = security;
    }

    @GetMapping
    public List<QueryHistoryResponse> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return queryHistoryService.list(security.requireIdentity(request).uid(), status);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request,
            @PathVariable Long id) {
        return queryHistoryService.delete(security.requireIdentity(request).uid(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public QueryHistoryService.ClearResult clear(
            HttpServletRequest request) {
        return queryHistoryService.clear(security.requireIdentity(request).uid());
    }
}
