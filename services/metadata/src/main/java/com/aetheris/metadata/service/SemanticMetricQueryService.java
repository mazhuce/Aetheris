package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.LogicalModel;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.entity.SemanticDimension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/** 将已发布指标编译为受控、只读 SQL；消费方不提交任意 SQL。 */
@Service
public class SemanticMetricQueryService {
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX");
    private static final Set<String> FILTER_OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "not_in", "is_null", "is_not_null");
    private static final Set<String> TIME_GRAINS = Set.of("none", "day", "month", "year");
    private final MetadataTableService tableService;
    private final DataSourceService dataSourceService;
    private final SemanticDimensionService dimensionService;
    private final LogicalModelService logicalModelService;
    private final BusinessDomainService domainService;
    private final JdbcService jdbcService;
    private final ObjectMapper objectMapper;

    public SemanticMetricQueryService(MetadataTableService tableService, DataSourceService dataSourceService,
                                      SemanticDimensionService dimensionService, LogicalModelService logicalModelService,
                                      BusinessDomainService domainService, JdbcService jdbcService,
                                      ObjectMapper objectMapper) {
        this.tableService = tableService;
        this.dataSourceService = dataSourceService;
        this.dimensionService = dimensionService;
        this.logicalModelService = logicalModelService;
        this.domainService = domainService;
        this.jdbcService = jdbcService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> validate(MetricDefinition metric) {
        ValidationContext context = context(metric);
        return Map.of("valid", true, "sourceTable", qualifiedName(context.table()),
                "columns", context.columns().size(), "dimensions", context.defaultDimensions().size(),
                "message", "指标口径、数据表、字段、维度和模型绑定校验通过");
    }

    public Map<String, Object> query(MetricDefinition metric, MetricQueryRequest request) {
        ValidationContext context = context(metric);
        MetricQueryRequest actual = request == null ? new MetricQueryRequest(List.of(), null, null, null, null) : request;
        List<SemanticDimension> dimensions = resolveDimensions(context, actual.dimensions());
        String grain = StringUtils.hasText(actual.timeGrain())
                ? actual.timeGrain().trim().toLowerCase(Locale.ROOT)
                : defaultValue(metric.getDefaultTimeGrain(), "none");
        if (!TIME_GRAINS.contains(grain)) throw new IllegalArgumentException("时间粒度仅支持 none/day/month/year");
        if (!"none".equals(grain) && !StringUtils.hasText(metric.getTimeColumn())) {
            throw new IllegalArgumentException("指标未配置时间字段，不能按时间粒度查询");
        }

        String sql = compile(metric, context, dimensions, grain, actual);
        Map<String, Object> raw = jdbcService.query(context.dataSource(), sql);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricCode", metric.getMetricCode());
        result.put("metricName", metric.getMetricName());
        result.put("version", metric.getVersion());
        result.put("unit", metric.getUnit());
        result.put("dimensions", dimensions.stream().map(SemanticDimension::getDimensionCode).toList());
        result.put("timeGrain", grain);
        result.put("generatedSql", sql);
        result.putAll(raw);
        return result;
    }

    private ValidationContext context(MetricDefinition metric) {
        if (metric.getBusinessDomainId() != null) domainService.require(metric.getBusinessDomainId());
        if (metric.getSourceTableId() == null) throw new IllegalArgumentException("指标必须绑定来源表");
        MetadataTable table = tableService.getById(metric.getSourceTableId());
        if (table == null || "stale".equals(table.getStatus())) throw new IllegalArgumentException("指标来源表不存在或已失效");
        DataSource source = table.getDatasourceId() == null ? null : dataSourceService.getById(table.getDatasourceId());
        if (source == null) throw new IllegalArgumentException("指标来源表所属数据源不存在");
        List<DataSourceColumnDto> columns = jdbcService.listColumns(source, table.getSchemaName(), table.getTableName());
        Set<String> columnNames = new HashSet<>();
        columns.forEach(column -> columnNames.add(column.name().toLowerCase(Locale.ROOT)));

        String aggregation = defaultValue(metric.getAggregation(), "COUNT").toUpperCase(Locale.ROOT);
        if (!AGGREGATIONS.contains(aggregation)) throw new IllegalArgumentException("不支持的聚合方式：" + aggregation);
        if (!"COUNT".equals(aggregation)) requireColumn(metric.getMeasureColumn(), "度量字段", columnNames);
        else if (StringUtils.hasText(metric.getMeasureColumn())) requireColumn(metric.getMeasureColumn(), "度量字段", columnNames);
        if (StringUtils.hasText(metric.getTimeColumn())) requireColumn(metric.getTimeColumn(), "时间字段", columnNames);

        List<FilterSpec> filters = parseFilters(metric.getFiltersJson());
        for (FilterSpec filter : filters) {
            requireColumn(filter.column(), "过滤字段", columnNames);
            if (!FILTER_OPERATORS.contains(filter.operator())) throw new IllegalArgumentException("不支持的过滤操作：" + filter.operator());
            if (!filter.operator().startsWith("is_") && (filter.value() == null || filter.value().isNull())) {
                throw new IllegalArgumentException("过滤条件 " + filter.column() + " 缺少值");
            }
        }

        List<SemanticDimension> defaultDimensions = new ArrayList<>();
        for (Long id : parseDimensionIds(metric.getDimensionIdsJson())) {
            SemanticDimension dimension = dimensionService.requirePublished(id);
            validateDimension(dimension, table, columnNames);
            defaultDimensions.add(dimension);
        }

        if (metric.getLogicalModelId() != null) {
            LogicalModel model = logicalModelService.requirePublished(metric.getLogicalModelId());
            boolean bound = StreamSupport.stream(parse(model.getEntitiesJson()).path("entities").spliterator(), false)
                    .anyMatch(entity -> entity.path("tableId").asLong(-1) == table.getId());
            if (!bound) throw new IllegalArgumentException("来源表未包含在绑定的逻辑模型中");
        }
        return new ValidationContext(table, source, columnNames, filters, defaultDimensions);
    }

    private List<SemanticDimension> resolveDimensions(ValidationContext context, List<String> requested) {
        if (requested == null || requested.isEmpty()) return context.defaultDimensions();
        if (requested.size() > 8) throw new IllegalArgumentException("单次查询最多使用 8 个维度");
        List<SemanticDimension> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : requested) {
            if (!StringUtils.hasText(code) || !seen.add(code.toUpperCase(Locale.ROOT))) continue;
            SemanticDimension dimension = dimensionService.findPublishedByCode(code);
            if (dimension == null) throw new IllegalArgumentException("维度未发布或不存在：" + code);
            validateDimension(dimension, context.table(), context.columns());
            result.add(dimension);
        }
        return result;
    }

    private void validateDimension(SemanticDimension dimension, MetadataTable table, Set<String> columns) {
        if (!"published".equals(dimension.getStatus())) throw new IllegalArgumentException("维度未发布：" + dimension.getDimensionCode());
        if (!table.getId().equals(dimension.getMetadataTableId())) throw new IllegalArgumentException("维度与指标来源表不一致：" + dimension.getDimensionCode());
        requireColumn(dimension.getColumnName(), "维度字段", columns);
    }

    private String compile(MetricDefinition metric, ValidationContext context, List<SemanticDimension> dimensions,
                           String grain, MetricQueryRequest request) {
        boolean postgres = postgresLike(context.dataSource());
        String tableName = qualifiedName(context.table(), postgres);
        List<String> selects = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (SemanticDimension dimension : dimensions) {
            String column = quote(dimension.getColumnName(), postgres);
            selects.add(column + " AS " + quote(dimension.getDimensionCode().toLowerCase(Locale.ROOT), postgres));
            groups.add(column);
        }
        if (!"none".equals(grain)) {
            String bucket = timeBucket(quote(metric.getTimeColumn(), postgres), grain, context.dataSource());
            selects.add(bucket + " AS " + quote("time_bucket", postgres));
            groups.add(bucket);
        }
        selects.add(aggregate(metric, postgres) + " AS " + quote("metric_value", postgres));

        List<String> where = new ArrayList<>();
        for (FilterSpec filter : context.filters()) where.add(compileFilter(filter, postgres));
        LocalDate start = parseDate(request.startDate(), "开始日期");
        LocalDate end = parseDate(request.endDate(), "结束日期");
        if (start != null || end != null) {
            if (!StringUtils.hasText(metric.getTimeColumn())) throw new IllegalArgumentException("按日期查询前必须为指标配置时间字段");
            if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
            String time = quote(metric.getTimeColumn(), postgres);
            if (start != null) where.add(time + " >= " + literal(start.toString()));
            if (end != null) where.add(time + " < " + literal(end.plusDays(1).toString()));
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selects)).append(" FROM ").append(tableName);
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
        if (!groups.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", groups));
        if (!groups.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", groups));
        sql.append(" LIMIT ").append(request.limit() == null ? 100 : Math.clamp(request.limit(), 1, 100));
        return sql.toString();
    }

    private String aggregate(MetricDefinition metric, boolean postgres) {
        String aggregation = defaultValue(metric.getAggregation(), "COUNT").toUpperCase(Locale.ROOT);
        String column = StringUtils.hasText(metric.getMeasureColumn()) ? quote(metric.getMeasureColumn(), postgres) : "*";
        return switch (aggregation) {
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + column + ")";
            case "COUNT" -> "COUNT(" + column + ")";
            default -> aggregation + "(" + column + ")";
        };
    }

    private String compileFilter(FilterSpec filter, boolean postgres) {
        String column = quote(filter.column(), postgres);
        return switch (filter.operator()) {
            case "eq" -> column + " = " + literal(filter.value());
            case "ne" -> column + " <> " + literal(filter.value());
            case "gt" -> column + " > " + literal(filter.value());
            case "gte" -> column + " >= " + literal(filter.value());
            case "lt" -> column + " < " + literal(filter.value());
            case "lte" -> column + " <= " + literal(filter.value());
            case "is_null" -> column + " IS NULL";
            case "is_not_null" -> column + " IS NOT NULL";
            case "in", "not_in" -> {
                List<JsonNode> values = new ArrayList<>();
                if (filter.value().isArray()) filter.value().forEach(values::add);
                else for (String value : filter.value().asText().split(",")) values.add(objectMapper.valueToTree(value.trim()));
                if (values.isEmpty() || values.size() > 100) throw new IllegalArgumentException("IN 过滤值数量必须在 1 到 100 之间");
                yield column + ("not_in".equals(filter.operator()) ? " NOT IN (" : " IN (")
                        + String.join(", ", values.stream().map(this::literal).toList()) + ")";
            }
            default -> throw new IllegalArgumentException("不支持的过滤操作");
        };
    }

    private List<FilterSpec> parseFilters(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        JsonNode root = parse(value);
        if (!root.isArray()) throw new IllegalArgumentException("过滤条件必须是数组");
        if (root.size() > 20) throw new IllegalArgumentException("单个指标最多配置 20 个过滤条件");
        List<FilterSpec> result = new ArrayList<>();
        for (JsonNode node : root) {
            String column = node.path("column").asText("").trim();
            String operator = node.path("operator").asText("eq").trim().toLowerCase(Locale.ROOT);
            if (column.isBlank()) throw new IllegalArgumentException("过滤字段不能为空");
            result.add(new FilterSpec(column, operator, node.get("value")));
        }
        return result;
    }

    private List<Long> parseDimensionIds(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        JsonNode root = parse(value);
        if (!root.isArray()) throw new IllegalArgumentException("维度绑定必须是数组");
        List<Long> result = new ArrayList<>();
        root.forEach(node -> { if (node.canConvertToLong()) result.add(node.asLong()); });
        return result;
    }

    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("语义配置不是有效 JSON"); }
    }
    private void requireColumn(String value, String label, Set<String> columns) {
        if (!StringUtils.hasText(value) || !columns.contains(value.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(label + " " + defaultValue(value, "（空）") + " 在来源表中不存在");
        }
    }
    private LocalDate parseDate(String value, String label) {
        if (!StringUtils.hasText(value)) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException error) { throw new IllegalArgumentException(label + "必须使用 yyyy-MM-dd 格式"); }
    }
    private String timeBucket(String column, String grain, DataSource source) {
        String type = source.getType() == null ? "" : source.getType().toLowerCase(Locale.ROOT);
        if (postgresLike(source)) return "DATE_TRUNC('" + grain + "', " + column + ")";
        if (type.contains("mysql")) return switch (grain) {
            case "day" -> "DATE(" + column + ")";
            case "month" -> "DATE_FORMAT(" + column + ", '%Y-%m-01')";
            case "year" -> "DATE_FORMAT(" + column + ", '%Y-01-01')";
            default -> column;
        };
        return "DATE_TRUNC('" + grain.toUpperCase(Locale.ROOT) + "', " + column + ")";
    }
    private boolean postgresLike(DataSource source) {
        String type = source.getType() == null ? "" : source.getType().toLowerCase(Locale.ROOT);
        return type.contains("postgres") || type.contains("greenplum") || type.contains("opengauss")
                || type.contains("cockroach") || type.contains("yugabyte");
    }
    private String qualifiedName(MetadataTable table) { return qualifiedName(table, true).replace("\"", ""); }
    private String qualifiedName(MetadataTable table, boolean postgres) {
        return StringUtils.hasText(table.getSchemaName())
                ? quote(table.getSchemaName(), postgres) + "." + quote(table.getTableName(), postgres)
                : quote(table.getTableName(), postgres);
    }
    private String quote(String value, boolean postgres) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw new IllegalArgumentException("字段或表名包含不支持的字符");
        String marker = postgres ? "\"" : "`";
        return marker + value + marker;
    }
    private String literal(JsonNode value) {
        if (value == null || value.isNull()) return "NULL";
        if (value.isNumber() || value.isBoolean()) return value.asText();
        return literal(value.asText());
    }
    private String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private String defaultValue(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }

    private record FilterSpec(String column, String operator, JsonNode value) { }
    private record ValidationContext(MetadataTable table, DataSource dataSource, Set<String> columns,
                                     List<FilterSpec> filters, List<SemanticDimension> defaultDimensions) { }
}
