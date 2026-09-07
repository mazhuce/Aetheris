package com.aetheris.metadata.mapper;

import com.aetheris.metadata.entity.DataSourceHealthObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface DataSourceHealthMapper {
    String COLUMNS = "datasource_id, configuration_revision, status, probe_token, started_at, "
            + "checked_at, last_success_at, expires_at, latency_ms, failure_code, failure_message";

    @Select("SELECT " + COLUMNS + " FROM metadata.datasource_connection_health WHERE datasource_id = #{id}")
    DataSourceHealthObservation find(@Param("id") Long id);

    @Select("<script>SELECT " + COLUMNS + " FROM metadata.datasource_connection_health WHERE datasource_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<DataSourceHealthObservation> findAll(@Param("ids") List<Long> ids);

    @Update("""
            INSERT INTO metadata.datasource_connection_health AS health
                (datasource_id, configuration_revision, status, probe_token, started_at, expires_at)
            SELECT id, connection_revision, 'checking', #{token}, #{startedAt}, #{expiresAt}
            FROM metadata.data_source WHERE id = #{id} AND connection_revision = #{revision}
            ON CONFLICT (datasource_id) DO UPDATE
            SET configuration_revision = EXCLUDED.configuration_revision, status = 'checking',
                probe_token = EXCLUDED.probe_token, started_at = EXCLUDED.started_at,
                expires_at = EXCLUDED.expires_at, checked_at = NULL, latency_ms = NULL,
                failure_code = NULL, failure_message = NULL
            WHERE health.configuration_revision <= EXCLUDED.configuration_revision
              AND (health.status != 'checking' OR health.expires_at <= #{startedAt}
                   OR health.configuration_revision < EXCLUDED.configuration_revision)
            """)
    int claim(@Param("id") Long id, @Param("revision") long revision, @Param("token") String token,
              @Param("startedAt") OffsetDateTime startedAt, @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("""
            UPDATE metadata.datasource_connection_health AS health
            SET status = #{status}, checked_at = #{checkedAt}, expires_at = #{expiresAt},
                last_success_at = CASE WHEN #{status} = 'available' THEN #{checkedAt} ELSE last_success_at END,
                latency_ms = #{latencyMs}, failure_code = #{failureCode}, failure_message = #{failureMessage},
                probe_token = NULL
            WHERE datasource_id = #{id} AND configuration_revision = #{revision} AND probe_token = #{token}
                AND EXISTS (SELECT 1 FROM metadata.data_source ds
                            WHERE ds.id = #{id} AND ds.connection_revision = #{revision})
            """)
    int complete(@Param("id") Long id, @Param("revision") long revision, @Param("token") String token,
                 @Param("status") String status, @Param("checkedAt") OffsetDateTime checkedAt,
                 @Param("expiresAt") OffsetDateTime expiresAt, @Param("latencyMs") Long latencyMs,
                 @Param("failureCode") String failureCode, @Param("failureMessage") String failureMessage);
}
