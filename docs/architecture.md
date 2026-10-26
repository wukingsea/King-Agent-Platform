# King AI Platform 架构说明

本文档描述平台的分层、核心链路、数据模型与部署方式，帮助二次开发时快速定位代码。

## 1. 设计目标

- 一套代码同时支持零依赖本地开发与 MySQL / Redis / Milvus 生产部署。
- 智能体是编排中枢：模型、提示词、知识库、MCP 工具与技能围绕智能体组合。
- 对话链路全程可观测：思考过程、工具调用、知识引用、Token 用量与耗时均落库。
- 所有业务数据以空间为边界隔离，鉴权、查询和写入都显式携带空间上下文。
- 外部能力通过稳定接口接入：OpenAI 兼容协议、MCP JSON-RPC、Embedding 接口。

## 2. 系统拓扑

```mermaid
flowchart TB
    subgraph Browser
        Admin[管理后台]
        Public[公开对话页]
    end

    subgraph Edge
        Nginx[Nginx / Vite Proxy]
    end

    subgraph Backend[Spring Boot]
        Auth[认证与空间拦截器]
        Runtime[ChatRuntime]
        RAG[知识库与向量检索]
        MCP[MCP 客户端]
        Skills[技能解析]
        Providers[LLM 客户端工厂]
        AdminAPI[管理与资源 API]
    end

    subgraph Storage
        MySQL[(MySQL / H2)]
        Redis[(Redis / 内存 Token)]
        Milvus[(Milvus / 本地向量表)]
    end

    subgraph External
        LLM[OpenAI 兼容服务]
        McpServer[远程 MCP 服务]
    end

    Admin --> Nginx
    Public --> Nginx
    Nginx --> Auth
    Auth --> Runtime
    Auth --> AdminAPI
    Runtime --> RAG
    Runtime --> MCP
    Runtime --> Skills
    Runtime --> Providers
    RAG --> Milvus
    Providers --> LLM
    MCP --> McpServer
    AdminAPI --> MySQL
    Runtime --> MySQL
    Auth --> Redis
```

## 3. 后端分层

后端按业务域分包，每个域内保持 Controller -> Service -> Mapper 的依赖方向。

| 包 | 职责 |
| --- | --- |
| `agent` | 智能体 CRUD、绑定关系、发布状态、公开信息 |
| `auth` | 登录、退出、当前用户、密码修改、Token 存储、登录失败保护 |
| `chat` | SSE 对话运行时、会话与消息、停止注册表、公开对话限流 |
| `common` | 统一响应、异常、加密、请求上下文、拦截器、健康检查 |
| `config` | 配置属性、运行时 Bean、演示数据引导 |
| `knowledge` | 知识库、文档、分片、Embedding、向量存储适配、检索 |
| `llm` | LLM 抽象、OpenAI 兼容客户端、Mock 客户端、工具调用模型 |
| `mcp` | MCP 服务管理、JSON-RPC 客户端、演示 MCP 服务 |
| `model` | 供应商与模型配置、模型解析、连通性测试与导入 |
| `skill` | 技能元数据、文件树、锁定的 SKILL.md、参考文件读取 |
| `system` | 用户与空间管理、成员授权 |

### 3.1 运行环境适配

以下能力通过 Spring 条件装配在本地与生产间切换：

| 配置 | 本地 | 生产 |
| --- | --- | --- |
| `king-ai.vector-store` | `local`：`LocalVectorStore` | `milvus`：`MilvusVectorStore` |
| `king-ai.token-store` | `memory`：`MemoryTokenStore` | `redis`：`RedisTokenStore` |
| `king-ai.db-type` | `h2` | `mysql` |
| Flyway 路径 | `db/migration/h2` | `db/migration/mysql` |
| 模型供应商 | `MOCK`，确定性文本与向量 | `OPENAI` 兼容服务 |

Mock 模型使用字符二元组哈希生成 256 维确定性向量，并对中文回复做分段流式输出，因此 RAG、引用、工具调用等链路在没有外部模型时也能完整演示。

## 4. 认证与空间隔离

- 登录成功后生成随机不透明 Token，服务端存储 Token 与用户 ID 的映射，默认有效期 7 天。
- 浏览器通过 `HttpOnly` 会话 Cookie（默认 `king_session`）认证；非浏览器客户端也可使用登录响应中的 `Authorization: Bearer <token>`。
- 客户端通过 `X-Space-Id` 指定当前空间。
- `AuthInterceptor` 解析 Token，校验用户状态与空间成员关系，写入 `RequestContext`；首次登录强制改密时仅放行改密接口并返回 428。
- 管理员可以访问全部空间；普通用户必须存在 `king_ai_user_space` 授权记录。
- 空间角色为 `OWNER / ADMIN / EDITOR / VIEWER / AUDITOR`，写接口通过 `@RequireSpaceRole` 在服务层校验。
- 管理接口通过 `@RequireAdmin` 限制平台管理员。
- 供应商 API Key 与 MCP 请求头使用 AES-GCM 加密存储，读取接口只返回掩码。
- 登录失败按用户名与来源 IP 计数，超过阈值后短暂锁定。

```mermaid
sequenceDiagram
    participant B as Browser
    participant N as Nginx
    participant I as AuthInterceptor
    participant S as Service
    participant D as MySQL

    B->>N: /api/agents + HttpOnly Cookie + X-Space-Id
    N->>I: 转发请求
    I->>I: 校验 Token、用户状态、空间成员与角色
    I->>S: 注入 UserPrincipal 与 spaceId
    S->>D: 查询时强制 space_id 条件
    D-->>S: 数据
    S-->>B: ApiResponse
```

## 5. 对话运行时

入口：

- 管理端：`POST /api/chat/send`，需要会话 Cookie 与 `X-Space-Id`
- 公开端：`POST /api/public/chat/send`，使用服务端 HMAC 签名的访客 Cookie 识别访客

`ChatRuntime.start` 完成同步校验后，先获取会话级锁与空间并发配额，再将生成任务提交到专用线程池，并立即返回 `SseEmitter`。

```mermaid
sequenceDiagram
    participant C as Client
    participant R as ChatRuntime
    participant K as Knowledge
    participant M as MCP
    participant L as LLM
    participant DB as Database

    C->>R: POST /chat/send
    R->>DB: 创建会话 / 保存用户消息 / 助手占位
    R-->>C: SSE 连接
    R-->>C: event: meta
    R->>K: 混合检索绑定知识库（向量 + 词法 + ACL）
    K-->>R: 片段与分数
    R-->>C: event: citation
    R->>M: 发现绑定 MCP 工具（客户端缓存）
    R->>L: 流式生成（系统提示词 + 历史 + 工具定义）
    L-->>R: reasoning / content 增量
    R-->>C: event: reasoning / content
    L-->>R: tool_calls
    R->>R: 工具白名单 / 参数校验 / 写操作人工确认
    R->>M: 执行工具
    M-->>R: 工具结果
    R-->>C: event: tool_call / tool_result
    R->>L: 携带工具结果继续生成
    R->>DB: 增量刷新助手消息
    R-->>C: event: usage / done
```

关键设计：

- 增量落库：每 800ms 或阶段性节点刷新助手消息，刷新页面可看到已生成内容。
- 状态收尾：完成、停止、失败三种状态统一在 `finally` 中持久化。
- 停止生成：前端调用 `/requests/{requestId}/stop`，运行时通过 `CancellationToken` 中断流式读取；停止与工具确认均校验会话归属。
- 会话锁与配额：同一会话同时只允许一个生成任务；每个空间默认最多 4 个并发流，超过返回 429，Redis 实现支持多实例共享。
- 工具循环：最多执行 `king-ai.chat.max-tool-rounds` 轮，工具结果按长度截断后回填给模型。
- 工具安全：`allowed_tools` 白名单失败关闭，参数按 JSON Schema 校验，写操作可按 `tool_approval_mode` 进入人工确认，所有调用写入审计。
- 技能工具：除 MCP 工具外，绑定的技能会注入一个只读的 `skill_read_file` 工具，防止模型读取技能目录之外的文件。
- 历史裁剪：按消息条数与字符数双重限制选取历史，避免上下文膨胀。
- 韧性：瞬态 LLM 错误自动重试并回退部分输出，支持降级模型与按模型熔断；`finish_reason=length` 会标记截断。
- 不可信内容：知识片段、技能文本与工具结果均包裹在不可信内容边界中，提示模型只作为数据引用。

SSE 事件由 `ChatRuntime` 统一发送：`meta`、`citation`、`reasoning`、`content`、`tool_call`、`tool_result`、`tool_approval`、`usage`、`done`、`error`、`retry`、`degraded`、`heartbeat`。

## 6. 知识库与 RAG

### 6.1 写入链路

```text
文档导入（文本 / 上传）
  -> 解析文本（TXT、Markdown、PDF）
  -> 按段落与句子边界分片
  -> 创建持久化索引任务（king_ai_knowledge_index_job）
  -> 批量为分片生成向量
  -> 写入向量存储
  -> 更新文档状态与分片数
```

- 文档状态：`PENDING`、`PROCESSING`、`READY`、`FAILED`。
- 索引任务由后台 Worker 轮询领取，支持重试、退避、永久失败、超时任务恢复与对账；进程重启不会丢失。
- 向量集合按知识库维度命名，包含 `chunk_id`、`doc_id`、`space_id`、`kb_id` 与向量字段。
- Milvus 使用 `FloatVector` + `AUTOINDEX` + `COSINE`。
- 本地向量存储复用 `king_ai_chunk_vector` 表，在应用内计算余弦相似度，仅适用于开发与演示。

### 6.2 读取链路

1. 对话开始前，根据智能体绑定的知识库分组检索。
2. 同一嵌入模型只对用户问题向量化一次，多个知识库复用查询向量。
3. 向量相似度与词法匹配结果融合，再按文档 ACL 过滤、按合并分数排序并做文档级去重。
4. 按相似度过滤低于 `king-ai.rag.min-score` 的结果。
5. 引用作为 `citation` 事件先推送给前端，同时以不可信内容边界拼接到系统提示词。
6. 系统提示词要求模型基于资料回答，并在证据不足时明确说明。

## 7. MCP 集成

- 传输：Streamable HTTP，JSON-RPC 2.0；配置层拒绝其他传输方式。
- 初始化：`initialize` -> `notifications/initialized` -> `tools/list`。
- 分页：最多读取 10 页工具清单，按名称去重。
- 调用：`tools/call`，提取 `content[].text`，兼容 `structuredContent`，结果超长截断。
- 工具命名：对外暴露为 `mcp{serverId}_{toolName}`，清洗非法字符并保证同名冲突时自动加后缀。
- 会话：客户端保留 `Mcp-Session-Id`，并在后续请求中回传。
- 缓存：按服务地址与配置指纹缓存客户端，配置变更或调用失败时失效，避免每次对话重新 initialize。
- 安全：请求头加密存储且只回显掩码；工具调用受智能体白名单、参数校验、人工确认与审计约束。

## 8. SKILL 设计

- 每个技能包含若干文件与目录，`SKILL.md` 为锁定文件。
- SKILL.md 允许编辑内容，但不允许改名或删除；这保证技能始终有入口规范。
- 智能体绑定技能后，所有 SKILL.md 按排序拼接注入系统提示词。
- 模型只可通过 `skill_read_file` 读取当前技能下的其他文件，路径越界会被拒绝。
- 文件编辑接口区分内容更新与元数据更新，避免编辑器误改文件类型。

## 9. 数据模型

```mermaid
erDiagram
    USER ||--o{ USER_SPACE : authorized
    SPACE ||--o{ USER_SPACE : grants

    SPACE ||--o{ SUPPLIER : owns
    SUPPLIER ||--o{ SUPPLIER_MODEL : provides
    SPACE ||--o{ AGENT : owns

    AGENT ||--o{ AGENT_KNOWLEDGE : binds
    AGENT ||--o{ AGENT_MCP : binds
    AGENT ||--o{ AGENT_SKILL : binds

    SPACE ||--o{ KNOWLEDGE_BASE : owns
    KNOWLEDGE_BASE ||--o{ KNOWLEDGE_DOC : contains
    KNOWLEDGE_DOC ||--o{ KNOWLEDGE_CHUNK : split_into
    CHUNK_VECTOR }o--|| KNOWLEDGE_CHUNK : embeds

    SPACE ||--o{ MCP_SERVER : owns
    SPACE ||--o{ SKILL : owns
    SKILL ||--o{ SKILL_FILE : contains

    AGENT ||--o{ CHAT_SESSION : serves
    CHAT_SESSION ||--o{ CHAT_MESSAGE : contains
```

所有业务表包含 `space_id`（技能文件、消息等通过父表继承空间），查询与写入由服务层强制带上空间条件。会话表额外记录 `source_type`（`ADMIN` / `PUBLIC`）、`owner_user_id` 与 `visitor_id`，用于区分管理端会话与访客会话。Agent 发布使用不可变版本表 `king_ai_agent_version`，支持回滚与历史补录；审计日志和索引任务分别独立持久化。

## 10. 前端结构

```text
src/
├── api/            # 按域拆分的 HTTP 客户端与类型
├── components/     # 布局、空状态、Markdown、聊天组件
├── pages/          # 登录、概览、智能体、对话、记录、知识库、MCP、技能、模型、用户、空间、公开页
├── stores/         # Pinia：登录态、当前用户、当前空间
├── router/         # Hash 路由与登录守卫
├── utils/          # 时间、文本、格式化
└── styles/         # 设计变量与全局样式
```

- `AppShell` 提供侧边导航、空间切换、管理员菜单与登出。
- `ChatConsole` 同时服务管理端对话测试与公开对话页，内部处理 SSE、停止、会话切换、工具卡片和引用卡片。
- `MarkdownView` 负责 Markdown 渲染、XSS 净化、代码高亮与外链安全属性。
- 公开页不加载管理端布局，仅通过 `/api/public/**` 获取最少信息。

## 11. 部署拓扑

```mermaid
flowchart LR
    User[用户] -->|80| UI[Nginx + Vue 静态资源]
    UI -->|/api| API[Spring Boot]
    API --> MySQL[(MySQL 8)]
    API --> Redis[(Redis 7)]
    API --> Milvus[(Milvus 2.5)]
    Milvus --> Etcd[(etcd)]
    Milvus --> MinIO[(MinIO)]
    API --> LLM[模型服务]
    API --> MCP[远程 MCP]
```

启动顺序由 Docker Compose 健康检查保证：

1. MySQL、Redis、etcd、MinIO 启动并通过健康检查。
2. Milvus 启动并暴露健康端点。
3. API 启动，Flyway 执行 schema 迁移；生产迁移目录不包含演示种子，初始管理员由环境变量创建。
4. UI 在 API 健康后启动。

Nginx 对 `/api/` 关闭代理缓冲并延长读写超时，保证 SSE 增量实时到达浏览器。API 默认绑定 `127.0.0.1`，只通过 UI 反向代理对外提供服务。

## 12. 扩展点

- 新增模型供应商：实现 `LlmClient` 并在 `LlmClientFactory` 中注册，或直接使用 OpenAI 兼容供应商。
- 新增向量后端：实现 `VectorStore`，通过 `king-ai.vector-store` 条件装配。
- 新增登录态存储：实现 `TokenStore`，通过 `king-ai.token-store` 切换。
- 新增对话渠道：复用 `ChatRuntime.start`，传入不同的 `sourceType` 与访客标识。
- 新增对话并发策略：实现 `ChatConcurrencyGuard`，按 `king-ai.token-store` 条件装配内存或 Redis 版本。
- 新增文档解析：扩展知识库上传解析器，保持文本、分片、索引链路不变。
- 新增 MCP 传输：在 `McpClient` 外部增加 stdio 等适配，保持工具抽象不变。

## 13. 安全边界与已知限制

- 本地向量存储为全表扫描并限制 20000 条，不适合生产规模。
- Milvus 当前为每个知识库创建独立 collection，知识库数量很大时应改为共享 collection 加分区字段。
- 内存 Token 存储、限流与并发守卫仅适用于单实例开发；生产使用 Redis 实现。
- 知识库文件解析支持文本型 PDF，尚未接入 OCR 处理扫描件。
- MCP 客户端未实现 OAuth 授权流程，当前支持自定义请求头与静态 Token。
- Agent 发布支持不可变版本与回滚，但发布审批、灰度与自动评测尚未实现。
- 合规能力（PII/DLP、内容审核、法律保留、数据导出、访问复核）与 Helm/air-gap 交付尚未内置。
