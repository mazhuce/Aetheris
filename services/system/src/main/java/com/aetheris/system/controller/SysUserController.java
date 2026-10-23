package com.aetheris.system.controller;

import com.aetheris.system.entity.SysUser;
import com.aetheris.system.dto.SysUserResponse;
import com.aetheris.system.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/users")
public class SysUserController {

    private final SysUserService sysUserService;

    public SysUserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @GetMapping
    public List<SysUserResponse> list() {
        return sysUserService.list().stream().map(SysUserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SysUserResponse> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getById(id);
        return user != null ? ResponseEntity.ok(SysUserResponse.from(user)) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public SysUserResponse create(@Valid @RequestBody SysUser user) {
        sysUserService.save(user);
        return SysUserResponse.from(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SysUserResponse> update(@PathVariable Long id, @Valid @RequestBody SysUser user) {
        user.setId(id);
        boolean updated = sysUserService.updateById(user);
        return updated ? ResponseEntity.ok(SysUserResponse.from(sysUserService.getById(id))) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = sysUserService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
