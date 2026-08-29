package com.aetheris.metadata.service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Hive / Spark SQL 中提取表级血缘（目标表 ← 来源表）。
 *
 * <p>为什么不用 JSqlParser：实测 4.9 版无法解析 Hive 的
 * {@code INSERT OVERWRITE TABLE ... PARTITION (dt)}，本项目仓库脚本里的四类写法全部报
 * JSQLParserException。改用面向 Hive 方言的词法提取，覆盖以下语法：
 * {@code INSERT OVERWRITE/INTO TABLE}、{@code PARTITION}、{@code LATERAL VIEW}、
 * {@code DISTRIBUTE BY}、{@code CREATE TABLE AS SELECT}。
 *
 * <p>这是表级血缘而非字段级：只回答「这张表的数据来自哪些表」，不追踪具体字段的加工路径。
 * 字段级血缘需要完整 AST，等引入真正支持 Hive 方言的解析器后再做。
 *
 * <p>刻意不做的事：不解析子查询别名与 CTE 名称之间的引用关系，而是先收集所有 CTE 名字，
 * 最后从来源表里剔除——CTE 是查询内部的临时结果，不是物理表，混进血缘会产生不存在的节点。
 */
public final class SqlLineageExtractor {

    /** 目标表：INSERT OVERWRITE|INTO [TABLE] db.table，TABLE 关键字在 Spark 中可省略。 */
    private static final Pattern INSERT_TARGET = Pattern.compile(
            "\\bINSERT\\s+(?:OVERWRITE|INTO)\\s+(?:TABLE\\s+)?([A-Za-z0-9_.`]+)",
            Pattern.CASE_INSENSITIVE);

    /** 目标表：CREATE [OR REPLACE] TABLE|VIEW db.table ... AS SELECT。 */
    private static final Pattern CREATE_TARGET = Pattern.compile(
            "\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EXTERNAL\\s+|TEMPORARY\\s+)?(?:TABLE|VIEW)\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.`]+)",
            Pattern.CASE_INSENSITIVE);

    /** 来源表：FROM / JOIN 后面的表名。 */
    private static final Pattern SOURCE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([A-Za-z0-9_.`]+)",
            Pattern.CASE_INSENSITIVE);

    /** CTE 名称：WITH name AS ( 或 , name AS (。 */
    private static final Pattern CTE_NAME = Pattern.compile(
            "(?:\\bWITH|,)\\s*([A-Za-z0-9_`]+)\\s+AS\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'(?:[^']|'')*'");

    /**
     * FROM 后面可能跟的不是表名，而是这些关键字（子查询、函数调用等）。
     * range(...) 是 Spark 的表值函数，造数脚本大量使用，必须排除。
     */
    private static final Set<String> NOT_TABLE_NAMES = Set.of(
            "SELECT", "VALUES", "LATERAL", "RANGE", "UNNEST", "TABLE", "EXPLODE", "SEQUENCE");

    private SqlLineageExtractor() {
    }

    /**
     * 从一段 SQL 中提取血缘。一段脚本里可以有多条语句，按分号切分后逐条处理。
     *
     * @param sql 待解析的 SQL，可含注释与多条语句
     * @return 每条有目标表且有来源表的语句产生一条血缘；无法识别的语句被跳过而非报错
     */
    public static List<TableLineage> extract(String sql) {
        if (!StringUtils.hasText(sql)) {
            return List.of();
        }
        List<TableLineage> lineages = new ArrayList<>();
        for (String statement : splitStatements(stripNoise(sql))) {
            TableLineage lineage = extractOne(statement);
            if (lineage != null) {
                lineages.add(lineage);
            }
        }
        return lineages;
    }

    private static TableLineage extractOne(String statement) {
        String target = firstMatch(INSERT_TARGET, statement);
        if (target == null) {
            target = firstMatch(CREATE_TARGET, statement);
        }
        if (target == null) {
            return null;
        }

        // CTE 名字是查询内部的临时结果，从来源中剔除，否则会产生不存在的表节点。
        Set<String> cteNames = new LinkedHashSet<>();
        Matcher cteMatcher = CTE_NAME.matcher(statement);
        while (cteMatcher.find()) {
            cteNames.add(normalize(cteMatcher.group(1)));
        }

        Set<String> sources = new LinkedHashSet<>();
        Matcher sourceMatcher = SOURCE_REF.matcher(statement);
        while (sourceMatcher.find()) {
            String candidate = normalize(sourceMatcher.group(1));
            if (candidate.isEmpty()
                    || NOT_TABLE_NAMES.contains(candidate.toUpperCase(Locale.ROOT))
                    || cteNames.contains(candidate)
                    || candidate.equals(target)) {
                // 排除自引用：INSERT INTO t SELECT ... FROM t 不构成血缘边。
                continue;
            }
            sources.add(candidate);
        }

        return sources.isEmpty() ? null : new TableLineage(target, List.copyOf(sources));
    }

    /** 去掉注释与字符串字面量，避免其中的 from/join 之类的词被误当成语法。 */
    private static String stripNoise(String sql) {
        String cleaned = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = LINE_COMMENT.matcher(cleaned).replaceAll(" ");
        cleaned = SINGLE_QUOTED.matcher(cleaned).replaceAll("''");
        return cleaned;
    }

    /** 按分号切分语句；括号内的分号不应切分，但 Hive DDL/DML 里不会出现，故简单处理。 */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (StringUtils.hasText(part)) {
                statements.add(part);
            }
        }
        return statements;
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : null;
    }

    /** 去掉反引号并统一小写；库名与表名之间保留点号。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 一条表级血缘。
     *
     * @param targetTable 目标表，形如 dwd.dwd_order
     * @param sourceTables 来源表，已去重且剔除 CTE 与自引用
     */
    public record TableLineage(String targetTable, List<String> sourceTables) {
    }
}
