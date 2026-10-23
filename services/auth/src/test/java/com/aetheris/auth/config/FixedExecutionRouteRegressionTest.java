package com.aetheris.auth.config;

import com.aetheris.security.servlet.RoutePolicy;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FixedExecutionRouteRegressionTest {
    @Test void fixedExecutionHasItsOwnMetadataOnlyNoUserRoute() {
        var route=AuthV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/permissions/fixed-execution-authorize");
        assertTrue(route.isPresent(),"The frozen fixed-execution contract must be reachable");
        assertEquals("auth.fixed-execution.authorize",route.orElseThrow().operation());
        assertEquals(Set.of("metadata"),route.orElseThrow().allowedCallers());
        assertEquals(RoutePolicy.UserMode.FORBIDDEN,route.orElseThrow().userMode());
    }
}
