package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedDataSourceInitializerTest {
    @Test
    void quarantinesExistingPlatformConnectionWithoutReplacingCredentials() {
        DataSourceService service = mock(DataSourceService.class);
        DataSource source = new DataSource();
        source.setId(1L); source.setManaged(true); source.setStatus("active");
        source.setUsername("platform_owner"); source.setPassword("v1:existing-ciphertext");
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(source));
        when(service.updateById(source)).thenReturn(true);
        new ManagedDataSourceInitializer(service).run();
        assertEquals("isolated", source.getStatus());
        assertEquals("platform_owner", source.getUsername());
        assertEquals("v1:existing-ciphertext", source.getPassword());
        verify(service).updateById(source);
        verify(service, never()).save(any());
    }

    @Test
    void emptyAndAlreadyIsolatedEnvironmentsDoNotRegisterPlatformCredentials() {
        DataSourceService service = mock(DataSourceService.class);
        DataSource source = new DataSource(); source.setStatus("isolated");
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(), List.of(source));
        ManagedDataSourceInitializer initializer = new ManagedDataSourceInitializer(service);
        initializer.run(); initializer.run();
        verify(service, never()).updateById(any());
        verify(service, never()).save(any());
    }

    @Test
    void refusesToSilentlyIgnorePersistenceFailure() {
        DataSourceService service = mock(DataSourceService.class);
        DataSource source = new DataSource(); source.setStatus("active");
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(source));
        assertThrows(IllegalStateException.class, () -> new ManagedDataSourceInitializer(service).run());
    }
}
