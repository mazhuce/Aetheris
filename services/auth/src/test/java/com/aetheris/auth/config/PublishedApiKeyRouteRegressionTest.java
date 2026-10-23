package com.aetheris.auth.config;
import com.aetheris.security.servlet.RoutePolicy;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class PublishedApiKeyRouteRegressionTest {
    @Test void dedicatedPublishedKeyDecisionIsMetadataOnlyAndNeverAcceptsUser(){
        var route=AuthV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/permissions/published-key-query-authorize");
        assertTrue(route.isPresent());assertEquals("auth.published-key-query.authorize",route.orElseThrow().operation());
        assertEquals(Set.of("metadata"),route.orElseThrow().allowedCallers());assertEquals(RoutePolicy.UserMode.FORBIDDEN,route.orElseThrow().userMode());
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("GET","/internal/v2/permissions/published-key-query-authorize").isEmpty());
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/permissions/published-key-query-authorize/child").isEmpty());
    }
}
