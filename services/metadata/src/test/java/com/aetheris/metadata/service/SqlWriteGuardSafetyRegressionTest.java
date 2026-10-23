package com.aetheris.metadata.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlWriteGuardSafetyRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t\nSELECT pg_sleep(1)",
            "INSERT OVERWRITE DIRECTORY '/tmp/no-file' SELECT 1",
            "INSERT OVERWRITE LOCAL DIRECTORY '/tmp/no-file' SELECT 1",
            "CREATE EXTERNAL TABLE t(id INT) LOCATION '/tmp/no-file'",
            "CREATE TABLE t(id INT) LOCATION '/tmp/no-file'",
            "INSERT INTO t SELECT dblink_exec('host=invalid', 'DROP TABLE x')",
            "INSERT INTO t SELECT \"pg_read_file\"('/etc/passwd')",
            "INSERT INTO t SELECT unknown_function(1)",
            "INSERT INTO warehouse.count SELECT evil.count(1)",
            "CREATE TABLE warehouse.count AS SELECT evil.count(1)",
            "CREATE TABLE t(id INT) STORED BY 'evil.Handler'",
            "INSERT INTO t SELECT 1 /*! ; DROP TABLE t */"
    })
    void rejectsDangerousWarehouseExtensions(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlWriteGuard.validate(sql));
    }
}
