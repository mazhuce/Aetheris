package com.aetheris.auth.config;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(AuthUserService authUserService, PasswordEncoder passwordEncoder) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (authUserService.count() == 0) {
            AuthUser admin = new AuthUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setNickname("系统管理员");
            admin.setStatus("active");
            authUserService.save(admin);
        }
    }
}
