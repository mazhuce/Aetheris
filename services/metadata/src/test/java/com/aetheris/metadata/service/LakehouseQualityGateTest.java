package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseQualityObservation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakehouseQualityGateTest {
    @Test
    void failedBlockingRuleStopsGold() {
        LakehouseQualityObservation observation = new LakehouseQualityObservation();
        observation.setRuleKey("orders.not_null");
        observation.setStatus("failed");
        observation.setBlocksGold(true);
        LakehouseQualityGate.GateResult result = LakehouseQualityGate.evaluate(List.of(observation));
        assertFalse(result.allowed());
        assertTrue(result.blockingRuleKeys().contains("orders.not_null"));
    }

    @Test
    void nonBlockingFailureDoesNotStopGold() {
        LakehouseQualityObservation observation = new LakehouseQualityObservation();
        observation.setRuleKey("orders.warning");
        observation.setStatus("failed");
        observation.setBlocksGold(false);
        assertTrue(LakehouseQualityGate.evaluate(List.of(observation)).allowed());
    }
}
