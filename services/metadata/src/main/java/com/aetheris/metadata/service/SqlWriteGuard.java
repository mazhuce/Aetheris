package com.aetheris.metadata.service;

/** Warehouse writes share the lexer, function policy and structural parser used by reads. */
public final class SqlWriteGuard {
    private SqlWriteGuard() { }

    public static String validate(String sql) { return SqlSafetyPolicy.warehouseWrite(sql); }

    public static boolean isWriteStatement(String sql) { return SqlSafetyPolicy.isWrite(sql); }
}
