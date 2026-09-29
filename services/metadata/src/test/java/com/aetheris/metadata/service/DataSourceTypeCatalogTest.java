package com.aetheris.metadata.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataSourceTypeCatalogTest {
    @Test
    void catalogContainsUniqueTypesAndExpandedJdbcFamilies() {
        var definitions = DataSourceTypeCatalog.all();
        assertEquals(21, definitions.size());
        assertEquals(definitions.size(), new HashSet<>(definitions.stream().map(DataSourceTypeCatalog.Definition::type).toList()).size());
        assertEquals(5432, DataSourceTypeCatalog.require("POSTGRESQL").defaultPort());
        assertEquals("Trino", DataSourceTypeCatalog.require("trino").label());
        assertEquals("Apache Hive", DataSourceTypeCatalog.require("hive2").label());
        assertEquals("H2", DataSourceTypeCatalog.require("h2").label());
        assertEquals("Apache Derby", DataSourceTypeCatalog.require("derby").label());
    }

    @Test
    void rejectsTypesOutsideTheJdbcAdapterContract() {
        assertThrows(IllegalArgumentException.class, () -> DataSourceTypeCatalog.require("kafka"));
        assertThrows(IllegalArgumentException.class, () -> DataSourceTypeCatalog.require(null));
    }
}
