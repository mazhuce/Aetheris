package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseQualityObservation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LakehouseQualityObservationService extends IService<LakehouseQualityObservation> {
    LakehouseQualityObservation record(Long tableId, LakehouseQualityObservation observation, String actor);
    List<LakehouseQualityObservation> latestForTable(Long tableId);
    LakehouseQualityGate.GateResult goldGate(Long tableId);
}
