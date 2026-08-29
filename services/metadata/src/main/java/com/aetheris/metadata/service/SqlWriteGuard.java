package com.aetheris.metadata.service;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 写入类 SQL 的守卫。
 *
 * <p>为什么单独一个类而不是复用 {@code validateReadOnlySql}：只读守卫把 INSERT / CREATE
 * 全部拒掉，而数仓加工任务必须写入。但「允许写入」不等于「放行任意语句」——这里仍然禁止
 * 删表、改权限、读服务器文件等操作，只放开数仓加工实际需要的那几种写法。
 *
 * <p>放行清单（白名单，不是黑名单）：
 * <ul>
 *   <li>{@code INSERT INTO/OVERWRITE ... SELECT}：数仓分层加工的主力写法</li>
 *   <li>{@code CREATE TABLE/VIEW ... AS SELECT}：建中间表</li>
 *   <li>{@code CREATE TABLE/DATABASE IF NOT EXISTS}：建表建库</li>
 * </ul>
 *
 * <p>刻意不放行 {@code DROP} / {@code TRUNCATE} / {@code ALTER}：
 * 这些是不可逆的破坏性操作，而用 {@code INSERT OVERWRITE} 覆盖分区就能满足重跑需求，
 * 不需要先删表。若将来确实要支持，应作为独立的、需显式确认的任务类型，而不是放进通用执行路径。
 */
public final class SqlWriteGuard {

    /** 允许的语句开头，按白名单匹配。 */
    private static final List<Pattern> ALLOWED_STARTS = List.of(
            // INSERT INTO / INSERT OVERWRITE，可带 TABLE 关键字
            Pattern.compile("^INSERT\\s+(INTO|OVERWRITE)\\s+", Pattern.CASE_INSENSITIVE),
            // CREATE [OR REPLACE] [EXTERNAL|TEMPORARY] TABLE|VIEW|DATABASE|SCHEMA
            Pattern.compile("^CREATE\\s+(OR\\s+REPLACE\\s+)?(EXTERNAL\\s+|TEMPORARY\\s+)?"
                    + "(TABLE|VIEW|DATABASE|SCHEMA)\\s+", Pattern.CASE_INSENSITIVE));

    /**
     * 无论语句类型都禁止的关键字。
     *
     * <p>前半部分是破坏性 DDL 与权限操作；后半部分是「语法上合法但能读服务器文件、
     * 探测本地端口或阻塞连接」的函数，与只读守卫保持同一份清单，避免两条路径的防护强度不一致。
     */
    private static final List<String> FORBIDDEN = List.of(
            "DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "RENAME",
            "CALL", "COPY", "VACUUM", "ATTACH", "DETACH",
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_STAT_FILE",
            "PG_SLEEP", "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND",
            "LO_IMPORT", "LO_EXPORT", "DBLINK", "LOAD_FILE", "BENCHMARK", "SLEEP",
            "REFLECT", "JAVA_METHOD", "OUTFILE", "DUMPFILE");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'(?:[^']|'')*'");

    private SqlWriteGuard() {
    }

    /**
     * 校验一条写入语句。
     *
     * @param sql 待执行的 SQL
     * @return 去除首尾空白与末尾分号后的语句
     * @throws IllegalArgumentException 语句为空、含多条语句、不在白名单内或含禁用关键字
     */
    public static String validate(String sql) {
        String cleaned = sql == null ? "" : sql.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        // 全部检查都在「剥离注释与字符串字面量」之后进行，顺序很关键：
        //   - 白名单若用原始文本匹配，以注释开头的脚本（真实加工脚本的常见写法）会被误拒；
        //   - 分号若用原始文本检查，注释里的分号（如 "-- 见工单; 紧急"）会被误判为多条语句；
        //   - 禁用词若用原始文本检查，注释里的「勿 DROP 此表」会被误拦，
        //     而字符串字面量里藏的关键字反而可能漏判。
        String scannable = stripNoise(cleaned).trim();

        // 末尾单个分号是书写习惯，允许；语句中间出现分号一律拒绝，
        // 否则 "INSERT ...; DROP TABLE x" 会绕过只看开头的白名单。
        String scannableBody = scannable.endsWith(";")
                ? scannable.substring(0, scannable.length() - 1).trim()
                : scannable;
        if (scannableBody.contains(";")) {
            throw new IllegalArgumentException("仅支持单条语句，请拆分为多个任务");
        }

        if (ALLOWED_STARTS.stream().noneMatch(p -> p.matcher(scannableBody).find())) {
            throw new IllegalArgumentException(
                    "仅支持 INSERT INTO / INSERT OVERWRITE / CREATE ... AS SELECT 语句");
        }

        String upper = scannableBody.toUpperCase(Locale.ROOT);
        for (String word : FORBIDDEN) {
            if (upper.matches(".*\\b" + word + "\\b.*")) {
                throw new IllegalArgumentException("语句中包含不允许的关键字：" + word);
            }
        }

        // 返回原始语句（保留注释）：注释对执行无害，且保留后任务日志与用户输入一致，便于排查。
        // 仅去掉末尾分号，避免部分驱动对结尾分号报错。
        return cleaned.endsWith(";")
                ? cleaned.substring(0, cleaned.length() - 1).trim()
                : cleaned;
    }

    /** 判断语句是否为写入类，用于选择执行路径（写入走 executeUpdate，查询走 executeQuery）。 */
    public static boolean isWriteStatement(String sql) {
        if (!StringUtils.hasText(sql)) {
            return false;
        }
        String trimmed = sql.trim();
        return ALLOWED_STARTS.stream().anyMatch(p -> p.matcher(trimmed).find());
    }

    private static String stripNoise(String sql) {
        String cleaned = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = LINE_COMMENT.matcher(cleaned).replaceAll(" ");
        return SINGLE_QUOTED.matcher(cleaned).replaceAll("''");
    }
}
