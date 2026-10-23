package com.aetheris.metadata.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 调用 auth-service 内部权限端点的客户端。判定输入是经过验签的身份，
 * 判定逻辑由 auth-service 决策引擎维护（角色/授权/组），业务服务不做本地复制。
 *
 * <p>fail-closed：auth-service 不可用或超时一律返回拒绝，绝不在鉴权链路
 * 降级放行。
 */
@Component
public class AuthPermissionClient {

    private static final Logger log = LoggerFactory.getLogger(AuthPermissionClient.class);

    private final com.aetheris.security.protocol.authorization.AuthProtocolClient client;
    private final MeterRegistry meterRegistry;
    private final Counter checkRequests;
    private final Counter checkAllowed;
    private final Counter checkDenied;
    private final Counter checkUnavailable;
    private final Timer checkLatency;
    private final Counter batchRequests;
    private final Counter batchAllowedIds;
    private final Counter batchItems;
    private final Counter batchUnavailable;
    private final Timer batchLatency;

    @Autowired
    public AuthPermissionClient(com.aetheris.security.protocol.authorization.AuthProtocolClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
        this.checkRequests = Counter.builder("aetheris.auth.permission.check.requests").register(meterRegistry);
        this.checkAllowed = Counter.builder("aetheris.auth.permission.check.allowed").register(meterRegistry);
        this.checkDenied = Counter.builder("aetheris.auth.permission.check.denied").register(meterRegistry);
        this.checkUnavailable = Counter.builder("aetheris.auth.permission.check.unavailable").register(meterRegistry);
        this.checkLatency = Timer.builder("aetheris.auth.permission.check.latency").register(meterRegistry);
        this.batchRequests = Counter.builder("aetheris.auth.permission.batch.requests").register(meterRegistry);
        this.batchAllowedIds = Counter.builder("aetheris.auth.permission.batch.allowed_ids").register(meterRegistry);
        this.batchItems = Counter.builder("aetheris.auth.permission.batch.items").register(meterRegistry);
        this.batchUnavailable = Counter.builder("aetheris.auth.permission.batch.unavailable").register(meterRegistry);
        this.batchLatency = Timer.builder("aetheris.auth.permission.batch.latency").register(meterRegistry);
    }

    /** 单条资源级权限判定（与 auth-service PermissionCheckResponse 对齐）。 */
    public Access check(String username, String resourceType, Long resourceId,
                        String action, Map<String, String> ctx) {
        checkRequests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = await(client.check(proofFor(username), resourceType, resourceId, action, ctx));
            if (response == null) {
                checkUnavailable.increment();
                return Access.unavailable("权限服务不可用");
            }
            if (response.allowed()) {
                checkAllowed.increment();
                return Access.ALLOWED;
            }
            checkDenied.increment();
            return new Access(false, response.reason());
        } catch (RuntimeException error) {
            checkUnavailable.increment();
            log.warn("auth permission check unavailable ({} {} {} {}): {}",
                    username, resourceType, resourceId, action, error.getMessage());
            return Access.unavailable("权限服务不可用");
        } finally {
            sample.stop(checkLatency);
        }
    }

    /**
     * 列表可见性判定（与 auth-service VisibilityResponse 对齐）。
     * mode ∈ ALL / IDS / OPAQUE；IDS 时 ids 为根资源（业务域）id 列表。
     */
    public Visibility visibility(String username, String resourceType, String action) {
        try {
            var response = await(client.visibility(proofFor(username), resourceType, action));
            if (response == null) return new Visibility("OPAQUE", List.of());
            return new Visibility(response.mode() == null ? "OPAQUE" : response.mode(),
                    response.ids() == null ? List.of() : response.ids());
        } catch (RuntimeException error) {
            log.warn("auth visibility unavailable ({} {} {}): {}",
                    username, resourceType, action, error.getMessage());
            // 不可用时按"仅自己明确可见的范围"处理会让列表为空（时钟上比放行安全），
            // 但也可能掩盖真实授权；选择 OPAQUE 由调用方逐条 /check 兜底。
            return new Visibility("OPAQUE", List.of());
        }
    }

    public java.util.Set<Long> checkBatch(String username, String resourceType, String action,
                                          Map<Long, Map<String, String>> resourceContexts) {
        if (resourceContexts == null || resourceContexts.isEmpty()) return java.util.Set.of();
        batchRequests.increment();
        batchItems.increment(resourceContexts.size());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = await(client.checkBatch(proofFor(username), resourceType, action, resourceContexts.entrySet().stream()
                    .map(item -> new com.aetheris.security.protocol.authorization.AuthProtocolClient.Item(item.getKey(), item.getValue())).toList()));
            if (response == null) {
                batchUnavailable.increment();
                return java.util.Set.of();
            }
            batchAllowedIds.increment(response.size());
            return java.util.Set.copyOf(response);
        } catch (RuntimeException error) {
            batchUnavailable.increment();
            log.warn("auth batch permission check unavailable: {}", error.getMessage());
            return java.util.Set.of();
        } finally {
            sample.stop(batchLatency);
        }
    }

    /** Lists must distinguish dependency failure from an authorized empty set. */
    public Visibility visibilityStrict(String username, String resourceType, String action) {
        try {
            var response=await(client.visibility(proofFor(username),resourceType,action));
            if(response==null || !java.util.Set.of("ALL","IDS","OPAQUE").contains(response.mode()) || response.ids()==null) throw new IllegalStateException();
            return new Visibility(response.mode(),List.copyOf(response.ids()));
        } catch(RuntimeException failure) {
            throw new MetadataSecurity.PermissionServiceUnavailableException("权限服务暂时不可用，请稍后重试");
        }
    }

    public java.util.Set<Long> checkBatchStrict(String username,String resourceType,String action,
                                               Map<Long,Map<String,String>> contexts) {
        if(contexts==null || contexts.isEmpty()) return java.util.Set.of();
        var entries=List.copyOf(contexts.entrySet());
        var allowed=new java.util.HashSet<Long>();
        for(int offset=0;offset<entries.size();offset+=500) {
            var batch=entries.subList(offset,Math.min(offset+500,entries.size()));
            batchRequests.increment();batchItems.increment(batch.size());
            var sample=Timer.start(meterRegistry);
            try {
                var response=await(client.checkBatch(proofFor(username),resourceType,action,batch.stream()
                        .map(item->new com.aetheris.security.protocol.authorization.AuthProtocolClient.Item(item.getKey(),item.getValue())).toList()));
                if(response==null) throw new IllegalStateException();
                var requested=batch.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                if(!requested.containsAll(response)) throw new IllegalStateException();
                allowed.addAll(response);batchAllowedIds.increment(response.size());
            } catch(RuntimeException failure) {
                batchUnavailable.increment();
                throw new MetadataSecurity.PermissionServiceUnavailableException("权限服务暂时不可用，请稍后重试");
            } finally { sample.stop(batchLatency); }
        }
        return java.util.Set.copyOf(allowed);
    }

    private static String proofFor(String username) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet))
            throw new IllegalStateException("Authenticated request context required");
        var user = com.aetheris.security.servlet.TrustedUser.require(servlet.getRequest());
        if (!user.proof().username().equals(username)) throw new IllegalStateException("Authenticated subject mismatch");
        return user.compactProof();
    }
    private static <T> T await(java.util.concurrent.CompletableFuture<T> pending) {
        try { return pending.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception failure) {
            pending.cancel(true); if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Auth v2 permission unavailable");
        }
    }

    /** 单条判定结果。 */
    public record Access(boolean allowed, String reason, boolean available) {
        public Access(boolean allowed, String reason) { this(allowed, reason, true); }
        public static final Access ALLOWED = new Access(true, null);
        public static Access unavailable(String reason) { return new Access(false, reason, false); }
    }

    /** 列表可见性结果。 */
    public record Visibility(String mode, List<Long> ids) {
    }

    /** 与 auth-service PermissionCheckResponse 对齐。 */
    public record PermissionCheckResponse(boolean allowed, String reason) {
    }

    /** 与 auth-service VisibilityResponse 对齐。 */
    public record VisibilityResponse(String mode, List<Long> ids) {
    }
    public record BatchResponse(List<Long> allowedIds) { }
}
