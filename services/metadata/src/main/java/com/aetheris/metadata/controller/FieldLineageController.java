package com.aetheris.metadata.controller;
import com.aetheris.metadata.dto.FieldLineageParseRequest;
import com.aetheris.metadata.entity.LineageFieldRelation;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.FieldLineageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/api/metadata/field-lineages")
public class FieldLineageController {
    private final FieldLineageService service; private final MetadataSecurity security;
    public FieldLineageController(FieldLineageService service, MetadataSecurity security){this.service=service;this.security=security;}
    @GetMapping public List<LineageFieldRelation> list(HttpServletRequest request,@RequestParam(required=false)String table,@RequestParam(required=false)String column){security.requireIdentity(request);return service.list(table,column);}
    @GetMapping("/impact") public List<LineageFieldRelation> impact(HttpServletRequest request,@RequestParam String table,@RequestParam String column){security.requireIdentity(request);return service.impact(table,column);}
    @PostMapping public LineageFieldRelation create(HttpServletRequest request,@Valid @RequestBody LineageFieldRelation relation){String user=security.require(request,"table",null,"update",Map.of()).username();return service.save(relation,user);}
    @PutMapping("/{id}") public LineageFieldRelation update(HttpServletRequest request,@PathVariable Long id,@RequestBody LineageFieldRelation relation){security.require(request,"table",null,"update",Map.of());return service.update(id,relation);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(HttpServletRequest request,@PathVariable Long id){security.require(request,"table",null,"update",Map.of());service.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/parse") public Map<String,Object> parse(HttpServletRequest request,@Valid @RequestBody FieldLineageParseRequest body){String user=security.require(request,"table",null,"update",Map.of()).username();return service.parse(body.sql(),body.relationType(),user);}
}
