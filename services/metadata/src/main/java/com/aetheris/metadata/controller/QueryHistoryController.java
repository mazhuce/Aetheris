package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.service.QueryHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 查询历史端点：当前用户在即席查询页的执行记录，支持按状态筛选、
 * 单条删除与清空。用户名取自网关注入的 X-User-Name，只能看到自己的历史。
 */
@RestController
@RequestMapping("/api/metadata/query-history")
public class QueryHistoryController {

    private final QueryHistoryService queryHistoryService;

    public QueryHistoryController(QueryHistoryService queryHistoryService) {
        this.queryHistoryService = queryHistoryService;
    }

    @GetMapping
    public List<QueryHistory> list(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestParam(required = false) String status) {
        return queryHistoryService.list(username, status);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        return queryHistoryService.delete(username, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public Map<String, Integer> clear(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        return Map.of("deleted", queryHistoryService.clear(username));
    }
}
