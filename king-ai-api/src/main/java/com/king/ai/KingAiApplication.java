package com.king.ai;

import com.king.ai.config.KingAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KingAiProperties.class)
public class KingAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KingAiApplication.class, args);
    }
}
