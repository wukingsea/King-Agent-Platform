package com.king.ai.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.system.entity.Space;
import com.king.ai.system.mapper.SpaceMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the deleted_at logical-delete contract against the real H2 schema:
 * archive rows must disappear from regular lookups while staying queryable by id.
 */
class SoftDeletePersistenceTest {

    private static final String URL = "jdbc:h2:mem:soft_delete_v14;MODE=MySQL;"
            + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    private static JdbcDataSource dataSource;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL(URL);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        globalConfig.setSqlInjector(new DefaultSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(SpaceMapper.class);
        configuration.addMapper(AgentMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void spaceDeleteMarksArchivedAtAndHidesRowFromLookups() throws Exception {
        long spaceId;
        try (SqlSession session = sessionFactory.openSession(true)) {
            Space space = new Space();
            space.setName("归档空间");
            space.setCode("archived-space");
            space.setStatus(0);
            space.setCreatedAt(LocalDateTime.now());
            space.setUpdatedAt(LocalDateTime.now());
            SpaceMapper mapper = session.getMapper(SpaceMapper.class);
            mapper.insert(space);
            spaceId = space.getId();

            assertThat(mapper.selectById(spaceId)).isNotNull();
            assertThat(mapper.softDeleteById(spaceId)).isEqualTo(1);
            assertThat(mapper.softDeleteById(spaceId)).isZero();
            assertThat(mapper.selectById(spaceId)).isNull();
            assertThat(mapper.selectCount(new LambdaQueryWrapper<Space>()
                    .eq(Space::getCode, "archived-space"))).isZero();
        }

        assertThat(deletedAt("king_ai_space", spaceId)).isNotNull();
        assertThat(spaceExists("archived-space")).isTrue();
    }

    @Test
    void archivedAgentStaysResolvableForHistoryAndDetachesDeletedModel() throws Exception {
        long agentId;
        try (SqlSession session = sessionFactory.openSession(true)) {
            long spaceId = insertSpace("agent-space");
            long modelId = insertModel(spaceId);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO king_ai_agent (space_id, name, model_id, temperature, max_tokens, "
                                 + "publish_status, status, created_at, updated_at) "
                                 + "VALUES (?, ?, ?, 0.70, 2048, 0, 0, now(), now())")) {
                statement.setLong(1, spaceId);
                statement.setString(2, "归档智能体");
                statement.setLong(3, modelId);
                statement.executeUpdate();
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT id FROM king_ai_agent WHERE name = '归档智能体'");
                 ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                agentId = result.getLong(1);
            }

            AgentMapper mapper = session.getMapper(AgentMapper.class);
            mapper.softDeleteById(agentId);

            assertThat(mapper.selectById(agentId)).isNull();
            Agent archived = mapper.selectAnyById(agentId);
            assertThat(archived).isNotNull();
            assertThat(archived.getName()).isEqualTo("归档智能体");
            assertThat(mapper.selectAnyByIds(java.util.List.of(agentId))).hasSize(1);

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM king_ai_supplier_model WHERE id = ?")) {
                statement.setLong(1, modelId);
                statement.executeUpdate();
            }
            assertThat(modelIdOf(agentId)).isNull();
        }
    }

    private long insertSpace(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO king_ai_space (name, code, status, created_at, updated_at) "
                             + "VALUES (?, ?, 0, now(), now())", new String[]{"id"})) {
            statement.setString(1, code);
            statement.setString(2, code);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertModel(long spaceId) throws Exception {
        long supplierId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO king_ai_supplier (space_id, name, type, base_url, created_at, updated_at) "
                             + "VALUES (?, '供应商', 'OPENAI', 'http://localhost', now(), now())",
                     new String[]{"id"})) {
            statement.setLong(1, spaceId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                supplierId = keys.getLong(1);
            }
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO king_ai_supplier_model (space_id, supplier_id, name, model, type, status, "
                             + "created_at, updated_at) VALUES (?, ?, '模型', 'gpt-test', 'CHAT', 0, now(), now())",
                     new String[]{"id"})) {
            statement.setLong(1, spaceId);
            statement.setLong(2, supplierId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private boolean spaceExists(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM king_ai_space WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private java.sql.Timestamp deletedAt(String table, long id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT deleted_at FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getTimestamp(1);
            }
        }
    }

    private Long modelIdOf(long agentId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT model_id FROM king_ai_agent WHERE id = ?")) {
            statement.setLong(1, agentId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                long value = result.getLong(1);
                return result.wasNull() ? null : value;
            }
        }
    }
}
