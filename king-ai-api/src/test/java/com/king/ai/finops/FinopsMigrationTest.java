package com.king.ai.finops;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FinopsMigrationTest {

    private static final String URL = "jdbc:h2:mem:finops_v13;MODE=MySQL;"
            + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v13CreatesUsageLedgerAndSpaceQuotaColumn() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(columns(statement, "king_ai_usage_ledger"))
                    .contains("space_id", "agent_id", "session_id", "message_id", "model",
                            "source_type", "prompt_tokens", "completion_tokens", "total_tokens",
                            "cost_micros", "currency", "status", "created_at");
            assertThat(columns(statement, "king_ai_space")).contains("monthly_token_quota");
        }
    }

    private Set<String> columns(Statement statement, String table) throws Exception {
        try (ResultSet columns = statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'")) {
            Set<String> names = new HashSet<>();
            while (columns.next()) {
                names.add(columns.getString(1).toLowerCase());
            }
            return names;
        }
    }
}
