> **历史快照（2026-10）**：本文记录评审时的代码状态与改进建议，不代表当前实现。后续已落地空间 RBAC、MFA/OIDC、合规审计、持久化索引任务、可观测性、发布治理和离线交付等能力。当前架构与运行方式请以 [架构说明](../architecture.md)、[部署指南](../DEPLOYMENT.md) 和代码为准；不要将下文中的“缺失”清单当作现状。

## 总体判断

**架构方向是合理的，但当前成熟度是“单实例 MVP / 演示级”，还不是“企业生产级”。**

如果目标是快速验证产品、做私有化单机试点，这套设计是成立的：模块化单体、领域分包、运行期适配器、空间隔离、SSE 协议都有清晰设计。但如果目标是多租户 SaaS 或大型企业核心系统，当前还缺一整层“生产化能力”，问题主要集中在**授权边界、分布式状态、异步可靠性、审计治理和工程保障**。

不建议现在拆微服务。当前更适合保留模块化单体，先把边界和安全补硬；否则拆分会把现在的事务、状态和一致性问题放大。

| 维度 | 判断 | 说明 |
|---|---|---|
| 架构风格 | 合理 | 模块化单体、领域分包、适配器边界清晰 |
| 多租户 | 基础可用 | 全链路有 `space_id`，但授权粒度太粗，隔离靠手写过滤条件 |
| 身份与权限 | 不足 | 只有全局 ADMIN + 空间成员两档，没有空间角色和资源所有权 |
| 安全基线 | 偏弱 | 有加密/脱敏基础，但存在默认凭据、明文 headers、SSRF、无 SSO/MFA |
| 可靠性 | 单机级 | 取消、限流、登录保护、索引任务都在进程内 |
| 可观测性 | 缺失 | 健康检查恒为 UP，无 metrics/tracing/audit |
| 工程质量 | 不足 | 后端与前端测试均为 0，无 CI、无 OpenAPI |

---

## 一、哪些设计是合理的

1. **模块化单体 + 端口适配器**
   `TokenStore`、`VectorStore`、`LlmClient` 都抽象成了可替换接口，本地用内存/H2/本地向量/Mock，生产切 Redis/MySQL/Milvus/OpenAI 兼容接口。这种设计让项目在 MVP 阶段保持零依赖可运行，同时保留了替换基础设施的能力，是这套架构最值得保留的部分。
   参见 [TokenStore.java](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/auth/TokenStore.java#L1)、[VectorStore.java](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/vector/VectorStore.java#L1)、[LlmClient.java](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/llm/LlmClient.java#L1)。

2. **空间隔离的主链路是通的**
   请求通过 `X-Space-Id` 指定空间，`AuthInterceptor` 会校验用户状态和空间成员关系，再写入 `RequestContext`。大部分业务表和查询都带 `space_id`，这是正确的基础。
   参见 [AuthInterceptor.java:57](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/common/security/AuthInterceptor.java#L57)。

3. **SSE 对话协议设计得比较完整**
   `meta/citation/reasoning/content/tool_call/tool_result/usage/done/error` 事件齐全，有 `requestId` 取消、增量落库、工具调用轮次上限、token/耗时统计。这比很多 MVP 的“直接吐文本”要成熟。
   参见 [ChatRuntime.java:71](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java#L71)。

4. **若干安全细节做得不错**
   API Key 使用 AES-GCM 加密、密码使用 BCrypt、Markdown 经过 DOMPurify 清洗、技能文件读取拒绝 `..` 路径穿越、外链带 `noopener noreferrer`。这些说明作者有基本的安全意识。
   参见 [CryptoService.java:26](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/common/CryptoService.java#L26)、[MarkdownView.vue:50](file:///D:/codes/king/King-AI-Platform/king-ai-ui/src/components/MarkdownView.vue#L50)、[SkillService.java:326](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/skill/SkillService.java#L326)。

5. **交付和文档意识较好**
   Flyway 管理迁移、Docker Compose 一键启动、Nginx 对 SSE 做了 `proxy_buffering off`、README 和架构文档明确写了已知边界。文档甚至主动提示“生产应清理演示数据”，这在开源项目里不多见。
   参见 [docker-compose.yml:120](file:///D:/codes/king/King-AI-Platform/docker-compose.yml#L120)、[nginx.conf:22](file:///D:/codes/king/King-AI-Platform/king-ai-ui/nginx.conf#L22)、[README.md:286](file:///D:/codes/king/King-AI-Platform/README.md#L286)。

---

## 二、P0：真实客户上线前必须解决

### 1. 租户内没有角色和所有权，隔离靠“每个查询记得加 `space_id`”

这是最严重的问题。

- `king_ai_user_space` 只有 `user_id`、`space_id`，没有空间角色，见 [V1__schema.sql:27](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/db/migration/mysql/V1__schema.sql#L27)。
- 配置类写操作几乎全部是全局 `@RequireAdmin`，普通空间成员只能读；但会话 rename/delete/send 又没有管理员限制，形成“配置不能改、别人的会话可以改”的错位。
- `ChatController` 的会话列表、消息、重命名、删除都没有 `@RequireAdmin`，见 [ChatController.java:38](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatController.java#L38)。
- `ChatService.listSessions` 只按 space 过滤，不按用户/访客过滤；`messages`、`rename`、`deleteSession` 也只校验会话属于当前空间，见 [ChatService.java:51](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L51)、[ChatService.java:99](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L99)、[ChatService.java:117](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L117)、[ChatService.java:129](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L129)。
- 后台发送只校验 space/agent/source，不校验会话归属；`ensureSession` 只对 `PUBLIC` 校验 visitor，`ADMIN` 会话可以被任意成员接着聊，见 [ChatService.java:137](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L137)、[ChatService.java:150](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L150)。
- MyBatis 只注册了分页插件，没有租户拦截器或行级安全；隔离完全依赖每个查询手写 `space_id`，任何新查询漏条件就是跨空间泄露，见 [MybatisConfig.java:17](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/config/MybatisConfig.java#L17)。
- 供应商和 MCP 的 `headers` 会原样返回给空间成员；这些 headers 很可能包含 `Authorization` 等凭据，见 [SupplierController.java:33](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/model/SupplierController.java#L33)、[SupplierView.java:13](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/model/dto/SupplierView.java#L13)、[McpController.java:31](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/mcp/McpController.java#L31)、[McpService.java:126](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/mcp/McpService.java#L126)。
- Agent 详情会返回 `systemPrompt`，技能和知识库内容也对空间成员基本透明，见 [AgentView.java:15](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/agent/dto/AgentView.java#L15)。

当前 UI 文案“后台调试会话 · 空间内可见”说明共享会话可能是有意设计，见 [ChatConsole.vue:491](file:///D:/codes/king/King-AI-Platform/king-ai-ui/src/components/chat/ChatConsole.vue#L491)。但企业通常不能接受默认共享，至少要可配置、按角色控制。

**建议：**
- 增加空间级 `OWNER / ADMIN / EDITOR / VIEWER / AUDITOR` 角色，写权限下沉到服务层。
- 会话增加 `owner_user_id`，列表/读取/重命名/删除都做行级校验；公开访客使用服务端签发的短期访客 token，而不是客户端随便传的 `X-Visitor-Id`。
- 停止接口必须绑定会话/访客/用户，不能“知道 requestId 就能停”。
- 供应商/MCP headers 改为 write-only，绝不回显；需要展示时只返回掩码。
- 用 MyBatis 租户插件或数据库 RLS 做第二道防线，并补“用户 A 不能读用户 B 会话”的集成测试。

### 2. 生产库仍会写入演示数据和默认管理员

Flyway 的 `mysql` 迁移目录里，`V2__seed.sql` 会无条件创建 `admin` 用户、演示空间、Mock 供应商/模型、知识库、技能，以及一个**已发布的公开智能体 `demo-agent`**；`V3__demo_mcp.sql` 还会插入演示 MCP。`KING_AI_DEMO_ENABLED` 只控制 `DemoBootstrap` 的自动索引/MCP 同步，并不会阻止这些迁移执行。

参见 [V2__seed.sql:2](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/db/migration/mysql/V2__seed.sql#L2)、[V2__seed.sql:118](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/db/migration/mysql/V2__seed.sql#L118)、[application-prod.yml:31](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/application-prod.yml#L31)。

README 明确写了默认账号是 `admin/admin123`，也提示生产要清理演示资源，见 [README.md:140](file:///D:/codes/king/King-AI-Platform/README.md#L140)、[README.md:213](file:///D:/codes/king/King-AI-Platform/README.md#L213)。但**安全不能依赖运维记得清理**：任何按 prod profile 启动的实例，第一次迁移后都会有一个已知密码的管理员和一个公开可访问的智能体。

**建议：**
- 把 schema migration 和 demo seed 彻底分开；生产 profile 不加载 seed 目录。
- 管理员通过环境变量/一次性初始化命令创建，首次登录强制改密。
- 删除 `demo-agent`、Mock 模型和演示 MCP；至少在生产 profile 下禁止发布。
- 在启动时做一次“生产安全自检”：默认密码、演示数据、弱 secret 直接拒绝启动。

### 3. 密钥、网络与前端安全基线不足

- `application-prod.yml` 里 `KING_AI_SECRET` 有弱默认值 `please-change-this-production-secret`，见 [application-prod.yml:24](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/application-prod.yml#L24)。
- MySQL JDBC 使用 `useSSL=false&allowPublicKeyRetrieval=true`，Redis 默认无 TLS，见 [application-prod.yml:7](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/application-prod.yml#L7)、[application-prod.yml:18](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/application-prod.yml#L18)。
- MCP headers 明文落库，供应商 headers 也基本等同明文，见 [McpService.java:144](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/mcp/McpService.java#L144)。
- AES 密钥直接由 `SHA-256(appSecret)` 得到，没有 KMS、密钥版本和轮换机制；一旦更换 secret，所有已加密的供应商 Key 都无法解密，见 [CryptoService.java:26](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/common/CryptoService.java#L26)。
- URL 校验只检查 scheme/host，不阻止 localhost、内网段、云元数据地址，存在 SSRF 风险，见 [Texts.java:43](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/common/Texts.java#L43)。
- 前端 token 存在 `localStorage`，见 [http.ts:4](file:///D:/codes/king/King-AI-Platform/king-ai-ui/src/api/http.ts#L4)；Nginx 的 `/api/` location 自己定义了 `add_header`，导致 server 级的 `X-Content-Type-Options`、`Referrer-Policy` 不会继承，也没有 HSTS/CSP，见 [nginx.conf:13](file:///D:/codes/king/King-AI-Platform/king-ai-ui/nginx.conf#L13)、[nginx.conf:44](file:///D:/codes/king/King-AI-Platform/king-ai-ui/nginx.conf#L44)。
- `application.yml` 把 `text/event-stream` 加进了压缩类型，可能造成 SSE 缓冲/首字延迟，见 [application.yml:25](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/application.yml#L25)。

**建议：**
- 生产 secret 缺失时 fail-fast，不留默认值；用 Vault/KMS 做信封加密和密钥轮换。
- 供应商/MCP headers 加密存储且只写不读；MCP 支持 OAuth/client credentials。
- 数据库/Redis 全链路 TLS；出站 URL 做 allowlist + DNS/IP 校验，禁止重定向到内网。
- 前端改 httpOnly + Secure Cookie/BFF，或至少 CSP + HSTS + 短期 token；修正 Nginx header 继承。
- SSE 从压缩列表移除。

### 4. 多实例状态和异步任务不可靠

- 取消注册表、公开对话限流、登录失败保护都是进程内 `ConcurrentHashMap`，见 [ChatCancellationRegistry.java:13](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatCancellationRegistry.java#L13)、[PublicChatRateLimiter.java:13](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/PublicChatRateLimiter.java#L13)、[LoginAttemptGuard.java:20](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/auth/LoginAttemptGuard.java#L20)。
- 多副本部署后，停止请求可能落到另一个 Pod；限流和登录锁定会“翻倍”；SSE 连接本身也绑在单个 Pod 上。
- 文档索引用的是本地 `ThreadPoolTaskExecutor`，见 [KnowledgeService.java:527](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L527)。进程崩溃后文档会永久卡在 `PROCESSING`，只能等 10 分钟后手动重建，见 [KnowledgeService.java:345](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L345)。
- 索引过程没有事务/outbox：先写 chunk、再 embed、再 upsert 向量、最后改状态；任一步失败都靠手工补偿，见 [KnowledgeService.java:396](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L396)、[KnowledgeService.java:408](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L408)、[KnowledgeService.java:413](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L413)。

**建议：**
- 把 `CancellationRegistry`、`RateLimiter`、`LoginAttemptStore`、`JobQueue` 也抽象成端口，生产用 Redis/消息队列实现。
- 索引改成持久化任务表 + 队列（Redis Stream/RabbitMQ/Kafka），支持重试、退避、DLQ、幂等和启动恢复。
- 向量与数据库之间用 outbox/补偿任务保证最终一致；同一会话加分布式锁或单写者，避免并发发送交错。
- 登录保护按“账号 + IP + 设备”计数，加渐进延迟/CAPTCHA，避免攻击者用 5 次失败锁死管理员账号。

### 5. 数据模型缺少企业级约束与生命周期

- `V1__schema.sql` 里没有外键，也没有通用的 `version`、`deleted`、`created_by`、`updated_by` 字段；删除靠应用层手工级联，见 [V1__schema.sql:1](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/resources/db/migration/mysql/V1__schema.sql#L1)。
- 供应商名、MCP 名、知识库名等唯一性主要靠应用层先查再插，并发下会重复。
- H2 和 MySQL 各维护一份 schema，长期必然漂移；生产/测试应统一用 MySQL + Testcontainers。
- 会话消息的 `content/reasoning/tool_trace/citations` 都是 `MEDIUMTEXT`，聊天过程中每 800ms 全量重写一次，见 [ChatRuntime.java:447](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java#L447)、[ChatService.java:196](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L196)。
- 历史消息先全量查出来再在内存裁剪，长会话会越来越慢，见 [ChatService.java:236](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatService.java#L236)。
- 删除智能体会直接删掉该智能体的全部会话和消息，见 [AgentService.java:119](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/agent/AgentService.java#L119)。从企业合规角度，这属于审计数据丢失。

**建议：**
- 加外键或至少统一的应用层级联；关键唯一键下沉到数据库；加 `version` 乐观锁和 `deleted_at`。
- 加 `created_by/updated_by` 和操作审计表；删除改为软删，聊天记录按保留策略归档。
- 聊天进度改增量追加或降低落库频率；历史消息按 id 倒序取最近 N 条，不要全量加载。
- 迁移只保留 MySQL 一套，H2 仅本地开发或改用 Testcontainers。

### 6. 可观测性和工程质量缺失

- `/api/health` 永远返回 `UP`，不检查 MySQL/Redis/Milvus，见 [HealthController.java:15](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/common/HealthController.java#L15)。
- Docker Compose 却拿这个接口当 readiness，见 [docker-compose.yml:134](file:///D:/codes/king/King-AI-Platform/docker-compose.yml#L134)。
- 没有 Actuator、Micrometer、OpenTelemetry、结构化日志、审计日志；`pom.xml` 里也没有相关依赖，见 [pom.xml:17](file:///D:/codes/king/King-AI-Platform/king-ai-api/pom.xml#L17)。
- 后端 `src/test` 和前端 spec/test 文件数量都是 0，没有 CI、没有 lint、没有 OpenAPI 契约。

**建议：**
- 引入 Actuator + Micrometer/OTel，区分 liveness/readiness；readiness 检查 MySQL/Redis/Milvus。
- 日志统一 JSON，带 `traceId/requestId/spaceId/userId/agentId`；配置修改、登录、工具调用、数据导出全部进审计。
- 建 CI：编译、Testcontainers 集成测试、租户隔离测试、SSE 契约测试、前端 E2E、SAST、依赖漏洞扫描、SBOM。
- 用 springdoc 生成 OpenAPI，前端类型从契约生成，减少手工 DTO 漂移。

---

## 三、P1：企业能力差距

- **Agent 版本化与发布治理**：当前“发布”只是把 `publish_status` 置 1，修改线上 Agent 会立即影响公开访问；没有草稿/版本/审批/回滚/评测，见 [AgentService.java:138](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/agent/AgentService.java#L138)。企业需要“不可变版本 + 发布审批 + 灰度 + 回滚”。
- **Prompt 注入与工具安全**：RAG 片段和技能文本直接拼进 system prompt，没有不可信内容边界，见 [ChatRuntime.java:306](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java#L306)、[ChatRuntime.java:316](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java#L316)。绑定的 MCP 工具会全部暴露给模型，没有工具白名单、写操作人工确认、参数校验和调用审计，见 [ChatRuntime.java:357](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/ChatRuntime.java#L357)。
- **RAG 质量与权限**：目前只有向量检索，没有 hybrid、rerank、query rewrite、文档级 ACL；每个 KB 单独 embed query，多个 KB 共用同一 embedding 模型时会重复调用，见 [KnowledgeService.java:470](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L470)、[KnowledgeService.java:485](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeService.java#L485)。Milvus 每个知识库一个 collection，规模上来后运维成本高，见 [MilvusVectorStore.java:60](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/vector/MilvusVectorStore.java#L60)；本地向量库还是 2 万条上限的全表扫描，见 [LocalVectorStore.java:22](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/vector/LocalVectorStore.java#L22)。
- **LLM/MCP 韧性**：没有重试、退避、熔断、模型降级；`finish_reason=length` 被忽略，截断输出会被当成正常完成，见 [OpenAiClient.java:122](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/llm/OpenAiClient.java#L122)。MCP 每次工具发现/调用都新建客户端，无法复用 initialize 和 session，见 [McpService.java:108](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/mcp/McpService.java#L108)。另外配置允许保存 `SSE`，运行时却明确拒绝，见 [McpClientFactory.java:24](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/mcp/McpClientFactory.java#L24)。
- **规模化与噪声邻居**：聊天线程池 4 核/16 最大/队列 100，见 [RuntimeConfig.java:16](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/config/RuntimeConfig.java#L16)，一个空间可以打满全局线程池；没有按空间的并发/Token/成本配额。多数列表接口仍是全量返回，例如 [AgentController.java:30](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/agent/AgentController.java#L30)、[SupplierController.java:33](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/model/SupplierController.java#L33)、[KnowledgeController.java:40](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/knowledge/KnowledgeController.java#L40)、[SkillController.java:34](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/skill/SkillController.java#L34)。
- **合规与运营**：没有审计追踪、DLP/PII 脱敏、内容审核、数据保留/法律保留、数据导出、访问复核；公开对话尤其需要内容审核和滥用防护。
- **私有化交付**：离线镜像、Helm/K8s、升级/回滚、迁移 dry-run、备份恢复、许可证、air-gap 支持包都还没有。

另外有几个代码级细节会直接踩坑：

- 文档说登录失败按“用户名 + 来源 IP”计数，代码实际只按用户名，见 [LoginAttemptGuard.java:20](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/auth/LoginAttemptGuard.java#L20) 与 [architecture.md:104](file:///D:/codes/king/King-AI-Platform/docs/architecture.md#L104)。
- Nginx 已经把 `X-Real-IP` 传过来，但后端没有 `ForwardedHeaderFilter`/`forward-headers-strategy` 配置，`getRemoteAddr()` 很可能拿到的是代理地址，见 [PublicChatController.java:63](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/PublicChatController.java#L63)。
- 公开停止接口没有鉴权，也没有绑定访客/会话，见 [PublicChatController.java:69](file:///D:/codes/king/King-AI-Platform/king-ai-api/src/main/java/com/king/ai/chat/PublicChatController.java#L69)。
- 供应商/MCP 的 headers 与 API Key 的处理不一致：API Key 脱敏，headers 却原样返回。

---

## 四、建议路线

按 3–5 人后端团队估算：

| 阶段 | 时间 | 目标 | 关键交付 |
|---|---|---|---|
| P0 上线前 | 4–8 周 | 可安全地让真实客户试点 | 空间 RBAC + 会话所有权 + 访客 token；移除演示种子/默认密码；Vault/KMS + TLS + SSRF 防护；Redis 化取消/限流/登录保护；持久化索引任务 + 重试/DLQ；审计日志；真实 readiness；Testcontainers + CI |
| P1 可运营 | 3–6 个月 | 可治理、可计费、可扩展 | Agent 版本化/审批/回滚/评测；工具白名单/HITL；Prompt 注入防护；RAG hybrid/rerank/文档 ACL/OCR；LLM 网关（重试、熔断、降级、配额、成本）；OpenAPI/SDK；分页/缓存/对象存储；K8s/Helm |
| P2 规模化 | 6–12 个月 | 多租户 SaaS 或大型企业 | 虚拟线程/WebFlux、SSE 网关；读写分离、消息分区/归档；多区域/DR；数据驻留；FinOps；i18n/无障碍；生态/插件沙箱 |

如果未来做 SaaS，还需要在 `space` 之上补 `tenant/org` 层；如果只做私有化单租户，`space` 可以继续当部门/工作区，但空间 RBAC 和会话归属仍然必须补。

---

## 五、1–2 周最快见效的改动

1. 拆分 Flyway seed：生产不加载 `V2/V3` 演示数据；删除默认 `admin/admin123`，改为环境变量初始化 + 首次强制改密。
2. 供应商/MCP 的 headers 改为只写字段，列表/详情只返回掩码；MCP headers 加密存储。
3. 给会话加 `owner_user_id`，列表/读取/重命名/删除全部校验归属；`stop` 绑定会话/访客。
4. 公开访客改成服务端签名的短期 token，替代客户端自造的 `X-Visitor-Id`。
5. 取消注册表、限流、登录保护切 Redis；Nginx/网关按真实 IP 限流。
6. URL 校验加内网/元数据地址黑名单，出站走 allowlist/代理。
7. 引入 Actuator，readiness 检查 MySQL/Redis/Milvus；Compose 改用 readiness。
8. Nginx 修正安全头继承，补 HSTS/CSP/X-Frame-Options；SSE 关闭压缩。
9. 文档启动恢复：扫描 `PROCESSING` 超时任务重新入队，加指数退避重试。
10. 加数据库唯一约束、`version` 字段和最小 CI（编译 + 一个 Testcontainers 租户隔离测试）。

**如果只做三件事：清掉生产默认数据、补空间角色与会话所有权、把内存状态和索引任务迁到 Redis + 持久队列。** 这三件做完，才谈得上让真实客户接入；至于微服务、WebFlux、多区域，等 P0 和 P1 稳定后再做，收益会大得多。