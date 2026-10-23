package com.aetheris.security.servlet;

import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** One committed database claim across receiver replicas; no in-memory or availability fallback. */
public final class PostgresNonceStore implements NonceStore {
    private static final Set<String> SCHEMAS=Set.of("auth","system","metadata","quality","ingestion","governance","workflow","ai","serving");
    private static final long RETENTION_SECONDS=86400;
    private final DataSource dataSource;
    private final Clock clock;
    private final String table;

    /** schema must be the fixed receiver-owned schema, never a request parameter. No DDL is performed here. */
    public PostgresNonceStore(DataSource dataSource,String schema,Clock clock) {
        if(dataSource==null || !SCHEMAS.contains(schema==null?"":schema) || clock==null)
            throw new IllegalArgumentException("Invalid nonce store configuration");
        this.dataSource=dataSource;this.clock=clock;this.table="\""+schema+"\".internal_request_nonce";
    }

    @Override public boolean claim(String caller,String nonce,long expiresAt) throws SQLException {
        long now=clock.instant().getEpochSecond();
        if(!ProtocolInstallation.SERVICES.contains(caller==null?"":caller) || !validNonce(nonce)
                || expiresAt<=now || expiresAt-now>70)
            throw new IllegalArgumentException("Invalid nonce claim");
        try(Connection connection=dataSource.getConnection()) {
            requireAutoCommit(connection);
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO "+table+
                    " (caller,nonce,expires_at) VALUES (?,?,?) ON CONFLICT (caller,nonce) DO NOTHING")) {
                statement.setQueryTimeout(2);statement.setString(1,caller);statement.setString(2,nonce);statement.setLong(3,expiresAt);
                return statement.executeUpdate()==1;
            }
        }
    }

    /** Bounded maintenance, keeping an extra day after signature expiry. Never deletes execution receipts. */
    public int purgeExpired(int limit) throws SQLException {
        if(limit<1 || limit>10000)throw new IllegalArgumentException("Invalid nonce retention batch size");
        long cutoff=clock.instant().getEpochSecond()-RETENTION_SECONDS;
        try(Connection connection=dataSource.getConnection()) {
            requireAutoCommit(connection);
            String sql="WITH expired AS (SELECT caller,nonce FROM "+table+
                    " WHERE expires_at<=? ORDER BY expires_at,caller,nonce LIMIT ?) DELETE FROM "+table+
                    " AS stored USING expired WHERE stored.caller=expired.caller AND stored.nonce=expired.nonce";
            try(PreparedStatement statement=connection.prepareStatement(sql)) {
                statement.setQueryTimeout(2);statement.setLong(1,cutoff);statement.setInt(2,limit);
                return statement.executeUpdate();
            }
        }
    }
    private static void requireAutoCommit(Connection connection) throws SQLException {
        // Never acknowledge an uncommitted claim or commit a transaction owned by a caller.
        if(!connection.getAutoCommit())throw new SQLException("Nonce store requires an independent autocommit connection");
    }
    private static boolean validNonce(String value) {
        if(value==null || value.length()<22 || value.length()>86 || !value.matches("[A-Za-z0-9_-]+"))return false;
        try {
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            return bytes.length>=16 && bytes.length<=64 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
        }catch(IllegalArgumentException invalid){return false;}
    }
}
