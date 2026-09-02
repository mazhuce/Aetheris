package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.TableTag;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.TableTagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签资产端点：
 * - GET  /api/metadata/table-tags         → 按标签名分组的资产清单（标签 → 表列表）
 * - POST /api/metadata/table-tags         → 给表打标签（幂等）
 * - DELETE /api/metadata/table-tags/{id}  → 删除标签关系
 *
 * <p>标签创建者与管理员可删除；表名以快照形式返回，表被删除后标签仍可追溯。
 */
@RestController
@RequestMapping("/api/metadata/table-tags")
public class TableTagController {

    private final TableTagService tagService;
    private final MetadataTableService tableService;

    public TableTagController(TableTagService tagService, MetadataTableService tableService) {
        this.tagService = tagService;
        this.tableService = tableService;
    }

    /** 标签分组视图：[{ tag, tableCount, tables: [{id, tableName, createdBy}] }]。 */
    @GetMapping
    public List<Map<String, Object>> groups() {
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
            @RequestBody TagRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        if (request.tableId() == null || request.tag() == null || request.tag().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "tableId 与 tag 不能为空"));
        }
        if (request.tag().length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("message", "标签长度不能超过 64 字符"));
        }
        MetadataTable table = tableService.getById(request.tableId());
        if (table == null) {
            return ResponseEntity.status(404).body(Map.of("message", "目标表不存在"));
        }
        return ResponseEntity.ok(tagService.add(request.tableId(), request.tag().trim(), username));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        TableTag tag = tagService.getById(id);
        if (tag == null) {
            return ResponseEntity.notFound().build();
        }
        // admin 可删任何标签；普通用户只能删自己打的
        if (!"admin".equals(role) && !username.equals(tag.getCreatedBy())) {
            return ResponseEntity.status(403).body(Map.of("message", "只能删除自己创建的标签"));
        }
        tagService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
