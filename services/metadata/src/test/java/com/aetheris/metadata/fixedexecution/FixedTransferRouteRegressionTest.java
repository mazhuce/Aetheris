package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.config.MetadataV2SecurityConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedTransferRouteRegressionTest {
    @Test void durableTransferSubmitAndStatusMustHaveExplicitRoutes() {
        var routes=MetadataV2SecurityConfiguration.workloadRoutes();
        assertTrue(routes.resolve("POST","/internal/v2/executions/transfer/submit").isPresent());
        assertTrue(routes.resolve("POST","/internal/v2/executions/status").isPresent());
    }
}
