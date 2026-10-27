package com.king.ai.compliance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessReviewMigrationTest {

    private static final String URL = "jdbc:h2:mem:compliance_v11;MODE=MySQL;"
            + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v11CreatesAccessReviewTablesAndActivityColumns() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(columns(statement, "king_ai_access_review"))
                    .contains("space_id", "status", "summary", "generated_by", "generated_at",
                            "completed_at");
            assertThat(columns(statement, "king_ai_access_review_item"))
                    .contains("review_id", "subject_type", "subject_id", "subject_name", "role",
                            "last_activity_at", "flags", "decision", "decided_by", "decided_at");
            assertThat(columns(statement, "king_ai_user")).contains("last_login_at");
            assertThat(columns(statement, "king_ai_supplier")).contains("api_key_rotated_at");
        }
    }

    private Set<String> columns(Statement statement, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_name = '"
                        + table + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1).toLowerCase());
            }
        }
        return names;
    }
}
