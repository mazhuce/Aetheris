package com.aetheris.auth.service.impl;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.mapper.AuthUserMapper;
import com.aetheris.auth.service.AuthUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthUserServiceImpl extends ServiceImpl<AuthUserMapper, AuthUser>
        implements AuthUserService {

    @Override
    public Optional<AuthUser> findByUsername(String username) {
        return Optional.ofNullable(getOne(
                new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username)));
    }
}
