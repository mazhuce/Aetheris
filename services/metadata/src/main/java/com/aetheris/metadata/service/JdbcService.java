package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class JdbcService {

    public Map<String, Object> test(DataSource dataSource) {
        long start = System.currentTimeMillis();
        try {
            DriverManager.setLoginTimeout(5);
            try (Connection connection = connect(dataSource)) {
                long cost = System.currentTimeMillis() - start;
                return Map.of(
                        "success", true,
                        "message", "连接成功",
                        "latencyMs", cost
                );
            }
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", e.getMessage()
            );
        }
    }

    public Map<String, Object> query(DataSource dataSource, String sql) {
        String trimmed = sql == null ? "" : sql.trim().toLowerCase();
        if (!(trimmed.startsWith("select") || trimmed.startsWith("show") || trimmed.startsWith("explain"))) {
            throw new IllegalArgumentException("仅支持 SELECT / SHOW / EXPLAIN 查询");
        }

        DriverManager.setLoginTimeout(5);
        try (Connection connection = connect(dataSource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {

            ResultSetMetaData meta = resultSet.getMetaData();
            int columnCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }

            List<List<Object>> rows = new ArrayList<>();
            int maxRows = 100;
            boolean truncated = false;
            while (resultSet.next()) {
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(resultSet.getObject(i));
                }
                rows.add(row);
            }

            return Map.of(
                    "columns", columns,
                    "rows", rows,
                    "rowCount", rows.size(),
                    "truncated", truncated
            );
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + e.getMessage());
        }
    }

    private Connection connect(DataSource dataSource) throws SQLException {
        String type = dataSource.getType() == null ? "" : dataSource.getType().toLowerCase();
        int port = dataSource.getPort() == null ? defaultPort(type) : dataSource.getPort();
        return DriverManager.getConnection(buildUrl(dataSource, type, port), dataSource.getUsername(), dataSource.getPassword());
    }

    private String buildUrl(DataSource dataSource, String type, int port) {
        String database = dataSource.getDatabaseName();
        switch (type) {
            case "postgresql":
                return "jdbc:postgresql://" + dataSource.getHost() + ":" + port + "/" + database;
            case "mysql":
                return "jdbc:mysql://" + dataSource.getHost() + ":" + port + "/" + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            case "hive2":
            case "spark":
                return "jdbc:hive2://" + dataSource.getHost() + ":" + port + "/" + (database == null ? "default" : database);
            default:
                throw new IllegalArgumentException("暂不支持的数据源类型：" + dataSource.getType());
        }
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "postgresql" -> 5432;
            case "mysql" -> 3306;
            case "hive2", "spark" -> 10000;
            default -> 0;
        };
    }
}
