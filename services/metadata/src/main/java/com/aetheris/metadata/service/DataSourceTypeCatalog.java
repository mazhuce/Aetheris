package com.aetheris.metadata.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend source of truth for data-source types exposed by the JDBC adapter.
 * The UI may cache a copy, but create/update/probe must validate against this
 * catalog so a newly displayed type cannot silently become an unsupported row.
 */
public final class DataSourceTypeCatalog {
    private static final List<Definition> DEFINITIONS = List.of(
            definition("postgresql", "PostgreSQL", "关系型数据库", 5432, "postgres", "批量写入", "首批 CDC"),
            definition("timescaledb", "TimescaleDB", "分布式数据库", 5432, "postgres", "批量写入"),
            definition("mysql", "MySQL", "关系型数据库", 3306, "mysql", "批量写入", "首批 CDC"),
            definition("mariadb", "MariaDB", "关系型数据库", 3306, "mysql", "批量写入"),
            definition("greenplum", "Greenplum", "分布式数据库", 5432, "postgres", "批量写入"),
            definition("opengauss", "openGauss", "分布式数据库", 5432, "postgres", "批量写入"),
            definition("cockroachdb", "CockroachDB", "分布式数据库", 26257, "defaultdb", "批量写入"),
            definition("yugabytedb", "YugabyteDB", "分布式数据库", 5433, "yugabyte", "批量写入"),
            definition("tidb", "TiDB", "分布式数据库", 4000, "test", "批量写入"),
            definition("oceanbase", "OceanBase", "分布式数据库", 2881, "test", "批量写入"),
            definition("doris", "Apache Doris", "数仓与湖仓", 9030, "information_schema", "批量写入"),
            definition("starrocks", "StarRocks", "数仓与湖仓", 9030, "information_schema", "批量写入"),
            definition("hive2", "Apache Hive", "数仓与湖仓", 10000, "default", "Hive/Spark SQL 写入"),
            definition("spark", "Spark Thrift Server", "数仓与湖仓", 10000, "default", "Hive/Spark SQL 写入"),
            definition("sqlserver", "Microsoft SQL Server", "关系型数据库", 1433, "master"),
            definition("oracle", "Oracle Database", "关系型数据库", 1521, "ORCL"),
            definition("sqlite", "SQLite", "关系型数据库", 0, "aetheris.db"),
            definition("h2", "H2", "关系型数据库", 0, "mem:aetheris"),
            definition("derby", "Apache Derby", "关系型数据库", 0, "memory:aetheris"),
            definition("clickhouse", "ClickHouse", "数仓与湖仓", 8123, "default"),
            definition("trino", "Trino", "企业与联邦查询", 8080, "hive")
    );

    private static final Map<String, Definition> BY_TYPE = index(DEFINITIONS);

    private DataSourceTypeCatalog() { }

    public static List<Definition> all() {
        return DEFINITIONS;
    }

    public static Definition require(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        Definition definition = BY_TYPE.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException("暂不支持的数据源类型：" + type);
        }
        return definition;
    }

    private static Definition definition(String type, String label, String category,
                                         int defaultPort, String defaultDatabase,
                                         String... capabilities) {
        return new Definition(type, label, category, defaultPort, defaultDatabase, List.of(capabilities));
    }

    private static Map<String, Definition> index(List<Definition> definitions) {
        Map<String, Definition> result = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            if (result.put(definition.type(), definition) != null) {
                throw new IllegalStateException("duplicate data-source type: " + definition.type());
            }
        }
        return Map.copyOf(result);
    }

    public record Definition(String type, String label, String category, int defaultPort,
                             String defaultDatabase, List<String> capabilities) { }
}
