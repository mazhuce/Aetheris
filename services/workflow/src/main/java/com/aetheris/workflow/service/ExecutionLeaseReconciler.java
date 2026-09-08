package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically turns expired local claims into unknown instead of guessing failure. */
@Component
public class ExecutionLeaseReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLeaseReconciler.class);
    private final ExecutionClaimCoordinator coordinator;

    public ExecutionLeaseReconciler(ExecutionClaimCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${aetheris.execution.reconcile-interval-ms:30000}")
    public void reconcile() {
        int updated = coordinator.reconcileExpiredLeases();
        if (updated > 0) {
            log.warn("Marked {} expired execution lease(s) unknown", updated);
        }
    }
}
