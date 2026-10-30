# King AI Helm Chart

## 前置条件
- Kubernetes 1.28+，Helm 3.15+
- 外部 MySQL 8.4 / Redis 7 / Milvus 2.5
- 默认使用 emptyDir 存导出文件，无需 StorageClass；启用 `persistence.exports.mode=pvc` 时才需要可用的 StorageClass
- 仅当 `autoscaling.targetActiveStreams` 非空时才需要 Prometheus Adapter

## 创建 Secret
```bash
kubectl create namespace king-ai
kubectl -n king-ai create secret generic king-ai-app \
  --from-literal=app-secret="$(openssl rand -hex 32)" \
  --from-literal=encryption-key="$(openssl rand -hex 32)" \
  --from-literal=previous-keys=""
kubectl -n king-ai create secret generic king-ai-admin \
  --from-literal=username=admin \
  --from-literal=password='<12-72 位字母+数字>'
kubectl -n king-ai create secret generic king-ai-db \
  --from-literal=username=king --from-literal=password='<db-password>'
kubectl -n king-ai create secret generic king-ai-redis \
  --from-literal=password='<redis-password>'
```

## 安装
```bash
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

## 升级与回滚
```bash
helm upgrade king-ai deploy/helm/king-ai -n king-ai -f prod-values.yaml --wait --timeout 15m
helm history king-ai -n king-ai
helm rollback king-ai <REVISION> -n king-ai --wait --timeout 15m
```

## 验证
```bash
kubectl -n king-ai get pods
kubectl -n king-ai logs job/king-ai-migrate
curl -fsS https://ai.example.com/api/health
```

## 开发集群
```bash
deploy/helm/king-ai/tests/kind-e2e.sh
```

## 迁移钩子链与 migration.enabled
全新安装时，pre-install/pre-upgrade 钩子按权重顺序执行：内置依赖的 dev
Secret（hook-weight `-30`）→ ServiceAccount（`-20`）→ `king-ai-migrate`
Job（`-10`），数据库 Schema 迁移先于 API 启动完成。
`migration.enabled` 语义：使用外部数据库时必须保持 `true`（由 migrate Job
执行 Flyway）；仅在内置依赖 dev/kind 模式下才允许设为 `false`——此时
API 容器在启动阶段自行运行 Flyway。

## CI 与本地校验分工
- CI 的 `deployment` job 强制执行：`render_test.sh`（含 kubeconform
  `-strict` schema 校验，kubeconform v0.6.7）与 ShellCheck
  （`deploy/helm/king-ai/tests/*.sh`，以及存在时的 `deploy/backup/` 脚本）。
- `tests/kind-e2e.sh` 是本地/可选演练（需要 kind、docker、kubectl、helm、
  curl 与镜像构建能力），不阻塞 CI；本机缺少 kubeconform 时
  render_test.sh 会大声跳过 schema 校验，由 CI 兜底强制。

## 自动扩缩容
默认 HPA 只按 CPU 扩缩容，可在任何集群安装。若集群已部署 Prometheus
Adapter 并暴露 `king_ai_chat_active`，设置
`autoscaling.targetActiveStreams=80` 追加按活跃流扩容的 Pods 指标。
未安装适配器时保持该值为空：不可用的自定义指标会让 HPA 无法计算副本数，
连 CPU 伸缩也会失效。该指标由应用内直接暴露（ChatRuntime 的 Gauge），
无需额外 exporter。

## TLS
设置 `ingress.enabled=true` 时默认引用 `ingress.tls.secretName`
（默认 `king-ai-tls`），由平台侧（cert-manager / sealed-secrets）创建。
如需 chart 托管证书，设置：

```bash
--set ingress.tls.create=true \
--set-file ingress.tls.crt=./tls.crt \
--set-file ingress.tls.key=./tls.key
```

## 限制
- SSE 流在 Pod 滚动升级时会被终止；断线恢复在 P2-2 实现。
- 导出目录始终挂载：默认 emptyDir，Pod 重建后导出文件丢失（导出 TTL 为 24 小时）。需要持久化时启用 `persistence.exports.mode=pvc`，多副本需 ReadWriteMany 的 StorageClass；只有 ReadWriteOnce 时请将 `api.replicaCount` 设为 1；或改用下文的对象存储。
- NetworkPolicy 的实际执行取决于 CNI：kind 默认 CNI 不执行 NetworkPolicy
  （仅渲染生效）；生产环境必须使用支持 NetworkPolicy 的 CNI
  （Calico/Cilium 等）并验证策略真实生效。

## 合规导出存储
- 默认 `api.export.storage=local`，导出文件写入 `persistence.exports` 对应的卷。
- 设为 `s3` 后改用任意 S3 兼容端点（MinIO / OSS / AWS S3），多副本无需共享卷。bucket 必须预先创建，凭据放在独立 Secret 中（缺失时渲染失败）：

```bash
kubectl create secret generic king-ai-export-s3 -n king-ai \
  --from-literal=access-key=... --from-literal=secret-key=...
helm upgrade king-ai deploy/helm/king-ai -n king-ai \
  --set api.export.storage=s3 \
  --set api.export.s3.endpoint=http://minio.internal:9000 \
  --set api.export.s3.bucket=king-ai-exports \
  --set api.export.s3.existingSecret=king-ai-export-s3
```

- 对象键形如 `<prefix>/<spaceId>/<uuid>.<ext>`，读写均被约束在空间前缀内；更换 `prefix` 后旧导出文件仍按原键读取，但新文件写入新前缀。
- 启用 `storage=s3` 时仍需为对象存储端点放行 NetworkPolicy 出站。

## 用量与 Token 配额
- `api.finops.quotaEnabled`（默认 `true`）控制空间月度 Token 配额用尽后是否阻断对话；`api.finops.currency` 与 `api.finops.defaultInputPricePerMillion` / `defaultOutputPricePerMillion` 决定账本币种与默认估算单价。
- 每个空间的配额存放在数据库列 `monthly_token_quota`，由空间 `OWNER` 通过 `PUT /api/finops/quota` 设置，调整配额不需要改 values 或重启。

## SSO、MFA、OCR 与发布治理

这些能力通过 `api.extraEnv` / `api.extraEnvFrom` 注入，避免把客户端密钥写进 ConfigMap：

```bash
kubectl create secret generic king-ai-sso -n king-ai \
  --from-literal=client-secret='<oidc-client-secret>'
```

```yaml
api:
  extraEnv:
    - name: KING_AI_SSO_ENABLED
      value: "true"
    - name: KING_AI_OIDC_ISSUER
      value: "https://idp.example.com"
    - name: KING_AI_OIDC_CLIENT_ID
      value: "king-ai"
    - name: KING_AI_OIDC_CLIENT_SECRET
      valueFrom:
        secretKeyRef:
          name: king-ai-sso
          key: client-secret
    - name: KING_AI_OIDC_REDIRECT_URI
      value: "https://ai.example.com/api/auth/oidc/callback"
    - name: KING_AI_MFA_ENABLED
      value: "true"
    - name: KING_AI_OCR_ENABLED
      value: "true"
    - name: KING_AI_RELEASE_REQUIRE_APPROVAL
      value: "true"
```

- 回调地址必须与 IdP 注册值完全一致；集群需能访问 issuer 的发现文档与 JWKS。
- API 镜像已包含 `tesseract` 与 `chi_sim`/`eng` 语言包；其他语言需构建自定义镜像。
- 发布审批、灰度与评测不依赖额外配置，未设置时使用默认值（审批开启、通过分 0.8）。

## 离线许可证

```bash
kubectl create secret generic king-ai-license -n king-ai \
  --from-file=license.json=./license.json
helm upgrade king-ai deploy/helm/king-ai -n king-ai \
  --set api.license.enabled=true \
  --set api.license.existingSecret=king-ai-license \
  --set-file api.license.publicKey=license-public-key.pem
```

- `api.license.mountPath` 默认 `/app/license`，Secret 键由 `api.license.secretKey` 指定（默认 `license.json`）。
- 启用后许可证缺失、签名无效或已过期都会让 API 启动失败；容量上限在创建空间/用户/智能体时校验。
- 状态可通过管理员接口 `GET /api/license` 查看。

## 备份与恢复
- API 的导出 PVC 路径为 `/app/data/exports`，对应 values `persistence.exports`；使用 `storage=s3` 时导出文件在对象存储中，应纳入对象存储的备份策略。
- 备份脚本不打包进 chart；使用 `deploy/backup` 与 `deploy/backup/README.md`。
- 启用 NetworkPolicy 时必须为数据库/Redis/Milvus/OTel/对象存储配置 CIDR；公共模型与 MCP 通过 443 放行。
- 迁移 Job（`<fullname>-migrate`）由独立的 NetworkPolicy 管控，出站仅放行 DNS 与数据库（含内置 MySQL pod 选择器）；除此之外无任何出站权限。
- 建议在 Calico/Cilium 集群验证 NetworkPolicy；kind 默认 CNI 不执行策略，kind values 会关闭它。

## 迁移
- 生产使用 `pre-install/pre-upgrade` Job；`kubectl logs job/king-ai-migrate` 查看结果。
- 内置依赖模式因 hook 运行时数据库尚未就绪，设置 `migration.enabled=false`，由 API 启动时迁移。

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
- 启用 NetworkPolicy 时，chart 默认放行 `monitoring` 命名空间中带
  `app.kubernetes.io/name=prometheus` 标签的 Pod 抓取 8080 端口；命名空间或
  标签不同请覆盖 `networkPolicy.metrics.namespace` / `networkPolicy.metrics.podLabels`，
  或设置 `networkPolicy.metrics.enabled=false` 自行管理。
- chart 不创建 ServiceMonitor/PodMonitor CRD，避免在未安装 operator 的集群中安装失败。
- 指标和 trace 标签不得包含用户、会话或消息标识；排障使用 requestId/traceId 日志关联。
