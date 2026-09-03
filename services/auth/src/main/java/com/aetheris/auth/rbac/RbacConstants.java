package com.aetheris.auth.rbac;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限模型常量：动作集合、读动作、资源层级与数据范围上下文键。
 *
 * <p>这里集中定义资源层级：table 的上游是 domain，field 的上游是 table，
 * model/dimension/metric 归属于业务域，dataset/dashboard/data_service 归属于项目空间。
 * 决策引擎据此做"祖先授权继承"（例如 domain.view 授权允许读取域内表与指标）。
 */
public final class RbacConstants {

    private RbacConstants() {
    }

    /** 系统角色代码：与 auth_user.role 的 admin/viewer 映射。 */
    public static final String ROLE_SYS_ADMIN = "sys_admin";
    public static final String ROLE_SYS_VIEWER = "sys_viewer";

    /** 读动作：命中这些动作时才应用"显式授权收窄"语义。 */
    public static final Set<String> READ_ACTIONS = Set.of("view", "query", "export");

    /** 写类动作：网关粗粒度入口保护与默认拒绝判断使用。 */
    public static final Set<String> WRITE_ACTIONS = Set.of(
            "create", "update", "delete", "submit_review", "approve",
            "publish", "deprecate", "grant", "execute");

    public static final String PERMISSION_SERVICE_ADMIN = "service.admin.manage";

    /**
     * 资源层级：资源类型 -> 祖先资源类型（直接上层）。
     *
     * <p>注意：field 没有直接上层 —— 表级授权不自动覆盖字段级读取，
     * 字段必须依赖独立的字段授权收窄，否则按系统角色默认或阶段二 E 的
     * 字段策略（脱敏/遮蔽）处理。这满足"有表权限但无字段权限时字段被
     * 拒绝或按策略脱敏"的要求。
     */
    public static final Map<String, String> ANCESTOR = Map.ofEntries(
            Map.entry("table", "domain"),
            Map.entry("model", "domain"),
            Map.entry("dimension", "domain"),
            Map.entry("metric", "domain"),
            Map.entry("dataset", "workspace"),
            Map.entry("dashboard", "workspace"),
            Map.entry("data_service", "workspace")
    );

    /** 服务（网关粗粒度命名）-> 其拥有的资源类型。用于"存在该服务任何写授权即放行粗粒度写入口"。 */
    public static final Map<String, List<String>> SERVICE_RESOURCES = Map.of(
            "metadata", List.of("domain", "table", "field", "model", "dimension", "metric", "datasource"),
            "serving", List.of("dataset", "dashboard", "data_service"),
            "workflow", List.of("domain", "model", "dimension", "metric"),
            "quality", List.of(),
            "ingestion", List.of(),
            "governance", List.of(),
            "system", List.of()
    );

    /** 取资源的全部祖先链（含自身），由近及远。 */
    public static List<String> lineage(String resourceType) {
        java.util.List<String> chain = new java.util.ArrayList<>();
        String current = resourceType;
        while (current != null) {
            chain.add(current);
            current = ANCESTOR.get(current);
        }
        return chain;
    }

    /** @return 动作是否为读动作 */
    public static boolean isReadAction(String action) {
        return READ_ACTIONS.contains(action);
    }
}