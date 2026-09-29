package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseSchemaChange;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LakehouseSchemaChangeService extends IService<LakehouseSchemaChange> {
    LakehouseSchemaChange propose(Long tableId, LakehouseSchemaChange change, String actor);
    LakehouseSchemaChange review(Long id, boolean approved, String actor);
    LakehouseSchemaChange isolate(Long id, String actor);
    List<LakehouseSchemaChange> listForTable(Long tableId);
    LakehouseSchemaChange execute(Long id);
    LakehouseSchemaChange reconcile(Long id);
    LakehouseSchemaChange cancel(Long id);
}
