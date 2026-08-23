package com.aetheris.system.controller;

import com.aetheris.system.entity.SysUser;
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
    public List<SysUser> list() {
        return sysUserService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SysUser> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getById(id);
        return user != null ? ResponseEntity.ok(user) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public SysUser create(@Valid @RequestBody SysUser user) {
        sysUserService.save(user);
        return user;
    }

    @PutMapping("/{id}")
    public ResponseEntity<SysUser> update(@PathVariable Long id, @Valid @RequestBody SysUser user) {
        user.setId(id);
        boolean updated = sysUserService.updateById(user);
        return updated ? ResponseEntity.ok(sysUserService.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = sysUserService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
