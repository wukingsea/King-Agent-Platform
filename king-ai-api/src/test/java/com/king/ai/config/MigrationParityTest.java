package com.king.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2（本地）与 MySQL（生产）各维护一套 Flyway 迁移，语法差异由方言决定，
 * 但逻辑结构必须一致：同一版本号、相同的表/列/索引/外键集合。
 * 本测试把两套迁移解析成与具体 SQL 方言无关的操作集合并逐版本比对，
 * 任何一侧漏掉表、列、索引或外键都会在这里失败，而不是等到运行时暴露。
 *
 * 比对粒度说明：只比较对象名称与归属，不比较列类型、默认值、注释等
 * 方言相关细节（例如 VARCHAR 长度、DATETIME 与 TIMESTAMP 的差异）。
 */
class MigrationParityTest {

    private static final String H2_LOCATION = "classpath:db/migration/h2/*.sql";
    private static final String MYSQL_LOCATION = "classpath:db/migration/mysql/*.sql";

    private static final Pattern FILE_VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:CACHED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)");
    private static final Pattern DROP_TABLE = Pattern.compile(
            "(?i)\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?i)\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?i)\\bADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)");
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "(?i)\\bDROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)");
    private static final Pattern MODIFY_COLUMN = Pattern.compile(
            "(?i)\\b(?:MODIFY|ALTER)\\s+COLUMN\\s+([a-zA-Z_]\\w*)");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?i)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_]\\w*)\\s+ON\\s+([a-zA-Z_]\\w*)");
    private static final Pattern INLINE_KEY = Pattern.compile(
            "(?i)(?:^|[,\\s])((?:UNIQUE)\\s+)?(?:KEY|INDEX)\\s+([a-zA-Z_]\\w*)\\s*\\(");
    private static final Pattern FK_DEFINE = Pattern.compile(
            "(?i)\\bCONSTRAINT\\s+([a-zA-Z_]\\w*)\\s+FOREIGN\\s+KEY");
    private static final Pattern FK_DROP = Pattern.compile(
            "(?i)\\bDROP\\s+(?:CONSTRAINT\\s+(?:IF\\s+EXISTS\\s+)?|FOREIGN\\s+KEY\\s+)([a-zA-Z_]\\w*)");

    @Test
    void h2AndMysqlMigrationsHaveIdenticalLogicalStructure() throws IOException {
        Map<String, Set<String>> h2 = parseLocation(H2_LOCATION);
        Map<String, Set<String>> mysql = parseLocation(MYSQL_LOCATION);

        assertThat(h2.keySet()).as("H2/MySQL 迁移版本集合必须一致").isEqualTo(mysql.keySet());

        for (String version : h2.keySet()) {
            assertThat(h2.get(version))
                    .as("V%s 迁移在 H2 与 MySQL 下的逻辑结构必须一致（表/列/索引/外键）", version)
                    .isEqualTo(mysql.get(version));
        }
    }

    private Map<String, Set<String>> parseLocation(String location) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Set<String>> byVersion = new TreeMap<>();
        for (Resource resource : resolver.getResources(location)) {
            String name = resource.getFilename();
            assertThat(name).as("迁移文件名不能为空").isNotNull();
            Matcher versionMatcher = FILE_VERSION.matcher(name);
            assertThat(versionMatcher.matches()).as("迁移文件名必须符合 V<n>__desc.sql: %s", name).isTrue();
            String version = "V" + versionMatcher.group(1);
            byVersion.put(version, parseSql(read(resource)));
        }
        assertThat(byVersion).as("迁移目录 %s 不能为空", location).isNotEmpty();
        return byVersion;
    }

    private String read(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 将一份迁移脚本归约为逻辑操作集合。先剥离注释，再按分号切分语句，
     * 逐语句提取对象级操作；无法识别的语句（UPDATE/INSERT/SET 等）
     * 不参与比对，因为数据修复语句本来就允许方言差异。
     */
    private Set<String> parseSql(String sql) {
        String noComments = sql.replaceAll("(?m)--[^\\n]*", " ").replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        Set<String> ops = new TreeSet<>();
        for (String statement : noComments.split(";")) {
            collect(statement, ops);
        }
        // 外键定义可能横跨语句边界（例如 CREATE TABLE 内联约束），
        // 因此对整份脚本再做一次全局扫描补充。
        collectForeignKeys(noComments, ops);
        return ops;
    }

    private void collect(String statement, Set<String> ops) {
        Matcher table = CREATE_TABLE.matcher(statement);
        if (table.find()) {
            String name = lower(table.group(1));
            ops.add("table+" + name);
            // MySQL 允许在 CREATE TABLE 内联声明二级索引（KEY/UNIQUE KEY），
            // H2 侧用独立 CREATE INDEX；两种写法都归一为 idx 操作。
            collectInlineKeys(statement, name, ops);
            return;
        }
        if ((table = DROP_TABLE.matcher(statement)).find()) {
            ops.add("table-" + lower(table.group(1)));
            return;
        }
        if ((table = ALTER_TABLE.matcher(statement)).find()) {
            String tableName = lower(table.group(1));
            Matcher column = ADD_COLUMN.matcher(statement);
            while (column.find()) {
                ops.add("col+" + tableName + "." + lower(column.group(1)));
            }
            Matcher dropColumn = DROP_COLUMN.matcher(statement);
            while (dropColumn.find()) {
                ops.add("col-" + tableName + "." + lower(dropColumn.group(1)));
            }
            Matcher modifyColumn = MODIFY_COLUMN.matcher(statement);
            while (modifyColumn.find()) {
                ops.add("col~" + tableName + "." + lower(modifyColumn.group(1)));
            }
            // MySQL 允许 ALTER TABLE ... ADD KEY/INDEX 内联加索引，
            // 与 CREATE INDEX 是同一逻辑操作，归一为 idx。
            collectInlineKeys(statement, tableName, ops);
            return;
        }
        Matcher index = CREATE_INDEX.matcher(statement);
        if (index.find()) {
            String unique = index.group(1) == null ? "" : "u";
            ops.add("idx" + unique + "+" + lower(index.group(2)) + "@" + lower(index.group(3)));
        }
    }

    private void collectInlineKeys(String statement, String tableName, Set<String> ops) {
        Matcher key = INLINE_KEY.matcher(statement);
        while (key.find()) {
            String unique = key.group(1) == null ? "" : "u";
            ops.add("idx" + unique + "+" + lower(key.group(2)) + "@" + tableName);
        }
    }

    private void collectForeignKeys(String sql, Set<String> ops) {
        Matcher define = FK_DEFINE.matcher(sql);
        while (define.find()) {
            ops.add("fk+" + lower(define.group(1)));
        }
        Matcher drop = FK_DROP.matcher(sql);
        while (drop.find()) {
            ops.add("fk-" + lower(drop.group(1)));
        }
    }

    private String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
