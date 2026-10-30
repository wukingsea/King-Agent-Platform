package com.king.ai.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.chat.ChatService;
import com.king.ai.chat.entity.ChatSession;
import com.king.ai.chat.mapper.ChatMessageDeltaMapper;
import com.king.ai.chat.mapper.ChatMessageMapper;
import com.king.ai.chat.mapper.ChatSessionMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.system.SpaceService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 用真实 MySQL 验证生产迁移目录：H2 与 MySQL 的 DDL 语法差异只有在这里才能暴露。
 * 同一容器上补齐租户隔离与会话归属的查询级集成覆盖。
 * 本地没有 Docker 时自动跳过，CI 中必须执行。
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("king_ai_it")
            .withUsername("king")
            .withPassword("king-it-password");

    private static final long SPACE_A = 101;
    private static final long SPACE_B = 102;
    private static final long AGENT_ID = 151;
    private static final long USER_OWNER = 201;
    private static final long USER_MEMBER = 202;
    private static final long SESSION_A_OWNER = 301;
    private static final long SESSION_B_OWNER = 302;
    private static final long SESSION_A_MEMBER = 303;
    private static final long MESSAGE_A = 401;

    private static MigrateResult migrateResult;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void migrateOnce() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration/mysql")
                .load();
        migrateResult = flyway.migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mysql-it", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        globalConfig.setSqlInjector(new DefaultSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(ChatSessionMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(ChatMessageDeltaMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        insertIsolationFixtures();
    }

    @AfterAll
    static void clearFixtures() throws SQLException {
        try (Connection connection = connect()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM king_ai_chat_message WHERE session_id IN (?, ?, ?)")) {
                statement.setLong(1, SESSION_A_OWNER);
                statement.setLong(2, SESSION_B_OWNER);
                statement.setLong(3, SESSION_A_MEMBER);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM king_ai_chat_session WHERE id IN (?, ?, ?)")) {
                statement.setLong(1, SESSION_A_OWNER);
                statement.setLong(2, SESSION_B_OWNER);
                statement.setLong(3, SESSION_A_MEMBER);
                statement.executeUpdate();
            }
            execute(connection, "DELETE FROM king_ai_agent WHERE id = " + AGENT_ID);
            execute(connection, "DELETE FROM king_ai_user WHERE id IN (" + USER_OWNER + ", " + USER_MEMBER + ")");
            execute(connection, "DELETE FROM king_ai_space WHERE id IN (" + SPACE_A + ", " + SPACE_B + ")");
        }
    }

    @Test
    void appliesAndValidatesMysqlMigrations() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isEqualTo(12);
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration/mysql")
                .load()
                .validate();
        try (Connection connection = connect()) {
            assertThat(tableExists(connection, "king_ai_chat_message_delta")).isTrue();
            assertThat(tableExists(connection, "king_ai_agent_version")).isTrue();
            assertThat(tableExists(connection, "king_ai_access_review")).isTrue();
            assertThat(tableExists(connection, "king_ai_audit_log")).isTrue();
            assertThat(tableExists(connection, "king_ai_usage_ledger")).isTrue();
            assertThat(columnExists(connection, "king_ai_space", "deleted_at")).isTrue();
            assertThat(columnExists(connection, "king_ai_agent", "deleted_at")).isTrue();
        }
    }

    @Test
    void streamingDeltaTableCascadesWithItsMessage() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(columnType(connection, "content_delta")).isEqualTo("mediumtext");
            assertThat(deleteRule(connection, "fk_message_delta_message")).isEqualTo("CASCADE");
        }
    }

    @Test
    void tenantScopedQueriesOnlySeeTheCurrentSpace() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatSessionMapper sessions = session.getMapper(ChatSessionMapper.class);

            assertThat(sessions.selectList(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getSpaceId, SPACE_A)))
                    .extracting(ChatSession::getId)
                    .containsExactlyInAnyOrder(SESSION_A_OWNER, SESSION_A_MEMBER);

            assertThat(sessions.selectList(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getSpaceId, SPACE_A)
                    .eq(ChatSession::getOwnerUserId, USER_OWNER)))
                    .extracting(ChatSession::getId)
                    .containsExactly(SESSION_A_OWNER);
        }
    }

    @Test
    void sessionOwnershipHidesAnotherMembersSessions() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatService chatService = chatService(session);

            assertThat(chatService.messages(principal(USER_OWNER, SPACE_A), SESSION_A_OWNER)).hasSize(1);
            assertThatThrownBy(() -> chatService.messages(principal(USER_MEMBER, SPACE_A), SESSION_A_OWNER))
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(404));
            assertThatThrownBy(() -> chatService.rename(
                    principal(USER_MEMBER, SPACE_A), SESSION_A_OWNER, "hijacked"))
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(403));
        }
    }

    @Test
    void crossSpaceSessionLookupIsRejected() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatService chatService = chatService(session);

            assertThat(chatService.requireSession(SPACE_A, SESSION_A_OWNER).getId())
                    .isEqualTo(SESSION_A_OWNER);
            assertThatThrownBy(() -> chatService.requireSession(SPACE_A, SESSION_B_OWNER))
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(404));
        }
    }

    private ChatService chatService(SqlSession session) {
        return new ChatService(
                session.getMapper(ChatSessionMapper.class),
                session.getMapper(ChatMessageMapper.class),
                session.getMapper(ChatMessageDeltaMapper.class),
                mock(AgentMapper.class),
                new ObjectMapper(),
                new KingAiProperties(),
                mock(SpaceService.class));
    }

    private UserPrincipal principal(long userId, long spaceId) {
        return new UserPrincipal(userId, "user" + userId, "User " + userId, "USER", spaceId, "EDITOR", false);
    }

    private static void insertIsolationFixtures() throws SQLException {
        try (Connection connection = connect()) {
            insertSpace(connection, SPACE_A, "it-space-a");
            insertSpace(connection, SPACE_B, "it-space-b");
            insertUser(connection, USER_OWNER, "it-owner");
            insertUser(connection, USER_MEMBER, "it-member");
            execute(connection, "INSERT INTO king_ai_agent (id, space_id, name, model_id, temperature, "
                    + "max_tokens, publish_status, status, created_at, updated_at) VALUES ("
                    + AGENT_ID + ", " + SPACE_A + ", 'IT 智能体', NULL, 0.70, 2048, 0, 0, now(), now())");
            insertSession(connection, SESSION_A_OWNER, SPACE_A, USER_OWNER);
            insertSession(connection, SESSION_B_OWNER, SPACE_B, USER_OWNER);
            insertSession(connection, SESSION_A_MEMBER, SPACE_A, USER_MEMBER);
            execute(connection, "INSERT INTO king_ai_chat_message (id, session_id, role, content, status, "
                    + "prompt_tokens, completion_tokens, duration_ms, created_at, updated_at) VALUES ("
                    + MESSAGE_A + ", " + SESSION_A_OWNER + ", 'user', 'hello', 'COMPLETED', 0, 0, 0, now(), now())");
        }
    }

    private static void insertSpace(Connection connection, long id, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO king_ai_space (id, name, code, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, now(), now())")) {
            statement.setLong(1, id);
            statement.setString(2, code);
            statement.setString(3, code);
            statement.executeUpdate();
        }
    }

    private static void insertUser(Connection connection, long id, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO king_ai_user (id, username, password, nickname, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', ?, 'USER', 0, now(), now())")) {
            statement.setLong(1, id);
            statement.setString(2, username);
            statement.setString(3, username);
            statement.executeUpdate();
        }
    }

    private static void insertSession(Connection connection, long id, long spaceId, long ownerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO king_ai_chat_session (id, space_id, agent_id, owner_user_id, source_type, "
                        + "visitor_id, title, message_count, last_message_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ADMIN', '', 'IT 会话', 1, now(), now(), now())")) {
            statement.setLong(1, id);
            statement.setLong(2, spaceId);
            statement.setLong(3, AGENT_ID);
            statement.setLong(4, ownerId);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private String columnType(Connection connection, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'king_ai_chat_message_delta' "
                        + "AND COLUMN_NAME = ?")) {
            statement.setString(1, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1).toLowerCase();
            }
        }
    }

    private String deleteRule(Connection connection, String constraint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ?")) {
            statement.setString(1, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }
}
