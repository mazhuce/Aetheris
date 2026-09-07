package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.aetheris.metadata.dto.ConnectionHealth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;

@TableName(value = "data_source", schema = "metadata")
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank
    private String name;

    @NotBlank
    @Pattern(regexp = "postgresql|greenplum|opengauss|cockroachdb|yugabytedb|mysql|mariadb|tidb|oceanbase|doris|starrocks|hive2|spark")
    private String type;

    @NotBlank
    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean managed;

    private String jdbcUrl;

    private String connectionParams;

    private String description;

    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @JsonIgnore
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long connectionRevision;

    @TableField(exist = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ConnectionHealth connectionHealth = ConnectionHealth.unknown();

    public Long getConnectionRevision() { return connectionRevision; }
    public void setConnectionRevision(Long value) { connectionRevision = value; }
    public ConnectionHealth getConnectionHealth() { return connectionHealth; }
    public void setConnectionHealth(ConnectionHealth value) { connectionHealth = value; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getManaged() { return managed; }
    public void setManaged(Boolean managed) { this.managed = managed; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getConnectionParams() { return connectionParams; }
    public void setConnectionParams(String connectionParams) { this.connectionParams = connectionParams; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
