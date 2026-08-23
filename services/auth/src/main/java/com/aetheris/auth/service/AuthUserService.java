package com.aetheris.auth.service;

import com.aetheris.auth.entity.AuthUser;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Optional;

public interface AuthUserService extends IService<AuthUser> {

    Optional<AuthUser> findByUsername(String username);
}
