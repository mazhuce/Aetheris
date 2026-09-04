package com.aetheris.metadata.controller;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.QueryAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/metadata/query-audit")
public class QueryAuditController {
    private final QueryAuditService service; private final MetadataSecurity security;
    public QueryAuditController(QueryAuditService service,MetadataSecurity security){this.service=service;this.security=security;}
    @GetMapping("/summary") public Map<String,Object> summary(HttpServletRequest request,@RequestParam(defaultValue="30")int days,@RequestParam(required=false)Long slowMs){var identity=security.require(request,"metric",null,"view",Map.of());return service.summary(identity.username(),false,days,slowMs);}
}
