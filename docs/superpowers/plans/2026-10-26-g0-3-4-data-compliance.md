# G0-3/G0-4 数据保留、法律保留、导出与访问复核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让空间可以按资源类型配置保留期、在法律保留优先的前提下清理过期会话/消息/审计日志，提供可审计的异步导出；并提供可生成、可决策、可导出 CSV 的季度访问复核。

**Architecture:** `king_ai_retention_policy`、`king_ai_legal_hold`、`king_ai_export_job` 三张表承载保留与导出；`ExportStorage` 端口默认落本地受控目录，后续 P2-3 可替换为对象存储。清理任务先写归档（可选）再删除，跳过任何 active hold。访问复核用 `king_ai_access_review` + `king_ai_access_review_item` 固化快照，决策即时作用到成员关系并写审计；`king_ai_user.last_login_at` 与 `king_ai_supplier.api_key_rotated_at` 提供“90 天未使用/未轮换”证据。

**Tech Stack:** Java 17、Spring Boot 3.5.16、MyBatis-Plus、Flyway H2/MySQL、JdbcTemplate、Micrometer、JUnit 5 + AssertJ + Mockito。

**Spec:** `docs/superpowers/plans/2026-10-26-p2-scale-program.md` 的 G0-3、G0-4 小节。

## Global Constraints

- 本计划占用迁移 **V10**（保留/法律保留/导出）与 **V11**（访问复核、登录时间、密钥轮换时间）；不得修改 V1–V9。
- 默认 `king-ai.compliance.retention.enabled=false`、`dry-run=true`，避免升级后静默删除数据。启用与关闭 dry-run 必须由运维显式操作。
- 法律保留优先级最高：任何清理、导出过期删除都必须跳过 active hold。
- 导出仅 `OWNER/ADMIN` 可创建和下载；文件有效期默认 24 小时；文件名使用 UUID，禁止用户控制路径。
- 导出内容中的消息正文必须经过 `RedactionService`（G0-1 产物）；G0-1 是本计划的前置，禁止以“G0-1 未实施”为由导出原文。
- 所有删除、导出、复核动作写审计；审计 detail 只含数量、类型、任务 ID，不含正文。
- 服务端提示中文；日志参数化；不得把 `file_path`、密钥、导出正文写入日志。
- 测试使用 `mvn -pl king-ai-api -Dtest=<Class> test`；每个任务结束跑一次 `mvn -pl king-ai-api test`。
- 版本控制前置条件：`git rev-parse --is-inside-work-tree` 失败时先 `git init` 并提交基线；不提交 `data/exports`。

---

## File Structure

**Create**
- `king-ai-api/src/main/resources/db/migration/{h2,mysql}/V10__compliance_retention.sql`
- `king-ai-api/src/main/resources/db/migration/{h2,mysql}/V11__access_review.sql`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionType.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicy.java`（entity）
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicyMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/LegalHold.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/LegalHoldMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/ExportJob.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/ExportJobMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicyEvaluator.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicyService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/LegalHoldService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionJob.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportFormat.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportType.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportStatus.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportStorage.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/LocalExportStorage.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportSink.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportSinkFactory.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportJobWorker.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReview.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewItem.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewItemMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewReportBuilder.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewDecisions.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/access/CsvWriter.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceController.java`
- DTOs under `com/king/ai/compliance/dto/`：`RetentionPolicyView`、`RetentionPolicyUpdateRequest`、`LegalHoldView`、`LegalHoldCreateRequest`、`ExportJobView`、`ExportCreateRequest`、`AccessReviewView`、`AccessReviewItemView`、`AccessReviewDecisionRequest`
- Tests under `king-ai-api/src/test/java/com/king/ai/compliance/`：`RetentionPolicyEvaluatorTest`、`RetentionMigrationTest`、`LegalHoldServiceTest`、`LocalExportStorageTest`、`ExportSinkTest`、`ExportServiceTest`、`AccessReviewMigrationTest`、`AccessReviewReportBuilderTest`、`AccessReviewDecisionsTest`、`CsvWriterTest`、`AccessReviewServiceTest`

**Modify**
- `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- `king-ai-api/src/main/java/com/king/ai/system/entity/User.java`
- `king-ai-api/src/main/java/com/king/ai/auth/AuthService.java`
- `king-ai-api/src/main/java/com/king/ai/model/entity/Supplier.java`
- `king-ai-api/src/main/java/com/king/ai/model/SupplierService.java`
- `king-ai-api/src/main/resources/application.yml`
- `docs/DEPLOYMENT.md`

---

### Task 1: V10 保留策略、法律保留、导出任务表

**Files:**
- Create: `king-ai-api/src/main/resources/db/migration/h2/V10__compliance_retention.sql`
- Create: `king-ai-api/src/main/resources/db/migration/mysql/V10__compliance_retention.sql`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/retention/{RetentionType,RetentionPolicy,RetentionPolicyMapper,LegalHold,LegalHoldMapper,ExportJob,ExportJobMapper}.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/{ExportFormat,ExportType,ExportStatus}.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- Modify: `king-ai-api/src/main/resources/application.yml`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/RetentionMigrationTest.java`

**Interfaces:**
- Produces enums: `RetentionType { SESSION, MESSAGE, AUDIT, EXPORT }`、`ExportType { CONVERSATIONS, AUDIT, MEMBERS }`、`ExportFormat { JSONL, CSV }`、`ExportStatus { PENDING, RUNNING, COMPLETED, FAILED, EXPIRED }`
- Produces entities：`RetentionPolicy`、`LegalHold`、`ExportJob`（均继承 `BaseEntity`，带 `@Version`）

- [ ] **Step 1: 写失败测试**

```java
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
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=RetentionMigrationTest test`

Expected: FAIL，表不存在。

- [ ] **Step 3: 迁移与实体**

`h2/V10__compliance_retention.sql`：

```sql
CREATE TABLE IF NOT EXISTS king_ai_retention_policy (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    space_id              BIGINT      NOT NULL,
    resource_type         VARCHAR(20) NOT NULL,
    retention_days        INT         NOT NULL,
    enabled               TINYINT     NOT NULL DEFAULT 1,
    archive_before_delete TINYINT     NOT NULL DEFAULT 1,
    version               INT         NOT NULL DEFAULT 0,
    created_by            BIGINT      NULL,
    created_at            DATETIME    NOT NULL,
    updated_by            BIGINT      NULL,
    updated_at            DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_retention_space_type
    ON king_ai_retention_policy (space_id, resource_type);
ALTER TABLE king_ai_retention_policy ADD CONSTRAINT fk_retention_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_legal_hold (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    until         DATETIME     NULL,
    released_at   DATETIME     NULL,
    released_by   BIGINT       NULL,
    version       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL,
    updated_by    BIGINT       NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_hold_space_type
    ON king_ai_legal_hold (space_id, resource_type, released_at);
CREATE INDEX IF NOT EXISTS idx_hold_resource
    ON king_ai_legal_hold (space_id, resource_id);
ALTER TABLE king_ai_legal_hold ADD CONSTRAINT fk_hold_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_export_job (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    export_type   VARCHAR(20)   NOT NULL,
    format        VARCHAR(10)   NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    from_at       DATETIME      NULL,
    to_at         DATETIME      NULL,
    file_name     VARCHAR(255)  NULL,
    file_path     VARCHAR(1000) NULL,
    row_count     BIGINT        NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    requested_by  BIGINT        NULL,
    expires_at    DATETIME      NULL,
    started_at    DATETIME      NULL,
    completed_at  DATETIME      NULL,
    version       INT           NOT NULL DEFAULT 0,
    created_by    BIGINT        NULL,
    created_at    DATETIME      NOT NULL,
    updated_by    BIGINT        NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_export_space_status
    ON king_ai_export_job (space_id, status);
CREATE INDEX IF NOT EXISTS idx_export_expires
    ON king_ai_export_job (expires_at);
ALTER TABLE king_ai_export_job ADD CONSTRAINT fk_export_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
```

`mysql/V10__compliance_retention.sql`：同样三张表，使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`，索引写成表内 `UNIQUE KEY` / `KEY`，外键写在表内。列类型：`TINYINT`、`DATETIME`、`TEXT` 保持与 H2 对应。

`KingAiProperties.Compliance` 增加：

```java
        private Retention retention = new Retention();
        private Export export = new Export();
        private AccessReview accessReview = new AccessReview();

        @Data
        public static class Retention {
            private boolean enabled = false;
            private boolean dryRun = true;
            private String cron = "0 0 3 * * *";
            private int batchSize = 200;
            private int maxBatches = 100;
        }

        @Data
        public static class Export {
            private String rootDir = "./data/exports";
            private long pollIntervalMs = 10000;
            private int ttlHours = 24;
            private long maxRows = 1_000_000;
            private int batchSize = 500;
        }

        @Data
        public static class AccessReview {
            private String cron = "0 0 4 1 1,4,7,10 *";
            private int staleDays = 90;
        }
```

`application.yml`：

```yaml
  compliance:
    retention:
      enabled: ${KING_AI_RETENTION_ENABLED:false}
      dry-run: ${KING_AI_RETENTION_DRY_RUN:true}
      cron: ${KING_AI_RETENTION_CRON:0 0 3 * * *}
      batch-size: ${KING_AI_RETENTION_BATCH_SIZE:200}
      max-batches: 100
    export:
      root-dir: ${KING_AI_EXPORT_DIR:./data/exports}
      poll-interval-ms: 10000
      ttl-hours: ${KING_AI_EXPORT_TTL_HOURS:24}
      max-rows: ${KING_AI_EXPORT_MAX_ROWS:1000000}
      batch-size: 500
    access-review:
      cron: ${KING_AI_ACCESS_REVIEW_CRON:0 0 4 1 1,4,7,10 *}
      stale-days: ${KING_AI_ACCESS_STALE_DAYS:90}
```

实体（以下三个类都是完整定义，均继承 `BaseEntity`）：

```java
package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_retention_policy")
public class RetentionPolicy extends BaseEntity {

    private Long spaceId;
    private String resourceType;
    private Integer retentionDays;
    private Integer enabled;
    private Integer archiveBeforeDelete;

    @Version
    private Integer version;
}
```

```java
package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_legal_hold")
public class LegalHold extends BaseEntity {

    private Long spaceId;
    private String resourceType;
    private Long resourceId;
    private String reason;
    private LocalDateTime until;
    private LocalDateTime releasedAt;
    private Long releasedBy;

    @Version
    private Integer version;
}
```

```java
package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_export_job")
public class ExportJob extends BaseEntity {

    private Long spaceId;
    private String exportType;
    private String format;
    private String status;
    private LocalDateTime fromAt;
    private LocalDateTime toAt;
    private String fileName;
    private String filePath;
    private Long rowCount;
    private String errorMessage;
    private Long requestedBy;
    private LocalDateTime expiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Version
    private Integer version;
}
```

Mapper 均为 `@Mapper public interface XMapper extends BaseMapper<X> {}`，三个 Mapper 都放在 `com.king.ai.compliance.retention`。

枚举（四个独立文件）：

```java
package com.king.ai.compliance.retention;

public enum RetentionType { SESSION, MESSAGE, AUDIT, EXPORT }
```

```java
package com.king.ai.compliance.export;

public enum ExportFormat { JSONL, CSV }
```

```java
package com.king.ai.compliance.export;

public enum ExportType { CONVERSATIONS, AUDIT, MEMBERS }
```

```java
package com.king.ai.compliance.export;

public enum ExportStatus { PENDING, RUNNING, COMPLETED, FAILED, EXPIRED }
```

`ExportJob.exportType`、`format`、`status` 均存 `String`（写入时用 `ExportType.valueOf(...).name()` 等），因此实体本身不依赖 `export` 包。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=RetentionMigrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/resources/db/migration king-ai-api/src/main/java/com/king/ai/compliance king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java king-ai-api/src/main/resources/application.yml
git commit -m "feat(compliance): add retention legal hold and export schema"
```

---

### Task 2: 保留期计算、策略 CRUD 与法律保留

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicyEvaluator.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionPolicyService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/retention/LegalHoldService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/dto/{RetentionPolicyView,RetentionPolicyUpdateRequest,LegalHoldView,LegalHoldCreateRequest}.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/RetentionPolicyEvaluatorTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/LegalHoldServiceTest.java`

**Interfaces:**
- Consumes: `AuditLogService.recordEvent(...)`（G0-1 产物）
- Produces: `static LocalDateTime RetentionPolicyEvaluator.cutoff(LocalDateTime now, int days)`
- Produces: `static boolean RetentionPolicyEvaluator.deletable(LocalDateTime activity, LocalDateTime cutoff, boolean held)`
- Produces: `List<RetentionPolicyView> RetentionPolicyService.list(Long spaceId)`
- Produces: `RetentionPolicyView RetentionPolicyService.upsert(Long spaceId, RetentionPolicyUpdateRequest request, Long operatorId)`
- Produces: `List<LegalHoldView> LegalHoldService.list(Long spaceId)`
- Produces: `LegalHoldView LegalHoldService.create(Long spaceId, LegalHoldCreateRequest request, Long operatorId)`
- Produces: `void LegalHoldService.release(Long spaceId, Long holdId, Long operatorId)`
- Produces: `boolean LegalHoldService.isHeld(Long spaceId, String resourceType, Long resourceId)`
- Produces: `boolean LegalHoldService.isSessionHeld(Long spaceId, Long sessionId)`
- Produces: `Set<Long> LegalHoldService.activeResourceIds(Long spaceId, String resourceType)`

- [ ] **Step 1: 写失败测试**

`RetentionPolicyEvaluatorTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.retention.RetentionPolicyEvaluator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPolicyEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void cutoffUsesAtLeastOneDay() {
        assertThat(RetentionPolicyEvaluator.cutoff(NOW, 30))
                .isEqualTo(NOW.minusDays(30));
        assertThat(RetentionPolicyEvaluator.cutoff(NOW, 0))
                .isEqualTo(NOW.minusDays(1));
    }

    @Test
    void deletableRequiresExpiredAndNotHeld() {
        LocalDateTime cutoff = NOW.minusDays(30);

        assertThat(RetentionPolicyEvaluator.deletable(cutoff.minusSeconds(1), cutoff, false)).isTrue();
        assertThat(RetentionPolicyEvaluator.deletable(cutoff, cutoff, false)).isFalse();
        assertThat(RetentionPolicyEvaluator.deletable(cutoff.minusDays(1), cutoff, true)).isFalse();
        assertThat(RetentionPolicyEvaluator.deletable(null, cutoff, false)).isFalse();
    }
}
```

`LegalHoldServiceTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.dto.LegalHoldCreateRequest;
import com.king.ai.compliance.retention.LegalHold;
import com.king.ai.compliance.retention.LegalHoldMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalHoldServiceTest {

    private final LegalHoldMapper mapper = mock(LegalHoldMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LegalHoldService service = new LegalHoldService(mapper, jdbcTemplate,
            mock(com.king.ai.audit.AuditLogService.class));

    @Test
    void rejectsUntilInThePast() {
        LegalHoldCreateRequest request = new LegalHoldCreateRequest(
                "SESSION", 9L, "诉讼保全", LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.create(1L, request, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("截止时间");
    }

    @Test
    void rejectsUnknownResourceType() {
        LegalHoldCreateRequest request = new LegalHoldCreateRequest(
                "UNKNOWN", 9L, "原因", null);

        assertThatThrownBy(() -> service.create(1L, request, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保留类型");
    }

    @Test
    void releasingAlreadyReleasedHoldConflicts() {
        LegalHold hold = new LegalHold();
        hold.setId(3L);
        hold.setSpaceId(1L);
        hold.setReleasedAt(LocalDateTime.now());
        when(mapper.selectById(3L)).thenReturn(hold);

        assertThatThrownBy(() -> service.release(1L, 3L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已解除");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=RetentionPolicyEvaluatorTest,LegalHoldServiceTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`RetentionPolicyEvaluator.java`：

```java
package com.king.ai.compliance.retention;

import java.time.LocalDateTime;

public final class RetentionPolicyEvaluator {

    private RetentionPolicyEvaluator() {
    }

    public static LocalDateTime cutoff(LocalDateTime now, int days) {
        return now.minusDays(Math.max(1, days));
    }

    public static boolean deletable(LocalDateTime activity, LocalDateTime cutoff, boolean held) {
        return !held && activity != null && activity.isBefore(cutoff);
    }
}
```

`RetentionPolicyService`：

```java
@Service
@RequiredArgsConstructor
public class RetentionPolicyService {

    private final RetentionPolicyMapper mapper;
    private final AuditLogService auditLogService;

    public List<RetentionPolicyView> list(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                        .eq(RetentionPolicy::getSpaceId, spaceId)
                        .orderByAsc(RetentionPolicy::getResourceType))
                .stream().map(this::view).toList();
    }

    @Transactional
    public RetentionPolicyView upsert(Long spaceId, RetentionPolicyUpdateRequest request,
                                      Long operatorId) {
        RetentionType type = parse(request.resourceType());
        if (request.retentionDays() == null || request.retentionDays() < 1
                || request.retentionDays() > 3650) {
            throw BizException.badRequest("保留天数必须在 1–3650 之间");
        }
        RetentionPolicy policy = mapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getSpaceId, spaceId)
                .eq(RetentionPolicy::getResourceType, type.name()).last("LIMIT 1"));
        boolean insert = policy == null;
        if (insert) {
            policy = new RetentionPolicy();
            policy.setSpaceId(spaceId);
            policy.setResourceType(type.name());
        }
        policy.setRetentionDays(request.retentionDays());
        policy.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        policy.setArchiveBeforeDelete(Boolean.FALSE.equals(request.archiveBeforeDelete()) ? 0 : 1);
        if (insert) {
            mapper.insert(policy);
        } else {
            OptimisticLocks.requireUpdated(mapper.updateById(policy), "保留策略");
        }
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.retention_policy_update",
                "retention_policy", type.name(), AuditLogService.STATUS_SUCCESS,
                "days=" + policy.getRetentionDays() + ", enabled=" + policy.getEnabled()
                        + ", archive=" + policy.getArchiveBeforeDelete(), null, null, null);
        return view(policy);
    }

    private RetentionPolicyView view(RetentionPolicy policy) {
        return new RetentionPolicyView(policy.getId(), policy.getResourceType(),
                policy.getRetentionDays(),
                policy.getEnabled() != null && policy.getEnabled() == 1,
                policy.getArchiveBeforeDelete() != null && policy.getArchiveBeforeDelete() == 1);
    }

    public List<RetentionPolicy> enabledPolicies(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getSpaceId, spaceId)
                .eq(RetentionPolicy::getEnabled, 1));
    }

    public List<Long> spacesWithPolicies() {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                        .select(RetentionPolicy::getSpaceId)
                        .eq(RetentionPolicy::getEnabled, 1))
                .stream().map(RetentionPolicy::getSpaceId).distinct().toList();
    }

    private static RetentionType parse(String value) {
        try {
            return RetentionType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("保留类型仅支持 SESSION/MESSAGE/AUDIT/EXPORT");
        }
    }
}
```

`LegalHoldService`：

```java
@Service
@RequiredArgsConstructor
public class LegalHoldService {

    private static final Set<String> TYPES = Set.of("SESSION", "MESSAGE", "AUDIT", "EXPORT");

    private final LegalHoldMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public List<LegalHoldView> list(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<LegalHold>()
                        .eq(LegalHold::getSpaceId, spaceId)
                        .orderByDesc(LegalHold::getId))
                .stream().map(this::view).toList();
    }

    @Transactional
    public LegalHoldView create(Long spaceId, LegalHoldCreateRequest request, Long operatorId) {
        String type = request.resourceType() == null ? "" : request.resourceType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw BizException.badRequest("保留类型仅支持 SESSION/MESSAGE/AUDIT/EXPORT");
        }
        if (request.resourceId() == null) {
            throw BizException.badRequest("缺少资源 ID");
        }
        if (Texts.isBlank(request.reason())) {
            throw BizException.badRequest("请填写法律保留原因");
        }
        if (request.until() != null && !request.until().isAfter(LocalDateTime.now())) {
            throw BizException.badRequest("法律保留截止时间必须晚于当前时间");
        }
        LegalHold hold = new LegalHold();
        hold.setSpaceId(spaceId);
        hold.setResourceType(type);
        hold.setResourceId(request.resourceId());
        hold.setReason(Texts.trimTo(request.reason(), 500));
        hold.setUntil(request.until());
        hold.setReleasedAt(null);
        hold.setReleasedBy(null);
        mapper.insert(hold);
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.legal_hold_create",
                "legal_hold", String.valueOf(hold.getId()), AuditLogService.STATUS_SUCCESS,
                "resourceType=" + type + ", resourceId=" + request.resourceId()
                        + ", until=" + request.until(), null, null, null);
        return view(hold);
    }

    @Transactional
    public void release(Long spaceId, Long holdId, Long operatorId) {
        LegalHold hold = mapper.selectById(holdId);
        if (hold == null || !Objects.equals(hold.getSpaceId(), spaceId)) {
            throw BizException.notFound("法律保留不存在");
        }
        if (hold.getReleasedAt() != null) {
            throw BizException.conflict("法律保留已解除");
        }
        hold.setReleasedAt(LocalDateTime.now());
        hold.setReleasedBy(operatorId);
        OptimisticLocks.requireUpdated(mapper.updateById(hold), "法律保留");
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.legal_hold_release",
                "legal_hold", String.valueOf(holdId), AuditLogService.STATUS_SUCCESS,
                "resourceType=" + hold.getResourceType() + ", resourceId=" + hold.getResourceId(),
                null, null, null);
    }

    private LegalHoldView view(LegalHold hold) {
        return new LegalHoldView(hold.getId(), hold.getResourceType(), hold.getResourceId(),
                hold.getReason(), hold.getUntil(), hold.getReleasedAt(), hold.getCreatedAt());
    }

    public boolean isHeld(Long spaceId, String resourceType, Long resourceId) {
        return mapper.selectCount(new LambdaQueryWrapper<LegalHold>()
                .eq(LegalHold::getSpaceId, spaceId)
                .eq(LegalHold::getResourceType, resourceType)
                .eq(LegalHold::getResourceId, resourceId)
                .isNull(LegalHold::getReleasedAt)
                .and(w -> w.isNull(LegalHold::getUntil).or().gt(LegalHold::getUntil, LocalDateTime.now()))) > 0;
    }

    public boolean isSessionHeld(Long spaceId, Long sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM king_ai_legal_hold h WHERE h.space_id = ? "
                        + "AND h.released_at IS NULL AND (h.until IS NULL OR h.until > ?) "
                        + "AND ((h.resource_type = 'SESSION' AND h.resource_id = ?) "
                        + "OR (h.resource_type = 'MESSAGE' AND h.resource_id IN "
                        + "(SELECT id FROM king_ai_chat_message WHERE session_id = ?)))",
                Long.class, spaceId, LocalDateTime.now(), sessionId, sessionId);
        return count != null && count > 0;
    }

    public boolean isMessageHeld(Long spaceId, Long messageId, Long sessionId) {
        return isHeld(spaceId, "MESSAGE", messageId) || isHeld(spaceId, "SESSION", sessionId);
    }

    public Set<Long> activeResourceIds(Long spaceId, String resourceType) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT resource_id FROM king_ai_legal_hold WHERE space_id = ? "
                        + "AND resource_type = ? AND released_at IS NULL "
                        + "AND (until IS NULL OR until > ?)",
                Long.class, spaceId, resourceType, LocalDateTime.now()));
    }
}
```

DTO（`com.king.ai.compliance.dto`）：

```java
public record RetentionPolicyView(Long id, String resourceType, Integer retentionDays,
                                  boolean enabled, boolean archiveBeforeDelete) {
}

public record RetentionPolicyUpdateRequest(@NotBlank String resourceType,
                                           @NotNull Integer retentionDays,
                                           Boolean enabled, Boolean archiveBeforeDelete) {
}

public record LegalHoldView(Long id, String resourceType, Long resourceId, String reason,
                            LocalDateTime until, LocalDateTime releasedAt,
                            LocalDateTime createdAt) {
}

public record LegalHoldCreateRequest(@NotBlank String resourceType,
                                     @NotNull Long resourceId,
                                     @NotBlank @Size(max = 500) String reason,
                                     LocalDateTime until) {
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=RetentionPolicyEvaluatorTest,LegalHoldServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance king-ai-api/src/test/java/com/king/ai/compliance
git commit -m "feat(compliance): add retention policy and legal hold services"
```

---

### Task 3: 本地导出存储与文件写入

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportStorage.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/LocalExportStorage.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportSink.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportSinkFactory.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/LocalExportStorageTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ExportSinkTest.java`

**Interfaces:**
- Produces: `StoredFile ExportStorage.store(Long spaceId, String extension, IOConsumer<OutputStream> writer)`
- Produces: `InputStream ExportStorage.open(Long spaceId, String relativePath)`
- Produces: `void ExportStorage.delete(Long spaceId, String relativePath)`
- Produces: `record StoredFile(String relativePath, long size)`
- Produces: `ExportSink ExportSinkFactory.open(OutputStream out, ExportFormat format, List<String> headers, ObjectMapper mapper)`
- Produces: `ExportSink.row(Map<String, Object> values)`、`long count()`

- [ ] **Step 1: 写失败测试**

`LocalExportStorageTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.export.LocalExportStorage;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalExportStorageTest {

    @TempDir
    Path root;

    private LocalExportStorage storage() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getExport().setRootDir(root.toString());
        return new LocalExportStorage(properties);
    }

    @Test
    void storesAndReadsFileUnderSpaceDirectory() throws Exception {
        LocalExportStorage storage = storage();

        ExportStorage.StoredFile file = storage.store(7L, "jsonl",
                out -> out.write("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(file.relativePath()).startsWith("7/").endsWith(".jsonl");
        assertThat(file.size()).isEqualTo(8);
        try (InputStream input = storage.open(7L, file.relativePath())) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"a\":1}\n");
        }
        storage.delete(7L, file.relativePath());
        assertThatThrownBy(() -> storage.open(7L, file.relativePath()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsPathTraversalAndCrossSpaceAccess() throws Exception {
        LocalExportStorage storage = storage();
        ExportStorage.StoredFile file = storage.store(7L, "csv", out -> out.write("x".getBytes()));

        assertThatThrownBy(() -> storage.open(7L, "../8/" + file.relativePath()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(8L, file.relativePath()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`ExportSinkTest.java`：

```java
package com.king.ai.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.compliance.export.ExportFormat;
import com.king.ai.compliance.export.ExportSink;
import com.king.ai.compliance.export.ExportSinkFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportSinkTest {

    @Test
    void writesJsonLines() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.JSONL,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "你好"));
            sink.row(Map.of("id", 2, "text", "a\"b"));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text.lines().count()).isEqualTo(2);
        assertThat(text).contains("\"id\":1").contains("你好");
    }

    @Test
    void writesCsvHeaderAndEscapes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.CSV,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "a,b"));
            sink.row(Map.of("id", 2, "text", "say \"hi\""));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("id,text\n");
        assertThat(text).contains("1,\"a,b\"");
        assertThat(text).contains("2,\"say \"\"hi\"\"\"");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=LocalExportStorageTest,ExportSinkTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`ExportStorage.java`：

```java
package com.king.ai.compliance.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ExportStorage {

    record StoredFile(String relativePath, long size) {
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    StoredFile store(Long spaceId, String extension, IOConsumer<OutputStream> writer) throws IOException;

    InputStream open(Long spaceId, String relativePath) throws IOException;

    long size(Long spaceId, String relativePath) throws IOException;

    void delete(Long spaceId, String relativePath) throws IOException;
}
```

`LocalExportStorage.java`：

```java
package com.king.ai.compliance.export;

import com.king.ai.config.KingAiProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Component
public class LocalExportStorage implements ExportStorage {

    private final Path root;

    public LocalExportStorage(KingAiProperties properties) {
        this.root = Paths.get(properties.getCompliance().getExport().getRootDir())
                .toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(Long spaceId, String extension,
                            IOConsumer<OutputStream> writer) throws IOException {
        Path directory = root.resolve(String.valueOf(spaceId)).normalize();
        ensureUnderRoot(directory);
        Files.createDirectories(directory);
        String ext = sanitize(extension);
        String name = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = directory.resolve(name).normalize();
        Path temp = Files.createTempFile(directory, "export-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.accept(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
        return new StoredFile(root.relativize(target).toString().replace('\\', '/'),
                Files.size(target));
    }

    @Override
    public InputStream open(Long spaceId, String relativePath) throws IOException {
        return Files.newInputStream(resolve(spaceId, relativePath));
    }

    @Override
    public long size(Long spaceId, String relativePath) throws IOException {
        return Files.size(resolve(spaceId, relativePath));
    }

    @Override
    public void delete(Long spaceId, String relativePath) throws IOException {
        Files.deleteIfExists(resolve(spaceId, relativePath));
    }

    private Path resolve(Long spaceId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("导出文件路径为空");
        }
        Path candidate = root.resolve(relativePath).normalize();
        Path spaceRoot = root.resolve(String.valueOf(spaceId)).normalize();
        if (!candidate.startsWith(spaceRoot) || candidate.equals(spaceRoot)) {
            throw new IllegalArgumentException("非法导出文件路径");
        }
        return candidate;
    }

    private void ensureUnderRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法导出目录");
        }
    }

    private static String sanitize(String extension) {
        if (extension == null) {
            return "";
        }
        return extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
```

`ExportSink.java`：

```java
package com.king.ai.compliance.export;

import java.io.IOException;

public interface ExportSink extends AutoCloseable {

    void row(java.util.Map<String, Object> values) throws IOException;

    long count();

    @Override
    void close() throws IOException;
}
```

`ExportSinkFactory.java`：

```java
package com.king.ai.compliance.export;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ExportSinkFactory {

    private ExportSinkFactory() {
    }

    public static ExportSink open(OutputStream out, ExportFormat format,
                                  List<String> headers, ObjectMapper mapper) throws IOException {
        return format == ExportFormat.CSV
                ? new CsvSink(out, headers)
                : new JsonlSink(out, mapper);
    }

    private static final class JsonlSink implements ExportSink {
        private final BufferedWriter writer;
        private final ObjectMapper mapper;
        private long count;

        private JsonlSink(OutputStream out, ObjectMapper mapper) {
            this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            this.mapper = mapper;
        }

        @Override
        public void row(Map<String, Object> values) throws IOException {
            writer.write(mapper.writeValueAsString(values));
            writer.newLine();
            count++;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }
    }

    private static final class CsvSink implements ExportSink {
        private final BufferedWriter writer;
        private final List<String> headers;
        private long count;

        private CsvSink(OutputStream out, List<String> headers) throws IOException {
            this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            this.headers = List.copyOf(headers);
            writer.write(String.join(",", headers.stream().map(CsvSink::escape).toList()));
            writer.newLine();
        }

        @Override
        public void row(Map<String, Object> values) throws IOException {
            writer.write(String.join(",", headers.stream()
                    .map(header -> escape(stringify(values.get(header)))).toList()));
            writer.newLine();
            count++;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }

        private static String stringify(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static String escape(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")
                    || value.contains("\r")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }
}
```

注意：`CsvSink` 构造函数写 header 时不应再次 `escape` 表头后的值；上面 `.map(CsvSink::escape)` 作用在 `Stream<String>` 上合法。保持可编译。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=LocalExportStorageTest,ExportSinkTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/export king-ai-api/src/test/java/com/king/ai/compliance
git commit -m "feat(compliance): add export storage and sink"
```

---

### Task 4: 异步导出任务

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/export/ExportJobWorker.java`
- Create: DTO `ExportJobView`、`ExportCreateRequest`
- Modify: `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/compliance/retention/LegalHoldService.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ExportServiceTest.java`

**Interfaces:**
- Produces: `ExportJobView ExportService.create(Long spaceId, ExportCreateRequest request, Long operatorId)`
- Produces: `PageResult<ExportJobView> ExportService.page(Long spaceId, int page, int size)`
- Produces: `Download ExportService.openDownload(Long spaceId, Long jobId)`
- Produces: `void ExportService.execute(Long jobId)`
- Produces: `record Download(String fileName, long size, InputStream stream)`
- Produces: `@Scheduled` 轮询由 `ExportJobWorker.poll()` 驱动

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.compliance;

import com.king.ai.compliance.dto.ExportCreateRequest;
import com.king.ai.compliance.export.ExportService;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.compliance.retention.ExportJob;
import com.king.ai.compliance.retention.ExportJobMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportServiceTest {

    private final ExportJobMapper mapper = mock(ExportJobMapper.class);
    private final ExportStorage storage = mock(ExportStorage.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LegalHoldService holds = mock(LegalHoldService.class);
    private final ExportService service = new ExportService(mapper, storage, jdbc,
            new ObjectMapper(), holds, new com.king.ai.config.KingAiProperties(),
            mock(com.king.ai.audit.AuditLogService.class), new RedactionService(),
            mock(ComplianceSettingsService.class));

    @Test
    void rejectsInvalidExportType() {
        assertThatThrownBy(() -> service.create(1L,
                new ExportCreateRequest("UNKNOWN", "JSONL", null, null), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出类型");
    }

    @Test
    void rejectsInvertedDateRange() {
        assertThatThrownBy(() -> service.create(1L,
                new ExportCreateRequest("CONVERSATIONS", "CSV",
                        java.time.LocalDateTime.now(), java.time.LocalDateTime.now().minusDays(1)), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("时间范围");
    }

    @Test
    void downloadRejectsMissingOrForeignJob() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.openDownload(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出任务不存在");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=ExportServiceTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`ExportCreateRequest`：

```java
package com.king.ai.compliance.dto;

import java.time.LocalDateTime;

public record ExportCreateRequest(String type, String format, LocalDateTime from, LocalDateTime to) {
}
```

`ExportJobView`：

```java
package com.king.ai.compliance.dto;

import java.time.LocalDateTime;

public record ExportJobView(Long id, String exportType, String format, String status,
                            String fileName, long rowCount, String errorMessage, Long requestedBy,
                            LocalDateTime fromAt, LocalDateTime toAt, LocalDateTime expiresAt,
                            LocalDateTime createdAt, LocalDateTime completedAt) {
}
```

`ExportService` 关键行为：

```java
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportJobMapper mapper;
    private final ExportStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LegalHoldService legalHoldService;
    private final KingAiProperties properties;
    private final AuditLogService auditLogService;
    private final RedactionService redactionService;
    private final ComplianceSettingsService complianceSettingsService;

    public record Download(String fileName, long size, InputStream stream) {
    }

    @Transactional
    public ExportJobView create(Long spaceId, ExportCreateRequest request, Long operatorId) {
        ExportType type = parseType(request.type());
        ExportFormat format = parseFormat(request.format());
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw BizException.badRequest("导出时间范围不合法");
        }
        ExportJob job = new ExportJob();
        job.setSpaceId(spaceId);
        job.setExportType(type.name());
        job.setFormat(format.name());
        job.setStatus(ExportStatus.PENDING.name());
        job.setFromAt(request.from());
        job.setToAt(request.to());
        job.setRowCount(0L);
        job.setRequestedBy(operatorId);
        mapper.insert(job);
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.export_create",
                "export", String.valueOf(job.getId()), AuditLogService.STATUS_SUCCESS,
                "type=" + type.name() + ", format=" + format.name(), null, null, null);
        return view(job);
    }

    public PageResult<ExportJobView> page(Long spaceId, int page, int size) {
        Page<ExportJob> result = mapper.selectPage(new Page<>(Math.max(1, page),
                        Math.min(200, Math.max(1, size))),
                new LambdaQueryWrapper<ExportJob>()
                        .eq(ExportJob::getSpaceId, spaceId)
                        .orderByDesc(ExportJob::getId));
        return PageResult.of(result.getRecords().stream().map(this::view).toList(),
                result.getTotal(), page, size);
    }

    public void execute(Long jobId) {
        ExportJob job = mapper.selectById(jobId);
        if (job == null || !ExportStatus.RUNNING.name().equals(job.getStatus())) {
            return;
        }
        job.setStartedAt(LocalDateTime.now());
        long started = System.nanoTime();
        try {
            long[] written = new long[1];
            ExportStorage.StoredFile file = storage.store(job.getSpaceId(),
                    job.getFormat().toLowerCase(Locale.ROOT),
                    out -> written[0] = writeRows(job, out));
            job.setFileName("king-ai-" + job.getExportType().toLowerCase(Locale.ROOT)
                    + "-" + job.getId() + "." + job.getFormat().toLowerCase(Locale.ROOT));
            job.setFilePath(file.relativePath());
            job.setRowCount(written[0]);
            job.setStatus(ExportStatus.COMPLETED.name());
            job.setCompletedAt(LocalDateTime.now());
            job.setExpiresAt(LocalDateTime.now().plusHours(
                    Math.max(1, properties.getCompliance().getExport().getTtlHours())));
            job.setErrorMessage(null);
        } catch (Exception ex) {
            job.setStatus(ExportStatus.FAILED.name());
            job.setErrorMessage(Texts.oneLine(Texts.blankToDefault(ex.getMessage(),
                    ex.getClass().getSimpleName()), 900));
            log.warn("Export job {} failed: {}", jobId, ex.getMessage());
        }
        OptimisticLocks.requireUpdated(mapper.updateById(job), "导出任务");
        auditLogService.recordEvent(job.getSpaceId(), 0L, "SYSTEM", "compliance.export",
                "export", String.valueOf(jobId),
                ExportStatus.COMPLETED.name().equals(job.getStatus())
                        ? AuditLogService.STATUS_SUCCESS : AuditLogService.STATUS_FAILURE,
                "type=" + job.getExportType() + ", format=" + job.getFormat()
                        + ", rows=" + job.getRowCount(), null, null, null);
    }
}
```

`ExportService` 与 `ExportJobWorker` 位于 `com.king.ai.compliance.export`，必须显式 import `com.king.ai.compliance.retention.ExportJob`、`ExportJobMapper` 与 `LegalHoldService`；`ExportJob` 实体与 Mapper 只放在 `retention` 包，禁止再创建同名 `export` 包实体。

`writeRows` 用 `ExportSinkFactory`，按类型查询：

```java
    private static final List<String> CONVERSATION_HEADERS = List.of(
            "session_id", "message_id", "role", "content", "status", "model", "created_at");
    private static final List<String> AUDIT_HEADERS = List.of(
            "id", "action", "actor_name", "resource_type", "resource_id", "status", "detail", "created_at");
    private static final List<String> MEMBER_HEADERS = List.of(
            "user_id", "username", "nickname", "role", "last_login_at");

    private long writeRows(ExportJob job, OutputStream out) throws IOException {
        ComplianceSettings settings = complianceSettingsService.effective(job.getSpaceId());
        long limit = Math.max(1, properties.getCompliance().getExport().getMaxRows());
        try (ExportSink sink = ExportSinkFactory.open(out,
                ExportFormat.valueOf(job.getFormat()), headers(job.getExportType()), objectMapper)) {
            switch (ExportType.valueOf(job.getExportType())) {
                case CONVERSATIONS -> writeConversations(job, sink, redactionService, settings, limit);
                case AUDIT -> writeAudit(job, sink, limit);
                case MEMBERS -> writeMembers(job, sink, limit);
            }
            return sink.count();
        }
    }
```

`writeConversations` 先按会话分页，再按消息分页：

```java
    private void writeConversations(ExportJob job, ExportSink sink, RedactionService redaction,
                                    ComplianceSettings settings, long limit) throws IOException {
        List<Long> sessionIds = jdbcTemplate.queryForList(
                "SELECT id FROM king_ai_chat_session WHERE space_id = ? "
                        + "AND COALESCE(last_message_at, created_at) >= ? "
                        + "AND COALESCE(last_message_at, created_at) <= ? "
                        + "ORDER BY id LIMIT ?",
                Long.class, job.getSpaceId(), fromOrEpoch(job.getFromAt()), toOrMax(job.getToAt()),
                limit);
        for (Long sessionId : sessionIds) {
            jdbcTemplate.query("SELECT id, session_id, role, content, status, model, created_at "
                            + "FROM king_ai_chat_message WHERE session_id = ? ORDER BY id",
                    rs -> {
                        if (sink.count() >= limit) {
                            return;
                        }
                        try {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("session_id", sessionId);
                            row.put("message_id", rs.getLong("id"));
                            row.put("role", rs.getString("role"));
                            row.put("content", redaction.redact(rs.getString("content"), settings).text());
                            row.put("status", rs.getString("status"));
                            row.put("model", rs.getString("model"));
                            row.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                            sink.row(row);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }, sessionId);
        }
    }
```

`writeAudit`/`writeMembers` 与 `writeConversations` 同构，按 `ExportSink.count() >= limit` 提前停止，并把 `IOException` 包成 `UncheckedIOException`：

```java
    private void writeAudit(ExportJob job, ExportSink sink, long limit) throws IOException {
        jdbcTemplate.query(
                "SELECT id, action, actor_name, resource_type, resource_id, status, detail, created_at "
                        + "FROM king_ai_audit_log WHERE space_id = ? "
                        + "AND created_at >= ? AND created_at <= ? "
                        + "ORDER BY id LIMIT ?",
                rs -> {
                    if (sink.count() >= limit) {
                        return;
                    }
                    try {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("action", rs.getString("action"));
                        row.put("actor_name", rs.getString("actor_name"));
                        row.put("resource_type", rs.getString("resource_type"));
                        row.put("resource_id", rs.getString("resource_id"));
                        row.put("status", rs.getString("status"));
                        row.put("detail", rs.getString("detail"));
                        row.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                        sink.row(row);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                },
                job.getSpaceId(), fromOrEpoch(job.getFromAt()), toOrMax(job.getToAt()), limit);
    }

    private static LocalDateTime fromOrEpoch(LocalDateTime value) {
        return value == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : value;
    }

    private static LocalDateTime toOrMax(LocalDateTime value) {
        return value == null ? LocalDateTime.of(9999, 12, 31, 23, 59, 59) : value;
    }

    private void writeMembers(ExportJob job, ExportSink sink, long limit) throws IOException {
        jdbcTemplate.query(
                "SELECT us.user_id, u.username, u.nickname, us.role, u.last_login_at "
                        + "FROM king_ai_user_space us JOIN king_ai_user u ON u.id = us.user_id "
                        + "WHERE us.space_id = ? ORDER BY us.user_id LIMIT ?",
                rs -> {
                    if (sink.count() >= limit) {
                        return;
                    }
                    try {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("user_id", rs.getLong("user_id"));
                        row.put("username", rs.getString("username"));
                        row.put("nickname", rs.getString("nickname"));
                        row.put("role", rs.getString("role"));
                        row.put("last_login_at", String.valueOf(rs.getTimestamp("last_login_at")));
                        sink.row(row);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }, job.getSpaceId(), limit);
    }

    private static List<String> headers(String exportType) {
        return switch (ExportType.valueOf(exportType)) {
            case CONVERSATIONS -> CONVERSATION_HEADERS;
            case AUDIT -> AUDIT_HEADERS;
            case MEMBERS -> MEMBER_HEADERS;
        };
    }

    private static ExportType parseType(String value) {
        try {
            return ExportType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("导出类型仅支持 CONVERSATIONS/AUDIT/MEMBERS");
        }
    }

    private static ExportFormat parseFormat(String value) {
        try {
            return ExportFormat.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("导出格式仅支持 JSONL/CSV");
        }
    }

    private ExportJobView view(ExportJob job) {
        return new ExportJobView(job.getId(), job.getExportType(), job.getFormat(), job.getStatus(),
                job.getFileName(), job.getRowCount(), job.getErrorMessage(), job.getRequestedBy(),
                job.getFromAt(), job.getToAt(), job.getExpiresAt(), job.getCreatedAt(),
                job.getCompletedAt());
    }
```

`openDownload`：

```java
    public Download openDownload(Long spaceId, Long jobId) {
        ExportJob job = mapper.selectById(jobId);
        if (job == null || !Objects.equals(job.getSpaceId(), spaceId)) {
            throw BizException.notFound("导出任务不存在");
        }
        if (!ExportStatus.COMPLETED.name().equals(job.getStatus())) {
            throw BizException.conflict("导出任务尚未完成");
        }
        if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw BizException.conflict("导出文件已过期，请重新生成");
        }
        try {
            return new Download(job.getFileName(), storage.size(spaceId, job.getFilePath()),
                    storage.open(spaceId, job.getFilePath()));
        } catch (IOException ex) {
            throw BizException.notFound("导出文件不存在");
        }
    }

    @Transactional
    public void deleteFile(ExportJob job) {
        try {
            if (job.getFilePath() != null) {
                storage.delete(job.getSpaceId(), job.getFilePath());
            }
        } catch (IOException ex) {
            throw BizException.conflict("导出文件删除失败");
        }
        mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getId, job.getId())
                .set(ExportJob::getFilePath, null)
                .set(ExportJob::getStatus, ExportStatus.EXPIRED.name())
                .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
        auditLogService.recordEvent(job.getSpaceId(), 0L, "SYSTEM", "compliance.export.expire",
                "export", String.valueOf(job.getId()), AuditLogService.STATUS_SUCCESS,
                "type=" + job.getExportType(), null, null, null);
    }
```

`ExportStorage` 已在 Task 3 提供 `long size(Long spaceId, String relativePath)`。

`ExportJobWorker`：

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportJobWorker {

    private final ExportJobMapper mapper;
    private final ExportService exportService;
    private final LegalHoldService legalHoldService;

    @Scheduled(fixedDelayString = "${king-ai.compliance.export.poll-interval-ms:10000}")
    public void poll() {
        List<ExportJob> jobs = mapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                .orderByAsc(ExportJob::getId)
                .last("LIMIT 3"));
        for (ExportJob job : jobs) {
            int claimed = mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, job.getId())
                    .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                    .set(ExportJob::getStatus, ExportStatus.RUNNING.name())
                    .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
            if (claimed == 1) {
                exportService.execute(job.getId());
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStale() {
        mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.RUNNING.name())
                .lt(ExportJob::getUpdatedAt, LocalDateTime.now().minusMinutes(30))
                .set(ExportJob::getStatus, ExportStatus.PENDING.name())
                .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void expire() {
        List<ExportJob> expired = mapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.COMPLETED.name())
                .lt(ExportJob::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 100"));
        for (ExportJob job : expired) {
            if (legalHoldService.isHeld(job.getSpaceId(), "EXPORT", job.getId())) {
                continue;
            }
            try {
                exportService.deleteFile(job);
            } catch (RuntimeException ex) {
                log.warn("Failed to delete expired export {}: {}", job.getId(), ex.getMessage());
            }
        }
    }
}
```

`exportService.deleteFile(job)` 删除文件并把状态置 `EXPIRED`、清空 `file_path`。`ExportService` 的 `writeRows` 使用 G0-1 的 `RedactionService` 与 `ComplianceSettingsService`；执行顺序要求 G0-1 先完成（见执行索引），本计划不提供透传降级实现。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=ExportServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/export king-ai-api/src/test/java/com/king/ai/compliance/ExportServiceTest.java
git commit -m "feat(compliance): add asynchronous export jobs"
```

---

### Task 5: 保留清理任务与审计保留接入

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionJob.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/RetentionJobTest.java`

**Interfaces:**
- Produces: `int AuditLogService.purgeBefore(Long spaceId, LocalDateTime cutoff, Set<Long> excludedIds)`
- Produces: `RetentionJob.run()`（`@Scheduled`）
- Produces: `RetentionJob.purge(Long spaceId, RetentionPolicy policy, boolean dryRun, Long operatorId)`

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.compliance;

import com.king.ai.compliance.retention.RetentionJob;
import com.king.ai.compliance.retention.RetentionPolicy;
import com.king.ai.compliance.retention.RetentionPolicyService;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.audit.AuditLogService;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionJobTest {

    @Test
    void dryRunDoesNotDelete() {
        RetentionPolicyService policies = mock(RetentionPolicyService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalHoldService holds = mock(LegalHoldService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(policies.spacesWithPolicies()).thenReturn(List.of(1L));
        when(policies.enabledPolicies(1L)).thenReturn(List.of(policy("SESSION", true)));
        when(jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L, "activity", LocalDateTime.now())));
        when(holds.isSessionHeld(1L, 9L)).thenReturn(false);
        RetentionJob job = new RetentionJob(policies, jdbc, holds, audit,
                new KingAiProperties(), mock(com.king.ai.compliance.export.ExportStorage.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), new SimpleMeterRegistry(),
                new RedactionService(), mock(ComplianceSettingsService.class));

        job.purge(1L, policy("SESSION", true), true, 0L);

        verify(jdbc, never()).update(any(String.class), any(Object[].class));
        verify(audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), any(), any(), any(), any());
    }

    private RetentionPolicy policy(String type, boolean archive) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setSpaceId(1L);
        policy.setResourceType(type);
        policy.setRetentionDays(30);
        policy.setEnabled(1);
        policy.setArchiveBeforeDelete(archive ? 1 : 0);
        return policy;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=RetentionJobTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`AuditLogService`：

```java
    @Scheduled(cron = "${king-ai.audit.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (!properties.getAudit().isEnabled()
                || properties.getCompliance().getRetention().isEnabled()
                || properties.getAudit().getRetentionDays() <= 0) {
            return;
        }
        purgeBefore(null, LocalDateTime.now()
                .minusDays(properties.getAudit().getRetentionDays()), Set.of());
    }

    public int purgeBefore(Long spaceId, LocalDateTime cutoff, Set<Long> excludedIds) {
        if (!properties.getAudit().isEnabled() || cutoff == null) {
            return 0;
        }
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(spaceId != null, AuditLog::getSpaceId, spaceId)
                .lt(AuditLog::getCreatedAt, cutoff);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            wrapper.notIn(AuditLog::getId, excludedIds);
        }
        try {
            return auditLogMapper.delete(wrapper);
        } catch (RuntimeException ex) {
            log.warn("Audit retention purge failed: {}", ex.getMessage());
            return 0;
        }
    }
```

`AuditLogService` 需新增 `import java.util.Set;`。`purgeExpired` 保留原调度表达式，`purgeBefore` 供 `RetentionJob` 调用。

`RetentionJob` 按类型分派。类与全部实现如下（`archiveSessions`/`deleteSessions`/`purgeMessages`/`purgeAudit`/`purgeExports` 都是完整方法，不留省略号）：

```java
package com.king.ai.compliance.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.compliance.RedactionService;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionJob {

    private final RetentionPolicyService policyService;
    private final JdbcTemplate jdbcTemplate;
    private final LegalHoldService legalHoldService;
    private final AuditLogService auditLogService;
    private final KingAiProperties properties;
    private final ExportStorage storage;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RedactionService redactionService;
    private final ComplianceSettingsService complianceSettingsService;

    @Scheduled(cron = "${king-ai.compliance.retention.cron:0 0 3 * * *}")
    public void run() {
        if (!properties.getCompliance().getRetention().isEnabled()) {
            return;
        }
        boolean dryRun = properties.getCompliance().getRetention().isDryRun();
        for (Long spaceId : policyService.spacesWithPolicies()) {
            for (RetentionPolicy policy : policyService.enabledPolicies(spaceId)) {
                try {
                    purge(spaceId, policy, dryRun, 0L);
                } catch (RuntimeException ex) {
                    log.warn("Retention purge failed: spaceId={}, type={}, reason={}",
                            spaceId, policy.getResourceType(), ex.getMessage());
                }
            }
        }
    }

    public void purge(Long spaceId, RetentionPolicy policy, boolean dryRun, Long operatorId) {
        RetentionType type = RetentionType.valueOf(policy.getResourceType());
        int days = policy.getRetentionDays() == null ? 1 : policy.getRetentionDays();
        LocalDateTime cutoff = RetentionPolicyEvaluator.cutoff(LocalDateTime.now(), days);
        int deleted = switch (type) {
            case SESSION -> purgeSessions(spaceId, cutoff, policy, dryRun);
            case MESSAGE -> purgeMessages(spaceId, cutoff, dryRun);
            case AUDIT -> purgeAudit(spaceId, cutoff, dryRun);
            case EXPORT -> purgeExports(spaceId, dryRun);
        };
        auditLogService.recordEvent(spaceId, operatorId == null ? 0L : operatorId, "SYSTEM",
                "compliance.retention", "retention", type.name(),
                AuditLogService.STATUS_SUCCESS,
                "deleted=" + deleted + ", dryRun=" + dryRun + ", cutoff=" + cutoff,
                null, null, null);
        meterRegistry.counter("king.ai.compliance.retention.deleted",
                "type", type.name()).increment(deleted);
    }
}
```

在 `RetentionJob` 类中加入 `purgeSessions`。它使用 `id` 游标，避免队首大量 held 会话把后续可删会话永久堵住：

```java
    private int purgeSessions(Long spaceId, LocalDateTime cutoff, RetentionPolicy policy,
                              boolean dryRun) {
        boolean archive = policy.getArchiveBeforeDelete() != null
                && policy.getArchiveBeforeDelete() == 1;
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM king_ai_chat_session WHERE space_id = ? "
                            + "AND COALESCE(last_message_at, created_at) < ? AND id > ? "
                            + "ORDER BY id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
                break;
            }
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (!legalHoldService.isSessionHeld(spaceId, id)) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                if (!dryRun && archive && !archiveSessions(spaceId, ids)) {
                    continue;
                }
                if (!dryRun) {
                    deleteSessions(ids);
                }
                total += ids.size();
            }
            if (rows.size() < batch) {
                break;
            }
        }
        return total;
    }
```

归档产物同样脱敏；归档失败返回 `false`，本轮不删除该批：

```java
    private boolean archiveSessions(Long spaceId, List<Long> sessionIds) {
        ComplianceSettings settings = complianceSettingsService.effective(spaceId);
        try {
            storage.store(spaceId, "jsonl.gz", out -> writeArchive(out, sessionIds, settings));
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Retention archive failed: spaceId={}, sessions={}, reason={}",
                    spaceId, sessionIds.size(), ex.getMessage());
            return false;
        }
    }

    private void writeArchive(OutputStream out, List<Long> sessionIds,
                              ComplianceSettings settings) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            for (Long sessionId : sessionIds) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sessionId", sessionId);
                payload.put("messages", archiveMessages(sessionId, settings));
                gzip.write(objectMapper.writeValueAsBytes(payload));
                gzip.write('\n');
            }
        }
    }

    private List<Map<String, Object>> archiveMessages(Long sessionId, ComplianceSettings settings) {
        return jdbcTemplate.query(
                "SELECT id, role, content, status, model, created_at FROM king_ai_chat_message "
                        + "WHERE session_id = ? ORDER BY id",
                (rs, rowNum) -> archiveMessage(rs, settings), sessionId);
    }

    private Map<String, Object> archiveMessage(ResultSet rs, ComplianceSettings settings)
            throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("role", rs.getString("role"));
        row.put("content", redactionService.redact(rs.getString("content"), settings).text());
        row.put("status", rs.getString("status"));
        row.put("model", rs.getString("model"));
        row.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
        return row;
    }
```

`king_ai_chat_message` 没有 `space_id` 与级联外键，删除会话前必须显式删消息：

```java
    private void deleteSessions(List<Long> sessionIds) {
        String in = placeholders(sessionIds.size());
        Object[] args = sessionIds.toArray();
        jdbcTemplate.update("DELETE FROM king_ai_chat_message WHERE session_id IN (" + in + ")", args);
        jdbcTemplate.update("DELETE FROM king_ai_chat_session WHERE id IN (" + in + ")", args);
    }

    private int purgeMessages(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT m.id, m.session_id FROM king_ai_chat_message m "
                            + "JOIN king_ai_chat_session s ON s.id = m.session_id "
                            + "WHERE s.space_id = ? AND m.created_at < ? AND m.id > ? "
                            + "ORDER BY m.id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
                break;
            }
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                Long sessionId = toLong(row.get("session_id"));
                if (id == null || sessionId == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (!legalHoldService.isMessageHeld(spaceId, id, sessionId)) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                if (!dryRun) {
                    jdbcTemplate.update("DELETE FROM king_ai_chat_message WHERE id IN ("
                            + placeholders(ids.size()) + ")", ids.toArray());
                }
                total += ids.size();
            }
            if (rows.size() < batch) {
                break;
            }
        }
        return total;
    }

    private int purgeAudit(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        Set<Long> held = legalHoldService.activeResourceIds(spaceId, "AUDIT");
        if (!dryRun) {
            return auditLogService.purgeBefore(spaceId, cutoff, held);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM king_ai_audit_log WHERE space_id = ? AND created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(spaceId);
        args.add(cutoff);
        if (!held.isEmpty()) {
            sql.append(" AND id NOT IN (").append(placeholders(held.size())).append(')');
            args.addAll(held);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count.intValue();
    }

    private int purgeExports(Long spaceId, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        LocalDateTime now = LocalDateTime.now();
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, file_path FROM king_ai_export_job WHERE space_id = ? "
                            + "AND status = 'COMPLETED' AND expires_at IS NOT NULL "
                            + "AND expires_at < ? AND id > ? ORDER BY id LIMIT ?",
                    spaceId, now, lastId, batch);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (legalHoldService.isHeld(spaceId, "EXPORT", id)) {
                    continue;
                }
                if (dryRun) {
                    total++;
                    continue;
                }
                String path = row.get("file_path") == null ? null
                        : String.valueOf(row.get("file_path"));
                try {
                    if (path != null && !path.isBlank()) {
                        storage.delete(spaceId, path);
                    }
                } catch (IOException | IllegalArgumentException ex) {
                    log.warn("Expired export file delete failed: spaceId={}, jobId={}, reason={}",
                            spaceId, id, ex.getMessage());
                    continue;
                }
                jdbcTemplate.update("UPDATE king_ai_export_job SET status = 'EXPIRED', "
                                + "file_path = NULL, updated_at = ? "
                                + "WHERE id = ? AND status = 'COMPLETED'",
                        LocalDateTime.now(), id);
                total++;
            }
            if (rows.size() < batch) {
                break;
            }
        }
        return total;
    }

    private int batchSize() {
        return Math.max(1, properties.getCompliance().getRetention().getBatchSize());
    }

    private int maxBatches() {
        return Math.max(1, properties.getCompliance().getRetention().getMaxBatches());
    }

    private static String placeholders(int count) {
        return count <= 0 ? "" : "?" + ",?".repeat(count - 1);
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
```

`raw` 文件路径只来自数据库 `file_path`，且 `LocalExportStorage.resolve` 会再次校验空间前缀与路径穿越；`placeholders` 只生成 `?`，不拼接用户输入。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=RetentionJobTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/retention/RetentionJob.java king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java king-ai-api/src/test/java/com/king/ai/compliance/RetentionJobTest.java
git commit -m "feat(compliance): add retention purge job with legal hold protection"
```

---

### Task 6: V11 访问复核、登录时间与密钥轮换时间

**Files:**
- Create: `king-ai-api/src/main/resources/db/migration/{h2,mysql}/V11__access_review.sql`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/access/{AccessReview,AccessReviewItem,AccessReviewMapper,AccessReviewItemMapper}.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/system/entity/User.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/auth/AuthService.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/model/entity/Supplier.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/model/SupplierService.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/AccessReviewMigrationTest.java`

**Interfaces:**
- Produces: `king_ai_user.last_login_at`、`king_ai_supplier.api_key_rotated_at`
- Produces: `AccessReview`、`AccessReviewItem` 实体与 Mapper

- [ ] **Step 1: 写失败测试**

```java
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
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=AccessReviewMigrationTest test`

Expected: FAIL。

- [ ] **Step 3: 迁移与实体**

H2：

```sql
ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS last_login_at DATETIME NULL;
ALTER TABLE king_ai_supplier ADD COLUMN IF NOT EXISTS api_key_rotated_at DATETIME NULL;

CREATE TABLE IF NOT EXISTS king_ai_access_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    space_id     BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    summary      VARCHAR(1000) NULL,
    generated_by BIGINT      NULL,
    generated_at DATETIME    NOT NULL,
    completed_at DATETIME    NULL,
    version      INT         NOT NULL DEFAULT 0,
    created_by   BIGINT      NULL,
    created_at   DATETIME    NOT NULL,
    updated_by   BIGINT      NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_access_review_space
    ON king_ai_access_review (space_id, status);
ALTER TABLE king_ai_access_review ADD CONSTRAINT fk_access_review_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_access_review_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    review_id        BIGINT       NOT NULL,
    space_id         BIGINT       NOT NULL,
    subject_type     VARCHAR(20)  NOT NULL,
    subject_id       BIGINT       NOT NULL,
    subject_name     VARCHAR(200) NOT NULL,
    role             VARCHAR(20)  NULL,
    last_activity_at DATETIME     NULL,
    flags            VARCHAR(500) NULL,
    decision         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by       BIGINT       NULL,
    decided_at       DATETIME     NULL,
    note             VARCHAR(500) NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_access_item_review
    ON king_ai_access_review_item (review_id, decision);
ALTER TABLE king_ai_access_review_item ADD CONSTRAINT fk_access_item_review
    FOREIGN KEY (review_id) REFERENCES king_ai_access_review (id) ON DELETE CASCADE;
```

MySQL：

```sql
ALTER TABLE king_ai_user
    ADD COLUMN last_login_at DATETIME NULL COMMENT '最近登录时间';
ALTER TABLE king_ai_supplier
    ADD COLUMN api_key_rotated_at DATETIME NULL COMMENT 'API Key 最近轮换时间';

CREATE TABLE king_ai_access_review (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    space_id     BIGINT       NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    summary      VARCHAR(1000) NULL,
    generated_by BIGINT       NULL,
    generated_at DATETIME     NOT NULL,
    completed_at DATETIME     NULL,
    version      INT          NOT NULL DEFAULT 0,
    created_by   BIGINT       NULL,
    created_at   DATETIME     NOT NULL,
    updated_by   BIGINT       NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_review_space (space_id, status),
    CONSTRAINT fk_access_review_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE king_ai_access_review_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    review_id        BIGINT       NOT NULL,
    space_id         BIGINT       NOT NULL,
    subject_type     VARCHAR(20)  NOT NULL,
    subject_id       BIGINT       NOT NULL,
    subject_name     VARCHAR(200) NOT NULL,
    role             VARCHAR(20)  NULL,
    last_activity_at DATETIME     NULL,
    flags            VARCHAR(500) NULL,
    decision         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by       BIGINT       NULL,
    decided_at       DATETIME     NULL,
    note             VARCHAR(500) NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_item_review (review_id, decision),
    CONSTRAINT fk_access_item_review FOREIGN KEY (review_id)
        REFERENCES king_ai_access_review (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

实体（均继承 `BaseEntity`，以下两个类都是完整定义）：

```java
package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_access_review")
public class AccessReview extends BaseEntity {

    private Long spaceId;
    private String status;
    private String summary;
    private Long generatedBy;
    private LocalDateTime generatedAt;
    private LocalDateTime completedAt;

    @Version
    private Integer version;
}
```

```java
package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_access_review_item")
public class AccessReviewItem extends BaseEntity {

    private Long reviewId;
    private Long spaceId;
    private String subjectType;
    private Long subjectId;
    private String subjectName;
    private String role;
    private LocalDateTime lastActivityAt;
    private String flags;
    private String decision;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String note;

    @Version
    private Integer version;
}
```

Mapper（两个独立文件）：

```java
package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccessReviewMapper extends BaseMapper<AccessReview> {
}
```

```java
package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccessReviewItemMapper extends BaseMapper<AccessReviewItem> {
}
```

`User` 增加 `private LocalDateTime lastLoginAt;`（import `java.time.LocalDateTime`）。
`Supplier` 增加 `private LocalDateTime apiKeyRotatedAt;`。

`AuthService.login` 在 `attemptGuard.success(...)` 后：

```java
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLoginAt, java.time.LocalDateTime.now()));
```

`SupplierService.apply` 的密钥分支改为：

```java
        if (creating) {
            String key = Texts.blankToDefault(request.apiKey(), "").trim();
            supplier.setApiKey(key.isEmpty() ? "" : cryptoService.encrypt(key));
            supplier.setApiKeyRotatedAt(key.isEmpty() ? null : java.time.LocalDateTime.now());
        } else if (request.apiKey() != null) {
            String key = request.apiKey().trim();
            supplier.setApiKey(key.isEmpty() ? "" : cryptoService.encrypt(key));
            supplier.setApiKeyRotatedAt(key.isEmpty() ? null : java.time.LocalDateTime.now());
        }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=AccessReviewMigrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/resources/db/migration king-ai-api/src/main/java/com/king/ai/system/entity/User.java king-ai-api/src/main/java/com/king/ai/auth/AuthService.java king-ai-api/src/main/java/com/king/ai/model king-ai-api/src/test/java/com/king/ai/compliance/AccessReviewMigrationTest.java
git commit -m "feat(compliance): track login and key rotation for access review"
```

---

### Task 7: 访问复核报告、决策与 CSV

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewReportBuilder.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewDecisions.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/access/CsvWriter.java`
- Create: DTO `AccessReviewView`、`AccessReviewItemView`、`AccessReviewDecisionRequest`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/AccessReviewReportBuilderTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/AccessReviewDecisionsTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/CsvWriterTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/AccessReviewServiceTest.java`

**Interfaces:**
- Produces: `List<AccessFinding> AccessReviewReportBuilder.build(List<MemberSnapshot>, List<CredentialSnapshot>, List<CredentialSnapshot>, List<PublicAgentSnapshot>, LocalDateTime now, int staleDays)`
- Produces: `SpaceRole AccessReviewDecisions.downgrade(SpaceRole current)`
- Produces: `void AccessReviewDecisions.validate(String subjectType, String decision)`
- Produces: `AccessReviewView AccessReviewService.generate(Long spaceId, Long operatorId)`
- Produces: `List<AccessReviewView> AccessReviewService.list(Long spaceId)`
- Produces: `AccessReviewView AccessReviewService.get(Long spaceId, Long reviewId)`
- Produces: `AccessReviewItemView AccessReviewService.decide(Long spaceId, Long reviewId, Long itemId, AccessReviewDecisionRequest request, UserPrincipal operator)`
- Produces: `String AccessReviewService.csv(Long spaceId, Long reviewId, UserPrincipal operator)`

- [ ] **Step 1: 写失败测试**

`AccessReviewReportBuilderTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.access.AccessReviewReportBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessReviewReportBuilderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void flagsNeverLoggedInAndUnusedMembers() {
        var findings = AccessReviewReportBuilder.build(
                List.of(new AccessReviewReportBuilder.MemberSnapshot(1L, "alice", "Alice",
                                "ADMIN", null),
                        new AccessReviewReportBuilder.MemberSnapshot(2L, "bob", "Bob",
                                "VIEWER", NOW.minusDays(120)),
                        new AccessReviewReportBuilder.MemberSnapshot(3L, "carol", "Carol",
                                "OWNER", NOW.minusDays(2))),
                List.of(), List.of(), List.of(), NOW, 90);

        assertThat(findings).filteredOn(item -> item.subjectId().equals(1L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("NEVER_LOGGED_IN"));
        assertThat(findings).filteredOn(item -> item.subjectId().equals(2L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("UNUSED_90D"));
        assertThat(findings).filteredOn(item -> item.subjectId().equals(3L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("OWNER").doesNotContain("UNUSED_90D"));
    }

    @Test
    void flagsStaleCredentialsAndPublicAgents() {
        var findings = AccessReviewReportBuilder.build(
                List.of(),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(5L, "openai",
                        NOW.minusDays(100))),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(6L, "mcp-a", null)),
                List.of(new AccessReviewReportBuilder.PublicAgentSnapshot(7L, "客服", "pub_1",
                        NOW.minusDays(1))),
                NOW, 90);

        assertThat(findings).extracting(item -> item.subjectType())
                .containsExactly("SUPPLIER", "MCP", "PUBLIC_AGENT");
        assertThat(findings.get(0).flags()).contains("STALE_CREDENTIAL");
        assertThat(findings.get(1).flags()).contains("STALE_CREDENTIAL");
        assertThat(findings.get(2).flags()).contains("PUBLIC_EXPOSURE");
    }
}
```

`AccessReviewDecisionsTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.common.BizException;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.compliance.access.AccessReviewDecisions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessReviewDecisionsTest {

    @Test
    void downgradeFollowsRoleLadder() {
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.OWNER)).isEqualTo(SpaceRole.ADMIN);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.ADMIN)).isEqualTo(SpaceRole.EDITOR);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.EDITOR)).isEqualTo(SpaceRole.VIEWER);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.AUDITOR)).isEqualTo(SpaceRole.VIEWER);
    }

    @Test
    void viewerCannotBeDowngraded() {
        assertThatThrownBy(() -> AccessReviewDecisions.downgrade(SpaceRole.VIEWER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最低角色");
    }

    @Test
    void nonMemberCanOnlyBeAcknowledged() {
        assertThatThrownBy(() -> AccessReviewDecisions.validate("SUPPLIER", "REMOVE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("供应商");
        AccessReviewDecisions.validate("SUPPLIER", "ACKNOWLEDGE");
    }
}
```

`CsvWriterTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.access.CsvWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void escapesRfc4180() {
        String csv = CsvWriter.write(List.of("a", "b"),
                List.of(List.of("1", "x,y"), List.of("2", "say \"hi\"")));

        assertThat(csv).isEqualTo("a,b\n1,\"x,y\"\n2,\"say \"\"hi\"\"\"\n");
    }

    @Test
    void neutralizesFormulaInjection() {
        String csv = CsvWriter.write(List.of("name"),
                List.of(List.of("=HYPERLINK(\"http://evil\")"), List.of("@SUM(A1)")));

        assertThat(csv).isEqualTo("name\n\"'=HYPERLINK(\"\"http://evil\"\")\"\n'@SUM(A1)\n");
    }
}
```

`AccessReviewServiceTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.compliance.access.AccessReviewItemMapper;
import com.king.ai.compliance.access.AccessReviewMapper;
import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.system.SpaceService;
import com.king.ai.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;

class AccessReviewServiceTest {

    @Test
    void constructsWithExpectedCollaborators() {
        AccessReviewService service = new AccessReviewService(
                mock(AccessReviewMapper.class), mock(AccessReviewItemMapper.class),
                mock(JdbcTemplate.class), mock(SpaceService.class),
                mock(AuditLogService.class), new com.king.ai.config.KingAiProperties());

        org.assertj.core.api.Assertions.assertThat(service).isNotNull();
    }
}
```

DTO（`com.king.ai.compliance.dto`）：

```java
public record AccessReviewView(Long id, Long spaceId, String status, String summary,
                               Long generatedBy, LocalDateTime generatedAt,
                               LocalDateTime completedAt, List<AccessReviewItemView> items) {
}

public record AccessReviewItemView(Long id, String subjectType, Long subjectId,
                                   String subjectName, String role,
                                   LocalDateTime lastActivityAt, List<String> flags,
                                   String decision, Long decidedBy, LocalDateTime decidedAt,
                                   String note) {
}

public record AccessReviewDecisionRequest(@NotBlank String decision,
                                          @Size(max = 500) String note) {
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=AccessReviewReportBuilderTest,AccessReviewDecisionsTest,CsvWriterTest,AccessReviewServiceTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`AccessReviewReportBuilder`：

```java
package com.king.ai.compliance.access;

import com.king.ai.common.security.SpaceRole;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AccessReviewReportBuilder {

    public record MemberSnapshot(Long userId, String username, String nickname, String role,
                                 LocalDateTime lastLoginAt) {
    }

    public record CredentialSnapshot(Long id, String name, LocalDateTime lastActivityAt) {
    }

    public record PublicAgentSnapshot(Long id, String name, String publicId,
                                      LocalDateTime lastActivityAt) {
    }

    public record AccessFinding(String subjectType, Long subjectId, String name, String role,
                                LocalDateTime lastActivityAt, List<String> flags) {
    }

    private AccessReviewReportBuilder() {
    }

    public static List<AccessFinding> build(List<MemberSnapshot> members,
                                            List<CredentialSnapshot> suppliers,
                                            List<CredentialSnapshot> mcps,
                                            List<PublicAgentSnapshot> publicAgents,
                                            LocalDateTime now, int staleDays) {
        int window = Math.max(1, staleDays);
        LocalDateTime stale = now.minusDays(window);
        List<AccessFinding> findings = new ArrayList<>();
        for (MemberSnapshot member : members) {
            Set<String> flags = new LinkedHashSet<>();
            if (member.lastLoginAt() == null) {
                flags.add("NEVER_LOGGED_IN");
            } else if (member.lastLoginAt().isBefore(stale)) {
                flags.add("UNUSED_" + window + "D");
            }
            if ("OWNER".equals(member.role())) {
                flags.add("OWNER");
            }
            findings.add(new AccessFinding("MEMBER", member.userId(),
                    member.nickname() + " (" + member.username() + ")", member.role(),
                    member.lastLoginAt(), List.copyOf(flags)));
        }
        for (CredentialSnapshot supplier : suppliers) {
            findings.add(credential("SUPPLIER", supplier, stale, "供应商密钥"));
        }
        for (CredentialSnapshot mcp : mcps) {
            findings.add(credential("MCP", mcp, stale, "MCP 配置"));
        }
        for (PublicAgentSnapshot agent : publicAgents) {
            findings.add(new AccessFinding("PUBLIC_AGENT", agent.id(), agent.name(),
                    agent.publicId(), agent.lastActivityAt(), List.of("PUBLIC_EXPOSURE")));
        }
        return findings;
    }

    private static AccessFinding credential(String type, CredentialSnapshot snapshot,
                                            LocalDateTime stale, String label) {
        List<String> flags = snapshot.lastActivityAt() == null
                || snapshot.lastActivityAt().isBefore(stale)
                ? List.of("STALE_CREDENTIAL") : List.of();
        return new AccessFinding(type, snapshot.id(), snapshot.name(), label,
                snapshot.lastActivityAt(), flags);
    }
}
```

`AccessReviewDecisions`：

```java
package com.king.ai.compliance.access;

import com.king.ai.common.BizException;
import com.king.ai.common.security.SpaceRole;

import java.util.Locale;
import java.util.Set;

public final class AccessReviewDecisions {

    public static final String CONFIRM = "CONFIRM";
    public static final String ACKNOWLEDGE = "ACKNOWLEDGE";
    public static final String DOWNGRADE = "DOWNGRADE";
    public static final String REMOVE = "REMOVE";

    private static final Set<String> ALL = Set.of(CONFIRM, ACKNOWLEDGE, DOWNGRADE, REMOVE);

    private AccessReviewDecisions() {
    }

    public static String parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(normalized)) {
            throw BizException.badRequest("复核决策不合法");
        }
        return normalized;
    }

    public static void validate(String subjectType, String decision) {
        if ("MEMBER".equals(subjectType)) {
            return;
        }
        if (!ACKNOWLEDGE.equals(decision) && !CONFIRM.equals(decision)) {
            throw BizException.badRequest("供应商/MCP/公开智能体仅支持确认或知悉，请在对应模块执行变更");
        }
    }

    public static SpaceRole downgrade(SpaceRole current) {
        return switch (current) {
            case OWNER -> SpaceRole.ADMIN;
            case ADMIN -> SpaceRole.EDITOR;
            case EDITOR -> SpaceRole.VIEWER;
            case AUDITOR -> SpaceRole.VIEWER;
            case VIEWER -> throw BizException.badRequest("该成员已是最低角色，无法降级");
        };
    }
}
```

`CsvWriter`：

```java
package com.king.ai.compliance.access;

import java.util.List;

public final class CsvWriter {

    private CsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        out.append(join(headers)).append('\n');
        for (List<String> row : rows) {
            out.append(join(row)).append('\n');
        }
        return out.toString();
    }

    private static String join(List<String> values) {
        return values.stream().map(CsvWriter::escape)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        // 防止 Excel/LibreOffice 把用户可控文本当作公式执行。
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
```

`AccessReviewService` 生成与决策（`AccessFinding`、`CredentialSnapshot`、`MemberSnapshot`、`PublicAgentSnapshot` 是 `AccessReviewReportBuilder` 的嵌套 record，本类需显式 import 这四个类型）：

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AccessReviewService {

    private final AccessReviewMapper reviewMapper;
    private final AccessReviewItemMapper itemMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SpaceService spaceService;
    private final AuditLogService auditLogService;
    private final KingAiProperties properties;

    @Transactional
    public AccessReviewView generate(Long spaceId, Long operatorId) {
        List<AccessReview> open = reviewMapper.selectList(new LambdaQueryWrapper<AccessReview>()
                .eq(AccessReview::getSpaceId, spaceId)
                .eq(AccessReview::getStatus, "OPEN"));
        for (AccessReview previous : open) {
            previous.setStatus("SUPERSEDED");
            reviewMapper.updateById(previous);
        }
        List<MemberSnapshot> members = jdbcTemplate.query(
                "SELECT us.user_id, u.username, u.nickname, us.role, u.last_login_at "
                        + "FROM king_ai_user_space us JOIN king_ai_user u ON u.id = us.user_id "
                        + "WHERE us.space_id = ? ORDER BY us.user_id",
                (rs, rowNum) -> new MemberSnapshot(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("nickname"), rs.getString("role"),
                        rs.getTimestamp("last_login_at") == null ? null
                                : rs.getTimestamp("last_login_at").toLocalDateTime()), spaceId);
        List<CredentialSnapshot> suppliers = jdbcTemplate.query(
                "SELECT id, name, COALESCE(api_key_rotated_at, updated_at) AS activity "
                        + "FROM king_ai_supplier WHERE space_id = ? ORDER BY id",
                (rs, rowNum) -> new CredentialSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("activity") == null ? null
                                : rs.getTimestamp("activity").toLocalDateTime()), spaceId);
        List<CredentialSnapshot> mcps = jdbcTemplate.query(
                "SELECT id, name, updated_at AS activity FROM king_ai_mcp "
                        + "WHERE space_id = ? ORDER BY id",
                (rs, rowNum) -> new CredentialSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("activity") == null ? null
                                : rs.getTimestamp("activity").toLocalDateTime()), spaceId);
        List<PublicAgentSnapshot> agents = jdbcTemplate.query(
                "SELECT id, name, public_id, updated_at FROM king_ai_agent "
                        + "WHERE space_id = ? AND publish_status = 1 AND public_id IS NOT NULL ORDER BY id",
                (rs, rowNum) -> new PublicAgentSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getString("public_id"),
                        rs.getTimestamp("updated_at") == null ? null
                                : rs.getTimestamp("updated_at").toLocalDateTime()), spaceId);
        List<AccessFinding> findings = AccessReviewReportBuilder.build(members, suppliers, mcps,
                agents, LocalDateTime.now(), properties.getCompliance().getAccessReview().getStaleDays());

        AccessReview review = new AccessReview();
        review.setSpaceId(spaceId);
        review.setStatus("OPEN");
        review.setGeneratedBy(operatorId);
        review.setGeneratedAt(LocalDateTime.now());
        review.setSummary("findings=" + findings.size());
        reviewMapper.insert(review);
        for (AccessFinding finding : findings) {
            AccessReviewItem item = new AccessReviewItem();
            item.setReviewId(review.getId());
            item.setSpaceId(spaceId);
            item.setSubjectType(finding.subjectType());
            item.setSubjectId(finding.subjectId());
            item.setSubjectName(Texts.trimTo(finding.name(), 200));
            item.setRole(Texts.trimTo(finding.role(), 20));
            item.setLastActivityAt(finding.lastActivityAt());
            item.setFlags(String.join(",", finding.flags()));
            item.setDecision("PENDING");
            itemMapper.insert(item);
        }
        auditLogService.recordEvent(spaceId, operatorId == null ? 0L : operatorId, "SYSTEM",
                "compliance.access_review_generate", "access_review",
                String.valueOf(review.getId()), AuditLogService.STATUS_SUCCESS,
                "findings=" + findings.size(), null, null, null);
        return get(spaceId, review.getId());
    }

    public List<AccessReviewView> list(Long spaceId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<AccessReview>()
                        .eq(AccessReview::getSpaceId, spaceId)
                        .orderByDesc(AccessReview::getId))
                .stream().map(review -> get(spaceId, review.getId())).toList();
    }

    public AccessReviewView get(Long spaceId, Long reviewId) {
        AccessReview review = reviewMapper.selectById(reviewId);
        if (review == null || !Objects.equals(review.getSpaceId(), spaceId)) {
            throw BizException.notFound("访问复核不存在");
        }
        List<AccessReviewItemView> items = itemMapper.selectList(
                        new LambdaQueryWrapper<AccessReviewItem>()
                                .eq(AccessReviewItem::getReviewId, reviewId)
                                .orderByAsc(AccessReviewItem::getId))
                .stream().map(this::view).toList();
        return new AccessReviewView(review.getId(), review.getSpaceId(), review.getStatus(),
                review.getSummary(), review.getGeneratedBy(), review.getGeneratedAt(),
                review.getCompletedAt(), items);
    }

    public String csv(Long spaceId, Long reviewId, UserPrincipal operator) {
        AccessReviewView review = get(spaceId, reviewId);
        List<List<String>> rows = review.items().stream()
                .map(item -> List.of(
                        nullToEmpty(item.subjectType()),
                        nullToEmpty(item.subjectName()),
                        nullToEmpty(item.role()),
                        item.lastActivityAt() == null ? "" : item.lastActivityAt().toString(),
                        String.join(";", item.flags()),
                        nullToEmpty(item.decision()),
                        item.decidedAt() == null ? "" : item.decidedAt().toString(),
                        nullToEmpty(item.note())))
                .toList();
        auditLogService.recordEvent(spaceId, operator.id(), operator.username(),
                "compliance.access_review_export", "access_review", String.valueOf(reviewId),
                AuditLogService.STATUS_SUCCESS, "rows=" + rows.size(), null, null, null);
        return CsvWriter.write(List.of("subject_type", "subject_name", "role",
                "last_activity_at", "flags", "decision", "decided_at", "note"), rows);
    }

    private AccessReviewItemView view(AccessReviewItem item) {
        List<String> flags = Texts.isBlank(item.getFlags()) ? List.of()
                : java.util.Arrays.stream(item.getFlags().split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toList();
        return new AccessReviewItemView(item.getId(), item.getSubjectType(), item.getSubjectId(),
                item.getSubjectName(), item.getRole(), item.getLastActivityAt(), flags,
                item.getDecision(), item.getDecidedBy(), item.getDecidedAt(), item.getNote());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

在 `AccessReviewService` 类中加入 `decide`：

```java
    @Transactional
    public AccessReviewItemView decide(Long spaceId, Long reviewId, Long itemId,
                                       AccessReviewDecisionRequest request, UserPrincipal operator) {
        AccessReview review = reviewMapper.selectById(reviewId);
        if (review == null || !Objects.equals(review.getSpaceId(), spaceId)
                || !"OPEN".equals(review.getStatus())) {
            throw BizException.notFound("访问复核不存在或已完成");
        }
        AccessReviewItem item = itemMapper.selectById(itemId);
        if (item == null || !Objects.equals(item.getReviewId(), reviewId)) {
            throw BizException.notFound("复核项不存在");
        }
        String decision = AccessReviewDecisions.parse(request.decision());
        AccessReviewDecisions.validate(item.getSubjectType(), decision);
        if ("MEMBER".equals(item.getSubjectType())) {
            applyMemberDecision(spaceId, item, decision, operator);
        }
        item.setDecision(decision);
        item.setDecidedBy(operator.id());
        item.setDecidedAt(LocalDateTime.now());
        item.setNote(Texts.trimTo(request.note(), 500));
        OptimisticLocks.requireUpdated(itemMapper.updateById(item), "复核项");
        long pending = itemMapper.selectCount(new LambdaQueryWrapper<AccessReviewItem>()
                .eq(AccessReviewItem::getReviewId, reviewId)
                .eq(AccessReviewItem::getDecision, "PENDING"));
        if (pending == 0) {
            review.setStatus("COMPLETED");
            review.setCompletedAt(LocalDateTime.now());
            OptimisticLocks.requireUpdated(reviewMapper.updateById(review), "访问复核");
        }
        auditLogService.recordEvent(spaceId, operator.id(), operator.username(),
                "compliance.access_review_decide", "access_review",
                String.valueOf(reviewId),
                AuditLogService.STATUS_SUCCESS,
                "item=" + itemId + ", subject=" + item.getSubjectType() + ", decision=" + decision,
                null, null, null);
        return view(item);
    }
```

`applyMemberDecision`：

```java
    private void applyMemberDecision(Long spaceId, AccessReviewItem item, String decision,
                                     UserPrincipal operator) {
        if ("CONFIRM".equals(decision) || "ACKNOWLEDGE".equals(decision)) {
            return;
        }
        if (Objects.equals(item.getSubjectId(), operator.id()) && "REMOVE".equals(decision)) {
            throw BizException.badRequest("不能移除当前登录账号");
        }
        // 以决策时的实时角色为准，避免复核快照过期导致“降级”变成升级。
        SpaceRole current = currentRole(spaceId, item.getSubjectId());
        SpaceRole actorRole = spaceService.roleInSpace(operator, spaceId);
        if (current == SpaceRole.OWNER && actorRole != SpaceRole.OWNER && !operator.isAdmin()) {
            throw BizException.forbidden("只有空间所有者可以调整其他所有者");
        }
        if (current == SpaceRole.OWNER) {
            Long owners = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM king_ai_user_space WHERE space_id = ? AND role = 'OWNER'",
                    Long.class, spaceId);
            if (owners == null || owners <= 1) {
                throw BizException.badRequest("REMOVE".equals(decision)
                        ? "该成员是空间唯一所有者，请先转移所有权"
                        : "该成员是空间唯一所有者，不能降级，请先转移所有权");
            }
        }
        if ("REMOVE".equals(decision)) {
            jdbcTemplate.update("DELETE FROM king_ai_user_space WHERE space_id = ? AND user_id = ?",
                    spaceId, item.getSubjectId());
            return;
        }
        SpaceRole target = AccessReviewDecisions.downgrade(current);
        jdbcTemplate.update("UPDATE king_ai_user_space SET role = ?, updated_at = ? "
                        + "WHERE space_id = ? AND user_id = ?",
                target.name(), LocalDateTime.now(), spaceId, item.getSubjectId());
    }

    private SpaceRole currentRole(Long spaceId, Long userId) {
        List<String> roles = jdbcTemplate.query(
                "SELECT role FROM king_ai_user_space WHERE space_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getString(1), spaceId, userId);
        if (roles.isEmpty()) {
            throw BizException.notFound("成员已不在该空间");
        }
        return SpaceRole.parse(roles.get(0));
    }
```

`csv(spaceId, reviewId)` 用 `CsvWriter.write` 输出表头 `subject_type,subject_name,role,last_activity_at,flags,decision,decided_at,note`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=AccessReviewReportBuilderTest,AccessReviewDecisionsTest,CsvWriterTest,AccessReviewServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/access king-ai-api/src/main/java/com/king/ai/compliance/dto king-ai-api/src/test/java/com/king/ai/compliance
git commit -m "feat(compliance): add access review report and decisions"
```

---

### Task 8: 合规控制器、季度任务与文档

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceController.java`
- Modify: `docs/DEPLOYMENT.md`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceControllerTest.java`

**Interfaces:**
- Produces:
  - `GET/PUT /api/compliance/retention-policies`
  - `GET/POST /api/compliance/legal-holds`、`DELETE /api/compliance/legal-holds/{id}`
  - `GET/POST /api/compliance/exports`、`GET /api/compliance/exports/{id}/download`
  - `GET/POST /api/compliance/access-reviews`、`GET /api/compliance/access-reviews/{id}`
  - `POST /api/compliance/access-reviews/{id}/items/{itemId}/decision`
  - `GET /api/compliance/access-reviews/{id}/export.csv`

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.compliance;

import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.compliance.export.ExportService;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.compliance.retention.RetentionPolicyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ComplianceControllerTest {

    @Test
    void constructsWithAllServices() {
        ComplianceController controller = new ComplianceController(
                mock(RetentionPolicyService.class), mock(LegalHoldService.class),
                mock(ExportService.class), mock(AccessReviewService.class),
                mock(com.king.ai.audit.AuditLogService.class));

        assertThat(controller).isNotNull();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=ComplianceControllerTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现控制器与季度任务**

控制器按现有 `ApiResponse` + `RequestContext` 约定编写，字段顺序与测试构造保持一致：

```java
package com.king.ai.compliance;

import com.king.ai.audit.AuditLogService;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.BizException;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.compliance.dto.AccessReviewDecisionRequest;
import com.king.ai.compliance.dto.AccessReviewItemView;
import com.king.ai.compliance.dto.AccessReviewView;
import com.king.ai.compliance.dto.ExportCreateRequest;
import com.king.ai.compliance.dto.ExportJobView;
import com.king.ai.compliance.dto.LegalHoldCreateRequest;
import com.king.ai.compliance.dto.LegalHoldView;
import com.king.ai.compliance.dto.RetentionPolicyUpdateRequest;
import com.king.ai.compliance.dto.RetentionPolicyView;
import com.king.ai.compliance.export.ExportService;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.compliance.retention.RetentionPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final RetentionPolicyService retentionPolicyService;
    private final LegalHoldService legalHoldService;
    private final ExportService exportService;
    private final AccessReviewService accessReviewService;
    private final AuditLogService auditLogService;

    @GetMapping("/retention-policies")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ApiResponse<List<RetentionPolicyView>> retentionPolicies() {
        return ApiResponse.ok(retentionPolicyService.list(RequestContext.requireSpaceId()));
    }

    @PutMapping("/retention-policies")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<RetentionPolicyView> upsertRetentionPolicy(
            @Valid @RequestBody RetentionPolicyUpdateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(retentionPolicyService.upsert(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @GetMapping("/legal-holds")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<List<LegalHoldView>> legalHolds() {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(legalHoldService.list(RequestContext.requireSpaceId()));
    }

    @PostMapping("/legal-holds")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<LegalHoldView> createLegalHold(
            @Valid @RequestBody LegalHoldCreateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(legalHoldService.create(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @DeleteMapping("/legal-holds/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> releaseLegalHold(@PathVariable Long id) {
        legalHoldService.release(RequestContext.requireSpaceId(), id,
                RequestContext.require().id());
        return ApiResponse.ok(null);
    }

    @GetMapping("/exports")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<PageResult<ExportJobView>> exports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(exportService.page(RequestContext.requireSpaceId(), page, size));
    }

    @PostMapping("/exports")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ExportJobView> createExport(@Valid @RequestBody ExportCreateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(exportService.create(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @GetMapping("/exports/{id}/download")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        UserPrincipal principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        ExportService.Download download = exportService.openDownload(spaceId, id);
        String fileName = download.fileName() == null ? "export.bin"
                : download.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        auditLogService.recordEvent(spaceId, principal.id(), principal.username(),
                "compliance.export_download", "export", String.valueOf(id),
                AuditLogService.STATUS_SUCCESS, "bytes=" + download.size(), null, null, null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.size())
                .body(new InputStreamResource(download.stream()));
    }

    @GetMapping("/access-reviews")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<List<AccessReviewView>> accessReviews() {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(accessReviewService.list(RequestContext.requireSpaceId()));
    }

    @PostMapping("/access-reviews")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AccessReviewView> generateAccessReview() {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(accessReviewService.generate(
                RequestContext.requireSpaceId(), principal.id()));
    }

    @GetMapping("/access-reviews/{id}")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<AccessReviewView> accessReview(@PathVariable Long id) {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(accessReviewService.get(RequestContext.requireSpaceId(), id));
    }

    @PostMapping("/access-reviews/{id}/items/{itemId}/decision")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AccessReviewItemView> decide(@PathVariable Long id,
                                                    @PathVariable Long itemId,
                                                    @Valid @RequestBody
                                                    AccessReviewDecisionRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(accessReviewService.decide(RequestContext.requireSpaceId(), id,
                itemId, request, principal));
    }

    @GetMapping("/access-reviews/{id}/export.csv")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ResponseEntity<byte[]> exportReview(@PathVariable Long id) {
        UserPrincipal principal = RequestContext.require();
        requireAuditorOrAdmin(principal);
        String csv = accessReviewService.csv(RequestContext.requireSpaceId(), id, principal);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"access-review-" + id + ".csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code @RequireSpaceRole} 的数组匹配是“任一角色 or 更高等级”，而 AUDITOR 与 VIEWER
     * 同级别；这里再按精确角色判一次，避免 VIEWER/EDITOR 读到审计类数据。
     */
    private static void requireAuditorOrAdmin(UserPrincipal principal) {
        if (principal.isAdmin()) {
            return;
        }
        SpaceRole role = principal.roleInSpace();
        if (role != SpaceRole.OWNER && role != SpaceRole.ADMIN && role != SpaceRole.AUDITOR) {
            throw BizException.forbidden("需要管理员或审计员权限");
        }
    }
}
```

`AccessReviewService` 增加季度生成：

```java
    @Scheduled(cron = "${king-ai.compliance.access-review.cron:0 0 4 1 1,4,7,10 *}")
    public void quarterly() {
        for (Long spaceId : spaceService.allSpaceIds()) {
            try {
                generate(spaceId, 0L);
            } catch (RuntimeException ex) {
                log.warn("Scheduled access review failed: spaceId={}, reason={}", spaceId,
                        ex.getMessage());
            }
        }
    }
```

`AccessReviewService` 已在类上标注 `@Slf4j`。季度任务生成的 review 会再次把上一次 OPEN 置为 SUPERSEDED，确保不会有多个 OPEN。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=ComplianceControllerTest test && mvn -pl king-ai-api test`

Expected: PASS。

- [ ] **Step 5: 更新文档并提交**

`docs/DEPLOYMENT.md` 追加：

```markdown
### 数据保留、法律保留与导出

- 默认关闭保留清理：`KING_AI_RETENTION_ENABLED=false`；首次开启保持 `KING_AI_RETENTION_DRY_RUN=true` 观察一轮。
- 策略按 `SESSION/MESSAGE/AUDIT/EXPORT` 配置；法律保留优先，清理与过期导出删除都会跳过 active hold。
- 保留清理开启后，审计日志不再走 `king-ai.audit.retention-days` 的旧清理任务，必须显式配置 `AUDIT` 策略，否则审计日志不会自动清理。
- 会话清理前默认写 `jsonl.gz` 归档到 `KING_AI_EXPORT_DIR`；归档失败则本轮不删除该批。
- 导出仅 OWNER/ADMIN，文件 24 小时过期；本地存储路径按空间隔离，禁止路径穿越。
- 审计动作：`compliance.retention`、`compliance.export`、`compliance.access_review_decide`。
- 指标：`king.ai.compliance.retention.deleted{type}`、`king.ai.compliance.export.jobs{status}`。

### 访问复核

- 每季度 1 日 04:00 自动生成；也可 `POST /api/compliance/access-reviews` 手动生成。
- 报告包含成员角色/最近登录、供应商密钥轮换时间、MCP 配置更新时间、公开智能体。
- 成员可确认/降级/移除；供应商/MCP/公开智能体只能确认或知悉，实际变更到对应模块执行。
- 降级阶梯：OWNER→ADMIN→EDITOR→VIEWER；AUDITOR→VIEWER；VIEWER 不可再降。
```

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/ComplianceController.java king-ai-api/src/main/java/com/king/ai/compliance/access/AccessReviewService.java king-ai-api/src/test/java/com/king/ai/compliance/ComplianceControllerTest.java docs/DEPLOYMENT.md
git commit -m "feat(compliance): expose retention export and access review APIs"
```

---

## Execution Notes

- 本计划依赖 G0-1 的 `RedactionService`/`ComplianceSettingsService` 与 `AuditLogService.recordEvent`，执行顺序固定为 G0-1/2 → G0-3/4；禁止以透传实现导出原文。
- 启用 `king-ai.compliance.retention.enabled=true` 后，审计日志只由 `RetentionJob` 的 `AUDIT` 策略清理；必须同时配置 `AUDIT` 保留策略并先以 `dry-run=true` 观察一轮。
- 保留清理的 `dry-run` 必须先跑一轮并核对审计数量，再关闭。
- 本地导出目录必须放在持久卷并限制文件权限；P2-3 切换到对象存储时只替换 `ExportStorage` 实现。
- 访问复核的 `DOWNGRADE` 直接改 `user_space.role`，`REMOVE` 直接删成员关系；所有保护（唯一所有者、自移除、非 OWNER 调整 OWNER）在服务层强制，不能只依赖前端。
- 归档写入失败时本轮不删除，保留数据优先于清理进度。
