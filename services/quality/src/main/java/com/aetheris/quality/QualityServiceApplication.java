package com.aetheris.quality;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aetheris.quality.mapper")
public class QualityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QualityServiceApplication.class, args);
    }
}
