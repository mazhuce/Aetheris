package com.aetheris.auth.config;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String nickname;

    public DataInitializer(
            AuthUserService authUserService,
            PasswordEncoder passwordEncoder,
            @Value("${aetheris.bootstrap-admin.username:admin}") String username,
            @Value("${aetheris.bootstrap-admin.password:}") String password,
            @Value("${aetheris.bootstrap-admin.nickname:系统管理员}") String nickname) {
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }

    @Override
    public void run(String... args) {
        if (authUserService.count() > 0) {
            return;
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("INITIAL_ADMIN_USERNAME must not be blank");
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("INITIAL_ADMIN_PASSWORD must contain at least 12 characters on first start");
        }

        AuthUser admin = new AuthUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setNickname(nickname);
        admin.setRole("admin");
        admin.setStatus("active");
        authUserService.save(admin);
    }
}
