# G0-5/G0-6 私有化交付 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可离线安装、可回滚、非 root 运行的 Helm/K8s 部署，以及可演练、可量化 RPO/RTO 的 MySQL + Milvus 备份恢复链路。

**Architecture:** 单个 Helm chart 默认对接外部 MySQL/Redis/Milvus，开发 values 可渲染最小内置依赖；Flyway 由独立迁移 Job 在部署前执行，应用 Pod 以只读根文件系统、非 root、探针和 PDB 运行；Ingress 为 SSE 关闭缓冲并启用 Cookie 亲和；备份采用 MySQL 全量 + 5 分钟 binlog 归档、Milvus collection 备份，恢复演练安装到隔离 namespace 并用 `verify.sh` 校验行数、向量数与 API 健康。

**Tech Stack:** Helm 3.15+、Kubernetes 1.28+、kubeconform 0.6.x、kind、MySQL 8.4、Redis 7、Milvus 2.5、Nginx Ingress、Bash 5、jq、AWS CLI/`mc`、Spring Boot Flyway、GitHub Actions。

**Spec:** `docs/superpowers/plans/2026-10-26-p2-scale-program.md` §4.2（G0-5、G0-6）。G0-1/G0-2、G0-3/G0-4 子计划与本计划相互独立；G0-3 的导出目录通过 `/app/data/exports` PVC 接入，未执行 G0-3 时该挂载仅预留。

## Global Constraints

- 当前工作区可能没有 `.git`。每个任务开始前若不存在版本库，先执行一次基线初始化：

```bash
if [ ! -d .git ]; then
  git init
  git add -A
  git commit -m "chore: baseline before G0-5/G0-6 delivery"
fi
```

- 所有命令在仓库根目录执行。Windows 使用 Git Bash/WSL；备份脚本只支持 Linux/macOS Bash。
- 不在 chart 默认渲染中创建任何 Secret。生产必须提供 `appSecret.existingSecret`、`adminSecret.existingSecret`、`externalDatabase.existingSecret`、`externalRedis.existingSecret`；名称缺失时 chart 通过 `fail` 在渲染阶段报错。
- 生产默认不打包 MySQL/Redis/Milvus；`devDependencies.enabled=true` 仅用于 kind/minikube/单机开发，绝不能用于生产。
- 所有应用 Pod：`runAsNonRoot: true`、`allowPrivilegeEscalation: false`、`readOnlyRootFilesystem: true`、`drop: ["ALL"]`、`seccompProfile: RuntimeDefault`。可写路径仅使用 emptyDir/PVC。
- API 镜像已使用非 root 用户 `king`；UI 镜像必须换成 `nginxinc/nginx-unprivileged` 并监听 8080。修改 UI 端口时同步 Docker Compose。
- SSE 必须经过 Ingress 的 `proxy-buffering: off`、`proxy-read-timeout: 600`、`proxy-send-timeout: 600` 与 Cookie 亲和；应用自身 `server.shutdown=graceful` 不变。
- 迁移 Job 是 Helm `pre-install,pre-upgrade` hook，失败时 release 不继续。内置依赖模式无法保证 hook 运行时数据库已就绪，因此 `values-kind.yaml` 设置 `migration.enabled=false`，由 API 启动时执行 Flyway；生产外部数据库必须启用 Job。
- HPA 自定义指标名固定为 `king_ai_chat_active`（Micrometer 将 `king.ai.chat.active` 转为下划线），需要 Prometheus Adapter；未安装 Adapter 时将 `autoscaling.enabled=false`。
- 备份凭据只从环境变量/Secret 读取，脚本不得把密码写入命令行参数、日志或 manifest；临时文件 `umask 077`。
- MySQL 备份用户最小权限：`SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES, PROCESS, REPLICATION CLIENT`；binlog 归档需要 `REPLICATION CLIENT` 或 `REPLICATION SLAVE`。
- RPO ≤ 15 分钟（全量每日 + binlog 每 5 分钟归档），RTO ≤ 1 小时（隔离 namespace 演练实测）。任何未达标项必须写入演练报告，不得用估算值代替。
- 备份保留 30 天；导出文件保留 24 小时（G0-3 默认）或对象存储 7 天生命周期。备份对象存储必须开启版本化。
- 所有 shell 脚本通过 `shellcheck`；所有 chart 渲染通过 `kubeconform -strict`。

---

### Task 1: Flyway 独立迁移模式

**Files:**
- Create: `king-ai-api/src/main/java/com/king/ai/config/MigrationRunner.java`
- Create: `king-ai-api/src/main/resources/application-migrate.yml`
- Modify: `king-ai-api/src/main/java/com/king/ai/config/AdminBootstrap.java`
- Modify: `king-ai-api/src/main/java/com/king/ai/config/LegacySecretMigrator.java`
- Test: `king-ai-api/src/test/java/com/king/ai/config/MigrationRunnerTest.java`

**Interfaces:**
- Produces: Spring profile `migrate`；设置 `SPRING_PROFILES_ACTIVE=prod,migrate` 后应用只跑 Flyway 并退出 0，不启动 Web 容器、不创建管理员、不执行定时任务。
- `MigrationRunner` 构造参数 `(Flyway flyway, ConfigurableApplicationContext context)`。

- [ ] **Step 1: 写失败测试**

```java
package com.king.ai.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MigrationRunnerTest {

    @Mock
    private Flyway flyway;
    @Mock
    private ConfigurableApplicationContext context;

    @Test
    void runsFlywayAndClosesContextWithSuccess() {
        try (MockedStatic<SpringApplication> application = mockStatic(SpringApplication.class)) {
            new MigrationRunner(flyway, context)
                    .run(new DefaultApplicationArguments(new String[]{}));

            verify(flyway).migrate();
            application.verify(() -> SpringApplication.exit(eq(context), any(ExitCodeGenerator.class)));
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -B -ntp -pl king-ai-api -am -Dtest=MigrationRunnerTest test`
Expected: 编译失败，`MigrationRunner` 不存在。

- [ ] **Step 3: 实现迁移模式**

`MigrationRunner.java`：

```java
package com.king.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs Flyway in a short-lived pod and exits before the web server or bootstrap
 * runners can create business state. Flyway auto-configuration has already run
 * once during context refresh; the explicit migrate() call is an idempotent
 * verification and makes a simultaneous hook failure impossible to ignore.
 */
@Slf4j
@Component
@Profile("migrate")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MigrationRunner implements ApplicationRunner {

    private final Flyway flyway;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        var result = flyway.migrate();
        if (result != null) {
            log.info("Flyway migrate-only finished: {} migration(s) executed, schema {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
        SpringApplication.exit(context, () -> 0);
    }
}
```

`application-migrate.yml`：

```yaml
spring:
  main:
    web-application-type: none
  task:
    scheduling:
      enabled: false

king-ai:
  demo:
    enabled: false
```

在 `AdminBootstrap` 和 `LegacySecretMigrator` 的 `@Component` 旁增加：

```java
import org.springframework.context.annotation.Profile;

@Profile("!migrate")
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -B -ntp -pl king-ai-api -am -Dtest=MigrationRunnerTest test`
Expected: `BUILD SUCCESS`，1 个测试通过。

- [ ] **Step 5: 用 H2 手工验证迁移模式不启动 Web**

Run:

```bash
mvn -q -pl king-ai-api -am package -DskipTests
java -jar king-ai-api/target/king-ai-api.jar \
  --spring.profiles.active=local,migrate \
  --spring.datasource.url=jdbc:h2:mem:migratecheck \
  --king-ai.app-secret=local-migrate-check-secret-0123456789abcdef
echo "exit=$?"
```

Expected: 输出包含 `Flyway migrate-only finished`，随后进程退出，`exit=0`，没有 Tomcat 端口监听日志。

- [ ] **Step 6: 提交**

```bash
git add king-ai-api/src/main/java/com/king/ai/config/MigrationRunner.java \
  king-ai-api/src/main/resources/application-migrate.yml \
  king-ai-api/src/main/java/com/king/ai/config/AdminBootstrap.java \
  king-ai-api/src/main/java/com/king/ai/config/LegacySecretMigrator.java \
  king-ai-api/src/test/java/com/king/ai/config/MigrationRunnerTest.java
git commit -m "feat: add flyway migrate-only profile for kubernetes jobs"
```

---

### Task 2: Helm chart 骨架、values 与配置

**Files:**
- Create: `deploy/helm/king-ai/Chart.yaml`
- Create: `deploy/helm/king-ai/values.yaml`
- Create: `deploy/helm/king-ai/values.schema.json`
- Create: `deploy/helm/king-ai/templates/_helpers.tpl`
- Create: `deploy/helm/king-ai/templates/configmap.yaml`
- Create: `deploy/helm/king-ai/templates/serviceaccount.yaml`
- Create: `deploy/helm/king-ai/templates/service-api.yaml`
- Create: `deploy/helm/king-ai/templates/service-ui.yaml`
- Create: `deploy/helm/king-ai/templates/NOTES.txt`

**Interfaces:**
- Produces helpers：`king-ai.fullname`、`king-ai.labels`、`king-ai.selectorLabels`、`king-ai.serviceAccountName`、`king-ai.dbHost`、`king-ai.redisHost`、`king-ai.milvusUrl`、`king-ai.dbSecretName`、`king-ai.redisSecretName`、`king-ai.appSecretName`、`king-ai.adminSecretName`、`king-ai.dbEnv`、`king-ai.redisEnv`、`king-ai.milvusEnv`、`king-ai.appSecretEnv`、`king-ai.adminEnv`。
- 资源名：API Service `{{ fullname }}-api:8080`，UI Service `{{ fullname }}-ui:8080`，迁移 Job `{{ fullname }}-migrate`。

- [ ] **Step 1: 创建 Chart 元数据与 values**

`Chart.yaml`：

```yaml
apiVersion: v2
name: king-ai
description: King AI Platform - API, UI and optional development dependencies
type: application
version: 0.1.0
appVersion: "1.0.0"
kubeVersion: ">=1.28.0-0"
```

`values.yaml`：

```yaml
nameOverride: ""
fullnameOverride: ""

imagePullSecrets: []

api:
  image:
    repository: king-ai-api
    tag: "1.0.0"
    pullPolicy: IfNotPresent
  replicaCount: 2
  allowedOrigins: ""
  javaOpts: "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: "2"
      memory: 2Gi
  podAnnotations: {}
  nodeSelector: {}
  tolerations: []
  affinity: {}
  terminationGracePeriodSeconds: 120

ui:
  image:
    repository: king-ai-ui
    tag: "1.0.0"
    pullPolicy: IfNotPresent
  replicaCount: 2
  resources:
    requests:
      cpu: 50m
      memory: 64Mi
    limits:
      cpu: 500m
      memory: 256Mi
  podAnnotations: {}
  nodeSelector: {}
  tolerations: []
  affinity: {}
  terminationGracePeriodSeconds: 30

serviceAccount:
  create: true
  annotations: {}
  name: ""

podSecurityContext:
  runAsNonRoot: true
  fsGroup: 10001
  seccompProfile:
    type: RuntimeDefault

securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop:
      - ALL

externalDatabase:
  host: mysql.example.internal
  port: 3306
  database: king_ai
  existingSecret: king-ai-db
  usernameKey: username
  passwordKey: password
  sslMode: REQUIRED

externalRedis:
  host: redis.example.internal
  port: 6379
  existingSecret: king-ai-redis
  passwordKey: password
  sslEnabled: true

externalMilvus:
  url: http://milvus.example.internal:19530
  existingSecret: ""
  tokenKey: token

appSecret:
  existingSecret: king-ai-app
  secretKey: app-secret
  encryptionKeyKey: encryption-key
  previousKeysKey: previous-keys

adminSecret:
  existingSecret: king-ai-admin
  usernameKey: username
  passwordKey: password

ingress:
  enabled: false
  className: nginx
  host: ai.example.com
  pathType: Prefix
  annotations: {}
  tls:
    enabled: true
    secretName: king-ai-tls
  sse:
    proxyReadTimeout: 600
    proxySendTimeout: 600
    bodySize: 25m

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
  targetActiveStreams: 80

pdb:
  enabled: true
  minAvailable: 1

networkPolicy:
  enabled: true
  ingressNamespace: ingress-nginx
  ingressPodLabels:
    app.kubernetes.io/name: ingress-nginx
  dnsNamespace: kube-system
  egress:
    databaseCidrs: []
    redisCidrs: []
    milvusCidrs: []
    otelCidrs: []
    objectStorageCidrs: []
    publicHttps: true
    otelPort: 4317

persistence:
  exports:
    enabled: true
    mode: pvc
    size: 20Gi
    storageClass: ""
    accessMode: ReadWriteMany

migration:
  enabled: true
  backoffLimit: 0
  activeDeadlineSeconds: 600
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: "1"
      memory: 1Gi

devDependencies:
  enabled: false
  persistence:
    enabled: true
    storageClass: ""
    size: 8Gi
  mysql:
    enabled: true
    image: mysql:8.4
  redis:
    enabled: true
    image: redis:7-alpine
  milvus:
    enabled: true
    image: milvusdb/milvus:v2.5.4
    etcdImage: quay.io/coreos/etcd:v3.5.18
    minioImage: minio/minio:RELEASE.2024-12-18T13-15-44Z
  secrets:
    appSecret: ""
    encryptionKey: ""
    adminUsername: admin
    adminPassword: ""
    mysqlPassword: ""
    redisPassword: ""
    minioUser: ""
    minioPassword: ""
```

- [ ] **Step 2: 创建 JSON Schema**

`values.schema.json`：

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["api", "ui", "externalDatabase", "externalRedis", "externalMilvus", "appSecret", "adminSecret"],
  "properties": {
    "api": {
      "type": "object",
      "required": ["image", "replicaCount"],
      "properties": {
        "image": {
          "type": "object",
          "required": ["repository", "tag"],
          "properties": {
            "repository": {"type": "string", "minLength": 1},
            "tag": {"type": "string", "minLength": 1},
            "pullPolicy": {"enum": ["Always", "IfNotPresent", "Never"]}
          }
        },
        "replicaCount": {"type": "integer", "minimum": 0}
      }
    },
    "ui": {
      "type": "object",
      "required": ["image", "replicaCount"],
      "properties": {
        "image": {
          "type": "object",
          "required": ["repository", "tag"],
          "properties": {
            "repository": {"type": "string", "minLength": 1},
            "tag": {"type": "string", "minLength": 1},
            "pullPolicy": {"enum": ["Always", "IfNotPresent", "Never"]}
          }
        },
        "replicaCount": {"type": "integer", "minimum": 0}
      }
    },
    "externalDatabase": {
      "type": "object",
      "required": ["host", "port", "database", "existingSecret"],
      "properties": {
        "host": {"type": "string", "minLength": 1},
        "port": {"type": "integer", "minimum": 1, "maximum": 65535},
        "database": {"type": "string", "minLength": 1},
        "existingSecret": {"type": "string", "minLength": 1}
      }
    },
    "externalRedis": {
      "type": "object",
      "required": ["host", "port", "existingSecret"],
      "properties": {
        "host": {"type": "string", "minLength": 1},
        "port": {"type": "integer", "minimum": 1, "maximum": 65535},
        "existingSecret": {"type": "string", "minLength": 1}
      }
    },
    "externalMilvus": {
      "type": "object",
      "required": ["url"],
      "properties": {"url": {"type": "string", "minLength": 1}}
    },
    "appSecret": {
      "type": "object",
      "required": ["existingSecret"],
      "properties": {"existingSecret": {"type": "string", "minLength": 1}}
    },
    "adminSecret": {
      "type": "object",
      "required": ["existingSecret"],
      "properties": {"existingSecret": {"type": "string", "minLength": 1}}
    },
    "ingress": {
      "type": "object",
      "properties": {
        "enabled": {"type": "boolean"},
        "host": {"type": "string", "minLength": 1}
      }
    },
    "persistence": {"type": "object"},
    "devDependencies": {
      "type": "object",
      "required": ["enabled"],
      "properties": {"enabled": {"type": "boolean"}},
      "if": {"properties": {"enabled": {"const": true}}},
      "then": {
        "required": ["secrets"],
        "properties": {
          "secrets": {
            "type": "object",
            "required": ["appSecret", "encryptionKey", "adminPassword", "mysqlPassword", "redisPassword"],
            "properties": {
              "appSecret": {"type": "string", "minLength": 32},
              "encryptionKey": {"type": "string", "minLength": 32},
              "adminPassword": {"type": "string", "minLength": 12},
              "mysqlPassword": {"type": "string", "minLength": 8},
              "redisPassword": {"type": "string", "minLength": 8}
            }
          }
        }
      }
    }
  }
}
```

- [ ] **Step 3: 创建 helpers**

`templates/_helpers.tpl`：

```gotemplate
{{- define "king-ai.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "king-ai.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "king-ai.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "king-ai.selectorLabels" -}}
app.kubernetes.io/name: {{ include "king-ai.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "king-ai.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "king-ai.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.dbSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-db" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "externalDatabase.existingSecret is required" .Values.externalDatabase.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.redisSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-redis" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "externalRedis.existingSecret is required" .Values.externalRedis.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.appSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-app" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "appSecret.existingSecret is required" .Values.appSecret.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.adminSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-admin" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "adminSecret.existingSecret is required" .Values.adminSecret.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.dbHost" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.mysql.enabled -}}
{{- printf "%s-mysql" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "externalDatabase.host is required" .Values.externalDatabase.host -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.redisHost" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.redis.enabled -}}
{{- printf "%s-redis" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "externalRedis.host is required" .Values.externalRedis.host -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.milvusUrl" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.milvus.enabled -}}
{{- printf "http://%s-milvus:19530" (include "king-ai.fullname" .) -}}
{{- else -}}
{{- required "externalMilvus.url is required" .Values.externalMilvus.url -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.dbEnv" -}}
- name: MYSQL_HOST
  value: {{ include "king-ai.dbHost" . | quote }}
- name: MYSQL_PORT
  value: {{ .Values.externalDatabase.port | quote }}
- name: MYSQL_DATABASE
  value: {{ .Values.externalDatabase.database | quote }}
- name: MYSQL_USER
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.dbSecretName" . }}
      key: {{ .Values.externalDatabase.usernameKey }}
- name: MYSQL_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.dbSecretName" . }}
      key: {{ .Values.externalDatabase.passwordKey }}
- name: MYSQL_SSL_MODE
  value: {{ .Values.externalDatabase.sslMode | quote }}
{{- end -}}

{{- define "king-ai.redisEnv" -}}
- name: REDIS_HOST
  value: {{ include "king-ai.redisHost" . | quote }}
- name: REDIS_PORT
  value: {{ .Values.externalRedis.port | quote }}
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.redisSecretName" . }}
      key: {{ .Values.externalRedis.passwordKey }}
- name: REDIS_SSL_ENABLED
  value: {{ .Values.externalRedis.sslEnabled | quote }}
{{- end -}}

{{- define "king-ai.milvusEnv" -}}
- name: MILVUS_URL
  value: {{ include "king-ai.milvusUrl" . | quote }}
{{- if .Values.externalMilvus.existingSecret }}
- name: MILVUS_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .Values.externalMilvus.existingSecret }}
      key: {{ .Values.externalMilvus.tokenKey }}
{{- end }}
{{- end -}}

{{- define "king-ai.appSecretEnv" -}}
- name: KING_AI_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.appSecretName" . }}
      key: {{ .Values.appSecret.secretKey }}
- name: KING_AI_ENCRYPTION_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.appSecretName" . }}
      key: {{ .Values.appSecret.encryptionKeyKey }}
{{- if .Values.appSecret.previousKeysKey }}
- name: KING_AI_PREVIOUS_ENCRYPTION_KEYS
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.appSecretName" . }}
      key: {{ .Values.appSecret.previousKeysKey }}
      optional: true
{{- end }}
{{- end -}}

{{- define "king-ai.adminEnv" -}}
- name: KING_AI_ADMIN_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.adminSecretName" . }}
      key: {{ .Values.adminSecret.usernameKey }}
- name: KING_AI_ADMIN_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "king-ai.adminSecretName" . }}
      key: {{ .Values.adminSecret.passwordKey }}
{{- end -}}
```

- [ ] **Step 4: 创建 ConfigMap、ServiceAccount、Service 与 NOTES**

`templates/configmap.yaml`：

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "king-ai.fullname" . }}
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
data:
  KING_AI_ALLOWED_ORIGINS: {{ .Values.api.allowedOrigins | default (ternary (printf "https://%s" .Values.ingress.host) "" .Values.ingress.enabled) | quote }}
  KING_AI_TRUST_FORWARDED_HEADERS: "true"
  KING_AI_COOKIE_SECURE: "true"
  KING_AI_BLOCK_PRIVATE_NETWORKS: "true"
  KING_AI_DEMO_ENABLED: "false"
  KING_AI_COMPLIANCE_EXPORT_ROOT_DIR: "/app/data/exports"
  LOG_STRUCTURED_FORMAT: "ecs"
  MANAGEMENT_EXPOSURE: "health,info,prometheus"
  JAVA_OPTS: {{ .Values.api.javaOpts | quote }}
```

`templates/serviceaccount.yaml`：

```yaml
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "king-ai.serviceAccountName" . }}
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: false
{{- end }}
```

`templates/service-api.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-api
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  type: ClusterIP
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: api
  ports:
    - name: http
      port: 8080
      targetPort: http
```

`templates/service-ui.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-ui
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: ui
spec:
  type: ClusterIP
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: ui
  ports:
    - name: http
      port: 8080
      targetPort: http
```

`templates/NOTES.txt`：

```text
King AI Platform {{ .Chart.AppVersion }} installed as release {{ .Release.Name }}.

{{- if .Values.ingress.enabled }}
URL: {{ ternary "https" "http" .Values.ingress.tls.enabled }}://{{ .Values.ingress.host }}
{{- else }}
API: kubectl -n {{ .Release.Namespace }} port-forward svc/{{ include "king-ai.fullname" . }}-api 8080:8080
UI:  kubectl -n {{ .Release.Namespace }} port-forward svc/{{ include "king-ai.fullname" . }}-ui 8081:8080
{{- end }}

Required secrets:
  app:      {{ include "king-ai.appSecretName" . }} ({{ .Values.appSecret.secretKey }}, {{ .Values.appSecret.encryptionKeyKey }})
  admin:    {{ include "king-ai.adminSecretName" . }}
  database: {{ include "king-ai.dbSecretName" . }}
  redis:    {{ include "king-ai.redisSecretName" . }}

Migration job enabled: {{ .Values.migration.enabled }}
Network policy enabled: {{ .Values.networkPolicy.enabled }} (configure egress CIDRs for external dependencies)
Exports PVC mode: {{ .Values.persistence.exports.mode }}
```

- [ ] **Step 5: 验证渲染与 schema**

Run:

```bash
helm lint deploy/helm/king-ai --strict
helm template king-ai deploy/helm/king-ai --namespace king-ai > /tmp/king-ai-render.yaml
grep -q 'kind: ServiceAccount' /tmp/king-ai-render.yaml
grep -q 'name: king-ai-api' /tmp/king-ai-render.yaml
grep -q 'name: king-ai-ui' /tmp/king-ai-render.yaml
```

Expected: `1 chart(s) linted, 0 chart(s) failed`，三条 `grep` 均成功。

- [ ] **Step 6: 提交**

```bash
git add deploy/helm/king-ai/Chart.yaml deploy/helm/king-ai/values.yaml \
  deploy/helm/king-ai/values.schema.json deploy/helm/king-ai/templates/_helpers.tpl \
  deploy/helm/king-ai/templates/configmap.yaml deploy/helm/king-ai/templates/serviceaccount.yaml \
  deploy/helm/king-ai/templates/service-api.yaml deploy/helm/king-ai/templates/service-ui.yaml \
  deploy/helm/king-ai/templates/NOTES.txt
git commit -m "feat: add helm chart skeleton and external dependency wiring"
```

---

### Task 3: 非 root API/UI 工作负载与 UI 镜像加固

**Files:**
- Create: `deploy/helm/king-ai/templates/deployment-api.yaml`
- Create: `deploy/helm/king-ai/templates/deployment-ui.yaml`
- Modify: `king-ai-ui/Dockerfile`
- Modify: `king-ai-ui/nginx.conf`
- Modify: `docker-compose.yml`
- Test: `deploy/helm/king-ai/tests/render_test.sh`（本任务先建立最小版本，Task 7 扩展）

**Interfaces:**
- API 容器端口名 `http`，8080；UI 端口名 `http`，8080。
- UI 环境变量 `API_UPSTREAM` 默认 `api:8080`（Compose）或 `{{ fullname }}-api:8080`（Helm）。
- UI 可写 emptyDir：`/etc/nginx/conf.d`、`/tmp`、`/var/cache/nginx`。
- API 可写 emptyDir：`/tmp`；导出 PVC `exports` 挂载 `/app/data/exports`。

- [ ] **Step 1: 写最小渲染测试**

`deploy/helm/king-ai/tests/render_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v helm >/dev/null || { echo "helm is required" >&2; exit 127; }

helm lint "$CHART_DIR" --strict
helm template king-ai "$CHART_DIR" --namespace king-ai > "$TMP/default.yaml"

assert_contains() {
  grep -q -- "$2" "$1" || { echo "ASSERT FAILED: $1 missing $2" >&2; exit 1; }
}

assert_contains "$TMP/default.yaml" 'kind: Deployment'
assert_contains "$TMP/default.yaml" 'readOnlyRootFilesystem: true'
assert_contains "$TMP/default.yaml" 'runAsNonRoot: true'
assert_contains "$TMP/default.yaml" '/actuator/health/liveness'
assert_contains "$TMP/default.yaml" '/actuator/health/readiness'
assert_contains "$TMP/default.yaml" 'name: API_UPSTREAM'

echo "render tests passed"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `chmod +x deploy/helm/king-ai/tests/render_test.sh && deploy/helm/king-ai/tests/render_test.sh`
Expected: `ASSERT FAILED ... missing kind: Deployment`。

- [ ] **Step 3: 创建 API Deployment**

`templates/deployment-api.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-api
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.api.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: api
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: api
      annotations:
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
        {{- with .Values.api.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      serviceAccountName: {{ include "king-ai.serviceAccountName" . }}
      automountServiceAccountToken: false
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      terminationGracePeriodSeconds: {{ .Values.api.terminationGracePeriodSeconds }}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.api.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.api.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.api.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: api
          image: "{{ .Values.api.image.repository }}:{{ .Values.api.image.tag }}"
          imagePullPolicy: {{ .Values.api.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "king-ai.fullname" . }}
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: prod
            {{- include "king-ai.dbEnv" . | nindent 12 }}
            {{- include "king-ai.redisEnv" . | nindent 12 }}
            {{- include "king-ai.milvusEnv" . | nindent 12 }}
            {{- include "king-ai.appSecretEnv" . | nindent 12 }}
            {{- include "king-ai.adminEnv" . | nindent 12 }}
          startupProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
            failureThreshold: 36
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 10
            failureThreshold: 6
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
            failureThreshold: 6
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
          resources:
            {{- toYaml .Values.api.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            {{- if .Values.persistence.exports.enabled }}
            - name: exports
              mountPath: /app/data/exports
            {{- end }}
      volumes:
        - name: tmp
          emptyDir: {}
        {{- if .Values.persistence.exports.enabled }}
        - name: exports
          {{- if eq .Values.persistence.exports.mode "pvc" }}
          persistentVolumeClaim:
            claimName: {{ include "king-ai.fullname" . }}-exports
          {{- else }}
          emptyDir: {}
          {{- end }}
        {{- end }}
```

- [ ] **Step 4: 创建 UI Deployment 并加固 UI 镜像**

`templates/deployment-ui.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-ui
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: ui
spec:
  replicas: {{ .Values.ui.replicaCount }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: ui
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: ui
      {{- with .Values.ui.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "king-ai.serviceAccountName" . }}
      automountServiceAccountToken: false
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      terminationGracePeriodSeconds: {{ .Values.ui.terminationGracePeriodSeconds }}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.ui.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.ui.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.ui.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: ui
          image: "{{ .Values.ui.image.repository }}:{{ .Values.ui.image.tag }}"
          imagePullPolicy: {{ .Values.ui.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: API_UPSTREAM
              value: "{{ include "king-ai.fullname" . }}-api:8080"
          readinessProbe:
            httpGet:
              path: /
              port: http
            periodSeconds: 5
            failureThreshold: 6
          livenessProbe:
            httpGet:
              path: /
              port: http
            periodSeconds: 10
            failureThreshold: 6
          resources:
            {{- toYaml .Values.ui.resources | nindent 12 }}
          volumeMounts:
            - name: nginx-conf
              mountPath: /etc/nginx/conf.d
            - name: nginx-cache
              mountPath: /var/cache/nginx
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: nginx-conf
          emptyDir: {}
        - name: nginx-cache
          emptyDir: {}
        - name: tmp
          emptyDir: {}
```

`king-ai-ui/Dockerfile` 改为：

```dockerfile
FROM node:24-alpine AS build
WORKDIR /app

COPY king-ai-ui/package.json king-ai-ui/package-lock.json ./
RUN npm ci

COPY king-ai-ui/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY king-ai-ui/nginx.conf /etc/nginx/templates/default.conf.template

COPY --from=build /app/dist /usr/share/nginx/html

ENV API_UPSTREAM=api:8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
```

`king-ai-ui/nginx.conf` 只改两处：`listen 80;` 改为 `listen 8080;`，`proxy_pass http://api:8080;` 改为 `proxy_pass http://${API_UPSTREAM};`。

`docker-compose.yml` 的 `ui` 服务增加环境变量并改端口映射：

```yaml
  ui:
    build:
      context: .
      dockerfile: king-ai-ui/Dockerfile
    restart: unless-stopped
    environment:
      API_UPSTREAM: api:8080
    depends_on:
      api:
        condition: service_healthy
    ports:
      - "${UI_PORT:-80}:8080"
```

- [ ] **Step 5: 运行渲染测试**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: `render tests passed`。

再验证 UI 镜像：

```bash
docker build -f king-ai-ui/Dockerfile -t king-ai-ui:local .
docker run --rm -d --name king-ai-ui-check -p 18081:8080 king-ai-ui:local
sleep 3
curl -fsS http://127.0.0.1:18081/ | grep -q '<div id="app">'
docker rm -f king-ai-ui-check
```

Expected: HTML 包含 `<div id="app">`，容器以非 root 用户运行（`docker exec king-ai-ui-check id -u` 输出非 `0`）。

- [ ] **Step 6: 提交**

```bash
git add deploy/helm/king-ai/templates/deployment-api.yaml \
  deploy/helm/king-ai/templates/deployment-ui.yaml \
  deploy/helm/king-ai/tests/render_test.sh \
  king-ai-ui/Dockerfile king-ai-ui/nginx.conf docker-compose.yml
git commit -m "feat: harden api and ui workloads for kubernetes"
```

---

### Task 4: 迁移 Job、导出 PVC 与部署顺序

**Files:**
- Create: `deploy/helm/king-ai/templates/migration-job.yaml`
- Create: `deploy/helm/king-ai/templates/pvc-exports.yaml`
- Test: extend `deploy/helm/king-ai/tests/render_test.sh`

**Interfaces:**
- Job 名称 `{{ fullname }}-migrate`，hook `pre-install,pre-upgrade`，权重 `-10`，`hook-delete-policy: before-hook-creation`。
- Job 环境：`SPRING_PROFILES_ACTIVE=prod,migrate`、数据库凭据、`KING_AI_SECRET`、`KING_AI_ENCRYPTION_KEY`；不注入管理员凭据。
- PVC 名称 `{{ fullname }}-exports`，访问模式默认 `ReadWriteMany`。

- [ ] **Step 1: 扩展渲染测试**

在 `render_test.sh` 的 `echo "render tests passed"` 前加入：

```bash
assert_contains "$TMP/default.yaml" 'helm.sh/hook: pre-install,pre-upgrade'
assert_contains "$TMP/default.yaml" 'value: prod,migrate'
assert_contains "$TMP/default.yaml" 'kind: PersistentVolumeClaim'
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: `ASSERT FAILED ... missing helm.sh/hook`。

- [ ] **Step 3: 创建迁移 Job**

`templates/migration-job.yaml`：

```yaml
{{- if .Values.migration.enabled }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "king-ai.fullname" . }}-migrate
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: migration
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation
spec:
  backoffLimit: {{ .Values.migration.backoffLimit }}
  activeDeadlineSeconds: {{ .Values.migration.activeDeadlineSeconds }}
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: migration
    spec:
      restartPolicy: Never
      serviceAccountName: {{ include "king-ai.serviceAccountName" . }}
      automountServiceAccountToken: false
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: migrate
          image: "{{ .Values.api.image.repository }}:{{ .Values.api.image.tag }}"
          imagePullPolicy: {{ .Values.api.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          envFrom:
            - configMapRef:
                name: {{ include "king-ai.fullname" . }}
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: prod,migrate
            {{- include "king-ai.dbEnv" . | nindent 12 }}
            {{- include "king-ai.appSecretEnv" . | nindent 12 }}
          resources:
            {{- toYaml .Values.migration.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
{{- end }}
```

- [ ] **Step 4: 创建导出 PVC**

`templates/pvc-exports.yaml`：

```yaml
{{- if and .Values.persistence.exports.enabled (eq .Values.persistence.exports.mode "pvc") }}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ include "king-ai.fullname" . }}-exports
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  accessModes:
    - {{ .Values.persistence.exports.accessMode }}
  {{- if .Values.persistence.exports.storageClass }}
  storageClassName: {{ .Values.persistence.exports.storageClass | quote }}
  {{- end }}
  resources:
    requests:
      storage: {{ .Values.persistence.exports.size }}
{{- end }}
```

- [ ] **Step 5: 验证 hook 与渲染**

Run:

```bash
deploy/helm/king-ai/tests/render_test.sh
if helm template king-ai deploy/helm/king-ai --namespace king-ai \
  --set migration.enabled=false \
  --set persistence.exports.mode=emptyDir \
  | grep -E 'kind: (Job|PersistentVolumeClaim)'; then
  echo "ASSERT FAILED: migration Job or exports PVC rendered while disabled" >&2
  exit 1
fi
```

Expected: 测试输出 `render tests passed`；第二次检查无输出且成功结束（Job 和 PVC 均未渲染）。

- [ ] **Step 6: 提交**

```bash
git add deploy/helm/king-ai/templates/migration-job.yaml \
  deploy/helm/king-ai/templates/pvc-exports.yaml \
  deploy/helm/king-ai/tests/render_test.sh
git commit -m "feat: add flyway pre-deploy job and export pvc"
```

---

### Task 5: Ingress、HPA、PDB 与 NetworkPolicy

**Files:**
- Create: `deploy/helm/king-ai/templates/ingress.yaml`
- Create: `deploy/helm/king-ai/templates/hpa.yaml`
- Create: `deploy/helm/king-ai/templates/pdb.yaml`
- Create: `deploy/helm/king-ai/templates/networkpolicy.yaml`
- Test: extend `deploy/helm/king-ai/tests/render_test.sh`

**Interfaces:**
- Ingress 路由 `/api` → API Service 8080，`/` → UI Service 8080；SSE 注解固定 600s 与 `off`。
- HPA 目标 `Deployment/{{ fullname }}-api`，指标 `cpu` 与 `king_ai_chat_active`。
- PDB 仅选择 API Pod，`minAvailable` 来自 values。
- NetworkPolicy 默认拒绝 API/UI 全部出入，再逐项放行 DNS、Ingress、数据库、Redis、Milvus、OTel、对象存储、公共 443。

- [ ] **Step 1: 扩展渲染测试**

在 `render_test.sh` 中加入：

```bash
assert_contains "$TMP/default.yaml" 'kind: HorizontalPodAutoscaler'
assert_contains "$TMP/default.yaml" 'king_ai_chat_active'
assert_contains "$TMP/default.yaml" 'kind: PodDisruptionBudget'
assert_contains "$TMP/default.yaml" 'kind: NetworkPolicy'
assert_contains "$TMP/default.yaml" 'policyTypes:'
```

再生成 ingress 渲染：

```bash
helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set ingress.enabled=true --set ingress.host=ai.example.com > "$TMP/ingress.yaml"
assert_contains "$TMP/ingress.yaml" 'nginx.ingress.kubernetes.io/proxy-buffering: "off"'
assert_contains "$TMP/ingress.yaml" 'nginx.ingress.kubernetes.io/proxy-read-timeout: "600"'
assert_contains "$TMP/ingress.yaml" 'nginx.ingress.kubernetes.io/affinity: "cookie"'
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: 缺少 HPA 或 Ingress 注解。

- [ ] **Step 3: 创建 Ingress**

`templates/ingress.yaml`：

```yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "king-ai.fullname" . }}
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
  annotations:
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    nginx.ingress.kubernetes.io/proxy-read-timeout: {{ .Values.ingress.sse.proxyReadTimeout | quote }}
    nginx.ingress.kubernetes.io/proxy-send-timeout: {{ .Values.ingress.sse.proxySendTimeout | quote }}
    nginx.ingress.kubernetes.io/proxy-body-size: {{ .Values.ingress.sse.bodySize | quote }}
    nginx.ingress.kubernetes.io/affinity: "cookie"
    nginx.ingress.kubernetes.io/session-cookie-name: "king-ai-affinity"
    nginx.ingress.kubernetes.io/session-cookie-path: "/"
    nginx.ingress.kubernetes.io/session-cookie-samesite: "Lax"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    {{- with .Values.ingress.annotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  {{- if .Values.ingress.tls.enabled }}
  tls:
    - hosts:
        - {{ .Values.ingress.host | quote }}
      secretName: {{ .Values.ingress.tls.secretName | quote }}
  {{- end }}
  rules:
    - host: {{ .Values.ingress.host | quote }}
      http:
        paths:
          - path: /api
            pathType: {{ .Values.ingress.pathType }}
            backend:
              service:
                name: {{ include "king-ai.fullname" . }}-api
                port:
                  number: 8080
          - path: /
            pathType: {{ .Values.ingress.pathType }}
            backend:
              service:
                name: {{ include "king-ai.fullname" . }}-ui
                port:
                  number: 8080
{{- end }}
```

- [ ] **Step 4: 创建 HPA 与 PDB**

`templates/hpa.yaml`：

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "king-ai.fullname" . }}-api
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "king-ai.fullname" . }}-api
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Pods
      pods:
        metric:
          name: king_ai_chat_active
        target:
          type: AverageValue
          averageValue: {{ .Values.autoscaling.targetActiveStreams | quote }}
{{- end }}
```

`templates/pdb.yaml`：

```yaml
{{- if .Values.pdb.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "king-ai.fullname" . }}-api
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: api
{{- end }}
```

- [ ] **Step 5: 创建 NetworkPolicy**

`templates/networkpolicy.yaml`：

```yaml
{{- if .Values.networkPolicy.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "king-ai.fullname" . }}-default-deny
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
spec:
  podSelector:
    matchExpressions:
      - key: app.kubernetes.io/name
        operator: In
        values:
          - {{ include "king-ai.name" . }}
      - key: app.kubernetes.io/instance
        operator: In
        values:
          - {{ .Release.Name }}
      - key: app.kubernetes.io/component
        operator: In
        values:
          - api
          - ui
  policyTypes:
    - Ingress
    - Egress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "king-ai.fullname" . }}-api
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: api
spec:
  podSelector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: api
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ .Values.networkPolicy.ingressNamespace }}
          podSelector:
            matchLabels:
              {{- toYaml .Values.networkPolicy.ingressPodLabels | nindent 14 }}
        - podSelector:
            matchLabels:
              {{- include "king-ai.selectorLabels" . | nindent 14 }}
              app.kubernetes.io/component: ui
      ports:
        - protocol: TCP
          port: 8080
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ .Values.networkPolicy.dnsNamespace }}
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    {{- range .Values.networkPolicy.egress.databaseCidrs }}
    - to:
        - ipBlock:
            cidr: {{ . }}
      ports:
        - protocol: TCP
          port: {{ $.Values.externalDatabase.port }}
    {{- end }}
    {{- range .Values.networkPolicy.egress.redisCidrs }}
    - to:
        - ipBlock:
            cidr: {{ . }}
      ports:
        - protocol: TCP
          port: {{ $.Values.externalRedis.port }}
    {{- end }}
    {{- range .Values.networkPolicy.egress.milvusCidrs }}
    - to:
        - ipBlock:
            cidr: {{ . }}
      ports:
        - protocol: TCP
          port: 19530
    {{- end }}
    {{- range .Values.networkPolicy.egress.otelCidrs }}
    - to:
        - ipBlock:
            cidr: {{ . }}
      ports:
        - protocol: TCP
          port: {{ $.Values.networkPolicy.egress.otelPort }}
    {{- end }}
    {{- range .Values.networkPolicy.egress.objectStorageCidrs }}
    - to:
        - ipBlock:
            cidr: {{ . }}
      ports:
        - protocol: TCP
          port: 443
    {{- end }}
    {{- if .Values.networkPolicy.egress.publicHttps }}
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
      ports:
        - protocol: TCP
          port: 443
    {{- end }}
    {{- if .Values.devDependencies.enabled }}
    {{- if .Values.devDependencies.mysql.enabled }}
    - to:
        - podSelector:
            matchLabels:
              {{- include "king-ai.selectorLabels" . | nindent 14 }}
              app.kubernetes.io/component: mysql
      ports:
        - protocol: TCP
          port: 3306
    {{- end }}
    {{- if .Values.devDependencies.redis.enabled }}
    - to:
        - podSelector:
            matchLabels:
              {{- include "king-ai.selectorLabels" . | nindent 14 }}
              app.kubernetes.io/component: redis
      ports:
        - protocol: TCP
          port: 6379
    {{- end }}
    {{- if .Values.devDependencies.milvus.enabled }}
    - to:
        - podSelector:
            matchLabels:
              {{- include "king-ai.selectorLabels" . | nindent 14 }}
              app.kubernetes.io/component: milvus
      ports:
        - protocol: TCP
          port: 19530
    {{- end }}
    {{- end }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "king-ai.fullname" . }}-ui
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: ui
spec:
  podSelector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: ui
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ .Values.networkPolicy.ingressNamespace }}
          podSelector:
            matchLabels:
              {{- toYaml .Values.networkPolicy.ingressPodLabels | nindent 14 }}
      ports:
        - protocol: TCP
          port: 8080
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ .Values.networkPolicy.dnsNamespace }}
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    - to:
        - podSelector:
            matchLabels:
              {{- include "king-ai.selectorLabels" . | nindent 14 }}
              app.kubernetes.io/component: api
      ports:
        - protocol: TCP
          port: 8080
{{- end }}
```

- [ ] **Step 6: 运行测试**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: `render tests passed`。

- [ ] **Step 7: 提交**

```bash
git add deploy/helm/king-ai/templates/ingress.yaml \
  deploy/helm/king-ai/templates/hpa.yaml \
  deploy/helm/king-ai/templates/pdb.yaml \
  deploy/helm/king-ai/templates/networkpolicy.yaml \
  deploy/helm/king-ai/tests/render_test.sh
git commit -m "feat: add ingress sse settings, autoscaling and network policies"
```

---

### Task 6: 内置开发依赖与 kind values

**Files:**
- Create: `deploy/helm/king-ai/templates/dev-dependencies.yaml`
- Create: `deploy/helm/king-ai/tests/values-kind.yaml`
- Test: extend `deploy/helm/king-ai/tests/render_test.sh`

**Interfaces:**
- dev Secret：`{{ fullname }}-db`（`username`/`password`/`root-password`）、`{{ fullname }}-redis`（`password`）、`{{ fullname }}-app`（`app-secret`/`encryption-key`/`previous-keys`）、`{{ fullname }}-admin`（`username`/`password`）、`{{ fullname }}-minio`（`user`/`password`）。
- dev Service：`{{ fullname }}-mysql:3306`、`{{ fullname }}-redis:6379`、`{{ fullname }}-milvus:19530`。
- `values-kind.yaml` 关闭 HPA/PDB/NetworkPolicy/Ingress，`migration.enabled=false`，`persistence.exports.mode=emptyDir`，`devDependencies.milvus.enabled=false`。

- [ ] **Step 1: 创建 kind values 并扩展测试**

`tests/values-kind.yaml`：

```yaml
api:
  replicaCount: 1
  image:
    repository: king-ai-api
    tag: kind
    pullPolicy: IfNotPresent
ui:
  replicaCount: 1
  image:
    repository: king-ai-ui
    tag: kind
    pullPolicy: IfNotPresent
autoscaling:
  enabled: false
pdb:
  enabled: false
networkPolicy:
  enabled: false
ingress:
  enabled: false
persistence:
  exports:
    enabled: true
    mode: emptyDir
migration:
  enabled: false
devDependencies:
  enabled: true
  persistence:
    enabled: false
  milvus:
    enabled: false
  secrets:
    appSecret: "kind-only-app-secret-0123456789abcdef"
    encryptionKey: "kind-only-encryption-key-0123456789ab"
    adminUsername: admin
    adminPassword: "KindOnlyPass123456"
    mysqlPassword: "kindonlymysql"
    redisPassword: "kindonlyredis"
    minioUser: "kindonlyminio"
    minioPassword: "kindonlyminio"
```

在 `render_test.sh` 中加入：

```bash
helm template king-ai "$CHART_DIR" --namespace king-ai -f "$CHART_DIR/tests/values-kind.yaml" > "$TMP/kind.yaml"
assert_contains "$TMP/kind.yaml" 'name: king-ai-db'
assert_contains "$TMP/kind.yaml" 'name: king-ai-mysql'
assert_contains "$TMP/kind.yaml" 'name: king-ai-redis'
assert_contains "$TMP/kind.yaml" 'name: API_UPSTREAM'
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: 缺少 `name: king-ai-db`。

- [ ] **Step 3: 创建 dev Secrets、MySQL 与 Redis**

`templates/dev-dependencies.yaml`：

```yaml
{{- if .Values.devDependencies.enabled }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "king-ai.fullname" . }}-db
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
type: Opaque
stringData:
  username: king
  password: {{ required "devDependencies.secrets.mysqlPassword is required" .Values.devDependencies.secrets.mysqlPassword | quote }}
  root-password: {{ required "devDependencies.secrets.mysqlPassword is required" .Values.devDependencies.secrets.mysqlPassword | quote }}
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "king-ai.fullname" . }}-redis
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
type: Opaque
stringData:
  password: {{ required "devDependencies.secrets.redisPassword is required" .Values.devDependencies.secrets.redisPassword | quote }}
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "king-ai.fullname" . }}-app
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
type: Opaque
stringData:
  app-secret: {{ required "devDependencies.secrets.appSecret is required" .Values.devDependencies.secrets.appSecret | quote }}
  encryption-key: {{ required "devDependencies.secrets.encryptionKey is required" .Values.devDependencies.secrets.encryptionKey | quote }}
  previous-keys: ""
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "king-ai.fullname" . }}-admin
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
type: Opaque
stringData:
  username: {{ .Values.devDependencies.secrets.adminUsername | quote }}
  password: {{ required "devDependencies.secrets.adminPassword is required" .Values.devDependencies.secrets.adminPassword | quote }}
{{- if .Values.devDependencies.mysql.enabled }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-mysql
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: mysql
spec:
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: mysql
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-mysql
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: mysql
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: mysql
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: mysql
    spec:
      containers:
        - name: mysql
          image: {{ .Values.devDependencies.mysql.image }}
          env:
            - name: MYSQL_ROOT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-db
                  key: root-password
            - name: MYSQL_DATABASE
              value: {{ .Values.externalDatabase.database | quote }}
            - name: MYSQL_ROOT_HOST
              value: "%"
            - name: MYSQL_USER
              value: king
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-db
                  key: password
          ports:
            - name: mysql
              containerPort: 3306
          readinessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h 127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\" --silent"]
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 12
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
      volumes:
        - name: data
          {{- if .Values.devDependencies.persistence.enabled }}
          persistentVolumeClaim:
            claimName: {{ include "king-ai.fullname" . }}-mysql
          {{- else }}
          emptyDir: {}
          {{- end }}
{{- if .Values.devDependencies.persistence.enabled }}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ include "king-ai.fullname" . }}-mysql
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: mysql
spec:
  accessModes: [ReadWriteOnce]
  {{- if .Values.devDependencies.persistence.storageClass }}
  storageClassName: {{ .Values.devDependencies.persistence.storageClass | quote }}
  {{- end }}
  resources:
    requests:
      storage: {{ .Values.devDependencies.persistence.size }}
{{- end }}
{{- end }}
{{- if .Values.devDependencies.redis.enabled }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-redis
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: redis
spec:
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: redis
  ports:
    - name: redis
      port: 6379
      targetPort: redis
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-redis
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: redis
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: redis
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: redis
    spec:
      containers:
        - name: redis
          image: {{ .Values.devDependencies.redis.image }}
          command:
            - sh
            - -c
            - exec redis-server --appendonly yes --requirepass "$REDIS_PASSWORD"
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-redis
                  key: password
          ports:
            - name: redis
              containerPort: 6379
          readinessProbe:
            exec:
              command: ["sh", "-c", "redis-cli -a \"$REDIS_PASSWORD\" ping | grep PONG"]
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          emptyDir: {}
{{- end }}
{{- if .Values.devDependencies.milvus.enabled }}
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "king-ai.fullname" . }}-minio
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
type: Opaque
stringData:
  user: {{ required "devDependencies.secrets.minioUser is required" .Values.devDependencies.secrets.minioUser | quote }}
  password: {{ required "devDependencies.secrets.minioPassword is required" .Values.devDependencies.secrets.minioPassword | quote }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-etcd
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: etcd
spec:
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: etcd
  ports:
    - name: client
      port: 2379
      targetPort: client
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-etcd
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: etcd
spec:
  replicas: 1
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: etcd
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: etcd
    spec:
      containers:
        - name: etcd
          image: {{ .Values.devDependencies.milvus.etcdImage }}
          command:
            - etcd
            - --advertise-client-urls=http://0.0.0.0:2379
            - --listen-client-urls=http://0.0.0.0:2379
            - --data-dir=/etcd
          ports:
            - name: client
              containerPort: 2379
          volumeMounts:
            - name: data
              mountPath: /etcd
      volumes:
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-minio
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: minio
spec:
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: minio
  ports:
    - name: s3
      port: 9000
      targetPort: s3
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-minio
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: minio
spec:
  replicas: 1
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: minio
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: minio
    spec:
      containers:
        - name: minio
          image: {{ .Values.devDependencies.milvus.minioImage }}
          command: ["minio", "server", "/data", "--console-address", ":9001"]
          env:
            - name: MINIO_ROOT_USER
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-minio
                  key: user
            - name: MINIO_ROOT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-minio
                  key: password
          ports:
            - name: s3
              containerPort: 9000
          readinessProbe:
            httpGet:
              path: /minio/health/live
              port: s3
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "king-ai.fullname" . }}-milvus
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: milvus
spec:
  selector:
    {{- include "king-ai.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: milvus
  ports:
    - name: grpc
      port: 19530
      targetPort: grpc
    - name: health
      port: 9091
      targetPort: health
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "king-ai.fullname" . }}-milvus
  labels:
    {{- include "king-ai.labels" . | nindent 4 }}
    app.kubernetes.io/component: milvus
spec:
  replicas: 1
  selector:
    matchLabels:
      {{- include "king-ai.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: milvus
  template:
    metadata:
      labels:
        {{- include "king-ai.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: milvus
    spec:
      containers:
        - name: milvus
          image: {{ .Values.devDependencies.milvus.image }}
          command: ["milvus", "run", "standalone"]
          env:
            - name: ETCD_ENDPOINTS
              value: "{{ include "king-ai.fullname" . }}-etcd:2379"
            - name: MINIO_ADDRESS
              value: "{{ include "king-ai.fullname" . }}-minio:9000"
            - name: MINIO_ACCESS_KEY_ID
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-minio
                  key: user
            - name: MINIO_SECRET_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: {{ include "king-ai.fullname" . }}-minio
                  key: password
          ports:
            - name: grpc
              containerPort: 19530
            - name: health
              containerPort: 9091
          readinessProbe:
            httpGet:
              path: /healthz
              port: health
            initialDelaySeconds: 30
            periodSeconds: 10
            failureThreshold: 18
          volumeMounts:
            - name: data
              mountPath: /var/lib/milvus
      volumes:
        - name: data
          emptyDir: {}
{{- end }}
{{- end }}
```

- [ ] **Step 4: 运行渲染测试**

Run: `deploy/helm/king-ai/tests/render_test.sh`
Expected: `render tests passed`。

- [ ] **Step 5: 检查 dev 模式默认拒绝策略仍放行依赖**

Run:

```bash
helm template king-ai deploy/helm/king-ai -f deploy/helm/king-ai/tests/values-kind.yaml \
  --set networkPolicy.enabled=true \
  | grep -A4 'app.kubernetes.io/component: mysql'
```

Expected: 输出包含指向 `component: mysql` 的 egress 规则。若 kind 的 CNI 不执行 NetworkPolicy，渲染正确即可，文档需注明生产使用 Calico/Cilium 验证。

- [ ] **Step 6: 提交**

```bash
git add deploy/helm/king-ai/templates/dev-dependencies.yaml \
  deploy/helm/king-ai/tests/values-kind.yaml \
  deploy/helm/king-ai/tests/render_test.sh
git commit -m "feat: add optional development dependencies and kind values"
```

---

### Task 7: Chart 静态校验、CI 与 kind 安装/升级/回滚

**Files:**
- Create: `deploy/helm/king-ai/tests/kind-e2e.sh`
- Create: `deploy/helm/king-ai/README.md`（安装与回滚步骤；备份章节在 Task 12 补充）
- Modify: `.github/workflows/ci.yml`
- Test: `deploy/helm/king-ai/tests/render_test.sh`（加入 kubeconform）

**Interfaces:**
- `render_test.sh` 退出码 0 表示 lint + template + kubeconform + 断言全部通过。
- `kind-e2e.sh` 使用 kind 集群名 `king-ai-e2e`，release `king-ai`，namespace `king-ai`。

- [ ] **Step 1: 在 render 测试加入 kubeconform**

在 `render_test.sh` 末尾、`echo` 前加入：

```bash
if command -v kubeconform >/dev/null; then
  kubeconform -strict -summary -kubernetes-version 1.29.0 \
    "$TMP/default.yaml" "$TMP/kind.yaml" "$TMP/ingress.yaml"
else
  echo "kubeconform not installed; skipped schema validation" >&2
  exit 1
fi
```

把 ingress 渲染的变量名保持为 `$TMP/ingress.yaml`。同时加入：

```bash
assert_not_contains() {
  if grep -q -- "$2" "$1"; then
    echo "ASSERT FAILED: $1 unexpectedly contains $2" >&2
    exit 1
  fi
}
assert_not_contains "$TMP/default.yaml" 'kind: Secret'
```

- [ ] **Step 2: 写 kind 演练脚本**

`tests/kind-e2e.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
CHART_DIR="$ROOT_DIR/deploy/helm/king-ai"
CLUSTER=king-ai-e2e
RELEASE=king-ai
NAMESPACE=king-ai

for cmd in kind docker kubectl helm curl; do
  command -v "$cmd" >/dev/null || { echo "missing $cmd" >&2; exit 127; }
done

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kind create cluster --name "$CLUSTER" --wait 120s

docker build -f "$ROOT_DIR/king-ai-api/Dockerfile" -t king-ai-api:kind "$ROOT_DIR"
docker build -f "$ROOT_DIR/king-ai-ui/Dockerfile" -t king-ai-ui:kind "$ROOT_DIR"
kind load docker-image king-ai-api:kind king-ai-ui:kind --name "$CLUSTER"

helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" --create-namespace \
  -f "$CHART_DIR/tests/values-kind.yaml" \
  --wait --timeout 10m

kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-ui --timeout=120s

kubectl -n "$NAMESPACE" port-forward svc/king-ai-api 18080:8080 >/tmp/king-ai-e2e-port-forward.log 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" >/dev/null 2>&1 || true; cleanup' EXIT
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:18080/api/health | grep -q '"status":"UP"'; then
    break
  fi
  sleep 2
done
curl -fsS http://127.0.0.1:18080/api/health | grep -q '"status":"UP"'

helm upgrade "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$CHART_DIR/tests/values-kind.yaml" \
  --set api.podAnnotations.e2e-upgrade="$(date +%s)" \
  --wait --timeout 10m
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s

helm rollback "$RELEASE" 1 --namespace "$NAMESPACE" --wait --timeout 10m
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s

kubectl -n "$NAMESPACE" get pods -o wide
echo "kind e2e passed: install, upgrade and rollback"
```

- [ ] **Step 3: 写 Helm README 的安装/升级/回滚部分**

`deploy/helm/king-ai/README.md` 至少包含：

```markdown
# King AI Helm Chart

## 前置条件
- Kubernetes 1.28+，Helm 3.15+，默认 StorageClass（或设置 `persistence.exports.storageClass`）
- 外部 MySQL 8.4 / Redis 7 / Milvus 2.5；Prometheus Adapter（启用 HPA 自定义指标时）

## 创建 Secret
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

## 安装
helm upgrade --install king-ai deploy/helm/king-ai -n king-ai \
  --set ingress.enabled=true --set ingress.host=ai.example.com \
  --set externalDatabase.host=mysql.internal \
  --set externalRedis.host=redis.internal \
  --set externalMilvus.url=http://milvus.internal:19530 \
  --set networkPolicy.egress.databaseCidrs={10.0.10.0/24} \
  --set networkPolicy.egress.redisCidrs={10.0.20.0/24} \
  --set networkPolicy.egress.milvusCidrs={10.0.30.0/24} \
  --wait --timeout 15m

## 升级与回滚
helm upgrade king-ai deploy/helm/king-ai -n king-ai -f prod-values.yaml --wait --timeout 15m
helm history king-ai -n king-ai
helm rollback king-ai <REVISION> -n king-ai --wait --timeout 15m

## 验证
kubectl -n king-ai get pods
kubectl -n king-ai logs job/king-ai-migrate
curl -fsS https://ai.example.com/api/health

## 开发集群
kind create cluster --name king-ai-e2e
deploy/helm/king-ai/tests/kind-e2e.sh

## 限制
- SSE 流在 Pod 滚动升级时会被终止；断线恢复在 P2-2 实现。
- 启用 `persistence.exports.mode=pvc` 时默认要求 ReadWriteMany；否则将 API 限制为单副本或等待 P2-3 对象存储。
```

- [ ] **Step 4: 在 CI 增加部署检查 Job**

在 `.github/workflows/ci.yml` 的 `docker` job 之前加入：

```yaml
  deployment:
    name: Helm chart and backup scripts
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4

    - uses: azure/setup-helm@v4
      with:
        version: v3.15.4

    - name: Install kubeconform
      run: |
        curl -sSLo kubeconform.tar.gz https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz
        tar -xzf kubeconform.tar.gz kubeconform
        sudo mv kubeconform /usr/local/bin/kubeconform

    - name: ShellCheck
      run: |
        shellcheck deploy/helm/king-ai/tests/*.sh
        if compgen -G 'deploy/backup/*.sh' > /dev/null; then
          shellcheck deploy/backup/*.sh deploy/backup/tests/*.sh
        fi

    - name: Render and validate chart
      run: deploy/helm/king-ai/tests/render_test.sh

    - name: Backup script tests
      if: hashFiles('deploy/backup/tests/run_all.sh') != ''
      run: deploy/backup/tests/run_all.sh
```

- [ ] **Step 5: 运行本地检查**

Run:

```bash
deploy/helm/king-ai/tests/render_test.sh
command -v kind >/dev/null && deploy/helm/king-ai/tests/kind-e2e.sh
```

Expected: render 输出 `render tests passed`；kind 输出 `kind e2e passed: install, upgrade and rollback`。若本机没有 kind/Docker，记录为未运行，不得伪造结果。

- [ ] **Step 6: 提交**

```bash
git add deploy/helm/king-ai/tests/kind-e2e.sh \
  deploy/helm/king-ai/tests/render_test.sh \
  deploy/helm/king-ai/README.md .github/workflows/ci.yml
git commit -m "test: validate helm chart and add kind install rollback drill"
```

---

### Task 8: 备份基础库与 MySQL 全量备份

**Files:**
- Create: `deploy/backup/lib.sh`
- Create: `deploy/backup/mysql-backup.sh`
- Create: `deploy/backup/tests/run_all.sh`
- Create: `deploy/backup/tests/mysql_backup_test.sh`

**Interfaces:**
- `lib.sh` 提供 `log`、`die`、`require_cmd`、`require_env`、`timestamp`、`sha256`、`s3_upload <file> <key>`、`prune_dirs`、`prune_files`。
- 环境变量：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_SSL_MODE`、`BACKUP_OUT_DIR`、`BACKUP_LABEL`、`BACKUP_RETENTION_DAYS`、`BACKUP_S3_URI`、`BACKUP_S3_SSE`、`BACKUP_DRY_RUN`。
- 输出：`$BACKUP_OUT_DIR/$BACKUP_LABEL/$MYSQL_DATABASE.sql.gz`、`manifest.json`。

- [ ] **Step 1: 写失败测试**

`deploy/backup/tests/mysql_backup_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/out"

cat > "$TMP/bin/mysqldump" <<'EOF'
#!/usr/bin/env bash
echo "-- MySQL dump 10.13"
echo "-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='mysql-bin.000042', SOURCE_LOG_POS=1234;"
echo "CREATE TABLE sample(id int primary key);"
EOF
chmod +x "$TMP/bin/mysqldump"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *flyway_schema_history*) echo "8:access-review";;
  *) echo "1";;
esac
EOF
chmod +x "$TMP/bin/mysql"

PATH="$TMP/bin:$PATH" \
MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
BACKUP_OUT_DIR="$TMP/out" BACKUP_LABEL=test-run \
"$ROOT/mysql-backup.sh" >/dev/null

test -f "$TMP/out/test-run/king_ai.sql.gz"
gzip -dc "$TMP/out/test-run/king_ai.sql.gz" | grep -q 'CREATE TABLE sample'
test -f "$TMP/out/test-run/manifest.json"
jq -e '
  .label == "test-run" and
  .database == "king_ai" and
  .sha256 != "" and
  .flywayVersion == "8:access-review" and
  (.binlog | contains("mysql-bin.000042"))
' "$TMP/out/test-run/manifest.json" >/dev/null

echo "mysql backup test passed"
```

`deploy/backup/tests/run_all.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for test in "$DIR"/*_test.sh; do
  bash "$test"
done
```

- [ ] **Step 2: 运行测试确认失败**

Run: `chmod +x deploy/backup/tests/*.sh && deploy/backup/tests/run_all.sh`
Expected: `mysql-backup.sh: No such file or directory`。

- [ ] **Step 3: 实现 lib.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

require_env() {
  local name=$1
  [[ -n "${!name:-}" ]] || die "missing required environment variable: $name"
}

timestamp() {
  date -u +%Y%m%dT%H%M%SZ
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

s3_upload() {
  local file=$1
  local key=$2
  [[ -n "${BACKUP_S3_URI:-}" ]] || return 0
  require_cmd aws
  local args=(s3 cp "$file" "${BACKUP_S3_URI%/}/${key}" --only-show-errors)
  if [[ -n "${BACKUP_S3_SSE:-}" ]]; then
    args+=(--sse "$BACKUP_S3_SSE")
  fi
  aws "${args[@]}"
}

prune_dirs() {
  local dir=$1
  local prefix=$2
  local days=${3:-30}
  [[ -d "$dir" ]] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -name "${prefix}*" -mtime +"$days" -print -exec rm -rf {} +
}

prune_files() {
  local dir=$1
  local pattern=$2
  local days=${3:-30}
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -type f -name "$pattern" -mtime +"$days" -print -delete
}
```

- [ ] **Step 4: 实现 mysql-backup.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysqldump
require_cmd gzip
require_cmd mysql
require_cmd jq
require_env MYSQL_HOST
require_env MYSQL_DATABASE
require_env MYSQL_USER
require_env MYSQL_PASSWORD

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
LABEL="${BACKUP_LABEL:-mysql-$(timestamp)}"
TARGET_DIR="$BACKUP_OUT_DIR/$LABEL"
DUMP_FILE="$TARGET_DIR/$MYSQL_DATABASE.sql.gz"
MANIFEST_FILE="$TARGET_DIR/manifest.json"
export MYSQL_PWD="$MYSQL_PASSWORD"
umask 077
mkdir -p "$TARGET_DIR"

MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

FLYWAY_VERSION="$(mysql "${MYSQL_ARGS[@]}" -N -B \
  -e "SELECT CONCAT(version, ':', description) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" \
  "$MYSQL_DATABASE" 2>/dev/null || true)"

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN mysqldump --single-transaction --source-data=2 ${MYSQL_ARGS[*]} $MYSQL_DATABASE | gzip -9 > $DUMP_FILE"
  exit 0
fi

# 不使用 --databases：该选项会写入 CREATE DATABASE/USE 源库名，导致恢复到
# king_ai_restore 时仍然写回源库。去掉后 dump 只含表定义与数据，可按目标库名恢复。
mysqldump \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=OFF \
  --source-data=2 \
  --default-character-set=utf8mb4 \
  "${MYSQL_ARGS[@]}" \
  "$MYSQL_DATABASE" | gzip -9 > "$DUMP_FILE"

SHA256="$(sha256 "$DUMP_FILE")"
SIZE_BYTES="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
BINLOG_HEADER="$(gzip -dc "$DUMP_FILE" | grep -m1 '^-- CHANGE REPLICATION SOURCE TO' || true)"
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg label "$LABEL" \
  --arg database "$MYSQL_DATABASE" \
  --arg file "$(basename "$DUMP_FILE")" \
  --arg sha256 "$SHA256" \
  --arg flywayVersion "$FLYWAY_VERSION" \
  --arg binlog "$BINLOG_HEADER" \
  --arg finishedAt "$FINISHED_AT" \
  --argjson sizeBytes "${SIZE_BYTES:-0}" \
  '{
    label: $label,
    database: $database,
    file: $file,
    sha256: $sha256,
    sizeBytes: $sizeBytes,
    flywayVersion: $flywayVersion,
    binlog: $binlog,
    finishedAt: $finishedAt
  }' > "$MANIFEST_FILE"

if [[ -n "${BACKUP_S3_URI:-}" ]]; then
  s3_upload "$DUMP_FILE" "$LABEL/$(basename "$DUMP_FILE")"
  s3_upload "$MANIFEST_FILE" "$LABEL/manifest.json"
fi

prune_dirs "$BACKUP_OUT_DIR" 'mysql-' "$BACKUP_RETENTION_DAYS"
log "MySQL backup completed: $DUMP_FILE (${SIZE_BYTES} bytes, sha256=$SHA256)"
```

- [ ] **Step 5: 运行测试确认通过**

Run: `deploy/backup/tests/run_all.sh`
Expected: `mysql backup test passed`。

- [ ] **Step 6: 提交**

```bash
git add deploy/backup/lib.sh deploy/backup/mysql-backup.sh \
  deploy/backup/tests/run_all.sh deploy/backup/tests/mysql_backup_test.sh
git commit -m "feat: add mysql full backup script with checksums"
```

---

### Task 9: binlog 归档与 Milvus 备份

**Files:**
- Create: `deploy/backup/mysql-binlog-archive.sh`
- Create: `deploy/backup/milvus-backup.sh`
- Create: `deploy/backup/tests/binlog_archive_test.sh`
- Create: `deploy/backup/tests/milvus_backup_test.sh`
- Modify: `deploy/backup/tests/run_all.sh`（自动遍历，无需改动，仅确认 glob 覆盖）

**Interfaces:**
- binlog 状态文件 `$BACKUP_OUT_DIR/binlog/.last-binlog`，只存最后一个成功归档的 `mysql-bin.NNNNNN`。
- Milvus 备份使用 `milvus-backup` 配置：`milvus.address/port/token`、`minio.address/port/accessKeyID/secretAccessKey/bucketName/rootPath`。
- 环境变量：`MILVUS_HOST`、`MILVUS_PORT`、`MILVUS_TOKEN`、`MILVUS_BACKUP_MINIO_*`、`MILVUS_BACKUP_BIN`、`BACKUP_LABEL`、`BACKUP_DRY_RUN`。

- [ ] **Step 1: 写失败测试**

`deploy/backup/tests/binlog_archive_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/out"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
printf 'mysql-bin.000001 120\nmysql-bin.000002 240\n'
EOF
chmod +x "$TMP/bin/mysql"

cat > "$TMP/bin/mysqlbinlog" <<'EOF'
#!/usr/bin/env bash
echo "/* binlog: $* */"
echo "CREATE TABLE sample(id int);"
EOF
chmod +x "$TMP/bin/mysqlbinlog"

PATH="$TMP/bin:$PATH" \
MYSQL_HOST=127.0.0.1 MYSQL_USER=king MYSQL_PASSWORD=secret \
BACKUP_OUT_DIR="$TMP/out" \
"$ROOT/mysql-binlog-archive.sh" >/dev/null

test -f "$TMP/out/binlog/mysql-bin.000001.sql.gz"
test -f "$TMP/out/binlog/mysql-bin.000002.sql.gz"
test "$(cat "$TMP/out/binlog/.last-binlog")" = "mysql-bin.000002"
gzip -dc "$TMP/out/binlog/mysql-bin.000002.sql.gz" | grep -q 'CREATE TABLE sample'

echo "binlog archive test passed"
```

`deploy/backup/tests/milvus_backup_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(
  MILVUS_HOST=127.0.0.1 \
  MILVUS_TOKEN=super-secret-token \
  MILVUS_BACKUP_MINIO_ADDRESS=127.0.0.1 \
  MILVUS_BACKUP_MINIO_ACCESS_KEY=minio-user \
  MILVUS_BACKUP_MINIO_SECRET_KEY=minio-secret \
  MILVUS_BACKUP_MINIO_BUCKET=backup \
  BACKUP_LABEL=test-milvus \
  BACKUP_DRY_RUN=1 \
  "$ROOT/milvus-backup.sh" 2>&1
)"

printf '%s\n' "$OUT" | grep -q 'DRY-RUN milvus-backup create -n test-milvus'
if printf '%s\n' "$OUT" | grep -q 'super-secret-token'; then
  echo "secret leaked to backup output" >&2
  exit 1
fi
if printf '%s\n' "$OUT" | grep -q 'minio-secret'; then
  echo "minio secret leaked to backup output" >&2
  exit 1
fi

echo "milvus backup test passed"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/backup/tests/run_all.sh`
Expected: 分别报告脚本不存在。

- [ ] **Step 3: 实现 binlog 归档**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysql
require_cmd mysqlbinlog
require_cmd gzip
require_env MYSQL_HOST
require_env MYSQL_USER
require_env MYSQL_PASSWORD

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
BINLOG_DIR="$BACKUP_OUT_DIR/binlog"
STATE_FILE="$BINLOG_DIR/.last-binlog"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
export MYSQL_PWD="$MYSQL_PASSWORD"
umask 077
mkdir -p "$BINLOG_DIR"

LAST="$(cat "$STATE_FILE" 2>/dev/null || true)"
MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN would archive binlogs after '${LAST}' from $MYSQL_HOST:$MYSQL_PORT to $BINLOG_DIR"
  exit 0
fi

# 先取列表再处理：进程替换里的 mysql 失败不会触发 set -e，会静默变成“无日志可归档”。
LOG_LIST="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SHOW BINARY LOGS")" \
  || die "cannot list binary logs from $MYSQL_HOST:$MYSQL_PORT"

while read -r LOG_NAME _REST; do
  [[ -n "$LOG_NAME" ]] || continue
  [[ "$LOG_NAME" =~ ^mysql-bin\.[0-9]+$ ]] || continue
  if [[ -n "$LAST" ]]; then
    LAST_SEQ="${LAST#mysql-bin.}"
    SEQ="${LOG_NAME#mysql-bin.}"
    [[ "$LAST_SEQ" =~ ^[0-9]+$ && "$SEQ" =~ ^[0-9]+$ ]] \
      || die "unexpected binlog sequence: last='$LAST' current='$LOG_NAME'"
    # 按数值比较，避免序号从 999999 进位到 1000000 后字典序判断失效。
    if (( 10#$SEQ <= 10#$LAST_SEQ )); then
      continue
    fi
  fi
  TARGET="$BINLOG_DIR/$LOG_NAME.sql.gz"
  mysqlbinlog \
    --read-from-remote-server \
    "${MYSQL_ARGS[@]}" \
    "$LOG_NAME" | gzip -9 > "$TARGET"
  SHA256="$(sha256 "$TARGET")"
  if [[ -n "${BACKUP_S3_URI:-}" ]]; then
    s3_upload "$TARGET" "binlog/$LOG_NAME.sql.gz"
  fi
  printf '%s\n' "$LOG_NAME" > "$STATE_FILE"
  log "Archived binlog $LOG_NAME (sha256=$SHA256)"
done <<< "$LOG_LIST"

prune_files "$BINLOG_DIR" 'mysql-bin.*.sql.gz' "$RETENTION_DAYS"
log "Binlog archive completed; last=$LAST"
```

- [ ] **Step 4: 实现 Milvus 备份**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env MILVUS_HOST
require_env MILVUS_BACKUP_MINIO_ADDRESS
require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
require_env MILVUS_BACKUP_MINIO_SECRET_KEY
require_env MILVUS_BACKUP_MINIO_BUCKET
require_cmd jq

# 配置值是 YAML 标量，统一走 JSON 引号编码，避免特殊字符破坏结构。
quote() { jq -Rn --arg value "$1" '$value'; }

MILVUS_PORT="${MILVUS_PORT:-19530}"
MILVUS_TOKEN="${MILVUS_TOKEN:-}"
BACKUP_BIN="${MILVUS_BACKUP_BIN:-milvus-backup}"
LABEL="${BACKUP_LABEL:-milvus-$(timestamp)}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
MANIFEST_DIR="$BACKUP_OUT_DIR/$LABEL"

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN milvus-backup create -n $LABEL (milvus=$MILVUS_HOST:$MILVUS_PORT, bucket=$MILVUS_BACKUP_MINIO_BUCKET)"
  exit 0
fi

command -v "$BACKUP_BIN" >/dev/null 2>&1 || die "missing milvus-backup binary: $BACKUP_BIN"
umask 077
mkdir -p "$MANIFEST_DIR"
CONFIG_FILE="$MANIFEST_DIR/milvus-backup.yaml"
cleanup() {
  rm -f "$CONFIG_FILE"
}
trap cleanup EXIT

cat > "$CONFIG_FILE" <<EOF
log:
  level: info
  console: true
milvus:
  address: $(quote "$MILVUS_HOST")
  port: ${MILVUS_PORT}
  authorizationEnabled: $([[ -n "$MILVUS_TOKEN" ]] && echo true || echo false)
  token: $(quote "$MILVUS_TOKEN")
minio:
  storageType: minio
  address: $(quote "$MILVUS_BACKUP_MINIO_ADDRESS")
  port: ${MILVUS_BACKUP_MINIO_PORT:-9000}
  accessKeyID: $(quote "$MILVUS_BACKUP_MINIO_ACCESS_KEY")
  secretAccessKey: $(quote "$MILVUS_BACKUP_MINIO_SECRET_KEY")
  bucketName: $(quote "$MILVUS_BACKUP_MINIO_BUCKET")
  rootPath: $(quote "${MILVUS_BACKUP_MINIO_ROOT_PATH:-backup}")
  useSSL: ${MILVUS_BACKUP_MINIO_USE_SSL:-false}
EOF

"$BACKUP_BIN" --config "$CONFIG_FILE" create -n "$LABEL"
"$BACKUP_BIN" --config "$CONFIG_FILE" get -n "$LABEL" > "$MANIFEST_DIR/backup-info.txt"

jq -n \
  --arg label "$LABEL" \
  --arg milvus "$MILVUS_HOST:$MILVUS_PORT" \
  --arg bucket "$MILVUS_BACKUP_MINIO_BUCKET" \
  --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{label: $label, milvus: $milvus, bucket: $bucket, finishedAt: $finishedAt}' \
  > "$MANIFEST_DIR/manifest.json"

if [[ -n "${BACKUP_S3_URI:-}" ]]; then
  s3_upload "$MANIFEST_DIR/manifest.json" "$LABEL/milvus-manifest.json"
  s3_upload "$MANIFEST_DIR/backup-info.txt" "$LABEL/milvus-backup-info.txt"
fi

log "Milvus backup completed: $LABEL"
```

- [ ] **Step 5: 运行测试**

Run: `deploy/backup/tests/run_all.sh`
Expected: 三个测试均输出 `passed`。

- [ ] **Step 6: 提交**

```bash
git add deploy/backup/mysql-binlog-archive.sh deploy/backup/milvus-backup.sh \
  deploy/backup/tests/binlog_archive_test.sh deploy/backup/tests/milvus_backup_test.sh
git commit -m "feat: add binlog archiving and milvus backup scripts"
```

---

### Task 10: 恢复、校验与隔离 namespace 演练

**Files:**
- Create: `deploy/backup/restore-mysql.sh`
- Create: `deploy/backup/restore-milvus.sh`
- Create: `deploy/backup/verify.sh`
- Create: `deploy/backup/restore-drill.sh`
- Create: `deploy/backup/tests/restore_guard_test.sh`
- Create: `deploy/backup/tests/verify_test.sh`
- Create: `deploy/helm/king-ai/tests/values-drill.yaml`

**Interfaces:**
- `restore-mysql.sh` 必须同时满足 `RESTORE_CONFIRM=RESTORE`、目标库名不含 `prod`、目标库不等于 `MYSQL_DATABASE`（除非 `RESTORE_ALLOW_SAME_DB=1`）。
- `verify.sh` 输出 JSON 报告到 stdout 和 `VERIFY_REPORT` 文件；失败时退出 1。
- `restore-drill.sh` 在隔离 namespace 安装 chart（dev 依赖、API 0 副本），恢复数据后扩容 API，记录 RTO。

- [ ] **Step 1: 写失败测试**

`deploy/backup/tests/restore_guard_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf 'not-a-real-dump' | gzip > "$TMP/dump.sql.gz"

expect_fail() {
  if "$@" >/dev/null 2>&1; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

expect_fail env MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_prod BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh" >/dev/null

echo "restore guard test passed"
```

`deploy/backup/tests/verify_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
# 分支按 verify.sh 实际 SQL 文本匹配，顺序从最具体到最宽泛。
case "$*" in
  *"flyway_schema_history"*) echo "12";;
  *"role = 'assistant'"*) echo "0";;
  *"LEFT JOIN king_ai_chat_session"*) echo "0";;
  *"ORDER BY id DESC LIMIT 5"*) echo "5";;
  *"king_ai_user"*) echo "3";;
  *"king_ai_space"*) echo "1";;
  *"king_ai_chat_session"*) echo "4";;
  *"king_ai_chat_message"*) echo "9";;
  *"vector_collection"*) echo "kb_collection_1";;
  *) echo "0";;
esac
EOF
chmod +x "$TMP/bin/mysql"

cat > "$TMP/bin/curl" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"/api/health"* ]]; then
  echo '{"code":200,"message":"OK","data":{"status":"UP"}}'
elif [[ "$*" == *"entities/query"* ]]; then
  echo '{"code":0,"data":[{"count(*)":42}]}'
else
  echo '{"code":0,"data":[]}'
fi
EOF
chmod +x "$TMP/bin/curl"

PATH="$TMP/bin:$PATH" \
VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_DATABASE=king_ai \
VERIFY_MYSQL_USER=king VERIFY_MYSQL_PASSWORD=secret \
VERIFY_API_URL=http://127.0.0.1:18080 \
VERIFY_MILVUS_URL=http://127.0.0.1:19531 \
VERIFY_REPORT="$TMP/report.json" \
"$ROOT/verify.sh" >/dev/null

jq -e '
  .checks.flyway.status == "ok" and
  .checks.assistant_blank.status == "ok" and
  .checks.orphan_messages.status == "ok" and
  .checks.messages.value == "9" and
  .checks.vector_count_0.value == "42"
' "$TMP/report.json" >/dev/null

# 空 Flyway 历史必须让校验失败并落盘 failed 状态，而不是永远标记 ok。
mkdir -p "$TMP/bin-empty"
cat > "$TMP/bin-empty/mysql" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"flyway_schema_history"*) echo "0";;
  *"role = 'assistant'"*) echo "0";;
  *"LEFT JOIN king_ai_chat_session"*) echo "0";;
  *"ORDER BY id DESC LIMIT 5"*) echo "5";;
  *"king_ai_chat_message"*) echo "9";;
  *"vector_collection"*) echo "kb_collection_1";;
  *) echo "1";;
esac
EOF
chmod +x "$TMP/bin-empty/mysql"

set +e
PATH="$TMP/bin-empty:$TMP/bin:$PATH" \
VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_DATABASE=king_ai \
VERIFY_MYSQL_USER=king VERIFY_MYSQL_PASSWORD=secret \
VERIFY_API_URL=http://127.0.0.1:18080 \
VERIFY_MILVUS_URL=http://127.0.0.1:19531 \
VERIFY_REPORT="$TMP/report-empty.json" \
"$ROOT/verify.sh" >/dev/null 2>&1
STATUS=$?
set -e
[[ "$STATUS" -eq 1 ]] || { echo "expected verify.sh to exit 1 when flyway history is empty" >&2; exit 1; }
jq -e '.checks.flyway.status == "failed"' "$TMP/report-empty.json" >/dev/null

echo "verify test passed"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/backup/tests/run_all.sh`
Expected: `restore-mysql.sh`、`verify.sh` 不存在。

- [ ] **Step 3: 实现 restore-mysql.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ "${RESTORE_CONFIRM:-}" == "RESTORE" ]] || die "set RESTORE_CONFIRM=RESTORE to confirm restore"
require_env MYSQL_HOST
require_env MYSQL_USER
require_env MYSQL_PASSWORD
require_env RESTORE_TARGET_DATABASE
require_env BACKUP_FILE

TARGET_DB="$RESTORE_TARGET_DATABASE"
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] || die "invalid target database name: $TARGET_DB"
[[ "$TARGET_DB" != *prod* || "${RESTORE_ALLOW_PROD_NAME:-0}" == "1" ]] \
  || die "refusing target database containing 'prod': $TARGET_DB"
if [[ "$TARGET_DB" == "${MYSQL_DATABASE:-}" && "${RESTORE_ALLOW_SAME_DB:-0}" != "1" ]]; then
  die "refusing to restore into the source database; set RESTORE_ALLOW_SAME_DB=1 for an isolated drill"
fi
[[ -f "$BACKUP_FILE" ]] || die "backup file not found: $BACKUP_FILE"

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
# 备份账号只有只读权限；恢复需要建库/建表/写入权限。默认沿用 MYSQL_USER，
# 正式演练必须显式提供 RESTORE_MYSQL_USER/RESTORE_MYSQL_PASSWORD。
RESTORE_USER="${RESTORE_MYSQL_USER:-$MYSQL_USER}"
RESTORE_PASSWORD="${RESTORE_MYSQL_PASSWORD:-$MYSQL_PASSWORD}"
[[ -n "${RESTORE_MYSQL_USER:-}" && -n "${RESTORE_MYSQL_PASSWORD:-}" || "$RESTORE_USER" == "$MYSQL_USER" ]] \
  || die "RESTORE_MYSQL_USER and RESTORE_MYSQL_PASSWORD must be set together"
export MYSQL_PWD="$RESTORE_PASSWORD"
MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$RESTORE_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

if [[ "${RESTORE_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN create database $TARGET_DB and load $BACKUP_FILE"
  exit 0
fi

require_cmd mysql
mysql "${MYSQL_ARGS[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

case "$BACKUP_FILE" in
  *.gz)
    gzip -dc "$BACKUP_FILE" | mysql "${MYSQL_ARGS[@]}" "$TARGET_DB"
    ;;
  *)
    mysql "${MYSQL_ARGS[@]}" "$TARGET_DB" < "$BACKUP_FILE"
    ;;
esac

FLYWAY_ROWS="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM flyway_schema_history" "$TARGET_DB")"
[[ "$FLYWAY_ROWS" =~ ^[0-9]+$ && "$FLYWAY_ROWS" -gt 0 ]] || die "restored database has no flyway history"
log "Restore completed into $TARGET_DB; flyway history rows=$FLYWAY_ROWS"
```

恢复账号需要的权限（仅授予目标标库）：`CREATE, ALTER, DROP, INDEX, INSERT, UPDATE, DELETE, SELECT, CREATE VIEW, SHOW VIEW, TRIGGER, REFERENCES`。不得直接复用备份账号。

binlog 回放只适用于**同名库**的时间点恢复：`mysqlbinlog` 解码出的 SQL 带 `use \`<源库>\`;`，跨库改名演练（`king_ai_restore`）只做全量 dump 恢复与校验，不叠加 binlog，否则会写回源库。

- [ ] **Step 4: 实现 restore-milvus.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ "${MILVUS_RESTORE_CONFIRM:-}" == "yes" ]] || die "set MILVUS_RESTORE_CONFIRM=yes to confirm restore"
require_env MILVUS_HOST
require_env MILVUS_BACKUP_NAME
require_env MILVUS_BACKUP_MINIO_ADDRESS
require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
require_env MILVUS_BACKUP_MINIO_SECRET_KEY
require_env MILVUS_BACKUP_MINIO_BUCKET
require_cmd jq

# 与 milvus-backup.sh 共用同一套 YAML 标量编码，避免注入与断行。
quote() { jq -Rn --arg value "$1" '$value'; }

BACKUP_BIN="${MILVUS_BACKUP_BIN:-milvus-backup}"
# 注意用 ${VAR-default} 而不是 ${VAR:-default}：演练需要显式传空串，
# 表示“恢复到原集合名”，不能用时间戳后缀生成一套对不上元数据的新集合。
SUFFIX="${MILVUS_RESTORE_SUFFIX-_restore_$(timestamp)}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
CONFIG="$TMP/milvus-backup.yaml"
umask 077

cat > "$CONFIG" <<EOF
log:
  level: info
  console: true
milvus:
  address: $(quote "$MILVUS_HOST")
  port: ${MILVUS_PORT:-19530}
  authorizationEnabled: $([[ -n "${MILVUS_TOKEN:-}" ]] && echo true || echo false)
  token: $(quote "${MILVUS_TOKEN:-}")
minio:
  storageType: minio
  address: $(quote "$MILVUS_BACKUP_MINIO_ADDRESS")
  port: ${MILVUS_BACKUP_MINIO_PORT:-9000}
  accessKeyID: $(quote "$MILVUS_BACKUP_MINIO_ACCESS_KEY")
  secretAccessKey: $(quote "$MILVUS_BACKUP_MINIO_SECRET_KEY")
  bucketName: $(quote "$MILVUS_BACKUP_MINIO_BUCKET")
  rootPath: $(quote "${MILVUS_BACKUP_MINIO_ROOT_PATH:-backup}")
  useSSL: ${MILVUS_BACKUP_MINIO_USE_SSL:-false}
EOF

if [[ "${MILVUS_RESTORE_DRY_RUN:-0}" == "1" ]]; then
  if [[ -n "$SUFFIX" ]]; then
    log "DRY-RUN milvus-backup restore -n $MILVUS_BACKUP_NAME --restore_collection_suffix $SUFFIX"
  else
    log "DRY-RUN milvus-backup restore -n $MILVUS_BACKUP_NAME (keep collection names)"
  fi
  exit 0
fi

command -v "$BACKUP_BIN" >/dev/null 2>&1 || die "missing milvus-backup binary: $BACKUP_BIN"
RESTORE_ARGS=(--config "$CONFIG" restore -n "$MILVUS_BACKUP_NAME")
if [[ -n "$SUFFIX" ]]; then
  RESTORE_ARGS+=(--restore_collection_suffix "$SUFFIX")
fi
"$BACKUP_BIN" "${RESTORE_ARGS[@]}"
log "Milvus restore completed: backup=$MILVUS_BACKUP_NAME suffix=${SUFFIX:-<none>}"
```

- [ ] **Step 5: 实现 verify.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysql
require_cmd curl
require_cmd jq
require_env VERIFY_MYSQL_HOST
require_env VERIFY_MYSQL_DATABASE
require_env VERIFY_MYSQL_USER
require_env VERIFY_MYSQL_PASSWORD
require_env VERIFY_API_URL

MYSQL_PORT="${VERIFY_MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${VERIFY_MYSQL_SSL_MODE:-REQUIRED}"
REPORT="${VERIFY_REPORT:-$PWD/verify-report.json}"
export MYSQL_PWD="$VERIFY_MYSQL_PASSWORD"
MYSQL_ARGS=(
  --host "$VERIFY_MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$VERIFY_MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
  --database "$VERIFY_MYSQL_DATABASE"
)

CHECKS='{}'
FAILED=0

add_check() {
  local name=$1 value=$2 status=$3
  CHECKS="$(jq --arg name "$name" --arg value "$value" --arg status "$status" \
    '. + {($name): {value: $value, status: $status}}' <<< "$CHECKS")"
  [[ "$status" == "ok" || "$status" == "skipped" ]] || FAILED=1
}

count() {
  mysql "${MYSQL_ARGS[@]}" -N -B -e "$1" 2>/dev/null || echo "error"
}

add_numeric_check() {
  local name=$1 value=$2
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    add_check "$name" "$value" "ok"
  else
    add_check "$name" "$value" "failed"
  fi
}

FLYWAY="$(count 'SELECT COUNT(*) FROM flyway_schema_history')"
if [[ "$FLYWAY" =~ ^[0-9]+$ && "$FLYWAY" -gt 0 ]]; then
  add_check "flyway" "$FLYWAY" "ok"
else
  add_check "flyway" "$FLYWAY" "failed"
fi
add_numeric_check "users" "$(count 'SELECT COUNT(*) FROM king_ai_user')"
add_numeric_check "spaces" "$(count 'SELECT COUNT(*) FROM king_ai_space')"
add_numeric_check "sessions" "$(count 'SELECT COUNT(*) FROM king_ai_chat_session')"
add_numeric_check "messages" "$(count 'SELECT COUNT(*) FROM king_ai_chat_message')"

BLANK="$(count "SELECT COUNT(*) FROM king_ai_chat_message WHERE role = 'assistant' AND (content IS NULL OR content = '')")"
if [[ "$BLANK" == "0" ]]; then
  add_check "assistant_blank" "$BLANK" "ok"
else
  add_check "assistant_blank" "$BLANK" "failed"
fi

ORPHANS="$(count 'SELECT COUNT(*) FROM king_ai_chat_message m LEFT JOIN king_ai_chat_session s ON s.id = m.session_id WHERE s.id IS NULL')"
if [[ "$ORPHANS" == "0" ]]; then
  add_check "orphan_messages" "$ORPHANS" "ok"
else
  add_check "orphan_messages" "$ORPHANS" "failed"
fi

SAMPLE="$(count "SELECT COUNT(*) FROM (SELECT content FROM king_ai_chat_message WHERE content IS NOT NULL AND content <> '' ORDER BY id DESC LIMIT 5) t")"
if [[ "$SAMPLE" =~ ^[0-9]+$ && "$SAMPLE" -gt 0 ]]; then
  add_check "recent_messages" "$SAMPLE" "ok"
else
  add_check "recent_messages" "$SAMPLE" "failed"
fi

if HEALTH="$(curl -fsS "$VERIFY_API_URL/api/health" 2>/dev/null)"; then
  HEALTH_STATUS="$(jq -r '.data.status // .status // "UNKNOWN"' <<< "$HEALTH")"
else
  HEALTH_STATUS="UNREACHABLE"
fi
if [[ "$HEALTH_STATUS" == "UP" ]]; then
  add_check "api_health" "$HEALTH_STATUS" "ok"
else
  add_check "api_health" "$HEALTH_STATUS" "failed"
fi

if [[ -n "${VERIFY_MILVUS_URL:-}" ]]; then
  INDEX=0
  while read -r COLLECTION; do
    [[ -n "$COLLECTION" ]] || continue
    VECTOR_RESPONSE="$(
      curl -fsS -X POST "$VERIFY_MILVUS_URL/v2/vectordb/entities/query" \
        -H "Content-Type: application/json" \
        ${VERIFY_MILVUS_TOKEN:+-H "Authorization: Bearer $VERIFY_MILVUS_TOKEN"} \
        -d "{\"collectionName\":\"$COLLECTION\",\"filter\":\"\",\"outputFields\":[\"count(*)\"]}" \
        2>/dev/null || true
    )"
    VECTOR_COUNT="$(jq -r '.data[0]["count(*)"] // empty' <<< "${VECTOR_RESPONSE:-{\}}" 2>/dev/null || true)"
    add_numeric_check "vector_count_${INDEX}" "$VECTOR_COUNT"
    INDEX=$((INDEX + 1))
  done < <(count "SELECT vector_collection FROM king_ai_knowledge_base WHERE vector_collection IS NOT NULL AND vector_collection <> ''")
else
  add_check "vector_count" "not-run" "skipped"
fi

jq -n --argjson checks "$CHECKS" --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{finishedAt: $finishedAt, checks: $checks}' > "$REPORT"
cat "$REPORT"

if [[ "$FAILED" != "0" ]]; then
  log "Verification failed; report=$REPORT"
  exit 1
fi
log "Verification passed; report=$REPORT"
```

- [ ] **Step 6: 实现隔离 namespace 演练**

`deploy/helm/king-ai/tests/values-drill.yaml`：

```yaml
api:
  replicaCount: 0
  image:
    repository: king-ai-api
    tag: drill
    pullPolicy: IfNotPresent
ui:
  replicaCount: 0
  image:
    repository: king-ai-ui
    tag: drill
    pullPolicy: IfNotPresent
autoscaling:
  enabled: false
pdb:
  enabled: false
networkPolicy:
  enabled: false
ingress:
  enabled: false
migration:
  enabled: false
persistence:
  exports:
    enabled: true
    mode: emptyDir
devDependencies:
  enabled: true
  persistence:
    enabled: false
  milvus:
    enabled: true
  secrets:
    appSecret: "drill-only-app-secret-0123456789abcdef"
    encryptionKey: "drill-only-encryption-key-0123456789ab"
    adminUsername: admin
    adminPassword: "DrillOnlyPass123456"
    mysqlPassword: "drillonlymysql"
    redisPassword: "drillonlyredis"
    minioUser: "drillonlyminio"
    minioPassword: "drillonlyminio"
```

`restore-drill.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env DRILL_NAMESPACE
require_env MYSQL_DUMP
if [[ "${SKIP_MILVUS:-0}" != "1" ]]; then
  require_env MILVUS_BACKUP_NAME
  require_env MILVUS_BACKUP_MINIO_ADDRESS
  require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
  require_env MILVUS_BACKUP_MINIO_SECRET_KEY
  require_env MILVUS_BACKUP_MINIO_BUCKET
fi

CHART_DIR="${CHART_DIR:-$SCRIPT_DIR/../helm/king-ai}"
RELEASE=king-ai-drill
# chart 的 fullname 在 release 名包含 chart 名时取 release 名，因此资源前缀等于 RELEASE。
PREFIX="$RELEASE"
NAMESPACE="$DRILL_NAMESPACE"
MYSQL_LOCAL_PORT="${DRILL_MYSQL_PORT:-13306}"
MILVUS_LOCAL_PORT="${DRILL_MILVUS_PORT:-19531}"
API_LOCAL_PORT="${DRILL_API_PORT:-18080}"
START_EPOCH="$(date +%s)"
KEEP_NAMESPACE="${KEEP_NAMESPACE:-0}"
SKIP_MILVUS="${SKIP_MILVUS:-0}"

for cmd in kubectl helm curl jq mysql base64; do
  require_cmd "$cmd"
done

kill_pf() {
  local pid
  for pid in "$@"; do
    [[ -z "$pid" ]] || kill "$pid" >/dev/null 2>&1 || true
  done
}

cleanup() {
  kill_pf "${MYSQL_PF:-}" "${MILVUS_PF:-}" "${API_PF:-}"
  if [[ "$KEEP_NAMESPACE" != "1" ]]; then
    kubectl delete namespace "$NAMESPACE" --wait=false >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_tcp() {
  local host=$1 port=$2 label=$3
  local attempt
  for attempt in $(seq 1 60); do
    if (echo > "/dev/tcp/$host/$port") 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for $label on $host:$port after $attempt attempts"
}

wait_http() {
  local url=$1 label=$2
  local attempt
  for attempt in $(seq 1 90); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for $label: $url after $attempt attempts"
}

wait_milvus() {
  local url="http://127.0.0.1:$MILVUS_LOCAL_PORT/v2/vectordb/collections/list"
  local attempt
  for attempt in $(seq 1 90); do
    # Milvus 的 /healthz 在 9091 端口；这里走 19530 的 RESTful API，
    # 确认 REST 网关和元数据都已就绪后再恢复。
    if curl -fsS -X POST "$url" -H 'Content-Type: application/json' -d '{}' \
        >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for milvus REST API on 127.0.0.1:$MILVUS_LOCAL_PORT after $attempt attempts"
}

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$CHART_DIR/tests/values-drill.yaml" \
  --wait --timeout 15m

MYSQL_ROOT_PASSWORD="$(
  kubectl -n "$NAMESPACE" get secret "$PREFIX-db" -o jsonpath='{.data.root-password}' 2>/dev/null \
    | base64 -d 2>/dev/null || true
)"
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die "cannot read drill mysql root password from secret $PREFIX-db"

kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-mysql" "$MYSQL_LOCAL_PORT:3306" \
  >/tmp/drill-mysql-pf.log 2>&1 &
MYSQL_PF=$!
wait_tcp 127.0.0.1 "$MYSQL_LOCAL_PORT" mysql

if [[ "$SKIP_MILVUS" != "1" ]]; then
  kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-milvus" "$MILVUS_LOCAL_PORT:19530" \
    >/tmp/drill-milvus-pf.log 2>&1 &
  MILVUS_PF=$!
  wait_tcp 127.0.0.1 "$MILVUS_LOCAL_PORT" milvus
  wait_milvus
fi

MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD" \
RESTORE_CONFIRM=RESTORE \
MYSQL_HOST=127.0.0.1 MYSQL_PORT="$MYSQL_LOCAL_PORT" MYSQL_DATABASE=king_ai \
MYSQL_USER=root MYSQL_SSL_MODE=DISABLED \
RESTORE_TARGET_DATABASE=king_ai RESTORE_ALLOW_SAME_DB=1 \
BACKUP_FILE="$MYSQL_DUMP" \
"$SCRIPT_DIR/restore-mysql.sh"

if [[ "$SKIP_MILVUS" != "1" ]]; then
  MILVUS_HOST=127.0.0.1 MILVUS_PORT="$MILVUS_LOCAL_PORT" \
  MILVUS_BACKUP_NAME="$MILVUS_BACKUP_NAME" \
  MILVUS_RESTORE_CONFIRM=yes \
  MILVUS_RESTORE_SUFFIX="" \
  "$SCRIPT_DIR/restore-milvus.sh"
fi

kubectl -n "$NAMESPACE" scale "deployment/$PREFIX-api" --replicas=1
kubectl -n "$NAMESPACE" rollout status "deployment/$PREFIX-api" --timeout=300s
kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-api" "$API_LOCAL_PORT:8080" \
  >/tmp/drill-api-pf.log 2>&1 &
API_PF=$!
wait_http "http://127.0.0.1:$API_LOCAL_PORT/api/health" api

VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_PORT="$MYSQL_LOCAL_PORT" \
VERIFY_MYSQL_DATABASE=king_ai VERIFY_MYSQL_USER=root \
VERIFY_MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD" VERIFY_MYSQL_SSL_MODE=DISABLED \
VERIFY_API_URL="http://127.0.0.1:$API_LOCAL_PORT" \
VERIFY_MILVUS_URL=$([[ "$SKIP_MILVUS" == "1" ]] && echo "" || echo "http://127.0.0.1:$MILVUS_LOCAL_PORT") \
VERIFY_REPORT="$PWD/drill-verify-report.json" \
"$SCRIPT_DIR/verify.sh"

END_EPOCH="$(date +%s)"
RTO_SECONDS=$((END_EPOCH - START_EPOCH))
jq -n \
  --arg namespace "$DRILL_NAMESPACE" \
  --arg mysqlDump "$MYSQL_DUMP" \
  --arg milvusBackup "$MILVUS_BACKUP_NAME" \
  --argjson rtoSeconds "$RTO_SECONDS" \
  --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{namespace: $namespace, mysqlDump: $mysqlDump, milvusBackup: $milvusBackup, rtoSeconds: $rtoSeconds, finishedAt: $finishedAt}' \
  > "$PWD/drill-report.json"

if (( RTO_SECONDS > 3600 )); then
  die "RTO ${RTO_SECONDS}s exceeds 3600s"
fi
log "Drill passed; RTO=${RTO_SECONDS}s; report=$PWD/drill-report.json"
```

- [ ] **Step 7: 运行测试**

Run:

```bash
chmod +x deploy/backup/*.sh deploy/backup/tests/*.sh
deploy/backup/tests/run_all.sh
shellcheck deploy/backup/*.sh deploy/backup/tests/*.sh
```

Expected: 六个测试全部 `passed`；`shellcheck` 无输出。

- [ ] **Step 8: 提交**

```bash
git add deploy/backup/restore-mysql.sh deploy/backup/restore-milvus.sh \
  deploy/backup/verify.sh deploy/backup/restore-drill.sh \
  deploy/backup/tests/restore_guard_test.sh deploy/backup/tests/verify_test.sh \
  deploy/helm/king-ai/tests/values-drill.yaml
git commit -m "feat: add restore verification and isolated namespace drill"
```

---

### Task 11: 对象存储生命周期与备份调度

**Files:**
- Create: `deploy/backup/minio-lifecycle.sh`
- Create: `deploy/backup/tests/minio_lifecycle_test.sh`
- Create: `deploy/backup/README.md`（备份运行手册主体）
- Test: `deploy/backup/tests/run_all.sh`（自动覆盖）

**Interfaces:**
- `minio-lifecycle.sh` 使用 `mc`，对备份桶开启版本化并设置 30 天过期，对导出桶设置 7 天过期；`DRY_RUN=1` 时只打印命令。
- 备份调度建议：MySQL 全量每日 02:00、binlog 每 5 分钟、Milvus 每日 02:30、恢复演练每月第一个周三。

- [ ] **Step 1: 写失败测试**

`deploy/backup/tests/minio_lifecycle_test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/mc" <<'EOF'
#!/usr/bin/env bash
echo "mc $*"
EOF
chmod +x "$TMP/bin/mc"

OUT="$(PATH="$TMP/bin:$PATH" MC_ALIAS=minio \
  BACKUP_BUCKET=king-ai-backups EXPORT_BUCKET=king-ai-exports DRY_RUN=1 \
  "$ROOT/minio-lifecycle.sh" 2>&1)"

printf '%s\n' "$OUT" | grep -q 'mc version enable minio/king-ai-backups'
printf '%s\n' "$OUT" | grep -q 'mc ilm rule add --expire-days 30 minio/king-ai-backups'
printf '%s\n' "$OUT" | grep -q 'mc ilm rule add --expire-days 7 --prefix exports/ minio/king-ai-exports'
echo "minio lifecycle test passed"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `deploy/backup/tests/run_all.sh`
Expected: `minio-lifecycle.sh` 不存在。

- [ ] **Step 3: 实现生命周期脚本**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env MC_ALIAS
require_env BACKUP_BUCKET
require_env EXPORT_BUCKET
require_cmd mc

run() {
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'mc %s\n' "$*"
  else
    mc "$@"
  fi
}

run version enable "$MC_ALIAS/$BACKUP_BUCKET"
run version enable "$MC_ALIAS/$EXPORT_BUCKET"
run ilm rule add --expire-days 30 "$MC_ALIAS/$BACKUP_BUCKET"
run ilm rule add --expire-days 7 --prefix exports/ "$MC_ALIAS/$EXPORT_BUCKET"
run ilm rule ls "$MC_ALIAS/$BACKUP_BUCKET"
run ilm rule ls "$MC_ALIAS/$EXPORT_BUCKET"
```

- [ ] **Step 4: 运行测试**

Run: `deploy/backup/tests/run_all.sh`
Expected: 六个测试全部 `passed`。

- [ ] **Step 5: 编写备份运行手册**

`deploy/backup/README.md` 必须包含以下内容（措辞可润色，但变量、命令、阈值不得改变）：

```markdown
# Backup and Restore Runbook

## 1. 目标
- RPO ≤ 15 分钟，RTO ≤ 1 小时。
- MySQL 每日全量 + binlog 每 5 分钟归档，保留 30 天。
- Milvus collection 级备份，每日一次，与 MySQL 备份 label 对齐。
- Redis 不是业务数据源，不作为恢复依据。

## 2. 最小权限账号
CREATE USER 'king_backup'@'%' IDENTIFIED BY '<random>';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES, PROCESS, REPLICATION CLIENT ON *.* TO 'king_backup'@'%';

## 3. 环境变量
MYSQL_HOST, MYSQL_PORT, MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD, MYSQL_SSL_MODE
BACKUP_OUT_DIR, BACKUP_LABEL, BACKUP_RETENTION_DAYS=30
BACKUP_S3_URI, BACKUP_S3_SSE
MILVUS_HOST, MILVUS_PORT, MILVUS_TOKEN
MILVUS_BACKUP_MINIO_ADDRESS, MILVUS_BACKUP_MINIO_PORT, MILVUS_BACKUP_MINIO_ACCESS_KEY,
MILVUS_BACKUP_MINIO_SECRET_KEY, MILVUS_BACKUP_MINIO_BUCKET, MILVUS_BACKUP_MINIO_ROOT_PATH
MILVUS_BACKUP_BIN

## 4. 调度
# crontab -e
0 2 * * * /opt/king-ai/deploy/backup/mysql-backup.sh
*/5 * * * * /opt/king-ai/deploy/backup/mysql-binlog-archive.sh
30 2 * * * /opt/king-ai/deploy/backup/milvus-backup.sh
0 3 * * 3 /opt/king-ai/deploy/backup/minio-lifecycle.sh

每月第一个周三执行：
DRILL_NAMESPACE=king-ai-drill-$(date +%Y%m%d) \
MYSQL_DUMP=/backup/<label>/king_ai.sql.gz \
MILVUS_BACKUP_NAME=<label> \
... deploy/backup/restore-drill.sh

## 5. 手工备份
source /etc/king-ai/backup.env
deploy/backup/mysql-backup.sh
deploy/backup/mysql-binlog-archive.sh
deploy/backup/milvus-backup.sh

## 6. 恢复
- MySQL：RESTORE_CONFIRM=RESTORE RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE=... restore-mysql.sh
- Milvus：MILVUS_RESTORE_CONFIRM=yes MILVUS_BACKUP_NAME=... restore-milvus.sh
- 先恢复到隔离数据库/collection 后缀，再切换流量。

## 7. 校验
verify.sh 检查 Flyway、用户/空间/会话/消息行数、孤儿消息、抽样消息、API 健康、
知识库 collection 的 count(*)。任何 failed 都以退出码 1 结束。

## 8. 密钥与加密
- 密钥（KING_AI_SECRET、KING_AI_ENCRYPTION_KEY、DB/Redis/MinIO）单独备份，不能与数据备份放在同一位置。
- S3 使用 SSE-KMS/SSE-S3；本地备份目录使用磁盘加密。
- 备份对象存储开启版本化；导出桶 7 天生命周期，备份桶 30 天。

## 9. RPO/RTO 记录
restore-drill.sh 输出 drill-report.json；把实测 RTO、备份时间与 RPO 写入
docs/operations/backup-drill-YYYY-MM-DD.md，并由值班负责人签字。

## 10. 故障排查
- `SHOW BINARY LOGS` 无权限：授予 REPLICATION CLIENT。
- binlog 归档重复：检查 .last-binlog 是否可写。
- Milvus restore 找不到备份：检查 bucket/rootPath 与 label。
- 恢复后 API 不就绪：检查 Flyway 版本、数据库账号权限与 Redis 连接。
```

- [ ] **Step 6: 提交**

```bash
git add deploy/backup/minio-lifecycle.sh deploy/backup/tests/minio_lifecycle_test.sh deploy/backup/README.md
git commit -m "docs: add backup scheduling and object storage lifecycle"
```

---

### Task 12: 部署文档、迁移分配说明与全量验证

**Files:**
- Modify: `docs/DEPLOYMENT.md`
- Modify: `deploy/helm/king-ai/README.md`（补充备份/恢复与 NetworkPolicy 细节）
- Create: `docs/operations/README.md`

**Interfaces:**
- `docs/DEPLOYMENT.md` 保留 Compose 路径，新增 Kubernetes/Helm 与备份恢复章节。
- 文档明确：迁移版本由 G0 子计划分配（V9 内容合规、V10 保留/导出、V11 访问复核）；Helm 不创建数据库迁移文件，只执行镜像内 Flyway。

- [ ] **Step 1: 更新 DEPLOYMENT.md**

在“## 8. 安全清单”之前插入：

```markdown
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

发布顺序：`pre-install/pre-upgrade` 迁移 Job → API/UI 滚动更新。Job 失败时 Helm 停止，不继续滚动。
`persistence.exports.mode=pvc` 需要 ReadWriteMany 存储；只有 RWO 时将 API 限制为单副本，或等待 P2-3 对象存储。
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

Helm 只执行镜像内 Flyway；迁移文件必须随 API 镜像发布。
```

- [ ] **Step 2: 补充 Helm README 的备份与网络策略**

在 `deploy/helm/king-ai/README.md` 追加：

```markdown
## 备份与恢复
- API 的导出 PVC 路径为 `/app/data/exports`，对应 values `persistence.exports`。
- 备份脚本不打包进 chart；使用 `deploy/backup` 与 `deploy/backup/README.md`。
- 启用 NetworkPolicy 时必须为数据库/Redis/Milvus/OTel/对象存储配置 CIDR；公共模型与 MCP 通过 443 放行。
- 建议在 Calico/Cilium 集群验证 NetworkPolicy；kind 默认 CNI 不执行策略，kind values 会关闭它。

## 迁移
- 生产使用 `pre-install/pre-upgrade` Job；`kubectl logs job/king-ai-migrate` 查看结果。
- 内置依赖模式因 hook 运行时数据库尚未就绪，设置 `migration.enabled=false`，由 API 启动时迁移。
```

- [ ] **Step 3: 创建运维记录目录说明**

`docs/operations/README.md`：

```markdown
# Operations Records

- `backup-drill-YYYY-MM-DD.md`：恢复演练记录，必须包含备份 label、MySQL 行数、Milvus 向量数、RTO 秒数、RPO 分钟数、失败项与负责人。
- 每次 Helm 升级记录 revision、迁移版本、回滚 revision 与观察窗口。
- 不记录任何密码、Token、连接串中的凭据。
```

- [ ] **Step 4: 全量验证**

Run:

```bash
mvn -B -ntp clean verify
deploy/helm/king-ai/tests/render_test.sh
deploy/backup/tests/run_all.sh
shellcheck deploy/backup/*.sh deploy/backup/tests/*.sh deploy/helm/king-ai/tests/*.sh
docker build -f king-ai-ui/Dockerfile -t king-ai-ui:verify .
docker build -f king-ai-api/Dockerfile -t king-ai-api:verify .
```

Expected:
- Maven `BUILD SUCCESS`，现有测试全通过，新增 `MigrationRunnerTest` 通过。
- `render tests passed`。
- 六个备份测试全部 `passed`。
- `shellcheck` 无输出。
- 两个镜像构建成功。

如果本机有 kind/Docker，再执行：

```bash
deploy/helm/king-ai/tests/kind-e2e.sh
```

Expected: `kind e2e passed: install, upgrade and rollback`。未执行时必须在交付说明中写“kind 演练未运行”，不得声称已通过。

- [ ] **Step 5: 提交**

```bash
git add docs/DEPLOYMENT.md deploy/helm/king-ai/README.md docs/operations/README.md
git commit -m "docs: document kubernetes deployment and backup drill procedures"
```

---

## 自检记录（执行前必读）

- [ ] Helm 默认 values 不渲染 Secret；`devDependencies.enabled=false` 时四个外部 Secret 名称必须存在。
- [ ] 迁移 Job 使用 `prod,migrate`，不注入管理员 Secret；`AdminBootstrap` 与 `LegacySecretMigrator` 已加 `@Profile("!migrate")`。
- [ ] UI 镜像监听 8080，Compose 映射 `UI_PORT:8080`，Helm `API_UPSTREAM` 指向 `{{ fullname }}-api:8080`。
- [ ] HPA 自定义指标名与 `ChatRuntime` 的 `king.ai.chat.active` 一致（`king_ai_chat_active`）。
- [ ] NetworkPolicy 在 dev 模式下放行内置 MySQL/Redis/Milvus；生产必须填写 CIDR。
- [ ] 备份脚本不把密码放在命令行；`MYSQL_PWD` 仅用于当前进程。
- [ ] `restore-drill.sh` 在 API 0 副本时恢复数据，再扩容；RTO 超过 3600 秒直接失败。
- [ ] `verify.sh` 的 Milvus `count(*)` 查询在目标 Milvus 2.5 上实测；若 REST 响应结构不同，以实测结构修正解析，但不能跳过校验。
- [ ] `docs/operations/` 下不提交包含真实凭据的演练报告。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-10-26-g0-5-6-private-delivery.md`.

1. **Subagent-Driven（推荐）**：每个 Task 派发独立 subagent，任务间做两阶段审查。
2. **Inline Execution**：在当前会话按 Task 批量执行，在迁移 Job、chart 渲染、备份脚本、kind 演练四个检查点暂停复核。

选择后使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`。
