package com.king.ai;

import com.king.ai.config.KingAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(KingAiProperties.class)
public class KingAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KingAiApplication.class, args);
    }
}
