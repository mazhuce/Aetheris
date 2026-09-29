package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.entity.LakehouseTableSnapshot;
import com.aetheris.metadata.mapper.LakehouseTableMapper;
import com.aetheris.metadata.mapper.LakehouseTableSnapshotMapper;
import com.aetheris.metadata.service.LakehouseTableService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class LakehouseTableServiceImpl extends ServiceImpl<LakehouseTableMapper, LakehouseTable>
        implements LakehouseTableService {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:-]{0,127}");
    private final LakehouseTableSnapshotMapper snapshotMapper;

    public LakehouseTableServiceImpl(LakehouseTableSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional
    public LakehouseTable register(LakehouseTable table, String actor) {
        requirePart(table.getCatalogName(), "catalogName");
        requireNamespace(table.getNamespaceName());
        requirePart(table.getTableName(), "tableName");
        if (table.getFormatVersion() == null) table.setFormatVersion((short) 2);
        if (table.getLayer() == null) table.setLayer("bronze");
        if (table.getStatus() == null) table.setStatus("active");
        if (table.getCurrentSchemaVersion() == null) table.setCurrentSchemaVersion(1);
        if (table.getRetentionDays() == null) table.setRetentionDays(30);
        if (!StringUtils.hasText(table.getOwner())) table.setOwner(actor);
        if (!StringUtils.hasText(table.getPropertiesJson())) table.setPropertiesJson("{}");
        LakehouseTable existing = getOne(new LambdaQueryWrapper<LakehouseTable>()
                .eq(LakehouseTable::getCatalogName, table.getCatalogName())
                .eq(LakehouseTable::getNamespaceName, table.getNamespaceName())
                .eq(LakehouseTable::getTableName, table.getTableName())
                .last("LIMIT 1"));
        if (existing != null) return existing;
        if (!save(table)) throw new IllegalStateException("湖仓表登记失败");
        return getById(table.getId());
    }

    @Override
    @Transactional
    public LakehouseTableSnapshot recordSnapshot(Long tableId, LakehouseTableSnapshot snapshot, String actor) {
        LakehouseTable table = requireTable(tableId);
        if (!StringUtils.hasText(snapshot.getSnapshotId())) throw new IllegalArgumentException("snapshotId 不能为空");
        if (snapshot.getCommittedAt() == null) snapshot.setCommittedAt(OffsetDateTime.now());
        if (snapshot.getOperation() == null) snapshot.setOperation("append");
        if (snapshot.getSchemaVersion() == null) snapshot.setSchemaVersion(table.getCurrentSchemaVersion() == null ? 1 : table.getCurrentSchemaVersion());
        if (!StringUtils.hasText(snapshot.getMetadataJson())) snapshot.setMetadataJson("{}");
        snapshot.setLakehouseTableId(tableId);
        snapshot.setCreatedBy(actor);
        LakehouseTableSnapshot existing = snapshotMapper.selectOne(new LambdaQueryWrapper<LakehouseTableSnapshot>()
                .eq(LakehouseTableSnapshot::getLakehouseTableId, tableId)
                .eq(LakehouseTableSnapshot::getSnapshotId, snapshot.getSnapshotId())
                .last("LIMIT 1"));
        if (existing != null) {
            if (!java.util.Objects.equals(existing.getOperation(), snapshot.getOperation())
                    || !java.util.Objects.equals(existing.getParentSnapshotId(), snapshot.getParentSnapshotId())
                    || !java.util.Objects.equals(existing.getSchemaVersion(), snapshot.getSchemaVersion())) {
                throw new IllegalArgumentException("同一 snapshotId 的快照证据不可变");
            }
            table.setCurrentSnapshotId(existing.getSnapshotId());
            table.setCurrentSchemaVersion(existing.getSchemaVersion());
            updateById(table);
            return existing;
        }
        if (snapshotMapper.insert(snapshot) != 1) throw new IllegalStateException("快照登记失败");
        table.setCurrentSnapshotId(snapshot.getSnapshotId());
        table.setCurrentSchemaVersion(snapshot.getSchemaVersion());
        updateById(table);
        return snapshot;
    }

    @Override
    public List<LakehouseTableSnapshot> snapshots(Long tableId) {
        requireTable(tableId);
        return snapshotMapper.selectList(new LambdaQueryWrapper<LakehouseTableSnapshot>()
                .eq(LakehouseTableSnapshot::getLakehouseTableId, tableId)
                .orderByDesc(LakehouseTableSnapshot::getCommittedAt));
    }

    @Override
    @Transactional
    public LakehouseTable setCurrentSnapshot(Long tableId, String snapshotId, String actor) {
        LakehouseTable table = requireTable(tableId);
        if (!StringUtils.hasText(snapshotId)) throw new IllegalArgumentException("snapshotId 不能为空");
        LakehouseTableSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<LakehouseTableSnapshot>()
                .eq(LakehouseTableSnapshot::getLakehouseTableId, tableId)
                .eq(LakehouseTableSnapshot::getSnapshotId, snapshotId)
                .last("LIMIT 1"));
        if (snapshot == null) throw new IllegalArgumentException("快照不存在，不能回滚当前指针");
        table.setCurrentSnapshotId(snapshotId);
        table.setCurrentSchemaVersion(snapshot.getSchemaVersion());
        updateById(table);
        return getById(tableId);
    }

    private LakehouseTable requireTable(Long id) {
        LakehouseTable table = id == null ? null : getById(id);
        if (table == null) throw new IllegalArgumentException("湖仓表不存在");
        return table;
    }

    private static void requireNamespace(String namespace) {
        if (!StringUtils.hasText(namespace) || java.util.Arrays.stream(namespace.split("\\."))
                .anyMatch(part -> !PART.matcher(part).matches())) {
            throw new IllegalArgumentException("namespaceName 只能包含字母、数字、下划线、冒号或连字符，并使用点分层级");
        }
    }

    private static void requirePart(String value, String field) {
        if (!StringUtils.hasText(value) || !PART.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 格式非法");
        }
    }
}
