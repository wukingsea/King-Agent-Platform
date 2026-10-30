package com.king.ai.common;

import com.king.ai.config.StateStoreHealthIndicator;
import com.king.ai.knowledge.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void reportsUpOnlyWhenDatabaseStateStoreAndVectorStoreAreUp() {
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller(true, true, true).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).containsEntry("vector", "UP");
    }

    @Test
    void vectorStoreOutageMakesTheInstanceNotReady() {
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller(true, true, false).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().data()).containsEntry("status", "DOWN");
        assertThat(response.getBody().data()).containsEntry("vector", "DOWN");
    }

    private static HealthController controller(boolean dbUp, boolean storeUp, boolean vectorUp) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        StateStoreHealthIndicator stateStore = mock(StateStoreHealthIndicator.class);
        when(stateStore.health()).thenReturn(storeUp ? Health.up().build() : Health.down().build());
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.healthy()).thenReturn(vectorUp);
        if (!dbUp) {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .thenThrow(new RuntimeException("db down"));
        }
        return new HealthController(jdbcTemplate, stateStore, vectorStore);
    }
}
