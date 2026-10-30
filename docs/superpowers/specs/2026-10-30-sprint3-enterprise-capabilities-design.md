# Sprint 3：企业能力设计

**日期：** 2026-10-30
**范围：** SSO/OIDC + MFA、Agent 发布审批/灰度/评测、OCR、MCP OAuth、air-gap 交付包与 License。
**原则：** 保持模块化单体；新增状态全部落库且可跨实例；默认拒绝；所有变更可审计；数据库迁移只增不改。

---

## 1. 交付拆分

五个能力彼此独立，按风险与依赖排序交付：

| 增量 | 解决的问题 | 依赖 | 交付物 |
| --- | --- | --- | --- |
| A. 发布治理 | 公开 Agent 变更缺少四眼原则、评测与灰度 | 已有 `king_ai_agent_version` | 状态机、评测集、灰度路由、审批 API |
| B. 身份 | 仅本地账号；无二次验证 | 已有会话/角色体系 | OIDC 授权码登录、空间角色映射、TOTP MFA |
| C. 文档解析 | 扫描件不可检索 | 索引任务链 | Tesseract OCR 适配器、PDF 低文本回退、图片上传 |
| D. MCP 认证 | 只能静态 header/token | MCP 客户端 | OAuth2 client_credentials / refresh_token |
| E. 私有化交付 | 无离线安装与授权 | Helm/Compose | 离线 bundle 脚本、Ed25519 License、限额校验 |

本次五个增量都实现后端闭环；UI 覆盖登录 MFA/OIDC、Agent 发布面板、MCP OAuth 字段与 License 状态。

---

## 2. 增量 A：Agent 发布治理

### 2.1 数据模型

`king_ai_agent_version` 增加发布治理字段：

- `status`：`DRAFT`、`PENDING_REVIEW`、`APPROVED`、`CANARY`、`PUBLISHED`、`REJECTED`、`ARCHIVED`
- `requested_by/requested_at`、`approved_by/approved_at`、`review_comment`
- `canary_percent`（0–100）、`published_at`
- 评测结果：`eval_total`、`eval_passed`、`eval_score`、`eval_summary`、`eval_run_at`
- `config_hash`（SHA-256，防止快照被篡改）

新增 `king_ai_agent_eval_case`：空间、Agent、名称、问题、期望关键词（逗号分隔）、权重、启用状态。

### 2.2 状态机

```
DRAFT --submit--> PENDING_REVIEW --approve--> APPROVED --canary(p%)--> CANARY --promote--> PUBLISHED
                       |                        |                          |
                       +--reject--> REJECTED    +---------promote----------+
PUBLISHED --publish newer version--> ARCHIVED
```

规则：

- 提交人不能审批自己的发布（四眼原则）。
- 审批需要空间 `ADMIN`/`OWNER`。
- 存在启用的评测用例时，必须先完成评测且 `eval_score >= king-ai.release.eval-pass-score` 才能审批。
- 同一 Agent 同时只允许一个 `CANARY` 版本。
- 晋级会归档旧 `PUBLISHED` 版本，并更新 `agent.published_version`、`publish_status`。
- `release.require-approval=false` 时保留旧 `/publish` 直达行为（升级兼容）；生产默认 `true`。

### 2.3 灰度路由

公开对话按 `sha256(visitorId + ":" + agentId)` 取模 100，小于 `canary_percent` 时使用灰度快照，否则使用当前发布快照。灰度快照缺失时 fail-closed 回退到当前发布快照，避免公开流量 5xx。

### 2.4 评测

评测同步执行、限制用例数与输出 token，使用 Agent 快照中的模型/系统提示词调用一次对话模型，按期望关键词命中率评分：

```
score = Σ(weight * hitRatio) / Σ(weight)
```

结果写入版本行；失败/异常记录到 `eval_summary` 并禁止审批。

---

## 3. 增量 B：SSO/OIDC 与 MFA

### 3.1 数据模型

- `king_ai_user`：`mfa_enabled`、`mfa_secret`（AES-GCM 加密）、`mfa_recovery_codes`（SHA-256 哈希 JSON，加密存储）、`mfa_enrolled_at`。
- `king_ai_user_identity`：`(provider, subject)` 唯一，关联本地用户，用于 OIDC JIT 绑定。
- `king_ai_user` 增加 `password` 可为随机值的 SSO 用户；仍保留本地密码作为回退。

### 3.2 登录流程

1. `POST /api/auth/login` 校验密码。
2. 若用户启用 MFA：不创建会话，返回 `mfaRequired=true` 与 HMAC 签名的短期 challenge（5 分钟）。
3. `POST /api/auth/mfa/verify` 校验 TOTP 或恢复码，成功后签发会话。
4. 登录失败、MFA 失败都进入现有 `LoginAttemptGuard`。

MFA 管理（需已登录）：`POST /api/auth/mfa/setup`（返回 secret + otpauth URL）、`POST /api/auth/mfa/enable`、`POST /api/auth/mfa/disable`（需密码 + 验证码）。

### 3.3 OIDC

- `GET /api/auth/sso/config`：是否启用、登录地址。
- `GET /api/auth/oidc/authorize`：生成带 HMAC 签名 state 的授权 URL，302 到 IdP。
- `GET /api/auth/oidc/callback`：校验 state、用 code 换 token、校验 ID Token（RS256、iss、aud、exp、nbf）、JIT 绑定/创建用户、映射空间角色、签发会话 Cookie、重定向回前端。

角色映射：`role-claim`（默认 `roles`）中的每个值支持：

- `SPACE_CODE:ROLE`：加入对应空间并设置角色。
- `ROLE`：加入 `default-space-code` 并设置角色。

只提升不降级：已存在更高角色时保留更高角色。

---

## 4. 增量 C：OCR

- `OcrService` 端口 + `TesseractOcrService` 适配器：PDFBox 以 200 DPI 渲染页面，调用 `tesseract <png> stdout -l <langs> --psm 6`，逐页拼接；支持超时、页数上限、进程强制终止。
- 触发条件：PDF 文本层字符数 < `min-text-chars-per-page * pages`，或上传 `.png/.jpg/.jpeg`。
- OCR 失败时保留文本层结果；两者都为空则返回明确错误。
- 配置：`king-ai.ocr.enabled/command/languages/max-pages/timeout-seconds/min-text-chars-per-page`。
- Docker 镜像安装 `tesseract-ocr` 与中英文语言包；关闭时行为与当前一致。

---

## 5. 增量 D：MCP OAuth

- `king_ai_mcp` 增加：`auth_type`（NONE/OAUTH2）、`oauth_token_url`、`oauth_client_id`、`oauth_client_secret`（加密）、`oauth_scopes`、`oauth_access_token`（加密）、`oauth_refresh_token`（加密）、`oauth_expires_at`。
- `McpOAuthService`：优先使用未过期 access token；否则用 refresh token 续期；无 refresh token 时走 `client_credentials`。请求走 `OutboundUrlGuard`，token 加密落库。
- `McpClientFactory` 注入 `Authorization: Bearer`，并纳入客户端缓存指纹。
- 列表只返回 `authType`、`oauthTokenUrl`、`oauthClientId`、`oauthScopes`、`oauthConnected`；client secret 只写不读。
- 修改认证配置时清空已缓存 token。

---

## 6. 增量 E：air-gap 与 License

### 6.1 License

文件格式：`{"payload":"<base64url(json)>","signature":"<base64url(ed25519)>"}`。

Claims：`licenseId`、`customer`、`edition`、`issuedAt`、`expiresAt`、`maxSpaces`、`maxUsers`、`maxAgents`、`features`。

- `LicenseService` 用配置的 Ed25519 公钥验签，启动时 fail-closed（`enabled=true` 且无效则拒绝启动）。
- 创建空间/用户/Agent 前校验限额；`max* <= 0` 表示不限。
- `GET /api/license`（管理员）返回状态、有效期、限额与当前用量。

### 6.2 离线交付

`deploy/airgap/build-bundle.sh`：

1. 拉取/构建 API、UI 镜像并 `docker save`。
2. 打包 Helm chart、Compose 文件、迁移脚本。
3. 生成 `manifest.json`、`SHA256SUMS`、可选 CycloneDX SBOM。
4. 生成 `license/README.md` 与公钥模板。

`deploy/airgap/load-images.sh`：校验 checksum → `docker load` 或 `ctr images import` → 安装 Helm chart。

---

## 7. 配置与迁移

新增 Flyway：`V15__agent_release_governance.sql`、`V16__identity_mfa.sql`、`V17__mcp_oauth.sql`（H2/MySQL 各一份）。

新增配置前缀：

```yaml
king-ai:
  release:
    require-approval: true
    eval-pass-score: 0.8
    max-eval-cases: 20
  sso:
    enabled: false
    oidc: { issuer, client-id, client-secret, redirect-uri, scopes, role-claim, default-space-code, default-role, auto-create-users }
  mfa:
    enabled: true
    issuer: King AI Platform
    challenge-ttl: 5m
    recovery-code-count: 8
  ocr:
    enabled: false
    command: tesseract
    languages: chi_sim+eng
    max-pages: 30
    timeout-seconds: 120
    min-text-chars-per-page: 50
  license:
    enabled: false
    file: ""
    public-key: ""
```

## 8. 测试与验收

- 单元：状态机四眼/评测门禁、灰度桶稳定性、TOTP RFC 向量、OIDC 签名/过期/受众、License 篡改/过期、OCR 回退、MCP token 续期。
- 迁移：MySQL Testcontainers 执行 V1–V17 并验证新表/新列。
- 回归：后端全量测试、前端 lint/build、Compose/Helm 静态校验。
- 验收：公开版本变更均有审批与评测记录；扫描件可检索；MCP 可用 OAuth 连接；离线包可在无外网环境安装；License 过期/超限被拒绝。
