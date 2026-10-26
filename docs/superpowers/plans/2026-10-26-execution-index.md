# Gate 0 + P2-1 执行索引

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Each subplan is executed task-by-task with its own test/commit cycle. This index only sets order, prerequisites, and cross-plan merge rules; it does not replace any subplan.

**Status:** 2026-10-26 静态自检完成。未执行任何代码修改、构建、部署或压测；本索引中的运行期检查一律标记为 `NOT RUN`，直到执行人实际运行并粘贴输出。

**范围:** G0-1、G0-2、G0-3、G0-4、G0-5、G0-6 与 P2-1。P2-2/P2-3 不在本批范围。

---

## 1. 执行顺序

顺序不可调换：

| 阶段 | 子计划 | 任务数 | Flyway | 依赖 | 阶段门 |
| --- | --- | --- | --- | --- | --- |
| 1 | `2026-10-26-g0-1-2-content-compliance.md`（G0-1/G0-2） | 7 | V9 | 无 | `mvn -pl king-ai-api clean test`、`package`、验收清单全勾 |
| 2 | `2026-10-26-g0-3-4-data-compliance.md`（G0-3/G0-4） | 8 | V10、V11 | 阶段 1 的 `RedactionService` | `mvn -pl king-ai-api test`（含 `ComplianceControllerTest`） |
| 3 | `2026-10-26-g0-5-6-private-delivery.md`（G0-5/G0-6） | 12 | 不新增，Job 执行 V1–V11 | 阶段 1、2 已完成，镜像内包含 V9–V11 | `mvn -B -ntp clean verify`、`render_test.sh`、`deploy/backup/tests/run_all.sh`、镜像构建 |
| 4 | `2026-10-26-p2-1-observability.md`（P2-1） | 15 | 不新增 | Task 1–13/15 无硬依赖；Task 14 依赖阶段 3 的 `deploy/helm/king-ai` | Task 15 的全量命令与 `NOT RUN` 记录 |

理由：

- 阶段 2 的导出必须调用阶段 1 的脱敏，不能导出原文；阶段 2 的 `V10/V11` 也不得修改 `V1–V9`。
- 阶段 3 的迁移 Job 执行镜像内全部 Flyway 文件；先完成 V9–V11，才能验证真实升级路径。
- 阶段 4 与阶段 1 都修改 `ChatRuntime`、`AuditLogService`、`application.yml`。按此顺序执行后，P2-1 的改动叠加在合规代码之上，避免 P2-1 的示例签名覆盖合规参数。
- 阶段 4 的 Task 14 直接扩展阶段 3 的 chart；chart 不存在时 Task 14 必须标记 `NOT RUN`，不得手写一个临时 chart 冒充。

---

## 2. 执行前统一前置条件

在任何子计划的 Task 1 之前完成一次，且只做一次：

1. 当前工作区不是 Git 仓库（`git rev-parse --is-inside-work-tree` 返回 128）。执行：

   ```bash
   git init
   git add -A
   git commit -m "chore: baseline before Gate 0 and P2-1"
   ```

   提交前确认 `.env`、`data/`、`*.log`、导出文件、真实 Token/密码未进入暂存区。`.gitignore` 已覆盖 `.env`、`target/`、`node_modules/`、`dist/`、`king-ai-api/data/`。

2. 工具链核对（本机现状，2026-10-26）：

   | 工具 | 状态 | 缺失时的处理 |
   | --- | --- | --- |
   | Java 17、Maven 3.9 | 可用 | — |
   | Docker、Helm、Node.js | 可用 | — |
   | `jq` | 未找到 | 安装后再跑 Grafana/JSON 校验；否则记 `NOT RUN` |
   | `shellcheck` | 未找到 | 记 `NOT RUN`；CI 的 `shellcheck --severity=error` 不得跳过 |
   | `promtool` | 未找到 | 记 `NOT RUN`；不得手写“规则通过” |
   | `kubeconform` | 未找到 | 记 `NOT RUN`；`render_test.sh` 会以非零退出，不能改写脚本绕过 |
   | `kind` | 未找到 | 记 `NOT RUN: kind e2e`；不得声称安装/升级/回滚已验证 |
   | `k6`/`xk6-sse` | 未构建 | 按 P2-1 Task 12 的 `build-k6.sh` 构建；未构建则基线记 `NOT RUN` |

3. 迁移版本已占用：当前最大为 `V8__agent_versioning_tool_policy.sql`。本批分配：

   - V9：内容合规设置（G0-1/G0-2）
   - V10：保留策略、法律保留、导出任务（G0-3）
   - V11：访问复核、登录时间、密钥轮换时间（G0-4）

   不得并行创建同一版本号；不得修改已发布的 V1–V8。

4. 环境变量与凭据：只通过环境变量或 Kubernetes Secret 注入；备份脚本使用 `MYSQL_PWD`，不得把密码放进命令行参数、日志、manifest 或提交文件。

---

## 3. 各子计划入口

### 阶段 1：G0-1/G0-2 内容合规

文件：`docs/superpowers/plans/2026-10-26-g0-1-2-content-compliance.md`

任务顺序：

1. 空间合规配置与通用审计事件（V9）
2. PII 脱敏引擎
3. 本地内容审核与外部适配降级
4. 流式脱敏安全带与输入合规准备
5. 接入 `ChatRuntime`（输入、检索、工具、输出、落库）
6. 合规配置管理端点
7. 文档与全量验证

阶段门：

```bash
mvn -pl king-ai-api clean test
mvn -pl king-ai-api -DskipTests package
```

并逐项勾选 Task 7 Step 3 的验收项。公开对话在审核不可用且 `fail-closed-on-error=true` 时必须拦截；日志、审计、指标不得出现命中的明文。

### 阶段 2：G0-3/G0-4 数据保留、法律保留、导出与访问复核

文件：`docs/superpowers/plans/2026-10-26-g0-3-4-data-compliance.md`

任务顺序：

1. V10 保留策略、法律保留、导出任务表
2. 保留期计算、策略 CRUD 与法律保留
3. 本地导出存储与文件写入
4. 异步导出任务
5. 保留清理任务与审计保留接入
6. V11 访问复核、登录时间与密钥轮换时间
7. 访问复核报告、决策与 CSV
8. 合规控制器、季度任务与文档

阶段门：

```bash
mvn -pl king-ai-api -Dtest=ComplianceControllerTest test
mvn -pl king-ai-api test
```

默认 `retention.enabled=false`、`dry-run=true`；首次开启必须保持 dry-run 观察一轮。导出正文必须经 G0-1 的 `RedactionService`，CSV 必须做公式注入中和。

### 阶段 3：G0-5/G0-6 Helm/K8s 与备份恢复

文件：`docs/superpowers/plans/2026-10-26-g0-5-6-private-delivery.md`

任务顺序：

1. Flyway 独立迁移模式
2. Helm chart 骨架、values 与配置
3. 非 root API/UI 工作负载与 UI 镜像加固
4. 迁移 Job、导出 PVC 与部署顺序
5. Ingress、HPA、PDB 与 NetworkPolicy
6. 内置开发依赖与 kind values
7. Chart 静态校验、CI 与 kind 安装/升级/回滚
8. 备份基础库与 MySQL 全量备份
9. binlog 归档与 Milvus 备份
10. 恢复、校验与隔离 namespace 演练
11. 对象存储生命周期与备份调度
12. 部署文档、迁移分配说明与全量验证

阶段门：

```bash
mvn -B -ntp clean verify
deploy/helm/king-ai/tests/render_test.sh
deploy/backup/tests/run_all.sh
shellcheck deploy/backup/*.sh deploy/backup/tests/*.sh deploy/helm/king-ai/tests/*.sh
docker build -f king-ai-ui/Dockerfile -t king-ai-ui:verify .
docker build -f king-ai-api/Dockerfile -t king-ai-api:verify .
```

`kind-e2e.sh` 仅在 kind 可用时执行；未执行必须在交付记录写 `NOT RUN: kind e2e`。RPO/RTO 只写演练实测值，不得估算。

### 阶段 4：P2-1 可观测性与 SLO

文件：`docs/superpowers/plans/2026-10-26-p2-1-observability.md`

任务顺序：

1. OpenTelemetry 依赖、配置与本地/生产采样
2. `RequestIdFilter` 写入 `traceId/spanId`，`ChatTracing` 封装
3. `ChatRuntime` 端到端 Span
4. `ChatMetrics` 与 SSE 容量指标
5. `ChatRuntime` 指标接线、SSE 准入与重连计数
6. 知识索引、RAG 与队列积压指标
7. Milvus 请求指标
8. MCP 工具指标
9. 审计写入失败与 Redis 探针
10. Prometheus 抓取、记录规则与告警
11. Grafana 数据源、看板与校验
12. k6 基线脚本与结果渲染
13. 可观测性 Compose overlay 与本地栈
14. Helm chart 的可观测性接线（依赖阶段 3）
15. 文档、CI 与整体验收

阶段门：执行 Task 15 Step 4 的完整命令块。预期输出必须是实际输出：

- `prometheus rules verified: 12 alerts`
- `grafana dashboards verified: 3 dashboards, 25 panels`
- `loadtest static checks passed; runtime load tests NOT RUN`
- `observability overlay verified`
- shellcheck 无 error，或明确 `NOT RUN: shellcheck unavailable`
- G0-5 已执行时 `render tests passed`；未执行时 `NOT RUN: ... (G0-5 not executed)`

Jaeger 实际收到 trace、Grafana 实际展示数据、k6 实际压测都只能在对应栈启动后人工核对；未做就写“未运行”。

---

## 4. 跨计划合并规则（执行人必读）

1. **`ChatRuntime` 方法签名**：阶段 1 的 `run` 会追加 `ComplianceSettings compliance`、`boolean publicChat`、`int inputRedactions`、`boolean inputMasked` 四个参数；`streamChatWithRetry` 与 `streamWithRetry` 各追加两个合规参数。阶段 4 的 Task 3 只在“当前已有参数”末尾追加 `Span chatSpan`，Task 5 再追加 `Runnable cleanup`，TTFT 的 `String source` 追加在合规参数之后。不得照抄子计划里的旧签名，不得删除合规参数。
2. **`ChatRuntime` 流式回调**：阶段 1 引入 `ComplianceStreamGuard` 与脱敏后的发送逻辑；阶段 4 只合并清理动作，保留“先脱敏/审核、再发送/落库”的顺序。
3. **`ChatRuntime.finally`**：阶段 1 在 `recordChatMetrics` 前增加合规计数；阶段 4 增加 span 标签/结束、把五行直接清理替换为 `cleanup.run()`。两者都要保留；`cleanup.run()` 只释放一次，避免 `releaseQuota` 重复扣减。
4. **`ChatRuntime.meterRegistry`**：阶段 1 用 `meterRegistry.counter("king.ai.compliance.*")` 记录合规指标。阶段 4 的 Task 5 **不得删除** `MeterRegistry meterRegistry` 字段及其导入，只删除 `@PostConstruct registerMetrics`、`recordChatMetrics`、`activeChats` 和 `Gauge`/`Timer`/`PostConstruct`/`AtomicInteger` 导入；否则阶段 1 的合规计数器无法编译。
5. **`AuditLogService`**：阶段 1 增加 `recordEvent` 并让 `recordChat` 复用；阶段 4 增加 `MeterRegistry` 字段、构造器参数与 `king.ai.audit.write.failures` 计数。合并时保留 `recordEvent` 全部调用点。
6. **`application.yml`**：阶段 1 增加 `king-ai.compliance`，阶段 4 在现有 `management:` 节点内增加 tracing/metrics。不得出现第二个顶层 `management:` 或重复的 `king-ai:` 键。
7. **`.github/workflows/ci.yml`**：阶段 3 和阶段 4 都把新 job 插在 `docker:` 之前。两个 job 都保留；阶段 4 的 Helm step 用 `hashFiles('deploy/helm/king-ai/Chart.yaml') != ''` 守卫，chart 缺失时 skipped 必须记为 `NOT RUN`。
8. **Helm chart**：阶段 4 Task 14 只扩展阶段 3 的 `values.yaml`、`values.schema.json`、`configmap.yaml`、`deployment-api.yaml`、`README.md`、`render_test.sh`。`render_test.sh` 的插入点在 `assert_not_contains` 定义之后、`echo "render tests passed"` 之前；G0-5 把 kubeconform 放在该定义之前，提前插入会因函数未定义失败。`observability.yaml` 需单独跑一次 kubeconform。
9. **空间序列上限**：`king.ai.chat.active.space` 只允许 199 个真实空间，第 200 条序列固定为 `other`。容量判断必须在 `spaceLock` 内完成；结束时按同一桶扣减。
10. **Grafana 表达式断言**：JSON 中的引号是转义后的 `\"`，`validate.sh` 的 needle 必须写 `'up{job=\"king-ai-api\"}'`，不能写 `'up{job="king-ai-api"}'`。
11. **依赖注入**：所有新增组件使用构造器注入；测试使用 `SimpleMeterRegistry`/`SimpleTracer`，不得依赖真实 Prometheus、Grafana、collector、Redis 或 Milvus。

---

## 5. 验证与交付记录

每个任务结束必须：

1. 运行该任务列出的定向测试，Expected 必须与子计划一致。
2. 运行 `mvn -pl king-ai-api test`（P2-1 Task 10–15 至少运行对应脚本，Task 15 再跑全量）。
3. 提交只包含该任务涉及的文件；不提交 `.env`、`data/`、导出文件、日志、真实凭据。
4. 在交付记录中写“实际运行的命令 + 实际输出”。以下情况只能记 `NOT RUN`，不能记“通过”：
   - 本机缺 `jq`、`shellcheck`、`promtool`、`kubeconform`、`kind`
   - 未启动 Docker/Compose、未启动 Jaeger/Grafana/Prometheus
   - 未构建 `xk6-sse`、未执行 k6
   - 未执行 `kind-e2e.sh`
   - G0-5 chart 尚不存在，Task 14 无法执行
   - 未做真实备份恢复演练，RPO/RTO 无实测值

当前静态自检已确认（仅文档层面）：

- 四份子计划均包含 Goal/Architecture/Tech Stack/Spec、Global Constraints、Files/Interfaces、逐步骤测试与提交。
- V9/V10/V11 无冲突；P2-1 不新增迁移。
- P2-1 的 OTLP 属性、`SimpleTracer`/`SimpleSpan` API、`ChatRuntime` 方法位置、`AuditLogService` 构造器、`MilvusVectorStore` 构造器、`KnowledgeIndexQueue` 构造器均与当前代码一致。
- P2-1 中直方图/SLO 的 map key 已改为 `"[metric.name]"`，测试读取 `...[metric.name]`。
- P2-1 的空间序列上限、幂等 cleanup、Grafana 转义、Helm 插入点与 CI 守卫已修正。
- G0-5 的 `render_test.sh` 顺序、`otelCidrs`/`otelPort`、`deployment-api.yaml` 注解位置与 P2-1 兼容。

以下检查 **NOT RUN**（不得视为通过）：`mvn test`/`mvn verify`、`helm lint`/`helm template`、`kubeconform`、`shellcheck`、`promtool`、`jq` 校验、Docker/Compose 启动、k6 压测、kind e2e、真实备份恢复演练、Jaeger/Grafana 实际收数。

---

## 6. 执行方式

**给执行 subagent 的首条指令模板：**

> 读取 `docs/superpowers/plans/2026-10-26-execution-index.md`、`<子计划路径>` 与仓库根目录的 `AGENTS.md`（如存在）。只执行 `<Task N>`：按 TDD 先写失败测试、运行确认失败、实现、运行确认通过、提交。遵守子计划的 Global Constraints 与本索引第 4 节的跨计划合并规则。报告实际运行的命令与输出；未运行的检查写 `NOT RUN`。不要开始下一个任务，不要修改无关文件。

**方式 A（推荐）：Subagent-Driven**。每个任务派发一个全新 subagent，任务间由主会话审查 diff、测试输出与提交内容；适合本批跨 4 份计划、42 个任务的长链路，能隔离上下文并逐任务纠偏。

**方式 B：Inline Execution**。当前会话按 `superpowers:executing-plans` 批量执行，在每个阶段门暂停复核；适合希望实时看到每一步、且能持续盯着测试输出的人工执行。

无论哪种方式，先完成第 2 节的 `git init` 基线提交，再从阶段 1 Task 1 开始。不要并行执行两个会修改 `ChatRuntime` 或 `application.yml` 的任务。
