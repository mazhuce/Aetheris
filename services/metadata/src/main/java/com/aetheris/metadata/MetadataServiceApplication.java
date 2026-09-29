package com.aetheris.metadata;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.aetheris.metadata.mapper")
public class MetadataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataServiceApplication.class, args);
    }
}
