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
java -jar target/king-ai-api.jar
```

默认 `local` profile 使用：

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

# 前端类型检查与生产构建
cd king-ai-ui
npm ci
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

## 5. 可观测性

- 存活检查：`/actuator/health/liveness`
- 就绪检查：`/actuator/health/readiness`（包含数据库与 Token 存储）
- 容器健康检查：`/api/health`（数据库 + 状态存储，异常返回 503）
- Prometheus 指标：`/actuator/prometheus`
- OpenAPI：生产默认关闭；需要时设置 `SPRINGDOC_ENABLED=true`
- 结构化日志：`LOG_STRUCTURED_FORMAT=ecs`，日志包含 `requestId` 与 `spaceId` 上下文

建议重点告警：

- `king_ai_chat_active` 持续升高
- `king_ai_chat_rejected_total{reason="space_quota"}` 出现突增
- `king_ai_chat_duration_seconds` P95 超时
- `king_ai_index_jobs_total{outcome="failed"}` 增长
- 就绪检查失败

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

## 8. 安全清单

- 使用独立、随机的 `KING_AI_SECRET` 与 `KING_AI_ENCRYPTION_KEY`，并制定轮换计划；轮换加密密钥时把旧密钥放入 `KING_AI_PREVIOUS_ENCRYPTION_KEYS`。
- 全链路启用 TLS：浏览器到 Nginx、Nginx 到 API、API 到 MySQL/Redis/Milvus。
- 保持 `KING_AI_BLOCK_PRIVATE_NETWORKS=true`；确需访问内部模型或 MCP 服务时，通过 `KING_AI_ALLOWED_OUTBOUND_HOSTS` 精确放行。
- 保持 `KING_AI_COOKIE_SECURE=true`，并配置准确的 `KING_AI_ALLOWED_ORIGINS`。
- 仅暴露 UI 入口；MySQL、Redis、Milvus、MinIO、etcd 不映射公网端口。
- 首次登录后立即修改管理员密码；为成员分配最小必要空间角色。
- 定期检查审计日志、登录失败指标与公开对话限流命中情况。
- 生产关闭 `KING_AI_DEMO_ENABLED`，不要保留演示智能体、Mock 供应商或演示 MCP。
- 定期执行依赖与镜像扫描；CI 会执行后端测试、前端构建、Trivy 漏洞/密钥/配置扫描并产出 SBOM。

## 9. 故障排查

### 启动失败

- `未配置 KING_AI_SECRET` / `长度不能少于 32 个字符`：检查生产环境变量。
- `必须配置 KING_AI_ENCRYPTION_KEY`：生成独立密钥，不能与签名密钥相同。
- `未检测到系统管理员`：设置 `KING_AI_ADMIN_USERNAME` 与强密码；已存在管理员时不会重复创建。
- Flyway 迁移失败：检查 `flyway_schema_history`、数据库账号权限与迁移日志；不要手工修改历史记录。

### 健康检查失败

- `/api/health` 返回 503：查看响应中的 `db` 与 `stateStore` 字段。
- Redis 连接失败：检查密码、TLS 配置与网络策略；本地开发可切换 `KING_AI_TOKEN_STORE=memory`。
- Milvus 不可用不会阻塞就绪检查，但知识库检索与索引会失败，应单独监控。

### 对话异常

- HTTP 429：空间并发配额或公开对话限流触发，检查 `KING_AI_CHAT_MAX_CONCURRENT_PER_SPACE` 与 `PUBLIC_RATE_LIMIT_PER_MINUTE`。
- SSE 首字慢：确认 Nginx 对 `/api/` 关闭 `proxy_buffering`，并适当提高 `proxy_read_timeout`。
- 工具调用等待确认：检查前端是否展示 `tool_approval` 事件，确认接口是否在 120 秒内调用。
- 模型持续失败：查看 `retry` / `degraded` 事件与熔断指标；确认供应商地址、密钥和出站 allowlist。

### 前端无法登录或写操作被拒绝

- 检查浏览器是否允许 Cookie（`HttpOnly` + `Secure` + `SameSite=Lax`）。
- HTTPS 部署必须设置 `KING_AI_COOKIE_SECURE=true`；本地 HTTP 开发设置为 `false`。
- 跨域部署时配置 `KING_AI_ALLOWED_ORIGINS`，并确保反向代理正确传递 `Host`、`X-Forwarded-*`。
