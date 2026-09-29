package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LakehouseMaintenanceJobService extends IService<LakehouseMaintenanceJob> {
    LakehouseMaintenanceJob request(Long tableId, LakehouseMaintenanceJob job, String actor);
    List<LakehouseMaintenanceJob> listForTable(Long tableId);
    LakehouseMaintenanceJob claim(Long jobId);
    LakehouseMaintenanceJob execute(Long jobId);
    LakehouseMaintenanceJob reconcile(Long jobId);
    LakehouseMaintenanceJob complete(Long jobId, String status, String resultJson, String error);
    LakehouseMaintenanceJob cancel(Long jobId);
    LakehouseMaintenancePlan.Plan preview(Long jobId);
}
