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
                            "completed_at", "version", "created_at", "updated_at");
            assertThat(columns(statement, "king_ai_access_review_item"))
                    .contains("review_id", "space_id", "subject_type", "subject_id", "subject_name",
                            "role", "last_activity_at", "flags", "decision", "decided_by",
                            "decided_at", "note", "version", "created_at", "updated_at");
            assertThat(columns(statement, "king_ai_user")).contains("last_login_at");
            assertThat(columns(statement, "king_ai_supplier")).contains("api_key_rotated_at");
            assertThat(nullable(statement, "king_ai_access_review", "generated_at")).isFalse();
            assertThat(nullable(statement, "king_ai_access_review_item", "subject_name")).isFalse();
            assertThat(nullable(statement, "king_ai_access_review_item", "subject_id")).isFalse();
            assertThat(nullable(statement, "king_ai_access_review_item", "decision")).isFalse();
            assertThat(nullable(statement, "king_ai_access_review_item", "note")).isTrue();
            assertThat(cascades(statement, "king_ai_access_review")).isTrue();
            assertThat(cascades(statement, "king_ai_access_review_item")).isTrue();
        }
    }

    private boolean nullable(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = '"
                        + table + "' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private boolean cascades(Statement statement, String table) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.table_constraints tc "
                        + "ON tc.constraint_name = rc.constraint_name "
                        + "WHERE tc.table_name = '" + table + "'")) {
            boolean found = false;
            while (rs.next()) {
                if ("CASCADE".equalsIgnoreCase(rs.getString(1))) {
                    found = true;
                }
            }
            return found;
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
