package com.king.ai.common;

import com.king.ai.config.StateStoreHealthIndicator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness endpoint used by the container healthcheck. It must check the dependencies
 * required to serve requests instead of reporting a static UP.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StateStoreHealthIndicator stateStore;

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        boolean dbUp;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbUp = true;
        } catch (RuntimeException ex) {
            dbUp = false;
        }
        Health store = stateStore.health();
        boolean storeUp = Status.UP.equals(store.getStatus());
        boolean up = dbUp && storeUp;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("app", "King AI Platform");
        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("stateStore", store.getStatus().getCode());
        body.put("time", LocalDateTime.now());
        ApiResponse<Map<String, Object>> response = up
                ? ApiResponse.ok(body)
                : new ApiResponse<>(503, "DOWN", body);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
