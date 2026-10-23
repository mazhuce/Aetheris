package com.aetheris.auth.controller;

import com.aetheris.auth.service.PermissionDecisionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** UI hints for the signed subject. Resource services remain the final authorization boundary. */
@RestController
@RequestMapping("/api/auth/me/resource-permissions")
public class SelfPermissionController {
    private static final Set<String> TYPES=Set.of("workflow_task","ingestion_task","quality_rule",
            "governance_policy","data_standard","dataset","dashboard");
    private static final Set<String> ACTIONS=Set.of("view","query","update","delete","execute","publish");
    private final PermissionDecisionService permissions;
    public SelfPermissionController(PermissionDecisionService permissions) { this.permissions=permissions; }

    @GetMapping
    public ResponseEntity<PermissionHints> check(
            @RequestAttribute("aetheris.username") String username,
            @RequestParam(required=false) String resourceType,@RequestParam(required=false) String action,
            @RequestParam(required=false) String ids,HttpServletRequest request) {
        if(!Set.of("resourceType","action","ids").containsAll(request.getParameterMap().keySet()))
            throw new IllegalArgumentException("仅接受资源类型、动作和ID；不能指定其他主体或授权上下文");
        if(resourceType==null || !TYPES.contains(resourceType) || action==null || !ACTIONS.contains(action))
            throw new IllegalArgumentException("不支持该资源类型或动作");
        if(ids==null || ids.isBlank()) throw new IllegalArgumentException("请提供资源ID");
        String[] entries=ids.split(",",-1);
        if(entries.length>200) throw new IllegalArgumentException("每次最多查询200个资源");
        Map<Long,Map<String,String>> contexts=new LinkedHashMap<>();
        for(String entry:entries) {
            if(!entry.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("资源ID格式不正确");
            contexts.put(Long.parseLong(entry),Map.of());
        }
        Set<Long> allowed=permissions.allowedIds(username,resourceType,action,contexts);
        List<Long> actual=contexts.keySet().stream().filter(allowed::contains).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PermissionHints(actual));
    }
    public record PermissionHints(List<Long> allowedIds) { }
}
