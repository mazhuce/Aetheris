package com.aetheris.governance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aetheris.governance.mapper")
public class GovernanceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GovernanceServiceApplication.class, args);
    }
}
