package com.aetheris.gateway.config;

import reactor.core.publisher.Mono;

/**
 * 网关粗粒度入口判定：写请求按服务（metadata/serving/workflow/quality/
 * ingestion/governance/system）问询 auth 决策引擎，敏感管理入口按
 * 系统管理权限问询。判定结果不会替代业务服务的资源级权限判断。
 */
public interface PermissionGate {

    /** 该用户是否具备某服务的写入口（粗粒度）。 */
    Mono<Boolean> mayMutate(String userProof, String service);

    /** 该用户是否具备系统管理入口（用户管理、RBAC 管理、审计日志）。 */
    Mono<Boolean> mayManage(String userProof);

    /** Live account state and access revocation version; missing implementation fails closed. */
    default Mono<Boolean> sessionActive(Long uid, String username, long accessVersion) {
        return Mono.just(false);
    }
    /** Uncached browser family and live account admission; never fall back to account-only checks. */
    default Mono<Boolean> browserSessionActive(Long uid, String username, long accessVersion, String sid) {
        return Mono.just(false);
    }
}
