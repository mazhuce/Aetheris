package com.aetheris.learning.grading;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 的静态审查。所有判断都基于“去掉字面量与注释后的语句”，
 * 避免把字符串里的 delete 误判为写操作，也避免用注释绕过关键字检查。
 */
public final class SqlGuard {

    private static final Pattern STAR = Pattern.compile("\\bselect\\s+\\*", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFIED_STAR = Pattern.compile("\\bselect\\s+[a-z_][a-z0-9_]*\\s*\\.\\s*\\*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_HEAD = Pattern.compile("^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_HEAD = Pattern.compile("^\\s*(insert|update|delete)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEACH_REF = Pattern.compile("\\bteach\\s*\\.", Pattern.CASE_INSENSITIVE);
    /** 学员只能访问自己的沙箱与 teach 教学数据；显式平台 schema 一律拒绝。 */
    private static final Pattern PLATFORM_SCHEMA = Pattern.compile(
            "\\b(learning|mcp|metadata|quality|auth|system|workflow|ingestion|governance|serving|ai)\\s*\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_FUNCTION = Pattern.compile(
            "\\b(pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|dblink|copy)\\b",
            Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    public record Check(String key, String label, boolean passed, String detail) {
    }

    public record Evaluation(List<Check> safety, List<Check> structure, String strippedSql) {
        public boolean safetyPassed() {
            return safety.stream().allMatch(Check::passed);
        }

        public boolean structurePassed() {
            return structure.stream().allMatch(Check::passed);
        }

        public List<String> failures() {
            List<String> messages = new ArrayList<>();
            safety.stream().filter(check -> !check.passed()).forEach(check -> messages.add(check.detail()));
            structure.stream().filter(check -> !check.passed()).forEach(check -> messages.add(check.detail()));
            return messages;
        }
    }

    public static Evaluation evaluate(String sql, JsonNode policy) {
        List<Check> safety = new ArrayList<>();
        List<Check> structure = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            safety.add(new Check("read_only", "只读查询", false, "提交内容为空，请先编写 SQL。"));
            return new Evaluation(safety, structure, "");
        }
        String stripped = stripLiteralsAndComments(sql);
        String normalized = stripped.toLowerCase(Locale.ROOT);

        boolean writeMode = policy.path("allowWrite").asBoolean(false);
        boolean headOk = (writeMode ? WRITE_HEAD : READ_HEAD).matcher(stripped).find();
        safety.add(new Check(writeMode ? "write_operation" : "read_only",
                writeMode ? "写操作" : "只读查询", headOk,
                headOk ? (writeMode ? "写语句校验通过。" : "只读查询校验通过。")
                        : (writeMode ? "只允许 INSERT / UPDATE / DELETE 开头的写语句。"
                        : "只允许 SELECT 或 WITH 开头的只读查询。")));

        int semicolons = countSemicolons(stripped);
        boolean single = semicolons == 0 || (semicolons == 1 && stripped.trim().endsWith(";"));
        safety.add(new Check("single_statement", "单条语句", single,
                single ? "单条语句校验通过。" : "只允许提交一条语句，禁止使用分号拼接多条 SQL。"));

        List<String> forbidden = new ArrayList<>();
        if (policy.has("forbiddenKeywords")) {
            for (JsonNode keyword : policy.withArray("forbiddenKeywords")) {
                String word = keyword.asText("");
                if (word.isBlank()) continue;
                if (Pattern.compile("\\b" + Pattern.quote(word.toLowerCase(Locale.ROOT)) + "\\b")
                        .matcher(normalized).find()) {
                    forbidden.add(word);
                }
            }
        }
        safety.add(new Check("no_write_keywords", "禁止写操作关键字", forbidden.isEmpty(),
                forbidden.isEmpty() ? "未发现写操作关键字。" : "包含禁止的关键字：" + String.join(", ", forbidden) + "。"));

        if (writeMode) {
            boolean teachReference = TEACH_REF.matcher(normalized).find();
            safety.add(new Check("sandbox_write_only", "仅写沙箱", !teachReference,
                    teachReference ? "写任务禁止显式引用 teach schema，请直接使用表名。" : "沙箱写入范围校验通过。"));
        }

        boolean platformSchema = PLATFORM_SCHEMA.matcher(normalized).find();
        safety.add(new Check("sandbox_only", "仅限沙箱与教学数据", !platformSchema,
                platformSchema ? "只能查询当前沙箱与 teach 教学数据，禁止显式引用平台 schema。" : "数据范围校验通过。"));

        boolean dangerousFunction = DANGEROUS_FUNCTION.matcher(normalized).find();
        safety.add(new Check("no_dangerous_function", "禁止文件与外部访问函数", !dangerousFunction,
                dangerousFunction ? "包含禁止的文件或外部访问函数。" : "函数使用校验通过。"));

        boolean disallowStar = policy.path("disallowSelectStar").asBoolean(false);
        if (disallowStar) {
            boolean star = STAR.matcher(stripped).find() || QUALIFIED_STAR.matcher(stripped).find();
            structure.add(new Check("no_select_star", "禁止 SELECT *", !star,
                    star ? "请显式列出需要的字段，不要使用 SELECT *。" : "字段投影校验通过。"));
        }

        if (policy.has("requiredPatterns")) {
            int index = 0;
            for (JsonNode node : policy.withArray("requiredPatterns")) {
                index++;
                String regex = node.asText("");
                if (regex.isBlank()) continue;
                boolean found;
                try {
                    found = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(stripped).find();
                } catch (RuntimeException invalidRegex) {
                    found = false;
                }
                structure.add(new Check("required_pattern_" + index, "必需结构 " + index, found,
                        found ? "结构 " + index + " 校验通过。" : "缺少必需结构：需要匹配 /" + regex + "/。"));
            }
        }

        if (policy.has("requiredColumns")) {
            int index = 0;
            for (JsonNode node : policy.withArray("requiredColumns")) {
                index++;
                String token = node.asText("").toLowerCase(Locale.ROOT).trim();
                if (token.isBlank()) continue;
                boolean found = normalized.contains(token);
                structure.add(new Check("required_column_" + index, "必需条件 " + token, found,
                        found ? "条件 " + token + " 校验通过。" : "缺少必需条件：" + token + "。"));
            }
        }
        return new Evaluation(safety, structure, stripped);
    }

    /** 去掉字符串字面量、标识符引号、行注释与块注释，保留其余字符用于结构判断。 */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < sql.length()) {
                    char current = sql.charAt(i);
                    if (current == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            out.append(' ');
                            i += 2;
                            continue;
                        }
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                out.append(' ');
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    out.append(' ');
                    i++;
                }
                if (i + 1 < sql.length()) {
                    out.append(' ');
                    out.append(' ');
                    i += 2;
                }
                continue;
            }
            if (c == '$') {
                Matcher tag = Pattern.compile("^\\$[A-Za-z_0-9]*\\$").matcher(sql.substring(i));
                if (tag.find()) {
                    String dollar = tag.group();
                    int end = sql.indexOf(dollar, i + dollar.length());
                    int stop = end < 0 ? sql.length() : end + dollar.length();
                    for (int position = i; position < stop; position++) out.append(' ');
                    i = stop;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int countSemicolons(String stripped) {
        int count = 0;
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) == ';') count++;
        }
        return count;
    }
}
