package com.aetheris.metadata.service;

import java.util.Set;

/** R4 变更分类：破坏性变化不会直接进入自动执行路径。 */
public final class SchemaEvolutionPolicy {
    private static final Set<String> AUTO = Set.of("add_column", "widen_type", "comment");
    private static final Set<String> APPROVAL = Set.of("rename_column", "drop_column");
    private static final Set<String> ISOLATE = Set.of("narrow_type");

    private SchemaEvolutionPolicy() { }

    public static String decision(String operation) {
        if (AUTO.contains(operation)) return "auto_apply";
        if (APPROVAL.contains(operation)) return "approval_required";
        if (ISOLATE.contains(operation)) return "isolate";
        throw new IllegalArgumentException("不支持的 Schema 变更类型");
    }
}
