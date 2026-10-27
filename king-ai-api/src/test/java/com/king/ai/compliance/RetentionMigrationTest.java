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

class RetentionMigrationTest {

    private static final String URL = "jdbc:h2:mem:compliance_v10;MODE=MySQL;"
            + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v10CreatesRetentionHoldAndExportTables() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(columns(statement, "king_ai_retention_policy"))
                    .contains("space_id", "resource_type", "retention_days", "enabled",
                            "archive_before_delete", "version");
            assertThat(columns(statement, "king_ai_legal_hold"))
                    .contains("space_id", "resource_type", "resource_id", "reason", "until",
                            "released_at", "released_by");
            assertThat(columns(statement, "king_ai_export_job"))
                    .contains("space_id", "export_type", "format", "status", "file_path",
                            "row_count", "requested_by", "expires_at");
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
