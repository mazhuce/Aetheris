package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.config.MetadataV2SecurityConfiguration;
import com.aetheris.security.servlet.RoutePolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedQualityRouteRegressionTest {
    @Test void qualityHasItsOwnFixedSubmitAndStatusCallerPolicy(){
        var policy=MetadataV2SecurityConfiguration.workloadRoutes();
        var route=policy.resolve("POST","/internal/v2/executions/quality-query/submit").orElse(null);
        assertNotNull(route);assertEquals("metadata.fixed-quality-query.submit",route.operation());assertEquals(java.util.Set.of("quality"),route.allowedCallers());assertEquals(RoutePolicy.UserMode.FORBIDDEN,route.userMode());
        assertTrue(policy.resolve("POST","/internal/v2/executions/status").orElseThrow().allowedCallers().contains("quality"));
    }
}
