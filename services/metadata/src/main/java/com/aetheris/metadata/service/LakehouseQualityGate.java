package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseQualityObservation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Gold 发布的 fail-closed 判定；只消费每条规则的最新观测。 */
public final class LakehouseQualityGate {
    private LakehouseQualityGate() { }

    public static GateResult evaluate(List<LakehouseQualityObservation> observations) {
        Set<String> blocking = observations.stream()
                .filter(observation -> Boolean.TRUE.equals(observation.getBlocksGold()))
                .filter(observation -> "failed".equals(observation.getStatus()) || "unknown".equals(observation.getStatus()))
                .map(LakehouseQualityObservation::getRuleKey)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return new GateResult(blocking.isEmpty(), List.copyOf(blocking));
    }

    public record GateResult(boolean allowed, List<String> blockingRuleKeys) { }
}
