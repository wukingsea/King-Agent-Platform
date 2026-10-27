# King AI Helm Chart

## 前置条件
- Kubernetes 1.28+，Helm 3.15+，默认 StorageClass（或设置 `persistence.exports.storageClass`）
- 外部 MySQL 8.4 / Redis 7 / Milvus 2.5；Prometheus Adapter（启用 HPA 自定义指标时）

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
`autoscaling.enabled=true` 依赖 Prometheus Adapter 提供
`king_ai_chat_active` 自定义指标；未部署 Prometheus Adapter 时请保持
`autoscaling.enabled=false`。该指标由应用内直接暴露（ChatRuntime 的
Gauge），无需额外 exporter。

## 限制
- SSE 流在 Pod 滚动升级时会被终止；断线恢复在 P2-2 实现。
- 启用 `persistence.exports.mode=pvc` 时默认要求 ReadWriteMany；否则将 API 限制为单副本或等待 P2-3 对象存储。
- NetworkPolicy 的实际执行取决于 CNI：kind 默认 CNI 不执行 NetworkPolicy
  （仅渲染生效）；生产环境必须使用支持 NetworkPolicy 的 CNI
  （Calico/Cilium 等）并验证策略真实生效。

## 备份与恢复
- API 的导出 PVC 路径为 `/app/data/exports`，对应 values `persistence.exports`。
- 备份脚本不打包进 chart；使用 `deploy/backup` 与 `deploy/backup/README.md`。
- 启用 NetworkPolicy 时必须为数据库/Redis/Milvus/OTel/对象存储配置 CIDR；公共模型与 MCP 通过 443 放行。
- 迁移 Job（`<fullname>-migrate`）由独立的 NetworkPolicy 管控，出站仅放行 DNS 与数据库（含内置 MySQL pod 选择器）；除此之外无任何出站权限。
- 建议在 Calico/Cilium 集群验证 NetworkPolicy；kind 默认 CNI 不执行策略，kind values 会关闭它。

## 迁移
- 生产使用 `pre-install/pre-upgrade` Job；`kubectl logs job/king-ai-migrate` 查看结果。
- 内置依赖模式因 hook 运行时数据库尚未就绪，设置 `migration.enabled=false`，由 API 启动时迁移。
