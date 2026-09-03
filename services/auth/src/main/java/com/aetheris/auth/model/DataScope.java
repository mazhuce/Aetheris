package com.aetheris.auth.model;

import java.util.List;

/**
 * 数据范围：表达"组织级、空间级、业务域级、表级、字段级、指标级"的授权范围。
 *
 * <pre>
 * {"kind":"domain","values":["5","6"]}     业务域 id 5、6
 * {"kind":"table","values":["12"]}          表 id 12
 * {"kind":"workspace","values":["1"]}       项目空间 id 1
 * </pre>
 *
 * values 全部以字符串存储，匹配时与调用方上下文（id 或 code）做字符串比较。
 */
public class DataScope {
    private String kind;
    private List<String> values;

    public DataScope() {
    }

    public DataScope(String kind, List<String> values) {
        this.kind = kind;
        this.values = values;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }

    public boolean isEmpty() {
        return kind == null || values == null || values.isEmpty();
    }
}