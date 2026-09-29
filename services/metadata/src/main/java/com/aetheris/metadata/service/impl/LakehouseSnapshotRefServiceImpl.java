package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LakehouseSnapshotRef;
import com.aetheris.metadata.entity.LakehouseTableSnapshot;
import com.aetheris.metadata.mapper.LakehouseSnapshotRefMapper;
import com.aetheris.metadata.mapper.LakehouseTableSnapshotMapper;
import com.aetheris.metadata.service.LakehouseSnapshotRefService;
import com.aetheris.metadata.service.LakehouseTableService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class LakehouseSnapshotRefServiceImpl extends ServiceImpl<LakehouseSnapshotRefMapper, LakehouseSnapshotRef>
        implements LakehouseSnapshotRefService {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{0,127}");
    private static final Set<String> TYPES = Set.of("branch", "tag");
    private final LakehouseTableService tableService;
    private final LakehouseTableSnapshotMapper snapshotMapper;

    public LakehouseSnapshotRefServiceImpl(LakehouseTableService tableService,
                                           LakehouseTableSnapshotMapper snapshotMapper) {
        this.tableService = tableService;
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional
    public LakehouseSnapshotRef create(Long tableId, LakehouseSnapshotRef ref, String actor) {
        requireTable(tableId);
        validate(ref);
        requireSnapshot(tableId, ref.getSnapshotId());
        LakehouseSnapshotRef existing = getOne(new LambdaQueryWrapper<LakehouseSnapshotRef>()
                .eq(LakehouseSnapshotRef::getLakehouseTableId, tableId)
                .eq(LakehouseSnapshotRef::getRefType, ref.getRefType())
                .eq(LakehouseSnapshotRef::getRefName, ref.getRefName())
                .last("LIMIT 1"));
        if (existing != null) {
            if ("tag".equals(existing.getRefType()) && Boolean.TRUE.equals(existing.getProtected())) {
                throw new IllegalStateException("受保护标签不可覆盖");
            }
            return existing;
        }
        ref.setLakehouseTableId(tableId);
        ref.setProtected(Boolean.TRUE.equals(ref.getProtected()) || "tag".equals(ref.getRefType()));
        ref.setCreatedBy(actor);
        ref.setUpdatedBy(actor);
        if (!save(ref)) throw new IllegalStateException("快照引用登记失败");
        return getById(ref.getId());
    }

    @Override
    @Transactional
    public LakehouseSnapshotRef move(Long refId, String snapshotId, String actor) {
        LakehouseSnapshotRef ref = requireRef(refId);
        if (!StringUtils.hasText(snapshotId)) throw new IllegalArgumentException("snapshotId 不能为空");
        if ("tag".equals(ref.getRefType()) && Boolean.TRUE.equals(ref.getProtected())) {
            throw new IllegalStateException("受保护标签不可移动");
        }
        requireSnapshot(ref.getLakehouseTableId(), snapshotId);
        ref.setSnapshotId(snapshotId);
        ref.setUpdatedBy(actor);
        ref.setUpdatedAt(OffsetDateTime.now());
        updateById(ref);
        return getById(refId);
    }

    @Override
    public List<LakehouseSnapshotRef> listForTable(Long tableId) {
        requireTable(tableId);
        return list(new LambdaQueryWrapper<LakehouseSnapshotRef>()
                .eq(LakehouseSnapshotRef::getLakehouseTableId, tableId)
                .orderByAsc(LakehouseSnapshotRef::getRefType)
                .orderByAsc(LakehouseSnapshotRef::getRefName));
    }

    @Override
    @Transactional
    public void removeRef(Long refId) {
        LakehouseSnapshotRef ref = requireRef(refId);
        if (Boolean.TRUE.equals(ref.getProtected())) throw new IllegalStateException("受保护引用不可删除");
        removeById(refId);
    }

    private void validate(LakehouseSnapshotRef ref) {
        if (!StringUtils.hasText(ref.getRefName()) || !NAME.matcher(ref.getRefName()).matches()) {
            throw new IllegalArgumentException("refName 格式非法");
        }
        if (!TYPES.contains(ref.getRefType())) throw new IllegalArgumentException("refType 必须是 branch 或 tag");
        if (!StringUtils.hasText(ref.getSnapshotId())) throw new IllegalArgumentException("snapshotId 不能为空");
    }

    private void requireSnapshot(Long tableId, String snapshotId) {
        LakehouseTableSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<LakehouseTableSnapshot>()
                .eq(LakehouseTableSnapshot::getLakehouseTableId, tableId)
                .eq(LakehouseTableSnapshot::getSnapshotId, snapshotId)
                .last("LIMIT 1"));
        if (snapshot == null) throw new IllegalArgumentException("快照不存在");
    }

    private void requireTable(Long tableId) {
        if (tableId == null || tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
    }

    private LakehouseSnapshotRef requireRef(Long refId) {
        LakehouseSnapshotRef ref = refId == null ? null : getById(refId);
        if (ref == null) throw new IllegalArgumentException("快照引用不存在");
        return ref;
    }
}
