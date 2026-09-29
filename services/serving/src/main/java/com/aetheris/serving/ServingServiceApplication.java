package com.aetheris.serving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ServingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServingServiceApplication.class, args);
    }
}
