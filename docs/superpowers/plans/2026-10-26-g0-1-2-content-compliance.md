# G0-1/G0-2 内容合规 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在聊天输入、检索上下文、模型输出、SSE 推送和落库五个环节加入可配置的 PII 脱敏与内容审核，保证敏感原文不进入模型、不落库、不出现在日志，违规公开对话 fail-closed 并留下可审计记录。

**Architecture:** 每个空间一条 `king_ai_compliance_setting` 配置（缺省来自 `application.yml`）。`RedactionService` 用内置规则 + 空间自定义正则做最长匹配替换；`LocalRuleModerator` 做归一化词表/正则/刷屏启发式审核，外部审核通过 `ExternalModerationClient` 适配并在超时时降级本地规则。`ChatRuntime` 在入口调用 `ChatInputCompliance`，在流式回调中使用 `ComplianceStreamGuard`（128 字符安全带）保证跨 delta 的敏感串不会先泄露，再统一发送、落库和计数。

**Tech Stack:** Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.18、Flyway（H2/MySQL）、Micrometer、JUnit 5 + AssertJ + Mockito。

**Spec:** `docs/superpowers/plans/2026-10-26-p2-scale-program.md` 的 G0-1、G0-2 小节。

## Global Constraints

- 所有新迁移只新增文件，禁止修改 V1–V8。本计划占用 **V9**；G0-3/G0-4 顺序使用 V10/V11。
- 不新增第三方运行时依赖；外部审核使用已有 OkHttp。
- 服务端面向用户的消息使用中文；类名、方法名、日志键使用英文。
- 任何日志、审计、指标都不得包含命中的原始值。审计只记录 `category`、`surface`、`ruleId`、`count`。
- 未配置合规设置的空间保持现有行为：PII 默认开启脱敏，内容审核默认 `OFF`。
- 公开对话在审核服务不可用且 `fail-closed-on-error=true` 时按 `REVIEW` 处理并拦截；内部对话记录审计后继续。
- 每个任务先写失败测试，再实现，再跑 `mvn -pl king-ai-api -Dtest=<TestClass> test`，最后跑 `mvn -pl king-ai-api test`。
- 版本控制前置条件：先执行 `git rev-parse --is-inside-work-tree`；若不是 Git 仓库，执行一次 `git init`，提交当前基线后再开始任务。不得提交 `data/`、`.env`、导出文件或任何密钥。
- 迁移测试用 H2 + Flyway 直接执行 `classpath:db/migration/h2`，不启动 Spring 上下文。

---

## File Structure

**Create**
- `king-ai-api/src/main/resources/db/migration/h2/V9__compliance_settings.sql`
- `king-ai-api/src/main/resources/db/migration/mysql/V9__compliance_settings.sql`
- `king-ai-api/src/main/java/com/king/ai/compliance/entity/ComplianceSetting.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/mapper/ComplianceSettingMapper.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettings.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettingsService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationMode.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationAction.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/dto/ComplianceSettingView.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/dto/ComplianceSettingUpdateRequest.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/RedactionRule.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/RedactionHit.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/RedactionResult.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/RedactionService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationVerdict.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationDecision.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationContext.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationHit.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationScan.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/LocalRuleModerator.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ExternalModerationClient.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/HttpExternalModerationClient.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ModerationService.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ContentModerationException.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ChatInputCompliance.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceStreamGuard.java`
- `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettingController.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceSettingsServiceTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceMigrationTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/RedactionServiceTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/LocalRuleModeratorTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/ModerationServiceTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/ChatInputComplianceTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceStreamGuardTest.java`
- `king-ai-api/src/test/java/com/king/ai/compliance/HttpExternalModerationClientTest.java`

**Modify**
- `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- `king-ai-api/src/main/resources/application.yml`
- `docs/DEPLOYMENT.md`

---

### Task 1: 空间合规配置与通用审计事件

**Files:**
- Create: `king-ai-api/src/main/resources/db/migration/h2/V9__compliance_settings.sql`
- Create: `king-ai-api/src/main/resources/db/migration/mysql/V9__compliance_settings.sql`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/entity/ComplianceSetting.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/mapper/ComplianceSettingMapper.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationMode.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationAction.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettings.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettingsService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/dto/ComplianceSettingView.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/dto/ComplianceSettingUpdateRequest.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- Modify: `king-ai-api/src/main/resources/application.yml`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceSettingsServiceTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceMigrationTest.java`

**Interfaces:**
- Produces: `ComplianceSettings ComplianceSettingsService.effective(Long spaceId)`
- Produces: `ComplianceSettingView ComplianceSettingsService.view(Long spaceId)`
- Produces: `ComplianceSettingView ComplianceSettingsService.update(Long spaceId, ComplianceSettingUpdateRequest request)`
- Produces: `void AuditLogService.recordEvent(Long spaceId, Long actorId, String actorName, String action, String resourceType, String resourceId, String status, String detail, String ip, String userAgent, String requestId)`
- Produces enums: `ModerationMode { OFF, LOCAL, EXTERNAL, HYBRID }`、`ModerationAction { BLOCK, WARN }`
- Produces record: `ComplianceSettings(boolean piiEnabled, ModerationMode moderationMode, ModerationAction inputAction, ModerationAction outputAction, List<String> blocklist, List<Pattern> customPiiPatterns, int streamHoldbackChars)`

- [ ] **Step 1: 写失败测试**

`ComplianceSettingsServiceTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.common.BizException;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.entity.ComplianceSetting;
import com.king.ai.compliance.mapper.ComplianceSettingMapper;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceSettingsServiceTest {

    @Mock
    private ComplianceSettingMapper mapper;

    private ComplianceSettingsService service;

    @BeforeEach
    void setUp() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getPii().setDefaultEnabled(true);
        properties.getCompliance().getModeration().setDefaultMode(ModerationMode.OFF);
        service = new ComplianceSettingsService(mapper, properties);
    }

    @Test
    void effectiveReturnsDefaultsWhenSpaceHasNoRow() {
        when(mapper.selectOne(any())).thenReturn(null);

        ComplianceSettings settings = service.effective(7L);

        assertThat(settings.piiEnabled()).isTrue();
        assertThat(settings.moderationMode()).isEqualTo(ModerationMode.OFF);
        assertThat(settings.inputAction()).isEqualTo(ModerationAction.BLOCK);
        assertThat(settings.outputAction()).isEqualTo(ModerationAction.BLOCK);
        assertThat(settings.blocklist()).isEmpty();
        assertThat(settings.customPiiPatterns()).isEmpty();
    }

    @Test
    void effectiveCachesPerSpace() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.effective(7L);
        service.effective(7L);

        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    void updateRejectsUnknownMode() {
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "STRICT", "BLOCK", "BLOCK", "", "");

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核模式");
    }

    @Test
    void updateRejectsInvalidCustomRegex() {
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "([a-z");

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 1 条");
    }

    @Test
    void updateNormalizesBlocklistAndPersists() {
        when(mapper.selectOne(any())).thenReturn(null);
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "local", "block", "warn",
                " 违禁词 \n违禁词, 另一个词 \n\n", "");

        service.update(7L, request);

        ArgumentCaptor<ComplianceSetting> captor = ArgumentCaptor.forClass(ComplianceSetting.class);
        verify(mapper).insert(captor.capture());
        ComplianceSetting saved = captor.getValue();
        assertThat(saved.getBlocklist()).isEqualTo("违禁词\n另一个词");
        assertThat(saved.getModerationMode()).isEqualTo("LOCAL");
        assertThat(saved.getModerationInputAction()).isEqualTo("BLOCK");
        assertThat(saved.getModerationOutputAction()).isEqualTo("WARN");
        assertThat(saved.getPiiEnabled()).isEqualTo(1);
    }
}
```

`ComplianceMigrationTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=ComplianceSettingsServiceTest,ComplianceMigrationTest test`

Expected: 编译失败，`ComplianceSettingsService` / `V9` 不存在。

- [ ] **Step 3: 迁移与配置类**

`h2/V9__compliance_settings.sql`：

```sql
CREATE TABLE IF NOT EXISTS king_ai_compliance_setting (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    space_id                  BIGINT      NOT NULL,
    pii_enabled               TINYINT     NOT NULL DEFAULT 1,
    moderation_mode           VARCHAR(16) NOT NULL DEFAULT 'OFF',
    moderation_input_action   VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    moderation_output_action  VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    blocklist                 TEXT        NULL,
    pii_custom_patterns       TEXT        NULL,
    version                   INT         NOT NULL DEFAULT 0,
    created_by                BIGINT      NULL,
    created_at                DATETIME    NOT NULL,
    updated_by                BIGINT      NULL,
    updated_at                DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_compliance_setting_space
    ON king_ai_compliance_setting (space_id);
ALTER TABLE king_ai_compliance_setting ADD CONSTRAINT fk_compliance_setting_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
```

`mysql/V9__compliance_settings.sql`：

```sql
CREATE TABLE IF NOT EXISTS king_ai_compliance_setting (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    space_id                  BIGINT      NOT NULL,
    pii_enabled               TINYINT     NOT NULL DEFAULT 1 COMMENT '是否开启 PII 脱敏',
    moderation_mode           VARCHAR(16) NOT NULL DEFAULT 'OFF' COMMENT 'OFF/LOCAL/EXTERNAL/HYBRID',
    moderation_input_action   VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/WARN',
    moderation_output_action  VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/WARN',
    blocklist                 TEXT        NULL COMMENT '违禁词，换行分隔',
    pii_custom_patterns       TEXT        NULL COMMENT '自定义 PII 正则，换行分隔',
    version                   INT         NOT NULL DEFAULT 0,
    created_by                BIGINT      NULL,
    created_at                DATETIME    NOT NULL,
    updated_by                BIGINT      NULL,
    updated_at                DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_compliance_setting_space (space_id),
    CONSTRAINT fk_compliance_setting_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间内容合规配置';
```

`KingAiProperties` 增加字段与嵌套类（放在 `Audit audit` 之后）：

```java
    private Compliance compliance = new Compliance();

    @Data
    public static class Compliance {
        private Pii pii = new Pii();
        private Moderation moderation = new Moderation();
    }

    @Data
    public static class Pii {
        private boolean defaultEnabled = true;
        private int maxCustomRules = 20;
        private int streamHoldbackChars = 128;
    }

    @Data
    public static class Moderation {
        private com.king.ai.compliance.ModerationMode defaultMode =
                com.king.ai.compliance.ModerationMode.OFF;
        private boolean failClosedOnError = true;
        private int externalTimeoutMs = 2000;
        private String externalUrl = "";
        private String externalToken = "";
    }
```

`ModerationMode.java`：

```java
package com.king.ai.compliance;

import java.util.Locale;

public enum ModerationMode {
    OFF, LOCAL, EXTERNAL, HYBRID;

    public static ModerationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return ModerationMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean usesLocal() {
        return this == LOCAL || this == HYBRID;
    }

    public boolean usesExternal() {
        return this == EXTERNAL || this == HYBRID;
    }
}
```

`ModerationAction.java`：

```java
package com.king.ai.compliance;

import java.util.Locale;

public enum ModerationAction {
    BLOCK, WARN;

    public static ModerationAction parse(String value) {
        if (value == null || value.isBlank()) {
            return BLOCK;
        }
        return ModerationAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
```

`ComplianceSetting.java`：

```java
package com.king.ai.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_compliance_setting")
public class ComplianceSetting extends BaseEntity {

    private Long spaceId;
    private Integer piiEnabled;
    private String moderationMode;
    private String moderationInputAction;
    private String moderationOutputAction;
    private String blocklist;
    private String piiCustomPatterns;

    @Version
    private Integer version;
}
```

`ComplianceSettingMapper.java`：

```java
package com.king.ai.compliance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.compliance.entity.ComplianceSetting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComplianceSettingMapper extends BaseMapper<ComplianceSetting> {
}
```

`ComplianceSettings.java`：

```java
package com.king.ai.compliance;

import java.util.List;
import java.util.regex.Pattern;

public record ComplianceSettings(
        boolean piiEnabled,
        ModerationMode moderationMode,
        ModerationAction inputAction,
        ModerationAction outputAction,
        List<String> blocklist,
        List<Pattern> customPiiPatterns,
        int streamHoldbackChars
) {
    public ComplianceSettings {
        blocklist = blocklist == null ? List.of() : List.copyOf(blocklist);
        customPiiPatterns = customPiiPatterns == null ? List.of() : List.copyOf(customPiiPatterns);
    }

    public boolean moderationEnabled() {
        return moderationMode != ModerationMode.OFF;
    }
}
```

`ComplianceSettingView.java`：

```java
package com.king.ai.compliance.dto;

import java.util.List;

public record ComplianceSettingView(
        boolean piiEnabled,
        String moderationMode,
        String inputAction,
        String outputAction,
        List<String> blocklist,
        List<String> customPiiPatterns
) {
}
```

`ComplianceSettingUpdateRequest.java`：

```java
package com.king.ai.compliance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ComplianceSettingUpdateRequest(
        @NotNull Boolean piiEnabled,
        @NotNull String moderationMode,
        @NotNull String inputAction,
        @NotNull String outputAction,
        @Size(max = 20000) String blocklist,
        @Size(max = 8000) String customPiiPatterns
) {
}
```

`ComplianceSettingsService.java`：

```java
package com.king.ai.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import com.king.ai.compliance.entity.ComplianceSetting;
import com.king.ai.compliance.mapper.ComplianceSettingMapper;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceSettingsService {

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int MAX_BLOCKLIST_TERMS = 500;
    private static final int MAX_TERM_CHARS = 50;

    private final ComplianceSettingMapper mapper;
    private final KingAiProperties properties;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ComplianceSettings settings, long expiresAt) {
    }

    public ComplianceSettings effective(Long spaceId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(spaceId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.settings();
        }
        ComplianceSettings settings = load(spaceId);
        cache.put(spaceId, new Cached(settings, now + CACHE_TTL_MS));
        return settings;
    }

    public ComplianceSettingView view(Long spaceId) {
        ComplianceSetting row = findRow(spaceId);
        if (row == null) {
            ComplianceSettings defaults = effective(spaceId);
            return new ComplianceSettingView(defaults.piiEnabled(),
                    defaults.moderationMode().name(), defaults.inputAction().name(),
                    defaults.outputAction().name(),
                    defaults.blocklist(), List.of());
        }
        return new ComplianceSettingView(row.getPiiEnabled() != null && row.getPiiEnabled() == 1,
                row.getModerationMode(), row.getModerationInputAction(),
                row.getModerationOutputAction(), splitLines(row.getBlocklist()),
                splitLines(row.getPiiCustomPatterns()));
    }

    @Transactional
    public ComplianceSettingView update(Long spaceId, ComplianceSettingUpdateRequest request) {
        ModerationMode mode = parseMode(request.moderationMode());
        ModerationAction inputAction = parseAction(request.inputAction());
        ModerationAction outputAction = parseAction(request.outputAction());
        List<String> terms = normalizeTerms(request.blocklist());
        List<String> patterns = normalizePatterns(request.customPiiPatterns());

        ComplianceSetting row = findRow(spaceId);
        boolean insert = row == null;
        if (insert) {
            row = new ComplianceSetting();
            row.setSpaceId(spaceId);
        }
        row.setPiiEnabled(Boolean.TRUE.equals(request.piiEnabled()) ? 1 : 0);
        row.setModerationMode(mode.name());
        row.setModerationInputAction(inputAction.name());
        row.setModerationOutputAction(outputAction.name());
        row.setBlocklist(String.join("\n", terms));
        row.setPiiCustomPatterns(String.join("\n", patterns));
        if (insert) {
            mapper.insert(row);
        } else {
            OptimisticLocks.requireUpdated(mapper.updateById(row), "合规配置");
        }
        cache.remove(spaceId);
        return view(spaceId);
    }

    public void evict(Long spaceId) {
        cache.remove(spaceId);
    }

    private ComplianceSettings load(Long spaceId) {
        ComplianceSetting row = findRow(spaceId);
        if (row == null) {
            return new ComplianceSettings(
                    properties.getCompliance().getPii().isDefaultEnabled(),
                    properties.getCompliance().getModeration().getDefaultMode(),
                    ModerationAction.BLOCK, ModerationAction.BLOCK,
                    List.of(), List.of(), holdback());
        }
        return new ComplianceSettings(
                row.getPiiEnabled() != null && row.getPiiEnabled() == 1,
                parseModeOrDefault(row.getModerationMode()),
                ModerationAction.parse(row.getModerationInputAction()),
                ModerationAction.parse(row.getModerationOutputAction()),
                splitLines(row.getBlocklist()),
                compileQuietly(splitLines(row.getPiiCustomPatterns())),
                holdback());
    }

    private ComplianceSetting findRow(Long spaceId) {
        return mapper.selectOne(new LambdaQueryWrapper<ComplianceSetting>()
                .eq(ComplianceSetting::getSpaceId, spaceId).last("LIMIT 1"));
    }

    private int holdback() {
        return Math.min(1024, Math.max(32,
                properties.getCompliance().getPii().getStreamHoldbackChars()));
    }

    private ModerationMode parseModeOrDefault(String value) {
        try {
            return ModerationMode.parse(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid moderation mode in database, falling back to OFF: {}", value);
            return ModerationMode.OFF;
        }
    }

    private ModerationMode parseMode(String value) {
        if (Texts.isBlank(value)) {
            throw BizException.badRequest("审核模式不能为空");
        }
        try {
            return ModerationMode.parse(value);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("审核模式仅支持 OFF/LOCAL/EXTERNAL/HYBRID");
        }
    }

    private ModerationAction parseAction(String value) {
        if (Texts.isBlank(value)) {
            throw BizException.badRequest("审核动作不能为空");
        }
        try {
            return ModerationAction.parse(value);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("审核动作仅支持 BLOCK/WARN");
        }
    }

    private List<String> normalizeTerms(String raw) {
        Set<String> terms = new LinkedHashSet<>();
        if (!Texts.isBlank(raw)) {
            for (String part : raw.split("[\\n,，]")) {
                String term = part.trim();
                if (term.isEmpty()) {
                    continue;
                }
                if (term.length() > MAX_TERM_CHARS) {
                    throw BizException.badRequest("违禁词长度不能超过 " + MAX_TERM_CHARS + " 个字符");
                }
                terms.add(term);
            }
        }
        if (terms.size() > MAX_BLOCKLIST_TERMS) {
            throw BizException.badRequest("违禁词不能超过 " + MAX_BLOCKLIST_TERMS + " 条");
        }
        return new ArrayList<>(terms);
    }

    private List<String> normalizePatterns(String raw) {
        Set<String> patterns = new LinkedHashSet<>();
        if (!Texts.isBlank(raw)) {
            for (String part : raw.split("\\n")) {
                String pattern = part.trim();
                if (pattern.isEmpty()) {
                    continue;
                }
                if (pattern.length() > 200) {
                    throw BizException.badRequest("自定义正则以 200 字符为限");
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    throw BizException.badRequest("自定义 PII 正则第 " + (patterns.size() + 1) + " 条不合法");
                }
                patterns.add(pattern);
            }
        }
        int max = Math.max(1, properties.getCompliance().getPii().getMaxCustomRules());
        if (patterns.size() > max) {
            throw BizException.badRequest("自定义 PII 正则不能超过 " + max + " 条");
        }
        return new ArrayList<>(patterns);
    }

    private List<Pattern> compileQuietly(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException ex) {
                log.warn("Ignoring invalid stored PII pattern");
            }
        }
        return compiled;
    }

    private static List<String> splitLines(String value) {
        if (Texts.isBlank(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("\\n"))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }
}
```

`AuditLogService` 新增通用入口，并让 `recordChat` 复用：

```java
    public void recordEvent(Long spaceId, Long actorId, String actorName, String action,
                            String resourceType, String resourceId, String status, String detail,
                            String ip, String userAgent, String requestId) {
        persist(spaceId, actorId, actorName, action, resourceType, resourceId, status, detail,
                ip, userAgent, requestId);
    }

    public void recordChat(Long spaceId, Long actorId, String actorName, String action, String resourceId,
                           String status, String detail, String ip, String userAgent, String requestId) {
        recordEvent(spaceId, actorId, actorName, action, "chat", resourceId, status, detail,
                ip, userAgent, requestId);
    }
```

`application.yml` 在 `audit` 后加入：

```yaml
  compliance:
    pii:
      default-enabled: ${KING_AI_PII_DEFAULT_ENABLED:true}
      max-custom-rules: 20
      stream-holdback-chars: ${KING_AI_PII_STREAM_HOLDBACK:128}
    moderation:
      default-mode: ${KING_AI_MODERATION_MODE:OFF}
      fail-closed-on-error: ${KING_AI_MODERATION_FAIL_CLOSED:true}
      external-timeout-ms: ${KING_AI_MODERATION_TIMEOUT_MS:2000}
      external-url: ${KING_AI_MODERATION_URL:}
      external-token: ${KING_AI_MODERATION_TOKEN:}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=ComplianceSettingsServiceTest,ComplianceMigrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/resources/db/migration king-ai-api/src/main/java/com/king/ai/compliance king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java king-ai-api/src/main/resources/application.yml
git commit -m "feat(compliance): add per-space compliance settings"
```

---

### Task 2: PII 脱敏引擎

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/RedactionRule.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/RedactionHit.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/RedactionResult.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/RedactionService.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/RedactionServiceTest.java`

**Interfaces:**
- Produces: `RedactionResult RedactionService.redact(String text, ComplianceSettings settings)`
- Produces: `RedactionRule(String id, String category, Pattern pattern, Predicate<String> validator, String replacement)`
- Produces: `RedactionHit(String ruleId, String category, int start, int end)`
- Produces: `RedactionResult(String text, List<RedactionHit> hits)`；`hits` 只含位置与规则，不含原文

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionServiceTest {

    private final RedactionService service = new RedactionService();

    private ComplianceSettings enabled(String... custom) {
        return new ComplianceSettings(true, ModerationMode.OFF, ModerationAction.BLOCK,
                ModerationAction.BLOCK, List.of(),
                java.util.Arrays.stream(custom).map(java.util.regex.Pattern::compile).toList(), 128);
    }

    @Test
    void redactsPhoneEmailIdCardAndBankCard() {
        RedactionResult result = service.redact(
                "联系 13812345678 / a.b@example.com / 11010519491231002X / 6222020200112233446",
                enabled());

        assertThat(result.text()).doesNotContain("13812345678");
        assertThat(result.text()).doesNotContain("a.b@example.com");
        assertThat(result.text()).doesNotContain("11010519491231002X");
        assertThat(result.text()).doesNotContain("6222020200112233446");
        assertThat(result.text()).contains("[REDACTED:PHONE]");
        assertThat(result.text()).contains("[REDACTED:EMAIL]");
        assertThat(result.text()).contains("[REDACTED:ID_CARD]");
        assertThat(result.text()).contains("[REDACTED:BANK_CARD]");
        assertThat(result.hits()).hasSize(4);
    }

    @Test
    void redactsApiKeysAndBearerTokens() {
        RedactionResult result = service.redact(
                "key=sk-live-abcdefghijklmnopqrstuvwx header=Bearer abcdefghijklmnopqrstuvwxyz0123456789",
                enabled());

        assertThat(result.text()).doesNotContain("sk-live-abcdefghijklmnopqrstuvwx");
        assertThat(result.text()).doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(result.text()).contains("[REDACTED:API_KEY]");
        assertThat(result.text()).contains("[REDACTED:BEARER]");
    }

    @Test
    void ignoresInvalidBankCardAndIdChecksums() {
        RedactionResult result = service.redact(
                "卡号 6222020200112233445 证件 110105194912310021", enabled());

        assertThat(result.text()).contains("6222020200112233445");
        assertThat(result.text()).contains("110105194912310021");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void appliesCustomPattern() {
        RedactionResult result = service.redact("员工编号 EMP-123456", enabled("EMP-\\d{6}"));

        assertThat(result.text()).isEqualTo("员工编号 [REDACTED:CUSTOM]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("custom-1"));
    }

    @Test
    void redactionIsIdempotent() {
        RedactionResult first = service.redact("13812345678", enabled());
        RedactionResult second = service.redact(first.text(), enabled());

        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.hits()).isEmpty();
    }

    @Test
    void disabledOrBlankReturnsOriginal() {
        ComplianceSettings disabled = new ComplianceSettings(false, ModerationMode.OFF,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of(), List.of(), 128);

        assertThat(service.redact("13812345678", disabled).text()).isEqualTo("13812345678");
        assertThat(service.redact("", enabled()).text()).isEmpty();
        assertThat(service.redact(null, enabled()).text()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=RedactionServiceTest test`

Expected: 编译失败，`RedactionService` 不存在。

- [ ] **Step 3: 实现规则与最长匹配**

`RedactionRule.java`：

```java
package com.king.ai.compliance;

import java.util.function.Predicate;
import java.util.regex.Pattern;

public record RedactionRule(String id, String category, Pattern pattern,
                            Predicate<String> validator, String replacement) {

    public boolean accepts(String value) {
        return validator == null || validator.test(value);
    }
}
```

`RedactionHit.java`：

```java
package com.king.ai.compliance;

public record RedactionHit(String ruleId, String category, int start, int end) {
}
```

`RedactionResult.java`：

```java
package com.king.ai.compliance;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record RedactionResult(String text, List<RedactionHit> hits) {

    public RedactionResult {
        text = text == null ? "" : text;
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public int redactedCount() {
        return hits.size();
    }

    public Set<String> ruleIds() {
        return hits.stream().map(RedactionHit::ruleId).collect(Collectors.toSet());
    }
}
```

`RedactionService.java`：

```java
package com.king.ai.compliance;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RedactionService {

    private static final int MAX_SCAN_CHARS = 200_000;

    private static final List<RedactionRule> BUILT_IN = List.of(
            new RedactionRule("email", "EMAIL",
                    Pattern.compile("(?i)(?<![\\w.%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])"),
                    value -> true, "[REDACTED:EMAIL]"),
            new RedactionRule("cn-mobile", "PHONE",
                    Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
                    value -> true, "[REDACTED:PHONE]"),
            new RedactionRule("cn-id", "ID_CARD",
                    Pattern.compile("(?<![\\dXx])[1-9]\\d{5}(?:19|20)\\d{2}"
                            + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![\\dXx])"),
                    RedactionService::validIdCard, "[REDACTED:ID_CARD]"),
            new RedactionRule("bank-card", "BANK_CARD",
                    Pattern.compile("(?<!\\d)(?:62\\d{14,17}|4\\d{15}|5[1-5]\\d{14})(?!\\d)"),
                    RedactionService::luhn, "[REDACTED:BANK_CARD]"),
            new RedactionRule("api-key", "API_KEY",
                    Pattern.compile("(?i)(?<![A-Za-z0-9])(?:sk|ak|pk|ghp|gho|github_pat|xox[abprs])"
                            + "[_-][A-Za-z0-9_-]{16,}(?![A-Za-z0-9])"),
                    value -> true, "[REDACTED:API_KEY]"),
            new RedactionRule("bearer", "BEARER",
                    Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
                    value -> true, "[REDACTED:BEARER]"),
            new RedactionRule("ipv4", "IP",
                    Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])"),
                    RedactionService::validIpv4, "[REDACTED:IP]")
    );

    public RedactionResult redact(String text, ComplianceSettings settings) {
        if (text == null || text.isEmpty() || settings == null || !settings.piiEnabled()) {
            return new RedactionResult(text == null ? "" : text, List.of());
        }
        String scan = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        List<RedactionRule> rules = new ArrayList<>(BUILT_IN);
        int index = 0;
        for (Pattern pattern : settings.customPiiPatterns()) {
            index++;
            rules.add(new RedactionRule("custom-" + index, "CUSTOM", pattern, value -> true,
                    "[REDACTED:CUSTOM]"));
        }
        List<RedactionHit> hits = findHits(scan, rules);
        if (hits.isEmpty()) {
            return new RedactionResult(text, List.of());
        }
        StringBuilder output = new StringBuilder(scan.length());
        int cursor = 0;
        for (RedactionHit hit : hits) {
            output.append(scan, cursor, hit.start());
            output.append(replacement(rules, hit));
            cursor = hit.end();
        }
        output.append(scan, cursor, scan.length());
        if (text.length() > MAX_SCAN_CHARS) {
            output.append(text, MAX_SCAN_CHARS, text.length());
        }
        return new RedactionResult(output.toString(), hits);
    }

    private static String replacement(List<RedactionRule> rules, RedactionHit hit) {
        return rules.stream().filter(rule -> rule.id().equals(hit.ruleId()))
                .findFirst().map(RedactionRule::replacement).orElse("[REDACTED]");
    }

    private static List<RedactionHit> findHits(String text, List<RedactionRule> rules) {
        List<RedactionHit> candidates = new ArrayList<>();
        for (RedactionRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                if (!rule.accepts(matcher.group())) {
                    continue;
                }
                candidates.add(new RedactionHit(rule.id(), rule.category(),
                        matcher.start(), matcher.end()));
            }
        }
        candidates.sort(Comparator.comparingInt(RedactionHit::start)
                .thenComparing(Comparator.comparingInt((RedactionHit hit) -> hit.end() - hit.start()).reversed()));
        List<RedactionHit> selected = new ArrayList<>();
        int cursor = -1;
        for (RedactionHit candidate : candidates) {
            if (candidate.start() >= cursor) {
                selected.add(candidate);
                cursor = candidate.end();
            }
        }
        return selected;
    }

    private static boolean luhn(String value) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = value.length() - 1; i >= 0; i--) {
            int digit = value.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static boolean validIdCard(String value) {
        if (value.length() != 18) {
            return false;
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
            sum += (ch - '0') * weights[i];
        }
        return Character.toUpperCase(value.charAt(17)) == codes[sum % 11];
    }

    private static boolean validIpv4(String value) {
        String[] parts = value.split("\\.");
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return parts.length == 4;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=RedactionServiceTest test`

Expected: PASS。身份证测试值 `11010519491231002X` 已按 GB 11643 校验位算法核对通过，直接使用，不要改成随机后缀。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/Redaction*.java king-ai-api/src/test/java/com/king/ai/compliance/RedactionServiceTest.java
git commit -m "feat(compliance): add PII redaction engine"
```

---

### Task 3: 本地内容审核与外部适配降级

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationVerdict.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationDecision.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationContext.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationHit.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationScan.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/LocalRuleModerator.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ExternalModerationClient.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/HttpExternalModerationClient.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ModerationService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ContentModerationException.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/LocalRuleModeratorTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ModerationServiceTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/HttpExternalModerationClientTest.java`

**Interfaces:**
- Produces: `ModerationDecision(ModerationVerdict verdict, String category, String reason)`
- Produces: `ModerationScan(ModerationDecision decision, List<ModerationHit> hits)`
- Produces: `LocalRuleModerator.scan(String text, List<String> blocklist)`
- Produces: `LocalRuleModerator.mask(String text, ModerationScan scan)`
- Produces: `ModerationScan ModerationService.scan(String text, ComplianceSettings settings, ModerationContext context)`
- Produces: `ExternalModerationClient.check(String text, ModerationContext context)`，异常表示适配器不可用

- [ ] **Step 1: 写失败测试**

`LocalRuleModeratorTest.java`：

```java
package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRuleModeratorTest {

    private final LocalRuleModerator moderator = new LocalRuleModerator();

    @Test
    void blocksConfiguredTerm() {
        ModerationScan scan = moderator.scan("这里有违禁词出现", List.of("违禁词"));

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.decision().category()).isEqualTo("BLOCKLIST");
        assertThat(scan.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.start()).isEqualTo(3));
    }

    @Test
    void detectsTermSeparatedByZeroWidthAndPunctuation() {
        ModerationScan scan = moderator.scan("违\u200B禁-词", List.of("违禁词"));

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.hits()).hasSize(1);
    }

    @Test
    void detectsTermSeparatedByWhitespace() {
        assertThat(moderator.scan("违 禁\n词", List.of("违禁词")).decision().verdict())
                .isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void maskStaysAlignedWhenNfkcWouldChangeLength() {
        // ㍿ 的 NFKC 结果是 4 个字符；若用归一化后的下标去改原串，会越界并漏掉违禁词。
        ModerationScan scan = moderator.scan("㍿违禁词", List.of("违禁词"));

        assertThat(moderator.mask("㍿违禁词", scan)).isEqualTo("㍿[已屏蔽]");
    }

    @Test
    void emptyTextOrEmptyBlocklistAllows() {
        assertThat(moderator.scan("", List.of("违禁词")).decision().verdict())
                .isEqualTo(ModerationVerdict.ALLOW);
        assertThat(moderator.scan("普通内容", List.of()).decision().verdict())
                .isEqualTo(ModerationVerdict.ALLOW);
    }

    @Test
    void excessiveLinksAreReview() {
        ModerationScan scan = moderator.scan(
                "https://a.com/1 https://a.com/2 https://a.com/3 "
                        + "https://a.com/4 https://a.com/5 https://a.com/6", List.of());

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(scan.decision().category()).isEqualTo("SPAM");
    }

    @Test
    void maskReplacesHitWithoutLeakingTerm() {
        ModerationScan scan = moderator.scan("前违禁词后", List.of("违禁词"));

        assertThat(moderator.mask("前违禁词后", scan)).isEqualTo("前[已屏蔽]后");
    }
}
```

`ModerationServiceTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationServiceTest {

    private KingAiProperties properties;
    private ModerationService service;
    private final ModerationContext internal = new ModerationContext(1L, false, "input");
    private final ModerationContext publicChat = new ModerationContext(1L, true, "input");

    private ComplianceSettings settings(ModerationMode mode) {
        return new ComplianceSettings(true, mode, ModerationAction.BLOCK,
                ModerationAction.BLOCK, List.of("违禁词"), List.of(), 128);
    }

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        service = new ModerationService(new LocalRuleModerator(), properties,
                (text, context) -> {
                    throw new IllegalStateException("timeout");
                });
    }

    @Test
    void offModeBypassesAllChecks() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.OFF), publicChat);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.ALLOW);
    }

    @Test
    void localModeBlocksConfiguredTerm() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.LOCAL), internal);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void externalFailureFallsBackToLocalBlock() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.EXTERNAL), internal);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void externalFailureForPublicChatFailClosedWhenLocalAllows() {
        ModerationScan scan = service.scan("普通内容", settings(ModerationMode.EXTERNAL), publicChat);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(scan.decision().category()).isEqualTo("MODERATION_UNAVAILABLE");
    }

    @Test
    void externalDecisionIsUsedWhenAvailable() {
        AtomicBoolean called = new AtomicBoolean();
        ModerationService withExternal = new ModerationService(new LocalRuleModerator(), properties,
                (text, context) -> {
                    called.set(true);
                    return new ModerationDecision(ModerationVerdict.BLOCK, "EXTERNAL", "blocked");
                });

        ModerationScan scan = withExternal.scan("普通内容", settings(ModerationMode.EXTERNAL), internal);

        assertThat(called).isTrue();
        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.decision().category()).isEqualTo("EXTERNAL");
    }
}
```

`HttpExternalModerationClientTest.java`：

```java
package com.king.ai.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpExternalModerationClientTest {

    @Test
    void rejectsBlockedOutboundUrlBeforeSending() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getModeration().setExternalUrl("http://127.0.0.1:9000/check");
        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        when(guard.violation("http://127.0.0.1:9000/check")).thenReturn("不允许访问内网地址");
        HttpExternalModerationClient client = new HttpExternalModerationClient(
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(), properties, guard);

        assertThatThrownBy(() -> client.check("hello",
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ModerationUnavailableException.class)
                .hasMessageContaining("不允许访问内网地址");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=LocalRuleModeratorTest,ModerationServiceTest,HttpExternalModerationClientTest test`

Expected: 编译失败，审核类不存在。

- [ ] **Step 3: 实现审核模型与本地规则**

`ModerationVerdict.java`：

```java
package com.king.ai.compliance;

public enum ModerationVerdict {
    ALLOW, REVIEW, BLOCK
}
```

`ModerationDecision.java`：

```java
package com.king.ai.compliance;

public record ModerationDecision(ModerationVerdict verdict, String category, String reason) {

    public static ModerationDecision allow() {
        return new ModerationDecision(ModerationVerdict.ALLOW, "NONE", "");
    }

    public boolean blocking(boolean publicChat) {
        return verdict == ModerationVerdict.BLOCK
                || (publicChat && verdict == ModerationVerdict.REVIEW);
    }
}
```

`ModerationContext.java`：

```java
package com.king.ai.compliance;

public record ModerationContext(Long spaceId, boolean publicChat, String surface) {
}
```

`ModerationHit.java`：

```java
package com.king.ai.compliance;

public record ModerationHit(String category, int start, int end) {
}
```

`ModerationScan.java`：

```java
package com.king.ai.compliance;

import java.util.List;

public record ModerationScan(ModerationDecision decision, List<ModerationHit> hits) {

    public ModerationScan {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static ModerationScan allow() {
        return new ModerationScan(ModerationDecision.allow(), List.of());
    }
}
```

`LocalRuleModerator.java`：

```java
package com.king.ai.compliance;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LocalRuleModerator {

    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern REPEATED = Pattern.compile("(.)\\1{79,}");
    private static final String IGNORABLE = "\u200B\u200C\u200D\uFEFF";
    private static final String SEPARATORS =
            " \t\r\n-_./\\|+*·,，。！？!?：:；;、\"'“”‘’()（）[]【】<>";

    public ModerationScan scan(String text, List<String> blocklist) {
        if (text == null || text.isEmpty()) {
            return ModerationScan.allow();
        }
        Normalized normalized = normalize(text);
        List<ModerationHit> hits = new ArrayList<>();
        for (String term : blocklist) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String needle = normalizeTerm(term);
            if (needle.isEmpty()) {
                continue;
            }
            int index = normalized.compact.indexOf(needle);
            while (index >= 0) {
                int start = normalized.positions.get(index);
                int end = normalized.positions.get(index + needle.length() - 1) + 1;
                hits.add(new ModerationHit("BLOCKLIST", start, end));
                index = normalized.compact.indexOf(needle, index + needle.length());
            }
        }
        if (!hits.isEmpty()) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.BLOCK,
                    "BLOCKLIST", "命中违禁词"), hits);
        }
        Matcher links = URL.matcher(text);
        int count = 0;
        while (links.find()) {
            count++;
        }
        if (count >= 6) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                    "SPAM", "链接数量异常"), List.of());
        }
        if (REPEATED.matcher(text).find()) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                    "FLOOD", "重复字符异常"), List.of());
        }
        return ModerationScan.allow();
    }

    public String mask(String text, ModerationScan scan) {
        if (text == null || scan == null || scan.hits().isEmpty()) {
            return text == null ? "" : text;
        }
        List<ModerationHit> hits = scan.hits().stream()
                .sorted(java.util.Comparator.comparingInt(ModerationHit::start).reversed())
                .toList();
        StringBuilder builder = new StringBuilder(text);
        for (ModerationHit hit : hits) {
            if (hit.start() >= 0 && hit.end() <= builder.length() && hit.start() < hit.end()) {
                builder.replace(hit.start(), hit.end(), "[已屏蔽]");
            }
        }
        return builder.toString();
    }

    private record Normalized(String compact, List<Integer> positions) {
    }

    private static Normalized normalize(String text) {
        StringBuilder compact = new StringBuilder(text.length());
        List<Integer> positions = new ArrayList<>(text.length());
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (normalized.length() != text.length()) {
            // 命中区间最终用于改原串；长度变化时直接用原串，避免下标错位导致漏屏蔽。
            normalized = text;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (IGNORABLE.indexOf(ch) >= 0 || SEPARATORS.indexOf(ch) >= 0) {
                continue;
            }
            compact.append(Character.toLowerCase(ch));
            positions.add(i);
        }
        return new Normalized(compact.toString(), positions);
    }

    private static String normalizeTerm(String term) {
        Normalized normalized = normalize(term);
        return normalized.compact().toLowerCase(Locale.ROOT);
    }
}
```

`ExternalModerationClient.java`：

```java
package com.king.ai.compliance;

public interface ExternalModerationClient {

    ModerationDecision check(String text, ModerationContext context);
}
```

`ModerationUnavailableException.java`（同文件包，单独文件）：

```java
package com.king.ai.compliance;

public class ModerationUnavailableException extends RuntimeException {

    public ModerationUnavailableException(String message) {
        super(message);
    }

    public ModerationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`HttpExternalModerationClient.java`：

```java
package com.king.ai.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

@Slf4j
@Component
public class HttpExternalModerationClient implements ExternalModerationClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final OutboundUrlGuard outboundUrlGuard;

    public HttpExternalModerationClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                                        KingAiProperties properties, OutboundUrlGuard outboundUrlGuard) {
        this.httpClient = httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(Math.max(500,
                        properties.getCompliance().getModeration().getExternalTimeoutMs())))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    @Override
    public ModerationDecision check(String text, ModerationContext context) {
        String url = properties.getCompliance().getModeration().getExternalUrl();
        if (Texts.isBlank(url)) {
            throw new ModerationUnavailableException("未配置外部审核服务");
        }
        String violation = outboundUrlGuard.violation(url);
        if (violation != null) {
            throw new ModerationUnavailableException(violation);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", text);
        payload.put("spaceId", context.spaceId());
        payload.put("surface", context.surface());
        payload.put("publicChat", context.publicChat());
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON));
        String token = properties.getCompliance().getModeration().getExternalToken();
        if (!Texts.isBlank(token)) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new ModerationUnavailableException("外部审核服务返回 HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(body.string());
            String action = root.path("action").asText("REVIEW").toUpperCase(Locale.ROOT);
            String category = Texts.blankToDefault(root.path("category").asText(""), "EXTERNAL");
            String reason = Texts.blankToDefault(root.path("reason").asText(""), "外部审核");
            return switch (action) {
                case "ALLOW" -> ModerationDecision.allow();
                case "BLOCK" -> new ModerationDecision(ModerationVerdict.BLOCK, category, reason);
                default -> new ModerationDecision(ModerationVerdict.REVIEW, category, reason);
            };
        } catch (IOException ex) {
            throw new ModerationUnavailableException("外部审核调用失败：" + ex.getMessage(), ex);
        }
    }
}
```

`ModerationService.java`：

```java
package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ModerationService {

    private final LocalRuleModerator local;
    private final KingAiProperties properties;
    private final ExternalModerationClient external;

    public ModerationService(LocalRuleModerator local, KingAiProperties properties,
                             ExternalModerationClient external) {
        this.local = local;
        this.properties = properties;
        this.external = external;
    }

    public ModerationScan scan(String text, ComplianceSettings settings, ModerationContext context) {
        if (settings == null || !settings.moderationEnabled()) {
            return ModerationScan.allow();
        }
        ModerationScan localScan = settings.moderationMode().usesLocal()
                ? local.scan(text, settings.blocklist()) : ModerationScan.allow();
        if (localScan.decision().verdict() == ModerationVerdict.BLOCK) {
            return localScan;
        }
        if (!settings.moderationMode().usesExternal()) {
            return localScan;
        }
        try {
            ModerationDecision decision = external.check(text, context);
            if (decision.verdict() == ModerationVerdict.BLOCK) {
                return new ModerationScan(decision, List.of());
            }
            if (decision.verdict() == ModerationVerdict.REVIEW
                    && localScan.decision().verdict() == ModerationVerdict.ALLOW) {
                return new ModerationScan(decision, List.of());
            }
            return localScan;
        } catch (RuntimeException ex) {
            log.warn("External moderation unavailable: {}", ex.getMessage());
            if (localScan.decision().verdict() != ModerationVerdict.ALLOW) {
                return localScan;
            }
            if (context.publicChat() && properties.getCompliance().getModeration().isFailClosedOnError()) {
                return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                        "MODERATION_UNAVAILABLE", "审核服务不可用"), List.of());
            }
            return localScan;
        }
    }
}
```

`ContentModerationException.java`：

```java
package com.king.ai.compliance;

public class ContentModerationException extends RuntimeException {

    private final String category;

    public ContentModerationException(String category) {
        super("内容审核未通过");
        this.category = category;
    }

    public String category() {
        return category;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=LocalRuleModeratorTest,ModerationServiceTest,HttpExternalModerationClientTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance king-ai-api/src/test/java/com/king/ai/compliance
git commit -m "feat(compliance): add local moderation and external fallback"
```

---

### Task 4: 流式脱敏安全带与输入合规准备

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceStreamGuard.java`
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ChatInputCompliance.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceStreamGuardTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ChatInputComplianceTest.java`

**Interfaces:**
- Produces: `String ComplianceStreamGuard.accept(String delta)`；命中拦截时抛 `ContentModerationException`
- Produces: `String ComplianceStreamGuard.flush()`
- Produces: `void ComplianceStreamGuard.reset()`
- Produces: `Map<String, Integer> ComplianceStreamGuard.countsByCategory()`（只含规则类别与次数）
- Produces: `PreparedInput ChatInputCompliance.prepare(String raw, ComplianceSettings settings, ModerationContext context)`
- Produces: `record PreparedInput(String text, int redactedCount, boolean masked)`

- [ ] **Step 1: 写失败测试**

`ComplianceStreamGuardTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceStreamGuardTest {

    private static final ComplianceSettings PII = new ComplianceSettings(true,
            ModerationMode.OFF, ModerationAction.BLOCK, ModerationAction.BLOCK,
            List.of(), List.of(), 32);

    private final KingAiProperties properties = new KingAiProperties();
    private final RedactionService redaction = new RedactionService();
    private final LocalRuleModerator local = new LocalRuleModerator();
    private final ModerationService moderation = new ModerationService(local, properties,
            (text, context) -> ModerationDecision.allow());

    @Test
    void doesNotLeakPhoneSplitAcrossDeltas() {
        ComplianceStreamGuard guard = guard(PII, false);
        String first = guard.accept("我的手机号是1381234");
        String second = guard.accept("5678，请查收");
        String tail = guard.flush();

        assertThat(first + second + tail).doesNotContain("13812345678");
        assertThat(first + second + tail).contains("[REDACTED:PHONE]");
        assertThat(guard.countsByCategory()).containsEntry("PHONE", 1);
    }

    @Test
    void blocksTermSplitAcrossDeltasWhenOutputActionBlocks() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of("违禁词"),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, true);

        assertThatThrownBy(() -> {
            guard.accept("这里有违");
            guard.accept("禁词出现");
        }).isInstanceOf(ContentModerationException.class)
                .extracting("category").isEqualTo("BLOCKLIST");
    }

    @Test
    void blocksReviewSpamForPublicChatEvenWhenOutputActionWarns() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.WARN, ModerationAction.WARN, List.of(),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, true);
        String spam = "https://a.com/1 https://a.com/2 https://a.com/3 "
                + "https://a.com/4 https://a.com/5 https://a.com/6";

        assertThatThrownBy(() -> guard.accept(spam))
                .isInstanceOf(ContentModerationException.class)
                .extracting("category").isEqualTo("SPAM");
    }

    @Test
    void warnMasksTermAndKeepsStreaming() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.BLOCK, ModerationAction.WARN, List.of("违禁词"),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, false);

        String text = guard.accept("这里有违禁词出现") + guard.flush();

        assertThat(text).doesNotContain("违禁词");
        assertThat(text).contains("[已屏蔽]");
    }

    @Test
    void resetDiscardsBufferedText() {
        ComplianceStreamGuard guard = guard(PII, false);
        guard.accept("1381234");
        guard.reset();

        assertThat(guard.flush()).isEmpty();
        assertThat(guard.countsByCategory()).isEmpty();
    }

    @Test
    void customPatternLongerThanWindowIsStillRedactedBeforeFlush() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.OFF,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of(),
                List.of(Pattern.compile("SECRET-[A-Z0-9]{40}")), 32);
        ComplianceStreamGuard guard = guard(settings, false);

        String text = guard.accept("token SECRET-") + guard.accept("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCD")
                + guard.flush();

        assertThat(text).doesNotContain("SECRET-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCD");
        assertThat(text).contains("[REDACTED:CUSTOM]");
    }

    private ComplianceStreamGuard guard(ComplianceSettings settings, boolean block) {
        return new ComplianceStreamGuard(redaction, moderation, settings,
                new ModerationContext(1L, block, "output"), block);
    }
}
```

说明：`customPatternLongerThanWindowIsStillRedactedBeforeFlush` 只验证 `flush` 后不泄露；跨 delta 的长自定义规则依赖安全带长度，生产需把 `stream-holdback-chars` 配置为不小于最长敏感串长度。该边界写入部署文档。

`ChatInputComplianceTest.java`：

```java
package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatInputComplianceTest {

    private final KingAiProperties properties = new KingAiProperties();
    private final ModerationService moderation = new ModerationService(
            new LocalRuleModerator(), properties, (text, context) -> ModerationDecision.allow());
    private final ChatInputCompliance input = new ChatInputCompliance(
            new RedactionService(), moderation, new LocalRuleModerator());

    private ComplianceSettings settings(ModerationMode mode, ModerationAction action) {
        return new ComplianceSettings(true, mode, action, action, List.of("违禁词"),
                List.of(), 128);
    }

    @Test
    void redactsPiiBeforeReturning() {
        PreparedInput prepared = input.prepare("电话13812345678",
                settings(ModerationMode.OFF, ModerationAction.BLOCK),
                new ModerationContext(1L, false, "input"));

        assertThat(prepared.text()).contains("[REDACTED:PHONE]");
        assertThat(prepared.text()).doesNotContain("13812345678");
        assertThat(prepared.redactedCount()).isEqualTo(1);
    }

    @Test
    void blocksConfiguredTermForPublicChat() {
        assertThatThrownBy(() -> input.prepare("违禁词",
                settings(ModerationMode.LOCAL, ModerationAction.BLOCK),
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ContentModerationException.class);
    }

    @Test
    void warnMasksConfiguredTermForInternalChat() {
        PreparedInput prepared = input.prepare("包含违禁词的内容",
                settings(ModerationMode.LOCAL, ModerationAction.WARN),
                new ModerationContext(1L, false, "input"));

        assertThat(prepared.text()).isEqualTo("包含[已屏蔽]的内容");
        assertThat(prepared.masked()).isTrue();
    }

    @Test
    void reviewBlocksPublicChatEvenWhenActionIsWarn() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.WARN, ModerationAction.WARN, List.of(),
                List.of(), 128);
        String spam = "https://a.com/1 https://a.com/2 https://a.com/3 "
                + "https://a.com/4 https://a.com/5 https://a.com/6";

        assertThatThrownBy(() -> input.prepare(spam, settings,
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ContentModerationException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=ComplianceStreamGuardTest,ChatInputComplianceTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现安全带与输入准备**

`ComplianceStreamGuard.java`：

```java
package com.king.ai.compliance;

import java.util.LinkedHashMap;
import java.util.Map;

public class ComplianceStreamGuard {

    private final RedactionService redactionService;
    private final ModerationService moderationService;
    private final ComplianceSettings settings;
    private final ModerationContext context;
    private final boolean publicChat;
    private final int holdback;
    private final StringBuilder pending = new StringBuilder();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public ComplianceStreamGuard(RedactionService redactionService, ModerationService moderationService,
                                 ComplianceSettings settings, ModerationContext context, boolean publicChat) {
        this.redactionService = redactionService;
        this.moderationService = moderationService;
        this.settings = settings;
        this.context = context;
        this.publicChat = publicChat;
        this.holdback = Math.max(32, settings.streamHoldbackChars());
    }

    public String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        pending.append(delta);
        // 先脱敏再送审：EXTERNAL/HYBRID 模式会把文本发给外部审核服务，原文中的
        // PII 不能因此离开部署边界；审核命中区间也统一以脱敏后的文本为准。
        String scanText = redactionService.redact(pending.toString(), settings).text();
        ModerationScan scan = moderationService.scan(scanText, settings, context);
        // 公开对话必须 fail-closed：REVIEW 不能因为 outputAction=WARN 而放行。
        if (scan.decision().blocking(publicChat)
                && (settings.outputAction() == ModerationAction.BLOCK
                || (publicChat && scan.decision().verdict() == ModerationVerdict.REVIEW))) {
            throw new ContentModerationException(scan.decision().category());
        }
        if (!scan.hits().isEmpty() && settings.outputAction() == ModerationAction.WARN) {
            String masked = moderationService.mask(scanText, scan);
            pending.setLength(0);
            pending.append(masked);
        }
        if (pending.length() <= holdback) {
            return "";
        }
        int emitEnd = pending.length() - holdback;
        String window = pending.substring(0, emitEnd);
        RedactionResult result = redactionService.redact(window, settings);
        for (RedactionHit hit : result.hits()) {
            counts.merge(hit.category(), 1, Integer::sum);
        }
        pending.delete(0, emitEnd);
        return result.text();
    }

    public String flush() {
        if (pending.length() == 0) {
            return "";
        }
        String scanText = redactionService.redact(pending.toString(), settings).text();
        ModerationScan scan = moderationService.scan(scanText, settings, context);
        if (scan.decision().blocking(publicChat)
                && (settings.outputAction() == ModerationAction.BLOCK
                || (publicChat && scan.decision().verdict() == ModerationVerdict.REVIEW))) {
            throw new ContentModerationException(scan.decision().category());
        }
        String text = pending.toString();
        if (!scan.hits().isEmpty() && settings.outputAction() == ModerationAction.WARN) {
            // 命中区间属于 scanText，必须用同一份文本做替换。
            text = moderationService.mask(scanText, scan);
        }
        RedactionResult result = redactionService.redact(text, settings);
        for (RedactionHit hit : result.hits()) {
            counts.merge(hit.category(), 1, Integer::sum);
        }
        pending.setLength(0);
        return result.text();
    }

    public void reset() {
        pending.setLength(0);
        counts.clear();
    }

    public Map<String, Integer> countsByCategory() {
        return Map.copyOf(counts);
    }
}
```

`ModerationService` 增加 `mask` 方法：

```java
    public String mask(String text, ModerationScan scan) {
        return local.mask(text, scan);
    }
```

`ChatInputCompliance.java`：

```java
package com.king.ai.compliance;

import org.springframework.stereotype.Service;

@Service
public class ChatInputCompliance {

    public record PreparedInput(String text, int redactedCount, boolean masked) {
    }

    private final RedactionService redactionService;
    private final ModerationService moderationService;
    private final LocalRuleModerator localModerator;

    public ChatInputCompliance(RedactionService redactionService, ModerationService moderationService,
                               LocalRuleModerator localModerator) {
        this.redactionService = redactionService;
        this.moderationService = moderationService;
        this.localModerator = localModerator;
    }

    public PreparedInput prepare(String raw, ComplianceSettings settings, ModerationContext context) {
        String text = raw == null ? "" : raw;
        RedactionResult redacted = redactionService.redact(text, settings);
        text = redacted.text();
        ModerationScan scan = moderationService.scan(text, settings, context);
        boolean blocked = scan.decision().blocking(context.publicChat());
        // 公开对话必须 fail-closed：REVIEW 不能因为 inputAction=WARN 而放行。
        if (blocked && (settings.inputAction() == ModerationAction.BLOCK
                || (context.publicChat() && scan.decision().verdict() == ModerationVerdict.REVIEW))) {
            throw new ContentModerationException(scan.decision().category());
        }
        boolean masked = false;
        if (!scan.hits().isEmpty()) {
            text = localModerator.mask(text, scan);
            masked = true;
        }
        return new PreparedInput(text, redacted.redactedCount(), masked);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=ComplianceStreamGuardTest,ChatInputComplianceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/ComplianceStreamGuard.java king-ai-api/src/main/java/com/king/ai/compliance/ChatInputCompliance.java king-ai-api/src/test/java/com/king/ai/compliance
git commit -m "feat(compliance): add streaming safety window and input preparation"
```

---

### Task 5: 接入 ChatRuntime（输入、检索、工具、输出、落库）

**Files:**
- Modify: `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ChatRuntimeComplianceWiringTest.java`

**Interfaces:**
- Consumes: Task 1–4 的全部类型
- Produces: SSE `degraded` 事件 `{"reason":"pii_redacted","surface":"input|output","count":N}`
- Produces: SSE `degraded` 事件 `{"reason":"moderation_warn","surface":"input|output","category":"..."}`
- Produces: 指标 `king.ai.compliance.redactions{surface,category}`、`king.ai.compliance.moderation{surface,action,category}`

- [ ] **Step 1: 写失败测试**

`ChatRuntimeComplianceWiringTest.java` 用源码契约测试锁定关键接线，避免引入 15 个依赖的上下文：

```java
package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRuntimeComplianceWiringTest {

    private static final Path RUNTIME = Path.of(
            "src/main/java/com/king/ai/chat/ChatRuntime.java");

    @Test
    void runtimeChecksInputBeforeCreatingEmitter() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("chatInputCompliance.prepare(");
        assertThat(source.indexOf("chatInputCompliance.prepare("))
                .isLessThan(source.indexOf("SseEmitter emitter = new SseEmitter"));
    }

    @Test
    void runtimeGuardsStreamingContentAndReasoning() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("contentGuard.accept(delta)");
        assertThat(source).contains("reasoningGuard.accept(delta)");
        assertThat(source).contains("contentGuard.flush()");
        assertThat(source).contains("reasoningGuard.flush()");
    }

    @Test
    void runtimePersistsOnlyGuardedText() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("content.append(safe)");
        assertThat(source).doesNotContain("content.append(delta)");
        assertThat(source).contains("auditLogService.recordEvent(");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=ChatRuntimeComplianceWiringTest test`

Expected: FAIL，源码中没有合规接线。

- [ ] **Step 3: 实现接线**

3.1 注入依赖（字段区）：

```java
    private final ComplianceSettingsService complianceSettingsService;
    private final ChatInputCompliance chatInputCompliance;
    private final RedactionService redactionService;
    private final ModerationService moderationService;
```

同时加入导入（`ChatRuntime` 位于 `com.king.ai.chat`，不能省略）：

```java
import com.king.ai.compliance.ChatInputCompliance;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.compliance.ComplianceStreamGuard;
import com.king.ai.compliance.ContentModerationException;
import com.king.ai.compliance.ModerationContext;
import com.king.ai.compliance.ModerationScan;
import com.king.ai.compliance.RedactionResult;
import com.king.ai.compliance.RedactionService;
```

3.2 `start()` 在长度校验之后、`resolveChat` 之前插入：

```java
        ComplianceSettings compliance = complianceSettingsService.effective(spaceId);
        boolean publicChat = "PUBLIC".equalsIgnoreCase(Texts.blankToDefault(sourceType, ""));
        ModerationContext inputContext = new ModerationContext(spaceId, publicChat, "input");
        ChatInputCompliance.PreparedInput prepared;
        try {
            prepared = chatInputCompliance.prepare(message, compliance, inputContext);
        } catch (ContentModerationException ex) {
            ChatAuditContext audit = captureAuditContext();
            auditLogService.recordEvent(spaceId, actor.userId(), actor.displayName(),
                    "chat.moderation_blocked", "chat", null, AuditLogService.STATUS_DENIED,
                    "surface=input, category=" + ex.category(),
                    audit.ip(), audit.userAgent(), audit.requestId());
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "input",
                    "action", "block", "category", ex.category()).increment();
            throw BizException.badRequest("输入包含不合规内容，已被拦截");
        }
        String safeMessage = prepared.text();
        if (prepared.redactedCount() > 0) {
            meterRegistry.counter("king.ai.compliance.redactions", "surface", "input",
                    "category", "any").increment(prepared.redactedCount());
        }
        if (prepared.masked()) {
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "input",
                    "action", "warn", "category", "BLOCKLIST").increment();
        }
```

将线程池提交改为：

```java
            chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
                    sessionId, safeMessage, sourceType, locked, mdcContext, auditContext,
                    compliance, publicChat, prepared.redactedCount(), prepared.masked()));
```

`run` 签名追加：

```java
                     Map<String, String> mdcContext, ChatAuditContext auditContext,
                     ComplianceSettings compliance, boolean publicChat,
                     int inputRedactions, boolean inputMasked) {
```

3.3 `run()` 在 `send(meta)` 之后：

```java
            if (inputRedactions > 0) {
                send(emitter, "degraded", Map.of("reason", "pii_redacted",
                        "surface", "input", "count", inputRedactions));
            }
            if (inputMasked) {
                send(emitter, "degraded", Map.of("reason", "moderation_warn",
                        "surface", "input", "category", "BLOCKLIST"));
            }
```

3.4 引用脱敏：`citationPayload` 改用安全片段。循环改为：

```java
            for (int i = 0; i < retrieved.size(); i++) {
                RetrievedChunk chunk = retrieved.get(i);
                RedactionResult safeSnippet = redactionService.redact(chunk.content(), compliance);
                Map<String, Object> citation = citationPayload(i + 1, chunk, safeSnippet.text());
                citations.add(citation);
                send(emitter, "citation", citation);
            }
```

方法签名改为：

```java
    private Map<String, Object> citationPayload(int index, RetrievedChunk chunk, String snippet) {
```

并把 `payload.put("snippet", cap(chunk.content(), 600));` 改为 `payload.put("snippet", cap(snippet, 600));`。

3.5 工具参数与结果脱敏。在 `BoundTool tool = toolIndex.get(call.name());` 之后插入：

```java
                    RedactionResult argRedaction = redactionService.redact(call.arguments(), compliance);
                    String toolArguments = argRedaction.text();
```

把 `callPayload.put("arguments", cap(call.arguments(), 8000))` 和
`traceItem.put("arguments", cap(call.arguments(), 8000))` 改为
`cap(toolArguments, 8000)`；`executeTool(...)` 仍接收原始 `call`（工具需要精确入参），但原始参数不得进入 SSE 或 trace。参数脱敏命中数并入 `surface=tool` 的指标（与结果脱敏共用一次计数即可）。

结果侧在 `ToolOutcome outcome = ...` 之后插入：

```java
                    String toolText = outcome.text();
                    RedactionResult toolRedaction = redactionService.redact(toolText, compliance);
                    toolText = toolRedaction.text();
                    ModerationScan toolScan = moderationService.scan(toolText, compliance,
                            new ModerationContext(spaceId, publicChat, "tool"));
                    if (toolScan.decision().blocking(publicChat)) {
                        throw new ContentModerationException(toolScan.decision().category());
                    }
                    if (!argRedaction.hits().isEmpty() || !toolRedaction.hits().isEmpty()) {
                        meterRegistry.counter("king.ai.compliance.redactions", "surface", "tool",
                                "category", "any").increment(
                                argRedaction.redactedCount() + toolRedaction.redactedCount());
                    }
```

把后续使用的 `outcome.text()` 全部替换为 `toolText`。当前源码共 3 处：`traceItem.put("result", cap(outcome.text(), ...))`、`resultPayload.put("result", cap(outcome.text(), 4000))`、`LlmMessage.tool(... cap(outcome.text(), ...))`；改完在文件中搜索 `outcome.text()`，必须只剩 `String toolText = outcome.text();` 这一处。

3.6 创建输出安全带。必须放在 `run()` 的 `try` **之前**（与 `content`、`reasoning` 同级），因为 lambda 只能捕获 effectively-final 变量，而 `finally` 也需要访问：

```java
        ComplianceStreamGuard contentGuard = new ComplianceStreamGuard(redactionService,
                moderationService, compliance,
                new ModerationContext(spaceId, publicChat, "output"), publicChat);
        ComplianceStreamGuard reasoningGuard = new ComplianceStreamGuard(redactionService,
                moderationService, compliance,
                new ModerationContext(spaceId, publicChat, "reasoning"), publicChat);

        try {
```

`streamChatWithRetry` 与 `streamWithRetry` 各追加两个参数：

```java
            ComplianceStreamGuard contentGuard, ComplianceStreamGuard reasoningGuard,
```

调用点同步传入。

3.7 回调改为：

```java
                            @Override
                            public void onReasoning(String delta) {
                                if (Texts.isBlank(delta)) {
                                    return;
                                }
                                String safe = reasoningGuard.accept(delta);
                                if (!safe.isEmpty()) {
                                    reasoning.append(safe);
                                    send(emitter, "reasoning", Map.of("delta", safe));
                                    maybeFlush(progressMessage, content, reasoning, trace, citations,
                                            lastFlush, false);
                                }
                            }

                            @Override
                            public void onContent(String delta) {
                                if (Texts.isBlank(delta)) {
                                    return;
                                }
                                String safe = contentGuard.accept(delta);
                                if (!safe.isEmpty()) {
                                    content.append(safe);
                                    send(emitter, "content", Map.of("delta", safe));
                                    maybeFlush(progressMessage, content, reasoning, trace, citations,
                                            lastFlush, false);
                                }
                            }
```

3.8 在 `run()` 的工具轮次循环**结束后**、原有 `maybeFlush(..., true)` 之前 flush 安全带：

```java
            // 循环结束后再 flush：轮次之间不能冲刷，否则跨轮次的手机号/身份证号
            // 会被拆成两段分别输出，脱敏窗口失效。
            String contentTail = contentGuard.flush();
            if (!contentTail.isEmpty()) {
                content.append(contentTail);
                send(emitter, "content", Map.of("delta", contentTail));
            }
            String reasoningTail = reasoningGuard.flush();
            if (!reasoningTail.isEmpty()) {
                reasoning.append(reasoningTail);
                send(emitter, "reasoning", Map.of("delta", reasoningTail));
            }
            maybeFlush(progressMessage, content, reasoning, trace, citations, lastFlush, true);
```

不要把这四行放进 `streamWithRetry`：该方法每轮工具调用都会返回，在其中 flush 会破坏 holdback 的跨轮次窗口；同时 `ContentModerationException` 应直接冒泡到 `run()` 的捕获分支，而不是被重试逻辑当作模型错误处理。

重试分支在 `content.setLength(contentMark); reasoning.setLength(reasoningMark);` 后追加：

```java
                    contentGuard.reset();
                    reasoningGuard.reset();
```

3.9 `run()` 捕获输出拦截，放在 `catch (LlmCancelledException ex)` 之后、`catch (Throwable ex)` 之前：

```java
        } catch (ContentModerationException ex) {
            status = ChatService.STATUS_FAILED;
            errorMessage = "回复包含不合规内容，已终止生成";
            auditLogService.recordEvent(spaceId, actor.userId(), actor.displayName(),
                    "chat.moderation_blocked", "chat",
                    session == null ? null : String.valueOf(session.getId()),
                    AuditLogService.STATUS_DENIED,
                    "surface=output, category=" + ex.category(),
                    auditContext.ip(), auditContext.userAgent(), auditContext.requestId());
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "output",
                    "action", "block", "category", ex.category()).increment();
            sendQuietly(emitter, "error", Map.of("message", errorMessage));
```

3.10 finally 中，在 `recordChatMetrics` 之前输出脱敏与告警计数：

```java
            Map<String, Integer> redactions = new java.util.LinkedHashMap<>();
            redactions.putAll(contentGuard.countsByCategory());
            reasoningGuard.countsByCategory().forEach((key, value) ->
                    redactions.merge(key, value, Integer::sum));
            redactions.forEach((category, count) ->
                    meterRegistry.counter("king.ai.compliance.redactions",
                            "surface", "output", "category", category).increment(count));
```

若 `redactions` 非空，在 `finally` 中 `if (assistant != null) {` 这一整块之前发送一次（保证早于 `usage`/`done`）：

```java
                if (!redactions.isEmpty()) {
                    sendQuietly(emitter, "degraded", Map.of("reason", "pii_redacted",
                            "surface", "output",
                            "count", redactions.values().stream().mapToInt(Integer::intValue).sum()));
                }
```

`contentGuard` / `reasoningGuard` 按 3.6 在 `try` 外声明并初始化；不要先赋 `null` 再在 `try` 内赋值，否则变量不再是 effectively-final，回调 lambda 无法捕获。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=ChatRuntimeComplianceWiringTest,ComplianceStreamGuardTest,ChatInputComplianceTest test`

Expected: PASS。

再跑全量：

Run: `mvn -pl king-ai-api test`

Expected: PASS。

- [ ] **Step 5: 手工验证 SSE 拦截与脱敏**

启动本地：`mvn -pl king-ai-api spring-boot:run -Dspring-boot.run.profiles=local`。

管理员配置（把 `<token>` 替换为登录后 Cookie）：

```bash
curl -X PUT http://localhost:8081/api/compliance-settings \
  -H 'Content-Type: application/json' -H 'X-Space-Id: 1' -H 'Cookie: king_session=<token>' \
  -d '{"piiEnabled":true,"moderationMode":"LOCAL","inputAction":"BLOCK","outputAction":"BLOCK","blocklist":"测试违禁词","customPiiPatterns":""}'
```

发送含手机号的消息，确认 SSE 中没有完整号码且出现 `degraded`；发送含“测试违禁词”的消息，确认收到 400 文本“输入包含不合规内容，已被拦截”。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java king-ai-api/src/test/java/com/king/ai/compliance/ChatRuntimeComplianceWiringTest.java
git commit -m "feat(compliance): enforce redaction and moderation in chat runtime"
```

---

### Task 6: 合规配置管理端点

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettingController.java`
- Test: `king-ai-api/src/test/java/com/king/ai/compliance/ComplianceSettingControllerTest.java`

**Interfaces:**
- Produces: `GET /api/compliance-settings`、`PUT /api/compliance-settings`
- 权限：`@RequireSpaceRole(SpaceRole.ADMIN)`；平台管理员由拦截器自动放行

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.compliance;

import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplianceSettingControllerTest {

    @Test
    void getReturnsServiceView() {
        ComplianceSettingsService service = mock(ComplianceSettingsService.class);
        when(service.view(1L)).thenReturn(new ComplianceSettingView(true, "LOCAL",
                "BLOCK", "WARN", List.of("违禁词"), List.of("EMP-\\d+")));
        ComplianceSettingController controller = new ComplianceSettingController(service);

        ComplianceSettingView view = controller.get(1L).data();

        assertThat(view.moderationMode()).isEqualTo("LOCAL");
        assertThat(view.blocklist()).containsExactly("违禁词");
    }

    @Test
    void updateDelegatesToService() {
        ComplianceSettingsService service = mock(ComplianceSettingsService.class);
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "");
        ComplianceSettingView expected = new ComplianceSettingView(true, "LOCAL",
                "BLOCK", "BLOCK", List.of(), List.of());
        when(service.update(1L, request)).thenReturn(expected);
        ComplianceSettingController controller = new ComplianceSettingController(service);

        assertThat(controller.update(1L, request).data()).isEqualTo(expected);
    }
}
```

`ApiResponse` 是 record，读取 payload 用 `data()`，测试已按此编写，不要给 `ApiResponse` 增加 `getData()`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl king-ai-api -Dtest=ComplianceSettingControllerTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现控制器**

```java
package com.king.ai.compliance;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compliance-settings")
@RequiredArgsConstructor
public class ComplianceSettingController {

    private final ComplianceSettingsService service;

    @GetMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ComplianceSettingView> get() {
        return get(RequestContext.requireSpaceId());
    }

    @PutMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ComplianceSettingView> update(
            @Valid @RequestBody ComplianceSettingUpdateRequest request) {
        return update(RequestContext.requireSpaceId(), request);
    }

    ApiResponse<ComplianceSettingView> get(Long spaceId) {
        return ApiResponse.ok(service.view(spaceId));
    }

    ApiResponse<ComplianceSettingView> update(Long spaceId, ComplianceSettingUpdateRequest request) {
        return ApiResponse.ok(service.update(spaceId, request));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl king-ai-api -Dtest=ComplianceSettingControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/compliance/ComplianceSettingController.java king-ai-api/src/test/java/com/king/ai/compliance/ComplianceSettingControllerTest.java
git commit -m "feat(compliance): expose space compliance settings"
```

---

### Task 7: 文档与全量验证

**Files:**
- Modify: `docs/DEPLOYMENT.md`

- [ ] **Step 1: 更新部署文档**

在 `docs/DEPLOYMENT.md` 的 `## 8. 安全清单` 末尾追加以下内容（实际环境变量名以 `application.yml` 第 53–63 行的 `${...}` 占位符为准）：

```markdown
### 内容合规与 PII 脱敏

- 空间管理员通过 `GET/PUT /api/compliance-settings` 配置；未配置时使用环境变量默认值。
- `KING_AI_PII_DEFAULT_ENABLED` 默认 `true`；`KING_AI_MODERATION_MODE` 默认 `OFF`，生产公开对话建议 `LOCAL` 或 `HYBRID`。
- 外部审核：`KING_AI_MODERATION_URL`、`KING_AI_MODERATION_TOKEN`，超时 2s 后降级本地规则；公开对话在审核不可用时 fail-closed。
- 流式脱敏安全带 `KING_AI_PII_STREAM_HOLDBACK` 默认 128 字符。若自定义正则可能匹配更长的串，必须调大该值；否则跨 delta 的长敏感串存在漏检窗口。更严格场景可把输出改为整段缓冲（后续 P2-2 支持）。
- 审计动作：`chat.moderation_blocked`（输入/输出）、`compliance_setting.update`（由访问审计自动记录）。
- 指标：`king.ai.compliance.redactions{surface,category}`、`king.ai.compliance.moderation{surface,action,category}`。
- 日志与审计不记录命中的明文；排障时只使用规则 ID、类别与计数。
```

- [ ] **Step 2: 全量验证**

Run: `mvn -pl king-ai-api clean test`

Expected: BUILD SUCCESS，所有测试通过。

Run: `mvn -pl king-ai-api -DskipTests package`

Expected: BUILD SUCCESS。

- [ ] **Step 3: 核对验收项**

- [ ] 邮箱、手机号、身份证、银行卡、API Key、Bearer、IPv4、自定义正则均有单元测试。
- [ ] 输入在进入 LLM 前已脱敏，命中记录不落库。
- [ ] 流式输出跨 delta 的手机号不泄露。
- [ ] 公开对话命中违禁词返回 400，输出命中终止并发送 `error`。
- [ ] 外部审核超时降级本地，公开对话 fail-closed。
- [ ] 审计与指标不包含明文。
- [ ] `mvn test` 全绿。

- [ ] **Step 4: 提交**

```bash
git add docs/DEPLOYMENT.md
git commit -m "docs(compliance): document redaction and moderation operations"
```

---

## Execution Notes

- `ChatRuntime` 参数较多，接线时优先新增 `ComplianceSettings`、`boolean publicChat`、`int inputRedactions` 三个参数并在所有调用点同步；不要引入静态状态。
- 安全带只保证长度不超过 `streamHoldbackChars` 的敏感串跨 delta 安全；这是刻意的性能/合规折中，部署文档已写明。
- 外部审核 URL 必须经过 `OutboundUrlGuard`，不得绕过；token 只从环境变量读取。
- `ApiResponse` 是 record，读取 payload 用 `data()`；不要为测试给共享类型增加 getter。
