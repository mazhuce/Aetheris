package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedDataSourceInitializerTest {

    @Test
    void synchronizesManagedPostgresqlCredentialsFromEnvironment() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSource dataSource = new DataSource();
        dataSource.setId(1L);
        dataSource.setName("本地 PostgreSQL");
        dataSource.setType("postgresql");
        dataSource.setManaged(true);
        when(dataSourceService.getOne(any(), eq(false))).thenReturn(dataSource);

        ManagedDataSourceInitializer initializer = new ManagedDataSourceInitializer(
                dataSourceService, true, "postgres", 5432, "aetheris", "aetheris", "real-secret");
        initializer.run();

        verify(dataSourceService).updateById(dataSource);
        assertEquals("postgres", dataSource.getHost());
        assertEquals(5432, dataSource.getPort());
        assertEquals("aetheris", dataSource.getDatabaseName());
        assertEquals("aetheris", dataSource.getUsername());
        assertEquals("real-secret", dataSource.getPassword());
        assertEquals("active", dataSource.getStatus());
        assertTrue(dataSource.getManaged());
    }
}
