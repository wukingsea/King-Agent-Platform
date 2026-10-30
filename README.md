# King AI Platform

一个从零实现的 AI Agent 平台，参考 xxl-ai 的产品形态与架构思路，覆盖业务空间与权限、模型供应商接入、智能体编排与发布、SSE 流式对话、知识库 RAG、MCP 工具、SKILL 技能、会话记录与管理后台。

前后端分离，支持两种运行方式：

- 本地零依赖：H2 文件数据库 + 内存 Token + 本地向量索引 + Mock 模型，不需要 MySQL、Redis、Milvus 或任何模型 API Key。
- 生产栈：MySQL 8 + Redis 7 + Milvus 2.5，通过 Docker Compose 一键启动。

## 功能概览

- 空间与 RBAC：业务空间隔离数据，通过 `X-Space-Id` 控制上下文；空间内支持 `OWNER / ADMIN / EDITOR / VIEWER / AUDITOR` 角色，管理员可切换全部空间，普通用户仅能访问被授权空间。删除空间与智能体采用归档语义：只写 `deleted_at`，会话、消息、审计与用量账本不会被级联清除。
- 供应商与模型：统一 OpenAI 兼容协议，支持对话模型与嵌入模型；API Key 使用 AES-GCM 加密存储并掩码展示；支持连通性测试与模型列表导入。
- 智能体编排：选择模型、填写系统指令与参数、绑定知识库 / MCP 服务 / 技能，支持发布、取消发布与公开访问 ID。
- 发布治理：发布版本为不可变快照；支持提交审批、四眼复核、评测用例与最低得分门禁、按百分比灰度、正式发布与回滚。`release.require-approval=false` 时提交即发布。
- 流式对话：SSE 输出思考过程、正文、工具调用、工具结果、知识引用与 Token 用量；支持停止生成，生成内容持续落库。
- 知识库 RAG：文本与文件导入（TXT / Markdown / PDF / PNG / JPG），按段落和句子边界分片，异步向量化；文本型 PDF 直接抽取，扫描件与图片在启用 OCR 后由 Tesseract 识别入库；对话前自动检索并注入上下文与引用。
- MCP 工具：基于 Streamable HTTP 与 JSON-RPC 2.0 连接远程 MCP 服务，自动发现工具并参与多轮工具调用；支持无认证与 OAuth 2.0 客户端凭证模式，令牌自动获取与刷新。
- 身份认证：本地账号支持 BCrypt 密码与 TOTP 二次验证（含恢复码）；可接入 OIDC 单点登录，按角色声明把用户映射到对应空间的 `OWNER / ADMIN / EDITOR / VIEWER / AUDITOR` 角色。
- SKILL 技能：以锁定的 `SKILL.md` 为核心，支持目录树与参考文件；技能规范注入系统提示词，模型可按需调用 `skill_read_file` 读取其他文件。
- 会话管理：管理端查看全量会话与消息、来源筛选、关键字搜索、重命名；公开对话按浏览器访客隔离会话。
- 合规与审计：按空间配置内容审核、PII 脱敏、留存策略与法律保留；支持数据导出、访问复核与审计日志检索，敏感操作全程留痕。
- 用量与成本：每次对话按实际模型记录输入/输出 Token 与估算成本，形成只追加的空间用量账本；空间可设置月度 Token 配额，用尽后对话返回 429。
- 公开对话页：发布后的智能体可通过 `/#/chat/{publicId}` 免登录访问，使用服务端签名的访客 Cookie、分钟级限流与空间并发配额。
- 私有化交付：提供 Docker Compose、Helm Chart 与 air-gap 离线包（镜像、Chart、迁移 SQL、License 工具）；支持 Ed25519 离线许可证，按空间 / 用户 / 智能体数量执行容量上限。
- 安全设计：不透明 Token 登录态、BCrypt 密码、登录失败保护、TOTP 二次验证、OIDC state/nonce 签名、Markdown XSS 净化、出站 URL 逐跳校验、公开接口最小化字段暴露。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.5、Java 17+、MyBatis-Plus、Flyway、OkHttp |
| 数据 | H2（本地）/ MySQL 8（生产）、Redis 7、Milvus 2.5 |
| 前端 | Vue 3.5、TypeScript、Vite、Pinia、Vue Router、Element Plus |
| 渲染 | marked + DOMPurify + highlight.js |
| 部署 | Docker 多阶段构建、Nginx、Docker Compose |

## 架构

```mermaid
flowchart LR
    U[浏览器] --> N[Nginx / Vite]
    N --> UI[Vue 3 管理后台与公开对话页]
    UI -->|/api| A[Spring Boot API]
    A --> DB[(MySQL / H2)]
    A --> R[(Redis / 内存 Token)]
    A --> M[(Milvus / 本地向量)]
    A --> L[OpenAI 兼容模型服务]
    A --> X[远程 MCP 服务]
```

对话主链路：

```text
用户消息
  -> 鉴权与空间校验
  -> 会话创建 / 历史裁剪
  -> 知识库检索与引用
  -> SKILL.md 注入系统提示词
  -> MCP 工具发现与装配
  -> 模型流式生成（可多轮工具调用）
  -> SSE 推送 + 增量落库
  -> 完成 / 停止 / 失败状态收尾
```

更完整的设计说明见 [docs/architecture.md](docs/architecture.md)。

## 目录结构

```text
King-AI-Platform/
├── pom.xml                    # Maven 聚合工程
├── docker-compose.yml         # MySQL / Redis / Milvus / API / UI
├── .env.example               # 部署环境变量模板
├── king-ai-api/               # Spring Boot 后端
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/king/ai/
│       │   ├── agent/         # 智能体编排与公开信息
│       │   ├── audit/         # 审计日志写入、检索与清理
│       │   ├── auth/          # 登录、Token、登录保护
│       │   ├── chat/          # SSE 对话运行时与会话
│       │   ├── common/        # 响应、异常、安全、加密
│       │   ├── compliance/    # 内容审核、留存、法律保留、导出、访问复核
│       │   ├── config/        # 配置、运行时装配、演示引导
│       │   ├── finops/        # 用量账本、价格表、月度 Token 配额
│       │   ├── knowledge/     # 知识库、分片、向量存储
│       │   ├── llm/           # OpenAI 兼容客户端与 Mock
│       │   ├── mcp/           # MCP 客户端与服务管理
│       │   ├── model/         # 供应商与模型
│       │   ├── skill/         # 技能与文件树
│       │   └── system/        # 用户与空间
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           ├── application-prod.yml
│           └── db/migration/{h2,mysql}
└── king-ai-ui/                # Vue 3 前端
    ├── Dockerfile
    ├── nginx.conf
    └── src/
        ├── api/
        ├── components/
        ├── pages/
        └── stores/
```

## 本地快速开始

环境要求：JDK 17+、Maven 3.9+、Node.js 20.19+（推荐 22/24）。

### 1. 启动后端

```bash
mvn -q -pl king-ai-api -am package -DskipTests
cd king-ai-api
java -jar target/king-ai-api.jar
```

本地 profile 默认使用：

- H2 文件数据库：`king-ai-api/data/kingai`
- 内存 Token 存储
- 本地向量索引
- 内置 Mock 模型
- 端口 `8081`（避免与常见 8080 服务冲突）

健康检查：<http://localhost:8081/api/health>

### 2. 启动前端

```bash
cd king-ai-ui
npm install
npm run dev
```

访问 <http://localhost:5173>，开发服务器会将 `/api` 代理到 `http://localhost:8081`。

如果后端端口不同，可通过 `VITE_API_PROXY` 指定：

```bash
VITE_API_PROXY=http://localhost:8082 npm run dev
```

### 3. 登录与体验

- 用户名：`admin`
- 密码：`admin123`（仅本地演示 profile；生产环境必须通过环境变量创建初始管理员）

内置演示数据包含：

- 业务空间 `King AI 演示空间`
- Mock 供应商、对话模型、256 维嵌入模型
- 知识库与已索引的《King AI 平台介绍》
- 技能「产品答疑规范」及参考文件
- 演示 MCP 服务（时间与回显工具）
- 已发布的公开智能体「King 助手」：<http://localhost:5173/#/chat/demo-agent>

建议按以下顺序体验：

1. 工作台查看空间概览。
2. 对话测试中提问「King AI 支持哪些核心能力？」，观察流式回答与知识引用。
3. 继续提问「现在几点了？」，观察 MCP 工具调用卡片。
4. 在知识库中导入文档并重建索引，再回到对话测试验证引用。
5. 在智能体中修改系统提示词、绑定知识库 / MCP / 技能并发布。
6. 打开公开对话页，使用无痕窗口验证访客会话隔离。
7. 在对话记录中按来源、关键字筛选并查看完整消息。

## Docker Compose 部署

环境要求：Docker 24+、Docker Compose v2，建议至少 4 核 CPU 与 8 GB 内存。

### 1. 准备环境变量

```bash
cp .env.example .env
```

修改 `.env`，至少完成：

- 使用 `openssl rand -hex 32` 生成并设置 `KING_AI_SECRET`
- 修改 MySQL、Redis、MinIO 密码
- 体验完整演示栈时设置 `KING_AI_DEMO_ENABLED=true`

### 2. 启动

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

首次启动需要拉取镜像并等待 Milvus 就绪，通常需要 1 到 3 分钟。MySQL 首次初始化时 Flyway 会创建业务表；生产迁移不包含演示种子数据。

访问：

- 管理后台：<http://localhost>
- 后端健康检查：<http://localhost:8080/api/health>
- 公开对话页：<http://localhost/#/chat/demo-agent>（仅当 `KING_AI_DEMO_ENABLED=true` 且使用演示数据时）

管理员账号由 `KING_AI_ADMIN_USERNAME` / `KING_AI_ADMIN_PASSWORD` 在首次启动时创建，首次登录必须修改密码。

### 3. 数据与清理

MySQL、Redis、etcd、MinIO、Milvus 均使用命名卷持久化。

```bash
docker compose down          # 停止并保留数据
docker compose down -v       # 停止并删除全部数据卷（不可恢复）
```

### 演示开关说明

`KING_AI_DEMO_ENABLED=true` 会启用内置演示 MCP 接口，并在启动时自动重建待索引的种子文档。设为 `false` 时：

- `/api/demo/**` 不注册；
- 种子知识文档保持 `PENDING`，需要手动重建索引；
- 种子演示智能体的 MCP 工具不可用。

生产环境建议关闭演示开关，并删除或取消发布演示空间中的示例资源，接入自己的模型供应商。

## 配置项

主要环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KING_AI_SECRET` | 无（Compose 必填） | API Key 加密与签名密钥，至少 32 位随机字符 |
| `MYSQL_HOST` / `MYSQL_PORT` | `mysql` / `3306` | MySQL 地址 |
| `MYSQL_DATABASE` | `king_ai` | 数据库名 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `king` / `king_ai_password` | 业务数据库账号 |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | `king_ai_redis` | Redis 密码 |
| `MILVUS_URL` | `http://milvus:19530` | Milvus REST 地址 |
| `MILVUS_TOKEN` | 空 | Milvus Token（未开启鉴权时为空） |
| `MILVUS_COLLECTION_MODE` | `shared` | 共享 collection（按维度拆分、空间分区）或 `per-kb` 旧布局 |
| `MILVUS_COLLECTION_PREFIX` / `MILVUS_PARTITIONS_NUM` | `king_ai_chunks_v2` / `64` | 共享 collection 前缀与分区数（分区数创建后不可修改） |
| `KING_AI_DEMO_ENABLED` | `false` | 是否启用演示 MCP 与自动索引 |
| `KING_AI_RELEASE_REQUIRE_APPROVAL` | `true` | 发布是否必须经过审批；关闭后提交即发布 |
| `KING_AI_RELEASE_EVAL_PASS_SCORE` | `0.8` | 审批前评测最低加权得分 |
| `KING_AI_RELEASE_MAX_EVAL_CASES` / `_EVAL_MAX_TOKENS` | `20` / `512` | 每个智能体的评测用例上限与单次评测 Token 上限 |
| `KING_AI_SSO_ENABLED` | `false` | 启用 OIDC 单点登录 |
| `KING_AI_OIDC_ISSUER` / `_CLIENT_ID` / `_CLIENT_SECRET` / `_REDIRECT_URI` | 空 | OIDC 提供方、客户端与回调地址；Issuer、Client ID、回调地址齐全时启用，机密客户端需同时提供 Secret |
| `KING_AI_OIDC_ROLE_CLAIM` | `roles` | 承载空间角色的声明，支持 `ROLE` 或 `space-code:ROLE` |
| `KING_AI_OIDC_DEFAULT_SPACE_CODE` / `_DEFAULT_ROLE` | 空 / `VIEWER` | 首次登录授予的默认空间与角色 |
| `KING_AI_MFA_ENABLED` | `true` | 是否允许用户启用 TOTP 二次验证 |
| `KING_AI_MFA_ISSUER` | `King AI Platform` | 认证器中显示的签发方 |
| `KING_AI_OCR_ENABLED` | `false` | 启用 Tesseract OCR，处理扫描件与图片 |
| `KING_AI_OCR_COMMAND` / `_LANGUAGES` | `tesseract` / `chi_sim+eng` | OCR 命令与语言包 |
| `KING_AI_OCR_MAX_PAGES` / `_TIMEOUT_SECONDS` / `_RENDER_DPI` | `30` / `120` / `200` | OCR 页数上限、单页超时与 PDF 渲染 DPI |
| `KING_AI_LICENSE_ENABLED` | `false` | 启用 Ed25519 离线许可证；无效许可证拒绝启动 |
| `KING_AI_LICENSE_FILE` / `_PUBLIC_KEY` | 空 | 许可证文件路径与公钥（PEM 或 Base64） |
| `KING_AI_API_IMAGE` / `KING_AI_UI_IMAGE` | 空 | air-gap 导入后 Compose 使用的镜像引用 |
| `PUBLIC_RATE_LIMIT_PER_MINUTE` | `20` | 公开对话每访客每分钟请求上限 |
| `KING_AI_CHAT_MAX_CONCURRENT_PER_SPACE` | `4` | 每个空间允许同时运行的对话流数量，`0` 表示不限制 |
| `KING_AI_FINOPS_QUOTA_ENABLED` | `true` | 空间月度 Token 配额用尽后是否阻断对话 |
| `KING_AI_FINOPS_CURRENCY` | `USD` | 用量账本记录的币种 |
| `KING_AI_FINOPS_DEFAULT_INPUT_PRICE` / `_OUTPUT_PRICE` | `0` | 未单独配置价格时，每百万 Token 的默认输入/输出单价 |
| `API_PORT` / `UI_PORT` | `8080` / `80` | Compose 暴露到宿主机的端口 |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | JVM 参数 |

Spring Boot 配置还可通过环境变量覆盖，例如：

- `KING_AI_CHAT_MAX_TOOL_ROUNDS`：最大工具调用轮次
- `KING_AI_CHAT_MAX_INPUT_CHARS`：单条输入长度上限
- `KING_AI_RAG_MIN_SCORE`：检索相似度阈值
- `KING_AI_SESSION_TTL`：登录态有效期

列表接口统一支持 `page` / `size`（默认 `1` / `20`，上限 `200`），响应包含 `total`、`totalPages`、`hasNext`、`hasPrevious`。工作台统计使用 `GET /api/workspace/stats` 聚合查询，不再拉取全量资源列表。

用量与配额接口：`GET /api/finops/usage` 返回当前空间本月 Token、估算成本、按模型分布与最近 20 条记录；`PUT /api/finops/quota` 需空间 `OWNER`，设置 `monthlyTokenQuota`（`0` 表示不限额）。模型单价按「模型标识 → 每百万 Token 单价」配置在 `king-ai.finops.prices`，未配置时回退到默认单价；价格变更只影响之后写入的记录。

企业能力要点：

- SSO：配置 `KING_AI_OIDC_*` 后，登录页出现单点登录入口；IdP 回调地址填写 `https://<你的域名>/api/auth/oidc/callback`。角色声明支持 `ADMIN` 或 `space-code:EDITOR` 形式，未命中时使用 `KING_AI_OIDC_DEFAULT_SPACE_CODE` 与默认角色。
- MFA：在「账号安全」中扫码绑定 TOTP，恢复码只展示一次；启用后密码登录需再输入动态码。可用 `KING_AI_MFA_ENABLED=false` 全局关闭。
- 发布治理：在智能体编辑抽屉中维护评测用例，`提交发布` 进入审批；审批通过后可设置灰度比例，再正式发布或回滚。关闭 `KING_AI_RELEASE_REQUIRE_APPROVAL` 可恢复提交即发布。
- OCR：API 镜像已内置 `tesseract` 与 `chi_sim` / `eng` 语言包；裸机部署需自行安装，设置 `KING_AI_OCR_ENABLED=true` 后即可导入扫描 PDF 与 PNG / JPG。
- 许可证与 air-gap：签名与文件格式见 [deploy/airgap/license/README.md](deploy/airgap/license/README.md)，离线交付流程见 [deploy/airgap/README.md](deploy/airgap/README.md)；许可证容量通过 `GET /api/license` 查看。

详细部署、认证、备份与故障排查见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 对话 SSE 协议

管理端：`POST /api/chat/send`，需要登录 Cookie（或 Bearer Token）与 `X-Space-Id`。

公开端：`POST /api/public/chat/send`，需要服务端签发的访客 Cookie；首次访问 `GET /api/public/chat/sessions` 会自动签发。

事件流示例：

```text
event: meta          requestId、sessionId、agentId、model
event: citation      知识引用（序号、文档名、片段、分数）
event: reasoning     思考过程增量
event: content       正文增量
event: tool_call     工具调用开始（名称、参数、来源）
event: tool_result   工具调用结果（结果、耗时、错误）
event: tool_approval 写操作工具等待人工批准（批准/拒绝后继续）
event: usage         prompt / completion Token 统计
event: done          messageId、sessionId、状态、耗时
event: error         生成失败信息（出现后仍会发送 done）
event: retry         模型瞬态失败后的重试通知
event: degraded      主模型失败后切换降级模型
event: heartbeat     长连接保活，客户端忽略
```

前端通过 `fetch` 读取事件流，支持停止按钮取消正在生成的请求。SSE 连接最长 600 秒，服务端每 15 秒发送心跳。

## 安全说明

- 生产环境必须设置独立的 `KING_AI_SECRET`、`KING_AI_ENCRYPTION_KEY` 与强管理员密码；缺失或不满足强度要求时应用拒绝启动。
- 供应商 API Key、MCP 请求头 / OAuth 客户端密钥与 TOTP 密钥使用 AES-GCM 加密后存储，接口仅返回掩码值；OAuth 客户端密钥为只写字段。
- 登录态使用服务端不透明 Token，浏览器通过 `HttpOnly` Cookie 携带；本地使用内存实现，生产使用 Redis。启用 MFA 的账号在密码校验后只返回短时挑战，验证 TOTP 或恢复码后才建立会话。
- OIDC 登录使用授权码流程：state 与 nonce 由平台密钥签名并限时有效，回调校验 ID Token 的签名、issuer、audience 与 nonce；角色声明只会提升空间权限，不会自动降级已有角色。
- 许可证使用 Ed25519 签名并在启动时校验证书与公钥；`KING_AI_LICENSE_ENABLED=true` 且许可证无效、过期或缺失时应用拒绝启动。
- OCR 通过本地 Tesseract 子进程执行，带超时、输出上限与临时文件清理；失败按页隔离。仅在受信服务器上开启，并确保 `tesseract` 与语言包已安装。
- 公开接口只返回智能体名称、介绍、公开 ID 与能力标签，不暴露系统提示词、模型配置或密钥。
- Markdown 内容经过 DOMPurify 净化后再渲染，外链自动添加 `noopener noreferrer`。
- 公开对话使用 HMAC 签名的访客 Cookie，按「访客 + 来源 IP」限流；停止与工具确认接口会校验请求归属。多实例部署时限流与并发配额使用 Redis。
- 出站 URL 默认拦截内网与云元数据地址，可通过 `KING_AI_ALLOWED_OUTBOUND_HOSTS` 精确放行。
- 建议在 Nginx 或云负载均衡层启用 HTTPS，并仅暴露 UI 与 API 端口，MySQL、Redis、Milvus 保持内网访问。

## 构建与验证

```bash
# 后端编译、单元测试与打包
mvn -B clean verify

# MySQL 8 集成测试：迁移、租户隔离、会话归属（需要 Docker，Testcontainers 自动拉起 MySQL）
mvn -B -pl king-ai-api -am test -Dtest=MySqlMigrationTest -DfailIfNoTests=false

# 前端校验：Lint、单元测试、类型检查与生产构建
cd king-ai-ui
npm ci
npm run lint
npm test
npm run build

# 端到端测试（首次需安装浏览器：npx playwright install chromium）
npm run test:e2e
```

CI（`.github/workflows/ci.yml`）包含：

- 后端 `mvn verify` 与测试报告归档
- 独立 MySQL 8 Job：Testcontainers 集成测试（迁移、租户隔离、会话归属）
- 前端 ESLint、Vitest、类型检查、生产构建、Playwright 端到端测试与 `npm audit`
- Trivy 依赖、密钥与配置扫描，以及 CycloneDX SBOM
- API 与 UI 容器镜像构建
- PR 依赖变更审查

## 已知边界

- 本地向量存储与内存 Token 存储仅适用于单实例开发环境，生产必须使用 Milvus 与 Redis。
- Milvus 默认使用共享 collection（按嵌入维度拆分，`space_id` 分区键）；从 `per-kb` 旧布局切换后需要重新索引已有知识库。
- 空间与智能体删除为归档，归档行及其历史数据默认长期保留，界面不提供还原或物理清理入口；空间编码在归档后仍被占用。需要复用编码或彻底清除时应按合规流程由数据库管理员处理。
- 本地向量存储按页扫描并用 Top-K 堆保留结果，单次查询扫描上限由 `king-ai.local-vector.max-scan` 控制（默认 20 万行），仅用于开发与演示。
- 知识库文件解析支持 TXT、Markdown、PDF 与 PNG / JPG；OCR 依赖宿主机或镜像内的 `tesseract` 与语言包，默认关闭，超过 `max-pages` 的扫描件需拆分后上传。
- 发布审批为单级四眼复核：提交人与审批人不能相同；灰度按固定百分比路由，尚未支持定时放量、按指标自动晋级或自动回滚。评测仅做关键词命中与加权得分，不等于完整质量评估。
- SSO 仅实现 OIDC 授权码流程，角色声明映射到空间角色；不包含 SCIM 用户同步、SAML 或 IdP 发起的单点登出。MFA 仅支持 TOTP 与一次性恢复码。
- 配额检查在请求开始时读取本月用量，属于前置近似判断：并发请求可能同时通过检查，适合防止失控花费，不保证精确到最后一个请求。
- 成本按配置单价估算，供应商账单仍以实际计费为准；价格变更只影响之后写入的记录。
- air-gap 包包含容器镜像、Helm Chart、Compose 文件、迁移 SQL 与 License 工具，不包含操作系统软件源镜像；OCR 等系统依赖需提前装入基础镜像或在目标机安装。
