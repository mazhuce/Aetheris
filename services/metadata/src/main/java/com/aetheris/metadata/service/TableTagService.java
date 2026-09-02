package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.TableTag;
import com.aetheris.metadata.mapper.TableTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 标签资产服务：标签的分组聚合与增删（打标签的人只能删自己打的标签，admin 除外由 controller 判定）。 */
@Service
public class TableTagService {

    private final TableTagMapper mapper;

    public TableTagService(TableTagMapper mapper) {
        this.mapper = mapper;
    }

    public List<TableTag> listByTable(Long tableId) {
        return mapper.selectList(new LambdaQueryWrapper<TableTag>()
                .eq(TableTag::getMetadataTableId, tableId)
                .orderByAsc(TableTag::getTagName));
    }

    public List<TableTag> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<TableTag>()
                .orderByDesc(TableTag::getId)
                .last("LIMIT 2000"));
    }

    public TableTag getById(Long id) {
        return mapper.selectById(id);
    }

    public TableTag add(Long tableId, String tagName, String createdBy) {
        // 先删后插：唯一约束兜底，重复打标签幂等处理
        mapper.delete(new LambdaQueryWrapper<TableTag>()
                .eq(TableTag::getMetadataTableId, tableId)
                .eq(TableTag::getTagName, tagName));
        TableTag tag = new TableTag();
        tag.setMetadataTableId(tableId);
        tag.setTagName(tagName);
        tag.setCreatedBy(createdBy);
        mapper.insert(tag);
        return tag;
    }

    public boolean remove(Long id) {
        return mapper.deleteById(id) > 0;
    }

    /**
     * 按标签名聚合：每个标签下的表数与表清单（带表名快照，表被删除时仍可追溯）。
     * 表清单由调用方（controller）传入表名映射，这里只做纯聚合。
     */
    public Map<String, List<TableTag>> groupByName(List<TableTag> tags) {
        Map<String, List<TableTag>> grouped = new HashMap<>();
        for (TableTag tag : tags) {
            grouped.computeIfAbsent(tag.getTagName(), name -> new java.util.ArrayList<>()).add(tag);
        }
        return grouped;
    }

    /** 表存在性校验用：标签必须挂在真实存在的表上。 */
    public boolean tableExists(List<MetadataTable> tables, Long tableId) {
        return tables.stream().anyMatch(table -> table.getId().equals(tableId));
    }
}
