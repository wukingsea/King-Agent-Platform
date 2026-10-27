package com.king.ai.compliance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceMigrationTest {

    private static final String URL = "jdbc:h2:mem:compliance_v9;MODE=MySQL;"
            + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v9CreatesComplianceSettingTable() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_name = 'king_ai_compliance_setting'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (columns.next()) {
                names.add(columns.getString(1).toLowerCase());
            }
            assertThat(names).contains("space_id", "pii_enabled", "moderation_mode",
                    "moderation_input_action", "moderation_output_action", "blocklist",
                    "pii_custom_patterns", "version");
        }
    }
}
