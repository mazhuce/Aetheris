package com.aetheris.metadata.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded SQL contract shared by read and warehouse-write entrypoints.
 * The existing MyBatis JSqlParser checks statement structure. A lexer first
 * separates executable tokens from comments/literals (including quoted names).
 * This is defense in depth: database roles must still restrict schema and EXECUTE privileges.
 */
public final class SqlSafetyPolicy {
    private static final Set<String> UNSAFE = Set.of(
            "DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "RENAME", "CALL", "COPY",
            "VACUUM", "ATTACH", "DETACH", "EXEC", "EXECUTE", "DO", "LOAD", "UNLOAD",
            "OUTFILE", "DUMPFILE", "DIRECTORY", "LOCATION", "PROGRAM", "EXTERNAL",
            "SERDE", "SERDEPROPERTIES", "TBLPROPERTIES", "OPTIONS", "CONNECTION", "ENGINE",
            "HANDLER", "RESOURCE", "JAR", "TRANSFORM", "SCRIPT", "LOCK", "UNLOCK",
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_STAT_FILE", "PG_LS_LOGDIR",
            "PG_SLEEP", "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "LO_IMPORT", "LO_EXPORT",
            "LOAD_FILE", "BENCHMARK", "SLEEP", "REFLECT", "JAVA_METHOD", "QUERY_TO_XML",
            "QUERY_TO_XMLSCHEMA", "QUERY_TO_XML_AND_XMLSCHEMA", "SET_CONFIG");
    private static final Set<String> READ_UNSAFE = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "INTO", "NEXTVAL", "SETVAL", "SHARE");
    private static final Set<String> FUNCTIONS = Set.of(
            "ABS", "ACOS", "ASIN", "ATAN", "ATAN2", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNC",
            "MOD", "PMOD", "POWER", "POW", "SQRT", "EXP", "LN", "LOG", "LOG10", "SIGN",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE",
            "COALESCE", "NULLIF", "IF", "IFNULL", "NVL", "NVL2", "GREATEST", "LEAST",
            "CAST", "TRY_CAST", "CONVERT", "DATE", "TIME", "TIMESTAMP", "DECIMAL", "NUMERIC",
            "VARCHAR", "CHAR", "CHARACTER", "STRING", "BINARY", "VARBINARY",
            "UPPER", "LOWER", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH",
            "TRIM", "LTRIM", "RTRIM", "SUBSTR", "SUBSTRING", "CONCAT", "CONCAT_WS", "LPAD", "RPAD",
            "REPLACE", "TRANSLATE", "POSITION", "INSTR", "SPLIT", "SPLIT_PART", "REGEXP_REPLACE",
            "REGEXP_EXTRACT", "REGEXP_LIKE", "REGEXP_COUNT", "TO_CHAR", "TO_NUMBER",
            "DATE_TRUNC", "DATE_FORMAT", "TO_DATE", "TO_TIMESTAMP", "DATE_ADD", "DATE_SUB", "DATEDIFF",
            "ADD_MONTHS", "MONTHS_BETWEEN", "LAST_DAY", "EXTRACT", "YEAR", "MONTH", "DAY", "HOUR",
            "MINUTE", "SECOND", "QUARTER", "DAYOFWEEK", "WEEKOFYEAR", "NOW", "CURRENT_DATE",
            "CURRENT_TIMESTAMP", "UNIX_TIMESTAMP", "FROM_UNIXTIME", "ROW_NUMBER", "RANK", "DENSE_RANK",
            "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTILE", "PERCENT_RANK", "CUME_DIST",
            "ARRAY", "MAP", "STRUCT", "NAMED_STRUCT", "ELEMENT_AT", "SIZE", "EXPLODE", "POSEXPLODE",
            "SEQUENCE", "RANGE", "GENERATE_SERIES", "ARRAY_AGG", "STRING_AGG", "COLLECT_LIST", "COLLECT_SET",
            "JSON_VALUE", "JSON_QUERY", "JSON_EXTRACT", "JSON_UNQUOTE", "GET_JSON_OBJECT", "ISNULL");
    private static final Set<String> PAREN_SYNTAX = Set.of(
            "AS", "IN", "NOT", "EXISTS", "OVER", "PARTITION", "BY", "FROM", "WHERE", "SELECT",
            "VALUES", "GROUPING", "SETS", "ROLLUP", "CUBE", "FILTER", "WITHIN", "KEY", "UNIQUE",
            "CHECK", "PRIMARY");
    private static final String ID = "(?:[A-Za-z_][A-Za-z0-9_$]*|`[^`]+`|\"[^\"]+\"|\\[[^]]+])";
    private static final String QUALIFIED = ID + "(?:\\s*\\.\\s*" + ID + "){0,2}";
    private static final Pattern HIVE_INSERT = Pattern.compile(
            "(?i)^INSERT\\s+(?:INTO|OVERWRITE)\\s+(?:TABLE\\s+)?(" + QUALIFIED + ")"
                    + "(?:\\s+PARTITION\\s*\\((?:" + ID + ")(?:\\s*,\\s*" + ID + ")*\\))?\\s+(?=SELECT\\b|WITH\\b|VALUES\\b)");
    private static final Pattern CREATE_NAMESPACE = Pattern.compile(
            "(?is)^CREATE\\s+(?:DATABASE|SCHEMA)\\s+IF\\s+NOT\\s+EXISTS\\s+" + ID + "(?:\\s+COMMENT\\s+'')?$");
    private static final Pattern STORED_FORMAT = Pattern.compile("(?i)\\s+STORED\\s+AS\\s+(?:PARQUET|ORC)\\s*$");
    private static final String PARTITION_TYPE = "(?:STRING|INT|BIGINT|SMALLINT|TINYINT|BOOLEAN|DATE|TIMESTAMP|VARCHAR\\s*\\(\\s*\\d+\\s*\\)|DECIMAL\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\))";
    private static final String PARTITION_COLUMN = ID + "\\s+" + PARTITION_TYPE + "(?:\\s+COMMENT\\s+'')?";
    private static final Pattern TABLE_OPTIONS = Pattern.compile("(?is)^(?:COMMENT\\s+''\\s*)?"
            + "(?:PARTITIONED\\s+BY\\s*\\(\\s*" + PARTITION_COLUMN + "(?:\\s*,\\s*" + PARTITION_COLUMN + ")*\\s*\\)\\s*)?"
            + "(?:STORED\\s+AS\\s+(?:PARQUET|ORC)\\s*)?$");

    private SqlSafetyPolicy() { }

    public static String readOnly(String sql) {
        Scan scan = scan(sql);
        rejectTokens(scan, false);
        String code = scan.code();
        if (code.matches("(?is)^SHOW\\s+(?:TABLES|DATABASES|SCHEMAS|COLUMNS|FIELDS)(?:\\s+(?:IN|FROM)\\s+" + QUALIFIED + ")?$")) return scan.original();
        if (code.matches("(?is)^(?:DESCRIBE|DESC)\\s+(?:(?:FORMATTED|EXTENDED)\\s+)?" + QUALIFIED + "$")) return scan.original();
        Statement statement = parse(code);
        if (!(statement instanceof Select) && !(statement instanceof ExplainStatement explain && explain.getStatement() != null)) {
            throw new IllegalArgumentException("仅支持只读 SELECT / WITH / SHOW / DESCRIBE / EXPLAIN 查询");
        }
        validateFunctions(scan, Set.of());
        return scan.original();
    }

    public static String warehouseWrite(String sql) {
        Scan scan = scan(sql);
        rejectTokens(scan, true);
        String code = scan.code();
        if (CREATE_NAMESPACE.matcher(code).matches()) return scan.original();
        // Only this bounded Hive extension is normalized for parsing; the original
        // statement is executed, so no LOCATION, properties or arbitrary options are discarded.
        var insert = HIVE_INSERT.matcher(code);
        if (insert.find()) code = insert.replaceFirst("INSERT INTO $1 ");
        code = STORED_FORMAT.matcher(code).replaceFirst("");
        Statement statement = parse(code);
        Set<String> objectNames = new HashSet<>();
        if (statement instanceof Insert value && value.getSelect() != null
                && value.getOutputClause() == null && value.getReturningClause() == null
                && value.getConflictAction() == null && !value.isUseDuplicate()) {
            objectNames.add(unquote(value.getTable().getName()));
        } else if (statement instanceof CreateTable value && value.getLikeTable() == null) {
            String options = value.getTableOptionsStrings() == null ? "" : String.join(" ", value.getTableOptionsStrings()).trim();
            String createOptions = value.getCreateOptionsStrings() == null ? "" : String.join(" ", value.getCreateOptionsStrings()).trim();
            if (!TABLE_OPTIONS.matcher(options).matches() || !createOptions.matches("(?i)(?:TEMPORARY|TEMP)?")) {
                throw new IllegalArgumentException("建表仅支持明确的分区、注释及 PARQUET/ORC 格式");
            }
            objectNames.add(unquote(value.getTable().getName()));
        } else if (statement instanceof CreateView value && value.getSelect() != null) {
            objectNames.add(unquote(value.getView().getName()));
        } else {
            throw new IllegalArgumentException("仅支持受限 INSERT、CREATE TABLE/VIEW 和 CREATE DATABASE IF NOT EXISTS");
        }
        validateFunctions(scan, objectNames);
        return scan.original();
    }

    static boolean isWrite(String sql) {
        try {
            String start = scan(sql).tokens().getFirst().name();
            return start.equals("INSERT") || start.equals("CREATE");
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(true).withTimeOut(1000));
        } catch (JSQLParserException | RuntimeException error) {
            throw new IllegalArgumentException("SQL 结构不在已支持的安全语法范围内", error);
        }
    }

    private static void rejectTokens(Scan scan, boolean write) {
        for (Token token : scan.tokens()) {
            if (token.literal()) continue;
            String name = token.name();
            if (UNSAFE.contains(name) || name.startsWith("DBLINK") || name.startsWith("PG_ADVISORY_")
                    || (!write && READ_UNSAFE.contains(name) && !token.quoted())
                    || (write && Set.of("UPDATE", "DELETE", "MERGE").contains(name) && !token.quoted())) {
                throw new IllegalArgumentException("SQL 包含不允许的操作或函数：" + name);
            }
        }
    }

    private static void validateFunctions(Scan scan, Set<String> objectNames) {
        List<Token> tokens = scan.tokens();
        Set<Integer> cteDeclarations = cteDeclarations(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.literal() || !tokens.get(i + 1).text().equals("(") || !token.identifier()) continue;
            String name = token.name();
            boolean qualified = i > 0 && tokens.get(i - 1).text().equals(".");
            boolean declaration = objectNames.contains(name) && isObjectDeclaration(tokens, i);
            boolean cteColumns = cteDeclarations.contains(i);
            if (qualified && !declaration && (i < 2 || !trustedBuiltinSchema(tokens.get(i - 2))
                    || (i >= 3 && tokens.get(i - 3).text().equals(".")))) {
                throw new IllegalArgumentException("不允许调用自定义 schema 函数");
            }
            if (!declaration && !cteColumns && token.quoted()
                    && !token.text().equals("\"" + name.toLowerCase(Locale.ROOT) + "\"")) {
                throw new IllegalArgumentException("引用函数名称必须精确匹配内置函数");
            }
            if (!declaration && !cteColumns && !FUNCTIONS.contains(name) && !(PAREN_SYNTAX.contains(name) && !token.quoted())) {
                throw new IllegalArgumentException("SQL 使用了未授权的函数：" + name);
            }
        }
    }

    private static boolean trustedBuiltinSchema(Token token) {
        return token.quoted() ? token.text().equals("\"pg_catalog\"") : token.name().equals("PG_CATALOG");
    }

    private static boolean isObjectDeclaration(List<Token> tokens, int i) {
        int start = i;
        while (start >= 2 && tokens.get(start - 1).text().equals(".")) start -= 2;
        return start > 0 && Set.of("INTO", "TABLE", "VIEW", "EXISTS").contains(tokens.get(start - 1).name());
    }

    private static int closingParenthesis(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            if (tokens.get(i).text().equals("(")) depth++;
            if (tokens.get(i).text().equals(")")) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static Set<Integer> cteDeclarations(List<Token> tokens) {
        Set<Integer> declarations = new HashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).name().equals("WITH") || tokens.get(i).quoted()) continue;
            int name = i + 1;
            if (name < tokens.size() && tokens.get(name).name().equals("RECURSIVE")) name++;
            while (name + 1 < tokens.size() && tokens.get(name).identifier()) {
                int next = name + 1;
                if (tokens.get(next).text().equals("(")) {
                    int close = closingParenthesis(tokens, next);
                    if (close < 0 || close + 1 >= tokens.size() || !tokens.get(close + 1).name().equals("AS")) break;
                    declarations.add(name);
                    next = close + 1;
                }
                if (!tokens.get(next).name().equals("AS") || next + 1 >= tokens.size() || !tokens.get(next + 1).text().equals("(")) break;
                int close = closingParenthesis(tokens, next + 1);
                if (close < 0 || close + 1 >= tokens.size() || !tokens.get(close + 1).text().equals(",")) break;
                name = close + 2;
            }
        }
        return declarations;
    }

    private static String unquote(String value) {
        String name = value;
        if (name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '"' || first == '`') && last == first) {
                name = name.substring(1, name.length() - 1).replace("" + first + first, "" + first);
            } else if (first == '[' && last == ']') {
                name = name.substring(1, name.length() - 1).replace("]]", "]");
            }
        }
        return name.toUpperCase(Locale.ROOT);
    }

    private record Token(String text, boolean literal, boolean quoted, int offset) {
        String name() { return unquote(text); }
        boolean identifier() { return quoted || (!text.isEmpty() && Character.isUnicodeIdentifierStart(text.codePointAt(0))); }
    }
    private record Scan(String original, String code, List<Token> tokens) { }

    private static Scan scan(String sql) {
        String original = sql == null ? "" : sql.trim();
        if (original.isEmpty() || original.length() > 100_000) throw new IllegalArgumentException("SQL 不能为空或超过长度限制");
        List<Token> tokens = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < original.length();) {
            char c = original.charAt(i);
            if (Character.isWhitespace(c)) { code.append(' '); i++; continue; }
            if (c == '-' && i + 1 < original.length() && original.charAt(i + 1) == '-') {
                while (i < original.length() && original.charAt(i) != '\n' && original.charAt(i) != '\r') i++;
                code.append(' '); continue;
            }
            if (c == '/' && i + 1 < original.length() && original.charAt(i + 1) == '*') {
                if (i + 2 < original.length() && (original.charAt(i + 2) == '!' || original.charAt(i + 2) == '+'))
                    throw new IllegalArgumentException("不支持可执行注释或 SQL 提示");
                int end = original.indexOf("*/", i + 2);
                if (end < 0 || original.substring(i + 2, end).contains("/*")) throw new IllegalArgumentException("不支持未闭合或嵌套注释");
                i = end + 2; code.append(' '); continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                int start = i++;
                char end = c == '[' ? ']' : c;
                boolean closed = false;
                while (i < original.length()) {
                    if (original.charAt(i) == '\\' && i + 1 < original.length() && original.charAt(i + 1) == end)
                        throw new IllegalArgumentException("引号请使用 SQL 双写转义");
                    if (original.charAt(i++) == end) {
                        if (i < original.length() && original.charAt(i) == end) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("SQL 引号未闭合");
                String text = original.substring(start, i);
                boolean literal = c == '\'';
                tokens.add(new Token(literal ? "''" : text, literal, !literal, start));
                code.append(literal ? "''" : text); continue;
            }
            if (c == '$' || Character.isISOControl(c) || c == '\\' || c == '#')
                throw new IllegalArgumentException("SQL 包含不支持的转义或字符");
            int start = i;
            int first = original.codePointAt(i);
            i += Character.charCount(first);
            if (Character.isUnicodeIdentifierStart(first)) {
                while (i < original.length() && Character.isUnicodeIdentifierPart(original.codePointAt(i))) {
                    i += Character.charCount(original.codePointAt(i));
                }
            }
            String text = original.substring(start, i);
            tokens.add(new Token(text, false, false, start)); code.append(text);
            if (c == '(' && ++depth > 64) throw new IllegalArgumentException("SQL 嵌套过深");
            if (c == ')' && --depth < 0) throw new IllegalArgumentException("SQL 括号不匹配");
        }
        if (depth != 0 || tokens.isEmpty()) throw new IllegalArgumentException("SQL 结构不完整");
        if (tokens.getLast().text().equals(";")) {
            Token delimiter = tokens.removeLast();
            int semicolon = code.lastIndexOf(";");
            code.deleteCharAt(semicolon);
            // Remove only an actual final delimiter, never a semicolon inside a literal/comment.
            int end = delimiter.offset();
            original = original.substring(0, end) + original.substring(end + 1);
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(token -> token.text().equals(";")))
            throw new IllegalArgumentException("仅支持单条语句，请拆分为多个任务");
        return new Scan(original.trim(), code.toString().trim(), List.copyOf(tokens));
    }
}
