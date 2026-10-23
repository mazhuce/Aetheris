package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.config.MetadataV2SecurityConfiguration;
import com.aetheris.security.servlet.RoutePolicy;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PublishedKeyRouteRegressionTest {
    @Test void onlyServingMayResolveAndExecuteWithoutAUserProof() {
        var policy=MetadataV2SecurityConfiguration.workloadRoutes();
        for(var entry:java.util.Map.of("resolve","metadata.published-asset.resolve","key-query","metadata.published-key.query").entrySet()) {
            var route=policy.resolve("POST","/internal/v2/published-api-assets/"+entry.getKey()).orElse(null);
            assertNotNull(route);assertEquals(entry.getValue(),route.operation());
            assertEquals(Set.of("serving"),route.allowedCallers());assertEquals(RoutePolicy.UserMode.FORBIDDEN,route.userMode());
            assertTrue(policy.resolve("GET","/internal/v2/published-api-assets/"+entry.getKey()).isEmpty());
        }
        assertTrue(policy.resolve("POST","/internal/v2/execute").isEmpty());
    }
}
