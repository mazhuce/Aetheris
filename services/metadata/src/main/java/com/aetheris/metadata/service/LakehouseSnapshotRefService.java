package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseSnapshotRef;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LakehouseSnapshotRefService extends IService<LakehouseSnapshotRef> {
    LakehouseSnapshotRef create(Long tableId, LakehouseSnapshotRef ref, String actor);
    LakehouseSnapshotRef move(Long refId, String snapshotId, String actor);
    List<LakehouseSnapshotRef> listForTable(Long tableId);
    void removeRef(Long refId);
}
