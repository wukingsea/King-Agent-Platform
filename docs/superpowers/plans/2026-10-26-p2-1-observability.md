# P2-1 可观测性与 SLO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为一次对话建立 HTTP → 鉴权 → 检索 → LLM → 工具 → SSE → 落库的端到端 Trace，补齐按 source/model/status 切分的业务指标、Prometheus 告警、Grafana 仪表盘与可复现的 k6 压测基线。

**Architecture:** 后端引入 Micrometer Tracing OTLP bridge，`RequestIdFilter` 把 `traceId/spanId/requestId` 写入 MDC 与响应头；`ChatTracing` 在 HTTP 线程创建 `chat.request` 根 span，在线程池 worker 中显式打开 scope，并派发 `rag.retrieve`、`llm.chat`、`mcp.tool.call`、`chat.persist` 子 span。指标集中到 `ChatMetrics`、`KnowledgeMetrics`、`McpMetrics`、`RedisProbeMetrics` 四个组件，`ChatRuntime` 只负责调用，避免高基数标签。观测栈使用 OTel Collector + Jaeger + Prometheus + Grafana 的 Compose overlay，生产通过 Helm ConfigMap 注入 OTLP 地址与采样率；压测使用 xk6-sse，基线报告只记录实测值。

**Tech Stack:** Java 17、Spring Boot 3.5.16、Micrometer 1.15.x、Micrometer Tracing 1.5.12、OpenTelemetry BOM 1.49.0、Prometheus 2.55、Grafana 11.3、Jaeger 1.62、OTel Collector Contrib 0.115、k6 + xk6-sse 0.2.0、JUnit 5 + AssertJ + Mockito、Bash 5、jq。

**Spec:** `docs/superpowers/plans/2026-10-26-p2-scale-program.md` §5（P2-1-1 至 P2-1-4）。

## Global Constraints

- 执行顺序固定为 G0-1/G0-2 → G0-3/G0-4 → G0-5/G0-6 → P2-1。Task 14 的 Helm 部分依赖 G0-5 的 `deploy/helm/king-ai`；若尚未执行 G0-5，先完成 Task 1–13、15 中的 Compose/CI/文档部分，Task 14 标记为未运行，不得伪造 chart 校验结果。
- Trace 属性只允许 ID、模型名、工具名、token 数、状态、耗时；禁止写入 prompt、回答正文、用户名、邮箱、手机号、API Key、Cookie、Authorization。`request.id` 之外不得使用可关联到自然人的标识。
- 指标标签禁止使用 `userId`、`sessionId`、`requestId`、消息 ID；`space` 只用于 `king.ai.chat.active.space`，最多 199 个真实空间，第 200 条序列为 `other`，超出归入 `other`。
- `king.ai.chat.active` 必须保持无标签单序列，供 Helm HPA 的 `king_ai_chat_active` `AverageValue` 使用；按空间拆分另用 `king.ai.chat.active.space{space}`。
- OTel 导出默认关闭（`management.otlp.tracing.export.enabled=false`），避免本地/测试无 collector 时产生连接错误；本地采样率 `1.0`，生产默认 `0.1`，由 `KING_AI_TRACE_SAMPLE` 覆盖。
- 所有新指标必须能用 `SimpleMeterRegistry` 单测，不依赖 Prometheus/Grafana/collector；所有 Trace 单测使用 `micrometer-tracing-test` 的 `SimpleTracer`。
- Task 1–9 每个任务结束运行 `mvn -pl king-ai-api test`；Task 10–15 至少运行对应脚本，最终在 Task 15 运行一次完整后端测试。新增 shell 脚本至少通过 `bash -n`；若本机有 `shellcheck` 必须通过，本机没有则记录 `NOT RUN`，Task 15 的 CI job 必须用 `shellcheck --severity=error` 校验。Prometheus 规则通过 `promtool check rules`；Grafana JSON 通过 `jq` 校验。
- 压测基线不得估算或编造。当前 `chatExecutor` 为 core 4 / max 16 / queue 100，1000 并发 SSE 预计大量拒绝；基线报告必须如实记录瓶颈，并把 1000 并发 <1% 错误率列为 P2-2/P2-3 的前置条件，不能在本计划中宣称已达标。
- 版本控制前置条件：`git rev-parse --is-inside-work-tree` 失败时先 `git init`，`git add -A`，提交 `chore: baseline before P2-1 observability`。不得提交 `.env`、真实 Token、压测账号密码。
- 本计划不新增数据库迁移，不修改 V1–V11。

---

## File Structure

**Create**
- `king-ai-api/src/main/java/com/king/ai/observability/ChatTracing.java`
- `king-ai-api/src/main/java/com/king/ai/observability/ChatMetrics.java`
- `king-ai-api/src/main/java/com/king/ai/observability/KnowledgeMetrics.java`
- `king-ai-api/src/main/java/com/king/ai/observability/McpMetrics.java`
- `king-ai-api/src/main/java/com/king/ai/observability/RedisProbeMetrics.java`
- `king-ai-api/src/main/java/com/king/ai/common/web/SseReconnectFilter.java`
- `king-ai-api/src/main/java/com/king/ai/knowledge/QueueStats.java`
- `king-ai-api/src/test/java/com/king/ai/config/TracingConfigTest.java`
- `king-ai-api/src/test/java/com/king/ai/observability/ChatTracingTest.java`
- `king-ai-api/src/test/java/com/king/ai/observability/ChatMetricsTest.java`
- `king-ai-api/src/test/java/com/king/ai/observability/KnowledgeMetricsTest.java`
- `king-ai-api/src/test/java/com/king/ai/observability/McpMetricsTest.java`
- `king-ai-api/src/test/java/com/king/ai/observability/RedisProbeMetricsTest.java`
- `king-ai-api/src/test/java/com/king/ai/common/web/RequestIdFilterTest.java`
- `king-ai-api/src/test/java/com/king/ai/common/web/SseReconnectFilterTest.java`
- `king-ai-api/src/test/java/com/king/ai/knowledge/KnowledgeIndexQueueStatsTest.java`
- `king-ai-api/src/test/java/com/king/ai/knowledge/vector/MilvusMetricsTest.java`
- `king-ai-api/src/test/java/com/king/ai/audit/AuditLogServiceMetricsTest.java`
- `deploy/observability/prometheus/prometheus.yml`
- `deploy/observability/prometheus/alerts.yml`
- `deploy/observability/prometheus/tests/verify.sh`
- `deploy/observability/grafana/provisioning/datasources/prometheus.yml`
- `deploy/observability/grafana/provisioning/dashboards/dashboards.yml`
- `deploy/observability/grafana/dashboards/chat-overview.json`
- `deploy/observability/grafana/dashboards/rag-knowledge.json`
- `deploy/observability/grafana/dashboards/platform-health.json`
- `deploy/observability/grafana/validate.sh`
- `deploy/observability/docker-compose.yml`
- `deploy/observability/otel-collector/config.yml`
- `deploy/observability/tests/verify.sh`
- `docs/operations/observability.md`
- `loadtest/k6/lib/api.js`
- `loadtest/k6/chat-sse.js`
- `loadtest/k6/rag-query.js`
- `loadtest/build-k6.sh`
- `loadtest/render-baseline.sh`
- `loadtest/tests/static_test.sh`
- `loadtest/.gitignore`
- `loadtest/README.md`

**Modify**
- `king-ai-api/pom.xml`
- `king-ai-api/src/main/resources/application.yml`
- `king-ai-api/src/main/resources/application-prod.yml`
- `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- `king-ai-api/src/main/java/com/king/ai/common/web/RequestIdFilter.java`
- `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- `king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexWorker.java`
- `king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexQueue.java`
- `king-ai-api/src/main/java/com/king/ai/knowledge/vector/MilvusVectorStore.java`
- `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- `docs/DEPLOYMENT.md`
- `.env.example`
- `.github/workflows/ci.yml`
- `deploy/helm/king-ai/values.yaml`（G0-5 产物）
- `deploy/helm/king-ai/values.schema.json`（G0-5 产物）
- `deploy/helm/king-ai/templates/configmap.yaml`（G0-5 产物）
- `deploy/helm/king-ai/templates/deployment-api.yaml`（G0-5 产物）
- `deploy/helm/king-ai/README.md`（G0-5 产物）
- `deploy/helm/king-ai/tests/render_test.sh`（G0-5 产物）
- `loadtest/baselines/*.md`（执行 Task 12 后按实测生成，不预填估算值）

---

### Task 1: OpenTelemetry 依赖、配置与本地/生产采样

**Files:**
- Modify: `king-ai-api/pom.xml`
- Modify: `king-ai-api/src/main/resources/application.yml`
- Modify: `king-ai-api/src/main/resources/application-prod.yml`
- Test: `king-ai-api/src/test/java/com/king/ai/config/TracingConfigTest.java`

**Interfaces:**
- Produces: classpath 上存在 `io.micrometer.tracing.Tracer`（Boot 自动配置）与 `io.opentelemetry.exporter.otlp` 实现。
- Produces 配置键：`management.tracing.enabled`、`management.tracing.sampling.probability`、`management.otlp.tracing.endpoint`、`management.otlp.tracing.export.enabled`。
- 后续 Task 2 依赖 `Tracer` bean 与 `micrometer-tracing-test` 测试依赖。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TracingConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void localConfigSamplesEveryTraceButDoesNotExportByDefault() throws IOException {
        PropertySource<?> source = load("application", "application.yml");

        assertThat(source.getProperty("management.tracing.enabled")).isEqualTo(true);
        assertThat(source.getProperty("management.tracing.sampling.probability")).isEqualTo(1.0);
        assertThat(source.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(source.getProperty("management.otlp.tracing.export.enabled")).isEqualTo(false);
    }

    @Test
    void productionConfigDefaultsToTenPercentAndEnvOverrides() throws IOException {
        PropertySource<?> source = load("application-prod", "application-prod.yml");

        assertThat(String.valueOf(source.getProperty("management.tracing.sampling.probability")))
                .isEqualTo("${KING_AI_TRACE_SAMPLE:0.1}");
        assertThat(String.valueOf(source.getProperty("management.otlp.tracing.export.enabled")))
                .isEqualTo("${OTEL_TRACING_EXPORT_ENABLED:false}");
    }

    @Test
    void chatHistogramsAreEnabledForTtftAndDuration() throws IOException {
        PropertySource<?> source = load("application", "application.yml");

        assertThat(source.getProperty(
                "management.metrics.distribution.percentiles-histogram.[king.ai.chat.ttft]"))
                .isEqualTo(true);
        assertThat(source.getProperty(
                "management.metrics.distribution.percentiles-histogram.[king.ai.chat.duration]"))
                .isEqualTo(true);
    }

    private PropertySource<?> load(String name, String path) throws IOException {
        return loader.load(name, new ClassPathResource(path)).get(0);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=TracingConfigTest test`

Expected: FAIL，`management.tracing.enabled` 或 OTLP 配置不存在，`chatHistogramsAreEnabledForTtftAndDuration` 断言为 null。

- [ ] **Step 3: 添加依赖与配置**

在 `king-ai-api/pom.xml` 的 `spring-boot-starter-actuator` 之后加入：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

在 `spring-boot-starter-test` 之前加入：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-test</artifactId>
    <scope>test</scope>
</dependency>
```

版本由 Spring Boot 3.5.16 BOM 管理，不写 `<version>`。

`application.yml` 已经有 `management:` 节点（`endpoints`/`endpoint`/`health`/`info`）。**不要新增第二个顶层 `management:` 键**——YAML 重复键会让先出现的整块被覆盖。把下列键合并进现有块，保持原有四个子节点不变：

```yaml
management:
  endpoints:          # 原有，保持不变
    web:
      exposure:
        include: health,info,prometheus
  endpoint:           # 原有，保持不变
    health:
      probes:
        enabled: true
      show-details: never
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db,stateStore
  health:             # 原有，保持不变
    redis:
      enabled: false
  info:               # 原有，保持不变
    env:
      enabled: true
  tracing:            # 新增
    enabled: true
    sampling:
      probability: 1.0
  otlp:               # 新增
    tracing:
      endpoint: http://localhost:4318/v1/traces
      export:
        enabled: false
  metrics:            # 新增
    distribution:
      percentiles-histogram:
        "[king.ai.chat.ttft]": true
        "[king.ai.chat.duration]": true
        "[king.ai.rag.retrieval]": true
        "[king.ai.mcp.latency]": true
        "[king.ai.index.duration]": true
        "[king.ai.milvus.latency]": true
        "[king.ai.redis.probe.latency]": true
      slo:
        "[king.ai.chat.ttft]": 1s,3s,5s,10s
        "[king.ai.chat.duration]": 5s,15s,30s,60s,180s
```

指标名本身含点号，必须用方括号包住 map key，否则 Spring 会把 `king.ai.chat.ttft` 拆成多层嵌套 map，`percentiles-histogram` 里最终没有这个指标。测试读取的扁平化 key 同样是 `...[king.ai.chat.ttft]`。

只替换 `application.yml` 中现有的这一行（`logging.level` 保持原样）：

```yaml
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{requestId:-}] [trace=%X{traceId:-} span=%X{spanId:-}] [%thread] %logger{40} - %msg%n"
```

`application-prod.yml` 同样已有 `management:` 节点（`endpoints`/`endpoint`）。把下列键合并进去，不要重复写顶层键，原有子节点保持原值：

```yaml
management:
  # endpoints / endpoint 保持现有值
  tracing:            # 新增
    enabled: true
    sampling:
      probability: ${KING_AI_TRACE_SAMPLE:0.1}
  otlp:               # 新增
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://otel-collector:4318/v1/traces}
      export:
        enabled: ${OTEL_TRACING_EXPORT_ENABLED:false}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=TracingConfigTest test`

Expected: PASS，3 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/pom.xml king-ai-api/src/main/resources/application.yml \
  king-ai-api/src/main/resources/application-prod.yml \
  king-ai-api/src/test/java/com/king/ai/config/TracingConfigTest.java
git commit -m "feat: add opentelemetry tracing configuration"
```

---

### Task 2: RequestIdFilter 写入 traceId/spanId，ChatTracing 封装

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/observability/ChatTracing.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/common/web/RequestIdFilter.java`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/ChatTracingTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/common/web/RequestIdFilterTest.java`

**Interfaces:**
- Produces `ChatTracing`:
  - `Span start(String name)`
  - `Span start(String name, Span parent)`
  - `Tracer.SpanInScope openScope(Span span)`
  - `void tag(Span span, String key, String value)`
  - `void error(Span span, Throwable error)`
  - `void end(Span span)`
  - `String traceId()` / `String spanId()`，无当前 span 时返回 `null`
- Produces `RequestIdFilter.TRACE_HEADER = "X-Trace-Id"`、`TRACE_ID_MDC_KEY = "traceId"`、`SPAN_ID_MDC_KEY = "spanId"`。
- `RequestIdFilter` 顺序为 `Ordered.HIGHEST_PRECEDENCE + 10`，必须晚于 Boot 的 `ServerHttpObservationFilter`（`HIGHEST_PRECEDENCE + 1`）。
- `ChatTracing.start` 自动从 MDC 读取 `requestId` 并写入 `request.id` span 标签。

- [ ] **Step 1: 写失败测试**

`ChatTracingTest.java`：

```java
package com.king.ai.observability;

import com.king.ai.common.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTracingTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createsChildSpanInSameTraceAndTagsRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-1");
        SimpleTracer tracer = new SimpleTracer();
        ChatTracing tracing = tracing(tracer);

        Span parent = tracing.start("chat.request");
        try (Tracer.SpanInScope scope = tracing.openScope(parent)) {
            Span child = tracing.start("llm.chat");
            tracing.tag(child, "gen_ai.request.model", "gpt-4o");
            tracing.end(child);
            assertThat(tracing.traceId()).isEqualTo(parent.context().traceId());
        }
        tracing.end(parent);

        assertThat(tracer.getSpans()).extracting(SimpleSpan::getName)
                .containsExactlyInAnyOrder("chat.request", "llm.chat");
        SimpleSpan child = tracer.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .findFirst().orElseThrow();
        assertThat(child.getTraceId()).isEqualTo(parent.context().traceId());
        assertThat(child.getTags())
                .containsEntry("gen_ai.request.model", "gpt-4o")
                .containsEntry("request.id", "req-1");
    }

    @Test
    void recordsErrorOnSpan() {
        SimpleTracer tracer = new SimpleTracer();
        ChatTracing tracing = tracing(tracer);
        Span span = tracing.start("chat.persist");

        tracing.error(span, new IllegalStateException("db down"));
        tracing.end(span);

        assertThat(tracer.onlySpan().getError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fallsBackToNoopWhenTracingIsDisabled() {
        ChatTracing tracing = new ChatTracing(new StaticListableBeanFactory().getBeanProvider(Tracer.class));

        Span span = tracing.start("chat.request");
        tracing.tag(span, "k", "v");
        tracing.end(span);

        assertThat(tracing.traceId()).isNull();
        assertThat(tracing.spanId()).isNull();
    }

    private ChatTracing tracing(SimpleTracer tracer) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        return new ChatTracing(factory.getBeanProvider(Tracer.class));
    }
}
```

`RequestIdFilterTest.java`：

```java
package com.king.ai.common.web;

import com.king.ai.observability.ChatTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void writesTraceAndSpanIdsToMdcAndResponseHeader() throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        RequestIdFilter filter = new RequestIdFilter(
                new ChatTracing(factory.getBeanProvider(Tracer.class)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/send");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Span span = tracer.nextSpan().name("http.server.requests").start();

        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY))
                        .isEqualTo(span.context().traceId());
                assertThat(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY))
                        .isEqualTo(span.context().spanId());
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank();
            });
        } finally {
            span.end();
        }

        assertThat(response.getHeader(RequestIdFilter.TRACE_HEADER))
                .isEqualTo(span.context().traceId());
        assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void keepsRequestIdValidationAndGeneratesUuidWhenInvalid() throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        RequestIdFilter filter = new RequestIdFilter(
                new ChatTracing(factory.getBeanProvider(Tracer.class)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        request.addHeader(RequestIdFilter.HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("bad id with spaces");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=ChatTracingTest,RequestIdFilterTest test`

Expected: 编译失败，`ChatTracing`、`TRACE_HEADER`、`TRACE_ID_MDC_KEY` 不存在。

- [ ] **Step 3: 实现 ChatTracing 与 RequestIdFilter**

`ChatTracing.java`：

```java
package com.king.ai.observability;

import com.king.ai.common.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatTracing {

    private final Tracer tracer;

    public ChatTracing(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable(() -> Tracer.NOOP);
    }

    public Span start(String name) {
        Span span = tracer.nextSpan().name(name).start();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            span.tag("request.id", requestId);
        }
        return span;
    }

    public Span start(String name, Span parent) {
        Span span = tracer.nextSpan(parent).name(name).start();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            span.tag("request.id", requestId);
        }
        return span;
    }

    public Tracer.SpanInScope openScope(Span span) {
        return tracer.withSpan(span);
    }

    public void tag(Span span, String key, String value) {
        if (span != null && key != null && value != null) {
            span.tag(key, value);
        }
    }

    public void error(Span span, Throwable error) {
        if (span != null && error != null) {
            span.error(error);
        }
    }

    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    public String traceId() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        return span.context().traceId();
    }

    public String spanId() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        return span.context().spanId();
    }
}
```

`RequestIdFilter.java` 完整替换为：

```java
package com.king.ai.common.web;

import com.king.ai.observability.ChatTracing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_KEY = "requestId";
    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String SPAN_ID_MDC_KEY = "spanId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final ChatTracing tracing;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !VALID_REQUEST_ID.matcher(requestId.trim()).matches()) {
            requestId = UUID.randomUUID().toString();
        } else {
            requestId = requestId.trim();
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);

        String traceId = tracing.traceId();
        String spanId = tracing.spanId();
        if (traceId != null) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            response.setHeader(TRACE_HEADER, traceId);
        }
        if (spanId != null) {
            MDC.put(SPAN_ID_MDC_KEY, spanId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(TRACE_ID_MDC_KEY);
            MDC.remove(SPAN_ID_MDC_KEY);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=ChatTracingTest,RequestIdFilterTest test`

Expected: PASS，5 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/observability/ChatTracing.java \
  king-ai-api/src/main/java/com/king/ai/common/web/RequestIdFilter.java \
  king-ai-api/src/test/java/com/king/ai/observability/ChatTracingTest.java \
  king-ai-api/src/test/java/com/king/ai/common/web/RequestIdFilterTest.java
git commit -m "feat: propagate trace ids into request logs"
```

---

### Task 3: ChatRuntime 端到端 Span

**Files:**
- Modify: `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/ChatTracingTest.java`（Task 2 已覆盖父子关系与错误标记）

**Interfaces:**
- Consumes `ChatTracing`。
- Produces span 名称与属性：
  - `chat.request`：`chat.source`、`chat.space_id`、`gen_ai.agent.id`、`gen_ai.request.model`、`request.id`；结束时 `chat.status`、`chat.duration_ms`、`gen_ai.usage.prompt_tokens`、`gen_ai.usage.completion_tokens`。
  - `rag.retrieve`：`rag.knowledge_base_count`、`rag.role`、`rag.hit_count`。
  - `llm.chat`：`gen_ai.request.model`、`llm.attempt`、`gen_ai.response.finish_reasons`、token 数。
  - `mcp.tool.call`：`mcp.server`、`mcp.tool`、`mcp.result`。
  - `chat.persist`：`chat.message_id`、`chat.status`。
- 所有 span 继承 `chat.request` 的 trace，`request.id` 由 `ChatTracing.start` 自动写入。

- [ ] **Step 1: 记录回归基线**

本任务只做运行期埋点，不新增可脱离 HTTP/线程池运行的单元测试；`ChatTracingTest` 已在 Task 2 覆盖父子 span 与错误标记。先运行现有套件并记录基线：

Run: `mvn -pl king-ai-api test`

Expected: 基线全部 PASS。若此步失败，先修复基线，不得带着失败继续埋点。ChatRuntime 的父子 span 是否真正出现在 Jaeger，只能在 Task 13 启动本地栈后人工核对；配置校验通过不等于已收到 trace。

- [ ] **Step 2: 添加字段与构造器注入**

在 `ChatRuntime` 字段区加入：

```java
private final ChatTracing tracing;
```

导入：

```java
import com.king.ai.observability.ChatTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
```

- [ ] **Step 3: 在 start() 创建 chat.request span**

在配额检查成功之后、`chatExecutor.execute(...)` 之前加入（Task 5 会在配额检查与本步骤之间插入 SSE 准入；准入失败直接返回 503，因此不会创建 span）：

```java
Span chatSpan = tracing.start("chat.request");
tracing.tag(chatSpan, "chat.source", sourceTag(sourceType));
tracing.tag(chatSpan, "chat.space_id", String.valueOf(spaceId));
tracing.tag(chatSpan, "gen_ai.agent.id", String.valueOf(agent.agentId()));
tracing.tag(chatSpan, "gen_ai.request.model", resolved.model().getModel());
```

把 `chatExecutor.execute(...)` 的调用改为传入 `chatSpan`。按当前仓库状态二选一：

G0-1 未执行：

```java
chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
        sessionId, message, sourceType, locked, mdcContext, auditContext, chatSpan));
```

G0-1 已执行（`safeMessage` 与四个合规参数必须保留）：

```java
chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
        sessionId, safeMessage, sourceType, locked, mdcContext, auditContext,
        compliance, publicChat, prepared.redactedCount(), prepared.masked(), chatSpan));
```

**不要**用 `message` 覆盖 G0-1 的 `safeMessage`，否则未脱敏的原文会重新进入 `run()`。

在提交失败分支加入：

```java
tracing.error(chatSpan, ex);
tracing.end(chatSpan);
```

- [ ] **Step 4: 在 run() 打开 span scope 并结束 span**

把 `run` 方法签名改为（在原参数列表末尾追加 `Span chatSpan`）：

```java
private void run(SseEmitter emitter, String requestId, CancellationToken token,
                 ChatActor actor, AgentRuntime agent, ModelRuntimeService.ResolvedModel resolved,
                 Long sessionId, String message, String sourceType, boolean locked,
                 Map<String, String> mdcContext, ChatAuditContext auditContext, Span chatSpan) {
```

若 G0-1 已执行，`run` 还带有 `ComplianceSettings compliance`、`boolean publicChat`、`int inputRedactions`、`boolean inputMasked` 四个参数。保留它们，只在参数列表最末尾追加 `Span chatSpan`。

在方法体开头，把现有的：

```java
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        Long spaceId = actor.spaceId();
        long startedAt = System.currentTimeMillis();
```

改为：

```java
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        Long spaceId = actor.spaceId();
        MDC.put("spaceId", spaceId == null ? "-" : String.valueOf(spaceId));
        long startedAt = System.currentTimeMillis();
```

保持现有局部变量声明不动，只在其后、`try {` 之前插入 scope 打开：

```java
        boolean truncated = false;

        Tracer.SpanInScope spanScope = tracing.openScope(chatSpan);
        try {
```

必须先恢复 `mdcContext`、写入 `spaceId` 再 `openScope`。Boot 的 `Slf4jScopeDecorator` 会在打开 scope 时把 MDC 的 `traceId/spanId` 切到 `chat.request` span；若先 `openScope` 再 `setContextMap(mdcContext)`，会把它们覆盖回 HTTP server span，worker 日志就无法与 `chat.request` 对齐。`spaceId` 只用于日志关联；日志中的 `traceId/spanId/requestId/spaceId` 足以把一次对话与 trace 对齐。

在 `finally` 开头的 `long duration = ...` 之后写入四个完成标签：

```java
            long duration = System.currentTimeMillis() - startedAt;
            tracing.tag(chatSpan, "chat.status", status);
            tracing.tag(chatSpan, "chat.duration_ms", String.valueOf(duration));
            tracing.tag(chatSpan, "gen_ai.usage.prompt_tokens", String.valueOf(promptTokens));
            tracing.tag(chatSpan, "gen_ai.usage.completion_tokens", String.valueOf(completionTokens));
```

在 `finally` 末尾现有的 `MDC.clear();` 之后关闭 scope 并结束 span：

```java
            MDC.clear();
            spanScope.close();
            tracing.end(chatSpan);
```

`spanScope.close()` 必须执行且只执行一次；`tracing.end(chatSpan)` 放在最后，保证前面所有 `tag` 都作用在仍存活的 span 上。

在捕获 `Throwable ex` 的分支中加入：

```java
tracing.error(chatSpan, ex);
```

- [ ] **Step 5: 在 retrieve() 添加 rag.retrieve span**

完整替换 `retrieve` 方法：

```java
private List<RetrievedChunk> retrieve(Long spaceId, List<Long> knowledgeBaseIds, String query,
                                      String role) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
        return List.of();
    }
    long startedAt = System.nanoTime();
    Span span = tracing.start("rag.retrieve");
    tracing.tag(span, "rag.knowledge_base_count", String.valueOf(knowledgeBaseIds.size()));
    tracing.tag(span, "rag.role", role);
    try {
        List<RetrievedChunk> retrieved = knowledgeService.retrieveForAgent(spaceId, knowledgeBaseIds,
                query, properties.getRag().getMaxContextChars(),
                properties.getRag().getMinScore(), role);
        tracing.tag(span, "rag.hit_count", String.valueOf(retrieved.size()));
        return retrieved;
    } catch (RuntimeException ex) {
        tracing.error(span, ex);
        log.warn("Knowledge retrieval failed: {}", ex.getMessage());
        return List.of();
    } finally {
        tracing.end(span);
    }
}
```

`startedAt` 在 Task 6 接入 `KnowledgeMetrics` 时使用；本任务保留该变量，避免 Task 6 再改签名。

- [ ] **Step 6: 在 streamWithRetry() 添加 llm.chat span**

在 `streamWithRetry` 的 `for (int attempt = 0; ...)` 循环体内、`token.throwIfCancelled();` 之后、`int contentMark = ...` 之前加入（取消异常直接抛出，此时尚未创建 span，不会泄漏）：

```java
Span llmSpan = tracing.start("llm.chat");
tracing.tag(llmSpan, "gen_ai.request.model", resolved.model().getModel());
tracing.tag(llmSpan, "llm.attempt", String.valueOf(attempt + 1));
```

把原有 `try { ... }` 改为 `try { ... } catch (LlmException ex) { ... } finally { tracing.end(llmSpan); }`，并在成功返回前加入：

```java
tracing.tag(llmSpan, "gen_ai.response.finish_reasons", result.finishReason());
tracing.tag(llmSpan, "gen_ai.usage.prompt_tokens", String.valueOf(result.promptTokens()));
tracing.tag(llmSpan, "gen_ai.usage.completion_tokens", String.valueOf(result.completionTokens()));
```

在 `catch (LlmException ex)` 第一行加入：

```java
tracing.error(llmSpan, ex);
```

- [ ] **Step 7: 在 MCP 工具调用外加 mcp.tool.call span**

在 `run` 的工具调用循环中，把：

```java
long toolStart = System.currentTimeMillis();
ToolOutcome outcome = executeTool(tool, call, spaceId, actor, agent.toolApprovalMode(),
        emitter, requestId, token);
long toolDuration = System.currentTimeMillis() - toolStart;
```

改为：

```java
long toolStart = System.currentTimeMillis();
Span toolSpan = tool != null && tool.kind() == ToolKind.MCP
        ? tracing.start("mcp.tool.call") : null;
if (toolSpan != null) {
    tracing.tag(toolSpan, "mcp.server", tool.server() == null ? null : tool.server().getName());
    tracing.tag(toolSpan, "mcp.tool", tool.remoteName());
}
ToolOutcome outcome;
try {
    outcome = executeTool(tool, call, spaceId, actor, agent.toolApprovalMode(),
            emitter, requestId, token);
    if (toolSpan != null) {
        tracing.tag(toolSpan, "mcp.result", outcome.error() ? "error" : "success");
    }
} catch (RuntimeException ex) {
    tracing.error(toolSpan, ex);
    throw ex;
} finally {
    tracing.end(toolSpan);
}
long toolDuration = System.currentTimeMillis() - toolStart;
```

- [ ] **Step 8: 在落库处添加 chat.persist span**

把 `run` 的 `finally` 中两段落库代码包在：

```java
Span persistSpan = tracing.start("chat.persist");
tracing.tag(persistSpan, "chat.message_id",
        assistant == null ? null : String.valueOf(assistant.getId()));
tracing.tag(persistSpan, "chat.status", status);
try {
    if (assistant != null) {
        try {
            chatService.finishAssistant(assistant.getId(), content.toString(), reasoning.toString(),
                    toolTraceJson(trace), citationsJson(citations), status,
                    resolved.model().getModel(), promptTokens, completionTokens, duration);
        } catch (RuntimeException ex) {
            tracing.error(persistSpan, ex);
            log.error("Failed to persist assistant message {}", assistant.getId(), ex);
        }
    }
    if (session != null) {
        try {
            chatService.touchSession(session.getId(),
                    (userSaved ? 1 : 0) + (assistant != null ? 1 : 0));
        } catch (RuntimeException ex) {
            tracing.error(persistSpan, ex);
            log.warn("Failed to update chat session {}: {}", session.getId(), ex.getMessage());
        }
    }
} finally {
    tracing.end(persistSpan);
}
```

其余 usage/done/audit/metrics 逻辑保持不变。

- [ ] **Step 9: 运行回归**

Run: `mvn -pl king-ai-api -Dtest=ChatTracingTest,RequestIdFilterTest test`

Expected: PASS。再运行 `mvn -pl king-ai-api test`，Expected: PASS，无新增失败。

- [ ] **Step 10: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java
git commit -m "feat: trace chat retrieval llm tools and persistence"
```

---

### Task 4: ChatMetrics 与 SSE 容量指标

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/observability/ChatMetrics.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java`
- Modify: `king-ai-api/src/main/resources/application.yml`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/ChatMetricsTest.java`

**Interfaces:**
- Produces `ChatMetrics`:
  - `void request(String source)`
  - `void rejected(String reason)`
  - `void activeStarted(Long spaceId)` / `void activeFinished(Long spaceId)`
  - `void toolCall(String source, boolean error)`
  - `TokenTimer startTokenTimer(String model, String source)`
  - `void finished(String source, String status, long durationMs, int promptTokens, int completionTokens, String model)`
  - `boolean tryAcquireSse()` / `void releaseSse()`
  - `void sseReconnect()`
- Produces nested `ChatMetrics.TokenTimer`：`void firstToken()`、`void reset()`。
- 指标名（Micrometer 基础名）：
  - `king.ai.chat.requests{source}`、`.rejected{reason}`、`.active`、`.active.space{space}`、`.tool_calls{source,result}`
  - `king.ai.chat.ttft{model,source}`（timer）、`.duration{source,status}`（timer）、`.tokens{type,model}`（counter）
  - `king.ai.sse.connections`、`.capacity`、`.reconnects`
- `king.ai.chat.active` 无标签；`king.ai.chat.active.space` 最多 199 个真实空间，第 200 条序列固定为 `other`，后续空间全部归入 `other`。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.observability;

import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatMetrics metrics = new ChatMetrics(registry, properties());

    @Test
    void recordsRequestsRejectionsAndTools() {
        metrics.request("admin");
        metrics.rejected("space_quota");
        metrics.toolCall("MCP", true);

        assertThat(registry.get("king.ai.chat.requests").tag("source", "admin")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.rejected").tag("reason", "space_quota")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.tool_calls")
                .tag("source", "MCP").tag("result", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void tracksActiveChatsGloballyAndBySpaceWithCap() {
        metrics.activeStarted(7L);
        metrics.activeStarted(7L);

        assertThat(registry.get("king.ai.chat.active").gauge().value()).isEqualTo(2);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "7")
                .gauge().value()).isEqualTo(2);

        metrics.activeFinished(7L);
        assertThat(registry.get("king.ai.chat.active").gauge().value()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "7")
                .gauge().value()).isEqualTo(1);
    }

    @Test
    void capsSpaceSeriesAndFoldsOverflowIntoOther() {
        for (long space = 1; space <= 199; space++) {
            metrics.activeStarted(space);
        }
        metrics.activeStarted(200L);
        metrics.activeStarted(201L);

        assertThat(registry.get("king.ai.chat.active.space").gauges()).hasSize(200);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "other")
                .gauge().value()).isEqualTo(2);
        assertThat(registry.find("king.ai.chat.active.space").tag("space", "200").gauge())
                .isNull();

        metrics.activeFinished(200L);
        metrics.activeFinished(201L);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "other")
                .gauge().value()).isZero();
        metrics.activeFinished(1L);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "1")
                .gauge().value()).isZero();
    }

    @Test
    void recordsTtftOncePerAttemptAndResetsOnRetry() {
        ChatMetrics.TokenTimer timer = metrics.startTokenTimer("gpt-4o", "admin");
        timer.firstToken();
        timer.firstToken();
        timer.reset();
        timer.firstToken();

        assertThat(registry.get("king.ai.chat.ttft").tag("model", "gpt-4o")
                .tag("source", "admin").timer().count()).isEqualTo(2);
    }

    @Test
    void recordsFinishedDurationAndTokens() {
        metrics.finished("admin", "completed", 1200, 10, 20, "gpt-4o");

        assertThat(registry.get("king.ai.chat.duration").tag("source", "admin")
                .tag("status", "completed").timer().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.tokens").tag("type", "prompt")
                .tag("model", "gpt-4o").counter().count()).isEqualTo(10);
        assertThat(registry.get("king.ai.chat.tokens").tag("type", "completion")
                .tag("model", "gpt-4o").counter().count()).isEqualTo(20);
    }

    @Test
    void enforcesSseCapacity() {
        assertThat(metrics.tryAcquireSse()).isTrue();
        assertThat(metrics.tryAcquireSse()).isTrue();
        assertThat(metrics.tryAcquireSse()).isFalse();
        assertThat(registry.get("king.ai.sse.connections").gauge().value()).isEqualTo(2);

        metrics.releaseSse();
        metrics.releaseSse();
        metrics.releaseSse();
        assertThat(registry.get("king.ai.sse.connections").gauge().value()).isZero();
        metrics.sseReconnect();
        assertThat(registry.get("king.ai.sse.reconnects").counter().count()).isEqualTo(1);
    }

    private KingAiProperties properties() {
        KingAiProperties properties = new KingAiProperties();
        properties.getChat().setSseCapacity(2);
        return properties;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=ChatMetricsTest test`

Expected: 编译失败，`ChatMetrics` 与 `sseCapacity` 不存在。

- [ ] **Step 3: 添加 sseCapacity 配置**

在 `KingAiProperties.Chat` 的 `maxConcurrentPerSpace` 之后加入：

```java
/**
 * Maximum SSE connections accepted by this instance. 0 or a negative value disables admission control.
 */
private int sseCapacity = 200;
```

在 `application.yml` 的 `king-ai.chat` 下加入：

```yaml
    sse-capacity: ${KING_AI_SSE_CAPACITY:200}
```

- [ ] **Step 4: 实现 ChatMetrics**

```java
package com.king.ai.observability;

import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChatMetrics {

    private static final int MAX_SPACE_GAUGES = 200;
    private static final String OTHER_SPACE = "other";

    private final MeterRegistry registry;
    private final int sseCapacity;
    private final Object spaceLock = new Object();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final AtomicInteger activeSse = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> activeBySpace = new ConcurrentHashMap<>();

    public ChatMetrics(MeterRegistry registry, KingAiProperties properties) {
        this.registry = registry;
        this.sseCapacity = properties.getChat().getSseCapacity();
        Gauge.builder("king.ai.chat.active", activeChats, AtomicInteger::get)
                .description("Number of chat generations currently running")
                .register(registry);
        Gauge.builder("king.ai.sse.connections", activeSse, AtomicInteger::get)
                .description("Number of SSE connections currently open")
                .register(registry);
        Gauge.builder("king.ai.sse.capacity", this, value -> value.sseCapacity)
                .description("Configured SSE connection capacity for this instance")
                .register(registry);
    }

    public void request(String source) {
        counter("king.ai.chat.requests", "source", safe(source)).increment();
    }

    public void rejected(String reason) {
        counter("king.ai.chat.rejected", "reason", safe(reason)).increment();
    }

    public void activeStarted(Long spaceId) {
        activeChats.incrementAndGet();
        spaceGauge(spaceId).incrementAndGet();
    }

    public void activeFinished(Long spaceId) {
        activeChats.updateAndGet(value -> Math.max(0, value - 1));
        AtomicInteger gauge = existingSpaceGauge(spaceId);
        if (gauge != null) {
            gauge.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public void toolCall(String source, boolean error) {
        counter("king.ai.chat.tool_calls",
                "source", safe(source),
                "result", error ? "error" : "success").increment();
    }

    public TokenTimer startTokenTimer(String model, String source) {
        return new TokenTimer(safe(model), safe(source));
    }

    public void finished(String source, String status, long durationMs, int promptTokens,
                         int completionTokens, String model) {
        String outcome = status == null ? "unknown" : status.toLowerCase(Locale.ROOT);
        registry.timer("king.ai.chat.duration",
                        "source", safe(source),
                        "status", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if (promptTokens > 0) {
            counter("king.ai.chat.tokens",
                    "type", "prompt", "model", safe(model)).increment(promptTokens);
        }
        if (completionTokens > 0) {
            counter("king.ai.chat.tokens",
                    "type", "completion", "model", safe(model)).increment(completionTokens);
        }
    }

    public boolean tryAcquireSse() {
        if (sseCapacity <= 0) {
            activeSse.incrementAndGet();
            return true;
        }
        while (true) {
            int current = activeSse.get();
            if (current >= sseCapacity) {
                return false;
            }
            if (activeSse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseSse() {
        activeSse.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void sseReconnect() {
        counter("king.ai.sse.reconnects").increment();
    }

    private AtomicInteger spaceGauge(Long spaceId) {
        String key = spaceKey(spaceId);
        AtomicInteger gauge = activeBySpace.get(key);
        if (gauge != null) {
            return gauge;
        }
        synchronized (spaceLock) {
            gauge = activeBySpace.get(key);
            if (gauge != null) {
                return gauge;
            }
            String bucket = activeBySpace.size() < MAX_SPACE_GAUGES - 1 ? key : OTHER_SPACE;
            AtomicInteger created = activeBySpace.get(bucket);
            if (created == null) {
                created = registerSpaceGauge(bucket);
                activeBySpace.put(bucket, created);
            }
            return created;
        }
    }

    private AtomicInteger existingSpaceGauge(Long spaceId) {
        String key = spaceKey(spaceId);
        AtomicInteger exact = activeBySpace.get(key);
        if (exact != null) {
            return exact;
        }
        return activeBySpace.size() >= MAX_SPACE_GAUGES - 1 ? activeBySpace.get(OTHER_SPACE) : null;
    }

    private AtomicInteger registerSpaceGauge(String space) {
        AtomicInteger value = new AtomicInteger();
        Gauge.builder("king.ai.chat.active.space", value, AtomicInteger::get)
                .tag("space", space)
                .description("Active chat generations by space (199 real spaces plus other)")
                .register(registry);
        return value;
    }

    private String spaceKey(Long spaceId) {
        return spaceId == null ? "unknown" : String.valueOf(spaceId);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 100);
    }

    public final class TokenTimer {

        private final String model;
        private final String source;
        private final AtomicBoolean recorded = new AtomicBoolean();
        private volatile long startedAt = System.nanoTime();

        private TokenTimer(String model, String source) {
            this.model = model;
            this.source = source;
        }

        public void reset() {
            startedAt = System.nanoTime();
            recorded.set(false);
        }

        public void firstToken() {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            Timer.builder("king.ai.chat.ttft")
                    .tag("model", model)
                    .tag("source", source)
                    .description("Time from LLM request start to first streamed token")
                    .register(registry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
```

容量判断必须在 `spaceLock` 内完成：`ConcurrentHashMap.size()` 与随后 `put` 之间的竞态会让并发首聊创建超过 200 条序列。`existingSpaceGauge` 对已归入 `other` 的空间返回 `other` gauge，保证开始时记在 `other` 的任务结束时也扣在 `other`；普通空间在启动时已经登记，结束路径按 key 命中同一个 gauge。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=ChatMetricsTest test`

Expected: PASS，6 tests。若 `king.ai.chat.ttft` 计数为 1 而非 2，检查 `reset()` 是否在第二次 `firstToken()` 前调用；若第 200 个空间仍生成独立 `space` 标签，检查 `spaceGauge` 的容量判断是否在 `spaceLock` 内完成。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/observability/ChatMetrics.java \
  king-ai-api/src/main/java/com/king/ai/config/KingAiProperties.java \
  king-ai-api/src/main/resources/application.yml \
  king-ai-api/src/test/java/com/king/ai/observability/ChatMetricsTest.java
git commit -m "feat: add chat sse and token metrics"
```

---

### Task 5: ChatRuntime 指标接线、SSE 准入与重连计数

**Files:**
- Modify: `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- Create: `king-ai-api/src/main/java/com/king/ai/common/web/SseReconnectFilter.java`
- Test: `king-ai-api/src/test/java/com/king/ai/common/web/SseReconnectFilterTest.java`

**Interfaces:**
- Consumes `ChatMetrics`。
- `ChatRuntime.start` 在配额之后调用 `tryAcquireSse()`；容量不足返回 503，并记录 `king.ai.chat.rejected{reason="sse_capacity"}`。
- `ChatRuntime.run` 调用 `activeStarted(spaceId)` / `activeFinished(spaceId)`、`request`、`toolCall`、`finished`。
- `SseReconnectFilter`：仅匹配 POST `/api/chat/send` 与 `/api/public/chat/send`，存在 `Last-Event-ID` 时调用 `sseReconnect()`。
- 删除 `ChatRuntime` 原有的 `@PostConstruct registerMetrics`、`recordChatMetrics`、`activeChats`，以及 `Gauge`/`Timer`/`PostConstruct`/`AtomicInteger` 导入；**保留 `MeterRegistry meterRegistry` 字段与构造器注入**，G0-1 的 `king.ai.compliance.*` 计数器仍使用它。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import com.king.ai.observability.ChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SseReconnectFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatMetrics metrics = new ChatMetrics(registry, new KingAiProperties());
    private final SseReconnectFilter filter = new SseReconnectFilter(metrics);

    @Test
    void countsLastEventIdOnChatSend() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/send");
        request.addHeader("Last-Event-ID", "42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(registry.get("king.ai.sse.reconnects").counter().count()).isEqualTo(1);
    }

    @Test
    void ignoresRequestsWithoutLastEventIdAndOtherPaths() throws Exception {
        MockHttpServletRequest plain = new MockHttpServletRequest("POST", "/api/chat/send");
        filter.doFilter(plain, new MockHttpServletResponse(), (req, res) -> {
        });

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/agents");
        other.addHeader("Last-Event-ID", "42");
        filter.doFilter(other, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(registry.get("king.ai.sse.reconnects").counter().count()).isZero();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=SseReconnectFilterTest test`

Expected: 编译失败，`SseReconnectFilter` 不存在。

- [ ] **Step 3: 实现 SseReconnectFilter**

```java
package com.king.ai.common.web;

import com.king.ai.common.Texts;
import com.king.ai.observability.ChatMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class SseReconnectFilter extends OncePerRequestFilter {

    private static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final Set<String> PATHS = Set.of("/api/chat/send", "/api/public/chat/send");

    private final ChatMetrics chatMetrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!Texts.isBlank(request.getHeader(LAST_EVENT_ID))) {
            chatMetrics.sseReconnect();
        }
        filterChain.doFilter(request, response);
    }
}
```

说明：当前前端使用 `fetch` 读取 POST SSE，不会自动发送 `Last-Event-ID`；该指标在 P2-2 引入可恢复流后才会有非零值。本任务只保证观测契约存在，不能把 0 解释为“没有断线”。

- [ ] **Step 4: ChatRuntime 接入 ChatMetrics**

在字段区加入：

```java
private final ChatMetrics chatMetrics;
```

删除 `AtomicInteger activeChats`、`@PostConstruct registerMetrics()`、`recordChatMetrics(...)` 及 `Gauge`/`Timer`/`PostConstruct`/`AtomicInteger` 导入。保留 `MeterRegistry meterRegistry` 字段：G0-1 在 `start()`/`run()` 中使用它记录 `king.ai.compliance.*`；本任务后聊天业务指标全部走 `ChatMetrics`，但合规计数器仍直接使用该 registry。

把 `start()` 中的：

```java
meterRegistry.counter("king.ai.chat.requests", "source", sourceTag(sourceType)).increment();
```

改为：

```java
chatMetrics.request(sourceTag(sourceType));
```

把配额拒绝分支改为：

```java
chatMetrics.rejected("space_quota");
```

在配额检查成功后、Task 3 创建的 `chatSpan` 之前加入 SSE 准入（顺序必须是：配额 → SSE 准入 → `chatSpan` → `chatExecutor.execute`；准入失败直接 503，不创建 span，也就不会出现只能靠 worker 结束的孤儿 span）：

```java
if (!chatMetrics.tryAcquireSse()) {
    cancellationRegistry.remove(requestId);
    releaseQuota(spaceId, requestId);
    releaseLock(sessionId, requestId, locked);
    chatMetrics.rejected("sse_capacity");
    throw new BizException(503, "当前流式连接已达上限，请稍后重试");
}
```

在创建 `SseEmitter` 之后、注册回调之前，把现有三个回调的重复清理逻辑合并为幂等 cleanup：

```java
AtomicBoolean closed = new AtomicBoolean();
Runnable cleanup = () -> {
    if (!closed.compareAndSet(false, true)) {
        return;
    }
    activeEmitters.remove(requestId);
    token.cancel();
    cancellationRegistry.remove(requestId);
    toolApprovalRegistry.removeRequest(requestId);
    releaseQuota(spaceId, requestId);
    releaseLock(sessionId, requestId, locked);
    chatMetrics.releaseSse();
};
emitter.onCompletion(cleanup);
emitter.onTimeout(() -> {
    cleanup.run();
    emitter.complete();
});
emitter.onError(error -> cleanup.run());
```

若 IDE 未自动补齐，在 `ChatRuntime` 导入区加入 `import java.util.concurrent.atomic.AtomicBoolean;`。

Task 3 已把 `chatSpan` 传给 `run`。把提交调用改为：

G0-1 未执行：

```java
chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
        sessionId, message, sourceType, locked, mdcContext, auditContext, chatSpan, cleanup));
```

G0-1 已执行（保留 `safeMessage` 与四个合规参数）：

```java
chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
        sessionId, safeMessage, sourceType, locked, mdcContext, auditContext,
        compliance, publicChat, prepared.redactedCount(), prepared.masked(),
        chatSpan, cleanup));
```

并给 `run` 签名末尾增加 `Runnable cleanup`：

```java
private void run(SseEmitter emitter, String requestId, CancellationToken token,
                 ChatActor actor, AgentRuntime agent, ModelRuntimeService.ResolvedModel resolved,
                 Long sessionId, String message, String sourceType, boolean locked,
                 Map<String, String> mdcContext, ChatAuditContext auditContext,
                 Span chatSpan, Runnable cleanup) {
```

同样保留 G0-1 的四个合规参数；它们位于 `auditContext` 与 `chatSpan` 之间。提交失败分支改为：

```java
} catch (RuntimeException ex) {
    cleanup.run();
    tracing.error(chatSpan, ex);
    tracing.end(chatSpan);
    log.warn("Chat task rejected: {}", ex.getMessage());
    throw new BizException(503, "对话请求繁忙，请稍后重试");
}
```

在 `run()` 开头 `activeChats.incrementAndGet()` 的位置改为：

```java
chatMetrics.activeStarted(spaceId);
```

把 `run()` 的 `finally` 中原有的五行直接清理：

```java
            activeEmitters.remove(requestId);
            cancellationRegistry.remove(requestId);
            toolApprovalRegistry.removeRequest(requestId);
            releaseQuota(spaceId, requestId);
            releaseLock(sessionId, requestId, locked);
```

替换为：

```java
            cleanup.run();
```

同时把 `activeChats.decrementAndGet();` 改为：

```java
            chatMetrics.activeFinished(spaceId);
```

`emitter.complete()` 触发的 `onCompletion` 与 worker 的 `finally` 谁先执行都只会清理一次；若保留原来的直接调用，`releaseQuota` 会重复扣减空间并发计数，导致配额被低估。

把工具调用计数：

```java
meterRegistry.counter("king.ai.chat.tool_calls",
        "source", toolSource,
        "result", outcome.error() ? "error" : "success").increment();
```

改为：

```java
chatMetrics.toolCall(toolSource, outcome.error());
```

把结尾的：

```java
recordChatMetrics(sourceType, status, duration, promptTokens, completionTokens,
        resolved.model().getModel());
```

改为：

```java
chatMetrics.finished(sourceTag(sourceType), status, duration, promptTokens, completionTokens,
        resolved.model().getModel());
```

TTFT 计时器必须在这条链路上真正创建并打点，否则 `king.ai.chat.ttft` 永远是空的。把 `run()` 中的调用：

```java
LlmResult result = streamChatWithRetry(resolved, messages, allowTools, toolDefinitions,
        agent, emitter, progressMessage, content, reasoning, trace, citations, lastFlush, token);
```

改为多传一个 `source`：

```java
LlmResult result = streamChatWithRetry(resolved, messages, allowTools, toolDefinitions,
        agent, emitter, progressMessage, content, reasoning, trace, citations, lastFlush, token,
        sourceTag(sourceType));
```

把 `streamChatWithRetry` 与 `streamWithRetry` 的签名末尾都加上 `String source`。若 G0-1 已执行，这两个方法已经追加了两个合规参数；`String source` 放在它们之后，并让两处内部调用与 fallback 调用原样传递 `source`。在 `streamWithRetry` 的每次尝试中（`token.throwIfCancelled();` 之后）创建独立计时器，这样重试和降级模型各自统计 TTFT，不会把主模型耗时算到降级模型上：

```java
ChatMetrics.TokenTimer ttft = chatMetrics.startTokenTimer(
        resolved.model().getModel(), source);
```

在两个流式回调中、`Texts.isBlank(delta)` 判断之后调用（`firstToken()` 内部幂等，reasoning 与 content 谁先到都只记一次）：

```java
ttft.firstToken();
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=SseReconnectFilterTest,ChatMetricsTest test`

Expected: PASS，8 tests（`ChatMetricsTest` 6 + `SseReconnectFilterTest` 2）。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java \
  king-ai-api/src/main/java/com/king/ai/common/web/SseReconnectFilter.java \
  king-ai-api/src/test/java/com/king/ai/common/web/SseReconnectFilterTest.java
git commit -m "feat: wire chat and sse metrics into runtime"
```

---

### Task 6: 知识索引、RAG 与队列积压指标

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/observability/KnowledgeMetrics.java`
- Create: `king-ai-api/src/main/java/com/king/ai/knowledge/QueueStats.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexQueue.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexWorker.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/KnowledgeMetricsTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/knowledge/KnowledgeIndexQueueStatsTest.java`

**Interfaces:**
- Produces `KnowledgeMetrics`:
  - `void indexJob(String outcome, long elapsedNanos)`，`outcome="skipped"` 时不记录。
  - `void updateQueue(long pending, long failed, LocalDateTime oldestPendingAt)`
  - `void ragRetrieval(long elapsedNanos, boolean empty, boolean failed)`
  - `void milvusRequest(String operation, String status, long elapsedNanos)`（Task 7 使用）
- Produces `QueueStats(long pending, long failed, LocalDateTime oldestPendingAt)`。
- Produces `KnowledgeIndexQueue.stats()`。
- 指标名（Micrometer 基础名）：
  - `king.ai.index.jobs{outcome}`（counter）、`king.ai.index.duration{outcome}`（timer）
  - `king.ai.index.pending`、`king.ai.index.failed`、`king.ai.index.lag.seconds`（gauge）
  - `king.ai.rag.retrieval{status}`（timer）、`king.ai.rag.empty`（counter）
  - `king.ai.milvus.requests{operation,status}`（counter）、`king.ai.milvus.latency{operation,status}`（timer）
- `king.ai.index.lag.seconds` 按抓取时刻动态计算 `now - oldestPendingAt`，没有 pending 任务时为 0。
- `king.ai.rag.empty` 只在“有知识库但召回为空”时递增；检索异常记 `status="error"`，不重复记空结果。

- [ ] **Step 1: 写失败测试**

`KnowledgeMetricsTest.java`：

```java
package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KnowledgeMetrics metrics = new KnowledgeMetrics(registry);

    @Test
    void recordsIndexJobsButSkipsNoopOutcome() {
        metrics.indexJob("succeeded", 1_000_000L);
        metrics.indexJob("skipped", 1_000_000L);

        assertThat(registry.get("king.ai.index.jobs").tag("outcome", "succeeded")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("king.ai.index.jobs").tag("outcome", "skipped").counter())
                .isNull();
        assertThat(registry.get("king.ai.index.duration").tag("outcome", "succeeded")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void exposesQueueBacklogAndLag() {
        metrics.updateQueue(3, 1, LocalDateTime.now().minusMinutes(5));

        assertThat(registry.get("king.ai.index.pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("king.ai.index.failed").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.index.lag.seconds").gauge().value())
                .isBetween(290.0, 310.0);

        metrics.updateQueue(0, 0, null);
        assertThat(registry.get("king.ai.index.lag.seconds").gauge().value()).isZero();
    }

    @Test
    void recordsRagOutcomesAndEmptyResults() {
        metrics.ragRetrieval(1_000_000L, false, false);
        metrics.ragRetrieval(2_000_000L, true, false);
        metrics.ragRetrieval(3_000_000L, false, true);

        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "success")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "empty")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "error")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.empty").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsMilvusRequestsAndLatency() {
        metrics.milvusRequest("search", "success", 1_000_000L);
        metrics.milvusRequest("search", "error", 2_000_000L);

        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "error").timer().count())
                .isEqualTo(1L);
    }
}
```

`KnowledgeIndexQueueStatsTest.java`：

```java
package com.king.ai.knowledge;

import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeIndexJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexQueueStatsTest {

    @Mock
    private KnowledgeIndexJobMapper mapper;

    @Mock
    private ApplicationEventPublisher publisher;

    @Test
    void returnsPendingFailedAndOldestNextRun() {
        LocalDateTime oldest = LocalDateTime.now().minusMinutes(5);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setNextRunAt(oldest);
        when(mapper.selectCount(any())).thenReturn(3L, 1L);
        when(mapper.selectOne(any())).thenReturn(job);
        KnowledgeIndexQueue queue = new KnowledgeIndexQueue(mapper, publisher);

        QueueStats stats = queue.stats();

        assertThat(stats.pending()).isEqualTo(3L);
        assertThat(stats.failed()).isEqualTo(1L);
        assertThat(stats.oldestPendingAt()).isEqualTo(oldest);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=KnowledgeMetricsTest,KnowledgeIndexQueueStatsTest test`

Expected: 编译失败，`KnowledgeMetrics`、`QueueStats`、`stats()` 不存在。

- [ ] **Step 3: 实现 QueueStats、队列统计与 KnowledgeMetrics**

`QueueStats.java`：

```java
package com.king.ai.knowledge;

import java.time.LocalDateTime;

public record QueueStats(long pending, long failed, LocalDateTime oldestPendingAt) {
}
```

在 `KnowledgeIndexQueue` 末尾加入：

```java
    public QueueStats stats() {
        Long pending = mapper.selectCount(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getStatus, STATUS_PENDING));
        Long failed = mapper.selectCount(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getStatus, STATUS_FAILED));
        KnowledgeIndexJob oldest = mapper.selectOne(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getStatus, STATUS_PENDING)
                .orderByAsc(KnowledgeIndexJob::getNextRunAt)
                .last("LIMIT 1"));
        return new QueueStats(
                pending == null ? 0L : pending,
                failed == null ? 0L : failed,
                oldest == null ? null : oldest.getNextRunAt());
    }
```

`KnowledgeMetrics.java`：

```java
package com.king.ai.observability;

import com.king.ai.common.Texts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class KnowledgeMetrics {

    private static final String INDEX_JOBS = "king.ai.index.jobs";
    private static final String INDEX_DURATION = "king.ai.index.duration";
    private static final String RAG_RETRIEVAL = "king.ai.rag.retrieval";
    private static final String MILVUS_REQUESTS = "king.ai.milvus.requests";
    private static final String MILVUS_LATENCY = "king.ai.milvus.latency";

    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicReference<LocalDateTime> oldestPendingAt = new AtomicReference<>();

    public KnowledgeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("king.ai.index.pending", pending, AtomicLong::get)
                .description("Knowledge index jobs waiting to run")
                .register(registry);
        Gauge.builder("king.ai.index.failed", failed, AtomicLong::get)
                .description("Knowledge index jobs in the failed state")
                .register(registry);
        Gauge.builder("king.ai.index.lag.seconds", oldestPendingAt, value -> {
                    LocalDateTime oldest = value.get();
                    return oldest == null ? 0.0
                            : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).toSeconds());
                })
                .description("Age in seconds of the oldest pending knowledge index job")
                .register(registry);
    }

    public void indexJob(String outcome, long elapsedNanos) {
        String value = safe(outcome);
        if ("skipped".equals(value)) {
            return;
        }
        registry.counter(INDEX_JOBS, "outcome", value).increment();
        registry.timer(INDEX_DURATION, "outcome", value).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void updateQueue(long pendingCount, long failedCount, LocalDateTime oldestPending) {
        pending.set(Math.max(0L, pendingCount));
        failed.set(Math.max(0L, failedCount));
        oldestPendingAt.set(oldestPending);
    }

    public void ragRetrieval(long elapsedNanos, boolean empty, boolean failed) {
        String status = failed ? "error" : empty ? "empty" : "success";
        registry.timer(RAG_RETRIEVAL, "status", status).record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (empty && !failed) {
            registry.counter("king.ai.rag.empty").increment();
        }
    }

    public void milvusRequest(String operation, String status, long elapsedNanos) {
        String op = safe(operation);
        String outcome = safe(status);
        registry.counter(MILVUS_REQUESTS, "operation", op, "status", outcome).increment();
        registry.timer(MILVUS_LATENCY, "operation", op, "status", outcome)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 80);
    }
}
```

- [ ] **Step 4: 接入 KnowledgeIndexWorker**

删除 `MeterRegistry`、`Timer`、`TimeUnit` 导入和 `private final MeterRegistry meterRegistry;`，改为：

```java
private final KnowledgeMetrics metrics;
```

在 `onReady`、`poll`、`reconcile` 的成功路径末尾调用 `refreshQueueMetrics()`：

```java
            queue.recoverStale(staleBefore());
            reconcileDocs();
            submitDue();
            refreshQueueMetrics();
```

`poll` 中为：

```java
            queue.recoverStale(staleBefore());
            submitDue();
            refreshQueueMetrics();
```

`reconcile` 中为：

```java
            reconcileDocs();
            refreshQueueMetrics();
```

把 `process` 的 `finally` 改为：

```java
        } finally {
            metrics.indexJob(outcome, System.nanoTime() - startedAt);
        }
```

删除 `recordMetrics` 方法，加入：

```java
    private void refreshQueueMetrics() {
        try {
            QueueStats stats = queue.stats();
            metrics.updateQueue(stats.pending(), stats.failed(), stats.oldestPendingAt());
        } catch (RuntimeException ex) {
            log.warn("Failed to refresh knowledge queue metrics: {}", ex.getMessage());
        }
    }
```

- [ ] **Step 5: 接入 ChatRuntime 的 RAG 指标**

加入字段：

```java
private final KnowledgeMetrics knowledgeMetrics;
```

导入：

```java
import com.king.ai.observability.KnowledgeMetrics;
```

把 Task 3 的 `retrieve` 方法改为：

```java
    private List<RetrievedChunk> retrieve(Long spaceId, List<Long> knowledgeBaseIds, String query,
                                          String role) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        long startedAt = System.nanoTime();
        Span span = tracing.start("rag.retrieve");
        tracing.tag(span, "rag.knowledge_base_count", String.valueOf(knowledgeBaseIds.size()));
        tracing.tag(span, "rag.role", role);
        try {
            List<RetrievedChunk> retrieved = knowledgeService.retrieveForAgent(spaceId, knowledgeBaseIds,
                    query, properties.getRag().getMaxContextChars(),
                    properties.getRag().getMinScore(), role);
            knowledgeMetrics.ragRetrieval(System.nanoTime() - startedAt, retrieved.isEmpty(), false);
            tracing.tag(span, "rag.hit_count", String.valueOf(retrieved.size()));
            return retrieved;
        } catch (RuntimeException ex) {
            knowledgeMetrics.ragRetrieval(System.nanoTime() - startedAt, false, true);
            tracing.error(span, ex);
            log.warn("Knowledge retrieval failed: {}", ex.getMessage());
            return List.of();
        } finally {
            tracing.end(span);
        }
    }
```

- [ ] **Step 6: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=KnowledgeMetricsTest,KnowledgeIndexQueueStatsTest test`

Expected: PASS，5 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/observability/KnowledgeMetrics.java \
  king-ai-api/src/main/java/com/king/ai/knowledge/QueueStats.java \
  king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexQueue.java \
  king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeIndexWorker.java \
  king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java \
  king-ai-api/src/test/java/com/king/ai/observability/KnowledgeMetricsTest.java \
  king-ai-api/src/test/java/com/king/ai/knowledge/KnowledgeIndexQueueStatsTest.java
git commit -m "feat: add knowledge rag and queue metrics"
```

---

### Task 7: Milvus 请求指标

**Files:**
- Modify: `king-ai-api/src/main/java/com/king/ai/knowledge/vector/MilvusVectorStore.java`
- Test: `king-ai-api/src/test/java/com/king/ai/knowledge/vector/MilvusMetricsTest.java`

**Interfaces:**
- Consumes `KnowledgeMetrics.milvusRequest(String operation, String status, long elapsedNanos)`。
- `operation` 取请求路径最后一段：`describe`、`create`、`upsert`、`delete`、`drop`、`search`。
- `status`：
  - `success`：HTTP 2xx 且响应 `code=0`。
  - `error`：传输失败、HTTP 非 2xx、响应 `code!=0` 且 `failOnError=true`。
  - `ignored`：`failOnError=false` 时 HTTP 非 2xx 或响应 `code!=0`（例如首次 `describe` 不存在的集合）。传输失败（`IOException`）始终记 `error`，因为调用方无法继续。
- 指标在 `post` 的 `finally` 中记录，异常路径也必须记录。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.knowledge.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.observability.KnowledgeMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusMetricsTest {

    private HttpServer server;
    private SimpleMeterRegistry registry;
    private MilvusVectorStore store;
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{\"code\":0,\"data\":[]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/vectordb/entities/search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        registry = new SimpleMeterRegistry();
        KingAiProperties properties = new KingAiProperties();
        properties.getMilvus().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMilvus().setTimeoutSeconds(5);
        store = new MilvusVectorStore(new OkHttpClient(), new ObjectMapper(), properties,
                new KnowledgeMetrics(registry));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void recordsSuccessfulSearch() {
        responseBody = "{\"code\":0,\"data\":[{\"chunk_id\":1,\"distance\":0.9}]}";

        assertThat(store.search(base(), new float[]{1f, 2f}, 3)).hasSize(1);
        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void recordsFailedSearch() {
        responseStatus = 500;
        responseBody = "{\"code\":1,\"message\":\"boom\"}";

        assertThatThrownBy(() -> store.search(base(), new float[]{1f}, 3))
                .isInstanceOf(VectorStoreException.class);
        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "error").timer().count())
                .isEqualTo(1L);
    }

    private KnowledgeBase base() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(1L);
        base.setSpaceId(1L);
        base.setVectorCollection("kb_1");
        return base;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=MilvusMetricsTest test`

Expected: 编译失败，`MilvusVectorStore` 构造器不接受 `KnowledgeMetrics`。

- [ ] **Step 3: 注入指标并包裹 post**

在 `MilvusVectorStore` 字段区加入：

```java
private final KnowledgeMetrics metrics;
```

导入：

```java
import com.king.ai.observability.KnowledgeMetrics;
```

构造器签名改为：

```java
    public MilvusVectorStore(OkHttpClient httpClient, ObjectMapper objectMapper,
                             KingAiProperties properties, KnowledgeMetrics metrics) {
```

在构造器末尾加入：

```java
        this.metrics = metrics;
```

完整替换 `post` 方法：

```java
    private JsonNode post(String path, ObjectNode body, boolean failOnError) {
        String operation = operationOf(path);
        long startedAt = System.nanoTime();
        String status = "success";
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (!Texts.isBlank(token)) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            JsonNode root = null;
            if (!text.isBlank()) {
                try {
                    root = objectMapper.readTree(text);
                } catch (Exception ex) {
                    status = failOnError ? "error" : "ignored";
                    if (failOnError) {
                        throw new VectorStoreException("Milvus 返回了无法解析的响应（HTTP "
                                + response.code() + "）");
                    }
                }
            }
            if (!response.isSuccessful() || !isSuccess(root)) {
                status = failOnError ? "error" : "ignored";
                if (failOnError) {
                    String message = root == null ? Texts.oneLine(text, 500)
                            : Texts.oneLine(root.path("message").asText(root.toString()), 500);
                    throw new VectorStoreException("Milvus 请求失败（HTTP " + response.code()
                            + "）：" + message);
                }
            }
            return root == null ? objectMapper.createObjectNode() : root;
        } catch (IOException ex) {
            status = "error";
            throw new VectorStoreException("Milvus 连接失败：" + ex.getMessage(), ex);
        } finally {
            metrics.milvusRequest(operation, status, System.nanoTime() - startedAt);
        }
    }

    private static String operationOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 || index == path.length() - 1 ? path : path.substring(index + 1);
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=MilvusMetricsTest test`

Expected: PASS，2 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/knowledge/vector/MilvusVectorStore.java \
  king-ai-api/src/test/java/com/king/ai/knowledge/vector/MilvusMetricsTest.java
git commit -m "feat: add milvus request metrics"
```

---

### Task 8: MCP 工具指标

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/observability/McpMetrics.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/McpMetricsTest.java`

**Interfaces:**
- Produces `McpMetrics.call(String server, String tool, boolean error, long durationMs)`。
- 指标名：
  - `king.ai.mcp.calls{server,tool,status}`（counter）
  - `king.ai.mcp.latency{server,tool,status}`（timer）
- `server` 最多保留 100 个不同值，超出后归并为 `other`；`tool` 截断到 80 字符；空值记为 `unknown`。
- 只记录 `ToolKind.MCP` 的调用，不记录内置 `skill_read_file`。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final McpMetrics metrics = new McpMetrics(registry);

    @Test
    void recordsCallsAndLatency() {
        metrics.call("weather", "forecast", false, 42);
        metrics.call("weather", "forecast", true, 80);

        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "error").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.mcp.latency")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "error").timer().count()).isEqualTo(1L);
    }

    @Test
    void capsServerCardinality() {
        for (int i = 0; i < McpMetrics.MAX_SERVERS + 5; i++) {
            metrics.call("server-" + i, "tool", false, 1);
        }

        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "other").tag("tool", "tool")
                .tag("status", "success").counter().count())
                .isEqualTo(5.0);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=McpMetricsTest test`

Expected: 编译失败，`McpMetrics` 不存在。

- [ ] **Step 3: 实现 McpMetrics**

```java
package com.king.ai.observability;

import com.king.ai.common.Texts;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class McpMetrics {

    static final int MAX_SERVERS = 100;

    private final MeterRegistry registry;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

    public McpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void call(String server, String tool, boolean error, long durationMs) {
        String serverTag = serverTag(server);
        String toolTag = safe(tool);
        String status = error ? "error" : "success";
        registry.counter("king.ai.mcp.calls",
                "server", serverTag, "tool", toolTag, "status", status).increment();
        registry.timer("king.ai.mcp.latency",
                        "server", serverTag, "tool", toolTag, "status", status)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private synchronized String serverTag(String server) {
        if (Texts.isBlank(server)) {
            return "unknown";
        }
        String value = Texts.trimTo(server.trim(), 80);
        if (servers.contains(value)) {
            return value;
        }
        if (servers.size() >= MAX_SERVERS) {
            return "other";
        }
        servers.add(value);
        return value;
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 80);
    }
}
```

- [ ] **Step 4: 接入 ChatRuntime**

加入字段：

```java
private final McpMetrics mcpMetrics;
```

导入：

```java
import com.king.ai.observability.McpMetrics;
```

在 Task 3 的工具调用块中，`long toolDuration = System.currentTimeMillis() - toolStart;` 之后加入：

```java
                    if (tool != null && tool.kind() == ToolKind.MCP) {
                        mcpMetrics.call(tool.server() == null ? null : tool.server().getName(),
                                tool.remoteName(), outcome.error(), toolDuration);
                    }
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=McpMetricsTest test`

Expected: PASS，2 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/observability/McpMetrics.java \
  king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java \
  king-ai-api/src/test/java/com/king/ai/observability/McpMetricsTest.java
git commit -m "feat: add mcp tool metrics"
```

---

### Task 9: 审计写入失败与 Redis 探针

**Files:**
- Modify: `king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java`
- Create: `king-ai-api/src/main/java/com/king/ai/observability/RedisProbeMetrics.java`
- Test: `king-ai-api/src/test/java/com/king/ai/audit/AuditLogServiceMetricsTest.java`
- Test: `king-ai-api/src/test/java/com/king/ai/observability/RedisProbeMetricsTest.java`

**Interfaces:**
- `AuditLogService.persist` 捕获数据库异常时递增 `king.ai.audit.write.failures`。
- Produces `RedisProbeMetrics.probe()`，每 30 秒执行一次（`king-ai.redis.probe-interval-ms` 可覆盖）。
- 指标：
  - `king.ai.redis.up`（gauge，1/0）
  - `king.ai.redis.probes{status}`（counter，`success`/`error`）
  - `king.ai.redis.probe.latency{status}`（timer）
- `RedisProbeMetrics` 在 `king-ai.token-store=redis` 或未配置（默认 redis）时注册；显式 `memory` 模式不注册，避免出现误导性的 0。与 `RedisChatSessionLock` 等现有 bean 的 `matchIfMissing = true` 保持一致。

- [ ] **Step 1: 写失败测试**

`AuditLogServiceMetricsTest.java`：

```java
package com.king.ai.audit;

import com.king.ai.audit.entity.AuditLog;
import com.king.ai.audit.mapper.AuditLogMapper;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceMetricsTest {

    @Test
    void incrementsFailureCounterWhenInsertFails() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any())).thenThrow(new RuntimeException("db down"));
        KingAiProperties properties = new KingAiProperties();
        properties.getAudit().setEnabled(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditLogService service = new AuditLogService(mapper, properties, registry);

        service.recordChat(1L, 2L, "tester", "chat.send", "9", "SUCCESS",
                "detail", "127.0.0.1", "test-agent", "req-1");

        assertThat(registry.get("king.ai.audit.write.failures").counter().count())
                .isEqualTo(1.0);
        verify(mapper).insert(any(AuditLog.class));
    }
}
```

`RedisProbeMetricsTest.java`：

```java
package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisProbeMetricsTest {

    @Test
    void recordsSuccessfulProbe() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics(factory, registry).probe();

        assertThat(registry.get("king.ai.redis.up").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probes").tag("status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probe.latency").tag("status", "success")
                .timer().count()).isEqualTo(1L);
        verify(connection).close();
    }

    @Test
    void recordsFailureWhenRedisIsDown() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("redis down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics(factory, registry).probe();

        assertThat(registry.get("king.ai.redis.up").gauge().value()).isZero();
        assertThat(registry.get("king.ai.redis.probes").tag("status", "error")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probe.latency").tag("status", "error")
                .timer().count()).isEqualTo(1L);
    }

    private RedisProbeMetrics metrics(RedisConnectionFactory factory, SimpleMeterRegistry registry) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("redisConnectionFactory", factory);
        return new RedisProbeMetrics(beans.getBeanProvider(RedisConnectionFactory.class), registry);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl king-ai-api -Dtest=AuditLogServiceMetricsTest,RedisProbeMetricsTest test`

Expected: 编译失败，`AuditLogService` 构造器不接收 `MeterRegistry`，`RedisProbeMetrics` 不存在。

- [ ] **Step 3: 修改 AuditLogService**

加入导入：

```java
import io.micrometer.core.instrument.MeterRegistry;
```

加入字段（必须放在现有 `properties` 字段之后，保证 Lombok 生成的构造器参数顺序是 `mapper, properties, registry`，与测试的 3 参调用一致）：

```java
private final MeterRegistry meterRegistry;
```

在 `persist` 的 catch 块中、`log.warn` 之前加入：

```java
            meterRegistry.counter("king.ai.audit.write.failures").increment();
```

保留 G0-1 新增的 `recordEvent(...)` 与 G0-3 新增的 `purgeBefore(...)`，以及 `purgeExpired` 中“compliance retention 启用时让位给 `RetentionJob`”的条件；本任务只加字段和指标，不得重写这些方法的语义。

- [ ] **Step 4: 实现 RedisProbeMetrics**

```java
package com.king.ai.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "king-ai", name = "token-store", havingValue = "redis",
        matchIfMissing = true)
public class RedisProbeMetrics {

    private final ObjectProvider<RedisConnectionFactory> factories;
    private final MeterRegistry registry;
    private final AtomicInteger up = new AtomicInteger();

    public RedisProbeMetrics(ObjectProvider<RedisConnectionFactory> factories, MeterRegistry registry) {
        this.factories = factories;
        this.registry = registry;
        Gauge.builder("king.ai.redis.up", up, AtomicInteger::get)
                .description("Redis reachability from the last probe (1 up, 0 down)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${king-ai.redis.probe-interval-ms:30000}")
    public void probe() {
        long startedAt = System.nanoTime();
        String status = "error";
        RedisConnection connection = null;
        try {
            RedisConnectionFactory factory = factories.getIfAvailable();
            if (factory == null) {
                up.set(0);
                return;
            }
            connection = factory.getConnection();
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                up.set(1);
                status = "success";
            } else {
                up.set(0);
            }
        } catch (RuntimeException ex) {
            up.set(0);
            log.debug("Redis probe failed: {}", ex.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                }
            }
            registry.counter("king.ai.redis.probes", "status", status).increment();
            registry.timer("king.ai.redis.probe.latency", "status", status)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl king-ai-api -Dtest=AuditLogServiceMetricsTest,RedisProbeMetricsTest test`

Expected: PASS，3 tests。再运行 `mvn -pl king-ai-api test`，Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/audit/AuditLogService.java \
  king-ai-api/src/main/java/com/king/ai/observability/RedisProbeMetrics.java \
  king-ai-api/src/test/java/com/king/ai/audit/AuditLogServiceMetricsTest.java \
  king-ai-api/src/test/java/com/king/ai/observability/RedisProbeMetricsTest.java
git commit -m "feat: add audit failure and redis probe metrics"
```

---

### Task 10: Prometheus 抓取、记录规则与告警

**Files:**
- Create: `deploy/observability/prometheus/prometheus.yml`
- Create: `deploy/observability/prometheus/alerts.yml`
- Create: `deploy/observability/prometheus/tests/verify.sh`
- Test: `deploy/observability/prometheus/tests/verify.sh`

**Interfaces:**
- Prometheus 抓取 `api:8080/actuator/prometheus`，job 名为 `king-ai-api`。
- 记录规则把原始指标聚合为 SLO 表达式；业务 SLO 告警引用记录规则，可用性告警（`KingAiApiDown`/`KingAiApiTargetMissing`）允许直接使用 `up`/`absent`。
- `rule_files` 使用相对路径 `alerts.yml`；Prometheus 容器必须把工作目录设为 `/etc/prometheus`（Task 13 的 `working_dir`），否则相对路径会解析到镜像默认的 `/prometheus` 并加载失败。
- 告警必须带 `severity`、`summary`、`description`、`runbook_url`；runbook 指向 `docs/operations/observability.md` 的锚点。
- 告警清单（与 §5 验收一致）：
  - `KingAiApiDown`、`KingAiApiTargetMissing`
  - `KingAiHttp5xxHigh`
  - `KingAiChatTtftSlow`
  - `KingAiSseCapacityHigh`
  - `KingAiChatQuotaRejectionHigh`
  - `KingAiIndexFailures`、`KingAiIndexLag`
  - `KingAiMysqlPoolHigh`
  - `KingAiRedisErrors`
  - `KingAiMilvusSearchErrors`
  - `KingAiAuditWriteFailures`

- [ ] **Step 1: 写校验脚本并确认失败**

`deploy/observability/prometheus/tests/verify.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES="$ROOT/alerts.yml"
CONFIG="$ROOT/prometheus.yml"

[[ -f "$RULES" ]] || { echo "missing $RULES" >&2; exit 1; }
[[ -f "$CONFIG" ]] || { echo "missing $CONFIG" >&2; exit 1; }

if command -v promtool >/dev/null 2>&1; then
  promtool check rules "$RULES"
  (cd "$ROOT" && promtool check config prometheus.yml)
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -w /work -v "$ROOT:/work:ro" --entrypoint promtool \
    prom/prometheus:v2.55.1 check rules /work/alerts.yml
  docker run --rm -w /work -v "$ROOT:/work:ro" --entrypoint promtool \
    prom/prometheus:v2.55.1 check config prometheus.yml
else
  echo "NOT RUN: promtool and docker are unavailable" >&2
  exit 2
fi

required=(
  KingAiApiDown
  KingAiApiTargetMissing
  KingAiHttp5xxHigh
  KingAiChatTtftSlow
  KingAiSseCapacityHigh
  KingAiChatQuotaRejectionHigh
  KingAiIndexFailures
  KingAiIndexLag
  KingAiMysqlPoolHigh
  KingAiRedisErrors
  KingAiMilvusSearchErrors
  KingAiAuditWriteFailures
)
for alert in "${required[@]}"; do
  grep -q "alert: $alert" "$RULES" || { echo "missing alert: $alert" >&2; exit 1; }
done

alert_count="$(grep -c '^      - alert:' "$RULES")"
runbook_count="$(grep -c 'runbook_url:' "$RULES")"
[[ "$alert_count" -eq "$runbook_count" ]] || {
  echo "every alert must have a runbook_url (alerts=$alert_count runbooks=$runbook_count)" >&2
  exit 1
}

echo "prometheus rules verified: $alert_count alerts"
```

Run: `chmod +x deploy/observability/prometheus/tests/verify.sh && deploy/observability/prometheus/tests/verify.sh`

Expected: FAIL，`missing .../alerts.yml`。

- [ ] **Step 2: 创建 prometheus.yml**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 30s
  external_labels:
    cluster: king-ai

rule_files:
  - alerts.yml

scrape_configs:
  - job_name: king-ai-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["api:8080"]
        labels:
          service: king-ai-api

  - job_name: prometheus
    static_configs:
      - targets: ["localhost:9090"]
```

- [ ] **Step 3: 创建 alerts.yml**

```yaml
groups:
  - name: king-ai-slo
    interval: 30s
    rules:
      - record: king_ai:http_5xx_ratio5m
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          /
          clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 0.001)

      - record: king_ai:chat_ttft_p95_10m
        expr: |
          histogram_quantile(0.95,
            sum by (le) (rate(king_ai_chat_ttft_seconds_bucket[10m])))

      - record: king_ai:chat_duration_p95_10m
        expr: |
          histogram_quantile(0.95,
            sum by (le) (rate(king_ai_chat_duration_seconds_bucket[10m])))

      - record: king_ai:sse_capacity_ratio
        expr: |
          sum(king_ai_sse_connections)
          /
          clamp_min(sum(king_ai_sse_capacity), 1)

      - record: king_ai:chat_quota_rejection_ratio5m
        expr: |
          sum(rate(king_ai_chat_rejected_total{reason="space_quota"}[5m]))
          /
          clamp_min(sum(rate(king_ai_chat_requests_total[5m])), 0.001)

      - record: king_ai:index_failures_10m
        expr: sum(increase(king_ai_index_jobs_total{outcome="failed"}[10m]))

      - record: king_ai:index_lag_seconds
        expr: max(king_ai_index_lag_seconds)

      - record: king_ai:mysql_pool_usage
        expr: |
          sum(hikaricp_connections_active)
          /
          clamp_min(sum(hikaricp_connections_max), 1)

      - record: king_ai:redis_error_ratio5m
        expr: |
          sum(rate(king_ai_redis_probes_total{status="error"}[5m]))
          /
          clamp_min(sum(rate(king_ai_redis_probes_total[5m])), 0.001)

      - record: king_ai:milvus_search_error_ratio5m
        expr: |
          sum(rate(king_ai_milvus_requests_total{operation="search",status="error"}[5m]))
          /
          clamp_min(sum(rate(king_ai_milvus_requests_total{operation="search"}[5m])), 0.001)

      - record: king_ai:audit_write_failures_10m
        expr: sum(increase(king_ai_audit_write_failures_total[10m]))

  - name: king-ai-alerts
    rules:
      - alert: KingAiApiDown
        expr: up{job="king-ai-api"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "King AI API 不可抓取"
          description: "Prometheus 已连续 2 分钟无法抓取 {{ $labels.instance }}。"
          runbook_url: "docs/operations/observability.md#api-down"

      - alert: KingAiApiTargetMissing
        expr: absent(up{job="king-ai-api"})
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "King AI API 抓取目标不存在"
          description: "Prometheus 中没有任何 job=king-ai-api 的目标，检查 scrape 配置。"
          runbook_url: "docs/operations/observability.md#api-target-missing"

      - alert: KingAiHttp5xxHigh
        expr: king_ai:http_5xx_ratio5m > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "King AI HTTP 5xx 比例超过 1%"
          description: "最近 5 分钟 5xx 比例为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#http-5xx"

      - alert: KingAiChatTtftSlow
        expr: king_ai:chat_ttft_p95_10m > 5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "King AI 首 token P95 超过 5 秒"
          description: "最近 10 分钟首 token P95 为 {{ $value | humanizeDuration }}。"
          runbook_url: "docs/operations/observability.md#ttft"

      - alert: KingAiSseCapacityHigh
        expr: king_ai:sse_capacity_ratio > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "King AI SSE 连接超过容量 80%"
          description: "当前单实例 SSE 容量使用率为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#sse-capacity"

      - alert: KingAiChatQuotaRejectionHigh
        expr: king_ai:chat_quota_rejection_ratio5m > 0.1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "King AI 空间配额拒绝率超过 10%"
          description: "最近 5 分钟空间配额拒绝率为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#quota-rejection"

      - alert: KingAiIndexFailures
        expr: king_ai:index_failures_10m > 5
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "King AI 知识索引失败超过 5 次/10 分钟"
          description: "最近 10 分钟永久失败 {{ $value | humanize }} 次。"
          runbook_url: "docs/operations/observability.md#index-failures"

      - alert: KingAiIndexLag
        expr: king_ai:index_lag_seconds > 600
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "King AI 索引队列积压超过 10 分钟"
          description: "最老 pending 任务已等待 {{ $value | humanizeDuration }}。"
          runbook_url: "docs/operations/observability.md#index-lag"

      - alert: KingAiMysqlPoolHigh
        expr: king_ai:mysql_pool_usage > 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "King AI MySQL 连接池使用率超过 80%"
          description: "当前 Hikari 活跃连接占比为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#mysql-pool"

      - alert: KingAiRedisErrors
        expr: king_ai:redis_error_ratio5m > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "King AI Redis 探针错误率超过 1%"
          description: "最近 5 分钟 Redis 探针错误率为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#redis-errors"

      - alert: KingAiMilvusSearchErrors
        expr: king_ai:milvus_search_error_ratio5m > 0.01
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "King AI Milvus 查询错误率超过 1%"
          description: "最近 5 分钟 search 错误率为 {{ $value | humanizePercentage }}。"
          runbook_url: "docs/operations/observability.md#milvus-errors"

      - alert: KingAiAuditWriteFailures
        expr: king_ai:audit_write_failures_10m > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "King AI 审计写入失败"
          description: "最近 10 分钟出现 {{ $value | humanize }} 次审计写入失败，检查数据库与审计表。"
          runbook_url: "docs/operations/observability.md#audit-write-failures"
```

- [ ] **Step 4: 运行确认通过**

Run: `deploy/observability/prometheus/tests/verify.sh`

Expected: `prometheus rules verified: 12 alerts`。若本机没有 promtool/Docker，脚本以退出码 2 输出 `NOT RUN`，不得把 `NOT RUN` 当成通过。

- [ ] **Step 5: 提交**

```bash
git add deploy/observability/prometheus/prometheus.yml \
  deploy/observability/prometheus/alerts.yml \
  deploy/observability/prometheus/tests/verify.sh
git commit -m "feat: add prometheus scrape rules and alerts"
```

---

### Task 11: Grafana 数据源、看板与校验

**Files:**
- Create: `deploy/observability/grafana/provisioning/datasources/prometheus.yml`
- Create: `deploy/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `deploy/observability/grafana/dashboards/chat-overview.json`
- Create: `deploy/observability/grafana/dashboards/rag-knowledge.json`
- Create: `deploy/observability/grafana/dashboards/platform-health.json`
- Test: `deploy/observability/grafana/validate.sh`

**Interfaces:**
- 数据源固定 `uid: prometheus`，指向 `http://prometheus:9090`。
- 三个看板 UID：`king-ai-chat-overview`、`king-ai-rag-knowledge`、`king-ai-platform-health`。
- 每个看板至少 6 个 panel；全部 panel 使用 `prometheus` 数据源，target 表达式非空。
- 看板不得包含 `__inputs` 或 `${DS_`（前者是旧版导入格式，后者依赖未定义的模板变量）。
- 看板只引用 Task 4–10 已定义的指标与记录规则，不引用尚未实现的指标。

- [ ] **Step 1: 写校验脚本并确认失败**

`deploy/observability/grafana/validate.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DASH="$ROOT/dashboards"

if ! command -v jq >/dev/null 2>&1; then
  echo "NOT RUN: jq is required to validate dashboards" >&2
  exit 2
fi

expected=(
  chat-overview.json
  rag-knowledge.json
  platform-health.json
)
for name in "${expected[@]}"; do
  [[ -f "$DASH/$name" ]] || { echo "missing $DASH/$name" >&2; exit 1; }
done

if grep -R -q '__inputs' "$DASH"; then
  echo "dashboards must not contain __inputs" >&2
  exit 1
fi
if grep -R -q '\${DS_' "$DASH"; then
  echo "dashboards must not reference \${DS_ template variables" >&2
  exit 1
fi

for name in "${expected[@]}"; do
  file="$DASH/$name"
  jq empty "$file" || { echo "invalid JSON: $file" >&2; exit 1; }
  jq -e '.uid | type == "string" and length > 0' "$file" >/dev/null \
    || { echo "missing uid: $file" >&2; exit 1; }
  jq -e '.title | type == "string" and length > 0' "$file" >/dev/null \
    || { echo "missing title: $file" >&2; exit 1; }
  jq -e '(.panels | length) >= 6' "$file" >/dev/null \
    || { echo "needs at least 6 panels: $file" >&2; exit 1; }
  jq -e '([.panels[].id] | length) == ([.panels[].id] | unique | length)' "$file" >/dev/null \
    || { echo "duplicate panel ids: $file" >&2; exit 1; }
  jq -e 'all(.panels[]; .datasource.uid == "prometheus")' "$file" >/dev/null \
    || { echo "panel datasource must be prometheus: $file" >&2; exit 1; }
  jq -e 'all(.panels[]; (.targets | length) > 0 and all(.targets[]; (.expr | length) > 0))' \
    "$file" >/dev/null \
    || { echo "panel target without expr: $file" >&2; exit 1; }
done

uids="$(jq -r '.uid' "$DASH"/*.json | sort)"
[[ "$(printf '%s\n' "$uids" | sort -u | wc -l)" -eq 3 ]] \
  || { echo "dashboard uids must be unique" >&2; exit 1; }

grep -q 'uid: prometheus' "$ROOT/provisioning/datasources/prometheus.yml" \
  || { echo "datasource uid must be prometheus" >&2; exit 1; }
grep -q 'url: http://prometheus:9090' "$ROOT/provisioning/datasources/prometheus.yml" \
  || { echo "datasource url must be http://prometheus:9090" >&2; exit 1; }
grep -q 'path: /var/lib/grafana/dashboards' "$ROOT/provisioning/dashboards/dashboards.yml" \
  || { echo "dashboard provider path mismatch" >&2; exit 1; }

require_expr() {
  local file="$1"
  shift
  local needle
  for needle in "$@"; do
    grep -q -- "$needle" "$file" \
      || { echo "missing expression fragment: $needle (in $file)" >&2; exit 1; }
  done
}

require_expr "$DASH/chat-overview.json" \
  'king_ai_chat_active' \
  'king_ai_sse_capacity' \
  'king_ai_chat_requests_total' \
  'king_ai_chat_ttft_seconds_bucket' \
  'king_ai_chat_duration_seconds_bucket' \
  'king_ai_chat_rejected_total' \
  'king_ai_chat_tokens_total' \
  'king_ai_chat_tool_calls_total'

require_expr "$DASH/rag-knowledge.json" \
  'king_ai_rag_retrieval_seconds_bucket' \
  'king_ai_rag_empty_total' \
  'king_ai_index_jobs_total' \
  'king_ai_index_pending' \
  'king_ai_index_failed' \
  'king_ai_index_lag_seconds' \
  'king_ai_milvus_latency_seconds_bucket' \
  'king_ai_mcp_calls_total'

require_expr "$DASH/platform-health.json" \
  'king_ai:http_5xx_ratio5m' \
  'http_server_requests_seconds_bucket' \
  'jvm_memory_used_bytes' \
  'king_ai:mysql_pool_usage' \
  'king_ai_redis_up' \
  'king_ai_audit_write_failures_total' \
  'up{job=\"king-ai-api\"}'

panels="$(for name in "${expected[@]}"; do jq '.panels | length' "$DASH/$name"; done \
  | awk '{sum += $1} END {print sum}')"
echo "grafana dashboards verified: 3 dashboards, $panels panels"
```

Run: `chmod +x deploy/observability/grafana/validate.sh && deploy/observability/grafana/validate.sh`

Expected: FAIL，`missing .../dashboards/chat-overview.json`。

- [ ] **Step 2: 创建数据源与看板 provisioning**

`deploy/observability/grafana/provisioning/datasources/prometheus.yml`：

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
    jsonData:
      timeInterval: 15s
```

`deploy/observability/grafana/provisioning/dashboards/dashboards.yml`：

```yaml
apiVersion: 1

providers:
  - name: king-ai
    orgId: 1
    folder: King AI
    type: file
    disableDeletion: true
    editable: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 3: 创建 chat-overview.json**

`deploy/observability/grafana/dashboards/chat-overview.json`：

```json
{
  "uid": "king-ai-chat-overview",
  "title": "King AI · Chat Overview",
  "description": "对话吞吐、首 token、SSE 容量、配额拒绝与 token 消耗。",
  "tags": ["king-ai", "chat"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-6h", "to": "now" },
  "templating": { "list": [] },
  "annotations": { "list": [] },
  "panels": [
    {
      "id": 1,
      "type": "stat",
      "title": "Active chats",
      "description": "当前实例进行中的对话数。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 0 },
      "fieldConfig": { "defaults": { "unit": "short", "decimals": 0 }, "overrides": [] },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "value",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(king_ai_chat_active)",
          "legendFormat": "active",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 2,
      "type": "stat",
      "title": "SSE capacity used",
      "description": "SSE 连接数占单实例容量的比例。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 6, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "decimals": 1,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.8 },
              { "color": "red", "value": 0.95 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(king_ai_sse_connections) / clamp_min(sum(king_ai_sse_capacity), 1)",
          "legendFormat": "used",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Chat requests by source",
      "description": "admin 与 public 入口的请求速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 12, "x": 12, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (source) (rate(king_ai_chat_requests_total[5m]))",
          "legendFormat": "{{source}}",
          "range": true
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "Time to first token (p50 / p95)",
      "description": "首 token 延迟，标签不包含用户或会话标识。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 6 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "histogram_quantile(0.50, sum by (le) (rate(king_ai_chat_ttft_seconds_bucket[5m])))",
          "legendFormat": "p50",
          "range": true
        },
        {
          "refId": "B",
          "editorMode": "code",
          "expr": "histogram_quantile(0.95, sum by (le) (rate(king_ai_chat_ttft_seconds_bucket[5m])))",
          "legendFormat": "p95",
          "range": true
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "Chat duration p95 by status",
      "description": "完整响应耗时，按成功/失败拆分。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 6 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "histogram_quantile(0.95, sum by (le, status) (rate(king_ai_chat_duration_seconds_bucket[5m])))",
          "legendFormat": "{{status}}",
          "range": true
        }
      ]
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "Rejected chats by reason",
      "description": "space_quota 与 sse_capacity 拒绝速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 14 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (reason) (rate(king_ai_chat_rejected_total[5m]))",
          "legendFormat": "{{reason}}",
          "range": true
        }
      ]
    },
    {
      "id": 7,
      "type": "timeseries",
      "title": "Tokens by type",
      "description": "prompt 与 completion token 速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 14 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (type) (rate(king_ai_chat_tokens_total[5m]))",
          "legendFormat": "{{type}} tokens/s",
          "range": true
        }
      ]
    },
    {
      "id": 8,
      "type": "timeseries",
      "title": "Tool calls by source and result",
      "description": "内置工具与 MCP 工具调用结果。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 22 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (source, result) (rate(king_ai_chat_tool_calls_total[5m]))",
          "legendFormat": "{{source}} / {{result}}",
          "range": true
        }
      ]
    }
  ]
}
```

- [ ] **Step 4: 创建 rag-knowledge.json**

`deploy/observability/grafana/dashboards/rag-knowledge.json`：

```json
{
  "uid": "king-ai-rag-knowledge",
  "title": "King AI · RAG & Knowledge",
  "description": "检索质量、索引队列、Milvus 与 MCP 下游健康度。",
  "tags": ["king-ai", "rag", "knowledge"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-6h", "to": "now" },
  "templating": { "list": [] },
  "annotations": { "list": [] },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "RAG retrievals by status",
      "description": "success / empty / error 检索速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (status) (rate(king_ai_rag_retrieval_seconds_count[5m]))",
          "legendFormat": "{{status}}",
          "range": true
        }
      ]
    },
    {
      "id": 2,
      "type": "stat",
      "title": "Empty recall ratio",
      "description": "有知识库但召回为空的比例。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 6, "x": 12, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "decimals": 1,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.2 },
              { "color": "red", "value": 0.5 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(rate(king_ai_rag_empty_total[5m])) / clamp_min(sum(rate(king_ai_rag_retrieval_seconds_count[5m])), 0.001)",
          "legendFormat": "empty",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 3,
      "type": "stat",
      "title": "Index queue pending",
      "description": "等待执行的索引任务数。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 6, "x": 18, "y": 0 },
      "fieldConfig": {
        "defaults": { "unit": "short", "decimals": 0 },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "value",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "max(king_ai_index_pending)",
          "legendFormat": "pending",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "RAG retrieval p95 by status",
      "description": "检索耗时 P95。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "histogram_quantile(0.95, sum by (le, status) (rate(king_ai_rag_retrieval_seconds_bucket[5m])))",
          "legendFormat": "{{status}}",
          "range": true
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "Index jobs by outcome",
      "description": "succeeded / failed / skipped 索引任务速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (outcome) (rate(king_ai_index_jobs_total[5m]))",
          "legendFormat": "{{outcome}}",
          "range": true
        }
      ]
    },
    {
      "id": 6,
      "type": "stat",
      "title": "Index permanently failed",
      "description": "处于 FAILED 且不再自动重试的任务数。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 6, "x": 0, "y": 16 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "decimals": 0,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "red", "value": 1 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "max(king_ai_index_failed)",
          "legendFormat": "failed",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 7,
      "type": "stat",
      "title": "Index queue lag",
      "description": "最老 pending 任务的等待时长。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 6, "x": 6, "y": 16 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "decimals": 0,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 300 },
              { "color": "red", "value": 600 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "max(king_ai_index_lag_seconds)",
          "legendFormat": "lag",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 8,
      "type": "timeseries",
      "title": "Milvus latency p95 by operation",
      "description": "describe / create / upsert / delete / drop / search 耗时。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 16 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "histogram_quantile(0.95, sum by (le, operation) (rate(king_ai_milvus_latency_seconds_bucket[5m])))",
          "legendFormat": "{{operation}}",
          "range": true
        }
      ]
    },
    {
      "id": 9,
      "type": "timeseries",
      "title": "MCP calls by server and status",
      "description": "server 标签最多 100 个，其余归入 other。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 24 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (server, status) (rate(king_ai_mcp_calls_total[5m]))",
          "legendFormat": "{{server}} / {{status}}",
          "range": true
        }
      ]
    }
  ]
}
```

- [ ] **Step 5: 创建 platform-health.json**

`deploy/observability/grafana/dashboards/platform-health.json`：

```json
{
  "uid": "king-ai-platform-health",
  "title": "King AI · Platform Health",
  "description": "HTTP 错误率、JVM、连接池、Redis、审计与抓取目标。",
  "tags": ["king-ai", "platform"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-6h", "to": "now" },
  "templating": { "list": [] },
  "annotations": { "list": [] },
  "panels": [
    {
      "id": 1,
      "type": "stat",
      "title": "HTTP 5xx ratio (5m)",
      "description": "告警 KingAiHttp5xxHigh 的同一记录规则。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "decimals": 2,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.01 },
              { "color": "red", "value": 0.05 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "king_ai:http_5xx_ratio5m",
          "legendFormat": "5xx",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 2,
      "type": "stat",
      "title": "Scrape targets up",
      "description": "job=king-ai-api 的可用目标数。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 6, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "decimals": 0,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "red", "value": null },
              { "color": "green", "value": 1 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(up{job=\"king-ai-api\"})",
          "legendFormat": "up",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 3,
      "type": "stat",
      "title": "JVM heap used",
      "description": "堆内存使用率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 12, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "decimals": 1,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.8 },
              { "color": "red", "value": 0.9 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(jvm_memory_used_bytes{area=\"heap\"}) / clamp_min(sum(jvm_memory_max_bytes{area=\"heap\"}), 1)",
          "legendFormat": "heap",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 4,
      "type": "stat",
      "title": "MySQL pool used",
      "description": "Hikari 活跃连接占最大连接数比例。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 18, "y": 0 },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "decimals": 1,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.8 },
              { "color": "red", "value": 0.95 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "king_ai:mysql_pool_usage",
          "legendFormat": "pool",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 5,
      "type": "stat",
      "title": "Redis probe",
      "description": "最近一次探针结果，1 为可用。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 6 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "decimals": 0,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "red", "value": null },
              { "color": "green", "value": 1 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "max(king_ai_redis_up)",
          "legendFormat": "redis",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 6,
      "type": "stat",
      "title": "Audit write failures (10m)",
      "description": "审计写入失败次数，任何非零值都需要人工核对。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 6, "w": 6, "x": 6, "y": 6 },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "decimals": 0,
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "red", "value": 1 }
            ]
          }
        },
        "overrides": []
      },
      "options": {
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false },
        "colorMode": "background",
        "graphMode": "area",
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": "auto"
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum(increase(king_ai_audit_write_failures_total[10m]))",
          "legendFormat": "failures",
          "instant": true,
          "range": false
        }
      ]
    },
    {
      "id": 7,
      "type": "timeseries",
      "title": "HTTP request rate by status",
      "description": "按响应状态码拆分请求速率。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 9, "w": 12, "x": 12, "y": 6 },
      "fieldConfig": {
        "defaults": {
          "unit": "reqps",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "sum by (status) (rate(http_server_requests_seconds_count[5m]))",
          "legendFormat": "{{status}}",
          "range": true
        }
      ]
    },
    {
      "id": 8,
      "type": "timeseries",
      "title": "HTTP p95 by URI",
      "description": "最慢的 URI P95，用于定位回归。",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 9, "w": 12, "x": 0, "y": 15 },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "drawStyle": "line",
            "fillOpacity": 10,
            "lineWidth": 2,
            "showPoints": "never",
            "spanNulls": false
          }
        },
        "overrides": []
      },
      "options": {
        "legend": { "displayMode": "list", "placement": "bottom", "showLegend": true },
        "tooltip": { "mode": "multi", "sort": "desc" }
      },
      "targets": [
        {
          "refId": "A",
          "editorMode": "code",
          "expr": "histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))",
          "legendFormat": "{{uri}}",
          "range": true
        }
      ]
    }
  ]
}
```

- [ ] **Step 6: 运行确认通过**

Run: `deploy/observability/grafana/validate.sh`

Expected: `grafana dashboards verified: 3 dashboards, 25 panels`。若本机没有 `jq`，脚本以退出码 2 输出 `NOT RUN`，不得把 `NOT RUN` 当成通过。

- [ ] **Step 7: 提交**

```bash
git add deploy/observability/grafana/provisioning/datasources/prometheus.yml \
  deploy/observability/grafana/provisioning/dashboards/dashboards.yml \
  deploy/observability/grafana/dashboards/chat-overview.json \
  deploy/observability/grafana/dashboards/rag-knowledge.json \
  deploy/observability/grafana/dashboards/platform-health.json \
  deploy/observability/grafana/validate.sh
git commit -m "feat: add grafana datasource and dashboards"
```

---

### Task 12: k6 基线脚本与结果渲染

**Files:**
- Create: `loadtest/k6/lib/api.js`
- Create: `loadtest/k6/chat-sse.js`
- Create: `loadtest/k6/rag-query.js`
- Create: `loadtest/build-k6.sh`
- Create: `loadtest/render-baseline.sh`
- Create: `loadtest/tests/static_test.sh`
- Create: `loadtest/README.md`
- Create: `loadtest/.gitignore`
- Test: `loadtest/tests/static_test.sh`

**Interfaces:**
- 环境变量：`KING_AI_BASE_URL`、`KING_AI_USERNAME`、`KING_AI_PASSWORD`、`KING_AI_AGENT_ID`、`KING_AI_SPACE_ID`（默认取登录返回的 currentSpaceId）、`KING_AI_KB_ID`、`VUS`、`RAMP`、`DURATION`、`SUMMARY_OUT`。
- 凭证只从环境变量读取，脚本中不得出现默认用户名/密码。
- SSE 事件名来自现有实现：`meta`、`content`、`done`、`error`；TTFT 取第一个 `content` 事件。
- 自定义指标以 `king_ai_load_` 开头，禁止把 `userId`、`sessionId`、`requestId` 放入标签。
- `render-baseline.sh` 只转录 k6 summary 中的真实数值；缺失指标写 `n/a`，不得填 0 或估计值。
- 1000 并发 SSE 的验收在 P2-2/P2-3 完成前不得宣称通过；README 必须显式写明该阻塞点。

- [ ] **Step 1: 写静态校验脚本并确认失败**

`loadtest/tests/static_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K6="$ROOT/k6"

required=(
  "$K6/lib/api.js"
  "$K6/chat-sse.js"
  "$K6/rag-query.js"
  "$ROOT/build-k6.sh"
  "$ROOT/render-baseline.sh"
  "$ROOT/README.md"
)
for file in "${required[@]}"; do
  [[ -f "$file" ]] || { echo "missing $file" >&2; exit 1; }
done

command -v node >/dev/null 2>&1 || {
  echo "NOT RUN: node is required for JavaScript syntax checks" >&2
  exit 2
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for file in "$K6"/*.js "$K6"/lib/*.js; do
  cp "$file" "$tmp/$(basename "${file%.js}").mjs"
  node --check "$tmp/$(basename "${file%.js}").mjs"
done

bash -n "$ROOT/build-k6.sh"
bash -n "$ROOT/render-baseline.sh"

grep -q "sse.open" "$K6/chat-sse.js" || {
  echo "chat-sse.js must use sse.open" >&2
  exit 1
}
grep -q "X-Space-Id" "$K6/lib/api.js" || {
  echo "api.js must send X-Space-Id" >&2
  exit 1
}
grep -q "Authorization" "$K6/lib/api.js" || {
  echo "api.js must send Authorization" >&2
  exit 1
}
grep -q "king_ai_load_error_rate" "$K6/chat-sse.js" || {
  echo "chat-sse.js must expose error rate" >&2
  exit 1
}
grep -q "king_ai_load_error_rate" "$K6/rag-query.js" || {
  echo "rag-query.js must expose error rate" >&2
  exit 1
}
grep -q "P2-2" "$ROOT/README.md" || {
  echo "README must document the 1000-concurrency blocker" >&2
  exit 1
}

if grep -R -n -E 'tags:[[:space:]]*\{[^}]*(userId|sessionId|requestId)' "$K6"; then
  echo "metric tags must not contain user/session/request identifiers" >&2
  exit 1
fi
if grep -R -n 'KING_AI_PASSWORD=' "$K6" "$ROOT"/*.sh; then
  echo "credentials must come from the environment, not literals" >&2
  exit 1
fi

if "$ROOT/render-baseline.sh" >/dev/null 2>&1; then
  echo "render-baseline.sh must reject a missing summary file" >&2
  exit 1
fi

echo "loadtest static checks passed; runtime load tests NOT RUN"
```

Run: `chmod +x loadtest/tests/static_test.sh && loadtest/tests/static_test.sh`

Expected: FAIL，`missing .../k6/lib/api.js`。

- [ ] **Step 2: 创建公共 API 与登录封装**

`loadtest/k6/lib/api.js`：

```javascript
import http from 'k6/http';
import { check, fail } from 'k6';

export function baseUrl() {
  return (__ENV.KING_AI_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
}

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value) {
    fail(`${name} is required`);
  }
  return value;
}

export function requirePositiveInt(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isFinite(value) || value < 1) {
    fail(`${name} must be a positive integer`);
  }
  return Math.floor(value);
}

export function login() {
  const response = http.post(
    `${baseUrl()}/api/auth/login`,
    JSON.stringify({
      username: requireEnv('KING_AI_USERNAME'),
      password: requireEnv('KING_AI_PASSWORD'),
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'auth.login' },
    },
  );
  check(response, { 'login 200': (r) => r.status === 200 });
  if (response.status !== 200) {
    fail(`login failed: HTTP ${response.status}`);
  }
  const body = response.json();
  if (!body || !body.data || !body.data.token) {
    fail('login response missing data.token');
  }
  return {
    token: body.data.token,
    spaceId: __ENV.KING_AI_SPACE_ID
      ? Number(__ENV.KING_AI_SPACE_ID)
      : body.data.currentSpaceId,
  };
}

export function adminHeaders(token, spaceId, accept) {
  if (spaceId === null || spaceId === undefined || Number.isNaN(Number(spaceId))) {
    fail('space id is required; set KING_AI_SPACE_ID');
  }
  return {
    'Content-Type': 'application/json',
    Accept: accept || 'application/json',
    Authorization: `Bearer ${token}`,
    'X-Space-Id': String(spaceId),
  };
}

export function agentId() {
  const value = Number(requireEnv('KING_AI_AGENT_ID'));
  if (!Number.isFinite(value) || value < 1) {
    fail('KING_AI_AGENT_ID must be a numeric agent id');
  }
  return value;
}
```

- [ ] **Step 3: 创建 chat-sse.js**

`loadtest/k6/chat-sse.js`：

```javascript
import sse from 'k6/x/sse';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  adminHeaders,
  agentId,
  baseUrl,
  login,
  requirePositiveInt,
} from './lib/api.js';

const ttft = new Trend('king_ai_load_ttft_ms', true);
const chatDuration = new Trend('king_ai_load_chat_duration_ms', true);
const done = new Counter('king_ai_load_done');
const events = new Counter('king_ai_load_events');
const errorRate = new Rate('king_ai_load_error_rate');

const vus = requirePositiveInt('VUS', 2);
const ramp = __ENV.RAMP || '10s';
const hold = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    chat: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: ramp, target: vus },
        { duration: hold, target: vus },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    king_ai_load_error_rate: ['rate<0.01'],
    king_ai_load_ttft_ms: ['p(95)<5000'],
  },
};

export function setup() {
  const auth = login();
  return {
    token: auth.token,
    spaceId: auth.spaceId,
    agentId: agentId(),
  };
}

export default function (data) {
  const startedAt = Date.now();
  let settled = false;
  let firstToken = false;
  let sawDone = false;
  let failed = false;

  const finish = (ok) => {
    if (settled) {
      return;
    }
    settled = true;
    chatDuration.add(Date.now() - startedAt);
    errorRate.add(!ok);
    if (ok) {
      done.add(1);
    }
  };

  const response = sse.open(
    `${baseUrl()}/api/chat/send`,
    {
      method: 'POST',
      headers: adminHeaders(data.token, data.spaceId, 'text/event-stream'),
      body: JSON.stringify({
        agentId: data.agentId,
        sessionId: null,
        message: `load test vu=${__VU} iter=${__ITER}`,
      }),
      tags: { name: 'chat.send' },
    },
    (client) => {
      client.on('event', (event) => {
        events.add(1);
        if (event.name === 'content' && !firstToken) {
          firstToken = true;
          ttft.add(Date.now() - startedAt);
        }
        if (event.name === 'error') {
          failed = true;
          finish(false);
          client.close();
        }
        if (event.name === 'done') {
          sawDone = true;
          finish(true);
          client.close();
        }
      });
      client.on('error', () => {
        failed = true;
        finish(false);
        client.close();
      });
    },
  );

  check(response, {
    'chat status 200': (r) => r && r.status === 200,
    'chat reached done': () => sawDone && !failed,
  });
  if (!settled) {
    finish(sawDone && !failed);
  }
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT;
  if (!out) {
    return {};
  }
  return { [out]: JSON.stringify(data, null, 2) };
}
```

- [ ] **Step 4: 创建 rag-query.js**

`loadtest/k6/rag-query.js`：

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  adminHeaders,
  baseUrl,
  login,
  requireEnv,
  requirePositiveInt,
} from './lib/api.js';

const latency = new Trend('king_ai_load_rag_latency_ms', true);
const errorRate = new Rate('king_ai_load_error_rate');

const vus = requirePositiveInt('VUS', 2);
const ramp = __ENV.RAMP || '10s';
const hold = __ENV.DURATION || '30s';
const pause = Number(__ENV.SLEEP || 0.5);

export const options = {
  scenarios: {
    rag: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: ramp, target: vus },
        { duration: hold, target: vus },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    king_ai_load_error_rate: ['rate<0.01'],
    king_ai_load_rag_latency_ms: ['p(95)<2000'],
  },
};

export function setup() {
  const auth = login();
  return {
    token: auth.token,
    spaceId: auth.spaceId,
    knowledgeBaseId: Number(requireEnv('KING_AI_KB_ID')),
  };
}

export default function (data) {
  const startedAt = Date.now();
  const response = http.post(
    `${baseUrl()}/api/knowledge-bases/${data.knowledgeBaseId}/search`,
    JSON.stringify({
      query: __ENV.RAG_QUERY || '负载测试查询',
      topK: 5,
    }),
    {
      headers: adminHeaders(data.token, data.spaceId),
      tags: { name: 'knowledge.search' },
    },
  );
  latency.add(Date.now() - startedAt);

  let hasData = false;
  if (response.status === 200) {
    try {
      hasData = Array.isArray(response.json().data);
    } catch (error) {
      hasData = false;
    }
  }
  check(response, {
    'search 200': (r) => r.status === 200,
    'search data array': () => hasData,
  });
  errorRate.add(!hasData);

  if (pause > 0) {
    sleep(pause);
  }
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT;
  if (!out) {
    return {};
  }
  return { [out]: JSON.stringify(data, null, 2) };
}
```

- [ ] **Step 5: 创建构建与基线渲染脚本**

`loadtest/build-k6.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/bin/k6-sse"
EXT="github.com/phymbert/xk6-sse@v0.2.0"
XK6="go.k6.io/xk6/cmd/xk6@v1.4.15"

mkdir -p "$ROOT/bin"
if [[ -x "$OUT" ]]; then
  echo "using existing $OUT"
  "$OUT" version
  exit 0
fi

if command -v xk6 >/dev/null 2>&1; then
  (cd "$ROOT" && xk6 build --output "$OUT" --with "$EXT")
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$ROOT:/work" -w /work \
    -e GOFLAGS=-buildvcs=false \
    golang:1.25-bookworm bash -lc "
      set -euo pipefail
      go install ${XK6}
      export PATH=\"\$PATH:\$(go env GOPATH)/bin\"
      xk6 build --output bin/k6-sse --with ${EXT}
    "
else
  echo "NOT RUN: xk6 and docker are unavailable" >&2
  exit 2
fi

"$OUT" version
```

`loadtest/render-baseline.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULT="${1:-}"
[[ -n "$RESULT" && -f "$RESULT" ]] || {
  echo "usage: render-baseline.sh <k6-summary.json>" >&2
  exit 2
}
command -v jq >/dev/null 2>&1 || {
  echo "NOT RUN: jq is required" >&2
  exit 2
}
jq -e '.metrics' "$RESULT" >/dev/null || {
  echo "not a k6 summary export: $RESULT" >&2
  exit 1
}

LABEL="${LABEL:-local}"
[[ "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "LABEL may only contain letters, digits, dot, underscore and dash" >&2
  exit 1
}
OUT="${OUT:-$ROOT/baselines/$LABEL.md}"
mkdir -p "$(dirname "$OUT")"

metric() {
  jq -r --arg metric "$1" --arg key "$2" \
    '.metrics[$metric].values[$key] // "n/a"' "$RESULT"
}

captured="${BASELINE_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
target="${KING_AI_BASE_URL:-unknown}"
vus="${VUS:-unknown}"
ramp="${RAMP:-unknown}"
hold="${DURATION:-unknown}"

cat > "$OUT" <<EOF
# Load baseline: $LABEL

- Captured (UTC): $captured
- Target: $target
- Scenario: VUS=$vus, ramp=$ramp, hold=$hold
- k6: ${K6_VERSION:-unknown}
- Source: \`$RESULT\`

| Metric | Value |
| --- | --- |
| Iterations | $(metric iterations count) |
| Error rate | $(metric king_ai_load_error_rate rate) |
| TTFT p95 (ms) | $(metric king_ai_load_ttft_ms 'p(95)') |
| Chat duration p95 (ms) | $(metric king_ai_load_chat_duration_ms 'p(95)') |
| RAG latency p95 (ms) | $(metric king_ai_load_rag_latency_ms 'p(95)') |
| HTTP duration p95 (ms) | $(metric http_req_duration 'p(95)') |
| Checks pass rate | $(metric checks rate) |

> Values are transcribed from the k6 summary export for a single run; missing
> metrics are shown as \`n/a\`, never as zero. This file is a measurement record,
> not a capacity commitment.
>
> The 1000-concurrent-SSE acceptance is **NOT MET** until P2-2 and P2-3 land.
> Do not cite this baseline as proof of that target.

Report copied to \`$OUT\`.
EOF
```

`loadtest/.gitignore`：

```gitignore
bin/
results/
# baselines/ is intentionally committed as measurement evidence.
```

- [ ] **Step 6: 创建 README**

`loadtest/README.md`：

```markdown
# Load tests

This directory contains the reproducible baseline for chat SSE and RAG search.
It records measurements only. It does not prove capacity targets.

## Prerequisites

- A dedicated test space with a working agent and at least one ready knowledge
  document. Never point these scripts at production.
- Node.js 22 for static checks.
- `jq` for baseline rendering.
- k6 with the `k6/x/sse` module. `build-k6.sh` pins
  `phymbert/xk6-sse@v0.2.0` and `xk6@v1.4.15`; the Docker fallback uses Go 1.25
  because the extension requires k6 v2 and Go 1.25.1. If the local k6 already
  exposes the module, substitute its path for `loadtest/bin/k6-sse` in the
  commands below and skip the build.
- Docker is optional and is used only when `xk6` is not installed.

## Credentials

All credentials come from the environment. Do not commit them.

```bash
export KING_AI_BASE_URL=http://127.0.0.1:8080
export KING_AI_USERNAME=loadtest-admin
export KING_AI_PASSWORD='<from your secret manager>'
export KING_AI_AGENT_ID=1
export KING_AI_KB_ID=1
```

`KING_AI_SPACE_ID` is optional; the login response `currentSpaceId` is used when
it is absent.

## Static checks

```bash
loadtest/tests/static_test.sh
```

The script prints `loadtest static checks passed; runtime load tests NOT RUN`.
That means syntax and guardrails passed; it does not mean a load test ran.

## Build

```bash
loadtest/build-k6.sh
```

If the script prints `NOT RUN`, install Docker or Go plus xk6 and rerun. A
`NOT RUN` result is not a pass.

## Chat SSE baseline

```bash
mkdir -p loadtest/results
K6_VERSION="$(loadtest/bin/k6-sse version)" \
VUS=10 RAMP=30s DURATION=2m \
SUMMARY_OUT=loadtest/results/chat-local.json \
  loadtest/bin/k6-sse run loadtest/k6/chat-sse.js
```

TTFT is measured from request start to the first `content` event. A request
fails when the stream emits `error`, never reaches `done`, or returns non-200.

## RAG baseline

```bash
mkdir -p loadtest/results
K6_VERSION="$(loadtest/bin/k6-sse version)" \
VUS=10 RAMP=30s DURATION=2m SLEEP=0.5 \
SUMMARY_OUT=loadtest/results/rag-local.json \
  loadtest/bin/k6-sse run loadtest/k6/rag-query.js
```

## Render a baseline

```bash
LABEL=local-chat-10vu \
OUT=loadtest/baselines/local-chat-10vu.md \
KING_AI_BASE_URL="$KING_AI_BASE_URL" VUS=10 RAMP=30s DURATION=2m \
  loadtest/render-baseline.sh loadtest/results/chat-local.json
```

Only measured values are copied. Missing metrics appear as `n/a`.

## 1000 concurrent SSE

Do not run 1000 VUs as an acceptance test yet. The current executor is core 4 /
max 16 / queue 100 per instance, and resumable SSE is not implemented. The
1000-concurrent target is blocked on P2-2 (executor/queue isolation) and P2-3
(resumable SSE). A 1000-VU run before those changes is expected to produce
rejections and is not evidence of readiness.

## Results hygiene

`loadtest/bin/` and `loadtest/results/` are ignored. `loadtest/baselines/` is
committed as measurement evidence, but only after reviewing the report for host
names, tokens and other environment detail.
```

- [ ] **Step 7: 运行静态校验**

Run: `chmod +x loadtest/build-k6.sh loadtest/render-baseline.sh loadtest/tests/static_test.sh && loadtest/tests/static_test.sh`

Expected: `loadtest static checks passed; runtime load tests NOT RUN`。若本机没有 Node，脚本以退出码 2 输出 `NOT RUN`，不得把 `NOT RUN` 当成通过。

- [ ] **Step 8: 提交**

```bash
git add loadtest/k6/lib/api.js \
  loadtest/k6/chat-sse.js \
  loadtest/k6/rag-query.js \
  loadtest/build-k6.sh \
  loadtest/render-baseline.sh \
  loadtest/tests/static_test.sh \
  loadtest/README.md \
  loadtest/.gitignore
git commit -m "test: add load baseline scripts"
```

---

### Task 13: 可观测性 Compose overlay 与本地栈

**Files:**
- Create: `deploy/observability/docker-compose.yml`
- Create: `deploy/observability/otel-collector/config.yml`
- Create: `deploy/observability/tests/verify.sh`
- Modify: `.env.example`
- Test: `deploy/observability/tests/verify.sh`

**Interfaces:**
- overlay 与根 `docker-compose.yml` 合并使用，命令固定为从仓库根执行：
  `docker compose -f docker-compose.yml -f deploy/observability/docker-compose.yml up -d`
- 新增服务：`otel-collector`、`jaeger`、`prometheus`、`grafana`；只把 UI 端口绑定到 `127.0.0.1`，不新增公网入口。
- overlay 给 `api` 注入：`OTEL_TRACING_EXPORT_ENABLED=true`、
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces`、
  `KING_AI_TRACE_SAMPLE`（默认 `1.0`，仅用于本地）。
- collector 接收 OTLP/HTTP `4318`，导出到 `jaeger:4317`；Jaeger UI 为 `127.0.0.1:16686`。
- Prometheus 使用 Task 10 的配置与告警文件；Grafana 使用 Task 11 的 provisioning。
- `GRAFANA_ADMIN_PASSWORD` 没有默认值，未设置时 `docker compose config` 必须失败。

- [ ] **Step 1: 写校验脚本并确认失败**

`deploy/observability/tests/verify.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
COMPOSE="$ROOT/docker-compose.yml"
COLLECTOR="$ROOT/otel-collector/config.yml"

[[ -f "$COMPOSE" ]] || { echo "missing $COMPOSE" >&2; exit 1; }
[[ -f "$COLLECTOR" ]] || { echo "missing $COLLECTOR" >&2; exit 1; }

for service in otel-collector jaeger prometheus grafana; do
  grep -q "^  ${service}:" "$COMPOSE" \
    || { echo "missing service: $service" >&2; exit 1; }
done

grep -q 'OTEL_TRACING_EXPORT_ENABLED: "true"' "$COMPOSE" \
  || { echo "api must enable trace export in the overlay" >&2; exit 1; }
grep -q 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-collector:4318/v1/traces' "$COMPOSE" \
  || { echo "api must export to the collector" >&2; exit 1; }
grep -q 'KING_AI_TRACE_SAMPLE: ${KING_AI_TRACE_SAMPLE:-1.0}' "$COMPOSE" \
  || { echo "api must expose KING_AI_TRACE_SAMPLE with a local default" >&2; exit 1; }

grep -q '"127.0.0.1:16686:16686"' "$COMPOSE" \
  || { echo "jaeger UI must bind to localhost" >&2; exit 1; }
grep -q '"127.0.0.1:9090:9090"' "$COMPOSE" \
  || { echo "prometheus must bind to localhost" >&2; exit 1; }
grep -q '"127.0.0.1:3000:3000"' "$COMPOSE" \
  || { echo "grafana must bind to localhost" >&2; exit 1; }
if grep -q '"0.0.0.0:' "$COMPOSE"; then
  echo "observability overlay must not bind host ports to 0.0.0.0" >&2
  exit 1
fi

grep -q 'endpoint: 0.0.0.0:4318' "$COLLECTOR" \
  || { echo "collector must listen on OTLP/HTTP 4318" >&2; exit 1; }
grep -q 'endpoint: jaeger:4317' "$COLLECTOR" \
  || { echo "collector must export to jaeger:4317" >&2; exit 1; }
grep -q 'health_check:' "$COLLECTOR" \
  || { echo "collector must expose a health check" >&2; exit 1; }

grep -q 'GRAFANA_ADMIN_PASSWORD' "$REPO/.env.example" \
  || { echo ".env.example must document GRAFANA_ADMIN_PASSWORD" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || {
  echo "NOT RUN: docker is required for compose and collector validation" >&2
  exit 2
}
docker compose version >/dev/null 2>&1 || {
  echo "NOT RUN: docker compose v2 is required" >&2
  exit 2
}

env \
  MYSQL_ROOT_PASSWORD=verify-root \
  MYSQL_PASSWORD=verify-pass \
  REDIS_PASSWORD=verify-pass \
  MINIO_ROOT_USER=verify \
  MINIO_ROOT_PASSWORD=verify-pass \
  KING_AI_SECRET=0123456789abcdef0123456789abcdef \
  KING_AI_ENCRYPTION_KEY=fedcba9876543210fedcba9876543210 \
  KING_AI_ADMIN_PASSWORD=VerifyPass123 \
  GRAFANA_ADMIN_PASSWORD=VerifyPass123 \
  docker compose -f "$REPO/docker-compose.yml" -f "$COMPOSE" config -q

docker run --rm \
  -v "$COLLECTOR:/etc/otelcol-contrib/config.yml:ro" \
  otel/opentelemetry-collector-contrib:0.115.1 \
  validate --config=/etc/otelcol-contrib/config.yml

echo "observability overlay verified"
```

Run: `chmod +x deploy/observability/tests/verify.sh && deploy/observability/tests/verify.sh`

Expected: FAIL，`missing .../deploy/observability/docker-compose.yml`。

- [ ] **Step 2: 创建 collector 配置**

`deploy/observability/otel-collector/config.yml`：

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 256
  batch:
    timeout: 5s
    send_batch_size: 512

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp/jaeger]
  telemetry:
    logs:
      level: info
    metrics:
      level: basic
```

- [ ] **Step 3: 创建 compose overlay**

`deploy/observability/docker-compose.yml`：

```yaml
services:
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.115.1
    restart: unless-stopped
    command: ["--config=/etc/otelcol-contrib/config.yml"]
    volumes:
      - ./deploy/observability/otel-collector/config.yml:/etc/otelcol-contrib/config.yml:ro
    depends_on:
      - jaeger

  jaeger:
    image: jaegertracing/all-in-one:1.62.0
    restart: unless-stopped
    environment:
      COLLECTOR_OTLP_ENABLED: "true"
    ports:
      - "127.0.0.1:16686:16686"

  prometheus:
    image: prom/prometheus:v2.55.1
    restart: unless-stopped
    working_dir: /etc/prometheus
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=15d
    volumes:
      - ./deploy/observability/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./deploy/observability/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro
      - prometheus-data:/prometheus
    ports:
      - "127.0.0.1:9090:9090"
    depends_on:
      api:
        condition: service_healthy

  grafana:
    image: grafana/grafana:11.3.1
    restart: unless-stopped
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?set GRAFANA_ADMIN_PASSWORD in .env}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      GF_ANALYTICS_CHECK_FOR_UPDATES: "false"
    volumes:
      - ./deploy/observability/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./deploy/observability/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    ports:
      - "127.0.0.1:3000:3000"
    depends_on:
      - prometheus

  api:
    environment:
      OTEL_TRACING_EXPORT_ENABLED: "true"
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-collector:4318/v1/traces
      KING_AI_TRACE_SAMPLE: ${KING_AI_TRACE_SAMPLE:-1.0}
    depends_on:
      otel-collector:
        condition: service_started

volumes:
  prometheus-data:
  grafana-data:
```

- [ ] **Step 4: 更新 .env.example**

在文件末尾追加：

```dotenv
# Observability overlay (deploy/observability/docker-compose.yml).
# Required by Grafana; use a unique value of at least 16 characters.
GRAFANA_ADMIN_PASSWORD=REPLACE_WITH_STRONG_PASSWORD
# The local overlay samples every trace. Production should use 0.1 or lower.
KING_AI_TRACE_SAMPLE=1.0
```

- [ ] **Step 5: 运行确认通过**

Run: `deploy/observability/tests/verify.sh`

Expected: `observability overlay verified`。该命令会拉取 collector 镜像并执行 `validate`；如果本机没有 Docker/Compose v2，脚本以退出码 2 输出 `NOT RUN`，不得把 `NOT RUN` 当成通过。

- [ ] **Step 6: 提交**

```bash
git add deploy/observability/docker-compose.yml \
  deploy/observability/otel-collector/config.yml \
  deploy/observability/tests/verify.sh \
  .env.example
git commit -m "feat: add observability stack overlay"
```

---

### Task 14: Helm chart 的可观测性接线

**前置条件：** `2026-10-26-g0-5-6-private-delivery.md` 的 Task 1–10 已执行，`deploy/helm/king-ai` 已存在并通过 `render_test.sh`。

**Files:**
- Modify: `deploy/helm/king-ai/values.yaml`
- Modify: `deploy/helm/king-ai/values.schema.json`
- Modify: `deploy/helm/king-ai/templates/configmap.yaml`
- Modify: `deploy/helm/king-ai/templates/deployment-api.yaml`
- Modify: `deploy/helm/king-ai/README.md`
- Test: `deploy/helm/king-ai/tests/render_test.sh`

**Interfaces:**
- 新增 values：
  - `observability.tracing.enabled`（默认 `false`）
  - `observability.tracing.endpoint`（默认 `http://otel-collector.observability.svc.cluster.local:4318/v1/traces`）
  - `observability.tracing.sampleRate`（默认 `0.1`，取值 0–1）
  - `observability.metrics.scrapeAnnotations`（默认 `true`）
- ConfigMap 注入 `OTEL_TRACING_EXPORT_ENABLED`、`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`、`KING_AI_TRACE_SAMPLE`。
- API Pod 注解默认包含 `prometheus.io/scrape=true`、`prometheus.io/path=/actuator/prometheus`、`prometheus.io/port=8080`。
- NetworkPolicy 的 OTLP egress 端口从 `4317` 改为 `4318`，与 HTTP exporter 一致。
- chart 不打包 Prometheus Operator CRD；使用注解或由平台侧创建 ServiceMonitor。
- 生产 `sampleRate` 禁止默认 1.0；schema 必须拒绝大于 1 的值。

- [ ] **Step 1: 扩展渲染测试并确认失败**

在 `deploy/helm/king-ai/tests/render_test.sh` 中定位 G0-5 Task 7 加入的 `assert_not_contains` 函数定义和紧随其后的 `assert_not_contains "$TMP/default.yaml" 'kind: Secret'`。在这两段之后、`echo "render tests passed"` 之前加入下列内容。**不要插在 kubeconform 段之前**——G0-5 把 kubeconform 放在 `assert_not_contains` 定义之前，提前插入会因函数未定义而失败：

```bash
helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set observability.tracing.enabled=true \
  --set observability.tracing.sampleRate=0.2 \
  --set networkPolicy.enabled=true \
  --set 'networkPolicy.egress.otelCidrs={10.0.40.0/24}' \
  > "$TMP/observability.yaml"

assert_contains "$TMP/observability.yaml" 'OTEL_TRACING_EXPORT_ENABLED: "true"'
assert_contains "$TMP/observability.yaml" 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://otel-collector.observability.svc.cluster.local:4318/v1/traces"'
assert_contains "$TMP/observability.yaml" 'KING_AI_TRACE_SAMPLE: "0.2"'
assert_contains "$TMP/observability.yaml" 'prometheus.io/scrape: "true"'
assert_contains "$TMP/observability.yaml" 'prometheus.io/path: "/actuator/prometheus"'
assert_contains "$TMP/observability.yaml" 'prometheus.io/port: "8080"'
assert_contains "$TMP/observability.yaml" 'port: 4318'
assert_not_contains "$TMP/observability.yaml" 'port: 4317'

if helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set observability.tracing.sampleRate=2 >/dev/null 2>&1; then
  echo "ASSERT FAILED: sampleRate above 1 must be rejected" >&2
  exit 1
fi

if command -v kubeconform >/dev/null; then
  kubeconform -strict -summary -kubernetes-version 1.29.0 "$TMP/observability.yaml"
else
  echo "kubeconform not installed; skipped observability schema validation" >&2
  exit 1
fi
```

G0-5 的 kubeconform 调用只覆盖 `default.yaml`、`kind.yaml`、`ingress.yaml`，不会自动包含新渲染的 `observability.yaml`，所以这里必须单独校验一次。

Run: `deploy/helm/king-ai/tests/render_test.sh`

Expected: FAIL，`ASSERT FAILED ... missing OTEL_TRACING_EXPORT_ENABLED`。脚本在第一个缺失的 ConfigMap 断言处退出，尚未执行 `sampleRate=2` 的拒绝断言；只有 Step 2 完成后重跑，才会真正验证 schema 的取值范围。

- [ ] **Step 2: 增加 values 与 schema**

在 `deploy/helm/king-ai/values.yaml` 的 `pdb:` 之前加入：

```yaml
observability:
  tracing:
    enabled: false
    endpoint: http://otel-collector.observability.svc.cluster.local:4318/v1/traces
    sampleRate: 0.1
  metrics:
    scrapeAnnotations: true
```

把 `networkPolicy.egress.otelPort` 从 `4317` 改为：

```yaml
    otelPort: 4318
```

在 `values.schema.json` 的 `"ingress"` 属性之前加入：

```json
    "observability": {
      "type": "object",
      "properties": {
        "tracing": {
          "type": "object",
          "required": ["enabled", "endpoint", "sampleRate"],
          "properties": {
            "enabled": {"type": "boolean"},
            "endpoint": {"type": "string", "minLength": 1},
            "sampleRate": {"type": "number", "minimum": 0, "maximum": 1}
          }
        },
        "metrics": {
          "type": "object",
          "properties": {
            "scrapeAnnotations": {"type": "boolean"}
          }
        }
      }
    },
```

- [ ] **Step 3: 注入 ConfigMap 环境变量**

在 `templates/configmap.yaml` 的 `data:` 末尾加入：

```yaml
  OTEL_TRACING_EXPORT_ENABLED: {{ .Values.observability.tracing.enabled | quote }}
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: {{ .Values.observability.tracing.endpoint | quote }}
  KING_AI_TRACE_SAMPLE: {{ .Values.observability.tracing.sampleRate | quote }}
```

- [ ] **Step 4: 增加 API Pod 的抓取注解**

在 `templates/deployment-api.yaml` 的 `checksum/config` 注解之后、`api.podAnnotations` 之前加入：

```yaml
        {{- if .Values.observability.metrics.scrapeAnnotations }}
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8080"
        {{- end }}
```

- [ ] **Step 5: 在 Helm README 补充可观测性说明**

在 `deploy/helm/king-ai/README.md` 追加：

```markdown
## 可观测性

chart 不内置 Prometheus、Grafana、Jaeger 或 collector；它们由平台侧部署。

开启 trace 导出：

```bash
helm upgrade king-ai deploy/helm/king-ai -n king-ai \
  --set observability.tracing.enabled=true \
  --set observability.tracing.endpoint=http://otel-collector.observability.svc.cluster.local:4318/v1/traces \
  --set observability.tracing.sampleRate=0.1 \
  --set networkPolicy.egress.otelCidrs={10.0.40.0/24}
```

- `sampleRate` 生产默认 `0.1`；禁止长期使用 `1.0`。
- NetworkPolicy 的 OTLP 端口是 `4318`（OTLP/HTTP）。如使用 gRPC 采集器，自行改端口并确认 exporter 配置一致。
- API Pod 默认带 `prometheus.io/scrape=true` 注解，路径 `/actuator/prometheus`，端口 `8080`。Prometheus Operator 环境可由平台侧据此创建 ServiceMonitor。
- chart 不创建 ServiceMonitor/PodMonitor CRD，避免在未安装 operator 的集群中安装失败。
- 指标和 trace 标签不得包含用户、会话或消息标识；排障使用 requestId/traceId 日志关联。
```

- [ ] **Step 6: 运行确认通过**

Run:

```bash
helm lint deploy/helm/king-ai --strict
deploy/helm/king-ai/tests/render_test.sh
```

Expected: `1 chart(s) linted, 0 chart(s) failed`，`render tests passed`。若本机没有 Helm/kubeconform，脚本以非 0 退出并说明缺失项，不得把跳过当成通过。

- [ ] **Step 7: 提交**

```bash
git add deploy/helm/king-ai/values.yaml \
  deploy/helm/king-ai/values.schema.json \
  deploy/helm/king-ai/templates/configmap.yaml \
  deploy/helm/king-ai/templates/deployment-api.yaml \
  deploy/helm/king-ai/README.md \
  deploy/helm/king-ai/tests/render_test.sh
git commit -m "feat: wire observability into helm chart"
```

---

### Task 15: 文档、CI 与整体验收

**Files:**
- Modify: `docs/DEPLOYMENT.md`
- Create: `docs/operations/observability.md`
- Modify: `.github/workflows/ci.yml`
- Test: 全部校验脚本与后端测试

**Interfaces:**
- `docs/operations/observability.md` 必须包含 12 个告警的锚点，且与 `alerts.yml` 的 `runbook_url` 一一对应。
- CI 新增 `observability` job，不改变现有 `backend`、`frontend`、`security`、`docker` 的职责。
- 文档不得写入未实测的延迟、容量或“已通过”结论；需要实测的步骤写成命令与预期输出。

- [ ] **Step 1: 替换 DEPLOYMENT.md 的可观测性章节**

把 `docs/DEPLOYMENT.md` 的 `## 5. 可观测性` 到 `## 6. 备份与恢复` 之前的内容替换为：

```markdown
## 5. 可观测性

### 5.1 信号端点

- 存活检查：`/actuator/health/liveness`
- 就绪检查：`/actuator/health/readiness`（包含数据库与 Token 存储）
- 容器健康检查：`/api/health`（数据库 + 状态存储，异常返回 503）
- Prometheus 指标：`/actuator/prometheus`
- Trace 导出：OTLP/HTTP，生产默认关闭；设置
  `OTEL_TRACING_EXPORT_ENABLED=true` 与
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 后启用
- OpenAPI：生产默认关闭；需要时设置 `SPRINGDOC_ENABLED=true`
- 结构化日志：`LOG_STRUCTURED_FORMAT=ecs`，日志包含 `requestId`、`traceId`、`spanId` 与 `spaceId` 上下文
- 响应头：`X-Request-Id`、`X-Trace-Id`；两者都可用于日志关联

### 5.2 本地观测栈

```bash
docker compose -f docker-compose.yml \
  -f deploy/observability/docker-compose.yml up -d
```

- Jaeger：<http://127.0.0.1:16686>
- Prometheus：<http://127.0.0.1:9090>
- Grafana：<http://127.0.0.1:3000>（密码来自 `GRAFANA_ADMIN_PASSWORD`）
- 本地 overlay 采样率为 `1.0`；生产使用 `KING_AI_TRACE_SAMPLE`，默认 `0.1`

本地 Jaeger 使用内存存储，重启即丢失，不能作为生产归档。生产应接入组织现有的
OTLP 后端，或为 Jaeger 配置持久化存储。

### 5.3 告警与运行手册

规则文件：`deploy/observability/prometheus/alerts.yml`。重点告警：

- `KingAiApiDown`、`KingAiApiTargetMissing`
- `KingAiHttp5xxHigh`
- `KingAiChatTtftSlow`
- `KingAiSseCapacityHigh`
- `KingAiChatQuotaRejectionHigh`
- `KingAiIndexFailures`、`KingAiIndexLag`
- `KingAiMysqlPoolHigh`
- `KingAiRedisErrors`
- `KingAiMilvusSearchErrors`
- `KingAiAuditWriteFailures`

每个告警的排查步骤见
[可观测性运行手册](operations/observability.md)。告警中的
`runbook_url` 是仓库相对路径，部署到组织内应替换为内部 Wiki 地址。

### 5.4 隐私与基数边界

- 指标标签不得包含用户 ID、会话 ID、请求 ID、消息 ID 或 prompt 内容。
- `space` 仅用于 `king_ai_chat_active_space`，最多 199 个真实空间，其余归入 `other`，合计 200 条序列。
- `king_ai_chat_active` 保持无标签单序列，供 HPA 使用。
- Trace 只允许 ID、模型名、工具名、token 数、状态与耗时；禁止写入 prompt、回答正文、用户名、邮箱、手机号、密钥或 Cookie。
- 审计写入失败会触发 `KingAiAuditWriteFailures`；该告警不能静默。
```

- [ ] **Step 2: 创建运行手册**

`docs/operations/observability.md`：

```markdown
# 可观测性运行手册

本手册覆盖 King AI 的指标、Trace、看板与 12 个告警。所有阈值以
`deploy/observability/prometheus/alerts.yml` 为准；修改阈值时必须同步本手册。

## 1. 快速入口

| 信号 | 位置 |
| --- | --- |
| 指标 | `/actuator/prometheus` |
| 健康 | `/actuator/health/liveness`、`/actuator/health/readiness`、`/api/health` |
| Trace | OTLP/HTTP `/v1/traces`，本地 Jaeger `http://127.0.0.1:16686` |
| 日志 | stdout，包含 `requestId`、`traceId`、`spanId`、`spaceId` |
| 看板 | Grafana `King AI · Chat Overview`、`RAG & Knowledge`、`Platform Health` |

本地启动：

```bash
docker compose -f docker-compose.yml \
  -f deploy/observability/docker-compose.yml up -d
```

## 2. 隐私与基数规则

1. 指标标签只允许 `source`、`model`、`status`、`reason`、`operation`、`outcome`、
   `type`、`result`、`server`、`tool` 与受控的 `space`。
2. 禁止把 `userId`、`sessionId`、`requestId`、消息 ID、prompt 或回答正文放入指标标签。
3. `server` 最多 100 个值，超出归入 `other`；`space` 最多 199 个真实值，第 200 条序列为 `other`，超出归入 `other`。
4. Trace 属性只允许 ID、模型名、工具名、token 数、状态与耗时；禁止写入个人数据与密钥。
5. `king_ai_chat_active` 必须保持无标签单序列；按空间查看使用
   `king_ai_chat_active_space`，不要给全局指标加 `space`。

## 3. 指标目录

Micrometer 中的点号在 Prometheus 中变为下划线；下表使用 Prometheus 名称。
Timer 产生 `_seconds_count`、`_seconds_sum` 与 `_seconds_bucket`，Counter 产生 `_total`。

| 指标 | 类型 | 标签 | 用途 |
| --- | --- | --- | --- |
| `king_ai_chat_requests_total` | counter | `source` | 对话请求量（admin/public） |
| `king_ai_chat_rejected_total` | counter | `reason` | 配额或 SSE 容量拒绝 |
| `king_ai_chat_active` | gauge | 无 | 当前对话数，HPA 使用 |
| `king_ai_chat_active_space` | gauge | `space` | 空间级进行中对话，最多 199 个真实空间 + `other` |
| `king_ai_chat_tool_calls_total` | counter | `source`、`result` | 工具调用结果 |
| `king_ai_chat_ttft_seconds` | timer | `model`、`source` | 首 token 延迟 |
| `king_ai_chat_duration_seconds` | timer | `source`、`status` | 完整对话耗时 |
| `king_ai_chat_tokens_total` | counter | `type`、`model` | prompt/completion token |
| `king_ai_sse_connections` | gauge | 无 | 当前 SSE 连接数 |
| `king_ai_sse_capacity` | gauge | 无 | 单实例 SSE 容量 |
| `king_ai_sse_reconnects_total` | counter | 无 | 带 `Last-Event-ID` 的重连 |
| `king_ai_rag_retrieval_seconds` | timer | `status` | 检索耗时与结果分类 |
| `king_ai_rag_empty_total` | counter | 无 | 有知识库但召回为空 |
| `king_ai_index_jobs_total` | counter | `outcome` | 索引任务结果 |
| `king_ai_index_duration_seconds` | timer | `outcome` | 索引任务耗时 |
| `king_ai_index_pending` | gauge | 无 | 等待任务数 |
| `king_ai_index_failed` | gauge | 无 | 永久失败任务数 |
| `king_ai_index_lag_seconds` | gauge | 无 | 最老 pending 任务等待时长 |
| `king_ai_milvus_requests_total` | counter | `operation`、`status` | Milvus 请求结果 |
| `king_ai_milvus_latency_seconds` | timer | `operation`、`status` | Milvus 请求耗时 |
| `king_ai_mcp_calls_total` | counter | `server`、`tool`、`status` | MCP 调用结果 |
| `king_ai_mcp_latency_seconds` | timer | `server`、`tool`、`status` | MCP 调用耗时 |
| `king_ai_redis_up` | gauge | 无 | 最近一次 Redis 探针（仅 redis 模式） |
| `king_ai_redis_probes_total` | counter | `status` | Redis 探针结果（仅 redis 模式） |
| `king_ai_redis_probe_latency_seconds` | timer | `status` | Redis 探针耗时（仅 redis 模式） |
| `king_ai_audit_write_failures_total` | counter | 无 | 审计写入失败 |

平台指标沿用 Spring Boot 标准名称：`http_server_requests_seconds`、
`jvm_memory_used_bytes`、`jvm_memory_max_bytes`、`hikaricp_connections_active`、
`hikaricp_connections_max`、`up`。

## 4. Trace 结构

根 span 为 `chat.request`，在 HTTP 线程创建；worker 线程显式打开 scope，子 span
继承同一 trace。响应头 `X-Trace-Id` 可用于在 Jaeger 中定位。

| Span | 关键属性 | 说明 |
| --- | --- | --- |
| `chat.request` | `chat.source`、`chat.space_id`、`gen_ai.agent.id`、`gen_ai.request.model`、`request.id` | 根 span；结束时写状态、耗时与 token |
| `rag.retrieve` | `rag.knowledge_base_count`、`rag.role`、`rag.hit_count` | 检索阶段 |
| `llm.chat` | `gen_ai.request.model`、`llm.attempt`、`gen_ai.response.finish_reasons` | 模型调用与重试 |
| `mcp.tool.call` | `mcp.server`、`mcp.tool`、`mcp.result` | MCP 工具调用 |
| `chat.persist` | `chat.message_id`、`chat.status` | 落库阶段 |

排障顺序：先在日志中按 `requestId` 找到 `traceId`，再用 Jaeger 查看子 span 的耗时与
错误标记。不要把 prompt、回答正文或用户身份写入 span。

## 5. 采样

- 本地 `management.tracing.sampling.probability=1.0`，导出默认关闭；overlay 打开导出。
- 生产默认 `KING_AI_TRACE_SAMPLE=0.1`，由 `application-prod.yml` 读取。
- 当前是头部采样：低采样率下，偶发错误 trace 可能未被采集。不要用 trace 数量
  推断错误总量；错误率以指标为准。
- 调整采样率时同步更新 Helm values、`.env.example` 与本文档，并观察 collector 与
  后端网络开销。生产禁止长期使用 `1.0`。

## 6. 告警处置

### KingAiApiDown

<a id="api-down"></a>

1. `up{job="king-ai-api"}` 是否为 0，Prometheus target 页面是否报错。
2. `kubectl -n <ns> get pods`、`kubectl describe`、`kubectl logs --previous`。
3. 检查 `/actuator/health/liveness` 与 `/api/health`；区分进程崩溃、依赖不可用与网络隔离。
4. 恢复后确认 `up` 回到 1，并检查 5xx、SSE 容量与索引积压是否伴随变化。

### KingAiApiTargetMissing

<a id="api-target-missing"></a>

1. 检查 `prometheus.yml` 的 `job_name: king-ai-api` 与 `targets`。
2. 检查服务名、端口、namespace 与网络策略；Kubernetes 环境确认 target 发现配置。
3. 如果是配置变更导致，回滚 Prometheus 配置并重新加载。
4. 确认 `absent(up{job="king-ai-api"})` 消失后再关闭告警。

### KingAiHttp5xxHigh

<a id="http-5xx"></a>

1. 看 `king_ai:http_5xx_ratio5m` 与 `sum by (status, uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))`。
2. 按 `requestId` 查日志，确认异常栈与依赖错误。
3. 检查数据库、Redis、Milvus、模型供应商与 MCP 下游。
4. 如为版本回归，按 Helm 回滚流程处理；回滚后观察至少 10 分钟。

### KingAiChatTtftSlow

<a id="ttft"></a>

1. 看 `king_ai:chat_ttft_p95_10m` 与 `king_ai_chat_ttft_seconds` 的 `model`、`source` 切片。
2. 对比 `rag.retrieve`、`llm.chat` span 与模型供应商状态页。
3. 检查 SSE 容量、对话并发与 executor 队列；队列饱和会放大首 token 延迟。
4. 仅在确认上游恢复后调整阈值；不要用放宽阈值掩盖回归。

### KingAiSseCapacityHigh

<a id="sse-capacity"></a>

1. 看 `king_ai:sse_capacity_ratio`、`king_ai_sse_connections`、`king_ai_sse_capacity`。
2. 检查 `king_ai_chat_active` 与 HPA 副本数；确认 HPA 指标可用。
3. 检查 `king_ai_chat_rejected_total{reason="sse_capacity"}` 是否已出现拒绝。
4. 扩容或降低单实例负载；不要修改 `king_ai_chat_active` 的标签结构。

### KingAiChatQuotaRejectionHigh

<a id="quota-rejection"></a>

1. 看 `king_ai:chat_quota_rejection_ratio5m` 与 `king_ai_chat_rejected_total{reason="space_quota"}`。
2. 用 `king_ai_chat_active_space` 找出高并发空间；不要查询用户级标签。
3. 确认是正常业务峰值、配额配置过小还是异常调用。
4. 调整空间配额需业务批准并留审计记录；不得临时移除并发保护。

### KingAiIndexFailures

<a id="index-failures"></a>

1. 看 `king_ai:index_failures_10m`、`king_ai_index_failed` 与 `king_ai_index_jobs_total{outcome="failed"}`。
2. 查询失败任务的 `lastError`，区分解析、嵌入、Milvus 写入与权限问题。
3. 检查 Milvus 健康与集合是否存在；修复后通过 reindex 重新入队。
4. 持续失败时停止重复入队，避免放大下游压力。

### KingAiIndexLag

<a id="index-lag"></a>

1. 看 `king_ai_index_pending` 与 `king_ai_index_lag_seconds`。
2. 检查 `KnowledgeIndexWorker` 日志、数据库连接与 Milvus 延迟。
3. 确认没有长事务或锁等待；必要时扩容 worker（需评估并发写冲突）。
4. 积压回落后确认 `king_ai_index_lag_seconds` 回到阈值以下。

### KingAiMysqlPoolHigh

<a id="mysql-pool"></a>

1. 看 `king_ai:mysql_pool_usage`、`hikaricp_connections_active` 与 `hikaricp_connections_max`。
2. 查找慢查询、长事务与连接泄漏；结合 `http_server_requests_seconds` 定位接口。
3. 检查数据库最大连接数与代理限制。
4. 调整连接池或扩容数据库前，先确认不是代码持有连接过久。

### KingAiRedisErrors

<a id="redis-errors"></a>

1. 看 `king_ai_redis_up`、`king_ai_redis_probes_total{status="error"}` 与错误率记录规则。
2. 检查 Redis 内存、淘汰、持久化与网络；确认 Token 存储是否受影响。
3. 如果认证/会话受影响，按故障流程降级并通知用户；不要把 Token 写入日志。
4. 恢复后观察探针与登录成功率。

### KingAiMilvusSearchErrors

<a id="milvus-errors"></a>

1. 看 `king_ai:milvus_search_error_ratio5m`、`king_ai_milvus_requests_total{operation="search"}`。
2. 检查集合是否存在、维度是否匹配、Milvus 健康与鉴权。
3. 区分 `error` 与 `ignored`：`ignored` 是尽力而为请求，不应触发本告警。
4. 检索持续失败时确认对话是否已降级为无 RAG，并在恢复后补测召回。

### KingAiAuditWriteFailures

<a id="audit-write-failures"></a>

1. 该告警表示审计写入失败，属于合规事件，不能忽略。
2. 检查审计表、数据库连接、磁盘与权限；按 `requestId` 找失败请求。
3. 在恢复前评估是否需要暂停高风险操作；不要删除或覆盖审计记录。
4. 恢复后确认 `king_ai_audit_write_failures_total` 不再增长，并按组织流程上报。

## 7. 压测基线

```bash
loadtest/tests/static_test.sh
loadtest/build-k6.sh
mkdir -p loadtest/results
SUMMARY_OUT=loadtest/results/chat-local.json \
  loadtest/bin/k6-sse run loadtest/k6/chat-sse.js
loadtest/render-baseline.sh loadtest/results/chat-local.json
```

只记录实测值；`n/a` 表示未采集。当前 executor 为 core 4 / max 16 / queue 100，
1000 并发 SSE 的验收阻塞在 P2-2 与 P2-3，任何基线都不得宣称该目标已达成。

## 8. 变更检查表

- [ ] 新指标有单测（`SimpleMeterRegistry`），标签不含高基数或个人数据。
- [ ] 新告警有 `severity`、`summary`、`description`、`runbook_url`。
- [ ] 看板表达式引用的指标真实存在，`jq` 校验通过。
- [ ] 运行手册锚点与 `runbook_url` 一致。
- [ ] `promtool check rules` 通过。
- [ ] 更新 `docs/DEPLOYMENT.md` 与 Helm README（如涉及部署参数）。
```

- [ ] **Step 3: 在 CI 增加 observability job**

在 `.github/workflows/ci.yml` 的 `docker:` job 之前加入：

```yaml
  observability:
    name: Observability config and load-test static checks
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v4

    - name: Set up Node.js
      uses: actions/setup-node@v4
      with:
        node-version: '22'

    - name: Install promtool
      run: |
        curl -sSLo prometheus.tar.gz https://github.com/prometheus/prometheus/releases/download/v2.55.1/prometheus-2.55.1.linux-amd64.tar.gz
        tar -xzf prometheus.tar.gz prometheus-2.55.1.linux-amd64/promtool
        sudo mv prometheus-2.55.1.linux-amd64/promtool /usr/local/bin/promtool
        promtool --version

    - name: Prometheus rules
      run: deploy/observability/prometheus/tests/verify.sh

    - name: Shell script lint
      run: |
        shellcheck --severity=error \
          deploy/observability/prometheus/tests/verify.sh \
          deploy/observability/grafana/validate.sh \
          deploy/observability/tests/verify.sh \
          loadtest/build-k6.sh \
          loadtest/render-baseline.sh \
          loadtest/tests/static_test.sh

    - name: Grafana dashboards
      run: deploy/observability/grafana/validate.sh

    - name: Load-test static checks
      run: loadtest/tests/static_test.sh

    - name: Compose and collector validation
      run: deploy/observability/tests/verify.sh

    - uses: azure/setup-helm@v4
      if: ${{ hashFiles('deploy/helm/king-ai/Chart.yaml') != '' }}
      with:
        version: v3.15.4

    - name: Render chart with observability enabled
      if: ${{ hashFiles('deploy/helm/king-ai/Chart.yaml') != '' }}
      run: |
        helm template king-ai deploy/helm/king-ai --namespace king-ai \
          --set observability.tracing.enabled=true \
          --set observability.tracing.sampleRate=0.2 > /dev/null
```

两个 Helm step 都以 `Chart.yaml` 是否存在为条件。G0-5 尚未执行时 CI 会跳过这两步而不是失败；执行人必须在交付记录中把“Helm 可观测性渲染”标记为 `NOT RUN`，不能把 skipped 当作通过。

- [ ] **Step 4: 运行后端与配置校验**

Run:

```bash
mvn -pl king-ai-api clean test
deploy/observability/prometheus/tests/verify.sh
deploy/observability/grafana/validate.sh
loadtest/tests/static_test.sh
deploy/observability/tests/verify.sh

# 本机无 shellcheck 时记录 NOT RUN；CI 必须执行同一组检查
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck --severity=error \
    deploy/observability/prometheus/tests/verify.sh \
    deploy/observability/grafana/validate.sh \
    deploy/observability/tests/verify.sh \
    loadtest/build-k6.sh loadtest/render-baseline.sh loadtest/tests/static_test.sh
else
  echo "NOT RUN: shellcheck unavailable"
fi

# G0-5 未执行时不要伪造 chart 校验
if [[ -x deploy/helm/king-ai/tests/render_test.sh ]]; then
  deploy/helm/king-ai/tests/render_test.sh
else
  echo "NOT RUN: deploy/helm/king-ai/tests/render_test.sh (G0-5 not executed)"
fi
```

Expected:

- `mvn` 输出 `BUILD SUCCESS`，测试数不少于执行前的基线。
- `prometheus rules verified: 12 alerts`。
- `grafana dashboards verified: 3 dashboards, 25 panels`。
- `loadtest static checks passed; runtime load tests NOT RUN`。
- `observability overlay verified`。
- shellcheck 无 error；本机无 shellcheck 时明确输出 `NOT RUN: shellcheck unavailable`。
- G0-5 已执行时输出 `render tests passed`；未执行时明确输出 `NOT RUN: ... (G0-5 not executed)`。

任一脚本输出 `NOT RUN` 时，记录缺失的依赖（Docker、jq、Node、Helm、kubeconform），
不得把 `NOT RUN` 写成通过。运行期验证（Jaeger 实际收到 trace、Grafana 实际展示数据、
k6 实际压测）未执行时，交付说明必须明确写“未运行”。

- [ ] **Step 5: 隐私与一致性自检**

Run:

```bash
git grep -n -E '"(userId|sessionId|requestId|messageId)"[[:space:]]*[,)]' -- \
  'deploy/observability/**' 'king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java' \
  'king-ai-api/src/main/java/com/king/ai/observability/**' \
  || echo "no forbidden metric labels"
git status --short
```

Expected:

- 输出 `no forbidden metric labels`；若出现匹配，只允许是注释或明确的拒绝测试，
  不得作为 `tag`/`counter`/`timer`/`gauge` 的标签名。
- `git status` 中没有 `.env`、`loadtest/results/`、`loadtest/bin/` 或凭据文件。

- [ ] **Step 6: 提交**

```bash
git add docs/DEPLOYMENT.md docs/operations/observability.md .github/workflows/ci.yml
git commit -m "docs: add observability runbook and ci checks"
```

---

## 完成标准

- Task 1–13、15 的提交都存在，且每个任务的测试命令有真实输出记录；Task 14 仅在 G0-5 的 `deploy/helm/king-ai` 已存在时要求提交，否则在交付说明中记录 `NOT RUN`。
- 12 个告警与运行手册锚点一一对应；3 个看板通过 `jq` 校验。
- 指标标签通过隐私与基数自检；`king_ai_chat_active` 保持无标签。
- G0-5 已执行时，Helm 可渲染 tracing 开关、采样率、抓取注解与 4318 egress；否则该项标记 `NOT RUN`。
- 压测只记录实测值；1000 并发 SSE 明确标记为 P2-2/P2-3 阻塞项。
- 任何 `NOT RUN` 都在交付说明中列出，不写成通过。
