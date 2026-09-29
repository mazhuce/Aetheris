package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LakehouseQualityObservation;
import com.aetheris.metadata.mapper.LakehouseQualityObservationMapper;
import com.aetheris.metadata.service.LakehouseQualityGate;
import com.aetheris.metadata.service.LakehouseQualityObservationService;
import com.aetheris.metadata.service.LakehouseTableService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class LakehouseQualityObservationServiceImpl
        extends ServiceImpl<LakehouseQualityObservationMapper, LakehouseQualityObservation>
        implements LakehouseQualityObservationService {
    private final LakehouseTableService tableService;

    public LakehouseQualityObservationServiceImpl(LakehouseTableService tableService) {
        this.tableService = tableService;
    }

    @Override
    @Transactional
    public LakehouseQualityObservation record(Long tableId, LakehouseQualityObservation observation, String actor) {
        if (tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
        if (!Set.of("bronze", "silver", "gold", "other").contains(observation.getLayer())) {
            throw new IllegalArgumentException("质量层级非法");
        }
        if (!StringUtils.hasText(observation.getRuleKey())) throw new IllegalArgumentException("ruleKey 不能为空");
        if (!Set.of("passed", "failed", "unknown").contains(observation.getStatus())) {
            throw new IllegalArgumentException("质量状态非法");
        }
        validateCount(observation.getViolationCount(), "violationCount");
        validateCount(observation.getLateDataCount(), "lateDataCount");
        validateCount(observation.getDeadLetterCount(), "deadLetterCount");
        observation.setLakehouseTableId(tableId);
        observation.setObservedBy(actor);
        if (observation.getObservedAt() == null) observation.setObservedAt(OffsetDateTime.now());
        if (observation.getViolationCount() == null) observation.setViolationCount(0L);
        if (observation.getLateDataCount() == null) observation.setLateDataCount(0L);
        if (observation.getDeadLetterCount() == null) observation.setDeadLetterCount(0L);
        if (observation.getBlocksGold() == null) observation.setBlocksGold(true);
        if (!StringUtils.hasText(observation.getDetailsJson())) observation.setDetailsJson("{}");
        if (!save(observation)) throw new IllegalStateException("质量观测写入失败");
        return getById(observation.getId());
    }

    @Override
    public List<LakehouseQualityObservation> latestForTable(Long tableId) {
        requireTable(tableId);
        List<LakehouseQualityObservation> all = list(new LambdaQueryWrapper<LakehouseQualityObservation>()
                .eq(LakehouseQualityObservation::getLakehouseTableId, tableId)
                .orderByDesc(LakehouseQualityObservation::getObservedAt)
                // Same-timestamp observations are common for a single Flink
                // checkpoint; id is the deterministic tie-breaker so the Gold
                // gate never evaluates an older row by accident.
                .orderByDesc(LakehouseQualityObservation::getId));
        Set<String> seen = new HashSet<>();
        return all.stream()
                .filter(item -> seen.add(item.getLayer() + "|" + item.getRuleKey()))
                .toList();
    }

    @Override
    public LakehouseQualityGate.GateResult goldGate(Long tableId) {
        return LakehouseQualityGate.evaluate(latestForTable(tableId));
    }

    private void requireTable(Long tableId) {
        if (tableId == null || tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
    }

    private static void validateCount(Long value, String field) {
        if (value != null && value < 0) throw new IllegalArgumentException(field + " 不能为负数");
    }
}
