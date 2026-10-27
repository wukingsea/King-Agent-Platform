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

{{/* Component resource names must stay within the 63-char limit even when the
     release name is long, so every "fullname-suffix" composition truncates. */}}
{{- define "king-ai.apiName" -}}
{{- printf "%s-api" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "king-ai.uiName" -}}
{{- printf "%s-ui" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "king-ai.dbSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-db" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "externalDatabase.existingSecret is required" .Values.externalDatabase.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.redisSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-redis" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "externalRedis.existingSecret is required" .Values.externalRedis.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.appSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-app" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "appSecret.existingSecret is required" .Values.appSecret.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.adminSecretName" -}}
{{- if .Values.devDependencies.enabled -}}
{{- printf "%s-admin" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "adminSecret.existingSecret is required" .Values.adminSecret.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.dbHost" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.mysql.enabled -}}
{{- printf "%s-mysql" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "externalDatabase.host is required" .Values.externalDatabase.host -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.redisHost" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.redis.enabled -}}
{{- printf "%s-redis" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "externalRedis.host is required" .Values.externalRedis.host -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.milvusUrl" -}}
{{- if and .Values.devDependencies.enabled .Values.devDependencies.milvus.enabled -}}
{{- printf "http://%s:19530" (include "king-ai.milvusName" .) -}}
{{- else -}}
{{- required "externalMilvus.url is required" .Values.externalMilvus.url -}}
{{- end -}}
{{- end -}}

{{- define "king-ai.milvusName" -}}
{{- printf "%s-milvus" (include "king-ai.fullname" .) | trunc 63 | trimSuffix "-" -}}
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
