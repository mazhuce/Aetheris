package com.aetheris.system.security;

import com.aetheris.system.config.WebConfig;
import com.aetheris.system.controller.SysUserController;
import com.aetheris.system.controller.SystemConfigController;
import com.aetheris.system.service.SysUserService;
import com.aetheris.system.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(controllers = {SysUserController.class, SystemConfigController.class},
        properties = "aetheris.internal-token=internal-test-token")
@Import({WebConfig.class, AdminEndpointInterceptor.class, SystemIdentityInterceptor.class,
        IdentityVerifier.class})
@org.springframework.test.context.ContextConfiguration(classes = {SysUserController.class, SystemConfigController.class})
class SystemManagementMvcTest {
    @Autowired MockMvc mvc;
    @MockBean AuthPermissionClient permissions;
    @MockBean SysUserService users;
    @MockBean SystemConfigService configs;

    @Test void usersAndConfigsRejectUnsignedAndViewerReads() throws Exception {
        for (String path : java.util.List.of("/api/system/users", "/api/system/configs", "/api/system/configs/key")) {
            mvc.perform(get(path).header("X-User-Name", "admin")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(V2TestFixture.trusted(42,"viewer")))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void signedManagerCanReadAndUnsignedWritesAreRejected() throws Exception {
        when(permissions.mayManage("admin")).thenReturn(true);
        when(configs.list()).thenReturn(java.util.List.of());
        mvc.perform(get("/api/system/configs").with(V2TestFixture.trusted(1,"admin"))).andExpect(status().isOk());
        mvc.perform(delete("/api/system/users/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/system/configs")).andExpect(status().isUnauthorized());
    }
    @Test void entityExtensionsCannotLeakThroughManagementResponse() throws Exception {
        class ExtendedConfig extends com.aetheris.system.entity.SystemConfig {
            public String getInternalSecret() { return "must-not-serialize"; }
        }
        var config = new ExtendedConfig(); config.setConfigKey("theme"); config.setConfigValue("light");
        when(permissions.mayManage("admin")).thenReturn(true);
        when(configs.list()).thenReturn(java.util.List.of(config));
        mvc.perform(get("/api/system/configs").with(V2TestFixture.trusted(1,"admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].configValue").value("light"))
                .andExpect(jsonPath("$[0].internalSecret").doesNotExist());
    }
}
