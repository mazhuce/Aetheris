package com.aetheris.serving.controller;

import com.aetheris.serving.dto.GrantInput;
import com.aetheris.serving.model.ServiceGrant;
import com.aetheris.serving.service.ServingGrantService;
import com.aetheris.serving.security.ServingSecurity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/serving/grants")
public class GrantController {

    private final ServingGrantService service;
    private final ServingSecurity security;

    @Autowired
    public GrantController(ServingGrantService service, ServingSecurity security) {
        this.service = service; this.security = security;
    }
    public GrantController(ServingGrantService service) { this(service, null); }
    public List<ServiceGrant> list(Optional<String> status) { return service.grants(status); }
    public ServiceGrant grant(Long id) { return service.grant(id); }
    public ServiceGrant create(GrantInput input, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.create(input, username); }
    public ServiceGrant update(Long id, GrantInput input, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.update(id, input); }
    public ServiceGrant revoke(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.revoke(id); }
    public ServiceGrant restore(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.restore(id); }
    public ResponseEntity<Void> delete(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); service.delete(id); return ResponseEntity.noContent().build(); }

    @GetMapping
    public List<ServiceGrant> list(HttpServletRequest request,@RequestParam(required = false) Optional<String> status) {
        security.require(request,"data_service",null,"view");
        return service.grants(status);
    }

    @GetMapping("/{id}")
    public ServiceGrant grant(HttpServletRequest request,@PathVariable Long id) { security.require(request,"data_service",id,"view"); return service.grant(id); }

    @PostMapping
    public ServiceGrant create(
            @Valid @RequestBody GrantInput input,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.create(input, security.requireIdentity(request).username());
    }

    @PutMapping("/{id}")
    public ServiceGrant update(
            @PathVariable Long id,
            @Valid @RequestBody GrantInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        return service.update(id, input);
    }

    @PostMapping("/{id}/revoke")
    public ServiceGrant revoke(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        return service.revoke(id);
    }

    @PostMapping("/{id}/restore")
    public ServiceGrant restore(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        return service.restore(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public static class ForbiddenException extends RuntimeException { }

    @ExceptionHandler(ServingGrantService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ServingGrantService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理服务授权"));
    }

    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> secureForbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
