package com.king.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "king-ai.demo", name = "enabled", havingValue = "true")
public class DemoBootstrap {

    private final ServletWebServerApplicationContext webContext;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void prepareDemo() {
        syncDemoMcpUrl();
    }

    private void syncDemoMcpUrl() {
        try {
            int port = webContext.getWebServer().getPort();
            String url = "http://localhost:" + port + "/api/demo/mcp";
            int updated = jdbcTemplate.update(
                    "UPDATE king_ai_mcp SET url = ? WHERE url LIKE '%/api/demo/mcp' AND url LIKE 'http://localhost:%'",
                    url);
            if (updated > 0) {
                log.info("Demo MCP endpoint aligned to {}", url);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to align demo MCP endpoint: {}", ex.getMessage());
        }
    }
}
