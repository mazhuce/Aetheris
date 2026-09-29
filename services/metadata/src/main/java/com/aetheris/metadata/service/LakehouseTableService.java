package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.entity.LakehouseTableSnapshot;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LakehouseTableService extends IService<LakehouseTable> {
    LakehouseTable register(LakehouseTable table, String actor);
    LakehouseTableSnapshot recordSnapshot(Long tableId, LakehouseTableSnapshot snapshot, String actor);
    List<LakehouseTableSnapshot> snapshots(Long tableId);
    LakehouseTable setCurrentSnapshot(Long tableId, String snapshotId, String actor);
}
