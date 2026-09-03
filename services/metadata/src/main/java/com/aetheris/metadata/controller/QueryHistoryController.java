package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.QueryHistory;
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
import java.util.Map;

/**
 * 查询历史端点：当前用户在即席查询页的执行记录，支持按状态筛选、
 * 单条删除与清空。用户名取网关验签身份（而非可伪造的 X-User-Name），
 * 只能看到自己的历史。
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
    public List<QueryHistory> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        String username = security.requireIdentity(request).username();
        return queryHistoryService.list(username, status);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request,
            @PathVariable Long id) {
        String username = security.requireIdentity(request).username();
        return queryHistoryService.delete(username, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public Map<String, Integer> clear(
            HttpServletRequest request) {
        String username = security.requireIdentity(request).username();
        return Map.of("deleted", queryHistoryService.clear(username));
    }
}