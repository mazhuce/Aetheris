package com.aetheris.quality.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量规则表达式的最小 AST 校验器。
 *
 * <p>质量规则不是通用 SQL 编辑器：谓词规则只允许标识符、常量、布尔组合、
 * 比较/NULL/IN/BETWEEN/LIKE 和显式函数白名单。先保留调用方的注释、分号与危险
 * 关键字拦截，再用递归下降解析器拒绝“字符合法但语法/函数不在契约内”的输入。
 */
final class QualityExpressionAst {

    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "ABS", "CAST", "COALESCE", "DATE", "DATE_TRUNC", "EXTRACT", "LENGTH",
            "LOWER", "NULLIF", "POSITION", "REGEXP_LIKE", "ROUND", "SUBSTRING",
            "TRIM", "UPPER");
    private static final Set<String> NON_VALUE_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "WITH", "UNION", "JOIN", "GROUP", "ORDER",
            "BY", "HAVING", "LIMIT", "OFFSET", "AS", "CASE", "WHEN", "THEN", "ELSE",
            "END", "DROP", "INSERT", "UPDATE", "DELETE", "ALTER", "TRUNCATE", "GRANT",
            "REVOKE", "CALL", "COPY");
    private static final Pattern FUNCTION_CALL = Pattern.compile(
            "(?i)\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");

    private QualityExpressionAst() { }

    static void validatePredicate(String expression) {
        new Parser(expression).parse();
    }

    /** 对自定义 SELECT/WITH 也执行函数白名单扫描；完整查询语法仍由目标数据库解析。 */
    static void validateSqlFunctions(String sql) {
        String scannable = STRING_LITERAL.matcher(sql).replaceAll(" ");
        Matcher matcher = FUNCTION_CALL.matcher(scannable);
        while (matcher.find()) {
            String function = matcher.group(1).toUpperCase(Locale.ROOT);
            if (!ALLOWED_FUNCTIONS.contains(function)
                    && !Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(function)) {
                throw new IllegalArgumentException("质量规则使用了不允许的函数：" + function);
            }
        }
    }

    private interface Node { }
    private record Atom(String text) implements Node { }
    private record Function(String name, List<Node> args) implements Node { }
    private record Unary(String operator, Node operand) implements Node { }
    private record Binary(String operator, Node left, Node right) implements Node { }
    private record Predicate(String operator, Node left, List<Node> values) implements Node { }

    private enum Kind { IDENT, NUMBER, STRING, OP, LEFT, RIGHT, COMMA, END }
    private record Token(Kind kind, String text) { }

    private static final class Parser {
        private final Lexer lexer;
        private Token current;

        private Parser(String expression) {
            this.lexer = new Lexer(expression);
            this.current = lexer.next();
        }

        private Node parse() {
            Node result = parseOr();
            expect(Kind.END, "表达式末尾存在不支持的内容");
            return result;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (matchKeyword("OR")) left = new Binary("OR", left, parseAnd());
            return left;
        }

        private Node parseAnd() {
            Node left = parseNot();
            while (matchKeyword("AND")) left = new Binary("AND", left, parseNot());
            return left;
        }

        private Node parseNot() {
            if (matchKeyword("NOT")) return new Unary("NOT", parseNot());
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (match(Kind.LEFT)) {
                Node value = parseOr();
                expect(Kind.RIGHT, "括号不匹配");
                return value;
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Node left = parseValue();
            if (matchKeyword("IS")) {
                boolean not = matchKeyword("NOT");
                expectKeyword("NULL");
                return new Predicate(not ? "IS NOT NULL" : "IS NULL", left, List.of());
            }
            if (matchKeyword("NOT")) {
                if (matchKeyword("IN")) return new Predicate("NOT IN", left, parseList());
                if (matchKeyword("LIKE")) return new Predicate("NOT LIKE", left, List.of(parseValue()));
                throw error("NOT 后只允许 IN 或 LIKE");
            }
            if (matchKeyword("IN")) return new Predicate("IN", left, parseList());
            if (matchKeyword("BETWEEN")) {
                Node lower = parseValue();
                expectKeyword("AND");
                Node upper = parseValue();
                return new Predicate("BETWEEN", left, List.of(lower, upper));
            }
            if (matchKeyword("LIKE")) return new Predicate("LIKE", left, List.of(parseValue()));
            if (current.kind() == Kind.OP && Set.of("=", "<>", "!=", "<", ">", "<=", ">=").contains(current.text())) {
                String operator = current.text();
                advance();
                return new Binary(operator, left, parseValue());
            }
            return left;
        }

        private List<Node> parseList() {
            expect(Kind.LEFT, "IN 后必须跟括号列表");
            List<Node> values = new ArrayList<>();
            if (!match(Kind.RIGHT)) {
                values.add(parseValue());
                while (match(Kind.COMMA)) values.add(parseValue());
                expect(Kind.RIGHT, "IN 列表括号不匹配");
            }
            if (values.isEmpty()) throw error("IN 列表不能为空");
            return values;
        }

        private Node parseValue() {
            if (current.kind() == Kind.OP && ("+".equals(current.text()) || "-".equals(current.text()))) {
                String operator = current.text();
                advance();
                return new Unary(operator, parseValue());
            }
            if (current.kind() == Kind.IDENT) {
                String name = current.text();
                String upper = name.toUpperCase(Locale.ROOT);
                if (NON_VALUE_KEYWORDS.contains(upper)) throw error("表达式包含不支持的 SQL 关键字：" + upper);
                advance();
                if (match(Kind.LEFT)) {
                    if (!ALLOWED_FUNCTIONS.contains(upper)) {
                        throw error("质量规则使用了不允许的函数：" + upper);
                    }
                    List<Node> args = new ArrayList<>();
                    if (!match(Kind.RIGHT)) {
                        args.add(parseValue());
                        while (match(Kind.COMMA)) args.add(parseValue());
                        expect(Kind.RIGHT, "函数调用括号不匹配");
                    }
                    return new Function(upper, args);
                }
                return new Atom(name);
            }
            if (current.kind() == Kind.NUMBER || current.kind() == Kind.STRING) {
                String value = current.text();
                advance();
                return new Atom(value);
            }
            throw error("表达式需要字段、常量或函数");
        }

        private boolean match(Kind kind) {
            if (current.kind() != kind) return false;
            advance();
            return true;
        }

        private boolean matchKeyword(String keyword) {
            if (current.kind() != Kind.IDENT || !keyword.equalsIgnoreCase(current.text())) return false;
            advance();
            return true;
        }

        private void expectKeyword(String keyword) {
            if (!matchKeyword(keyword)) throw error("缺少关键字：" + keyword);
        }

        private void expect(Kind kind, String message) {
            if (!match(kind)) throw error(message);
        }

        private void advance() { current = lexer.next(); }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }
    }

    private static final class Lexer {
        private final String input;
        private int position;

        private Lexer(String input) { this.input = input; }

        private Token next() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
            if (position >= input.length()) return new Token(Kind.END, "");
            char c = input.charAt(position);
            if (Character.isLetter(c) || c == '_') return identifier();
            if (Character.isDigit(c)) return number();
            if (c == '\'') return string();
            position++;
            return switch (c) {
                case '(' -> new Token(Kind.LEFT, "(");
                case ')' -> new Token(Kind.RIGHT, ")");
                case ',' -> new Token(Kind.COMMA, ",");
                case '+', '-', '*', '/', '%' -> new Token(Kind.OP, String.valueOf(c));
                case '=', '<', '>', '!' -> operator(c);
                default -> throw new IllegalArgumentException("表达式包含不支持的字符：" + c);
            };
        }

        private Token identifier() {
            int start = position++;
            while (position < input.length()) {
                char c = input.charAt(position);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') position++;
                else break;
            }
            String value = input.substring(start, position);
            if (!value.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*){0,2}")) {
                throw new IllegalArgumentException("字段标识符格式不正确：" + value);
            }
            return new Token(Kind.IDENT, value);
        }

        private Token number() {
            int start = position++;
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            if (position < input.length() && input.charAt(position) == '.') {
                position++;
                while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            }
            return new Token(Kind.NUMBER, input.substring(start, position));
        }

        private Token string() {
            int start = position++;
            while (position < input.length()) {
                if (input.charAt(position++) != '\'') continue;
                if (position < input.length() && input.charAt(position) == '\'') {
                    position++;
                    continue;
                }
                return new Token(Kind.STRING, input.substring(start, position));
            }
            throw new IllegalArgumentException("字符串常量未闭合");
        }

        private Token operator(char first) {
            if (position < input.length() && (input.charAt(position) == '=' || input.charAt(position) == '>')) {
                return new Token(Kind.OP, "" + first + input.charAt(position++));
            }
            if (first == '!' && (position >= input.length() || input.charAt(position) != '=')) {
                throw new IllegalArgumentException("不支持的操作符：!");
            }
            return new Token(Kind.OP, String.valueOf(first));
        }
    }
}
