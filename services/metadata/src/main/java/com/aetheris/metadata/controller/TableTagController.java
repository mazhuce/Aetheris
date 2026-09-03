package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.TableTag;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.TableTagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签资产端点：
 * - GET  /api/metadata/table-tags         → 按标签名分组的资产清单（标签 → 表列表）
 * - POST /api/metadata/table-tags         → 给表打标签（幂等，要求表更新权限）
 * - DELETE /api/metadata/table-tags/{id}  → 删除标签关系（本人或表更新权限）
 *
 * <p>标签创建者与管理员可删除；表名以快照形式返回，表被删除后标签仍可追溯。
 */
@RestController
@RequestMapping("/api/metadata/table-tags")
public class TableTagController {

    private final TableTagService tagService;
    private final MetadataTableService tableService;
    private final MetadataSecurity security;

    public TableTagController(TableTagService tagService, MetadataTableService tableService,
                              MetadataSecurity security) {
        this.tagService = tagService;
        this.tableService = tableService;
        this.security = security;
    }

    /** 标签分组视图：[{ tag, tableCount, tables: [{id, tableId, tableName, createdBy}] }]。 */
    @GetMapping
    public List<Map<String, Object>> groups(HttpServletRequest request) {
        security.requireIdentity(request);
        List<TableTag> tags = tagService.listAll();
        Map<Long, String> tableNames = new HashMap<>();
        for (TableTag tag : tags) {
            tableNames.computeIfAbsent(tag.getMetadataTableId(), id -> {
                MetadataTable table = tableService.getById(id);
                return table != null ? table.getTableName() : "（表已删除）";
            });
        }
        Map<String, List<TableTag>> grouped = tagService.groupByName(tags);
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<Map<String, Object>> tables = new ArrayList<>();
                    for (TableTag tag : entry.getValue()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", tag.getId());
                        item.put("tableId", tag.getMetadataTableId());
                        item.put("tableName", tableNames.get(tag.getMetadataTableId()));
                        item.put("createdBy", tag.getCreatedBy());
                        item.put("createdAt", tag.getCreatedAt());
                        tables.add(item);
                    }
                    Map<String, Object> group = new HashMap<>();
                    group.put("tag", entry.getKey());
                    group.put("tableCount", tables.size());
                    group.put("tables", tables);
                    return group;
                })
                // 标签多的排前面，资产页的浏览价值优先
                .sorted((a, b) -> Integer.compare((int) b.get("tableCount"), (int) a.get("tableCount")))
                .toList();
    }

    public record TagRequest(Long tableId, String tag) {
    }

    @PostMapping
    public ResponseEntity<?> add(
            HttpServletRequest request,
            @RequestBody TagRequest tagRequest) {
        if (tagRequest.tableId() == null || tagRequest.tag() == null || tagRequest.tag().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "tableId 与 tag 不能为空"));
        }
        if (tagRequest.tag().length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("message", "标签长度不能超过 64 字符"));
        }
        MetadataTable table = tableService.getById(tagRequest.tableId());
        if (table == null) {
            return ResponseEntity.status(404).body(Map.of("message", "目标表不存在"));
        }
        String username = security.require(request, "table", tagRequest.tableId(), "update",
                domainCtx(table.getBusinessDomainId())).username();
        return ResponseEntity.ok(tagService.add(tagRequest.tableId(), tagRequest.tag().trim(), username));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(
            HttpServletRequest request,
            @PathVariable Long id) {
        TableTag tag = tagService.getById(id);
        if (tag == null) {
            return ResponseEntity.notFound().build();
        }
        String username = security.requireIdentity(request).username();
        // 本人可删自己打的标签；其他用户需要表更新权限（admin 经决策引擎全量通过）。
        if (!username.equals(tag.getCreatedBy())) {
            MetadataTable table = tableService.getById(tag.getMetadataTableId());
            Long domainId = table == null ? null : table.getBusinessDomainId();
            security.require(request, "table", tag.getMetadataTableId(), "update", domainCtx(domainId));
        }
        tagService.remove(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> domainCtx(Long domainId) {
        return domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId));
    }
}