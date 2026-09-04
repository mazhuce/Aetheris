package com.aetheris.metadata.controller;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.SemanticDependencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/metadata/semantic-dependencies")
public class SemanticDependencyController {
    private final SemanticDependencyService service; private final MetadataSecurity security;
    public SemanticDependencyController(SemanticDependencyService service,MetadataSecurity security){this.service=service;this.security=security;}
    @GetMapping public Map<String,Object> graph(HttpServletRequest request){security.requireIdentity(request);return service.graph();}
}
