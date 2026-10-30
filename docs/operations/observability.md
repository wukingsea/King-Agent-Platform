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

1. 看 `king_ai:chat_quota_rejection_ratio5m`、`king_ai_chat_rejected_total{reason="space_quota"}` 与 `king_ai_finops_quota_rejected_total`。
2. 用 `king_ai_chat_active_space` 找出高并发空间；不要查询用户级标签。
3. 确认是正常业务峰值、并发配额或月度 Token 配额配置过小，还是异常调用；`GET /api/finops/usage` 可查当月 Token 与剩余配额。
4. 调整空间配额需业务批准并留审计记录；不得临时移除并发保护或配额检查。

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
