# King AI Platform - 部署指南

本指南覆盖本地开发、Docker Compose 部署、生产配置、认证方式、可观测性、备份升级与故障排查。安全相关的启动校验以 `application-prod.yml` 与 `StartupSecurityChecks` 的实现为准。

## 1. 环境要求

| 组件 | 本地开发 | 生产 |
| --- | --- | --- |
| JDK | 17+ | 17+ |
| Maven | 3.9+ | 构建阶段 |
| Node.js | 20.19+（推荐 22/24） | 构建阶段 |
| MySQL | 不需要（H2） | 8.x |
| Redis | 不需要（内存 Token） | 7.x，必须设置密码 |
| Milvus | 不需要（本地向量） | 2.5.x |
| Docker | 可选 | 24+ / Compose v2 |

## 2. 本地开发

### 2.1 启动后端

```bash
mvn -q -pl king-ai-api -am package -DskipTests
cd king-ai-api
java -jar target/king-ai-api.jar --spring.profiles.active=local
```

本地开发必须显式启用 `local` profile；未指定时会使用 `prod` profile。`local` profile 使用：

- H2 文件数据库：`king-ai-api/data/kingai`
- 内存 Token 存储与内存限流/并发守卫
- 本地向量索引
- 内置 Mock 模型和演示数据
- 端口 `8081`

健康检查：<http://localhost:8081/api/health>

本地演示账号为 `admin` / `admin123`，仅用于本地开发；生产环境不会创建该默认密码。

### 2.2 启动前端

```bash
cd king-ai-ui
npm ci
npm run dev
```

访问 <http://localhost:5173>。Vite 会将 `/api` 代理到 `http://localhost:8081`，可通过 `VITE_API_PROXY` 覆盖：

```bash
VITE_API_PROXY=http://localhost:8082 npm run dev
```

### 2.3 构建与测试

```bash
# 后端编译、单元测试、打包
mvn -B clean verify

# MySQL 8 集成测试：迁移、租户隔离、会话归属（需要 Docker）
mvn -B -pl king-ai-api -am test -Dtest=MySqlMigrationTest -DfailIfNoTests=false

# 前端 Lint、单元测试、类型检查与生产构建
cd king-ai-ui
npm ci
npm run lint
npm test
npm run build
```

## 3. Docker Compose 部署

### 3.1 准备环境变量

```bash
cp .env.example .env
```

至少修改以下值：

```bash
# 两个独立密钥，均不少于 32 字符
KING_AI_SECRET=$(openssl rand -hex 32)
KING_AI_ENCRYPTION_KEY=$(openssl rand -hex 32)

# 数据库与 Redis、MinIO 凭据
MYSQL_ROOT_PASSWORD=...
MYSQL_PASSWORD=...
REDIS_PASSWORD=...
MINIO_ROOT_USER=...
MINIO_ROOT_PASSWORD=...

# 首次启动创建的系统管理员；密码需 12-72 位且同时包含字母和数字
KING_AI_ADMIN_USERNAME=admin
KING_AI_ADMIN_PASSWORD=...

# 浏览器访问来源，多个用逗号分隔
KING_AI_ALLOWED_ORIGINS=https://ai.example.com

# 由 Nginx/负载均衡终止 TLS 时开启
KING_AI_TRUST_FORWARDED_HEADERS=true
KING_AI_COOKIE_SECURE=true

# 默认关闭演示资源
KING_AI_DEMO_ENABLED=false

# 合规导出存储：local 写入 API 数据卷；s3 使用 S3 兼容对象存储（bucket 需预先创建）
KING_AI_EXPORT_STORAGE=local
# KING_AI_EXPORT_S3_ENDPOINT=http://minio:9000
# KING_AI_EXPORT_S3_ACCESS_KEY=...
# KING_AI_EXPORT_S3_SECRET_KEY=...
# KING_AI_EXPORT_S3_BUCKET=king-ai-exports
# KING_AI_EXPORT_S3_PREFIX=exports

# 空间月度 Token 配额与成本估算；配额本身通过 PUT /api/finops/quota 设置
KING_AI_FINOPS_QUOTA_ENABLED=true
KING_AI_FINOPS_CURRENCY=USD
KING_AI_FINOPS_DEFAULT_INPUT_PRICE=0
KING_AI_FINOPS_DEFAULT_OUTPUT_PRICE=0

# SSO / MFA（不启用 SSO 时保留 false）
KING_AI_SSO_ENABLED=false
# KING_AI_OIDC_ISSUER=https://idp.example.com
# KING_AI_OIDC_CLIENT_ID=king-ai
# KING_AI_OIDC_CLIENT_SECRET=...
# KING_AI_OIDC_REDIRECT_URI=https://ai.example.com/api/auth/oidc/callback
# KING_AI_OIDC_ROLE_CLAIM=roles
# KING_AI_OIDC_DEFAULT_SPACE_CODE=default
# KING_AI_OIDC_DEFAULT_ROLE=VIEWER
KING_AI_MFA_ENABLED=true

# OCR（扫描件与图片）；镜像已内置 tesseract 与 chi_sim/eng 语言包
KING_AI_OCR_ENABLED=false
# KING_AI_OCR_LANGUAGES=chi_sim+eng
# KING_AI_OCR_MAX_PAGES=30

# 离线许可证；文件放在 KING_AI_LICENSE_DIR（默认 ./deploy/airgap/license）
KING_AI_LICENSE_ENABLED=false
# KING_AI_LICENSE_FILE=/app/license/license.json
# KING_AI_LICENSE_PUBLIC_KEY=<PEM 或 Base64 公钥>
```

使用 Compose 自带的 MinIO 作为导出存储时，先创建 bucket，再在 `.env` 中启用：

```bash
docker compose exec minio mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
docker compose exec minio mc mb local/king-ai-exports
# 然后在 .env 中设置：
# KING_AI_EXPORT_STORAGE=s3
# KING_AI_EXPORT_S3_ENDPOINT=http://minio:9000
# KING_AI_EXPORT_S3_ACCESS_KEY=<MINIO_ROOT_USER>
# KING_AI_EXPORT_S3_SECRET_KEY=<MINIO_ROOT_PASSWORD>
# KING_AI_EXPORT_S3_BUCKET=king-ai-exports
docker compose up -d api
```

生产 profile 启动时会强制校验：

- `KING_AI_SECRET` 已配置、不少于 32 字符，且不是示例默认值。
- `KING_AI_ENCRYPTION_KEY` 已配置、不少于 32 字符，且不能与 `KING_AI_SECRET` 相同。
- Token 存储必须为 Redis。
- 未检测到系统管理员时必须提供 `KING_AI_ADMIN_USERNAME` 和强密码 `KING_AI_ADMIN_PASSWORD`。

首次登录会被要求修改初始密码（接口返回 HTTP 428，前端跳转到修改密码页）。

### 3.2 启动与检查

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

首次启动需要拉取镜像并等待 MySQL、Redis、Milvus 就绪，通常需要 1 到 3 分钟。访问：

- 管理后台：<http://localhost>
- 健康检查：<http://localhost:8080/api/health>
- 公开对话页：`http://localhost/#/chat/{publicId}`（需先在后台发布智能体）

Compose 中 API 默认只绑定 `127.0.0.1:8080`，建议仅通过 UI 的 Nginx 反向代理访问，不直接暴露 API。

API 的健康检查使用 `/actuator/health/readiness`，MySQL、Redis、Milvus 未就绪时不会被视为可用。UI 容器同样配置了独立健康检查，并通过 `UI_CPU_LIMIT`、`UI_MEMORY_LIMIT`、`UI_CPU_RESERVATION`、`UI_MEMORY_RESERVATION` 控制资源配额。

### 3.3 停止与数据卷

```bash
docker compose down          # 停止，保留数据
docker compose down -v       # 停止并删除全部数据卷（不可恢复）
```

MySQL、Redis、etcd、MinIO、Milvus 均使用命名卷持久化。

## 4. 认证与 API 调用

### 4.1 登录与 Cookie

浏览器登录成功后，服务端通过 `Set-Cookie` 写入 `HttpOnly` 会话 Cookie（默认名 `king_session`，`SameSite=Lax`，生产环境应开启 `Secure`）。前端不需要也不能读取该 Cookie。

命令行调用建议使用 Cookie Jar：

```bash
# 登录并保存 Cookie
curl -s -c cookies.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your-password"}'

# 后续请求携带 Cookie 与空间 ID
curl -s -b cookies.txt http://localhost:8080/api/agents \
  -H "X-Space-Id: 1"

# 退出登录
curl -s -b cookies.txt -X POST http://localhost:8080/api/auth/logout
```

登录响应中的 `data.token` 也支持 `Authorization: Bearer <token>`，主要用于非浏览器 API 客户端；浏览器端统一使用 Cookie，避免把令牌写入 `localStorage`。

### 4.2 空间与分页

所有空间资源都必须携带 `X-Space-Id`。列表接口统一支持分页：

```bash
curl -s -b cookies.txt "http://localhost:8080/api/agents?page=1&size=20&keyword=助手" \
  -H "X-Space-Id: 1"
```

响应中的 `data` 结构：

```json
{
  "list": [],
  "total": 0,
  "page": 1,
  "size": 20,
  "totalPages": 0,
  "hasNext": false,
  "hasPrevious": false
}
```

`size` 上限为 200，超过会被截断。工作台聚合统计使用：

```bash
curl -s -b cookies.txt http://localhost:8080/api/workspace/stats \
  -H "X-Space-Id: 1"
```

### 4.3 流式对话

```bash
curl -N -b cookies.txt -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -H "X-Space-Id: 1" \
  -d '{"agentId":1,"message":"你好"}'
```

SSE 事件包括 `meta`、`citation`、`reasoning`、`content`、`tool_call`、`tool_result`、`tool_approval`、`usage`、`done`、`error`、`retry`、`degraded` 和 `heartbeat`。服务端每 15 秒发送心跳，单次连接最长 600 秒。

每个空间默认最多同时运行 4 个对话流，超过后返回 HTTP 429。可通过 `KING_AI_CHAT_MAX_CONCURRENT_PER_SPACE` 调整，设为 `0` 关闭限制。多实例部署时 Redis 实现会跨实例共享配额。

### 4.4 公开对话

公开对话使用服务端签发的访客 Cookie（默认 `king_visitor`，HMAC 签名），客户端无需也不应自行传递 `X-Visitor-Id`：

```bash
# 首次请求会签发访客 Cookie
curl -s -c visitor.txt "http://localhost:8080/api/public/chat/sessions?publicId=your-public-id"

curl -N -b visitor.txt -X POST http://localhost:8080/api/public/chat/send \
  -H "Content-Type: application/json" \
  -d '{"publicId":"your-public-id","message":"你好"}'
```

停止生成与工具确认接口会校验访客 Cookie 与请求归属。

### 4.5 用量与 Token 配额

每次对话结束后，服务端向 `king_ai_usage_ledger` 追加一条用量记录（空间、智能体、模型、输入/输出 Token、估算成本、状态）。查询当前空间本月用量：

```bash
curl -s -H "X-Space-Id: 1" -b cookies.txt http://localhost:8080/api/finops/usage
```

设置空间月度 Token 配额（仅空间 `OWNER`，`0` 表示不限额）：

```bash
curl -s -X PUT -H "Content-Type: application/json" -H "X-Space-Id: 1" -b cookies.txt \
  -d '{"monthlyTokenQuota":1000000}' http://localhost:8080/api/finops/quota
```

- 配额用尽后对话返回 HTTP 429，递增计数器 `king.ai.finops.quota_rejected`，并写入 `chat.quota_rejected` 审计事件；已完成的历史用量不受影响。
- 成本按每百万 Token 单价估算：默认单价由 `KING_AI_FINOPS_DEFAULT_INPUT_PRICE` / `KING_AI_FINOPS_DEFAULT_OUTPUT_PRICE` 提供，模型级覆盖使用 `king-ai.finops.prices.<model>.input-per-million` / `output-per-million`（需通过 JVM 系统属性或配置文件设置）。价格变更只影响之后写入的记录。
- 配额检查是请求前近似判断，并发请求可能同时通过；它用于防止失控花费，不保证精确到最后一个请求。

### 4.6 空间与智能体归档

- `DELETE /api/spaces/{id}` 与 `DELETE /api/agents/{id}` 只写入 `deleted_at`。空间归档后，其会话、消息、审计日志、法律保留、导出任务、用量账本以及供应商、知识库、MCP、技能原样保留；成员授权会被移除。
- 归档空间的公开智能体不再可访问，管理端查询与详情接口返回 404。归档智能体不能再开始新对话，但历史会话仍显示其名称。
- 空间编码在归档后继续占用唯一索引；如果需要复用编码，应由 DBA 在确认合规要求后手动处理归档行。
- 若 `KING_AI_ADMIN_SPACE_CODE` 指向的空间已被归档，服务启动时会自动恢复该空间，避免管理员失去默认空间。

### 4.7 向量存储布局

- 生产 Milvus 默认使用共享 collection：`MILVUS_COLLECTION_PREFIX`（默认 `king_ai_chunks_v2`）加嵌入维度后缀，例如 `king_ai_chunks_v2_1024`；`space_id` 为分区键，`MILVUS_PARTITIONS_NUM` 默认 64，创建后不可修改。
- 检索与删除均按 `space_id + kb_id` 过滤；删除知识库只删除该知识库的向量行，不再删除整个 collection，旧版每知识库 collection 会在删除时顺带清理。
- 需要沿用旧布局时设置 `MILVUS_COLLECTION_MODE=per-kb`。从 `per-kb` 切换到 `shared` 后，已有知识库需要重新索引（重新生成向量）才能被检索到。
- 本地开发使用 `king-ai.local-vector.scan-batch-size`（默认 2000）分页扫描，`king-ai.local-vector.max-scan`（默认 200000，`0` 不限制）控制单次查询扫描行数。

### 4.8 SSO 与 MFA

OIDC 单点登录需要配置 `KING_AI_SSO_ENABLED=true`，并至少提供 issuer、client ID 与回调地址；机密客户端还需 client secret。回调地址固定为：

```text
https://<你的域名>/api/auth/oidc/callback
```

- 登录页通过 `GET /api/auth/sso/config` 判断是否展示 SSO 入口；未启用时返回 `enabled=false`。
- 授权码回调成功后写入会话 Cookie 并跳转 `KING_AI_OIDC_SUCCESS_REDIRECT`（默认 `/#/dashboard`），失败跳转 `/#/login?sso_error=...`。
- 角色声明 `KING_AI_OIDC_ROLE_CLAIM` 默认 `roles`，支持 `ADMIN` 或 `space-code:EDITOR` 格式；只提升不降级，平台管理员不受影响。
- `KING_AI_OIDC_AUTO_CREATE_USERS=false` 时，未绑定且邮箱未匹配的 SSO 用户会被拒绝，需管理员预先创建账号并填写相同邮箱。
- SSO 首次创建用户会执行许可证用户容量检查。

MFA 默认可由用户在「账号安全」中自行启用，`KING_AI_MFA_ENABLED=false` 可全局关闭。密码登录返回：

```json
{"data":{"token":null,"user":null,"spaces":[],"currentSpaceId":null,"mfaRequired":true,"mfaChallenge":"..."}}
```

随后提交动态码或恢复码：

```bash
curl -s -c cookies.txt -X POST http://localhost:8080/api/auth/mfa/verify \
  -H "Content-Type: application/json" \
  -d '{"challenge":"...","code":"123456"}'
```

挑战默认 5 分钟有效（`KING_AI_MFA_CHALLENGE_TTL`），恢复码数量由 `KING_AI_MFA_RECOVERY_CODE_COUNT` 控制（默认 8）。MFA 密钥与恢复码哈希加密存储；丢失认证器时使用恢复码登录并重新绑定。

### 4.9 发布审批、灰度与评测

发布走统一版本通道，接口位于 `/api/agents/{agentId}/release`：

```bash
# 维护评测用例（EDITOR+）
curl -s -b cookies.txt -X POST http://localhost:8080/api/agents/1/release/eval-cases \
  -H "Content-Type: application/json" -H "X-Space-Id: 1" \
  -d '{"name":"价格问题","prompt":"退款政策是什么？","expectedKeywords":"退款,7 天","weight":2,"status":0}'

# 运行评测、提交审批
curl -s -b cookies.txt -X POST http://localhost:8080/api/agents/1/release/3/evaluate \
  -H "X-Space-Id: 1"
curl -s -b cookies.txt -X POST http://localhost:8080/api/agents/1/release/submit \
  -H "X-Space-Id: 1"

# 审批人通过（不能与提交人相同），再设置灰度或正式发布
curl -s -b approver.txt -X POST http://localhost:8080/api/agents/1/release/3/approve \
  -H "Content-Type: application/json" -H "X-Space-Id: 1" -d '{"comment":"ok"}'
curl -s -b approver.txt -X POST http://localhost:8080/api/agents/1/release/3/canary \
  -H "Content-Type: application/json" -H "X-Space-Id: 1" -d '{"percent":20}'
curl -s -b approver.txt -X POST http://localhost:8080/api/agents/1/release/3/promote \
  -H "Content-Type: application/json" -H "X-Space-Id: 1" -d '{}'
```

- 状态机：`DRAFT -> PENDING_REVIEW -> APPROVED -> CANARY -> PUBLISHED`，驳回为 `REJECTED`；回滚会基于历史快照生成新版本。
- 审批前若存在启用用例，最近一次评测得分必须达到 `KING_AI_RELEASE_EVAL_PASS_SCORE`（默认 0.8）。
- `KING_AI_RELEASE_REQUIRE_APPROVAL=false` 时提交即发布；此时旧发布接口仍返回 409，请统一使用 `submit`。
- 灰度比例范围 1–99，只有 `APPROVED` / `CANARY` 版本可调整；正式发布后智能体公开页切换到新版本。

### 4.10 OCR

API 镜像已安装 `tesseract-ocr`、`tesseract-ocr-data-eng` 与 `tesseract-ocr-data-chi_sim`。启用后：

- 文本层字符数低于 `min-text-chars-per-page * pages` 的 PDF 会按 `KING_AI_OCR_RENDER_DPI`（默认 200）逐页渲染并识别。
- PNG / JPG 直接识别；未启用 OCR 时图片上传返回 400。
- 页数超过 `KING_AI_OCR_MAX_PAGES`（默认 30）会被拒绝，需拆分后上传。
- 单页识别超时由 `KING_AI_OCR_TIMEOUT_SECONDS`（默认 120）控制；单页失败只记录告警，全部失败时回退 PDF 文本层。

其他语言需要自定义镜像并安装对应 `tesseract-ocr-data-*` 包，然后调整 `KING_AI_OCR_LANGUAGES`。

### 4.11 许可证与 air-gap

许可证文件格式、Ed25519 签名步骤见 [deploy/airgap/license/README.md](../deploy/airgap/license/README.md)。Compose 部署将文件放在 `KING_AI_LICENSE_DIR`（默认 `./deploy/airgap/license`），容器内路径为 `/app/license/license.json`：

```bash
KING_AI_LICENSE_ENABLED=true
KING_AI_LICENSE_FILE=/app/license/license.json
# PEM 含换行，写入 .env 时使用单行 Base64：
# KING_AI_LICENSE_PUBLIC_KEY=$(base64 -w0 license-public-key.pem)
```

`KING_AI_LICENSE_PUBLIC_KEY` 也接受完整 PEM 文本（通过 shell 环境或 Secret 注入，不要写进 `.env`），以及 32 字节原始公钥的 Base64。

- 启用后，许可证缺失、签名无效或已过期都会阻止应用启动。
- 创建空间、用户与智能体前会校验容量上限，超限返回 403；SSO 自动建号同样受限。
- `GET /api/license` 仅管理员可访问，返回签发方、有效期、上限、当前用量与特性；未启用时状态为 `DISABLED`。
- Helm 部署通过 `api.license.enabled`、`api.license.existingSecret`、`api.license.secretKey` 与 `api.license.publicKey` 挂载 Secret，示例见 chart README。

离线交付使用 `deploy/airgap/build-bundle.sh` 打包镜像、Chart、Compose、迁移 SQL 与 License 工具，在目标机用 `load-images.sh` 校验并导入；完整流程见 [deploy/airgap/README.md](../deploy/airgap/README.md)。

## 5. 可观测性

### 5.1 信号端点

- 存活检查：`/actuator/health/liveness`
- 就绪检查：`/actuator/health/readiness`（包含数据库与 Token 存储）
- 容器健康检查：`/actuator/health/readiness`（`readinessState + db + stateStore`，任一异常返回 503）
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

## 6. 备份与恢复

### 6.1 备份

```bash
# MySQL
mysqldump --single-transaction --routines --triggers \
  -h 127.0.0.1 -u king -p king_ai > backup-$(date +%F).sql

# Redis（AOF + RDB）
redis-cli -a "$REDIS_PASSWORD" BGSAVE

# Milvus：备份 MinIO 中的对象数据与 etcd 元数据；生产建议使用 Milvus 官方备份工具
```

同时备份 `.env` 中的密钥，但不要与数据备份存放在同一位置。加密密钥丢失将导致供应商 API Key 与 MCP 请求头无法解密。

### 6.2 恢复

1. 停止 API 与 UI，保留基础设施。
2. 恢复 MySQL 备份，确认 `flyway_schema_history` 与备份版本一致。
3. 恢复 Redis 与 Milvus 数据。
4. 使用与备份时一致的 `KING_AI_SECRET`、`KING_AI_ENCRYPTION_KEY` 启动 API。
5. 检查 `/api/health` 与 `/actuator/health/readiness`，再恢复流量。

## 7. 升级与回滚

```bash
# 1. 备份数据库、Redis、Milvus 与密钥
# 2. 拉取新镜像或重新构建
docker compose build api ui
docker compose up -d api ui

# 3. 观察迁移日志与健康检查
docker compose logs -f api
curl -s http://127.0.0.1:8080/api/health
```

Flyway 迁移只向前执行。回滚应用镜像前必须确认新版本没有执行破坏性迁移；否则应先从备份恢复数据库。生产发布前建议在预发环境使用数据库副本执行一次迁移演练。

## 7.1 Kubernetes / Helm 部署

Helm chart 位于 `deploy/helm/king-ai`，完整 values 说明见该目录 README。

```bash
kubectl create namespace king-ai
# 按 README 创建 king-ai-app、king-ai-admin、king-ai-db、king-ai-redis
helm upgrade --install king-ai deploy/helm/king-ai -n king-ai \
  --set ingress.enabled=true --set ingress.host=ai.example.com \
  --set externalDatabase.host=mysql.internal \
  --set externalRedis.host=redis.internal \
  --set externalMilvus.url=http://milvus.internal:19530 \
  --set networkPolicy.egress.databaseCidrs={10.0.10.0/24} \
  --set networkPolicy.egress.redisCidrs={10.0.20.0/24} \
  --set networkPolicy.egress.milvusCidrs={10.0.30.0/24} \
  --wait --timeout 15m
```

启用离线许可证时额外创建 Secret，并把公钥传给 Chart（`--set-file` 便于保留 PEM 换行）：

```bash
kubectl create secret generic king-ai-license -n king-ai \
  --from-file=license.json=./license.json
helm upgrade --install king-ai deploy/helm/king-ai -n king-ai \
  -f prod-values.yaml \
  --set api.license.enabled=true \
  --set api.license.existingSecret=king-ai-license \
  --set-file api.license.publicKey=license-public-key.pem \
  --wait --timeout 15m
```

发布顺序：`pre-install/pre-upgrade` 迁移 Job → API/UI 滚动更新。Job 失败时 Helm 停止，不继续滚动。
`persistence.exports.mode=pvc` 需要 ReadWriteMany 存储；只有 RWO 时将 API 限制为单副本，或设置 `api.export.storage=s3` 使用对象存储（见 chart README「合规导出存储」）。
SSE 在 Pod 滚动升级期间会被终止，断线恢复属于 P2-2；Ingress 已关闭缓冲并设置 600 秒超时。

回滚：

```bash
helm history king-ai -n king-ai
helm rollback king-ai <REVISION> -n king-ai --wait --timeout 15m
```

Flyway 迁移只向前。若新版本执行了破坏性迁移，回滚镜像前必须先从备份恢复数据库。

## 7.2 备份与恢复演练

生产备份使用 `deploy/backup` 脚本，详细步骤见 `deploy/backup/README.md`。

```bash
# 每日全量 + 每 5 分钟 binlog + 每日 Milvus
deploy/backup/mysql-backup.sh
deploy/backup/mysql-binlog-archive.sh
deploy/backup/milvus-backup.sh

# 隔离 namespace 演练
DRILL_NAMESPACE=king-ai-drill-$(date +%Y%m%d) \
MYSQL_DUMP=/backup/<label>/king_ai.sql.gz \
MILVUS_BACKUP_NAME=<label> \
MILVUS_BACKUP_MINIO_ADDRESS=minio.backup.internal \
MILVUS_BACKUP_MINIO_ACCESS_KEY=... \
MILVUS_BACKUP_MINIO_SECRET_KEY=... \
MILVUS_BACKUP_MINIO_BUCKET=king-ai-backups \
deploy/backup/restore-drill.sh
```

验收阈值：RPO ≤ 15 分钟、RTO ≤ 1 小时。实测值写入 `docs/operations/backup-drill-YYYY-MM-DD.md`。

## 7.3 迁移版本分配

| 版本 | 内容 | 所属计划 |
| --- | --- | --- |
| V9 | 内容合规设置 | G0-1/G0-2 |
| V10 | 保留策略、法律保留、导出任务 | G0-3/G0-4 |
| V11 | 访问复核、`last_login_at`、`api_key_rotated_at` | G0-3/G0-4 |
| V12 | 消息增量落库 | Sprint 2 |
| V13 | 用量账本与空间配额 | Sprint 2 |
| V14 | 空间与智能体逻辑删除 | Sprint 2 |
| V15 | 发布审批、灰度与评测用例 | Sprint 3 |
| V16 | MFA 字段与 OIDC 身份绑定 | Sprint 3 |
| V17 | MCP OAuth 配置与令牌 | Sprint 3 |

Helm 只执行镜像内 Flyway；迁移文件必须随 API 镜像发布。

## 8. 安全清单

- 使用独立、随机的 `KING_AI_SECRET` 与 `KING_AI_ENCRYPTION_KEY`，并制定轮换计划；轮换加密密钥时把旧密钥放入 `KING_AI_PREVIOUS_ENCRYPTION_KEYS`。
- 全链路启用 TLS：浏览器到 Nginx、Nginx 到 API、API 到 MySQL/Redis/Milvus。
- 保持 `KING_AI_BLOCK_PRIVATE_NETWORKS=true`；确需访问内部模型或 MCP 服务时，通过 `KING_AI_ALLOWED_OUTBOUND_HOSTS` 精确放行。
- 保持 `KING_AI_COOKIE_SECURE=true`，并配置准确的 `KING_AI_ALLOWED_ORIGINS`。
- 设置 `KING_AI_MANAGEMENT_TOKEN` 保护 `/actuator` 下除 health 外的端点（prometheus、info 等）；Prometheus 抓取时通过 `X-Actuator-Token` 或 Bearer 凭证携带该令牌（见 `deploy/observability/prometheus/prometheus.yml` 注释）。
- 仅暴露 UI 入口；MySQL、Redis、Milvus、MinIO、etcd 不映射公网端口。Milvus 端口默认绑定 `127.0.0.1`（`MILVUS_BIND` 可覆盖），仅在确有外部工具需要时放开并启用 `MILVUS_TOKEN`。
- 首次登录后立即修改管理员密码；为成员分配最小必要空间角色。
- 定期检查审计日志、登录失败指标与公开对话限流命中情况。
- 生产关闭 `KING_AI_DEMO_ENABLED`，不要保留演示智能体、Mock 供应商或演示 MCP。
- 建议启用 MFA 并为管理员与空间 `OWNER` 强制绑定 TOTP；启用 SSO 时确认 `KING_AI_OIDC_REDIRECT_URI` 使用 HTTPS 且与 IdP 注册值完全一致。
- 启用 `KING_AI_LICENSE_ENABLED` 前先验证公钥与许可证签名；许可证文件与公钥应通过 Secret 挂载，不要写入镜像。
- OCR 会增加 CPU 与内存占用并调用本地二进制，只应在受信服务器开启；如需其他语言包，构建自定义镜像而不是在运行时安装。
- 离线交付包通过 `SHA256SUMS` 校验后再导入镜像；`--skip-checksum` 仅用于紧急恢复。
- 定期执行依赖与镜像扫描；CI 会执行后端测试、前端构建、Trivy 漏洞/密钥/配置扫描并产出 SBOM。

### 内容合规与 PII 脱敏

- 空间管理员通过 `GET/PUT /api/compliance-settings` 配置 PII 开关、审核模式与动作、违禁词和自定义 PII 正则；两个接口都要求空间 `ADMIN` 角色，平台管理员自动通过。未配置的空间使用下方环境变量默认值，审核动作默认为 `BLOCK`。
- 默认配置下 PII 开启（`KING_AI_PII_DEFAULT_ENABLED`，默认 `true`）。内置规则覆盖邮箱、手机号、身份证、银行卡（Luhn 校验）、API Key、Bearer Token 与 IPv4；自定义正则上限由 `king-ai.compliance.pii.max-custom-rules` 决定，默认 20，`application.yml` 未提供环境变量占位符，需要调整时用 JVM 系统属性 `-Dking-ai.compliance.pii.max-custom-rules=...`。
- 默认审核模式由 `KING_AI_MODERATION_MODE`（默认 `OFF`）设置，可选 `OFF`/`LOCAL`/`EXTERNAL`/`HYBRID`；生产公开对话建议 `LOCAL` 或 `HYBRID`。
- 外部审核通过 `KING_AI_MODERATION_URL` 与 `KING_AI_MODERATION_TOKEN` 配置。输入、输出和工具结果都会先在本地完成 PII 脱敏再送审，但只有命中规则的 PII 会被移除：空间关闭 PII 时脱敏会原样返回文本，未命中任何规则的值（如校验和不合法的银行卡号或身份证号、未被枚举的 PII 类型）同样会原样发给审核提供方。启用 `EXTERNAL` 前请确认这种边界符合你的合规要求。URL 缺失、出站地址被策略拦截、超时（`KING_AI_MODERATION_TIMEOUT_MS`，默认 `2000`）或任何提供方异常都会降级为本地规则；公开对话在审核不可用时 fail-closed（`KING_AI_MODERATION_FAIL_CLOSED` 默认 `true`）。
- 流式脱敏安全带由 `KING_AI_PII_STREAM_HOLDBACK` 控制，默认 128，运行时限制在 32–1024。它只保证长度不超过该值的敏感串在跨 delta 时不被拆开。自定义正则或长违禁词（违禁词最长 50 字符，已超过 32 的下限）可能匹配超过该长度的串：只要其可匹配长度可能大于当前配置的 holdback，就必须调大 `KING_AI_PII_STREAM_HOLDBACK`，否则该串会在放行边界处被切开而漏检。这是刻意的性能/合规折中，整段缓冲输出模式留待后续版本。
- Fail-closed 上限（仅在空间开启 PII 时生效；关闭 PII 时 `RedactionService` 直接原样返回文本，不会触发下述替换）：单次送入脱敏引擎的文本一旦超过 200,000 字符，脱敏引擎会整段替换为 `[REDACTED:OVERSIZE]`。该上限只作用于单个字符串（如超大工具结果）：普通对话输入在更早的 `max-input-chars` 校验处就会被拒绝（默认 10,000），常规流式输出按缓冲窗口（holdback + 单个 delta）分批脱敏，单个 delta 通常远小于该上限；但若供应商忽略 `stream=true` 返回完整 JSON，`OpenAiClient.readFullResponse` 会把整段回答作为一次 `onContent` 交给安全护栏，该次脱敏仍是单个字符串，超大回答同样可能触达 200,000 字符上限。保存自定义正则时，包含无界嵌套量词的模式（如 `(a+)+`）会被拒绝，历史遗留的同类模式会让整段消息返回 `[REDACTED:REGEX_LIMIT]`，原消息不会放行；该 fail-closed 同样只在 PII 开启时生效。
- 审计动作：`chat.moderation_blocked`（输入/输出，显式写入）与 `compliance_setting.update`（由请求审计拦截器根据控制器名与方法名自动生成，仅 PUT 触发）。
- 指标：`king.ai.compliance.redactions{surface,category}` 与 `king.ai.compliance.moderation{surface,action,category}`。输入与工具侧脱敏使用 `category=any`，输出侧使用规则类别（`PHONE`、`EMAIL`、`ID_CARD`、`BANK_CARD`、`API_KEY`、`BEARER`、`IP`、`CUSTOM` 等）。
- 外部审核返回的类别会归一化到固定白名单（`BLOCKLIST`、`SPAM`、`FLOOD`、`MODERATION_UNAVAILABLE`、`EXTERNAL`），白名单外的值一律记为 `EXTERNAL`（空值记为 `UNKNOWN`），任何自由文本都不会进入审计详情或指标标签。
- 日志、审计记录、指标标签与 SSE 事件都不包含命中的明文；排障只使用规则 ID、类别与计数（合规 `degraded` 事件仅携带 `reason`、`surface`，以及 `count` 或 `category`；模型降级另有独立的 `degraded` 事件，携带 `message` 与 `model`）。

### 数据保留、法律保留与导出

- 默认关闭保留清理：`KING_AI_RETENTION_ENABLED=false`；首次开启保持 `KING_AI_RETENTION_DRY_RUN=true` 观察一轮。
- 策略按 `SESSION/MESSAGE/AUDIT/EXPORT` 配置；法律保留优先，清理与过期导出删除都会跳过 active hold。
- 保留清理开启后，审计日志不再走 `king-ai.audit.retention-days` 的旧清理任务，必须显式配置 `AUDIT` 策略，否则审计日志不会自动清理。
- 会话清理前默认写 `jsonl.gz` 归档到 `KING_AI_EXPORT_DIR`；归档失败则本轮不删除该批。
- 导出仅 OWNER/ADMIN，文件 24 小时过期；本地存储路径按空间隔离，禁止路径穿越。
- 审计动作：`compliance.retention`、`compliance.retention_policy_update`、`compliance.legal_hold_create`、`compliance.legal_hold_release`、`compliance.export_create`、`compliance.export`、`compliance.export_download`、`compliance.export.expire`、`compliance.access_review_generate`、`compliance.access_review_decide`、`compliance.access_review_export`。
- 指标：`king.ai.compliance.retention.deleted{type,dryRun}`（按资源类型与是否演练统计本轮删除量）。导出任务状态请查询导出任务接口或审计日志；当前未暴露导出任务指标。

### 访问复核

- 每季度 1 日 04:00 自动生成；也可 `POST /api/compliance/access-reviews` 手动生成。
- 报告包含成员角色/最近登录、供应商密钥轮换时间、MCP 配置更新时间、公开智能体。
- 成员可确认/降级/移除；供应商/MCP/公开智能体只能确认或知悉，实际变更到对应模块执行。
- 降级阶梯：OWNER→ADMIN→EDITOR→VIEWER；AUDITOR→VIEWER；VIEWER 不可再降。
- 多实例/Helm 部署：季度任务通过 `JobLock` 抢占跨实例锁，未取得锁的实例跳过本轮，避免生成重复 OPEN 复核；也可将 `KING_AI_ACCESS_REVIEW_CRON` 设为 `-` 关闭自动生成。

## 9. 故障排查

### 启动失败

- `未配置 KING_AI_SECRET` / `长度不能少于 32 个字符`：检查生产环境变量。
- `必须配置 KING_AI_ENCRYPTION_KEY`：生成独立密钥，不能与签名密钥相同。
- `未检测到系统管理员`：设置 `KING_AI_ADMIN_USERNAME` 与强密码；已存在管理员时不会重复创建。
- `许可证无效，拒绝启动`：检查 `KING_AI_LICENSE_FILE` 是否挂载、公钥是否与签名密钥匹配、许可证是否过期；确认后重启。
- Flyway 迁移失败：检查 `flyway_schema_history`、数据库账号权限与迁移日志；不要手工修改历史记录。

### 健康检查失败

- `/api/health` 返回 503：查看响应中的 `db` 与 `stateStore` 字段。
- Redis 连接失败：检查密码、TLS 配置与网络策略；本地开发可切换 `KING_AI_TOKEN_STORE=memory`。
- Milvus 不可用不会阻塞就绪检查，但知识库检索与索引会失败，应单独监控。

### 对话异常

- HTTP 429：空间并发配额、空间月度 Token 配额或公开对话限流触发，检查 `KING_AI_CHAT_MAX_CONCURRENT_PER_SPACE`、`GET /api/finops/usage` 的剩余配额与 `PUBLIC_RATE_LIMIT_PER_MINUTE`。
- SSE 首字慢：确认 Nginx 对 `/api/` 关闭 `proxy_buffering`，并适当提高 `proxy_read_timeout`。
- 工具调用等待确认：检查前端是否展示 `tool_approval` 事件，确认接口是否在 120 秒内调用。
- 模型持续失败：查看 `retry` / `degraded` 事件与熔断指标；确认供应商地址、密钥和出站 allowlist。
- 发布被 409 拒绝：`KING_AI_RELEASE_REQUIRE_APPROVAL=true` 时不能直接发布，改为 `POST /api/agents/{id}/release/submit`；审批通过后再灰度或正式发布。
- 审批被拒绝：确认审批人不是提交人，且最近一次评测得分达到 `KING_AI_RELEASE_EVAL_PASS_SCORE`。
- OCR 返回 400 或超时：确认镜像内有 `tesseract` 与对应语言包、`KING_AI_OCR_LANGUAGES` 正确，扫描件页数未超过 `KING_AI_OCR_MAX_PAGES`；可提高 `KING_AI_OCR_TIMEOUT_SECONDS` 或降低 `KING_AI_OCR_RENDER_DPI`。

### 前端无法登录或写操作被拒绝

- 检查浏览器是否允许 Cookie（`HttpOnly` + `Secure` + `SameSite=Lax`）。
- HTTPS 部署必须设置 `KING_AI_COOKIE_SECURE=true`；本地 HTTP 开发设置为 `false`。
- 跨域部署时配置 `KING_AI_ALLOWED_ORIGINS`，并确保反向代理正确传递 `Host`、`X-Forwarded-*`。
- SSO 回调失败：确认回调地址、客户端 ID/密钥与 IdP 一致，服务器可访问 issuer 的发现文档与 JWKS；登录页上的 `sso_error` 会给出具体原因。若 IdP 使用自签证书，需要把 CA 导入 API 容器的信任库。
- MFA 动态码无效：检查服务器时间是否同步（TOTP 依赖时间窗口）；挑战过期后重新输入密码。恢复码用尽前应重新绑定认证器。
