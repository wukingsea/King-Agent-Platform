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
MILVUS_BACKUP_MINIO_SECRET_KEY, MILVUS_BACKUP_MINIO_BUCKET, MILVUS_BACKUP_MINIO_ROOT_PATH MILVUS_BACKUP_MINIO_USE_SSL
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
- MySQL 恢复会读取 dump 同目录的 `manifest.json`（可用 `RESTORE_MANIFEST_FILE` 指定），
  校验 `file` 与 `sha256`：manifest 缺失、文件名不符或校验和不一致都会直接退出。
  仅当明确接受风险时才可用 `RESTORE_SKIP_CHECKSUM=1` 跳过，并在演练记录中注明原因。

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

## 11. 版本要求与已知限制
- mysqldump 需 ≥ 8.0.26（`--source-data=2` 语法）；旧版本会直接报错，或使 manifest 的 binlog 字段为空。
- binlog 归档假定 log-bin 基名为 `mysql-bin`（如 `mysql-bin.000042`）；其他基名的 binlog 会被静默跳过——请统一基名或有意识地调整脚本正则。
- 备份目录中缺少 `manifest.json` 即表示该次备份失败（dump 中断产生截断文件），禁止用于恢复；restore-mysql.sh 默认据此拒绝恢复。
- restore-mysql.sh 在库名字符集校验之后设置 `shopt -s nocasematch`：prod 名守卫与同库守卫按大小写不敏感匹配（宁可错杀，方向为 fail-closed）；`RESTORE_CONFIRM=RESTORE` 本身仍区分大小写。
