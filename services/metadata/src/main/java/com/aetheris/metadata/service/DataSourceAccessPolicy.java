package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;

/** Saved business connections require an explicit active state; missing/unknown states never grant access. */
public final class DataSourceAccessPolicy {
    private DataSourceAccessPolicy() { }

    public static boolean isActiveBusinessSource(DataSource source) {
        return source != null && !Boolean.TRUE.equals(source.getManaged()) && "active".equals(source.getStatus());
    }

    public static void requireActiveBusinessSource(DataSource source) {
        if (!isActiveBusinessSource(source))
            throw new IllegalArgumentException("仅明确启用的业务数据源可建立连接");
    }

    /** A separately authorized pre-save probe tests submitted connection fields, never a persisted identity. */
    static DataSource connectionTestDraft(DataSource source) {
        if (source == null || Boolean.TRUE.equals(source.getManaged())
                || source.getStatus() != null && !"active".equals(source.getStatus()) && !"inactive".equals(source.getStatus()))
            throw new IllegalArgumentException("保存前测试仅接受业务连接草稿");
        var draft = new DataSource();
        draft.setType(source.getType()); draft.setHost(source.getHost()); draft.setPort(source.getPort());
        draft.setDatabaseName(source.getDatabaseName()); draft.setUsername(source.getUsername());
        draft.setPassword(source.getPassword()); draft.setJdbcUrl(source.getJdbcUrl());
        draft.setConnectionParams(source.getConnectionParams()); draft.setManaged(false); draft.setStatus("active");
        return draft;
    }
}
